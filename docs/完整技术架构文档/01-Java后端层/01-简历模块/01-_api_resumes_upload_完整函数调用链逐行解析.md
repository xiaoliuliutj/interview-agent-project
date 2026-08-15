# POST /api/resumes/upload：简历上传、异步分析与 Python 调用完整解析

> 本文以当前工作区代码为准。行号均为文中列出的源文件当前行号；不把 Spring、MyBatis、RabbitMQ、Axios、PostgreSQL 或模型 SDK 内部实现误写成项目自定义函数。上传请求同步返回的是“已创建并已投递”的分析任务；真正的 Python 调用发生在 RabbitMQ 消费端。

## 1. 接口定义

### 1.1 功能与作用

该接口接收浏览器提交的简历文件和目标岗位，完成身份键校验、文件哈希计算、同一候选人的重复文件识别、简历版本写入、分析任务创建与 RabbitMQ 投递。Java 不在 HTTP 线程中等待大模型结果。任务消费者随后先调用 Python 的“激活简历记忆”接口，再调用“简历评估”接口；结果写入 Java 的 `resume_analyses` 表，并在数据库事务提交后更新 Java 专属 Redis 状态快照。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| HTTP 方法与路径 | `POST /api/resumes/upload` |
| Controller | `ResumeController.upload`，`java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:32-37` |
| 请求类型 | `multipart/form-data` |
| 必填 Part | `file`、`targetRole` |
| 身份头 | `X-User-Id`；Controller 可缺省接收，但 `UserIdentityResolver.require` 会拒绝空值 |
| 追踪头 | 前端自动写入 `X-Request-Id`；Java 在响应头回传它 |
| 可选幂等头 | `X-Idempotency-Key`；只有客户端提供时才进入 `IdempotencyFilter` |
| 同步成功响应 | `ApiResult`，其中 `storage.resumeId` 与 `analysis.analysisId` 可供前端后续查询 |
| 同步完成边界 | RabbitMQ 消息已由 `ResumeAnalysisWorker.enqueue` 发出；不代表 Python 已完成 |
| 异步 Python 路径 | `POST /v1/agent/resume/activate`，再到 `POST /v1/agent/evaluate/resume` |
| 数据库访问方式 | MyBatis Mapper：`CandidateRepository.xml`、`ResumeRepository.xml`、`ResumeAnalysisRepository.xml`；没有 JPA Repository |
| Redis 所有权 | Java 专属 `redis-java`；任务状态键为 `java:task:resume-analysis:*`，PostgreSQL 仍是最终事实来源 |

### 1.3 前端访问入口

前端入口是 `frontend/src/pages/UploadPage.tsx`。页面把 `FileUploadCard` 的 `onUpload` 回调绑定为 `handleUpload`，再由 `resumeApi.uploadAndAnalyze` 发送请求。`frontend/src/api/request.ts` 的 Axios 请求拦截器为每次请求补充 `X-User-Id` 和 `X-Request-Id`。当前前端没有为上传接口默认生成 `X-Idempotency-Key`，因此幂等过滤器在普通页面请求中会跳过；接口调用方可以显式补充该头启用幂等保护。

## 2. 函数调用链

### 2.1 浏览器到 Java 同步链

```text
FileUploadCard.handleUpload
  -> UploadPage.handleUpload
  -> resumeApi.uploadAndAnalyze
  -> request.upload
  -> Axios request interceptor
     -> currentUserId -> createClientId
  -> RequestIdFilter.doFilterInternal -> normalize
  -> SimpleRateLimitFilter.doFilterInternal
     -> JavaRedisStore.incrementInFixedWindow
     -> （Redis 不可用）ConcurrentHashMap 本地窗口分支
  -> IdempotencyFilter.shouldNotFilter
     -> （带 X-Idempotency-Key）IdempotencyFilter.doFilterInternal
        -> JavaRedisStore.acquire
        -> （Redis 不可用）fallback.putIfAbsent
  -> ResumeController.upload
  -> ResumeService.upload
     -> UserIdentityResolver.require
     -> ResumeFileStorageService.inspect -> sha256
     -> CandidateRepository.findByUserId
        -> （无候选人）BusinessIdGenerator.next -> CandidateRepository.save -> CandidateRepository.upsert SQL
     -> ResumeRepository.findFirstByCandidateIdAndFileHash
     -> （重复文件）CandidateEntity.setCurrentResumeId -> CandidateRepository.save
        -> ResumeAnalysisService.cancelActiveForResumeIds
        -> ResumeAnalysisService.submit
     -> （新文件）ResumeRepository.findByCandidateId
        -> ResumeRepository.findFirstByCandidateIdOrderByVersionDesc
        -> BusinessIdGenerator.next
        -> Tika.parseToString（第三方库）
        -> ResumeFileStorageService.store
        -> ResumeEntity.attachFile -> ResumeRepository.save -> ResumeRepository.upsert SQL
        -> ResumeAnalysisService.cancelActiveForResumeIds
        -> CandidateEntity.setCurrentResumeId -> CandidateRepository.save
        -> ResumeAnalysisService.submit
           -> requiredResume -> ResumeRepository.findById
           -> CandidateRepository.findById
           -> ResumeAnalysisPersistenceService.cancelActiveForResumeIds
           -> ResumeAnalysisPersistenceService.create
              -> ResumeAnalysisEntity.<init> -> ResumeAnalysisRepository.save/insert SQL
              -> cacheAfterCommit -> JavaTaskStatusCache.updateResumeAnalysis -> JavaRedisStore.putJson
           -> ResumeAnalysisWorker.enqueue -> RabbitTemplate.convertAndSend
           -> ResumeAnalysisService.toView -> stringList/mapList
  -> ApiResult.success
  -> Axios response interceptor -> UploadPage.handleUpload 的成功或失败分支
```

