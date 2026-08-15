# POST /api/resumes/upload：简历上传到 Python Agent 的完整函数调用链

> 本文严格依据当前仓库源码编写，分析第一个 Java 后端接口。行号以当前工作区文件为准。同步 HTTP 请求和返回响应后的 RabbitMQ 异步链分别说明，避免把异步任务误写成同步调用。

## 1. 接口定义

### 1.1 功能与作用

该接口接收用户上传的简历文件和目标岗位，完成用户身份校验、文件合法性检查、文件内容解析、候选人及简历版本持久化，并创建一条 PENDING 的简历分析记录。分析不会在浏览器请求线程中等待模型完成，而是通过 RabbitMQ 投递任务；Java 在任务入队后立即返回 resumeId、原始文本和分析状态。随后消费者异步调用 Python 的简历记忆激活与简历评价端点。

### 1.2 基本信息

| 项目 | 内容 |
| --- | --- |
| HTTP 方法 | POST |
| 完整路径 | /api/resumes/upload |
| Java Controller | ResumeController.upload |
| 类级映射 | /api/resumes |
| 方法级映射 | /upload |
| 请求类型 | multipart/form-data |
| 必填 part | file、targetRole |
| 可选请求头 | X-User-Id（业务层实际要求非空） |
| 成功返回 | ApiResult<Map<String,Object>>，分析初始状态为 PENDING |
| Python 调用 | /v1/agent/resume/activate、/v1/agent/evaluate/resume |

### 1.3 前端入口

用户在 UploadPage 选择岗位和文件，FileUploadCard.handleUpload 把文件交给 UploadPage.handleUpload，后者调用 resumeApi.uploadAndAnalyze。请求封装 request.upload 使用 multipart POST，并由拦截器添加 X-User-Id 与 X-Request-Id。

## 2. 函数调用链

### 2.1 同步 HTTP 链

~~~text
FileUploadCard.handleUpload
 -> UploadPage.handleUpload
 -> resumeApi.uploadAndAnalyze
 -> request.upload
 -> Axios request interceptor
    -> currentUserId -> createClientId
 -> RequestIdFilter.doFilterInternal -> normalize
 -> SimpleRateLimitFilter.doFilterInternal
 -> ResumeController.upload
 -> ResumeService.upload
    -> UserIdentityResolver.require
    -> ResumeFileStorageService.inspect -> sha256
    -> CandidateRepository.findByUserId
       ->（无候选人）BusinessIdGenerator.next -> CandidateRepository.save
    -> ResumeRepository.findFirstByCandidateIdAndFileHash
    ->（新文件）ResumeRepository.findByCandidateId
    -> ResumeRepository.findFirstByCandidateIdOrderByVersionDesc
    -> BusinessIdGenerator.next
    -> Tika.parseToString
    -> ResumeFileStorageService.store
    -> ResumeEntity.attachFile -> ResumeRepository.save
    -> ResumeAnalysisService.cancelActiveForResumeIds
    -> CandidateEntity.setCurrentResumeId -> CandidateRepository.save
    -> ResumeAnalysisService.submit
       -> requiredResume -> CandidateRepository.findById
       -> ResumeAnalysisPersistenceService.cancelActiveForResumeIds
       -> ResumeAnalysisPersistenceService.create
          -> new ResumeAnalysisEntity -> ResumeAnalysisRepository.save
       -> ResumeAnalysisWorker.enqueue
          -> new AgentWorkTaskMessage -> RabbitTemplate.convertAndSend
 -> ApiResult.success
 -> Axios response interceptor -> request.upload Promise
 -> UploadPage.handleUpload 成功/异常/finally 分支
~~~

### 2.2 异步 RabbitMQ 到 Python 链

~~~text
RabbitAgentWorkConsumer.consume
 -> ResumeAnalysisWorker.process
    -> ResumeAnalysisRepository.findById
    -> ResumeAnalysisEntity.isCancelled
    -> ResumeRepository.findById
    -> isCurrentResume -> CandidateRepository.findById
    -> ResumeAnalysisPersistenceService.beginAttempt
       -> required -> canBeginAttempt -> beginAttempt
    -> new AgentResumeMemoryActivationRequest
    -> HttpPythonAgentClient.activateResumeMemory
       -> AgentCallExecutor.execute -> HttpPythonAgentClient.post
          -> validateRequest -> RestClient POST /v1/agent/resume/activate
    -> Python activate_resume_memory
       -> _remember_request_context -> _resolve_memory_service
       -> build_memory_service -> create_session_factory -> create_engine
       -> MemoryService.activate_resume
          -> _resume_activation_fingerprint -> repository.get
          -> repository.create 或 repository.save
       -> new AgentResponse
    -> requireMatchingResponse -> requireSuccess -> AgentResponse.retryable
    -> new AgentResumeEvaluateRequest
    -> HttpPythonAgentClient.evaluateResume
       -> AgentCallExecutor.execute -> post -> validateRequest
       -> RestClient POST /v1/agent/evaluate/resume
    -> Python evaluate_resume
       -> _remember_request_context -> _resume_evaluation_fingerprint
       -> _resolve_memory_service -> get_resume_evaluation_run
       ->（无缓存）_resolve_resume_evaluator -> build_resume_evaluation_agent
       -> ResumeEvaluationAgent.evaluate
          -> SkillRegistry.get -> PromptLoader.render -> PromptLoader.load/_resolve
          -> StructuredOutputInvoker.invoke
             -> schema.model_json_schema -> _few_shot_output
             -> _invoke_model -> AsyncRetryExecutor.execute -> model.ainvoke
             -> _validate -> _content_as_text -> _strip_json_fence
             -> ResumeEvaluation.model_validate
       -> MemoryService.record_resume_analysis -> repository.get/save
       -> new AgentResponse
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 FileUploadCard.handleUpload

文件：frontend/src/components/FileUploadCard.tsx:87-90。

~~~tsx
const handleUpload = () => {
  if (!selectedFile) return;
  onUpload(selectedFile, name.trim() || undefined);
};
~~~

1. 第 87 行定义点击处理函数，读取 selectedFile 和 name 状态。
2. 第 88 行没有选中文件时立即返回，防止向父组件提交空文件。
3. 第 89 行调用父组件 onUpload；文件作为第一个参数，名称 trim 后为空则转为 undefined。简历页只使用 File 参数。

#### 3.1.2 UploadPage.handleUpload

文件：frontend/src/pages/UploadPage.tsx:13-29。

~~~tsx
const handleUpload = async (file: File) => {
  if (!targetRole.trim()) {
    setError('请先填写目标岗位');
    return;
  }
  setUploading(true);
  setError('');
  try {
    const data = await resumeApi.uploadAndAnalyze(file, targetRole.trim());
    if (!data.storage?.resumeId) throw new Error('上传未返回简历标识');
    onUploadComplete(data.storage.resumeId);
  } catch (error) {
    setError(getErrorMessage(error));
  } finally {
    setUploading(false);
  }
};
~~~

