# POST /api/resumes/{id}/reanalyze：重新分析当前简历完整函数调用链

> 本文对应 Java 接口清单第 6 个接口。该接口同步阶段只创建并投递新的分析任务；实际 Python 调用发生在 RabbitMQ 消费者异步处理阶段。文档将两段分开，避免把 HTTP 200 误写成 Python 已完成。

## 1. 接口定义

### 1.1 功能和作用

接口让当前用户按新的 targetRole 对当前简历版本重新发起分析。Java 校验用户、简历所有权和 currentResumeId，取消该简历旧的活动分析，创建 PENDING 记录并投递 RabbitMQ。消费者随后先调用 Python 激活简历记忆，再调用 Python 简历评价端点，最后把结果写回 Java 的 resume_analyses 表。

### 1.2 基本信息

| 项目 | 内容 |
| --- | --- |
| HTTP 方法 | POST |
| 路径 | /api/resumes/{id}/reanalyze |
| 输入 | id 路径变量、targetRole 查询参数、X-User-Id 头 |
| Controller | ResumeController.reanalyze |
| 同步返回 | ApiResult<ResumeAnalysisView>，通常 PENDING |
| 异步 Python | /v1/agent/resume/activate、/v1/agent/evaluate/resume |
| 幂等与版本 | 取消旧活动任务；Worker 前后检查 currentResumeId |

### 1.3 前端入口

ResumeDetailPage.handleReanalyze 或 HistoryPage.reanalyze 提示用户输入 targetRole。非空岗位调用 historyApi.reanalyze，响应成功后前者刷新详情、后者刷新列表。

## 2. 函数调用链

~~~text
handleReanalyze 或 HistoryPage.reanalyze
 -> historyApi.reanalyze
 -> request.post
 -> 请求拦截器 -> currentUserId -> createClientId
 -> POST /api/resumes/{id}/reanalyze?targetRole=...
 -> RequestIdFilter.doFilterInternal -> normalize
 -> SimpleRateLimitFilter.doFilterInternal
 -> ResumeController.reanalyze
 -> ResumeService.reanalyze
    -> UserIdentityResolver.require
    -> ResumeService.owned -> ResumeRepository.findById -> ResumeService.owns
    -> CandidateRepository.findById -> CandidateEntity.getCurrentResumeId
    -> ResumeAnalysisService.submit
       -> requiredResume -> CandidateRepository.findById
       -> ResumeAnalysisPersistenceService.cancelActiveForResumeIds
       -> ResumeAnalysisPersistenceService.create -> ResumeAnalysisEntity 构造
       -> ResumeAnalysisWorker.enqueue -> RabbitTemplate.convertAndSend
       -> ResumeAnalysisService.toView -> stringList/mapList
 -> ApiResult.success

RabbitAgentWorkConsumer.consume
 -> ResumeAnalysisWorker.process
    -> find analysis/resume/current resume/beginAttempt
    -> HttpPythonAgentClient.activateResumeMemory
       -> AgentCallExecutor.execute -> post -> validateRequest
       -> Python activate_resume_memory -> MemoryService.activate_resume
    -> requireMatchingResponse
    -> HttpPythonAgentClient.evaluateResume
       -> AgentCallExecutor.execute -> post -> validateRequest
       -> Python evaluate_resume
          -> get_resume_evaluation_run
          -> ResumeEvaluationAgent.evaluate
          -> StructuredOutputInvoker.invoke -> model.ainvoke
          -> MemoryService.record_resume_analysis
    -> ResumeAnalysisPersistenceService.complete 或 fail/retry/cancel
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 ResumeDetailPage.handleReanalyze

文件：frontend/src/pages/ResumeDetailPage.tsx:77-89。

1. 第77行定义异步重新分析函数。
2. 第79行 window.prompt 获取岗位文本。
3. 第80行 targetRole 缺失或 trim 后为空时 return，不发请求。
4. 第81行 setReanalyzing(true)。
5. 第82行 await historyApi.reanalyze(resumeId,targetRole.trim)。
6. 第83行成功后 await loadResumeDetailSilent，立即重新读取 Java 中新创建的 PENDING 记录。
7. 第84-85行记录失败；第86-88行 finally 恢复 reanalyzing=false；第89行结束。

#### 3.1.2 HistoryPage.reanalyze

文件：frontend/src/pages/HistoryPage.tsx:17。