### 2.2 RabbitMQ 到 Python 异步链

```text
RabbitAgentWorkConsumer.consume
  -> ResumeAnalysisWorker.process
     -> ResumeAnalysisRepository.findById
     -> ResumeAnalysisEntity.isCancelled
     -> ResumeRepository.findById
     -> ResumeAnalysisWorker.isCurrentResume -> CandidateRepository.findById
     -> ResumeAnalysisPersistenceService.beginAttempt
        -> required -> ResumeAnalysisRepository.findById
        -> ResumeAnalysisEntity.canBeginAttempt -> beginAttempt
        -> ResumeAnalysisRepository.save/update SQL -> cacheAfterCommit
     -> HttpPythonAgentClient.activateResumeMemory
        -> AgentCallExecutor.execute -> HttpPythonAgentClient.post -> validateRequest
        -> HTTP POST /v1/agent/resume/activate
        -> Python activate_resume_memory
           -> _remember_request_context -> _resolve_memory_service
           -> build_memory_service -> create_session_factory（装配）
           -> MemoryService.activate_resume -> _resume_activation_fingerprint
              -> LongTermMemoryRepository.get/create/save
     -> ResumeAnalysisWorker.requireMatchingResponse -> requireSuccess
     -> HttpPythonAgentClient.evaluateResume
        -> AgentCallExecutor.execute -> post -> validateRequest
        -> HTTP POST /v1/agent/evaluate/resume
        -> Python evaluate_resume
           -> _remember_request_context -> _resume_evaluation_fingerprint
           -> _resolve_memory_service -> MemoryService.get_resume_evaluation_run
           -> （未命中）_resolve_resume_evaluator -> build_resume_evaluation_agent
              -> ResumeEvaluationAgent.evaluate
                 -> SkillRegistry.get -> PromptLoader.render
                 -> StructuredOutputInvoker.invoke -> AsyncRetryExecutor.execute -> LLM 调用
           -> MemoryService.record_resume_analysis -> LongTermMemoryRepository.save
           -> AgentResponse
     -> ResumeAnalysisWorker.requireMatchingResponse
     -> ResumeAnalysisPersistenceService.complete 或 cancel/fail/recordRetryableFailure
        -> ResumeAnalysisEntity 状态函数 -> ResumeAnalysisRepository.update SQL
        -> cacheAfterCommit -> JavaTaskStatusCache.updateResumeAnalysis
```

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `FileUploadCard.handleUpload`

**文件与行号：** `frontend/src/components/FileUploadCard.tsx:87-90`。

1. 第 87 行声明无参点击处理函数；它闭包读取组件状态 `selectedFile` 与 `name`。
2. 第 88 行在尚未选择文件时直接返回，避免把 `null` 传给父组件。
3. 第 89 行调用传入的 `onUpload`；第一个参数是文件对象，第二个参数会先 `trim`，空名称变为 `undefined`。简历页面不启用名称输入框，因此 `UploadPage` 只接收第一个参数。
4. 第 90 行结束函数。点击按钮的 JSX 位于同文件第 269-285 行，第 271 行把该函数绑定到按钮。

#### 3.1.2 `UploadPage.handleUpload`

**文件与行号：** `frontend/src/pages/UploadPage.tsx:13-29`。

1. 第 13 行声明异步函数，参数类型为浏览器 `File`。
2. 第 14 行对 `targetRole` 调用 `trim` 后取反；空岗位不进入网络链路。
3. 第 15 行调用 `setError` 写入页面提示。
4. 第 16 行返回，阻止文件上传。
5. 第 18 行设置 `uploading=true`，从而禁用输入与上传按钮。
6. 第 19 行清空上一次错误。
7. 第 20 行开始 `try`，把请求异常与后续响应校验放入同一处理域。
8. 第 21 行调用 `resumeApi.uploadAndAnalyze(file, targetRole.trim())`，并等待其 Promise；这里第二次去空白保证提交值与前端校验值一致。
9. 第 22 行验证后端响应包含 `storage.resumeId`；缺失时主动抛错，避免把不完整数据用于页面跳转。
10. 第 23 行将简历 ID 交给父组件回调，父组件据此切换到后续页面。
11. 第 24 行捕获 Axios、业务或本地校验错误。
12. 第 25 行调用项目函数 `getErrorMessage`，把 `Error.message` 写入状态。
13. 第 26 行开始 `finally`，无论成功或失败均执行。
14. 第 27 行复位 `uploading=false`。
15. 第 28-29 行关闭 `finally` 与函数。

#### 3.1.3 `resumeApi.uploadAndAnalyze`

**文件与行号：** `frontend/src/api/resume.ts:8-13`。

1. 第 8 行声明异步 API 包装函数，并把返回值约束为 `UploadResponse`。
2. 第 9 行创建 `FormData`；浏览器会据此构造 multipart 边界。
3. 第 10 行以 `file` 键写入文件，必须对应 Java 的 `@RequestPart("file")`。
4. 第 11 行以 `targetRole` 键写入岗位文本，必须对应 `@RequestPart("targetRole")`。
5. 第 12 行委托 `request.upload`，路径与本接口完全一致。
6. 第 13 行结束对象方法。

#### 3.1.4 `request` 的身份、上传与响应处理

**文件与行号：** `frontend/src/api/request.ts:47-73、123-155、173-178、185-187`。