1. 第 13 行声明异步函数，参数是浏览器 File。
2. 第 14-17 行 trim 岗位，空岗位通过 setError 写提示并 return，浏览器不会发请求。
3. 第 18 行设置 uploading=true，使控件进入处理中状态；第 19 行清空旧错误。
4. 第 21 行调用 resumeApi.uploadAndAnalyze 并等待网络 Promise；岗位在发送前再次 trim。
5. 第 22 行检查后端 data.storage.resumeId；缺失时主动抛 Error，拒绝不完整响应。
6. 第 23 行把 resumeId 交给 onUploadComplete；App 包装器随后导航历史页。此时只代表 Java 已受理 PENDING 任务。
7. 第 24-25 行捕获网络、HTTP 或业务错误，getErrorMessage 取可显示文本。
8. 第 26-28 行 finally 无论结果如何恢复 uploading=false。

#### 3.1.3 resumeApi.uploadAndAnalyze

文件：frontend/src/api/resume.ts:8-13。

~~~ts
async uploadAndAnalyze(file: File, targetRole: string): Promise<UploadResponse> {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('targetRole', targetRole);
  return request.upload<UploadResponse>('/api/resumes/upload', formData);
}
~~~

1. 第 8 行声明上传 API 并约束返回类型。
2. 第 9 行创建 FormData。
3. 第 10 行追加 file，字段名必须对应 Java 的 @RequestPart("file")。
4. 第 11 行追加 targetRole，字段名必须对应 @RequestPart("targetRole")。
5. 第 12 行调用 request.upload，路径为 /api/resumes/upload。

#### 3.1.4 request.upload 与拦截器

文件：frontend/src/api/request.ts:47-72、123-179。

request.upload 第 173-179 行调用 instance.post；第 175 行把浏览器等待超时设为 300000ms；第 176 行声明 multipart；第 178 行提取 response.data。

createClientId（47-50 行）优先使用 crypto.randomUUID，缺失时用时间和随机数拼接。currentUserId（52-58 行）读取 localStorage 的 interview-agent-user-id，不存在就生成并保存。请求拦截器第 64-73 行确保 headers 存在，写入 X-User-Id 和 X-Request-Id，并返回配置。

响应拦截器第 125-129 行检测 code=200 后把 response.data 替换为外层 data，因此页面收到的是 storage/analysis 对象；第 131-153 行解析非 200、Blob JSON、网络超时和服务不可用异常，并拒绝 Promise。

### 3.2 Java Web 函数

#### 3.2.1 RequestIdFilter.doFilterInternal 与 normalize

文件：java-backend/src/main/java/com/interviewguide/infrastructure/web/RequestIdFilter.java:23-41。

1. 第 25 行读取 X-Request-Id 并调用 normalize；normalize 第 36-41 行只接受不超过128字符且匹配 [A-Za-z0-9._:-]+ 的值，否则生成 UUID。
2. 第 26 行把 requestId 放入 Servlet attribute；第 27 行写入响应头；第 28 行放入 MDC。
3. 第 30 行进入下一个过滤器；第 31-33 行 finally 清理 MDC，避免线程复用污染。

#### 3.2.2 SimpleRateLimitFilter.doFilterInternal

文件：java-backend/src/main/java/com/interviewguide/infrastructure/ratelimit/SimpleRateLimitFilter.java:38-61。

1. 第 40-43 行放行 health 和 actuator。
2. 第 44 行用远端地址与 URI 构造限流键；第 45 行计算 epoch 分钟。
3. 第 46-47 行用 ConcurrentHashMap.compute 创建或复用窗口。
4. 第 48 行原子递增并比较 limit；超限时第 49-58 行返回 429、Retry-After 和 ApiErrorResponse，不进入 Controller；第 60 行对正常请求继续 filterChain。

#### 3.2.3 ResumeController.upload

文件：java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:23-37。

~~~java
@PostMapping("/upload")
public ApiResult<Map<String, Object>> upload(@RequestPart("file") MultipartFile file,
        @RequestPart("targetRole") String targetRole,
        @RequestHeader(value = "X-User-Id", required = false) String userId) throws IOException {
    return ApiResult.success(resumeService.upload(file, targetRole, userId));
}
~~~

1. 第 23-24 行的 Controller 和类级映射与第 32 行方法映射拼出完整路径。
2. 第 33 行由 Spring 把 multipart file 绑定为 MultipartFile。
3. 第 34 行把 targetRole part 绑定为 String。
4. 第 35 行读取可选 X-User-Id；缺失时由业务层 require 拒绝；IOException 可向异常处理器传播。
5. 第 36 行先执行 resumeService.upload，再把结果交给 ApiResult.success。

#### 3.2.4 ApiResult.success

文件：java-backend/src/main/java/com/interviewguide/common/web/dto/ApiResult.java:3-6。第 4 行接收泛型数据；第 5 行构造 code=200、message=success 的 record。Jackson 序列化后，前端响应拦截器取 data。

### 3.3 ResumeService.upload

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:74-138。

1. 第 74-77 行声明上传业务函数，输入文件、岗位、用户 ID，允许 IOException。
2. 第 78 行调用 identity.require；null 或 blank 抛 USER_ID_REQUIRED，合法值 strip 后成为 owner。
3. 第 79 行 file.isEmpty 为空文件抛 RESUME_FILE_REQUIRED。
4. 第 80-82 行检查原始文件名，缺失抛 RESUME_FILENAME_REQUIRED。
5. 第 83-85 行检查 targetRole，空白抛 TARGET_ROLE_REQUIRED。
6. 第 86 行调用 fileStorage.inspect 读取字节并生成 FileDescriptor。
7. 第 87-89 行按 owner 查询候选人；不存在时调用 idGenerator.next 创建 ID，再 candidateRepository.save。
8. 第 90-92 行按 candidateId 与 hash 查找重复文件。
9. 第 93-105 行重复分支：取候选人简历 ID、把 duplicate 设为当前版本、保存候选人、取消活动分析、重新 submit，并返回 duplicate=true、resumeId、storage.resumeId 与分析状态；不重新写文件。
10. 第 107-110 行新文件分支读取旧简历 ID并计算最大版本+1；无历史记录时版本为1。
11. 第 111 行调用 idGenerator.next 生成新 resumeId。
12. 第 113-117 行用 Tika.parseToString(file.getInputStream()) 解析文件；异常转为 RESUME_PARSE_FAILED。
13. 第 118-120 行拒绝空文本，抛 RESUME_CONTENT_EMPTY。
14. 第 121 行调用 fileStorage.store，把文件写入受控根目录。
15. 第 123-125 行构造 ResumeEntity、调用 attachFile 写入文件元数据、保存实体。
16. 第 126-129 行数据库保存失败时调用 fileStorage.delete 清理已写入文件，再重新抛异常。
17. 第 130-132 行取消旧分析、设置 candidate.currentResumeId 并保存候选人。
18. 第 133 行调用 analysisService.submit，创建 PENDING 分析并入队；返回不代表 Python 已完成。
19. 第 134-137 行组装 storage.resumeId、原文、分析状态和 analysisId 并返回。