1. 该单行箭头函数接收 ResumeListItem。
2. 先 prompt 获取岗位；targetRole 缺失或 trim 为空立即 return。
3. 调用 historyApi.reanalyze(resume.id,targetRole.trim)。
4. await load 刷新列表，轮询逻辑随后可显示 PENDING/PROCESSING。
5. 它和详情页入口不同，但进入同一个 historyApi 和 Java Controller。

#### 3.1.3 historyApi.reanalyze 与 request.post

文件：frontend/src/api/history.ts:102；frontend/src/api/request.ts:161-163。

1. historyApi 第102行将 id 和 encodeURIComponent(targetRole) 拼接为 URL 查询参数，再调用 request.post。
2. encodeURIComponent 防止岗位中的空格、中文、& 等改变查询串结构。
3. request.post 第161行定义泛型 POST；第162行调用 instance.post，then 返回 response.data；第163行结束。
4. 请求拦截器（request.ts:64-73）设置 X-User-Id 和 X-Request-Id；createClientId（47-50）与 currentUserId（52-58）分别生成请求 ID 和稳定用户 ID。
5. 响应拦截器（123-155）在 code=200 时解包 data，失败时以 parseApiError、decodeErrorData、transportError 生成 Promise rejection。

### 3.2 Java HTTP 入口

#### 3.2.1 RequestIdFilter 与 SimpleRateLimitFilter

文件：java-backend/src/main/java/com/interviewguide/infrastructure/web/RequestIdFilter.java:23-41；java-backend/src/main/java/com/interviewguide/infrastructure/ratelimit/SimpleRateLimitFilter.java:38-61。

1. RequestIdFilter 第25行 normalize 请求 ID；第26-28行写 attribute、响应头、MDC；第30行继续；第31-33行 finally 清理。
2. normalize 第36-41行验证格式与长度，非法时生成 UUID。
3. RateLimitFilter 第44-47行按来源和 URI 建分钟窗口；第48行递增并比较限制。
4. 超限第49-58行返回429，未超限第60行进入 Spring Controller。

#### 3.2.2 ResumeController.reanalyze

文件：java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:63-67。

1. 第63行映射 POST /{id}/reanalyze。
2. 第64行绑定 id 和必填 RequestParam targetRole。
3. 第65行读取 X-User-Id。
4. 第66行调用 resumeService.reanalyze，并用 ApiResult.success 包装 ResumeAnalysisView。
5. targetRole 参数缺失由 Spring 绑定失败处理；空白值由 Service.submit 再校验。

### 3.3 ResumeService.reanalyze

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:234-246。

1. 第234-236行声明参数。
2. 第237行 identity.require 校验并规范化 owner。
3. 第238行 owned(id,owner) 查询并校验简历归属。
4. 第239-240行按 resume.getCandidateId 查 CandidateEntity，缺失抛 CANDIDATE_NOT_FOUND。
5. 第241行比较请求 id 和 candidate.getCurrentResumeId。
6. 第242-243行不相等时抛 RESUME_NOT_CURRENT；旧版本不能重新分析。
7. 第245行调用 analysisService.submit(id,owner,targetRole)，把后续任务创建交给分析服务。
8. 第246行结束。

#### 3.3.1 owned、owns、require 和 CandidateEntity getter

文件：ResumeService.java:276-288；UserIdentityResolver.java:14-19；CandidateEntity.java:25-29。

1. owned 第277-278行通过 ResumeRepository.findById 查找或抛 RESUME_NOT_FOUND。
2. 第279-280行 require 后调用 owns，不归属抛 RESUME_ACCESS_DENIED。
3. owns 第286-287行用 CandidateRepository.findById 和 candidate.getUserId 比较，缺候选人时返回 false。
4. require 第15-17行拒绝 null/blank；第18行 strip；第19行返回。
5. CandidateEntity.getCurrentResumeId 第28行单句返回指针；reanalyze 第241行用它拒绝历史版本。

### 3.4 ResumeAnalysisService.submit

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:38-60。