1. `createClientId` 第 47 行定义 ID 工厂；第 48 行优先调用浏览器安全随机 UUID；第 49 行在旧环境用前缀、时间与两段随机十六进制拼接替代。
2. `currentUserId` 第 52 行定义本地身份键读取函数；第 53 行从 localStorage 读取；第 54 行若非空直接复用；第 55 行否则生成 ID；第 56 行持久化；第 57 行返回。该值是暂时身份键，不是鉴权令牌。
3. 请求拦截器第 64 行注册。第 65 行确保 `headers` 对象存在；第 66 行定义兼容 AxiosHeaders 与普通对象的 `setHeader`；第 67-68 行分别处理两种写入方式；第 70 行写 `X-User-Id`；第 71 行为本次请求写新的 `X-Request-Id`；第 72 行返回配置。
4. 响应成功拦截器第 123-135 行处理 Java `ApiResult`。第 125 行读取包裹体；第 126 行确认有 `code`；第 127 行识别成功码；第 128 行把外层 `data` 解包成调用方可见数据；第 129 行返回响应；第 131-132 行对非 200 包装为 `ApiRequestError`；第 134 行保留非项目响应；第 135 行结束成功回调。
5. 错误回调第 136-154 行先判断是否 Axios 错误、第 138 行处理无 HTTP 响应的传输错误、第 140 行解码 Blob JSON、第 141-142 行优先解析项目错误体，最后第 144-153 行按 HTTP 状态构造安全错误。
6. `request.upload` 第 173 行声明泛型上传函数；第 174 行发 POST；第 175 行把上传超时设为 300 秒；第 176 行声明 multipart；第 177 行允许调用者覆盖其他配置；第 178 行取解包后的 `response.data`。
7. `getErrorMessage` 第 185 行声明显示转换函数；第 186 行仅暴露 `Error.message`，未知对象使用固定兜底文本；第 187 行结束。

### 3.2 Java Web 入口与保护函数

#### 3.2.1 `RequestIdFilter.doFilterInternal` 与 `normalize`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/infrastructure/web/RequestIdFilter.java:23-41`。

1. `doFilterInternal` 第 23-24 行声明 Servlet 过滤函数及可抛出的容器异常。
2. 第 25 行读取 `X-Request-Id` 并调用项目函数 `normalize`。
3. 第 26 行把结果存为 request attribute，供限流、幂等和异常包装读取。
4. 第 27 行回写响应头，客户端可用它关联日志。
5. 第 28 行将 ID 放进 SLF4J MDC。
6. 第 29 行开始 `try`；第 30 行把控制权交给后续过滤器和 Controller。
7. 第 31 行开始 `finally`；第 32 行移除 MDC，避免线程池复用时串请求；第 33-34 行结束。
8. `normalize` 第 36 行定义私有函数；第 37 行只接受非空、长度不超过 128 且匹配安全字符集的值；第 38 行原样返回合格值；第 40 行为不合格或缺失值生成 UUID；第 41 行结束。

#### 3.2.2 `SimpleRateLimitFilter.doFilterInternal` 与 Redis 回退

**文件与行号：** `java-backend/src/main/java/com/interviewguide/infrastructure/ratelimit/SimpleRateLimitFilter.java:48-82`，`JavaRedisStore.java:31-39`。

1. 限流函数第 48-49 行声明过滤器参数。
2. 第 50 行识别 `/health` 与 `/actuator`，第 51 行放行健康探针，第 52 行结束该分支。
3. 第 54 行用远端 IP 与 URI 拼接限流维度；第 55 行计算当前分钟窗口。
4. 第 56 行在未注入 Redis 的测试场景生成空 Optional；第 57-58 行在生产场景调用 `incrementInFixedWindow`，键前缀是 `java:rate-limit:`，TTL 为 65 秒。
5. `JavaRedisStore.incrementInFixedWindow` 第 31 行定义方法；第 32 行开始捕获 Redis 数据访问异常；第 33 行执行原子 `INCR`；第 34 行仅首次计数时设置过期；第 35 行返回计数；第 36-38 行记录警告并返回空值。
6. 回到过滤器，第 60-61 行在 Redis 成功时采用分布式计数。第 62 行进入失败/未配置分支；第 65-66 行用 `ConcurrentHashMap.compute` 创建或替换分钟窗口；第 67 行原子递增本地计数。该回退保障可用性，但跨 Java 实例的严格全局限流暂时失效。
7. 第 69 行比较计数与阈值。超限时第 70 行置 429；第 71 行告知 60 秒后重试；第 72 行设 JSON 类型；第 73-74 行取追踪 ID；第 75-77 行构造统一错误；第 78 行序列化；第 79 行终止链路。
8. 第 81 行在未超限时放行，随后才可能进入幂等过滤器和 Controller。

#### 3.2.3 `IdempotencyFilter.shouldNotFilter`、`doFilterInternal` 与 `writeConflict`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/infrastructure/idempotency/IdempotencyFilter.java:41-96`。

1. `shouldNotFilter` 第 41 行定义跳过判定；第 42-44 行规定只有携带 `X-Idempotency-Key` 的 POST、PUT、PATCH、DELETE 才执行。当前上传前端未发送该头，因而通常跳过。
2. 若调用方发送该头，`doFilterInternal` 第 48-49 行运行；第 50 行读取并去空白；第 51-53 行拒绝空键或超过 200 字符的键，并调用 `writeConflict`。
3. 第 55 行读取用户 ID；第 56-57 行按用户、方法、路径和业务键构造隔离 Redis 键。
4. 第 58 行调用 `JavaRedisStore.acquire`；其第 42-48 行通过 `setIfAbsent` 原子占位，Redis 异常返回空 Optional。
5. 第 60-61 行使用分布式占位结果；第 62-65 行在 Redis 故障时使用本机 `fallback.putIfAbsent` 并清除过期项。
6. 第 67-70 行拒绝重复请求；第 72-73 行让已接受请求继续执行。第 77-80 行对 4xx 删除占位，允许修正参数后使用同一个键重试；第 81-84 行对异常也删除占位并重新抛出。
7. `writeConflict` 第 88-95 行设置 409、读取 requestId、构造 `ApiErrorDetail` 并写入 JSON。

#### 3.2.4 `ResumeController.upload` 与 `ApiResult.success`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:32-37`，`java-backend/src/main/java/com/interviewguide/common/web/dto/ApiResult.java:3-6`。