#### 3.3.1 UserIdentityResolver.require

文件：java-backend/src/main/java/com/interviewguide/common/security/UserIdentityResolver.java:14-19。第 15-17 行拒绝 null/blank 并抛 BusinessException；第 18 行 strip；第 19 行返回规范化 ID。

#### 3.3.2 ResumeFileStorageService.inspect、sha256、store

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeFileStorageService.java:21-41、62-69。

inspect 第 22 行读取全部 bytes；第 23 行调用 sha256；第 24 行读取文件名；第 25-27 行拒绝空名；第 28 行 Path.getFileName 去除目录；第 29-30 行构造 FileDescriptor。sha256 第 64 行调用 SHA-256；第 65-67 行逐字节格式化为小写十六进制；第 68 行算法不可用时转 IllegalStateException。store 第 34 行校验 resumeId；第 35-37 行拼接并校验目标路径必须 startsWith(root)；第 38 行创建父目录；第 39 行 CREATE_NEW 写文件；第 40 行返回 StoredFile。

#### 3.3.3 实体和 Repository 函数

文件：`java-backend/src/main/java/com/interviewguide/resume/domain/ResumeEntity.java:26-54`；`java-backend/src/main/java/com/interviewguide/resume/domain/CandidateEntity.java:19-29`；`java-backend/src/main/java/com/interviewguide/resume/mapper/CandidateRepository.java:7-9`；`java-backend/src/main/java/com/interviewguide/resume/mapper/ResumeRepository.java:8-12`。

ResumeEntity（java-backend/src/main/java/com/interviewguide/resume/domain/ResumeEntity.java:26-54）：JPA 无参构造供反射使用；业务构造函数第 29-35 行保存 id、candidateId、version、content 和 createdAt；attachFile 第 48-54 行保存 hash、文件名、大小、MIME 和 storageKey。CandidateEntity（CandidateEntity.java:19-23、29）构造函数保存 id/userId/displayName，setCurrentResumeId 只更新当前简历指针。

CandidateRepository.findByUserId（CandidateRepository.java:7-9）由 Spring Data 按 user_id 查询；继承的 findById 和 save 负责主键查询及新增/更新。ResumeRepository（ResumeRepository.java:8-12）的 findFirstByCandidateIdAndFileHash 查重，findByCandidateId 取历史简历，findFirstByCandidateIdOrderByVersionDesc 取最新版本，save 持久化实体。这些是项目声明的 Repository 函数，实际 SQL 由 JPA 根据方法名生成。

### 3.4 ResumeAnalysisService 与任务持久化

#### 3.4.1 ResumeAnalysisService.submit

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:38-60。

1. 第 39-41 行校验 targetRole。
2. 第 42 行调用 requiredResume（77-80 行），通过 resumeRepository.findById 查不到时抛 RESUME_NOT_FOUND。
3. 第 43-47 行查 CandidateRepository.findById，找不到抛 CANDIDATE_NOT_FOUND，userId 不匹配抛 RESUME_ACCESS_DENIED。
4. 第 51 行调用 persistence.cancelActiveForResumeIds，取消同简历的 PENDING/PROCESSING。
5. 第 52 行调用 persistence.create，岗位使用 strip。
6. 第 54 行调用 worker.enqueue。
7. 第 55 行调用 toView（82-88 行）把实体字段转成 ResumeAnalysisView；stringList（90-94 行）和 mapList（96-100 行）解析 JSON，异常返回空集合。
8. 第 56-59 行入队失败时调用 safeMessage（102-105 行，最多500字符）写失败状态后重新抛异常。

#### 3.4.2 ResumeAnalysisPersistenceService

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisPersistenceService.java:28-89。

create 第 29-30 行构造 ResumeAnalysisEntity 并 repository.save；cancelActiveForResumeIds 第 63 行空集合直接返回，第 64-65 行查询 PENDING/PROCESSING 并逐个调用实体 cancel；beginAttempt 第 69-74 行 required 查询实体，canBeginAttempt 为 false 返回 null，否则 beginAttempt 并返回；required 第 86-89 行按 id 查询，不存在抛 RESUME_ANALYSIS_NOT_FOUND。

ResumeAnalysisEntity（java-backend/src/main/java/com/interviewguide/resume/domain/ResumeAnalysisEntity.java:45-109）：构造函数把状态设为 PENDING；beginAttempt 把状态设为 PROCESSING、retryCount 加一并更新时间；canBeginAttempt 只允许 PENDING/PROCESSING；cancel 只将这两种状态改为 CANCELLED；isCancelled 判断 CANCELLED。Python 返回后实际调用的 complete、fail、recordRetryableFailure 与 truncate 也在本文后续各自列出源码行号并逐句解释，当前段落不以概括替代这些独立函数解析。

#### 3.4.3 ResumeAnalysisWorker.enqueue

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java:48-52。第 49 行选择 exchange；第 50 行选择 routing key；第 51 行构造 AgentWorkTaskMessage 并调用 RabbitTemplate.convertAndSend。AgentWorkTaskMessage record（AgentWorkTaskMessage.java:5-7）提供 taskType/resourceId/userId 访问器及 RESUME_ANALYSIS 常量。消息只携带 ID，不携带原文。

### 3.5 RabbitMQ 消费和 Java Worker

#### 3.5.1 RabbitAgentWorkConsumer.consume

文件：java-backend/src/main/java/com/interviewguide/infrastructure/messaging/RabbitAgentWorkConsumer.java:22-39。第 24-27 行校验消息及三个字段；第 29-35 行按 taskType 分派，简历任务把 resourceId 转 Long 后调用 resumeAnalysisWorker.process；第 36-38 行捕获非数字 resourceId 并丢弃。

#### 3.5.2 ResumeAnalysisWorker.process

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java:54-125。