1. 第39-41行校验 targetRole 非 null 且非 blank，否则抛 TARGET_ROLE_REQUIRED。
2. 第42行 requiredResume（77-80）调用 ResumeRepository.findById，缺失抛 RESUME_NOT_FOUND。
3. 第43-47行查询候选人并比较 userId，缺失抛 CANDIDATE_NOT_FOUND，不归属抛 RESUME_ACCESS_DENIED。
4. 第51行 persistence.cancelActiveForResumeIds(List.of(resumeId))，取消该简历 PENDING/PROCESSING 的旧任务。
5. 第52行 persistence.create(resumeId,targetRole.strip)，创建新的 PENDING 实体。
6. 第54行 worker.enqueue(analysis.getId,userId) 发布消息。
7. 第55行 toView 返回 PENDING ResumeAnalysisView。
8. 第56-59行入队失败时 persistence.fail、safeMessage 后重新抛异常。

#### 3.4.1 Persistence create、cancel 和实体状态

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisPersistenceService.java:28-31、61-66；ResumeAnalysisEntity.java:45-51、102-109。

1. create 第29行构造 ResumeAnalysisEntity；第30行 repository.save；第31行结束。
2. cancelActiveForResumeIds 第63行空集合 return；第64行查询 PENDING/PROCESSING；第65行逐个调用 entity.cancel。
3. 实体构造第45-51行保存 resumeId、岗位，状态设 PENDING，记录时间。
4. cancel 第102-107行只在 PENDING/PROCESSING 改为 CANCELLED；isCancelled 第109行比较状态。
5. 这些状态更新发生在 Java 数据库，不表示 Python 已收到任何请求。

#### 3.4.2 enqueue、RabbitAgentWorkConsumer.consume

文件：ResumeAnalysisWorker.java:48-52；RabbitAgentWorkConsumer.java:22-39。

1. enqueue 第49-51行选择 exchange、routing key，构造 AgentWorkTaskMessage(RESUME_ANALYSIS,analysisId,userId)，调用 RabbitTemplate.convertAndSend。
2. 消息只含资源 ID；原文由消费者重新查数据库。
3. consume 第24-27行丢弃空字段消息。
4. 第29-35行 switch taskType；RESUME_ANALYSIS 时第31行调用 worker.process(Long.parseLong(resourceId),userId)。
5. 第36-38行捕获非数字 ID并记录错误。

### 3.5 ResumeAnalysisWorker.process 与 Java-Python 客户端

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java:54-160。

1. 第55-60行查 analysis，缺失或取消 return。
2. 第61-64行查 resume，缺失 return；第65-68行 isCurrentResume，旧简历则 persistence.cancel 并 return。
3. 第69-72行 beginAttempt，不能开始则 return；beginAttempt 使状态变 PROCESSING。
4. 第74-81行构造 activation runId/sessionId 和 AgentResumeMemoryActivationRequest。
5. 第76行调用 pythonAgentClient.activateResumeMemory；第82-84行 requireMatchingResponse。
6. 第85-88行再次 isCurrentResume，防止激活期间替换简历。
7. 第89-96行构造 AgentResumeEvaluateRequest 并调用 evaluateResume。
8. 第97-103行检查 Python code；可重试错误抛 PythonAgentException，不可重试 persistence.fail。
9. 第105-112行校验响应身份，未取消且仍当前简历时 persistence.complete，否则 cancel。
10. 第113-124行捕获 RuntimeException；isRetryable（127-129）且未达尝试上限时 recordRetryableFailure 后重新抛出，其他错误 fail。
11. isCurrentResume 第131-134行查候选人并比较 currentResumeId；requireSuccess 第136-142行拒绝 null 或非1xx/2xx；requireMatchingResponse 第144-154行再比较 userId/sessionId/runId；safeMessage 第156-160行截断错误。

#### 3.5.1 HttpPythonAgentClient 与 AgentCallExecutor

文件：java-backend/src/main/java/com/interviewguide/pythonagent/mapper/HttpPythonAgentClient.java:46-96；AgentCallExecutor.java:22-43。

1. evaluateResume 第46行调用 callExecutor.execute，目标 post 路径 /v1/agent/evaluate/resume。
2. activateResumeMemory 第47行同样执行，目标 /v1/agent/resume/activate。
3. execute 第24-31行循环调用 Supplier；PythonAgentException 可重试且未耗尽时 sleepBeforeRetry；第33行耗尽时抛异常。
4. post 第66行 validateRequest；第68行 RestClient POST 并读 AgentResponse；第69行拒绝空响应；第71-79行处理结构化 HTTP 错误和网络错误。
5. validateRequest 第90-95行 Bean Validator 校验 DTO；parseStructuredError 第82-87行尝试解析 Python 错误体。