1. Controller 第 32 行把方法映射为 `/upload`，与类级 `/api/resumes` 拼成完整路径。
2. 第 33 行把 multipart 的 `file` 绑定为 `MultipartFile`。
3. 第 34 行把 `targetRole` 绑定为字符串。
4. 第 35 行读取可缺省的身份头；真正的非空约束在业务层，以保持适配器简单。
5. 第 36 行调用 `resumeService.upload`，再以 `ApiResult.success` 包装其结果；第 37 行结束。
6. `ApiResult.success` 的第 4 行接收泛型数据，第 5 行构造 `code=200`、`message=success` 的统一响应，第 6 行结束。前端响应拦截器会去掉这层包装。

### 3.3 Java 简历写入与任务创建函数

#### 3.3.1 `ResumeService.upload`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:74-138`。

1. 第 74-77 行声明上传业务函数及三个输入参数。
2. 第 78 行调用 `identity.require` 得到规范化 owner。
3. 第 79 行拒绝空文件；第 80-82 行拒绝空文件名；第 83-85 行拒绝空岗位。
4. 第 86 行调用 `fileStorage.inspect`，一次读取字节并得到安全文件名、大小、MIME 与 SHA-256。
5. 第 87-89 行按用户查询候选人；没有候选人时调用 `idGenerator.next` 新建 ID，构造 `CandidateEntity` 后调用 Mapper 默认 `save`。
6. 第 90-92 行以候选人 ID 和文件哈希查询重复文件。
7. 第 93 行进入重复分支。第 94-95 行取该候选人的全部简历 ID；第 96 行把重复简历设为当前简历；第 97 行保存候选人；第 98 行取消这些简历正在运行的分析；第 99 行为重复简历重新提交分析。
8. 第 100-105 行返回 `duplicate=true`、已有 resumeId、`storage.resumeId` 与新分析任务信息，不重新写磁盘文件。
9. 新文件分支第 107-108 行取得旧简历 ID；第 109-110 行查询最大版本并计算下一版本；第 111 行生成新的业务 ID。
10. 第 112 行声明文本变量；第 113-117 行调用第三方 Tika 解析输入流，任何解析异常都转换为 `RESUME_PARSE_FAILED`；第 118-120 行拒绝空解析结果。
11. 第 121 行在受控存储根目录写入原文件。第 123 行构造 `ResumeEntity`；第 124 行调用 `attachFile` 写入文件元数据；第 125 行通过 Mapper 保存。
12. 第 126-129 行在数据库保存失败时删除刚写入的文件，并重新抛出原异常，避免孤儿文件。
13. 第 130 行取消旧简历分析；第 131 行更新候选人的当前简历指针；第 132 行保存候选人；第 133 行创建并投递新分析任务。
14. 第 134 行创建可变结果 Map；第 135 行写 `storage.resumeId`；第 136 行写原文、状态与分析 ID；第 137 行返回；第 138 行结束。

#### 3.3.2 身份、ID、文件和实体函数

**`UserIdentityResolver.require`：** `java-backend/src/main/java/com/interviewguide/common/security/UserIdentityResolver.java:14-19`。第 14 行定义函数；第 15-17 行拒绝 null 或空白 `X-User-Id`；第 18 行 `strip`；第 19 行返回。

**`BusinessIdGenerator.next`：** `java-backend/src/main/java/com/interviewguide/common/id/BusinessIdGenerator.java:13-16`。第 13 行定义函数；第 14 行以原子 `updateAndGet` 取“当前毫秒”和“上一次值加一”的较大者，防止同 JVM 同毫秒重复；第 15 行转成字符串；第 16 行结束。

**`ResumeFileStorageService.inspect`：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeFileStorageService.java:21-31`。第 22 行读取字节；第 23 行调用 `sha256`；第 24 行读取原名；第 25-27 行拒绝无名文件；第 28 行用 `Path.getFileName` 去掉客户端目录；第 29-30 行构造不可变 `FileDescriptor`；第 31 行结束。

**`ResumeFileStorageService.sha256`：** 同文件第 62-69 行。第 63 行开始异常保护；第 64 行取得 SHA-256 摘要；第 65 行创建字符串累加器；第 66 行逐字节格式化为两位小写十六进制；第 67 行返回；第 68 行把算法不可用转换为不可恢复运行时异常。

**`ResumeFileStorageService.store`：** 同文件第 33-41 行。第 34 行校验 resumeId；第 35 行形成 `resumeId/filename` 键；第 36 行解析并规范化目标路径；第 37 行检查仍在根目录内以阻止路径穿越；第 38 行建父目录；第 39 行以 `CREATE_NEW` 写文件；第 40 行返回 `StoredFile`；第 41 行结束。

**`CandidateEntity.setCurrentResumeId`：** `java-backend/src/main/java/com/interviewguide/resume/domain/CandidateEntity.java:22`。该单行只替换内存中的当前简历指针；实际持久化由其后的 `CandidateRepository.save` 完成。

**`ResumeEntity.<init>` 与 `attachFile`：** `java-backend/src/main/java/com/interviewguide/resume/domain/ResumeEntity.java:20-26、39-45`。构造函数第 21-25 行依次写 ID、候选人 ID、版本、文本和创建时间；`attachFile` 第 40-44 行依次写哈希、文件名、大小、类型、存储键，第 45 行结束。

#### 3.3.3 MyBatis Mapper 函数和 SQL

1. `CandidateRepository.save` 位于 `CandidateRepository.java:9-12`。第 9 行声明 XML 对应的 `upsert`；第 10 行默认方法先执行 `upsert(entity)` 再原样返回实体；第 11-12 行声明按主键、按用户查询。
2. `CandidateRepository.xml:4` 是 `findById` SQL；第 5 行是 `findByUserId` SQL，`LIMIT 1` 保证单值；第 6 行是 `INSERT ... ON CONFLICT(id) DO UPDATE`，因此候选人保存不依赖 JPA dirty checking。
3. `ResumeRepository.java:11-18` 中，第 11 行声明 upsert；第 12 行默认 save；第 13 行按 ID；第 16 行用 `@Param` 绑定重复查找参数；第 17-18 行分别列出历史与最新版本查询。
4. `ResumeRepository.xml:6` 用 candidate_id、file_hash 和版本倒序找重复；第 7 行按版本列历史；第 8 行取最新版本；第 9 行以 PostgreSQL `ON CONFLICT(id) DO UPDATE` 保存简历。所有这些 SQL 都由 MyBatis 执行。

#### 3.3.4 `ResumeAnalysisService.submit`、持久化与 Redis 一致性

**`ResumeAnalysisService.submit`：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:43-64`。第 44-46 行校验岗位；第 47 行调用 `requiredResume`；第 48-49 行取得候选人；第 50-52 行校验简历归属；第 56 行取消同简历活动任务；第 57 行创建 PENDING 任务；第 58 行开始投递保护；第 59 行投递 RabbitMQ；第 60 行转为视图；第 61-64 行在投递失败时写 FAILED 并重新抛出。

