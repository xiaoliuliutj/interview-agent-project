# POST /api/interviews：创建文字面试的完整函数调用链

> 对应接口汇总第 8 项。本文以当前工作区源码为准，区分浏览器同步请求、Java 同步调用 Python 初始化端点，以及 Python 内部创建计划和记忆的过程；本接口不会投递 RabbitMQ。

## 1. 接口定义

### 1.1 功能与作用

该接口以当前用户的当前简历、目标岗位、时长、难度、面试方向和可选 JD 创建一场文字面试。Java 先校验简历归属和当前版本，验证系统知识库已经完成向量索引，保存 `INITIALIZING` 会话；随后在同一 HTTP 请求内调用 Python `/v1/agent/sessions/initialize`。Python 选择技能、生成并校验面试计划、创建首条开场问题、初始化长期记忆并持久化 Agent 会话。Java 收到匹配响应后把会话改为 `ACTIVE` 并返回首题。

### 1.2 基本信息

| 项目 | 内容 |
| --- | --- |
| HTTP 方法 | POST |
| Java 路径 | `/api/interviews` |
| Controller | `InterviewController.start` |
| 请求体 | `StartInterviewRequest` JSON |
| 必要字段 | resumeId、targetRole、interviewDurationMinutes、desiredDifficulty |
| 成功响应 | `ApiResult<InterviewView>`，状态通常为 ACTIVE |
| Java→Python | POST `/v1/agent/sessions/initialize` |
| 异步消息 | 无；Python 初始化在本次浏览器请求尚未返回时同步完成 |

## 2. 函数调用链

~~~text
InterviewPage.useEffect → InterviewPage.startInterview
 -> interviewApi.createSession
    -> request.post → Axios 请求拦截器
       -> currentUserId（首次时 createClientId）→ createClientId("web")
 -> RequestIdFilter.doFilterInternal → normalize
 -> SimpleRateLimitFilter.doFilterInternal
 -> InterviewController.start
    -> UserIdentityResolver.require
    -> InterviewService.start
       -> ownedResume → ResumeRepository.findById → CandidateRepository.findById
       -> ResumeEntity/CandidateEntity getter
       -> InterviewKnowledgeBaseSelectionService.selectForUser
          -> KnowledgeBaseRepository 两次查询 → KnowledgeBaseEntity.getId
       -> normalizeDifficulty
       -> InterviewSessionEntity 构造 → configure
       -> InterviewSessionPersistenceService.createConfigured → sessionRepository.save
       -> HttpPythonAgentClient.initialize → AgentCallExecutor.execute → post → validateRequest
       -> Python initialize_session
          -> _remember_request_context → _resolve_service
          -> InterviewAgentService.initialize_session
             -> PostgresInterviewSessionRepository.get
             -> _run_interview_node → _report_progress → InterviewPlanner.create_plan
                -> SkillRegistry.available_for_interview/select_for_interview/selection_catalog/resolve_for_interview
                -> PromptLoader.render/load/_resolve
                -> StructuredOutputInvoker.invoke → _invoke_model → AsyncRetryExecutor.execute → model.ainvoke
                -> _validate/_content_as_text/_strip_json_fence
                -> _coverage_matrix/_missing_coverage
             -> InterviewWorkflow.opening_message → _register_question
             -> MemoryService.initialize_user_memory → LongTermMemoryRepository.get/create 或 save
             -> PostgresInterviewSessionRepository.create
          -> _success_response
       -> requireMatchingResponse → requireSuccess
       -> InterviewSessionPersistenceService.activate
          -> requiredForUpdate → InterviewSessionEntity.applyAgentResponse/applyCounters → save
       -> InterviewSessionPersistenceService.load → InterviewService.toView → parseFinalEvaluation
 -> ApiResult.success → Axios 响应拦截器
 -> interviewApi.toSession → InterviewPage.initSession
~~~

## 3. 函数解析

### 3.1 前端入口与请求函数

#### 3.1.1 `InterviewPage.startInterview`

文件：`frontend/src/pages/InterviewPage.tsx:121-146`。