1. 第 55 行查询分析；第 58-59 行不存在或已取消直接返回。
2. 第 61-64 行查询简历，不存在返回。
3. 第 65-68 行调用 isCurrentResume（131-134 行），内部通过 CandidateRepository.findById 比较 candidate.currentResumeId；不是当前版本则 cancel 并返回。
4. 第 69-71 行调用 persistence.beginAttempt；不能开始时返回。
5. 第 74-81 行生成 activationRunId、activationSessionId，并构造 AgentResumeMemoryActivationRequest；UUID.randomUUID 生成 requestId。
6. 第 76 行调用 pythonAgentClient.activateResumeMemory；第 82-84 行调用 requireMatchingResponse。
7. 第 85-88 行再次检查 current resume，防止 Python 激活期间版本替换。
8. 第 89-96 行构造 evaluationRunId/evaluationSessionId 和 AgentResumeEvaluateRequest，再调用 evaluateResume。
9. 第 97-103 行检查 code；可重试错误第 100 行抛 PythonAgentException，不可重试第 102 行 persistence.fail。
10. 第 105-112 行校验响应身份；未取消且仍为当前版本时 persistence.complete，否则 cancel。
11. 第 113-124 行捕获 RuntimeException；可重试且未超 maxDeliveryAttempts 时 recordRetryableFailure 后重新抛出，其他错误写 FAILED。

requireSuccess（136-142 行）拒绝 null 或非 1xx/2xx，并按 response.retryable 构造异常。requireMatchingResponse（144-154 行）先调用 requireSuccess，再比较 userId/sessionId/runId。safeMessage（156-160 行）截断异常信息到500字符。AgentResponse.retryable（pythonagent/dto/AgentResponse.java:24-26）只有 error 非空、error.retryable 且 code 为5xx时返回 true。

### 3.6 Java HTTP 适配器

文件：java-backend/src/main/java/com/interviewguide/pythonagent/mapper/HttpPythonAgentClient.java:43-96；java-backend/src/main/java/com/interviewguide/infrastructure/reliability/AgentCallExecutor.java:22-43。

activateResumeMemory 第 47 行把 post('/v1/agent/resume/activate', request) 交给 callExecutor；evaluateResume 第 46 行同理，路径为 /v1/agent/evaluate/resume。AgentCallExecutor.execute 第 24 行循环 attempts，第 26 行执行 Supplier，第 27-31 行仅对 retryable 异常且尚有次数时 sleepBeforeRetry；第 33 行耗尽后抛最后异常。sleepBeforeRetry 第 38 行 sleep，InterruptedException 时恢复中断标志并抛异常。

HttpPythonAgentClient.post 第 66 行调用 validateRequest；第 68 行 RestClient POST 并反序列化 AgentResponse；第 69-70 行拒绝空响应；第 71-79 行区分 PythonAgentException、HTTP 响应异常和普通网络异常。parseStructuredError（82-87 行）尝试把错误响应体解析成结构化 AgentResponse。validateRequest（89-96 行）调用 Validator.validate，收集约束字段并在有错误时抛不可重试异常。

### 3.7 Python 激活端点

#### 3.7.1 activate_resume_memory

文件：python-agent/app/api/application.py:205-221。

1. FastAPI 先按 AgentResumeMemoryActivationRequest 校验 alias、必填字段和 operation。
2. 第 209 行调用 _remember_request_context（388-392 行），通过 payload.model_dump(by_alias=True, mode='json') 保存异常上下文。
3. 第 210 行调用 _resolve_memory_service（348-354 行）；state 为空时调用 build_memory_service 并缓存。
4. 第 210-214 行调用 MemoryService.activate_resume，传入 userId、subjectId、candidateId、inputText、targetRole 和 runId。
5. 第 215-221 行构造 code=100、COMPLETED、ACTIVE 的 AgentResponse。

build_memory_service（python-agent/app/bootstrap.py:32-37）第 33 行读取 settings，第 34 行 create_session_factory，第 35-36 行构造 PostgresLongTermMemoryRepository 和 MemoryPolicy。create_session_factory（python-agent/app/infrastructure/persistence/database.py:16-19）调用 create_engine；create_engine（9-13 行）检查 DATABASE_URL 后创建 AsyncEngine。MemoryPolicy.load（python-agent/app/memory/policy.py:18-40）读取 JSON、转换配置并校验窗口和保留数量。

#### 3.7.2 MemoryService.activate_resume

文件：python-agent/app/memory/service.py:49-97。

1. 第 59-62 行调用 _resume_activation_fingerprint（258-267 行），对 resumeId、candidateId、resumeText、targetRole 做稳定 JSON 编码并计算 SHA-256。
2. 第 63-66 行构造 ResumeMemory 快照。
3. 第 67 行 repository.get 读取用户长期记忆。
4. 第 68-76 行无记忆时创建 LongTermMemory；有 runId 时写 ResumeActivationRun；调用 repository.create。
5. 第 77-81 行重复 runId 时比较 resumeId 和 fingerprint，不同抛 ConsistencyError，相同直接返回。
6. 第 82-89 行已有记忆时切换 active_resume_id，调用 _merge_resume_snapshot（250-252 行）用新快照替换同 resumeId 的旧快照，并清空旧评价衍生的技术栈/深度/偏好。
7. 第 90-96 行写入 activation run、按策略裁剪旧 run、更新时间。
8. 第 97 行 repository.save 以 expected_version 做乐观锁更新。

PostgresLongTermMemoryRepository（python-agent/app/infrastructure/persistence/long_term_memory_repository.py:31-86）中，get 第 31-38 行开异步会话并按 user_id 查询、model_validate JSON；create 第 40-47 行 add entity、commit，IntegrityError 转 ConsistencyError；save 第 49-76 行把版本加一，构造带旧版本条件的 UPDATE，检查 rowcount 后提交；_to_entity 第 79-86 行完成 Pydantic 到 ORM 转换。

### 3.8 Python 评价端点

#### 3.8.1 evaluate_resume

文件：python-agent/app/api/application.py:156-203。

1. 第 158 行保存请求上下文。
2. 第 159 行调用 _resume_evaluation_fingerprint（435-441 行），按 subjectId、inputText、targetRole 固定编码并 SHA-256。
3. 第 160-164 行解析 MemoryService 并调用 get_resume_evaluation_run；命中相同 runId/指纹时直接复用。
4. 第 165-170 行无缓存时调用 _resolve_resume_evaluator(request).evaluate。
5. 第 172-188 行调用 record_resume_analysis，把评分、摘要、issues、suggestions、技术栈、深度和偏好写入长期记忆。
6. 第 189-196 行捕获 ConsistencyError 并尝试 replay。
7. 第 197-203 行构造 code=100 的 AgentResponse，output 使用 result.model_dump(by_alias=True)，输出字段为 camelCase。

#### 3.8.2 ResumeEvaluationAgent.evaluate

文件：python-agent/app/agents/evaluation/agent.py:25-50。

