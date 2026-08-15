# DELETE /api/resumes/{id}：删除简历完整函数调用链逐行解析

> 本文以当前实现为准。删除操作不会调用 Python；它先取消活动分析任务，清理分析/面试数据、调整当前简历指针，再删除磁盘原文件和简历记录。RabbitMQ 中已经排队的旧消息仍可能到达，但 Worker 会因记录缺失或任务已取消而安全返回。

## 1. 接口定义

### 1.1 功能与作用

`DELETE /api/resumes/{id}` 删除当前用户的一份简历及其分析记录和关联面试记录。若删除的是候选人的当前简历，系统选择剩余版本中 version 最大的一份作为替代当前简历；没有剩余版本则清空指针。删除前会把 PENDING/PROCESSING 分析取消，避免延迟的 Python 结果回写。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 方法与路径 | `DELETE /api/resumes/{id}` |
| Controller | `ResumeController.delete`，`java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:69-74` |
| 输入 | 路径 `id`、`X-User-Id`、可选 `X-Idempotency-Key` |
| 成功响应 | `ApiResult<Void>`，data 为 `null` |
| 删除对象 | 分析任务、对应面试 turn/session、文件存储中的原文件、resumes 行；必要时更新 candidates.current_resume_id |
| Python/MQ | 不发新消息、不调 Python；已在队列中的历史消息由 Worker 识别为过期。|

### 1.3 前端入口

`frontend/src/pages/HistoryPage.tsx:16` 的 `remove` 在确认对话框后调用 `historyApi.deleteResume`，成功后清空待删除项并重新加载列表。`frontend/src/api/history.ts:100` 使用 `request.delete` 构造 URL。

## 2. 函数调用链

```text
HistoryPage.remove -> historyApi.deleteResume -> request.delete
  -> Axios 请求拦截器 -> currentUserId / createClientId
  -> RequestIdFilter.doFilterInternal -> normalize
  -> SimpleRateLimitFilter.doFilterInternal -> JavaRedisStore.incrementInFixedWindow
  -> IdempotencyFilter.shouldNotFilter
     ->（带键）doFilterInternal -> JavaRedisStore.acquire / 本机回退
  -> ResumeController.delete -> ResumeService.delete
     -> owned -> ResumeRepository.findById -> UserIdentityResolver.require -> owns
        -> CandidateRepository.findById
     -> ResumeAnalysisService.cancelActiveForResumeIds
        -> ResumeAnalysisPersistenceService.cancelActiveForResumeIds -> Repository.save -> cacheAfterCommit
     -> ResumeAnalysisService.deleteByResumeId
        -> ResumeAnalysisPersistenceService.deleteByResumeId -> Repository.deleteByResumeId
           -> JavaTaskStatusCache.removeLatestResumeAnalysis（提交后）
     -> InterviewSessionRepository.findByUserIdOrderByCreatedAtDesc -> InterviewTurnRepository.deleteBySessionId
        -> InterviewSessionRepository.delete
     -> ResumeRepository.findByCandidateId -> CandidateEntity.setCurrentResumeId -> CandidateRepository.save
     -> ResumeFileStorageService.delete -> ResumeRepository.delete
  -> ApiResult.success(null) -> Axios response interceptor -> HistoryPage.load
```

## 3. 函数解析

### 3.1 前端、过滤器和 Controller 函数

#### 3.1.1 `HistoryPage.remove` 与 `historyApi.deleteResume`

**文件与行号：** `frontend/src/pages/HistoryPage.tsx:16`，`frontend/src/api/history.ts:100`。

1. `remove` 第 16 行先检查 `pendingDelete`，为空即返回。随后设置 `deleting=true`，调用 `deleteResume(pendingDelete.id)`。
2. 成功后同一行清空确认对象并 `await load()` 读取新列表；finally 始终把 `deleting` 设回 false。失败会由调用栈抛出，finally 仍解除禁用状态。
3. API 第 100 行调用 `request.delete<void>`，用模板字符串绑定 ID。`request.ts:47-72` 的 `createClientId`、`currentUserId` 与请求拦截器生成/写入用户、追踪头；`request.ts:123-154` 的响应拦截器解包 `ApiResult` 或转换错误。

#### 3.1.2 `RequestIdFilter`、限流和幂等函数

**文件与行号：** `RequestIdFilter.java:23-41`、`SimpleRateLimitFilter.java:48-82`、`IdempotencyFilter.java:41-96`，均在 `java-backend/src/main/java/com/interviewguide/infrastructure/`。

1. `RequestIdFilter.doFilterInternal` 第 25-33 行调用 `normalize`、保存/回传 requestId、写 MDC、放行并 finally 清理；`normalize` 第 36-41 行对非法头生成 UUID。
2. 限流函数第 54-58 行调用 `JavaRedisStore.incrementInFixedWindow`；其第 32-38 行原子递增、首次设 TTL、Redis 异常返回空 Optional。过滤器第 60-67 行回退本机窗口，第 69-79 行超限返回 429，第 81 行放行。
3. `IdempotencyFilter.shouldNotFilter` 第 42-44 行使没有幂等键的 DELETE 跳过。带键时 `doFilterInternal` 第 50-84 行校验、调用 `acquire` 占位并防重；`writeConflict` 第 88-95 行返回 409。4xx 或异常会释放占位。