1. 第 121 行定义异步创建函数；第 122-123 行设置创建中状态并清空旧错误。
2. 第 126 行检查 `resumeId`、`difficulty`、`targetRole`、`interviewDurationMinutes`；任一缺失就在第 127 行抛本地错误，不访问 Java。
3. 第 129-137 行调用 `interviewApi.createSession`，把页面的简历、岗位、时长、方向、难度、JD 和自定义分类显式映射到请求对象。
4. 第 139 行将后端返回的会话交给 `initSession`；该函数仅更新 React 状态，不再创建会话。
5. 第 140-142 行将 HTTP/业务错误经 `getErrorDisplayMessage` 转为可显示文本并记录控制台；第 143-145 行 finally 清除创建中状态。

#### 3.1.2 `interviewApi.createSession` 与 `toSession`

文件：`frontend/src/api/interview.ts:41-51、60-71`。

1. `createSession` 第 60 行声明输入 CreateInterviewRequest、返回 InterviewSession。
2. 第 61 行调用 `request.post('/api/interviews', body, {timeout:180000})`；第 62-68 行逐字段构造与 Java `StartInterviewRequest` 对应的 JSON，`?? null` 保留可选 JD/方向的显式空值。
3. 第 69 行将本接口等待时间设为三分钟，覆盖 Python 初始计划所需时间；第 70 行调用 `toSession(view)`；第 71 行结束。
4. `toSession` 第 41 行定义投影函数；第 42 行把已有 turns 映射为前端问题；第 43-45 行在 ACTIVE/PAUSED 且存在 currentQuestion 时补入当前未回答问题。
5. 第 46-50 行展开 Java view、计算当前题下标并返回前端 InterviewSession。创建响应没有历史 turns，因此只保留 Python 返回的首题。

#### 3.1.3 `request.post`、身份函数与请求拦截器

文件：`frontend/src/api/request.ts:47-73、161-163`。

1. `request.post` 第 161 行声明泛型 POST；第 162 行调用共享 Axios instance 并返回 `response.data`；第 163 行结束。
2. `createClientId` 第 47 行定义 ID 工厂；第 48 行优先 `crypto.randomUUID`，第 49 行在旧环境拼接前缀、时间和随机数。
3. `currentUserId` 第 52-58 行从 localStorage 读取稳定用户 ID；空缺时第 55 行生成、第 56 行保存、第 57 行返回。
4. 请求拦截器第 64-73 行确保 headers、定义兼容 AxiosHeaders 的 `setHeader`、第 70 行写 X-User-Id、第 71 行写独立 X-Request-Id，最后返回 config。

#### 3.1.4 Axios 响应拦截器

文件：`frontend/src/api/request.ts:75-155`。

1. `isRecord` 第 75-77 行只接受非 null、非数组对象；`stringValue` 第 79-81 行只提取非空字符串。
2. `parseApiError` 第 83-99 行读取嵌套 error 或外层 code，以状态、requestId、stage、retryable 构造 ApiRequestError。
3. 成功回调第 123-135 行只对带 code 的 JSON 包装体处理；第 127-129 行把 code=200 的 `data` 解包，故 `createSession` 得到 InterviewView。
4. 失败回调第 136-155 行对无响应调用 `transportError`，有响应时调用 `decodeErrorData`、`parseApiError` 或构造通用 HTTP 错误；异常回到 startInterview 的 catch。

### 3.2 Java Web、授权与会话创建

#### 3.2.1 `RequestIdFilter.doFilterInternal`、`normalize` 与限流函数

文件：`java-backend/src/main/java/com/interviewguide/infrastructure/web/RequestIdFilter.java:23-41`；`infrastructure/ratelimit/SimpleRateLimitFilter.java:38-61`。

1. RequestIdFilter 第 25 行读取头并调用 `normalize`；第 36-41 行只接受长度不超过 128 的允许字符，否则生成 UUID。
2. 第 26-28 行把 ID 放入 request attribute、响应头和 MDC；第 30 行进入后续过滤器；第 31-33 行 finally 清理 MDC。
3. RateLimitFilter 第 40-43 行不放行本路径；第 44-47 行按 IP、URI、分钟创建/复用计数窗口；第 48 行原子递增。
4. 超限时第 49-58 行返回 429 和结构化错误；正常时第 60 行放行至 Controller。