1. 第 32 行 strip 简历文本；第 33-34 行空文本抛 ValueError。
2. 第 36 行 SkillRegistry.get('resume-analyst')。
3. 第 37-39 行 PromptLoader.render('resume/analysis.md', {'skill_instructions': skill.instructions})。
4. 第 40-44 行组装 subjectId、targetRole、resumeText。
5. 第 45-50 行调用 StructuredOutputInvoker.invoke，要求 ResumeEvaluation 模型输出。

SkillRegistry.get（python-agent/app/tools/skills/loader.py:47-84）校验 ID 正则，读取 skill.json/SKILL.md，检查 enabled、ID 和 allowedTools，最后构造 SkillDefinition。PromptLoader.render（python-agent/app/common/prompt_loader.py:26-40）先 load，再逐个替换 {{变量}}，缺变量或残留占位符时抛异常；load（19-24 行）读取 UTF-8；_resolve（42-46 行）阻止路径越界。

#### 3.8.3 StructuredOutputInvoker.invoke 及模型调用

文件：python-agent/app/infrastructure/reliability/structured_output.py:23-120。

1. 第 26-28 行构造函数保存 prompt loader 和 retry executor。
2. invoke 第 38-45 行渲染 shared/structured-output.md，调用 schema.model_json_schema 和 _few_shot_output（123-154 行）生成格式约束。
3. 第 46-49 行创建 SystemMessage/HumanMessage，输入以 JSON 序列化。
4. 第 50-69 行循环调用 _invoke_model；成功时 _validate；解析或校验失败时追加修正消息，超过次数抛 ModelOutputError。
5. _invoke_model（72-75 行）无执行器时直接 model.ainvoke，有执行器时交给 AsyncRetryExecutor.execute。
6. _validate（77-84 行）调用 _content_as_text、_strip_json_fence、json.loads 和 schema.model_validate。
7. _content_as_text（87-104 行）兼容字符串、消息 content、列表片段和映射；无法提取文本时抛 TypeError。
8. _strip_json_fence（107-112 行）移除 Markdown 三反引号围栏。
9. _readable_validation_error（115-120 行）把字段路径或异常压缩到500字符。
10. AsyncRetryExecutor.execute（python-agent/app/infrastructure/reliability/retry.py:23-40）用 asyncio.wait_for 限制单次调用超时，按策略判断重试并退避；_is_retryable（42-43 行）按异常类名匹配；_backoff_seconds（45-50 行）计算指数退避。

ResumeEvaluation 模型（python-agent/app/agents/evaluation/models.py:14-29）通过 Pydantic 校验六个评分范围、summary 长度、列表上限和 issue priority；alias 让输出字段匹配 Java 的 overallScore、technicalStack 等名称。LLMFactory.create_chat_model（python-agent/app/agents/llm/factory.py:12-39）校验 provider/model/API key，组装 timeout、temperature、max_retries=0 等参数并返回 ChatOpenAI。

#### 3.8.4 MemoryService.record_resume_analysis

文件：python-agent/app/memory/service.py:177-234。第 185-187 行读取记忆；第 188-193 行对相同 runId 做幂等校验；第 194-196 行要求 active_resume_id 匹配；第 197-217 行更新或追加 ResumeMemory，截断问题和建议；第 220-223 行调用 _unique_items（275-277 行）去空白、去重和限长；第 224-232 行记录 ResumeEvaluationRun 并淘汰旧 run；第 233-234 行更新时间并 repository.save。

## 4. 返回、失败与边界

### 4.1 正常返回

同步返回的 data.storage.resumeId 是简历 ID，data.analysis.status 是 PENDING，data.analysis.analysisId 是后续 Rabbit 任务的分析主键。HTTP 200 只表示上传和任务受理成功，Python 模型可能仍在 PROCESSING。

### 4.2 失败处理

BusinessException、PythonAgentException、校验异常、数据访问异常和未知异常由 ApiExceptionHandler（java-backend/src/main/java/com/interviewguide/common/web/ApiExceptionHandler.java:31-129）统一转换。常见失败包括用户 ID 缺失、空文件、空文件名、岗位缺失、解析失败、空文本、路径越界、数据库失败、Rabbit 发布失败和 Python 依赖失败。异步 Worker 对可重试 Python 异常记录 retryable failure 并让 Rabbit 重投；最终失败写入 FAILED。

### 4.3 一致性边界

1. 文件写入后数据库保存失败时调用 delete 补偿。
2. 消息只携带 ID，消费者重新查库，避免把原文和实体塞进队列。
3. 新版本通过 currentResumeId 和前后两次 isCurrentResume 检查，旧结果不能覆盖新版本。
4. Python 激活和评价都用 runId/fingerprint 幂等，长期记忆用 state_version 乐观锁。
5. Java AgentCallExecutor 只对可恢复依赖有限重试。因此不能把 HTTP 200 解释为评价已经完成。

## 5. 源码文件与行号索引

- 前端：frontend/src/components/FileUploadCard.tsx:87-90；frontend/src/pages/UploadPage.tsx:13-29；frontend/src/api/resume.ts:8-13；frontend/src/api/request.ts:47-72、123-179。
- Java 入口：java-backend/src/main/java/com/interviewguide/infrastructure/web/RequestIdFilter.java:23-41；java-backend/src/main/java/com/interviewguide/infrastructure/ratelimit/SimpleRateLimitFilter.java:38-61；java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:23-37。
- Java 业务：java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:74-138；ResumeAnalysisService.java:38-60；ResumeAnalysisPersistenceService.java:28-89；ResumeAnalysisWorker.java:48-160。
- Python：python-agent/app/api/application.py:156-221、312-354、388-441；python-agent/app/memory/service.py:49-97、177-248；python-agent/app/agents/evaluation/agent.py:12-50；python-agent/app/infrastructure/reliability/structured_output.py:23-120。


### 3.9 Python 响应辅助函数

#### 3.9.1 _resolve_memory_service、_resolve_resume_evaluator

文件：python-agent/app/api/application.py:340-354。

1. _resolve_resume_evaluator 第 341 行从 request.app.state 读取 resume_evaluator。
2. 第 342 行判断是否为空；第 343 行为空时调用 build_resume_evaluation_agent。
3. 第 344 行把新实例写回 state，避免下一次请求重复组装模型客户端。
4. 第 345 行返回 evaluator。
5. _resolve_memory_service 第 349 行读取 memory_service；第 350 行判断空值；第 351-353 行懒加载并缓存 MemoryService；第 354 行返回缓存对象。
6. 这两个函数只负责依赖解析，不执行评价或记忆业务；它们的“首次构造”和“后续复用”是两个实际分支。

#### 3.9.2 _resume_evaluation_fingerprint

文件：python-agent/app/api/application.py:435-441。

