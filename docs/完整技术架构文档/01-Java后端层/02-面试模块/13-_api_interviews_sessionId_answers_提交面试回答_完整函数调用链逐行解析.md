# POST /api/interviews/{sessionId}/answers：提交面试回答完整函数调用链逐行解析

> 此接口是当前项目最严格的同步 Agent 调用路径之一：前端为同一回答稳定复用 runId；Java 和 Python 均验证会话/状态版本与 runId；Java 最后以 MyBatis 事务写入 turn 和新会话状态。它不经过 RabbitMQ。

## 1. 接口定义

### 1.1 功能与作用

`POST /api/interviews/{sessionId}/answers` 提交当前题的候选人回答，Python Agent 评估回答并生成下一题/阶段，Java 持久化回答 turn、评估摘要、可选评分与会话状态。响应为完整详情，前端可基于它重建整个聊天记录。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 路径 | `POST /api/interviews/{sessionId}/answers` |
| Controller | `InterviewController.submitAnswer`，`InterviewController.java:72-79` |
| 请求体 | `answer`、必需 `runId`，均受 `@Valid` 约束 |
| Python 调用 | `POST /v1/agent/respond`，180 秒前端超时、Python 单轮 150 秒限制 |
| 幂等 | 同一回答重试复用 runId；Java `interview_turns.run_id` 查询防止重复写入 |
| 并发 | Java 对 session 使用 `stateVersion`；不同 runId 的过期提交返回 `SESSION_CONCURRENT_MODIFICATION`。|

### 1.3 前端入口

`frontend/src/pages/InterviewPage.tsx:226-277` 的回答处理器生成/复用 `answer-run` ID，存入 ref 与 sessionStorage，调用 `interviewApi.submitAnswer`。`frontend/src/api/interview.ts:78-87` 发起 180 秒 JSON POST 并将详情转换为 session。

## 2. 函数调用链

```text
InterviewPage.handleSubmitAnswer -> createClientId / pendingAnswerStorageKey
  -> interviewApi.submitAnswer -> request.post -> Axios interceptor
  -> RequestIdFilter -> SimpleRateLimitFilter -> IdempotencyFilter（可选）
  -> InterviewController.submitAnswer -> UserIdentityResolver.require
  -> InterviewService.submitAnswer -> ownedSession
     -> HttpPythonAgentClient.respond -> AgentCallExecutor.execute -> post / validateRequest
        -> Python respond -> InterviewAgentService.submit_answer_for_run
           -> runId cache/persistence -> InterviewAgent -> RAG/skills/model -> progress reporting
     -> requireMatchingResponse / requireSuccess / firstNonBlank
     -> InterviewSessionPersistenceService.applyAnswer
        -> requiredForUpdate -> InterviewSessionRepository.findByIdForUpdate
        -> InterviewTurnRepository.findByRunId
        -> InterviewTurnEntity / session apply functions -> number
        -> InterviewTurnRepository.save -> InterviewSessionRepository.save
  -> InterviewService.detail -> turns -> toView / parseFinalEvaluation
  -> ApiResult.success -> Axios -> interviewApi.toSession -> InterviewPage.initSession
```

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `InterviewPage.handleSubmitAnswer`

**文件与行号：** `frontend/src/pages/InterviewPage.tsx:226-277`。

1. 第 226 行检查回答去空白后、session、当前题；任一缺失直接返回。第 228 行开始 try。
2. 第 229-231 行设置提交中、Agent 显示 EVALUATING 并清错误。第 232 行规范回答。第 233-237 行从 ref 读取上次待提交项，只有 session、问题、回答三者相同时判定为重试。
3. 第 238-245 行重试复用原对象和 runId；新提交则记录 sessionId、题目、回答，并调用项目 `createClientId('answer-run')` 生成 runId。第 246-247 行同时保存 ref 和 sessionStorage，刷新页面后仍可恢复幂等重试。
4. 第 249-257 行仅新提交时把用户消息插入聊天列表，并删去先前 runId 的重复乐观消息。
5. 第 259-263 行调用 API。成功后第 265-267 行清理 ref/sessionStorage/输入框；第 269 行用后端完整记录调用 `initSession`，保证网络重试后聊天仍可重建。
6. 第 270-272 行显示错误；第 273-276 行 finally 解除提交状态并将显示状态改 IDLE；第 277 行结束。

