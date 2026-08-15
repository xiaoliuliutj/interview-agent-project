# POST /api/resumes/{id}/reanalyze：重新分析当前简历完整函数调用链逐行解析

> 本文基于当前工作区。该接口先同步创建 `PENDING` 分析任务并投递 RabbitMQ，随后由 Java 消费者调用 Python 的简历记忆激活与评估接口；HTTP 成功不表示 Python 已完成。

## 1. 接口定义

### 1.1 功能与作用

`POST /api/resumes/{id}/reanalyze?targetRole=...` 只允许当前用户重新分析其候选人当前版本简历。它会取消同一简历的活动分析、创建新任务、事务提交后更新 Java Redis 快照并发送 RabbitMQ 消息。消息消费者随后调用 Python，完成、失败、取消和可重试失败均写回 PostgreSQL。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 方法与路径 | `POST /api/resumes/{id}/reanalyze` |
| Controller | `ResumeController.reanalyze`，`java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:63-67` |
| 输入 | 路径 `id`、查询参数 `targetRole`、`X-User-Id`；可选 `X-Idempotency-Key` |
| 同步响应 | `ApiResult<ResumeAnalysisView>`，通常状态为 `PENDING` |
| 异步调用 | RabbitMQ `agent.work` 消费后依次 POST Python `/v1/agent/resume/activate` 与 `/v1/agent/evaluate/resume` |
| 一致性 | PostgreSQL 为最终事实来源；Redis 仅在事务提交后刷新；Python 或 MQ 故障有受限重试和失败状态。|

### 1.3 前端入口

`frontend/src/pages/HistoryPage.tsx:17` 的 `reanalyze` 从用户弹窗读取目标岗位、调用 `historyApi.reanalyze`，随后重新 `load`。API 包装在 `frontend/src/api/history.ts:102`，用 `encodeURIComponent` 将岗位写入查询字符串。

## 2. 函数调用链

```text
HistoryPage.reanalyze -> historyApi.reanalyze -> request.post
  -> Axios 请求拦截器 -> currentUserId / createClientId
  -> RequestIdFilter.doFilterInternal -> normalize
  -> SimpleRateLimitFilter.doFilterInternal -> JavaRedisStore.incrementInFixedWindow
  -> IdempotencyFilter.shouldNotFilter
     ->（带 X-Idempotency-Key）IdempotencyFilter.doFilterInternal -> JavaRedisStore.acquire
  -> ResumeController.reanalyze -> ResumeService.reanalyze
     -> UserIdentityResolver.require -> ResumeService.owned -> ResumeRepository.findById
        -> ResumeService.owns -> CandidateRepository.findById
     -> CandidateRepository.findById -> ResumeAnalysisService.submit
        -> requiredResume -> ResumeRepository.findById
        -> ResumeAnalysisPersistenceService.cancelActiveForResumeIds
        -> ResumeAnalysisPersistenceService.create -> ResumeAnalysisRepository.save
           -> cacheAfterCommit -> JavaTaskStatusCache.updateResumeAnalysis -> JavaRedisStore.putJson
        -> ResumeAnalysisWorker.enqueue -> RabbitTemplate.convertAndSend
        -> ResumeAnalysisService.toView -> stringList / mapList
  -> RabbitAgentWorkConsumer.consume -> ResumeAnalysisWorker.process
     -> beginAttempt -> activateResumeMemory -> HttpPythonAgentClient.post -> AgentCallExecutor.execute
        -> Python activate_resume_memory -> MemoryService.activate_resume
     -> evaluateResume -> HttpPythonAgentClient.post -> Python evaluate_resume
        -> ResumeEvaluationAgent.evaluate -> MemoryService.record_resume_analysis
     -> complete / fail / recordRetryableFailure
```

## 3. 函数解析

### 3.1 前端、过滤器和 Controller 函数

#### 3.1.1 `HistoryPage.reanalyze` 与 `historyApi.reanalyze`

**文件与行号：** `frontend/src/pages/HistoryPage.tsx:17`，`frontend/src/api/history.ts:102`。

1. 页面函数第 17 行调用 `window.prompt` 读取岗位；空值或仅空白时立即返回。合格值调用 API，成功后调用 `load` 刷新列表。
2. API 函数第 102 行以 `encodeURIComponent(targetRole)` 构建 URL，并调用 `request.post`。编码防止岗位中的空格、`&` 等字符改变查询参数语义。
3. `request` 的 `createClientId`、`currentUserId`、请求拦截器分别在 `frontend/src/api/request.ts:47-57、64-72`：生成/复用本地用户 ID、写入用户和请求追踪头。`request.post` 调用 Axios，并由 `123-154` 行响应拦截器解包 `ApiResult` 或构造前端错误。