1. 第 436 行用 json.dumps 开始构造规范化字符串。
2. 第 437-439 行只纳入 subjectId、inputText、targetRole，ensure_ascii=False 保留中文，sort_keys=True 固定键顺序，紧凑 separators 消除无意义空格。
3. 第 441 行把 UTF-8 字符串交给 hashlib.sha256 并返回十六进制摘要。
4. 这个值与 Java 的 evaluationRunId 配合，保证同一 runId 不能写入不同的简历内容。

#### 3.9.3 MemoryService.get_resume_evaluation_run

文件：python-agent/app/memory/service.py:236-248。

1. 第 239 行通过 repository.get 读取用户记忆；第 240-241 行没有记忆时返回 None。
2. 第 242 行从 resume_evaluation_runs 按 run_id 取记录；第 243-244 行不存在时返回 None。
3. 第 245-247 行比较已有记录的 resume_id 和 fingerprint；任意不一致抛 ConsistencyError。
4. 第 248 行返回已有的 evaluation 对象，调用方随后跳过大模型。

#### 3.9.4 Python AgentResponse 与契约校验

文件：python-agent/app/common/contracts.py:125-185。

1. AgentEvaluationRequest 第 128-138 行把 apiVersion、requestId、runId、userId、sessionId、operation、subjectType、subjectId、candidateId、inputText、targetRole 声明为别名字段；Pydantic 在进入路由函数前检查最小长度和 operation literal。
2. AgentResumeMemoryActivationRequest 第 145-155 行声明同一公共字段及 subjectId/candidateId/inputText/targetRole。
3. AgentResponse 第 161-175 行声明返回字段、code、status、sessionStatus、stateVersion、output、error，并以当前 UTC 时间作为 timestamp 默认值。
4. validate_code_category 第 177-182 行检查 code 的百位必须在1到5，非法 code 在路由函数执行前被拒绝。
5. to_json_dict 第 184-185 行以 JSON 模式、字段别名和不排除 None 的方式导出，保证 Java 能收到完整错误或成功契约。

### 3.10 Python 写回分析与 Java 完成状态

#### 3.10.1 ResumeAnalysisPersistenceService.complete

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisPersistenceService.java:33-45。

1. 第 34 行接收 Python AgentResponse。
2. 第 35 行读取 response.output；第 36-38 行为空时抛 RESUME_ANALYSIS_OUTPUT_MISSING。
3. 第 39-44 行调用 required(id)，并按字段调用 integer、string、json 后传给实体 complete。
4. integer（91-95 行）读取 Map 值，Number 转 int；不是 Number 时抛 RESUME_ANALYSIS_OUTPUT_INVALID。
5. string（97-101 行）要求值是非空 String，否则抛同一错误。
6. json（103-106 行）把 null 转空列表并由 ObjectMapper 序列化；JsonProcessingException 转业务异常。
7. required（86-89 行）按分析主键查询，找不到抛 RESUME_ANALYSIS_NOT_FOUND。

#### 3.10.2 ResumeAnalysisEntity.complete、fail、truncate

文件：java-backend/src/main/java/com/interviewguide/resume/domain/ResumeAnalysisEntity.java:69-100、131-133。

1. 五参数 complete（69-75 行）把默认 issuesJson 设为 [] 后转调完整重载；它本身没有额外持久化动作。
2. 完整 complete（77-94 行）第 81 行状态设为 COMPLETED；第 82-91 行逐项写入六个评分、summary、strengthsJson、suggestionsJson、issuesJson，并清空 error；第 93 行更新 updatedAt。
3. fail（96-100 行）第 97 行状态设为 FAILED；第 98 行调用 truncate 保存错误；第 99 行更新时间。
4. truncate（131-133 行）消息为 null 时使用默认文本，否则保留最多500字符，防止数据库 error 列超长。
5. getId/getResumeId/getTargetRole/getStatus 等访问函数（111-129 行）逐个返回对应字段；Worker 使用 getResumeId、getTargetRole，Persistence 使用 getStatus、getRetryCount，View 转换使用评分、summary、JSON 和时间字段。它们没有副作用，不会改变状态。

#### 3.10.3 Worker 完成和重试分支

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java:97-124。

1. Python 返回 code 非 1xx/2xx时进入第 97 行条件。
2. 第 98 行从 response.error 读取消息；第 99 行判断 retryable。
3. 第 100 行对可恢复错误抛异常，使 Rabbit 监听器触发重新投递。
4. 第 102-103 行对不可恢复错误调用 persistence.fail 并正常返回，消息不会继续重试。
5. Python 返回成功后第 105-107 行 requireMatchingResponse 验证身份。
6. 第 108 行检查任务未取消且 isCurrentResume 为真。
7. 第 109 行调用 persistence.complete；第 110-112 行只要任务未取消但简历已替换，就把任务取消。
8. 第 113-124 行捕获 activate、evaluate、数据库或解析异常；第 114-116 行已取消任务直接结束；第 117-120 行可重试且未超过 maxDeliveryAttempts 时记录错误并抛出；第 121-123 行否则写 FAILED。

### 3.11 项目函数与框架函数的边界

1. Spring 的 multipart 解析、参数绑定、@RequestPart 校验、DispatcherServlet 路由和异常分派不是项目定义函数，因此本文只在 Controller 入口处说明其结果。
2. JpaRepository.findById、save 以及项目 Repository 的派生查询方法由项目声明、由 Spring Data 实现；本文分别列出声明文件和调用语义，没有虚构实现类。
3. RabbitTemplate.convertAndSend、RabbitListener 消费循环、RestClient.retrieve/body、Tika.parseToString、MultipartFile.getBytes/getInputStream、Validator.validate 和 ChatOpenAI.ainvoke 都是第三方/框架函数；它们在链路中的入参、返回值和失败边界已逐行标出。
4. Pydantic record/model 的默认构造、model_dump、model_validate、model_json_schema 是模型库函数；项目代码定义的是请求契约字段、调用位置以及别名，不把生成代码冒充为业务函数。
5. RabbitMQ 配置函数在 java-backend/src/main/java/com/interviewguide/infrastructure/messaging/RabbitTaskConfiguration.java:25-61 于应用启动时创建交换机、队列、绑定和 RabbitTemplate；它们不是每次上传请求重新调用，但决定 enqueue 能否把消息送到 consume。

## 6. 逐函数核对结论