#### 3.2.2 `InterviewController.start` 与 `UserIdentityResolver.require`

文件：`java-backend/src/main/java/com/interviewguide/interview/controller/InterviewController.java:41-45`；`common/security/UserIdentityResolver.java:14-19`。

1. 第 41 行 `@PostMapping` 与类级 `/api/interviews` 构成路径；第 42 行的 `@Valid` 先让 Spring 校验 JSON DTO。
2. 第 43 行读取可缺省身份头；第 44 行先调用 `identity.require(userId)` 再调用 service，并用 ApiResult.success 包装结果。
3. require 第 15-17 行拒绝 null/blank，第 18 行 strip，第 19 行返回规范 owner。

#### 3.2.3 `InterviewService.start`

文件：`java-backend/src/main/java/com/interviewguide/interview/service/InterviewService.java:53-87`。

1. 第 54 行调用 `ownedResume`；第 55-56 行查候选人，缺失抛 CANDIDATE_NOT_FOUND；第 57-60 行要求简历 ID 等于 candidate.currentResumeId。
2. 第 61 行调用知识库选择；第 62 行标准化难度；第 63 行生成 Java 会话 UUID。
3. 第 64-67 行构造 `InterviewSessionEntity`，并由 persistence 保存带方向/难度的 INITIALIZING 会话。
4. 第 69 行生成本次 Python runId；第 71-79 行构造 AgentInitializeRequest 和 CandidateSnapshot，传入简历正文、JD、岗位、时长、分类及两类已就绪知识库 ID。
5. 第 80 行验证 Python 响应的成功性和三项关联 ID；第 81 行写回 ACTIVE 状态；第 82 行 load 后 toView 返回。
6. 第 83-86 行若 HTTP/Python/验证任一抛 RuntimeException，先 markFailed 再重新抛出，浏览器不会得到成功会话。

#### 3.2.4 `ownedResume`、`normalizeDifficulty`、`requireSuccess`、`requireMatchingResponse`

文件：`InterviewService.java:174-236`。

1. ownedResume 第 175-182 行按主键查简历、按 candidateId 查候选人，并在第 179-180 行比较 userId；每个缺失或越权分支均抛 BusinessException。
2. normalizeDifficulty 第 193-200 行对输入 strip/upper；第 195-197 行把 EASY/JUNIOR、MEDIUM/MID、HARD/SENIOR 映射为三个规范值，第 198 行拒绝其他值。
3. requireSuccess 第 202-215 行拒绝 null、非 1xx 或非 2xx AgentResponse；第 204-210 行提取错误消息、类型、可重试性和阶段，第 211-215 行抛带关联信息的 BusinessException。
4. requireMatchingResponse 第 226-236 行先 requireSuccess，再比较 response 的 userId、sessionId、runId；不一致时抛 `AGENT_RESPONSE_MISMATCH`。

#### 3.2.5 知识库选择函数

文件：`InterviewKnowledgeBaseSelectionService.java:29-44`。

1. 第 30-33 行拒绝未配置的系统知识库 ID。
2. 第 34-36 行调用 `findByIdInAndVectorStatus(...,"COMPLETED")`，并逐个 `KnowledgeBaseEntity.getId` 得到已就绪系统 ID。
3. 第 37-40 行要求结果数与配置数相等，避免把未索引系统知识库交给 Python。
4. 第 41-42 行查询当前 owner 已完成索引的用户知识库并取 ID；第 43 行构造 Selection；第 44 行结束。

#### 3.2.6 Java 会话实体与持久化函数

文件：`InterviewSessionEntity.java:48-108`；`InterviewSessionPersistenceService.java:37-54、183-191`。