#### 3.1.2 `RequestIdFilter`、`SimpleRateLimitFilter` 与 `IdempotencyFilter`

**文件与行号：** `RequestIdFilter.java:23-41`、`SimpleRateLimitFilter.java:48-82`、`IdempotencyFilter.java:41-96`，均在 `java-backend/src/main/java/com/interviewguide/infrastructure/`。

1. `RequestIdFilter.doFilterInternal` 第 25-33 行调用 `normalize`、保存/回传 requestId、写 MDC、放行并 finally 清理；`normalize` 第 36-41 行仅接受安全短 ID，否则新建 UUID。
2. `SimpleRateLimitFilter.doFilterInternal` 第 54-67 行按 IP、URI、分钟调用 `JavaRedisStore.incrementInFixedWindow`；该函数第 31-39 行 INCR、首次设 TTL，故障返回空 Optional；过滤器随后用 ConcurrentHashMap 回退。第 69-79 行超限返回 429，第 81 行放行。
3. `IdempotencyFilter.shouldNotFilter` 第 41-44 行：只有带键的写请求进入。若客户端未提供键，POST 直接放行；若提供键，`doFilterInternal` 第 50-84 行校验长度、按用户/方法/路径/键构造 Redis 键、调用 `JavaRedisStore.acquire` 原子占位，并在重复时通过 `writeConflict` 第 88-95 行返回 409。4xx 或异常会删除占位，允许修正后重试。

#### 3.1.3 `ResumeController.reanalyze` 与 `ApiResult.success`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:63-67`，`common/web/dto/ApiResult.java:3-6`。

1. 第 63 行映射 POST；第 64 行绑定路径 ID 与必需查询参数；第 65 行绑定可选用户头。
2. 第 66 行调用 `resumeService.reanalyze` 并由 `success` 包装。`success` 第 4-5 行创建 `code=200`、`message=success` 的 record；第 6 行结束。

### 3.2 Java 任务创建函数

#### 3.2.1 `ResumeService.reanalyze`、`owned` 与 `owns`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:234-245、276-288`。

1. `reanalyze` 第 237 行调用 `identity.require`；第 238 行调用 `owned`。
2. `owned` 第 277-282 行通过 `ResumeRepository.findById` 读取简历，调用 `require` 与 `owns` 校验候选人 userId，不存在抛 `RESUME_NOT_FOUND`，越权抛 `RESUME_ACCESS_DENIED`。
3. 回到第 239-240 行，按简历 candidateId 调用 `CandidateRepository.findById`，缺失抛 `CANDIDATE_NOT_FOUND`。第 241-244 行要求 id 等于 `currentResumeId`，否则抛 `RESUME_NOT_CURRENT`。第 245 行才调用 `analysisService.submit`。

#### 3.2.2 `ResumeAnalysisService.submit` 与持久化函数

**文件与行号：** `ResumeAnalysisService.java:43-64、86-97、127-141`，`ResumeAnalysisPersistenceService.java:33-38、80-85、115-125`，均在 `java-backend/src/main/java/com/interviewguide/resume/service/`。

1. `submit` 第 44-46 行拒绝空岗位；第 47 行 `requiredResume` 查询简历，第 48-52 行检查候选人与用户；第 56 行调用 `cancelActiveForResumeIds`。
2. 持久化取消函数第 81-85 行查询 PENDING/PROCESSING 任务，逐项实体 `cancel`、Mapper `save`、登记缓存更新。
3. `create` 第 34-37 行在事务中创建 `ResumeAnalysisEntity`、调用 `ResumeAnalysisRepository.save`（接口 `15-18` 行按 id 选择 insert/update）、登记 `cacheAfterCommit`、返回实体。
4. `cacheAfterCommit` 第 115-125 行只在事务提交后调用 `JavaTaskStatusCache.updateResumeAnalysis`；后者将快照交给 `JavaRedisStore.putJson`，Redis 故障仅记录日志，不取代 PostgreSQL。
5. `submit` 第 58-60 行投递并 `toView` 返回。`toView` 第 91-97 行复制字段；`stringList` 第 127-131 行与 `mapList` 第 133-137 行反序列化 JSON，格式异常回退空列表。第 61-63 行投递异常时把任务标记 FAILED 再重抛。

#### 3.2.3 `ResumeAnalysisWorker.enqueue` 与 Rabbit 消费函数

**文件与行号：** `ResumeAnalysisWorker.java:49-53、55-161`，`infrastructure/messaging/RabbitAgentWorkConsumer.java:22-39`。