### 3.6 Python 激活与评价函数

#### 3.6.1 activate_resume_memory

文件：python-agent/app/api/application.py:205-221。

1. 第209行 _remember_request_context 保存 payload JSON。
2. 第210-214行 _resolve_memory_service 后 await MemoryService.activate_resume，传入用户、简历、候选人、原文、岗位、runId。
3. 第215-221行构造 code=100 的 AgentResponse。
4. _resolve_memory_service（348-354）首次调用 build_memory_service 并缓存；MemoryService.activate_resume（memory/service.py:49-97）生成指纹、读取记忆、创建或更新 active resume、调用 repository.create/save。

#### 3.6.2 evaluate_resume

文件：python-agent/app/api/application.py:156-203。

1. 第158行保存请求上下文；第159行计算 _resume_evaluation_fingerprint。
2. 第160-164行读取 MemoryService.get_resume_evaluation_run；命中 runId/指纹时重用历史评价。
3. 第165-170行缓存未命中时 _resolve_resume_evaluator().evaluate。
4. ResumeEvaluationAgent.evaluate（agents/evaluation/agent.py:25-50）strip 文本、SkillRegistry.get('resume-analyst')、PromptLoader.render、构造 payload、调用 StructuredOutputInvoker.invoke。
5. StructuredOutputInvoker.invoke（structured_output.py:30-70）构造格式提示、消息、调用 _invoke_model、校验 JSON，并对格式错误做受限修正；_invoke_model（72-75）通过 AsyncRetryExecutor.execute 调 model.ainvoke。
6. 第172-188行 MemoryService.record_resume_analysis 保存摘要、issues、建议、技术栈和评价 run；第189-196行并发冲突时尝试 replay；第197-203行返回成功 AgentResponse。
7. Python 端点返回后，Worker 的 persistence.complete（Java ResumeAnalysisPersistenceService.java:33-45）校验 output、解析分数/字符串/JSON，并调用实体 complete。

### 3.7 异步链辅助函数的逐项审核补充

#### 3.7.1 `ResumeAnalysisService.requiredResume`

文件：`java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:77-80`。

1. 第 77 行定义私有查询函数，接收即将分析的 `resumeId`。
2. 第 78 行调用项目声明的 `resumeRepository.findById(resumeId)`；Spring Data 生成实际主键查询。
3. 第 79 行在 Optional 为空时抛 `RESUME_NOT_FOUND`；第 80 行返回已找到的 ResumeEntity。`submit` 第 42 行必经此函数。

#### 3.7.2 `ResumeAnalysisService.toView`、`stringList`、`mapList` 与 `safeMessage`

文件：`java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:82-105`。

1. `toView` 第 82 行接收分析实体；第 83-87 行按构造器顺序读取 id、状态、六项分数、摘要、更新时间、JSON 字段及 error，构造 `ResumeAnalysisView`。
2. 第 86 行对 strengths 和 suggestions 调用 `stringList`，第 87 行对 issues 调用 `mapList`；因此 PENDING 的 null JSON 会稳定表现为空列表。
3. `stringList` 第 90 行定义 JSON 字符串转 `List<String>`；第 91 行对 null/blank 返回 `List.of()`；第 92 行用 ObjectMapper 反序列化；第 93 行捕获任意解析异常并返回空列表；第 94 行结束。
4. `mapList` 第 96-100 行采取同一防御性流程，但目标类型为 `List<Map<String,Object>>`。
5. `safeMessage` 第 102 行接收 RuntimeException；第 103 行读取 message；第 104 行缺失时返回异常类名，否则截断到 500 字符；第 105 行结束。`submit` 入队失败时调用它再写 FAILED。

#### 3.7.3 `ResumeAnalysisPersistenceService.create` 与 `cancelActiveForResumeIds`

文件：`java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisPersistenceService.java:28-31、61-66`。

1. `create` 第 28 行声明事务边界；第 29 行定义方法；第 30 行 `new ResumeAnalysisEntity(resumeId,targetRole)` 后交给 `repository.save`；第 31 行返回保存后的实体。
2. `cancelActiveForResumeIds` 第 61 行声明事务；第 62 行接收简历 ID 集合。
3. 第 63 行对空集合直接 return；第 64 行查询状态为 PENDING 或 PROCESSING 的记录。
4. 第 65 行对每条记录调用项目实体函数 `ResumeAnalysisEntity.cancel`；第 66 行结束。它不撤回已经投递到 RabbitMQ 的消息，而是让 Worker 收到旧消息时主动退出。

