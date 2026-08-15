# GET /api/interviews：查询当前用户面试列表的完整函数调用链

> 对应接口汇总第 9 项。源码证明该请求仅查询 Java 数据库并投影会话字段，不调用 RabbitMQ、Java PythonAgentClient 或 Python `/v1/**` 服务。

## 1. 接口定义

### 1.1 功能与作用

接口按 `X-User-Id` 查询当前用户的所有文字面试会话，按创建时间倒序返回。每条记录包含会话、简历、候选人标识、面试方向、难度、题量、状态、Agent 状态版本、当前题、阶段、计数、最终评价和时间。它不读取逐题 turn，不写数据库，也不触发或查询 Python Agent。

### 1.2 基本信息

| 项目 | 内容 |
| --- | --- |
| HTTP 方法 | GET |
| 路径 | `/api/interviews` |
| Controller | `InterviewController.list` |
| Service | `InterviewService.list` |
| 成功响应 | `ApiResult<List<InterviewView>>` |
| 排序 | `createdAt DESC` |
| 授权 | 只能查询 X-User-Id 对应会话 |
| Python 调用 | 无 |

## 2. 函数调用链

~~~text
InterviewHistoryPage.load
 -> interviewApi.listSessions
 -> request.get
 -> Axios 请求拦截器 → currentUserId → createClientId（首次）/createClientId("web")
 -> RequestIdFilter.doFilterInternal → normalize
 -> SimpleRateLimitFilter.doFilterInternal
 -> InterviewController.list
    -> UserIdentityResolver.require
    -> InterviewService.list
       -> InterviewSessionPersistenceService.list
          -> InterviewSessionRepository.findByUserIdOrderByCreatedAtDesc
       -> InterviewService.toView（每条会话）
          -> InterviewService.parseFinalEvaluation
          -> InterviewSessionEntity 的各 getter
 -> ApiResult.success
 -> Axios 响应拦截器
 -> InterviewHistoryPage.setSessions/setError/setLoading
~~~

到 `InterviewSessionRepository.findByUserIdOrderByCreatedAtDesc` 得到数据后，后续均是内存 DTO 投影；当前代码路径不存在 `pythonAgentClient.*` 调用。

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `InterviewHistoryPage.load`

文件：`frontend/src/pages/InterviewHistoryPage.tsx:34-44`。

1. 第 34 行以 `useCallback` 定义异步加载函数，避免 useEffect 因函数身份变化重复执行。
2. 第 35 行 `setLoading(true)` 进入加载状态。
3. 第 36-38 行 try 中 await `interviewApi.listSessions()`，成功后把完整数组写到 sessions，并清空旧 error。
4. 第 39-41 行 catch 将 Error.message 或默认文案写入 error。
5. 第 42-43 行 finally 无论成功失败都 `setLoading(false)`；第 44 行结束。
6. 第 46-48 行的 useEffect 调用 `void load()`，是页面挂载时进入本接口的实际入口。

#### 3.1.2 `interviewApi.listSessions` 与 `request.get`

文件：`frontend/src/api/interview.ts:55-58`；`frontend/src/api/request.ts:157-160`。

1. listSessions 第 56 行声明返回 TextSessionMeta 数组；第 57 行调用 `request.get<InterviewView[]>('/api/interviews')`；第 58 行结束。
2. request.get 第 158 行接收 URL/配置；第 159 行调用共享 Axios instance.get，并取响应 data；第 160 行结束。
3. 未设置自定义 timeout，因此 Axios 使用 instance 在 request.ts:44 创建时配置的 60000ms。

#### 3.1.3 `createClientId`、`currentUserId` 与请求拦截器

文件：`frontend/src/api/request.ts:47-73`。