**`requiredResume`：** 同文件第 86-89 行。第 87 行按 ID 查询；第 88 行将缺失转为 `RESUME_NOT_FOUND`；第 89 行结束。

**`ResumeAnalysisPersistenceService.create`：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisPersistenceService.java:33-38`。第 34 行开始事务函数；第 35 行构造 `ResumeAnalysisEntity` 并调用 Mapper `save`；第 36 行登记提交后缓存动作；第 37 行返回实体。

**`cacheAfterCommit`：** 同文件第 115-125 行。第 116-119 行处理无事务的保护分支；当前 `create` 在事务内，所以第 120 行注册同步器，第 121-123 行只在数据库提交后调用 `taskCache.updateResumeAnalysis`。这保证 Redis 写失败或事务回滚都不会制造虚假的已提交状态。

**`JavaTaskStatusCache.updateResumeAnalysis`：** `java-backend/src/main/java/com/interviewguide/infrastructure/redis/JavaTaskStatusCache.java:20-39`。第 21 行创建有序快照；第 22-36 行逐项复制分析 ID、状态、分数、摘要、JSON 字段、错误和更新时间；第 37 行写任务 ID 键；第 38 行写“该简历最新任务”键；第 39 行结束。底层 `JavaRedisStore.putJson` 在 `JavaRedisStore.java:51-58` 捕获 Redis/JSON 异常，只记日志，不影响已提交数据库数据。

**`ResumeAnalysisWorker.enqueue`：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java:49-53`。第 50-52 行把任务类型、分析 ID 字符串和用户 ID 构造成 `AgentWorkTaskMessage` 并发往配置的 exchange/routing key；第 53 行结束。

#### 3.3.5 审核补充：任务取消、视图转换、持久化与 Mapper 函数

以下函数均已经出现在第 2 节的真实调用链中。它们不能因为实现短小而省略；本小节将原先合并的说明拆开，作为首篇文档的逐函数审核补全。

**`ResumeAnalysisService.cancelActiveForResumeIds`：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:82-84`。第 82 行声明接收简历 ID 列表的公开服务方法。第 83 行不在本层自行修改任务，而是把列表完整委托给持久化服务的同名方法，因此取消与状态缓存更新都仍在事务边界内执行。第 84 行结束方法。它由 `ResumeService.upload` 的重复上传和新版本上传分支调用，目的是避免旧任务覆盖当前简历的分析结果。

**`ResumeAnalysisPersistenceService.cancelActiveForResumeIds`：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisPersistenceService.java:80-85`。第 80 行以 `@Transactional` 标记整个批量取消操作。第 81 行声明输入集合，不把调用方限定为 `List`。第 82 行对空集合立即返回，避免生成无效 SQL。第 83 行通过 MyBatis `ResumeAnalysisRepository.findByResumeIdInAndStatusIn` 仅查询 `PENDING`、`PROCESSING` 两种活动状态。第 84 行逐个执行 Lambda：先调用实体 `cancel` 改变内存状态，再调用 Mapper `save` 落库，最后调用 `cacheAfterCommit` 登记事务提交后刷新 Redis。第 85 行结束方法。

**`ResumeAnalysisRepository.findByResumeIdInAndStatusIn`：** `java-backend/src/main/java/com/interviewguide/resume/mapper/ResumeAnalysisRepository.java:23`，对应 `java-backend/src/main/resources/mapper/resume/ResumeAnalysisRepository.xml` 中同名 `<select>`。接口第 23 行用两个 `@Param` 将简历集合和状态集合命名为 XML 可引用的参数。XML 的 `foreach` 将两组集合展开为 `IN (...)` 条件；它只读取候选记录，不改变状态。调用方收到实体列表后才逐一调用 `save`，所以取消行为的写入点仍清晰可追踪。

**`ResumeAnalysisEntity.cancel`：** `java-backend/src/main/java/com/interviewguide/resume/domain/ResumeAnalysisEntity.java` 的 `cancel` 方法。该方法把可取消任务标记为 `CANCELLED` 并更新时间字段；它不直接访问数据库。其后的 `ResumeAnalysisRepository.save` 才把该状态写入 PostgreSQL，避免将领域对象方法误认为持久化操作。

**`ResumeAnalysisRepository.save`：** `java-backend/src/main/java/com/interviewguide/resume/mapper/ResumeAnalysisRepository.java:15-18`。第 15 行声明 MyBatis Mapper 的默认保存函数。第 16 行根据 `entity.getId()` 是否为空选择 `insert` 或 `update`：新建任务没有数据库主键，已有任务走更新。第 17 行原样返回同一个实体，便于 `create` 取得数据库回填的主键。第 18 行结束方法。第 13、14 行分别声明由 XML 实现的 `insert` 与 `update`；这不是 JPA 自动脏检查。