1. 实体构造第 48-65 行保存 id、用户、候选人、简历和题量，将 issuedQuestionCount 设 0、status 设 INITIALIZING、写入时间。
2. configure 第 67-70 行写面试方向和难度；createConfigured 第 37-41 行调用它后 repository.save。
3. activate 第 44 行取得 `requiredForUpdate`；第 46 行调用实体 `applyAgentResponse`；第 47-52 行有 output 时调用 applyCounters；第 53 行保存。
4. applyAgentResponse 第 86-102 行拒绝倒退 stateVersion，写首题，把下层状态映射为 ACTIVE/PAUSED/FAILED/COMPLETED，更新 agentStateVersion、阶段和时间。
5. applyCounters 第 104-108 行只更新非 null 的统计数字。
6. load 第 183-186 行按 ID 查询或抛 SESSION_NOT_FOUND；requiredForUpdate 第 188-191 行用带悲观锁的 Repository 查询或抛同一错误。

### 3.3 Java 到 Python HTTP 调用

#### 3.3.1 `HttpPythonAgentClient.initialize`、`post`、`validateRequest`

文件：`java-backend/src/main/java/com/interviewguide/pythonagent/mapper/HttpPythonAgentClient.java:43、65-96`。

1. initialize 第 43 行将固定 `/v1/agent/sessions/initialize` 和 DTO lambda 交给 callExecutor。
2. post 第 65 行接收路径/对象；第 66 行先 validateRequest；第 68 行 RestClient POST、retrieve 并反序列化 AgentResponse；第 69-70 行拒绝空 body。
3. 第 71-79 行分别保留已有 PythonAgentException、解析 HTTP 错误或包装网络错误。
4. validateRequest 第 89-96 行运行 Bean Validation；违规为空则 return，否则逐字段拼接约束信息并抛不可重试异常。

#### 3.3.2 `AgentCallExecutor.execute` 与 `sleepBeforeRetry`

文件：`java-backend/src/main/java/com/interviewguide/infrastructure/reliability/AgentCallExecutor.java:22-43`。

1. 第 22-25 行循环执行 Supplier 并在成功时立即返回。
2. 第 26-31 行仅捕获 PythonAgentException；不可重试或耗尽次数直接抛出，否则调用 sleepBeforeRetry 后重试。
3. sleepBeforeRetry 第 36-43 行 Thread.sleep 配置延迟；被中断时恢复标志并抛异常。

### 3.4 Python 初始化路由与服务

#### 3.4.1 `initialize_session`、`_remember_request_context`、`_resolve_service`、`_success_response`

文件：`python-agent/app/api/application.py:70-91、312-317、357-373、388-392`。

1. 路由第 70-71 行声明 POST 端点及 AgentResponse 模型。第 72 行保存请求上下文。
2. 第 73-80 行解析/缓存 InterviewAgentService，调用 initialize_session；CandidateProfile 由 payload.candidate dump 加默认 question_count 构造。
3. 第 81-90 行调用 _success_response，返回首题和当前题量统计。
4. _resolve_service 第 312-317 行从 app.state 取服务；首次为空时 build_interview_agent_service 并缓存。
5. _remember_request_context 第 388-392 行调用 payload.model_dump(by_alias=True,mode='json') 并写 request.state，供错误处理关联 response。
6. _success_response 第 357-373 行逐字段构造 code=100、COMPLETED 的 AgentResponse，使用 session 的用户、会话、版本、当前问题和当前阶段。

#### 3.4.2 `InterviewAgentService.initialize_session`

文件：`python-agent/app/agents/interview/service.py:127-189`。

1. 第 135 行 repository.get；第 136-149 行处理幂等：同 runId/同 user 时以 `_profile_fingerprint` 校验参数并返回已有会话，其余重复创建抛 ConsistencyError。
2. 第 155-159 行用 `_run_interview_node` 调 planner.create_plan，限制规划节点超时。
3. 第 160-184 行把 profile、plan、题量上限、技能、开场问题、知识库 ID、runId 和指纹写入 InterviewSession。
4. 第 185 行 `_register_question` 把开场问题加入已问目录；第 186-188 行初始化用户长期记忆；第 189 行 repository.create 持久化 Agent 会话。