1. createClientId 第 47 行定义 ID 生成函数；第 48 行优先 crypto.randomUUID；第 49 行降级拼接 prefix、时间、随机十六进制值。
2. currentUserId 第 52-58 行从 localStorage 取稳定用户 ID；第 54 行存在非空值即返回；第 55-57 行首次生成、保存并返回。
3. 请求拦截器第 64 行注册；第 65 行保证 headers；第 66-69 行内部 setHeader 兼容 AxiosHeaders/普通对象。
4. 第 70 行写 `X-User-Id`，第 71 行用 createClientId("web") 写每次不同的 X-Request-Id，第 72 行返回 config。

#### 3.1.4 响应与错误解析函数

文件：`frontend/src/api/request.ts:75-155`。

1. isRecord 第 75-77 行排除 null、数组和基本值；stringValue 第 79-81 行只接受非空字符串。
2. parseApiError 第 83-99 行提取嵌套 error、code、message、retryable、status、requestId 等并构造 ApiRequestError。
3. 成功拦截器第 123-135 行发现 ApiResult 的 code=200 时，在第 128 行把 response.data 替换为外层 data，因此 listSessions 获得数组而非 `{code,message,data}`。
4. decodeErrorData 第 101-108 行仅解析 JSON Blob；transportError 第 110-121 行把超时和无连接变为可重试错误。
5. 失败拦截器第 136-155 行处理 AxiosError、解析服务错误或构造通用 HTTP 错误，最后交给 load 的 catch。

### 3.2 Java Web 入口函数

#### 3.2.1 `RequestIdFilter.doFilterInternal` 与 `normalize`

文件：`java-backend/src/main/java/com/interviewguide/infrastructure/web/RequestIdFilter.java:23-41`。

1. doFilterInternal 第 25 行读取 X-Request-Id 并调用 normalize；第 26-28 行写 request attribute、响应头和 MDC。
2. 第 29-30 行进入下一个过滤器；第 31-33 行 finally 清理 MDC。
3. normalize 第 36-41 行只保留非 null、长度≤128且符合正则的 ID；非法值第 40 行生成 UUID。

#### 3.2.2 `SimpleRateLimitFilter.doFilterInternal`

文件：`java-backend/src/main/java/com/interviewguide/infrastructure/ratelimit/SimpleRateLimitFilter.java:38-61`。

1. 第 40-43 行只跳过 health/actuator，本路径继续限流。
2. 第 44-47 行按远端地址、URI、当前分钟创建或复用 Window；第 48 行原子递增计数。
3. 超限第 49-58 行写 429、Retry-After 和 ApiErrorResponse；未超限第 60 行进入 MVC Controller。

#### 3.2.3 `InterviewController.list` 与 `UserIdentityResolver.require`

文件：`java-backend/src/main/java/com/interviewguide/interview/controller/InterviewController.java:47-51`；`common/security/UserIdentityResolver.java:14-19`。

1. 第 47 行类级路径加 @GetMapping 匹配 GET `/api/interviews`。
2. 第 48-49 行声明 ApiResult 列表和可缺省 X-User-Id；第 50 行先 require 再 service.list，最后 ApiResult.success 包装。
3. require 第 15-17 行拒绝 null/blank 用户 ID；第 18 行 strip；第 19 行返回 owner。头缺失时不会进入数据库查询。

### 3.3 Java 列表查询和投影函数

#### 3.3.1 `InterviewService.list`

文件：`java-backend/src/main/java/com/interviewguide/interview/service/InterviewService.java:130-132`。

1. 第 130 行接收已由 Controller 验证的 userId。
2. 第 131 行调用 persistence.list(userId)，对返回 Stream 逐条应用 `this::toView`，再 `toList()` 形成不可变结果列表。
3. 第 132 行结束。这里没有调用 ownedSession，因为 Repository 查询本身已限定 userId。

#### 3.3.2 `InterviewSessionPersistenceService.list`

文件：`java-backend/src/main/java/com/interviewguide/interview/service/InterviewSessionPersistenceService.java:166-168`。