**`ResumeAnalysisService.toView`：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:91-97`。第 91 行声明将数据库领域实体转换为 HTTP 视图的方法。第 92-94 行按构造器参数顺序拷贝分析 ID、状态、六项分数、摘要和更新时间。第 95 行对 `strengthsJson`、`suggestionsJson` 调用 `stringList`，将 JSON 字符串恢复为字符串列表。第 96 行对问题 JSON 调用 `mapList`，并复制错误信息。第 97 行结束；转换不产生数据库写入。

**`ResumeAnalysisService.stringList`：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:127-131`。第 127 行声明 JSON 字符串到列表的私有转换。第 128 行把空值或空白字符串规范为不可变空列表。第 129 行通过项目注入的 `ObjectMapper` 和 `TypeReference` 反序列化。第 130 行吞掉格式错误并返回空列表，防止历史脏数据导致查询接口整体失败。第 131 行结束。

**`ResumeAnalysisService.mapList`：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:133-137`。第 133 行声明问题列表转换函数。第 134 行对空 JSON 返回空列表。第 135 行按目标泛型 `List<Map<String,Object>>` 读取 JSON。第 136 行在反序列化异常时回退为空列表。第 137 行结束。它由 `toView` 调用，用于把持久化的 `issues_json` 安全呈现给前端。

**`ResumeAnalysisPersistenceService.beginAttempt`：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisPersistenceService.java:87-95`。第 87 行声明事务。第 88 行声明按分析 ID 开始一次消费尝试。第 89 行调用 `required` 读取任务，不存在时立即报业务错误。第 90 行调用实体 `canBeginAttempt`；若任务已完成、取消或不允许再次处理则返回 `null`。第 91 行调用实体 `beginAttempt`，将状态切换到处理中并增加尝试计数。第 92 行调用 Mapper `save` 更新数据库。第 93 行登记事务提交后的 Redis 快照刷新。第 94 行把开始后的实体返回给 Worker，以便比较重试次数。第 95 行结束。

**`ResumeAnalysisPersistenceService.required`：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisPersistenceService.java:110-113`。第 110 行声明私有查询助手。第 111 行调用 `ResumeAnalysisRepository.findById`。第 112 行在 `Optional` 为空时抛出 `RESUME_ANALYSIS_NOT_FOUND`，否则返回实体。第 113 行结束。Worker、`beginAttempt`、完成和失败分支都通过它避免在空任务上继续写状态。

**`ResumeAnalysisPersistenceService.isCancelled`：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisPersistenceService.java:108`。这一行是单行公开函数：先调用 `required(id)` 取得最新持久化实体，再调用实体 `isCancelled()` 返回布尔值。它由 Worker 在成功回写和异常处理前调用，确保已被新上传操作取消的任务不会被重新标记为失败或完成。

**`ResumeAnalysisPersistenceService.complete`：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisPersistenceService.java:40-55`。第 40 行声明事务。第 41 行接收分析 ID 和 Python 的 `AgentResponse`。第 42 行读取输出映射。第 43-45 行拒绝缺失输出。第 46 行用 `required` 取得任务。第 47-52 行调用实体 `complete`，其中六次 `integer` 强制校验分数字段，`string` 校验摘要，三次 `json` 序列化列表或问题对象。第 53 行调用 Mapper 保存完成状态与结果。第 54 行登记提交后 Redis 刷新。第 55 行结束。

**`ResumeAnalysisPersistenceService.integer`、`string`、`json`：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisPersistenceService.java:127-142`。`integer` 的第 128 行按键取值，第 129 行仅接受数值并取 `intValue`，第 130 行对缺失或非数值抛出 `RESUME_ANALYSIS_OUTPUT_INVALID`；第 131 行结束。`string` 的第 133 行声明函数，第 134 行取值，第 135 行只接受非空白字符串，第 136 行对不合格输出抛出同类业务异常，第 137 行结束。`json` 的第 139 行声明序列化函数，第 140 行把 `null` 规范为空列表并写成 JSON，第 141 行把 Jackson 序列化错误转换为业务异常，第 142 行结束。这三者保证 Python 返回的结构不符合协议时不会写入半成品分析结果。

**`ResumeAnalysisPersistenceService.recordRetryableFailure`：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisPersistenceService.java:97-103`。第 97 行开启事务。第 98 行声明 ID 和已截断错误文本。第 99 行读取任务。第 100 行调用实体 `recordRetryableFailure`，保留失败原因并使任务处于可重试状态。第 101 行更新 Mapper。第 102 行登记 Redis 刷新。第 103 行结束；Worker 随后重新抛出异常，把是否重新投递交由 RabbitMQ 配置处理。

**`ResumeAnalysisWorker.isRetryable`：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java:128-130`。第 128 行声明判断函数。第 129 行仅当异常是 `PythonAgentException` 且其 `retryable` 标志为真时返回真；普通业务异常、响应身份不匹配和参数错误不会重试。第 130 行结束。

**`ResumeAnalysisWorker.isCurrentResume`：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java:132-135`。第 132 行声明函数。第 133 行通过 `CandidateRepository.findById` 查询简历所属候选人，缺失时得到 `null`。第 134 行同时要求候选人存在，且候选人的 `currentResumeId` 精确等于当前 `resume.id`。第 135 行结束。这是消息延迟到达时防止旧简历结果回写的关键校验。

**`ResumeAnalysisWorker.requireSuccess`：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java:137-143`。第 137 行声明响应状态验证函数。第 138 行将空响应、非 100–199 的响应视为失败。第 139-140 行优先读取 Python 返回的错误消息，缺失时使用调用点的后备文本。第 141 行抛出 `PythonAgentException`，并保留远端响应的可重试标志。第 142-143 行结束。

