# POST /api/interviews：创建文字面试完整函数调用链逐行解析

> 以当前工作区代码为准。创建请求会在 HTTP 线程中同步调用 Python 初始化面试会话；并不经过 RabbitMQ。Java 先持久化 CONFIGURED 会话，Python 初始化成功后再激活会话，失败则写 FAILED。

## 1. 接口定义

### 1.1 功能与作用

该接口按当前用户的“当前简历”创建一场文字面试。调用方提供目标岗位、时长、难度、面试方向及可选 JD/自定义分类；Java 校验简历归属和当前版本，选择系统/用户知识库，创建会话后请求 Python Agent 生成首题和初始状态。成功时返回可直接渲染的面试视图。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 方法与路径 | `POST /api/interviews` |
| Controller | `InterviewController.start`，`java-backend/src/main/java/com/interviewguide/interview/controller/InterviewController.java:41-45` |
| 请求体 | `StartInterviewRequest`，受 `@Valid` 校验；包含 resumeId、targetRole、时长、难度、方向、JD、分类 |
| 身份 | `X-User-Id`，由 Controller 先调用 `UserIdentityResolver.require` |
| 同步边界 | Java 调用 Python `POST /v1/agent/sessions/initialize`，仅 Python 成功后返回 ACTIVE 会话 |
| 持久化 | MyBatis：interview_sessions；失败写 FAILED；无 RabbitMQ 消息 |
| Redis | 本接口仅经过 Redis 限流；Python 侧会用其专属 Redis 维护 Agent 会话/进度缓存。|

### 1.3 前端入口

`frontend/src/pages/InterviewPage.tsx:121-146` 的 `startInterview` 校验本地输入后调用 `interviewApi.createSession`。`frontend/src/api/interview.ts:60-71` 将字段映射为 JSON POST，并为初始化设置 180 秒超时。

## 2. 函数调用链

```text
InterviewPage.startInterview -> interviewApi.createSession -> request.post
  -> Axios request interceptor -> currentUserId / createClientId
  -> RequestIdFilter.doFilterInternal -> normalize
  -> SimpleRateLimitFilter.doFilterInternal -> JavaRedisStore.incrementInFixedWindow
  -> IdempotencyFilter.shouldNotFilter
     ->（带 X-Idempotency-Key）doFilterInternal -> JavaRedisStore.acquire
  -> InterviewController.start -> UserIdentityResolver.require -> InterviewService.start
     -> ownedResume -> ResumeRepository.findById -> CandidateRepository.findById
     -> InterviewKnowledgeBaseSelectionService.selectForUser
     -> normalizeDifficulty
     -> InterviewSessionPersistenceService.createConfigured
        -> InterviewSessionEntity.configure -> InterviewSessionRepository.save (MyBatis)
     -> HttpPythonAgentClient.initialize -> AgentCallExecutor.execute -> post/validateRequest
        -> Python initialize_session -> InterviewAgentService.initialize_session
           -> SessionRepository / Redis cache -> InterviewAgent.initialize
     -> requireMatchingResponse -> requireSuccess / firstNonBlank
     -> InterviewSessionPersistenceService.activate -> applyAgentResponse / applyCounters / number
        -> InterviewSessionRepository.save
     -> InterviewSessionPersistenceService.load -> InterviewService.toView / parseFinalEvaluation
  -> ApiResult.success -> Axios response interceptor -> interviewApi.toSession -> initSession
```

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `InterviewPage.startInterview`

**文件与行号：** `frontend/src/pages/InterviewPage.tsx:121-146`。

1. 第 121 行声明异步启动函数。第 122 行设置创建中状态；第 123 行清空旧错误。
2. 第 125 行进入 try。第 126-128 行检查简历、难度、岗位和时长是否存在，缺少任一项就抛本地 Error，不发 HTTP 请求。
3. 第 129-137 行调用 `interviewApi.createSession`，逐项传入业务字段。第 139 行把返回会话交给 `initSession`，由页面切换到首题。
4. 第 140-142 行把错误转换为可显示信息并记录控制台。第 143-145 行 finally 清除创建中状态；第 146 行结束。

#### 3.1.2 `interviewApi.createSession` 与 `toSession`

**文件与行号：** `frontend/src/api/interview.ts:60-71、37-51`。

1. 第 60 行声明异步创建函数。第 61 行 POST `/api/interviews`。
2. 第 62-68 行将前端输入按 Java `StartInterviewRequest` 字段名序列化：未选择方向/JD 写 `null`，分类数组原样传递。第 69 行设置 180 秒 timeout，覆盖普通请求默认超时。
3. 第 70 行将 Java `InterviewView` 交给 `toSession`。该函数第 37 行从 view 中读取当前题和既有问题；第 47-50 行用展开运算保留所有字段，计算当前题索引并返回 `questions`；第 51 行结束。

#### 3.1.3 请求身份、追踪与响应函数