#### 3.4.3 `_run_interview_node` 与 `_report_progress`

文件：`python-agent/app/agents/interview/service.py:107-125`。

1. _report_progress 第 107-110 行更新内存进度字典，并在配置了 reporter 时 await 回调。
2. _run_interview_node 第 112-115 行先报 PLANNING；第 117-119 行以 asyncio.wait_for 执行调用并施加 45 秒上限。
3. 第 120-125 行超时时标记 FAILED 并抛可重试 AgentDependencyError。

#### 3.4.4 `InterviewPlanner.create_plan` 及覆盖辅助函数

文件：`python-agent/app/agents/interview/agent.py:41-170`。

1. 第 42-48 行从 SkillRegistry 得到可用技能、建议技能和按 ID 索引。
2. 第 49-59 行用 StructuredOutputInvoker 调模型选择技能；第 60-77 行过滤非法 ID、回退建议、强制 interview-coach 和一个领域技能、去重并最多保留四个。
3. 第 78-90 行解析所选技能、渲染 planner prompt、调用模型生成 InterviewPlan。
4. 第 93-118 行最多两轮调用 `_missing_coverage`；有缺口时带草案和反馈再次 invoke；第 119-124 行两次仍失败则抛异常。
5. 第 125-145 行校验每阶段难度，按阶段写题量/追问上限，覆盖 selected_skills 并返回。
6. _coverage_matrix 第 149-158 行从阶段 topic 判断项目、技术栈、实践覆盖；_missing_coverage 第 160-170 行把 false 项映射为中文缺口列表。

#### 3.4.5 结构化模型调用、记忆和仓储

文件：`python-agent/app/infrastructure/reliability/structured_output.py:30-120`；`memory/service.py:28-47`；`infrastructure/persistence/interview_session_repository.py:36-103`。

1. StructuredOutputInvoker.invoke 第 30-70 行渲染输出约束、构造消息、调用 _invoke_model，再以 _validate 解析 JSON 并 Pydantic 校验；格式错误只在策略次数内补充修复消息。
2. _invoke_model 第 72-75 行通过 AsyncRetryExecutor.execute 调 model.ainvoke；_validate 第 77-84 行依次调用 _content_as_text、_strip_json_fence、json.loads 和 schema.model_validate。
3. MemoryService.initialize_user_memory 第 28-35 行读用户记忆；不存在就用当前简历快照 repository.create。第 36-47 行已有记忆时重置因简历切换失效的画像字段、合并快照并以 expected_version 保存。
4. PostgresInterviewSessionRepository.create 第 36-44 行将会话转换实体、打开异步 session、add/commit；完整性冲突转 ConsistencyError。_to_entity 第 88-99 行把 Pydantic 会话字段映射为 ORM 行。
5. 这些函数完成后 Python 返回 AgentResponse；Java 的 activate 再把首题和状态写回 Java 会话表。

### 3.5 初始化链遗漏风险的逐函数核对

#### 3.5.1 `build_interview_agent_service`

文件：`python-agent/app/bootstrap.py:39-72`。

1. 第 44 行取得 Settings；第 45 行创建数据库 session factory；第 46-50 行依次创建 PromptLoader、SkillRegistry、InterviewWorkflow、聊天模型和重试执行器。
2. 第 51 行按 embedding_model 决定是否构造 RagSearchTool；第 53-72 行把 Planner、评价/路由/出题 Agent、Postgres 会话仓储、workflow、MemoryService、总结 Agent、幂等策略和 WebEvidenceTool 注入 InterviewAgentService。
3. 该函数只在 `_resolve_service` 首次发现 app.state 中没有实例时调用；后续同进程请求复用缓存实例。

#### 3.5.2 `_profile_fingerprint` 与 `_register_question`

文件：`python-agent/app/agents/interview/service.py:399-414、670-681`。