#### 3.1.3 `ResumeController.delete` 与 `ApiResult.success`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:69-74`，`common/web/dto/ApiResult.java:3-6`。

1. 第 69 行映射 DELETE；第 70-71 行绑定 id 和用户头；第 72 行调用服务删除；第 73 行调用 `success(null)`；第 74 行结束。
2. `success` 第 4 行接收 data，第 5 行构造成功 record，第 6 行结束。

### 3.2 Java 删除、关系清理和存储函数

#### 3.2.1 `ResumeService.delete` 与授权函数

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:247-274、276-288`。

1. 第 249 行 `owned` 读取并授权简历；第 250-251 行读取候选人，缺失抛 `CANDIDATE_NOT_FOUND`。
2. `owned` 第 277-282 行调用 `ResumeRepository.findById`，空时抛 `RESUME_NOT_FOUND`；它用 `UserIdentityResolver.require`（`14-19` 行拒绝空身份并 strip）和 `owns` 校验所有权。`owns` 第 286-287 行以 `CandidateRepository.findById` 比较 userId。
3. 第 254 行取消活动分析，第 255 行删除分析记录。第 256-261 行查询当前用户会话、按 resumeId 过滤，对每个会话先删 turn 再删 session。
4. 第 262-270 行仅在删除当前简历时，从 `findByCandidateId` 结果排除当前 ID，按 version 取最大值，缺失则 `null`；第 268 行设置候选人当前简历，第 269 行 Mapper 保存。
5. 第 271 行调用文件删除；第 272 行删简历数据库行；第 273 行显式 return；第 274 行结束。

#### 3.2.2 分析取消、删除与 Redis 提交后清理函数

**文件与行号：** `ResumeAnalysisService.java:80-84`，`ResumeAnalysisPersistenceService.java:68-85、115-125`，均在 `java-backend/src/main/java/com/interviewguide/resume/service/`。

1. `ResumeAnalysisService.cancelActiveForResumeIds` 第 82-84 行将列表委托给持久化服务。持久化函数第 81-85 行对空列表返回，否则查询 PENDING/PROCESSING 记录，逐个调用实体 `cancel`、Mapper `save`、`cacheAfterCommit`。
2. `deleteByResumeId` 第 80 行委托持久化层；持久化删除第 69-77 行调用 Mapper `deleteByResumeId`。没有活动事务时第 71-73 行立即删最新 Redis 键；有事务时第 75-77 行注册 afterCommit 后删，避免回滚时删除仍有效的缓存。
3. `cacheAfterCommit` 第 115-125 行同样保证任务状态快照只在数据库提交后调用 `JavaTaskStatusCache.updateResumeAnalysis`。Redis 不是最终事实源。

#### 3.2.3 面试 Mapper、候选人/简历 Mapper 与文件删除函数

**文件与行号：** `InterviewTurnRepository.java:15`、`InterviewTurnRepository.xml:7`、`ResumeFileStorageService.java:49-54`。

1. `InterviewSessionRepository.findByUserIdOrderByCreatedAtDesc` 的 XML 按 user_id 查询会话；服务过滤目标 resumeId。`InterviewTurnRepository.deleteBySessionId` 接口第 15 行绑定 XML 第 7 行 `DELETE FROM interview_turns WHERE session_id=#{sessionId}`；之后调用 Session Mapper `delete` 删除父会话，符合先子后父顺序。
2. `ResumeRepository.findByCandidateId`（接口第 17 行）读取替代版本；`CandidateRepository.save` 第 9-10 行先执行 MyBatis `upsert` 再返回实体；`ResumeRepository.delete` 第 15 行绑定 XML 删除 SQL。
3. `ResumeFileStorageService.delete` 第 49 行声明函数；第 50 行对空 key 返回；第 51 行在根目录下 resolve/normalize；第 52 行拒绝越界路径；第 53 行 `Files.deleteIfExists`；第 54 行结束。文件删除 IOException 会沿 Controller 声明路径转为错误，不会被静默吞掉。

## 4. 主流构建分析

当前删除跨数据库和本地文件系统，优点是直观且 Worker 有取消/缺失记录保护；缺点是没有单一事务覆盖文件删除与多张表删除，文件删除失败或数据库失败可能留下孤儿资源，且逐会话删除有 N+1 问题。

主流方案是软删除加异步清理：事务内把简历标记 `DELETED`、写 outbox 清理事件、查询统一过滤软删除；后台任务重试删除对象存储文件和附属数据。优点是可恢复、跨资源更可靠、审计完备；缺点是查询条件、唯一约束、存储成本和清理任务复杂度更高。

本项目适合在真实用户数据或对象存储上线前引入。实现时为 resumes 增加 `deleted_at`/`status`，所有 Mapper 默认加未删除条件；删除接口在事务中取消任务、更新状态、写 outbox；清理 Worker 成功后物理删除文件和必要历史数据，并记录失败供重试。当前的 `isCurrentResume` 与删除前取消任务逻辑应保留。