**文件与行号：** `frontend/src/api/request.ts:47-72、123-164`。

1. `createClientId` 第 47-49 行以 `crypto.randomUUID` 或兼容拼接生成客户端 ID；`currentUserId` 第 52-57 行从 localStorage 读取或首次保存该 ID。
2. 请求拦截器第 64-72 行初始化 headers，兼容 AxiosHeaders/普通对象，写 `X-User-Id` 与新的 `X-Request-Id` 并返回配置。
3. `request.post` 位于同文件 `165` 行之后，调用 Axios POST 并返回 data。成功拦截器第 123-135 行只在 Java `code=200` 时解包 data；失败拦截器第 136-154 行将网络、Blob、统一错误体和 HTTP 错误转换为 `ApiRequestError`。

### 3.2 Java Web、校验与会话创建函数

#### 3.2.1 `RequestIdFilter`、`SimpleRateLimitFilter` 与 `IdempotencyFilter`

**文件与行号：** `infrastructure/web/RequestIdFilter.java:23-41`、`infrastructure/ratelimit/SimpleRateLimitFilter.java:48-82`、`infrastructure/idempotency/IdempotencyFilter.java:41-96`，根目录均为 `java-backend/src/main/java/com/interviewguide/`。

1. RequestId 过滤器第 25 行调用 `normalize`，第 26-28 行保存 attribute、回传头、写 MDC，第 29-33 行放行并 finally 清理；`normalize` 第 36-41 行拒绝非法 ID 并新建 UUID。
2. 限流函数第 54-58 行按 IP/URI/分钟调用 `JavaRedisStore.incrementInFixedWindow`。Redis 函数第 31-39 行 INCR、首次设置 65 秒 TTL，异常返回空 Optional；第 60-67 行随即回退 ConcurrentHashMap；第 69-79 行超限返回 429。
3. 幂等 `shouldNotFilter` 第 42-44 行仅让带键的写请求进入。进入后 `doFilterInternal` 第 50-84 行校验键、调用 `acquire` 进行 Redis/本机原子占位，重复调用 `writeConflict` 第 88-95 行返回 409；4xx 或异常会释放占位。

#### 3.2.2 `InterviewController.start`、身份与请求对象

**文件与行号：** `java-backend/src/main/java/com/interviewguide/interview/controller/InterviewController.java:41-45`，`common/security/UserIdentityResolver.java:14-19`。

1. 第 41 行映射 POST。第 42 行以 `@Valid @RequestBody` 绑定请求，Spring 在进入函数前执行 record 字段约束；第 43 行绑定可选头。
2. 第 44 行先调用 `identity.require(userId)`，再调用服务并用 `ApiResult.success` 包装；第 45 行结束。`require` 第 15-17 行拒绝空/空白 ID，第 18 行 strip，第 19 行返回。
3. `ApiResult.success` 位于 `common/web/dto/ApiResult.java:3-6`：第 4 行接收泛型数据，第 5 行创建 code 200/message success 的 record，第 6 行结束。

#### 3.2.3 `InterviewService.start`、`ownedResume` 与难度规范化

**文件与行号：** `java-backend/src/main/java/com/interviewguide/interview/service/InterviewService.java:53-87、174-201`。

1. 第 54 行调用 `ownedResume`；该函数第 175-183 行按 ID 查 Resume Mapper、缺失抛 `RESUME_NOT_FOUND`，再查 Candidate Mapper 并比较 userId，越权抛 `RESUME_ACCESS_DENIED`。
2. 第 55-56 行再次取候选人以获得对象；第 57-60 行要求 resume ID 等于 `currentResumeId`，否则阻止从旧版本开启面试。
3. 第 61 行调用 `knowledgeBaseSelection.selectForUser`；第 62 行调用 `normalizeDifficulty`；第 63 行生成 session UUID；第 64-67 行构造会话实体并持久化 CONFIGURED 配置。
4. 第 69 行生成本次 Python runId。第 70 行开始保护区；第 71-79 行构造带协议版本、requestId、runId、用户/会话身份和候选人快照的初始化请求。
5. 第 80 行验证 Python 响应；第 81 行激活本地会话；第 82 行重新加载并转换返回。第 83-85 行在任何运行时错误下标记本地会话 FAILED 后重抛；第 86-87 行结束。
6. `normalizeDifficulty` 第 193-200 行将输入去空白、转小写并限制为项目支持值；非法值抛业务异常，避免 Python 收到不可识别难度。

#### 3.2.4 `InterviewKnowledgeBaseSelectionService.selectForUser`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/interview/service/InterviewKnowledgeBaseSelectionService.java:29-`。

1. 该函数按调用者 userId 查询可用于面试的知识库，将系统库和用户库分成 `Selection` record 的两个 ID 列表。
2. `InterviewService.start` 第 61 行将两个列表写入 Python 初始化快照，使 Python RAG 只在授权知识库范围内检索。查询为空时返回空列表，不阻止没有知识库的基础面试。