本链路已覆盖以下实际项目函数：前端 FileUploadCard.handleUpload、UploadPage.handleUpload、resumeApi.uploadAndAnalyze、request.upload、createClientId、currentUserId；Java RequestIdFilter.doFilterInternal/normalize、SimpleRateLimitFilter.doFilterInternal、ResumeController.upload、ApiResult.success、UserIdentityResolver.require、ResumeService.upload、ResumeFileStorageService.inspect/sha256/store、ResumeAnalysisService.submit/requiredResume/toView/stringList/mapList/safeMessage、ResumeAnalysisPersistenceService.create/cancelActiveForResumeIds/beginAttempt/required/complete/fail/integer/string/json、ResumeAnalysisWorker.enqueue/process/isCurrentResume/requireSuccess/requireMatchingResponse/safeMessage、RabbitAgentWorkConsumer.consume、AgentCallExecutor.execute/sleepBeforeRetry、HttpPythonAgentClient.activateResumeMemory/evaluateResume/post/validateRequest/parseStructuredError，以及 Python activate_resume_memory/evaluate_resume/_remember_request_context/_resolve_memory_service/_resolve_resume_evaluator/_resume_evaluation_fingerprint、MemoryService.activate_resume/get_resume_evaluation_run/record_resume_analysis/_merge_resume_snapshot/_unique_items、PostgresLongTermMemoryRepository.get/create/save/_to_entity、ResumeEvaluationAgent.__init__/evaluate、SkillRegistry.get、PromptLoader.load/render/_resolve、StructuredOutputInvoker.__init__/invoke/_invoke_model/_validate/_content_as_text/_strip_json_fence/_readable_validation_error/_few_shot_output、AsyncRetryExecutor.execute/_is_retryable/_backoff_seconds、LLMFactory.create_chat_model，以及相关实体状态函数。\n\n这条链的终点是 Python 两个 FastAPI 路由已经完成自身 service 调用并返回 AgentResponse；Java 收到后继续执行状态校验和分析持久化，但不会把 Python 的成功响应误当成浏览器同步等待的模型结果。\n


### 3.12 文件选择、前端回调与错误辅助函数

#### 3.12.1 FileUploadCard.handleDrop 与 handleFileChange

文件：frontend/src/components/FileUploadCard.tsx:69-85。

1. handleDrop 第 69 行定义拖放回调；第 70 行 preventDefault 阻止浏览器打开文件；第 71 行清除 dragOver；第 72 行读取 dataTransfer.files；第 73 行判断至少一个文件；第 74 行选择第一个文件；第 75 行调用可选的 onFileSelect；第 77 行声明依赖。
2. handleFileChange 第 79 行定义 input change 回调；第 80 行读取 input.files；第 81 行判断列表存在且非空；第 82 行保存第一个 File；第 83 行调用 onFileSelect；第 85 行声明依赖。
3. 两个函数都不会直接发请求，真正的请求由后续 handleUpload 发起；这两个入口解释了“点击选择”和“拖拽上传”两条前端可达路径。

#### 3.12.2 App.UploadPageWrapper.handleUploadComplete

文件：frontend/src/App.tsx:29-38。第 33 行定义上传完成回调；第 35 行调用 navigate('/history', {state:{newResumeId:resumeId}})。它只改变前端路由，不查询 Python，也不表示分析已经完成。

#### 3.12.3 前端错误函数

文件：frontend/src/api/request.ts:75-121、185-200。

- isRecord（75-77 行）只接受非 null、object 且非数组的值。
- stringValue（79-81 行）只返回非空字符串。
- parseApiError（83-99 行）先拒绝非对象，再取嵌套 error 或外层 code；第 88-98 行构造 ApiRequestError，保留 retryable、HTTP 状态、requestId、runId、sessionId 和 stage。
- decodeErrorData（101-108 行）只对 JSON Blob 调 text 和 JSON.parse，解析失败返回原 Blob。
- transportError（110-121 行）根据 URL 或 Content-Type 判断是否上传，超时时返回 NETWORK_TIMEOUT，其余返回 NETWORK_UNAVAILABLE。
- getErrorMessage（185-187 行）对 Error 返回 message，否则返回“发生未知错误”。
- getErrorDisplayMessage（190-200 行）先调用 getErrorMessage；ApiRequestError 还会拼接错误码、阶段、是否可重试和 requestId。这些函数最终服务于 UploadPage.catch。

### 3.13 Java ID 与任务状态辅助函数

#### 3.13.1 BusinessIdGenerator.next

文件：java-backend/src/main/java/com/interviewguide/common/id/BusinessIdGenerator.java:13-16。

1. 第 14 行用 AtomicLong.updateAndGet，在当前 JVM 内取“当前毫秒时间”和“上一次 ID+1”的较大值，保证同一毫秒并发上传仍不冲突。
2. 第 15 行把 long 转为字符串，作为 candidateId 或 resumeId。
3. 第 16 行返回该字符串。该实现只提供单 JVM 单调 ID，多实例部署需要换成数据库序列或雪花 ID。

#### 3.13.2 ResumeAnalysisPersistenceService.cancel、isCancelled

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisPersistenceService.java:81-84。

1. cancel 第 82 行调用 required(id)，再调用实体 cancel；事务提交后旧消息会在 Worker 的第一道检查中被丢弃。
2. isCancelled 第 84 行调用 required(id).isCancelled；如果记录不存在，required 先抛 RESUME_ANALYSIS_NOT_FOUND，而不是把不存在当成可取消。
3. Worker 第 114 行和第 121 行使用该函数区分“用户已取消的正常旧消息”和“真正的基础设施异常”。


### 3.14 上传接口审核补充：此前合并说明的函数逐项展开

#### 3.14.1 ResumeAnalysisWorker.isRetryable

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java:127-129。

1. 第 127 行声明私有布尔函数，输入为 process 捕获到的 RuntimeException。
2. 第 128 行使用 Java 模式匹配；只有 error 是 PythonAgentException 时才绑定为 gatewayError。
3. 同一行随后调用 gatewayError.retryable()；只有 Python HTTP 客户端明确标记可重试的异常才返回 true。
4. 第 129 行结束函数。普通数据库、序列化或编程异常不会被这个函数误当作可重试 Python 依赖错误。

#### 3.14.2 ResumeAnalysisPersistenceService.recordRetryableFailure

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisPersistenceService.java:76-79。

1. 第 76 行标注 Transactional，保证状态更新在数据库事务中提交。
2. 第 77 行声明函数，接收分析主键与经 safeMessage 截断后的错误文本。
3. 第 78 行先调用 required(id) 查询任务，缺失时抛 RESUME_ANALYSIS_NOT_FOUND；查到后调用实体 recordRetryableFailure。
4. 第 79 行结束函数。它只记录错误及更新时间，刻意不把状态改为 FAILED，以便 Rabbit 可以再次投递。

#### 3.14.3 ResumeAnalysisEntity.recordRetryableFailure

文件：java-backend/src/main/java/com/interviewguide/resume/domain/ResumeAnalysisEntity.java:64-67。