#### 3.7.4 `ResumeAnalysisEntity` 的构造、尝试与取消函数

文件：`java-backend/src/main/java/com/interviewguide/resume/domain/ResumeAnalysisEntity.java:45-67、102-109`。

1. 构造函数第 45 行接收 resumeId 和 targetRole；第 46-48 行写入两个业务字段、`PENDING` 状态和当前创建时间；第 49 行把 updatedAt 设为同一时间；第 50-51 行结束。
2. `canBeginAttempt` 第 53-55 行仅在状态为 PENDING 或 PROCESSING 时返回 true；Worker 用它拒绝已完成、失败或取消的旧消息。
3. `beginAttempt` 第 57-62 行把状态写为 PROCESSING、retryCount 加一、更新时间戳；第 63 行结束。
4. `recordRetryableFailure` 第 64-67 行用 `truncate` 保存错误并更新 updatedAt，刻意不改状态，使监听器重投后仍能开始尝试。
5. `cancel` 第 102 行定义方法；第 103-106 行只把可运行状态改为 CANCELLED 并更新时间；第 107 行结束。
6. `isCancelled` 第 109 行单句比较 status；Worker 在调用 Python 前、异常处理前和完成写回前均用它防止旧任务覆盖新版本。

#### 3.7.5 `ResumeAnalysisPersistenceService.beginAttempt`、`cancel`、`isCancelled` 与 `required`

文件：`java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisPersistenceService.java:68-89`。

1. `beginAttempt` 第 68 行标注事务；第 69 行定义函数；第 70 行经 `required(id)` 取得实体。
2. 第 71 行若实体 `canBeginAttempt()` 为 false 返回 null；第 72 行否则调用实体 `beginAttempt`；第 73 行返回实体；第 74 行结束。
3. `cancel` 第 81-82 行先 required 后调用实体 cancel；`isCancelled` 第 84 行同样 required 后读取实体状态。
4. `required` 第 86 行定义私有查询；第 87 行 repository.findById；第 88 行 Optional 为空时抛 `RESUME_ANALYSIS_NOT_FOUND`；第 89 行结束。

#### 3.7.6 `ResumeAnalysisWorker.isCurrentResume`、`requireSuccess`、`requireMatchingResponse` 与 `safeMessage`

文件：`java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java:127-160`。

1. `isRetryable` 第 127 行定义判断；第 128 行仅当异常是 `PythonAgentException` 且其 `retryable()` 为 true 时返回 true；第 129 行结束。
2. `isCurrentResume` 第 131 行接收 ResumeEntity；第 132 行按 candidateId 查询候选人；第 133 行同时要求候选人存在且 `candidate.currentResumeId` 等于 `resume.id`；第 134 行结束。
3. `requireSuccess` 第 136 行定义响应校验；第 137 行拒绝 null、非 1xx 或非 2xx；第 138-140 行优先读取结构化 error.message，缺失时使用调用者的默认信息，并创建带 retryable 标记的 PythonAgentException；第 142 行结束。
4. `requireMatchingResponse` 第 144-146 行接收响应和三项关联 ID；第 147 行先调用 `requireSuccess`；第 148-150 行比较 userId、sessionId、runId；第 151-153 行任一不匹配即抛不可重试身份不一致异常；第 154 行结束。
5. `safeMessage` 第 156-160 行与 Service 中的同名函数相同：读 message、缺失时使用类名、否则截断为 500 字符。

#### 3.7.7 `HttpPythonAgentClient` 的每个项目函数

文件：`java-backend/src/main/java/com/interviewguide/pythonagent/mapper/HttpPythonAgentClient.java:46-96`。