#### 3.2.5 `InterviewSessionPersistenceService.createConfigured` 与实体配置

**文件与行号：** `java-backend/src/main/java/com/interviewguide/interview/service/InterviewSessionPersistenceService.java:36-41`。

1. 第 36 行声明事务。第 37-38 行接收新实体、方向和难度。
2. 第 39 行调用 `InterviewSessionEntity.configure` 写入方向、难度和初始状态相关字段。第 40 行调用 MyBatis `sessionRepository.save` 插入会话并返回实体；第 41 行结束。
3. Repository XML 位于 `java-backend/src/main/resources/mapper/interview/InterviewSessionRepository.xml`；当前实现是 MyBatis insert/upsert，不是 JPA 脏检查。

### 3.3 Java 调 Python 与 Python 初始化函数

#### 3.3.1 `HttpPythonAgentClient.initialize`、`post`、`validateRequest` 与重试

**文件与行号：** `java-backend/src/main/java/com/interviewguide/pythonagent/mapper/HttpPythonAgentClient.java:40-96`，`infrastructure/reliability/AgentCallExecutor.java:22-43`。

1. `initialize` 将请求交给 `AgentCallExecutor.execute`，并在其 Supplier 内 POST `/v1/agent/sessions/initialize`。
2. `post` 第 65-79 行先 `validateRequest`，通过 RestClient 发送/反序列化，拒绝空响应，并把结构化错误、HTTP 错误和网络错误转成带 retryable 标志的 `PythonAgentException`。`parseStructuredError` 第 82-87 行只接受带 error 的协议体；`validateRequest` 第 89-96 行把 Bean Validation 失败变成不可重试调用错误。
3. `execute` 第 22-34 行最多按配置尝试；第 27-30 行只重试 retryable Python 异常。`sleepBeforeRetry` 第 36-43 行等待退避；中断时恢复线程中断标志并抛不可重试异常。

#### 3.3.2 Python `initialize_session` 与 Agent 服务

**文件与行号：** `python-agent/app/api/application.py:73-91`，以及该文件调用的 `python-agent/app/agents/interview/service.py` 初始化函数。

1. FastAPI 路由第 73 行映射 `/v1/agent/sessions/initialize`，第 74 行接收 Pydantic `AgentInitializationRequest`。
2. 第 75 行保存请求上下文；第 76 行解析 InterviewAgentService 并 await `initialize_session`；后续行把会话状态、首题、stage、版本和计数封装为 code 100 的 `AgentResponse`，并回显 userId/sessionId/runId。
3. InterviewAgentService 负责加载或创建会话、校验 runId 幂等、调用项目 Interview Agent 生成首题，并将持久化会话/进度写入 PostgreSQL 与 Python 专属 Redis。Redis 失败时读取/保存会回退持久化存储，不能把 Java 会话初始化伪装为成功。

#### 3.3.3 `requireMatchingResponse`、激活与视图转换

**文件与行号：** `InterviewService.java:202-257`，`InterviewSessionPersistenceService.java:43-55`。

1. `requireSuccess` 第 202-217 行检查响应存在且 code 在 100–199；失败时用 `firstNonBlank` 第 219-224 行选择远端错误或后备错误码并抛异常。
2. `requireMatchingResponse` 第 226-237 行先调用 `requireSuccess`，再逐项比对 userId、sessionId、runId；身份不一致抛不可重试协议错误，防止串会话回写。
3. `activate` 第 43-55 行事务读取待更新会话，第 46 行应用首题、状态、Python stateVersion 和 stage；第 47-52 行若有 output 则用 `number` 第 122-124 行读取四项计数；第 53 行推进 Java stateVersion；第 54 行 Mapper 保存。
4. `load` 读取会话后，`toView` 第 238-248 行复制会话字段并调用 `parseFinalEvaluation`；该函数第 249-257 行将空/格式错误 JSON 回退空 Map。创建接口随后返回这个视图。

## 4. 主流构建分析

当前模式是同步 RPC 初始化：优点是客户端收到成功即已有首题和可交互会话，链路简单；缺点是 HTTP 线程受 Python 模型响应时间约束，即使已创建 CONFIGURED 记录也可能因超时标记 FAILED，峰值时会增加 Java 连接与线程压力。

主流替代方式是异步初始化：Java 在事务中创建会话和 outbox 事件，消费者初始化 Python，前端轮询 `agent-status` 或通过 WebSocket/SSE 获得 ACTIVE/FAILED。优点是削峰、可重试和跨服务投递可靠；缺点是用户首次进入必须等待状态、需新增任务状态/补偿和前端等待交互。

本项目当前以“立即给出首题”为体验目标，保留同步方式较适配。若并发上升，可先配置更严格的连接/超时/熔断并保持当前 FAILED 补偿；再引入 outbox、初始化任务 ID 和状态推送。无论哪种方式，都应保持 runId 和 Java/Python 双方的 user/session/run 回显校验。