**`ResumeAnalysisWorker.requireMatchingResponse`：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java:145-155`。第 145-147 行声明同时校验协议状态和身份回显的函数。第 148 行先调用 `requireSuccess`。第 149-151 行逐项比较 `userId`、`sessionId`、`runId`。第 152-153 行在任一项不一致时抛出不可重试的 `PythonAgentException`，避免把其他请求的结果写到当前任务。第 154-155 行结束。

**`AgentCallExecutor.execute` 与 `sleepBeforeRetry`：** `java-backend/src/main/java/com/interviewguide/infrastructure/reliability/AgentCallExecutor.java:22-44`。`execute` 第 22 行接收惰性 HTTP 调用。第 23 行预留最后一次 Python 异常。第 24 行按配置次数循环。第 25-26 行执行一次调用并在成功时立刻返回。第 27-30 行只捕获 `PythonAgentException`；记录异常，遇到不可重试错误或最后一次尝试立即抛出，否则调用 `sleepBeforeRetry`。第 31 行结束循环体。第 33 行是理论兜底，抛出最后异常或新的不可重试异常。第 34 行结束。`sleepBeforeRetry` 第 36 行声明等待函数；第 37-38 行按配置退避；第 39 行捕获线程中断；第 40 行恢复中断标志；第 41 行转换为不可重试 Python 调用异常；第 42-44 行结束。

### 3.4 RabbitMQ 消费、Java 到 Python 的调用及结果回写

#### 3.4.1 `RabbitAgentWorkConsumer.consume`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/infrastructure/messaging/RabbitAgentWorkConsumer.java:22-39`。

1. 第 22 行把函数订阅到 agent 工作队列；第 23 行接收反序列化消息。
2. 第 24-27 行拒绝空消息或缺少任务类型、资源 ID、用户 ID 的消息。
3. 第 28 行开始保护区；第 29 行按任务类型分支；第 30-31 行将简历任务 ID 转 Long 后调用 `ResumeAnalysisWorker.process`；第 32-33 行保留知识库索引分支；第 34 行记录未知类型。
4. 第 36-38 行专门吞掉资源 ID 格式错误，避免无效消息无限重试；第 39 行结束。