1. `_profile_fingerprint` 第 399 行接收 CandidateProfile；第 400-412 行使用 JSON mode dump、固定键顺序和紧凑分隔符构造稳定内容；第 413-414 行返回 SHA-256 十六进制摘要。initialize_session 的重复 runId 分支用它拒绝“同 runId、不同参数”。
2. `_register_question` 第 670 行接收 session、问题、阶段和 topic；第 671-676 行向 asked_question_catalog、stage_question_counts、topic_question_counts 等会话字段登记首题；第 677-681 行保持计数边界一致。它只注册开场题，并不调用模型或数据库。

#### 3.5.3 `InterviewWorkflow.opening_message`

文件：`python-agent/app/agents/interview/workflow.py:38-42`。

1. 第 38 行定义函数；第 39 行用 PromptLoader.render 读取开场模板并传入 targetRole；第 40-41 行对渲染结果 strip 并返回；第 42 行结束。
2. 它生成写入 Python 会话的 current_question，随后 `_success_response` 将这一首题交给 Java；没有在此阶段执行 RAG 搜索或候选人答题评价。

#### 3.5.4 `SkillRegistry` 的四个可达函数

文件：`python-agent/app/tools/skills/loader.py:86-154`。

1. `available_for_interview` 第 104-116 行读取已加载技能、过滤 enabled 与 interview 可用项并返回不可变 tuple。
2. `selection_catalog` 第 118-128 行将可用技能转换为给模型选择的 id、名称、说明和标签字典；它不接受模型输出作为可信配置。
3. `select_for_interview` 第 130-154 行依据 targetRole、JD 和方向在本地规则中生成建议 Skill；这些只是 Planner 的保底候选。
4. `resolve_for_interview` 第 86-102 行逐个验证 selected ID 存在、已启用且允许面试，再返回 SkillDefinition；非法 ID 在模型选择后被再次阻断。

#### 3.5.5 `PromptLoader`、`AsyncRetryExecutor` 与结构化输出辅助函数

文件：`python-agent/app/common/prompt_loader.py:19-46`；`infrastructure/reliability/retry.py:23-50`；`structured_output.py:72-120`。

1. PromptLoader.load 第 19-24 行先 `_resolve`，再按 UTF-8 读取；render 第 26-40 行逐个替换变量、拒绝缺失和残留占位符；_resolve 第 42-46 行阻止 prompt_id 路径越出 resources 根目录。
2. AsyncRetryExecutor.execute 第 23-40 行以策略的 timeout 运行 operation，只有 `_is_retryable` 第 42-43 行认定的错误且次数未尽时才 await `_backoff_seconds` 第 45-50 行的指数退避。
3. StructuredOutputInvoker._content_as_text 第 87-104 行兼容字符串、消息和内容片段；_strip_json_fence 第 107-112 行移除 Markdown 围栏；_readable_validation_error 第 115-120 行把校验失败压缩为可反馈给模型的文本。

#### 3.5.6 LongTermMemory 仓储的实际调用边界

文件：`python-agent/app/infrastructure/persistence/long_term_memory_repository.py:31-86`。

1. `get` 第 31-38 行用异步 session 按 user_id 查询并将 JSON model_validate 回 LongTermMemory；第 38 行不存在返回 None。
2. `create` 第 40-47 行通过 `_to_entity` 映射、add 和 commit；完整性冲突转换为 ConsistencyError。
3. `save` 第 49-76 行以 expected_version 更新 state_version，并在 rowcount 不为 1 时 rollback 和抛并发一致性错误。
4. `initialize_user_memory` 只会调用上述 get 与 create/save 分支之一；其返回结果不直接写回 Java，而是保留给同一用户后续 Python 面试节点使用。

## 4. 审核结论

1. 已按真实源码覆盖前端创建入口、Java 授权/知识库/会话、HTTP Python 调用、Python 初始化规划与记忆持久化、Java 状态写回。
2. 已明确本接口同步等待 Python 初始化，不经过 RabbitMQ；Python 初始化失败会使 Java 会话标记 FAILED 且 HTTP 失败。
3. 每个项目定义的可达函数均给出源码文件和行号；Spring、JPA、Axios、RestClient、Pydantic 和模型 SDK 仅按项目调用边界说明。