1. 第 166 行定义只读 service 函数。
2. 第 167 行调用项目声明的 `sessionRepository.findByUserIdOrderByCreatedAtDesc(userId)`；Spring Data 按方法名生成按 user_id 过滤、created_at 倒序查询。
3. 第 168 行结束。没有 save、delete、turn 查询或 Python HTTP。

#### 3.3.3 `InterviewService.toView`

文件：`java-backend/src/main/java/com/interviewguide/interview/service/InterviewService.java:238-247`。

1. 第 238 行接收一条 InterviewSessionEntity。
2. 第 239 行先调用 parseFinalEvaluation，把 finalEvaluationJson 转为 Map 或 null。
3. 第 240-246 行依 InterviewView 构造参数顺序读取 sessionId、用户、候选人、简历、JD、方向、难度、题量、状态名、Agent 版本、当前题、阶段、四项计数、最终评价、创建/更新时间。
4. 第 247 行结束。所有 getter 均仅读字段，不会触发懒加载或写状态。

#### 3.3.4 `InterviewService.parseFinalEvaluation`

文件：`java-backend/src/main/java/com/interviewguide/interview/service/InterviewService.java:249-255`。

1. 第 249 行接收数据库 JSON 字符串。
2. 第 250 行对 null/blank 直接返回 null，未完成面试不会产生解析错误。
3. 第 251 行用 ObjectMapper.readValue 和 TypeReference 反序列化 Map。
4. 第 252-254 行捕获任意解析异常并返回 null，防止单条历史损坏阻塞全列表；第 255 行结束。

#### 3.3.5 `InterviewSessionEntity` getter

文件：`java-backend/src/main/java/com/interviewguide/interview/domain/InterviewSessionEntity.java:118-137`。

1. getId、第 118 行；getUserId、第 119 行；getCandidateId、第 120 行；getResumeId、第 121 行；getJdId、第 122 行，分别单句返回标识字段。
2. getInterviewDirection、第 123 行；getDifficulty、第 124 行；getTotalQuestions、第 125 行，返回配置及题量。
3. getIssuedQuestionCount、第 126 行；getPrimaryQuestionCount、第 127 行；getTotalPrimaryQuestionCount、第 128 行；getFollowupCount、第 129 行，返回 Agent 写回的统计值。
4. getFinalEvaluationJson、第 130 行；getStatus、第 131 行；getAgentStateVersion、第 133 行；getCurrentQuestion、第 134 行；getCurrentStage、第 135 行；getCreatedAt、第 136 行；getUpdatedAt、第 137 行，均是无副作用的单句 return。

#### 3.3.6 `ApiResult.success`

文件：`java-backend/src/main/java/com/interviewguide/common/web/dto/ApiResult.java:3-6`。

1. 第 4 行声明泛型静态工厂；第 5 行以 code=200、message=success 和输入列表构造 record；第 6 行结束。
2. Jackson 序列化后由前端响应拦截器解包；该函数不访问数据库或 Python。

### 3.4 Python 调用边界和审核

1. `InterviewController.list` 的唯一 Service 调用是 `InterviewService.list`（Controller.java:50）。
2. `InterviewService.list` 的唯一下游是 `sessionPersistence.list` 与本地 `toView`（InterviewService.java:131）。
3. `InterviewSessionPersistenceService.list` 的唯一下游是 `sessionRepository.findByUserIdOrderByCreatedAtDesc`（PersistenceService.java:167）。
4. 以上路径没有 `PythonAgentClient`、`HttpPythonAgentClient`、`RabbitTemplate` 或 `/v1/agent` 字符串，因此本接口从 Java 到 Python 的调用次数为零。

## 4. 审核结论

1. 已按源码覆盖前端加载、请求封装、过滤器、Controller、身份校验、受限 Repository 查询、DTO 投影和响应解包。
2. 每个可达项目函数均标注文件和行号，并按语句解释。
3. 已确认该接口仅为 Java 本地查询，未虚构 Python 下游调用。