#### 3.1.2 `interviewApi.submitAnswer` 与请求函数

**文件与行号：** `frontend/src/api/interview.ts:78-87`，`frontend/src/api/request.ts:47-72、123-`。

1. 第 79-82 行 POST 路径，并只发送 answer/runId，设置 180 秒 timeout。
2. 第 83 行调用 `toSession(detail.session, detail.turns)`；第 84-85 行在 ACTIVE 且有 questions 时取最后一题为 nextQuestion；第 86 行返回 session、是否有下一题与下一题。
3. `createClientId` 第 47-49 行生成用户/运行 ID；`currentUserId` 第 52-57 行读写本地用户；请求拦截器第 64-72 行写身份与 requestId。响应拦截器第 123-154 行解包成功或解析错误。

### 3.2 Java Web 和 Agent 调用函数

#### 3.2.1 RequestId、限流与幂等过滤器

**文件与行号：** `RequestIdFilter.java:23-41`、`SimpleRateLimitFilter.java:48-82`、`IdempotencyFilter.java:41-96`，均在 `java-backend/src/main/java/com/interviewguide/infrastructure/`。

1. RequestId filter 第 25-33 行规范、保存、回传 ID、写/清 MDC；`normalize` 第 36-41 行非法时生成 UUID。
2. 限流第 54-67 行 Redis INCR 失败时回退 ConcurrentHashMap，第 69-79 行超限 429。
3. 前端依赖 runId 业务幂等，`X-Idempotency-Key` 未默认写入；若调用方提供，`shouldNotFilter` 第 42-44 行允许进入，`doFilterInternal` 第 50-84 行进行键占位、`writeConflict` 第 88-95 行返回重复 409。

#### 3.2.2 `InterviewController.submitAnswer` 与 `InterviewService.submitAnswer`

**文件与行号：** `InterviewController.java:72-79`，`InterviewService.java:89-105`。

1. Controller 第 73-75 行绑定 sessionId、有效请求体与用户头。第 76 行先 require owner；第 77 行调用服务；第 78 行再次调用 `detail` 并 success 包装，所以客户端得到持久化后的完整历史。
2. 服务第 90-92 行拒绝空 runId。第 93 行 `ownedSession` 校验 session/owner。第 94-97 行只允许 ACTIVE 或 PAUSED 状态。
3. 第 98-101 行构造 Python respond 请求，携带 Java 当前会话状态/版本、runId、回答及身份。第 102 行验证响应身份。第 103 行调用事务 `applyAnswer`，入参为提交前 stateVersion；第 104 行 reload 转 view；第 105 行结束。

#### 3.2.3 `HttpPythonAgentClient`、重试和响应验证函数

**文件与行号：** `HttpPythonAgentClient.java`（`java-backend/src/main/java/com/interviewguide/pythonagent/mapper/`）的 `respond`/`post:65-96`，`AgentCallExecutor.java:22-43`，`InterviewService.java:202-237`。

1. `respond` 把请求交给 `AgentCallExecutor.execute`，POST `/v1/agent/respond`。`post` 第 65-79 行先 `validateRequest`、执行 RestClient、拒绝空 response，并映射结构化/HTTP/网络异常；`validateRequest` 第 89-96 行运行 Bean Validation。
2. `execute` 第 22-34 行只重试带 retryable 标记的 Python 异常；`sleepBeforeRetry` 第 36-43 行退避并正确恢复中断标志。
3. `requireSuccess` 第 202-217 行检查 100–199 响应，`firstNonBlank` 第 219-224 行选择错误文本，`requireMatchingResponse` 第 226-237 行逐项比对 user/session/run，防止响应串写。