1. `enqueue` 第 50-52 行创建 `AgentWorkTaskMessage`，带任务类型、分析 ID 和用户 ID，经固定 exchange/routing key 调用 `RabbitTemplate.convertAndSend`。
2. 消费者 `consume` 第 22-39 行校验消息字段，按任务类型在第 29-34 行将 resume ID 转 Long 并调用 `process`；格式错误第 36-38 行记录并吞掉，避免无效消息无限重试。
3. `process` 第 56-73 行读取任务与简历，忽略已删除/取消任务，调用 `isCurrentResume` 第 132-135 行防止旧版回写，再调用 `beginAttempt`。第 75-85 行构造激活请求并验证响应；第 90-108 行构造评估请求并验证响应身份。
4. 第 109-113 行通过 `isCancelled`、`isCurrentResume` 决定 `complete` 或 `cancel`。异常块第 114-124 行由 `isRetryable` 第 128-130 行识别可重试 Python 异常，未超次数则 `recordRetryableFailure` 并重抛；否则 `fail`。`requireSuccess` 第 137-143 行校验 100–199，`requireMatchingResponse` 第 145-155 行比对 user/session/run ID，`safeMessage` 第 157-161 行截断错误文本。

### 3.3 Java 到 Python 及 Python 函数

#### 3.3.1 `HttpPythonAgentClient` 与 `AgentCallExecutor`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/pythonagent/mapper/HttpPythonAgentClient.java:46-96`，`infrastructure/reliability/AgentCallExecutor.java:22-43`。

1. `activateResumeMemory`、`evaluateResume` 分别在客户端第 46、47 行委托 `execute`，最终 POST 两个 Python 路径。
2. `post` 第 65-79 行先 `validateRequest`，调用 RestClient，拒绝空响应，并把结构化、HTTP、网络异常转成含 retryable 标志的 `PythonAgentException`；`parseStructuredError` 第 82-87 行只接受含 error 的 AgentResponse；`validateRequest` 第 89-96 行运行 Bean Validation。
3. `AgentCallExecutor.execute` 第 22-34 行最多按配置尝试，只有 retryable Python 异常才第 30 行等待后再试；`sleepBeforeRetry` 第 36-43 行 sleep，线程中断时恢复中断标志并抛不可重试异常。

#### 3.3.2 Python 路由、记忆与评估函数

**文件与行号：** `python-agent/app/api/application.py:159-224、343-357、391-395、438-444`，`python-agent/app/memory/service.py:48-96、176-245`，`python-agent/app/agents/evaluation/agent.py:25-50`。

1. `activate_resume_memory` 第 208-224 行保存请求上下文、解析 MemoryService、调用 `activate_resume`，返回 code 100 和回显身份。`_resolve_memory_service` 第 351-357 行复用或构建服务；`_remember_request_context` 第 391-395 行把可序列化请求保存到 `request.state`。
2. `MemoryService.activate_resume` 第 58-96 行计算激活指纹、读取/创建长期记忆；同 runId 时验证 resumeId/指纹后幂等返回，冲突抛 `ConsistencyError`；新结果以乐观版本保存活动简历快照。
3. `evaluate_resume` 第 159-206 行计算输入指纹、先读同 runId 缓存，未命中才解析 evaluator 并调用 `evaluate`，随后调用 `record_resume_analysis`。`record_resume_analysis` 第 176-231 行拒绝冲突和旧简历结果，更新记忆、保存可回放评估 run。
4. `ResumeEvaluationAgent.evaluate` 第 25-50 行拒绝空文本，从 SkillRegistry 取得 `resume-analyst`，加载外置提示词，组织 subject/岗位/文本，再由结构化输出调用器执行模型并校验 `ResumeEvaluation` schema。

## 4. 主流构建分析

当前“同步建任务 + RabbitMQ 异步 Python 调用”的模式优点是页面不等待模型、失败可持久化、Java/Python 可独立扩缩；缺点是数据库写任务与直接发 MQ 仍存在双写窗口，消息至少一次投递要求消费者持续处理重复与过期任务。

主流生产改进是 Transactional Outbox + 消费 Inbox：在创建 `resume_analyses` 的同一事务写 `outbox_events`，发布器可靠投递 RabbitMQ；消费者先登记 `processed_messages` 再处理。优点是可审计并消除双写丢消息窗口；缺点是多表、轮询/CDC、清理和监控复杂。

本项目在任务量或可靠性要求提升时适合引入。实现方式是将 `enqueue` 改为写 outbox，提交后由发布器发送；为 `AgentWorkTaskMessage` 增加 messageId；消费者以 messageId 写 Inbox 并幂等处理；仍保持 Redis 只在 PostgreSQL 提交后刷新。当前 `isCurrentResume`、runId 和 Python 记忆幂等已为该演进提供了基础。