#### 3.4.2 `ResumeAnalysisWorker.process` 及辅助函数

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisWorker.java:55-161`。

1. 第 56 行读取分析记录；第 59-61 行跳过已删除或已取消任务；第 62-65 行跳过已经删除的简历。
2. 第 66-69 行调用 `isCurrentResume`，旧版本任务改为 CANCELLED 后返回；第 70-73 行调用 `beginAttempt`，不可开始时结束消费。
3. 第 74 行开始主处理区；第 75-76 行生成激活阶段的稳定 run/session ID；第 77-82 行构造 `AgentResumeMemoryActivationRequest` 并调用 Python 客户端。
4. 第 83-85 行校验激活响应成功且 userId/sessionId/runId 精确回显；第 86-89 行再次确认简历仍是当前版本，防止调用期间有新上传覆盖它。
5. 第 90-97 行生成评估 run/session ID、构造评估请求并调用 Python。
6. 第 98-105 行处理非 1xx 成功码：可重试响应转换为异常；不可重试响应写 FAILED 并正常返回。
7. 第 106-108 行校验成功响应身份；第 109-113 行在未取消且仍为当前简历时写 COMPLETED，否则取消。
8. 第 114-125 行处理运行时异常：第 115-117 行忽略已取消任务；第 118-120 行对未超过消费尝试次数的可重试 Python 异常记录失败原因后重新抛出，以便 Rabbit 策略重试；第 122-124 行把其余异常写 FAILED。
9. `isRetryable` 第 128-130 行仅认可 `PythonAgentException.retryable()`；`isCurrentResume` 第 132-135 行读取候选人并比较 currentResumeId。
10. `requireSuccess` 第 137-143 行拒绝 null 或非成功码并保留 retryable 属性；`requireMatchingResponse` 第 145-155 行先调用它，再逐项比较 user、session、run，不一致时抛不可重试协议错误；`safeMessage` 第 157-161 行截断异常文本到 500 字符。

#### 3.4.3 `HttpPythonAgentClient` 与 `AgentCallExecutor`

**文件与行号：** `HttpPythonAgentClient.java:46-47、65-96`，`AgentCallExecutor.java:22-43`。

1. `evaluateResume` 第 46 行将请求交给 `AgentCallExecutor.execute`，内部 POST `/v1/agent/evaluate/resume`；`activateResumeMemory` 第 47 行同样 POST `/v1/agent/resume/activate`。
2. `post` 第 65 行定义通用调用；第 66 行先 `validateRequest`；第 68 行用 RestClient 发请求并反序列化；第 69 行拒绝空响应；第 71-79 行分别保留已有异常、解析结构化错误、转换 HTTP 错误和网络错误。
3. `parseStructuredError` 第 82-87 行尝试把错误体反序列化为 `AgentResponse`，仅带 `error` 的体才作为正常业务响应返回。
4. `validateRequest` 第 89-96 行运行 Bean Validation；第 90 行获取违反项；第 91-94 行拼出字段名并抛不可重试请求异常。
5. `AgentCallExecutor.execute` 第 22-34 行循环最多 `maxAttempts`；第 25-26 行执行调用；第 27-30 行只对可重试 Python 异常等待后重试；第 33 行作为理论兜底重新抛最后异常。`sleepBeforeRetry` 第 36-43 行睡眠、处理中断并恢复中断标志。

### 3.5 Python 激活记忆与简历评估函数

#### 3.5.1 FastAPI 路由和装配函数

**文件与行号：** `python-agent/app/api/application.py:159-224、343-357、391-395、438-455`，`python-agent/app/bootstrap.py:33-43、94-104`。

1. `activate_resume_memory` 第 208-224 行：第 212 行保存请求上下文；第 213-217 行解析/惰性创建 `MemoryService` 并调用 `activate_resume`；第 218-223 行返回 code 100、COMPLETED 的 `AgentResponse`；第 224 行结束。
2. `evaluate_resume` 第 159-206 行：第 161 行保存上下文；第 162 行计算输入指纹；第 163 行获取记忆服务；第 164-167 行按 runId/指纹查询幂等评估缓存；第 168-173 行未命中时解析评估 Agent 并调用 `evaluate`；第 174-191 行记录分析结果；第 192-199 行处理并发一致性异常时读取已保存的回放结果；第 200-205 行构造成功响应。
3. `_remember_request_context` 第 391-395 行检测 Pydantic `model_dump`，并把 JSON 形态请求保存到 `request.state`，供异常处理保留 requestId、runId、sessionId。
4. `_resolve_memory_service` 第 351-357 行优先复用 app state；缺失时导入并调用 `build_memory_service`，再回存。`_resolve_resume_evaluator` 第 343-348 行以相同模式创建评估 Agent。
5. `_resume_evaluation_fingerprint` 第 438-444 行按 subject、文本、岗位组成排序稳定 JSON，再计算 SHA-256；相同 runId 的不同输入会被识别为一致性冲突。
6. `build_memory_service` 第 33-38 行读取 settings、建会话工厂、以 PostgreSQL 记忆仓储和策略构造服务。`build_resume_evaluation_agent` 第 94-104 行创建重试器、模型、提示词加载器与 SkillRegistry，再构造 Agent。

#### 3.5.2 `MemoryService.activate_resume` 与评估结果写入

**文件与行号：** `python-agent/app/memory/service.py:48-96、176-245`。

1. `activate_resume` 第 48-51 行声明输入；第 58-61 行调用 `_resume_activation_fingerprint`；第 62-65 行构造简历快照；第 66 行读取用户长期记忆。
2. 第 67-75 行在首次用户场景构造 `LongTermMemory`，有 runId 时记录 activation run，然后创建数据库记录。
3. 第 76-80 行在同 runId 已存在时验证 resumeId/指纹，一致则直接返回，冲突则抛 `ConsistencyError`。
4. 第 81-96 行保存既有记忆：记录乐观锁版本，更新 active resume、合并快照、清空由旧评估派生的字段、记录受限数量的 run、更新时间，最后按 expected_version 保存。
5. `_resume_activation_fingerprint` 第 255-264 行将 resumeId、candidateId、文本、岗位序列化为稳定 JSON 后做 SHA-256。
6. `record_resume_analysis` 第 176-231 行先读取记忆，第 187-192 行识别同 run 回放或冲突，第 193-194 行拒绝非当前简历的迟到结果，第 195-216 行更新或补充简历快照，第 217-220 行用替换语义更新技术栈、深度、建议和偏好，第 221-230 行保存可回放评估 run 并限制数量，第 231 行乐观锁写入。
7. `get_resume_evaluation_run` 第 233-245 行读取记忆、查 run、验证 resumeId/指纹，并只返回已保存的评估对象；这正是评估接口的幂等读取路径。

#### 3.5.3 `ResumeEvaluationAgent.evaluate`

**文件与行号：** `python-agent/app/agents/evaluation/agent.py:25-50`。

1. 第 25-31 行声明异步评估函数及命名参数。
2. 第 32 行对简历文本去空白；第 33-34 行拒绝空文本。
3. 第 36 行从 `SkillRegistry` 取得 `resume-analyst` Skill；第 37-39 行从外置 `resources/prompts/resume/analysis.md` 渲染提示词，业务提示词不内嵌在代码里。
4. 第 40-44 行组装 subjectId、岗位、标准化简历文本的模型输入。
5. 第 45-50 行调用 `StructuredOutputInvoker.invoke`，传入模型、`ResumeEvaluation` schema、渲染好的提示词和输入；其重试器仅包裹模型调用与结构化结果校验，返回后即回到 API 路由构造响应。

## 4. 主流构建分析

当前实现采用“同步接收文件和建任务 + RabbitMQ 异步调用 Python + PostgreSQL 最终事实来源 + Redis 加速状态查询”的分层方式。它的优点是浏览器不等待模型、Java/Python 可独立扩缩容、任务状态可恢复、Redis 不可用不会丢失业务结果；缺点是端到端最终一致、需要处理重复消息和状态轮询，且本机限流回退不能提供跨实例严格限额。

主流生产方案可进一步采用 **Transactional Outbox + 消费端幂等表/Inbox**：在创建 `resume_analyses` 的同一 PostgreSQL 事务中写一条 outbox 事件，由独立发布器可靠投递 RabbitMQ；消费者在同一事务中登记已处理消息 ID 再更新分析状态。优点是避免“数据库已提交但 RabbitTemplate 发送失败”或“消息已发送但事务回滚”的双写窗口，并使重放可审计；缺点是新增 outbox 表、轮询/CDC 发布器、清理策略和运维监控。

本项目适合在任务量增加、需要更高投递可靠性时采用。实施步骤是：第一，在 `infrastructure/postgres/init` 增加 `outbox_events` 与 `processed_messages` 表；第二，把 `ResumeAnalysisWorker.enqueue` 改为由 `ResumeAnalysisPersistenceService.create` 写 outbox，而非直接 `convertAndSend`；第三，新增定时发布器或 Debezium CDC 将未发布事件推送交换机，并在成功后标记；第四，让 `RabbitAgentWorkConsumer` 以消息 ID 写 Inbox 后再处理；第五，继续保持当前“事务提交后才刷新 Redis”的规则。对于当前实习项目，现有实现更轻量；Outbox 应在需要可靠跨服务投递时再引入。