### 3.3 Python 处理与 Java 事务写回函数

#### 3.3.1 FastAPI `respond`

**文件与行号：** `python-agent/app/api/application.py:96-133`。

1. 第 96 行注册 Python POST；第 97 行接收 Pydantic payload；第 98 行保存请求上下文；第 99 行解析 InterviewAgentService。
2. 第 100-111 行用 `asyncio.wait_for` 调用 `submit_answer_for_run`，把 user、session、回答、runId、预期状态和版本全部传入，并设为 `INTERVIEW_TURN_TIMEOUT_SECONDS`。
3. 第 112-119 行超时时调用 `mark_progress_failed`，再抛不可重试 `AgentDependencyError`；第 120-124 行其他 BaseException 同样标失败后重抛。
4. 第 125-133 行将服务结果用 `_success_response` 包装，回显请求身份、下一个 answer、筛选后的输出、stateVersion、sessionStatus、turnStage 与 currentStage。

#### 3.3.2 `InterviewAgentService.submit_answer_for_run`

**文件与行号：** `python-agent/app/agents/interview/service.py` 中同名函数及其被调用的 runId/会话持久化助手。

1. 函数按 sessionId 读取会话，验证 user、状态与预期版本；同 runId 已完成时返回已保存快照，确保 Python 一侧幂等。
2. 新 run 时它上报进度、检索记忆/RAG/可选网页证据、调用项目 InterviewAgent 评估并生成后续问题，再持久化新的 Agent 状态和 run 结果。
3. 缓存/Redis 只用于跨实例加速 run/进度；持久化会话仍是恢复依据。异常时路由已将进度标为 FAILED。

#### 3.3.3 `InterviewSessionPersistenceService.applyAnswer`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/interview/service/InterviewSessionPersistenceService.java:57-124`。

1. 第 57 行声明事务；第 64 行用 `requiredForUpdate` 获取锁定会话；该函数第 194-196 行使用 Mapper `findByIdForUpdate`，缺失抛 SESSION_NOT_FOUND。
2. 第 65 行按 runId 查 turn。第 66-73 行命中时验证 session/回答完全相同；相同直接 return，不同抛 `RUN_ID_PAYLOAD_MISMATCH`。
3. 第 80-83 行才比较 stateVersion，旧版本的新 runId 抛并发修改错误。第 85-93 行验证 Python turnStage 存在且在 AGENT_STAGES。
4. 第 95-97 行创建 turn、写 stage。第 98-115 行读取 output，分别写评估摘要/分数；第 104-110 行尝试把 strengths、weaknesses、finalEvaluation 序列化，失败时保留基本字段；第 111-114 行调用 `number`（122-124）安全读取计数。
5. 第 116 行保存 turn；第 117 行将 Python 答案/状态/版本/阶段应用到 session；第 118 行推进 Java stateVersion；第 119 行保存 session；第 120 行结束。

## 4. 主流构建分析

当前采用“同步 Agent RPC + 双层 runId 幂等 + 数据库悲观/版本校验”的实现。优点是客户端立即收到下一题，重试不会重复写回答，Python/Java 都能检测响应串线；缺点是单轮请求耗时长、HTTP 连接承压，且 Java 直连 Python 仍有跨服务双写窗口。

主流替代方案是异步命令处理：提交回答先写命令/turn 事件，后台 Agent Worker 生成下一题，前端通过 SSE/WebSocket 订阅结果。优点是可削峰、模型长耗时不占请求线程、可可靠重试；缺点是前端交互变为等待态，需要更完整的事件顺序、取消和重连设计。

本项目的文字面试强调即时体验，当前同步方式适配。若引入异步，必须保留 runId 唯一约束和 stateVersion：先以 runId 写 inbox/outbox，消费者按 session 串行处理，事件携带版本，前端断线后用详情接口恢复。不要仅靠 Redis 锁代替数据库幂等记录。