1. `evaluateResume` 第 46 行把固定路径 `/v1/agent/evaluate/resume` 与传入 DTO 放进 lambda，交给 `callExecutor.execute`；重试时 lambda 会再次进入 `post`。
2. `activateResumeMemory` 第 47 行以同一方式请求 `/v1/agent/resume/activate`；这两个函数没有共享或修改 ResumeEntity。
3. `post` 第 65 行接收路径和对象；第 66 行先 `validateRequest`；第 68 行通过 RestClient POST 写 JSON、retrieve 并转换为 AgentResponse；第 69-70 行拒绝空 body。
4. 第 71 行原样重新抛出已有 PythonAgentException；第 72-75 行把 HTTP 响应异常交给 `parseStructuredError`；第 76-79 行把其他网络异常包装为可重试 PythonAgentException。
5. `parseStructuredError` 第 82 行取 response body；第 83-85 行尝试反序列化 AgentResponse，并在成功时使用 error.message 与 retryable；第 86-87 行解析失败则保留 HTTP 异常信息。
6. `validateRequest` 第 89 行调用 Bean Validator；第 90 行取违规集合；第 91 行空集合直接 return；第 92-95 行把 propertyPath 和 message 拼成错误字符串并抛不可重试 PythonAgentException；第 96 行结束。

#### 3.7.8 `AgentCallExecutor.execute` 与 `sleepBeforeRetry`

文件：`java-backend/src/main/java/com/interviewguide/infrastructure/reliability/AgentCallExecutor.java:22-43`。

1. `execute` 第 22 行接收实际 HTTP 调用的 Supplier；第 23 行开始 attempt 计数循环；第 25 行 try 内执行 supplier.get 并立即返回成功响应。
2. 第 26-31 行仅捕获 PythonAgentException；不可重试或已达 maxAttempts 时立即抛出，否则调用 `sleepBeforeRetry` 后进入下一轮。
3. 第 33 行是理论保护分支，循环未成功时抛最后异常；第 34 行结束。
4. `sleepBeforeRetry` 第 36 行定义延迟函数；第 37-38 行调用 Thread.sleep(backoffMillis)；第 39-42 行响应中断、恢复中断标志并抛不可重试异常；第 43 行结束。

#### 3.7.9 Python 路由的依赖解析与幂等辅助函数

文件：`python-agent/app/api/application.py:340-354、388-392、435-441`。

1. `_resolve_resume_evaluator` 第 340 行从 `request.app.state` 读取 evaluator；第 342-344 行缺失时 `build_resume_evaluation_agent()` 并缓存；第 345 行返回实例。
2. `_resolve_memory_service` 第 348 行读取 memory_service；第 350-353 行缺失时 `build_memory_service()` 并缓存；第 354 行返回实例。
3. `_remember_request_context` 第 388 行定义函数；第 389-391 行把 payload 的 alias JSON 写进 request.state，供异常处理器构造关联响应；第 392 行结束。
4. `_resume_evaluation_fingerprint` 第 435 行定义函数；第 436-440 行以固定键、固定顺序和 UTF-8 编码序列化 subjectId/inputText/targetRole；第 441 行返回 SHA-256 十六进制摘要。

#### 3.7.10 Python `MemoryService` 的三个可达函数

文件：`python-agent/app/memory/service.py:49-97、177-248`。

1. `activate_resume` 第 49 行接收身份、简历和 runId；第 59-62 行调用 `_resume_activation_fingerprint`；第 63-66 行构造 ResumeMemory 快照；第 67 行 repository.get。
2. 第 68-76 行无记忆时创建 LongTermMemory、可选写 activation run、调用 repository.create；第 77-81 行重复 runId 时验证指纹，不一致抛 ConsistencyError，一致时 return。
3. 第 82-96 行已有记忆时更新 active resume、合并快照、写 activation run、裁剪记录、更新时间并 repository.save；第 97 行结束。
4. `get_resume_evaluation_run` 第 236 行读取记忆；第 240-241 行无记忆返回 None；第 242-244 行按 runId 查记录或返回 None；第 245-247 行验证 resumeId 与 fingerprint；第 248 行返回已缓存评价。
5. `record_resume_analysis` 第 177 行接收评价结果；第 185-196 行读取并校验记忆、runId 和 active resume；第 197-217 行更新当前简历画像；第 220-232 行去重、记录 evaluation run、按策略裁剪；第 233-234 行 repository.save。

## 4. 审核结论

1. 接口定义、同步任务受理和异步 Python 两阶段均已区分。
2. 调用链覆盖前端、Java 授权、任务取消/创建、Rabbit 消费、Java HTTP 客户端、Python 激活和评价、Java 结果持久化。
3. 每个项目函数均给出文件、行号及语句级解释；框架函数只按实际调用边界说明。
4. HTTP 200 只表示 PENDING 任务成功投递；Python 评价在 Rabbit 消费后才发生。
5. 下一接口为 DELETE /api/resumes/{id}。