1. 第 64 行定义状态辅助函数，参数是可重试错误文本。
2. 第 65 行调用 truncate，把消息限制为数据库 error 字段的安全长度并写入 error。
3. 第 66 行把 updatedAt 设为当前 Instant。
4. 第 67 行结束函数。status 保持 PROCESSING 或 PENDING，因此 beginAttempt 仍可接受后续重新消费。

#### 3.14.4 ResumeAnalysisPersistenceService.fail 与 ResumeAnalysisEntity.fail

文件：ResumeAnalysisPersistenceService.java:47-49；ResumeAnalysisEntity.java:96-100。

1. Persistence 的第 47 行标注 Transactional；第 48 行先 required(id) 再调用实体 fail；第 49 行结束一行委托函数。
2. 实体 fail 的第 96 行定义函数；第 97 行把 status 固定写成 FAILED；第 98 行截断并保存错误；第 99 行更新时间；第 100 行结束。
3. 因此不可重试 Python 返回、超出 Rabbit 尝试上限的异常都会持久化为最终失败，而不是继续留下不明确的 PROCESSING。

#### 3.14.5 AgentCallExecutor 的构造函数和 sleepBeforeRetry

文件：java-backend/src/main/java/com/interviewguide/infrastructure/reliability/AgentCallExecutor.java:16-20、36-43。

1. 构造函数第 16-17 行接收配置的 maxAttempts 与 backoffMillis。
2. 第 18 行用 Math.max(1, maxAttempts) 保证至少执行一次。
3. 第 19 行用 Math.max(0, backoffMillis) 防止负睡眠时间。
4. sleepBeforeRetry 第 37 行进入 try；第 38 行 Thread.sleep(backoffMillis)。
5. 第 39-42 行捕获 InterruptedException；第 40 行恢复线程中断标志；第 41 行包装成不可重试 PythonAgentException；第 43 行结束。
6. 该函数只由 execute 的第 30 行调用，且仅在还有重试机会时调用。

#### 3.14.6 HttpPythonAgentClient 的两个入口函数和请求 DTO

文件：java-backend/src/main/java/com/interviewguide/pythonagent/mapper/HttpPythonAgentClient.java:46-47；AgentResumeEvaluateRequest.java:8-20；AgentResumeMemoryActivationRequest.java:8-20。

1. evaluateResume 第 46 行创建 Supplier lambda；Supplier 被 execute 重试时会重新进入 post，路径恒为 /v1/agent/evaluate/resume。
2. activateResumeMemory 第 47 行采用相同结构，路径恒为 /v1/agent/resume/activate。
3. 两个 Java record 都在第 8 行声明规范化请求字段；第 9-19 或第 9-20 行为 apiVersion、requestId、runId、userId、sessionId、operation、主体标识、简历文本、岗位和 timestamp 标记 NotBlank/NotNull。
4. record 的成员访问器由 Java 编译器生成，源码没有自定义实现；业务代码通过构造参数把 ResumeEntity 和 ResumeAnalysisEntity 的值映射到 HTTP JSON。

#### 3.14.7 Python build_resume_evaluation_agent

文件：python-agent/app/bootstrap.py:86-96。

1. 第 86-88 行定义 builder，可选传入 Settings，测试可传入替代配置。
2. 第 89 行选择传入 settings 或调用 get_settings。
3. 第 90 行调用 RetryPolicy.load，读取模型调用的次数、超时和退避配置。
4. 第 91 行开始构造 ResumeEvaluationAgent。
5. 第 92 行调用 LLMFactory.create_chat_model，创建实际 ChatOpenAI 客户端但尚未发模型请求。
6. 第 93 行构造 PromptLoader；第 94 行构造 SkillRegistry；第 95 行传入 retry executor；第 96 行结束并返回 Agent。
7. state 缓存由 _resolve_resume_evaluator 负责，builder 本身不写 request.app.state。

#### 3.14.8 LLMFactory.create_chat_model

文件：python-agent/app/agents/llm/factory.py:12-39。

1. 第 13 行定义静态工厂；第 14 行取得 Settings。
2. 第 16-20 行声明并检查允许的 provider，未知 provider 抛 ModelConfigurationError。
3. 第 21-24 行分别要求 model_name 与 model_api_key 非空。
4. 第 26-33 行组装 model_kwargs：模型名、密钥、temperature、timeout 和 max_retries=0；重试由 AsyncRetryExecutor 统一控制。
5. 第 34-35 行可选加入 base_url；第 36-37 行可选加入 max_tokens。
6. 第 39 行用这些参数构造并返回 ChatOpenAI。网络调用只发生在后续 model.ainvoke。

#### 3.14.9 RetryPolicy.load

文件：python-agent/app/infrastructure/reliability/policy.py:20-45。

1. 第 21 行定义类方法并允许测试指定 JSON 路径。
2. 第 22 行选择传入路径或 resources/agent/reliability.json。
3. 第 24 行读取 UTF-8 JSON；第 25-31 行把每项转换为明确类型并构造 policy。
4. 第 33-34 行把文件不存在、键缺失、类型和值错误转为 ReliabilityConfigurationError。
5. 第 35-44 行分别验证次数范围、退避大小关系、单次超时范围、输出修复次数以及可重试错误集合非空。
6. 第 45 行返回通过审核的不可变策略。

#### 3.14.10 StructuredOutputInvoker._few_shot_output

文件：python-agent/app/infrastructure/reliability/structured_output.py:123-154。

1. 第 123-124 行定义函数和用途：为不同 Pydantic schema 提供合法最小示例。
2. 第 125-149 行建立 examples 字典，其中 ResumeEvaluation 的示例在第 126-131 行提供 Java 持久化所需的评分、摘要、列表和技术画像字段。
3. 第 150-153 行为 CrawlPageDecision 单独返回示例；该分支不会被简历评价调用。
4. 第 154 行用 schema.__name__ 从 examples 取值，未知 schema 返回空字典。
5. ResumeEvaluationAgent 调用 invoke 时会命中 ResumeEvaluation 分支，因此 format_prompt 中带有可验证样例。


#### 3.14.11 ResumeFileStorageService.delete：上传失败补偿

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeFileStorageService.java:49-54。

1. 第 49 行定义删除函数并接收 storageKey。
2. 第 50 行对 null 或 blank key 直接 return；这保证补偿调用本身幂等。
3. 第 51 行把 key 解析到 root 下并 normalize。
4. 第 52 行检查路径仍以 root 开头；不满足时抛 IOException，防止删除根目录外文件。
5. 第 53 行调用 Files.deleteIfExists；文件已不存在时不抛异常。
6. 第 54 行结束函数。ResumeService.upload 第 127 行只在 ResumeRepository.save 抛 RuntimeException 后调用它，因此其责任是回收“文件已成功写入、数据库事务未完成”的孤立文件。
