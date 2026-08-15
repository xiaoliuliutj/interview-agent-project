# GET /api/resumes：查询我的简历列表完整函数调用链

> 本文对应 Java 后端接口清单中的第 2 个接口。结论先行：该接口只读取 Java 后端 PostgreSQL 中的简历、分析和面试会话投影；当前项目实现没有调用 RabbitMQ、HttpPythonAgentClient 或 Python Agent。因此函数调用链在 Java Service 返回后结束，不能虚构 Python 调用。

## 1. 接口定义

### 1.1 功能和作用

接口向当前用户返回其拥有的全部简历列表。每个列表项包含简历基础字段、该简历最近一次分析的状态和分数，以及当前用户在该简历上创建的文本面试数量。它不是分页接口，也不启动分析任务；分析状态为 PENDING 或 PROCESSING 时，前端会每五秒重新请求该接口。

### 1.2 基本信息

| 项目 | 内容 |
| --- | --- |
| HTTP 方法 | GET |
| 路径 | /api/resumes |
| Controller | ResumeController.list |
| Service | ResumeService.list |
| 认证输入 | X-User-Id 请求头 |
| 成功响应 | ApiResult<List<Map<String,Object>>> |
| Python 调用 | 无 |
| 写操作 | 无；所有 Repository 调用均为读取 |

### 1.3 前端触发条件

HistoryPage 首次挂载时调用 load；如果列表中存在 PENDING 或 PROCESSING 分析，第二个 useEffect 建立五秒定时器再次调用 load。因此同一个接口既由初次页面加载调用，也由轮询调用。

## 2. 函数调用链

~~~text
HistoryPage.useEffect 首次回调
  -> HistoryPage.load
  -> historyApi.getResumes
  -> request.get
  -> Axios request interceptor
       -> currentUserId
       -> createClientId
  -> GET /api/resumes
  -> RequestIdFilter.doFilterInternal
       -> normalize
  -> SimpleRateLimitFilter.doFilterInternal
  -> ResumeController.list
  -> ResumeService.list
       -> UserIdentityResolver.require
       -> ResumeRepository.findAll
       -> 对每一条 ResumeEntity：
          -> ResumeService.owns
             -> CandidateRepository.findById
             -> CandidateEntity.getUserId
          -> ResumeAnalysisService.latest
             -> ResumeAnalysisPersistenceService.latest
             -> ResumeAnalysisRepository.findFirstByResumeIdOrderByCreatedAtDesc
             -> ResumeAnalysisService.toView
                -> ResumeAnalysisEntity 全部 getter
                -> stringList（strengths）
                -> stringList（suggestions）
                -> mapList（issues）
          -> ResumeEntity.getId/getOriginalFilename/getFileSize/getContent/getCreatedAt
          -> InterviewSessionRepository.findByUserIdOrderByCreatedAtDesc
          -> InterviewSessionEntity.getResumeId
       -> List.toList
  -> ApiResult.success
  -> Axios response interceptor
  -> HistoryPage.load 写入 resumes
  -> HistoryPage 轮询 useEffect（仅 PENDING/PROCESSING 时）
~~~

链路在 ResumeService.list 的第 158 行返回后就离开项目业务函数。没有 Agent DTO、没有 RabbitTemplate、没有 Python HTTP 路径；这是代码中可验证的同步只读边界。

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 HistoryPage 组件内的 load

文件：frontend/src/pages/HistoryPage.tsx:7-18，实际函数在第 13 行。

~~~tsx
const load = useCallback(async () => {
  setLoading(true);
  try {
    setResumes(await historyApi.getResumes());
    setError('');
  } catch (requestError) {
    setError(requestError instanceof Error ? requestError.message : '加载简历失败');
  } finally {
    setLoading(false);
  }
}, []);
~~~

1. 第 7 行声明 HistoryPage，onSelectResume 不参与列表 HTTP 请求。
2. 第 8-12 行建立 resumes、loading、error、pendingDelete、deleting 五个状态；本接口只读取并修改前三个。
3. 第 13 行 useCallback 创建异步 load，空依赖数组使函数引用在组件生命周期内稳定。
4. 同一行的 setLoading(true) 在请求前打开加载状态。
5. try 块的 historyApi.getResumes 发起 GET；await 使 setResumes 只在请求完成后执行。
6. 请求成功后 setResumes 写入数组，setError('') 清理旧错误。
7. catch 中 requestError 是 Error 时取其 message；否则使用固定“加载简历失败”。
8. finally 总会 setLoading(false)，包括业务错误、网络错误与组件轮询请求。
9. 第 14 行 useEffect 在页面挂载后执行 void load；void 明确忽略异步 Promise。
10. 第 15 行第二个 useEffect 先检查 resumes 中是否有 PENDING 或 PROCESSING；没有时 return，不创建定时器。
11. 有进行中分析时，setInterval 每 5000ms 调用 load；return 的 cleanup 调 clearInterval，组件卸载或依赖变化时停止轮询。
12. 第 16-17 行删除和重新分析函数会在完成后 await load，但它们不是 GET /api/resumes 的初次访问入口。

#### 3.1.2 historyApi.getResumes

文件：frontend/src/api/history.ts:84-86。

1. 第 84 行定义 historyApi 对象。
2. 第 85 行定义 getResumes 零参数箭头函数。
3. 该函数调用 request.get<ResumeListItem[]>('/api/resumes')；泛型只供 TypeScript 编译期检查，不改变 HTTP 数据。
4. 返回的 Promise 由 HistoryPage.load 等待。

#### 3.1.3 request.get

文件：frontend/src/api/request.ts:157-160。

1. 第 157 行定义统一 request 对象。
2. 第 158 行定义泛型 get，接收 URL 和可选 Axios 配置。
3. 第 159 行调用 instance.get(url, config)。
4. 同一行 then 回调返回 response.data；成功响应拦截器已经把 ApiResult.data 解包，因此页面拿到 ResumeListItem 数组。
5. 第 160 行结束函数。Axios 的请求发送和 JSON 解析属于框架行为，不是项目自定义函数。

#### 3.1.4 createClientId、currentUserId 与请求拦截器

文件：frontend/src/api/request.ts:47-73。

1. createClientId 第 47 行定义可选 prefix，默认 anonymous。
2. 第 48 行检测 crypto.randomUUID；支持时直接返回 UUID。
3. 第 49 行是降级分支，拼接 prefix、Date.now 和两段随机十六进制。
4. currentUserId 第 52 行定义私有函数；第 53 行读取 localStorage。
5. 第 54 行已有非空值时返回；第 55 行否则调用 createClientId；第 56 行保存；第 57 行返回新 ID。
6. 请求拦截器第 64 行接收 Axios config；第 65 行确保 headers 对象存在。
7. 第 66-69 行定义 setHeader，AxiosHeaders 有 set 方法时调用它，否则对普通对象赋值。
8. 第 70 行调用 currentUserId 并设置 X-User-Id；第 71 行调用 createClientId('web') 并设置 X-Request-Id；第 72 行返回 config。
9. getUserId 第 60-62 行只是 currentUserId 的公开包装，本次 GET 未直接调用它。

#### 3.1.5 响应拦截器和错误函数

文件：frontend/src/api/request.ts:75-155。

1. 成功拦截器第 124 行接收 Axios response；第 125 行把 data 视为统一 Result。
2. 第 126 行调用 isRecord；isRecord 第 75-77 行要求值非 null、类型为 object 且不是数组。
3. 第 127 行识别数字或字符串 200；第 128 行用 result.data 覆盖 response.data；第 129 行返回 response。
4. 非 200 时第 131-132 行调用 parseApiError 后拒绝 Promise。
5. parseApiError 第 83-99 行先校验 record，再读取嵌套 error 或外层 code；第 88-98 行利用 stringValue 构造 ApiRequestError。
6. stringValue 第 79-81 行只返回非空字符串；这避免把空字段当有效诊断信息。
7. 失败拦截器第 136-153 行处理 AxiosError；无 response 时调用 transportError；有 response 时调用 decodeErrorData 和 parseApiError。
8. decodeErrorData 第 101-108 行只解析 JSON Blob；transportError 第 110-121 行把上传和普通请求分别映射为超时或网络不可用错误。GET 列表实际走普通请求文案。

### 3.2 Java Web 入口函数

#### 3.2.1 RequestIdFilter.doFilterInternal 与 normalize

文件：java-backend/src/main/java/com/interviewguide/infrastructure/web/RequestIdFilter.java:23-41。

1. 第 25 行读 X-Request-Id 并调用 normalize。
2. normalize 第 36-41 行仅复用非空、长度不超过 128 且匹配允许字符集的值；否则第 40 行生成 UUID。
3. 第 26 行把 ID 放入 request attribute；第 27 行写到响应头；第 28 行写入 MDC。
4. 第 30 行调用 chain.doFilter 进入下一个过滤器和 Controller。
5. 第 31-33 行 finally 移除 MDC，避免复用线程携带旧 ID。

#### 3.2.2 SimpleRateLimitFilter.doFilterInternal

文件：java-backend/src/main/java/com/interviewguide/infrastructure/ratelimit/SimpleRateLimitFilter.java:38-61。

1. 第 40-43 行只对 health 与 actuator 放行；/api/resumes 不满足，因此继续限流。
2. 第 44 行用 remoteAddr 与 URI 组成 key；第 45 行计算当前分钟。
3. 第 46-47 行 compute 创建新 Window 或重用当前分钟 Window。
4. 第 48 行 incrementAndGet 后比较 limit。
5. 超限时第 49-57 行设置 429、Retry-After、JSON 内容类型，读取 RequestIdFilter attribute，构造 ApiErrorDetail 和 ApiErrorResponse；第 58 行 return。
6. 未超限时第 60 行调用 filterChain.doFilter，Spring 随后路由 Controller。

#### 3.2.3 ResumeController.list

文件：java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:39-43。

1. 第 39 行 @GetMapping 没有子路径，和类上的 /api/resumes 拼接为 GET /api/resumes。
2. 第 40-41 行声明返回 ApiResult<List<Map<String,Object>>> 并由 Spring 绑定 X-User-Id。
3. 第 42 行先调用 resumeService.list(userId)，再调用 ApiResult.success。
4. 第 43 行结束函数。用户头缺失不会在 Controller 拦截，而在 Service 的 identity.require 抛出。

#### 3.2.4 ApiResult.success

文件：java-backend/src/main/java/com/interviewguide/common/web/dto/ApiResult.java:3-6。

1. 第 3 行声明 code/message/data 三字段 record。
2. 第 4 行定义静态泛型 success。
3. 第 5 行创建 code=200、message=success、data=Service 结果的响应。
4. 第 6 行结束。响应 JSON 外层会被前端拦截器解包。

### 3.3 ResumeService.list

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:140-159。

1. 第 140-141 行声明函数和 userId 参数。
2. 第 142 行调用 identity.require，空 userId 抛 USER_ID_REQUIRED，非空 userId strip 后成为 owner。
3. 第 143 行调用 resumeRepository.findAll；这是 JpaRepository 继承方法，读取 resumes 表所有记录。随后 stream 逐条处理。
4. 第 143 行的 filter 调用本类 owns(item, owner)。不属于该 owner 的简历会被完全排除，之后 map 不会执行。
5. map lambda 的第 144 行调用 analysisService.latest(resume.getId())；getId 是 ResumeEntity 第37行的单句 getter，返回 id。
6. 第 145 行为当前 resume 创建 HashMap。
7. 第 146 行调用 getId 写入 id。
8. 第 147 行调用 getOriginalFilename（ResumeEntity:42）写入 filename。
9. 第 148 行调用 getFileSize（43）写入 fileSize。
10. 第 149 行调用 getContent（40）写入 resumeText；注意前端 ResumeListItem 类型没有该字段，但 JSON 实际会包含它。
11. 第 150 行调用 interviewSessionRepository.findByUserIdOrderByCreatedAtDesc(owner)，该派生查询只取当前用户会话并按 createdAt 降序。
12. 第 151 行对会话流调用 session.getResumeId，只有等于当前 resume.getId 的会话计数，写入 interviewCount。
13. 第 152 行调用 getCreatedAt（46）写入 uploadedAt。
14. 第 153 行 latest 为 null 时写 latestScore=null，否则调用 ResumeAnalysisView.overallScore。
15. 第 154 行同样写 lastAnalyzedAt 或 null。
16. 第 155 行同样写 analyzeStatus 或 null。
17. 第 156 行同样写 analyzeError 或 null。
18. 第 157 行返回该 resume 的 map；第 158 行 toList 收集所有通过 owns 的映射结果；第159行结束。
19. 该函数不存在 pythonAgentClient、RabbitTemplate、Worker 或 Agent DTO 调用，因此 Python 层调用数为零。

#### 3.3.1 ResumeService.owns

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:285-288。

1. 第 285 行定义私有归属判断，参数为 ResumeEntity 和规范化 userId。
2. 第 286 行用 resume.getCandidateId（38）查 CandidateRepository.findById。
3. 第 287 行 Optional.map 中调用 candidate.getUserId（26），与传入 userId 做 equals 比较。
4. 同一行 orElse(false) 保证候选人不存在时返回 false，而不是泄露简历记录。
5. 第 288 行结束。它是列表接口的资源级授权核心。

#### 3.3.2 Repository 查询函数

文件：java-backend/src/main/java/com/interviewguide/resume/mapper/ResumeRepository.java:8-12；CandidateRepository.java:7-9；InterviewSessionRepository.java:14-20。

1. ResumeRepository 继承 JpaRepository，findAll 是框架继承的全表读取函数。
2. CandidateRepository.findById 是框架继承的主键读取函数；本接口在 owns 中使用。
3. InterviewSessionRepository.findByUserIdOrderByCreatedAtDesc 在第15行由项目声明。Spring Data 解析名称为“按 userId 过滤、按 createdAt 降序查询”。
4. 这三项都是读查询，没有 save、delete 或事务状态修改。
5. SQL 生成和实体水合属于 Spring Data/JPA 框架，不是项目中手写的函数实现。

### 3.4 最新分析投影函数

#### 3.4.1 ResumeAnalysisService.latest

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:62-65。

1. 第 62 行声明按 resumeId 取最近分析的函数。
2. 第 63 行调用 persistence.latest。
3. 第 64 行 analysis 为 null 返回 null；否则调用 toView。
4. 第 65 行结束。这个 null 直接决定 ResumeService.list 的四个分析字段是否为 null。

#### 3.4.2 ResumeAnalysisPersistenceService.latest

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisPersistenceService.java:50-52。

1. 第 50 行声明函数。
2. 第 51 行调用 repository.findFirstByResumeIdOrderByCreatedAtDesc(resumeId)。
3. Optional 没有记录时 orElse(null)，不会抛分析不存在异常。
4. 第 52 行结束。

ResumeAnalysisRepository 的该项目声明位于 java-backend/src/main/java/com/interviewguide/resume/mapper/ResumeAnalysisRepository.java:10-14；方法名要求按 resumeId 筛选并以 createdAt 倒序取第一条。

#### 3.4.3 ResumeAnalysisService.toView

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:82-88。

1. 第 82 行定义实体到 ResumeAnalysisView 的私有转换。
2. 第 83 行读取 entity.getId、getStatus、getOverallScore。
3. 第 84 行读取 content、structure、skillMatch 三个评分。
4. 第 85 行读取 expression、project、summary、updatedAt，并把 updatedAt 映射为 analyzedAt。
5. 第 86 行分别调用 stringList 解析 strengthsJson 和 suggestionsJson。
6. 第 87 行调用 mapList 解析 issuesJson，并读取 error。
7. 第 83-87 行参数齐备后构造 ResumeAnalysisView record；第 88 行结束。
8. ResumeAnalysisView 声明在 dto/ResumeAnalysisView.java:7-21；record 访问器 overallScore、analyzedAt、status、error 被 ResumeService.list 第153-156行使用。

#### 3.4.4 stringList 与 mapList

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:90-100。

1. stringList 第 90 行接收原始 JSON 字符串。
2. 第 91 行 null 或 blank 时返回不可变空 List，避免 ObjectMapper 解析空值。
3. 第 92 行在 try 中用 ObjectMapper.readValue 和 TypeReference 反序列化为 List<String>。
4. 第 93 行任意解析异常返回空 List；该接口不会因历史 JSON 损坏而整体失败。
5. mapList 第 96 行接收 issues JSON。
6. 第 97 行使用同样的 null/blank 短路。
7. 第 98 行解析为 List<Map<String,Object>>。
8. 第 99 行解析失败返回空 List；第100行结束。

### 3.5 实体 getter 的逐行作用

文件：java-backend/src/main/java/com/interviewguide/resume/domain/ResumeEntity.java:37-46；CandidateEntity.java:25-29；ResumeAnalysisEntity.java:111-129。

1. ResumeEntity.getId 第37行返回 id；getCandidateId 第38行返回 candidateId；getContent 第40行返回解析文本；getOriginalFilename 第42行返回文件名；getFileSize 第43行返回大小；getCreatedAt 第46行返回上传时间。每个函数都只返回字段，没有数据库读写或副作用。
2. CandidateEntity.getUserId 第26行返回 owner 的用户 ID；owns 使用它完成过滤。
3. ResumeAnalysisEntity.getId、第111行，getStatus、第114行，六个评分 getter 第115-120行，getSummary 第121行，三份 JSON getter 第122-124行，getError 第125行，getUpdatedAt 第129行全部被 toView 调用。
4. 这些 getter 是单语句函数：每一行的 return 只暴露对应私有字段；状态转换、重试计数和写数据库不在 getter 中发生。
5. InterviewSessionEntity.getResumeId 由 ResumeService.list 第151行调用；其作用是返回会话关联的简历 ID，以支持本地流过滤和 count。

## 4. Python 边界、失败与审核

### 4.1 Python 边界

从 ResumeController.list 到 ResumeService.list，再到 Repository 和 DTO 转换，源码中不存在 PythonAgentClient、HttpPythonAgentClient、RabbitTemplate、ResumeAnalysisWorker、AgentCallExecutor 或 /v1 路径。因此“到 Python 层调用结束”的准确描述是：本接口在 Java 只读数据投影完成后结束，未发生 Python 调用。

### 4.2 失败路径

1. RequestIdFilter 与限流过滤器可能在 Controller 前返回错误。
2. UserIdentityResolver.require 可能抛 USER_ID_REQUIRED。
3. JPA findAll、findById、分析查询或会话查询发生 DataAccessException 时，由 ApiExceptionHandler 的 handleDataAccess（java-backend/src/main/java/com/interviewguide/common/web/ApiExceptionHandler.java:86-92）记录 requestId 并返回 DATA_SERVICE_UNAVAILABLE。
4. 前端响应拦截器将错误转换为 ApiRequestError，HistoryPage.load 的 catch 把消息显示在页面。
5. 某候选人记录不存在时 owns 返回 false，该简历被静默过滤；这不是服务端错误，避免越权数据暴露。

### 4.3 当前文档审核清单

- 接口定义：已写明路径、方法、输入、输出、用途和 Python 边界。
- 函数调用链：已按前端、过滤器、Controller、Service、Repository、DTO 和响应顺序列出。
- 函数解析：每个项目定义且实际可达的函数均含文件和行号，并逐句说明执行语义。
- 未调用 Python：已按代码事实明确标记，而非补造调用链。


### 3.6 列表请求的过滤器和异常函数补充

#### 3.6.1 RequestIdFilter.normalize 的两个分支

文件：java-backend/src/main/java/com/interviewguide/infrastructure/web/RequestIdFilter.java:36-41。

1. 第 36 行声明 static normalize，因此无需依赖 Spring Bean 字段。
2. 第 37 行同时检查 value 非 null、长度小于等于128、正则匹配；三个条件短路执行。
3. 第 38 行返回调用方传来的值，前端传入的 web UUID 正常走这里。
4. 第 40 行只有任一检查失败才执行 UUID.randomUUID。
5. 第 41 行结束函数。生成的新值随后在 doFilterInternal 第26、27、28行同时进入 Servlet attribute、响应头和日志 MDC。

#### 3.6.2 SimpleRateLimitFilter.Window 和构造函数

文件：java-backend/src/main/java/com/interviewguide/infrastructure/ratelimit/SimpleRateLimitFilter.java:26-35。

1. 第 26 行定义私有 Window record，字段 epochMinute 表示窗口分钟，count 是该分钟的 AtomicInteger。
2. 第 27 行创建 ConcurrentHashMap，键是“来源地址:请求 URI”，值是 Window。
3. 第 28 行声明最终 limit；第 29 行声明 ObjectMapper。
4. 第 31-32 行构造函数接收配置的 requests-per-minute 和 Spring 注入的 ObjectMapper。
5. 第 33 行把 limit 下限钳制为1，非法0或负配置不会变成无限放行。
6. 第 34 行保存 ObjectMapper；第35行结束。
7. 上传接口和列表接口共享同一个过滤器实例，因此两个接口的计数分别按 URI 分桶，不会相互消耗限额。

#### 3.6.3 SimpleRateLimitFilter 的 Window 计算 lambda

文件：SimpleRateLimitFilter.java:44-48。

1. 第44行使用 remoteAddr 加 URI 创建 map key；用户 ID 不参与 key，因此同一来源多个用户共享本 URI 限额。
2. 第45行取得当前 Instant 的 epoch 秒后除60，任何一分钟内结果相同。
3. 第46行调用 windows.compute；该函数在同一 key 上原子地执行 lambda。
4. 第46-47行 lambda 的 ignored 是 key、old 是旧 Window。
5. old 为 null 时构造 new Window(minute,new AtomicInteger())。
6. old.epochMinute 不等于当前 minute 时同样构造新窗口，旧计数自然被替换。
7. 否则返回 old，保留当前分钟累积计数。
8. 第48行取得 window.count 后 incrementAndGet；判断严格大于 limit，等于 limit 的那一次仍被放行。

#### 3.6.4 ApiExceptionHandler.handleBusiness

文件：java-backend/src/main/java/com/interviewguide/common/web/ApiExceptionHandler.java:31-35。

1. 第31行声明 BusinessException 的异常处理映射。
2. 第32行接收业务异常和当前 HttpServletRequest。
3. 第33行调用私有 response，传入异常自身的 httpStatus、code、message、retryable。
4. 第34行调用 firstNonBlank(error.requestId(), requestId(request))，优先采用异常携带 requestId，缺失时从过滤器 attribute 取。
5. 同一行还传入 runId、sessionId、stage。
6. 第35行返回 ResponseEntity<ApiErrorResponse>。列表接口的 USER_ID_REQUIRED 会走该函数。

#### 3.6.5 ApiExceptionHandler.handleDataAccess 与 response

文件：ApiExceptionHandler.java:86-92、123-140。

1. handleDataAccess 第87行接收 DataAccessException 与 request。
2. 第88行记录 requestId 和异常堆栈。
3. 第89-91行调用 response，状态为503、类型为 DATA_SERVICE_UNAVAILABLE、retryable=true、stage 为 DATA_ACCESS。
4. response 第123-125行接收所有错误字段。
5. 第126-127行构造 ApiErrorDetail；第128行用 ResponseEntity.status(status).body 包装；第129行结束。
6. requestId 第131-136行先读 RequestIdFilter.ATTRIBUTE；值是非空 String 时返回；否则读 header；header 也空时生成 UUID。
7. firstNonBlank 第138-140行只在 preferred 为 null 或 blank 时返回 fallback。

### 3.7 ResumeService.list 的每个 lambda 与字段映射补充

#### 3.7.1 resumeRepository.findAll 返回流的授权顺序

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:143-158。

1. 第143行的 findAll 先从数据库得到所有 ResumeEntity；这个项目实现不是在 SQL 层按用户过滤。
2. 同一行紧接 stream，流尚未执行数据库二次查询。
3. filter lambda 对每个 entity 调用 owns；任何 owns=false 的元素不会进入后续 map。
4. 因此第144行的 analysisService.latest 只会对已授权简历执行，不会通过分析状态泄露其他用户资源。
5. map lambda 变量名 resume 是已经通过所有权检查的实体。
6. toList 是终端操作；在执行到第158行前，所有 filter/map 都是惰性的。
7. 返回的 List 保持 ResumeRepository.findAll 的默认遍历顺序，源码没有显式排序。

#### 3.7.2 最近分析为 null 的四个条件表达式

文件：ResumeService.java:153-156。

1. 第153行 condition 是 latest == null；为真写 null，否则调用 latest.overallScore。
2. 第154行同样控制 lastAnalyzedAt；ResumeAnalysisView.analyzedAt 来自实体 updatedAt。
3. 第155行同样控制 analyzeStatus；没有分析记录与未查到记录都表示 null，而不是伪造 PENDING。
4. 第156行同样控制 analyzeError；分析失败时它保留错误，成功时通常为 null。
5. 四行都不创建任务、不访问 Python，仅消费 latest 已经读取到的 DTO。

#### 3.7.3 interviewCount 的完整过滤语义

文件：ResumeService.java:150-151。

1. 第150行先调用 findByUserIdOrderByCreatedAtDesc(owner)，Repository 从数据库返回 owner 的所有文本面试会话。
2. 该调用位于每一条 resume 的 map lambda 内，因此 N 条简历会执行 N 次会话列表查询；当前实现没有批量聚合。
3. 第150-151行 stream 对这些会话逐个比较。
4. session.getResumeId 返回会话创建时保存的 resume ID。
5. resume.getId 返回当前外层简历 ID。
6. equals 为 true 的会话通过 filter；count 对通过元素计数。
7. count 返回 long；Map 接受 Long 自动装箱值。
8. 当前用户条件先在 Repository 查询中限制，resume ID 条件再在内存流中限制。

#### 3.7.4 ResumeAnalysisView record 访问器

文件：java-backend/src/main/java/com/interviewguide/resume/dto/ResumeAnalysisView.java:7-21。

1. 第7行声明 record，Java 为每个组件生成无副作用访问器。
2. 第8行 id 对应 id()；第9行 status 对应 status()。
3. 第10-15行对应六个评分访问器。
4. 第16行 summary、17行 analyzedAt、18行 strengths、19行 suggestions、20行 issues、21行 error 均生成同名访问器。
5. ResumeService.list 实际调用 overallScore、analyzedAt、status、error；其余访问器由详情或导出接口使用，不由本接口的 map lambda 调用。
6. record 构造与访问器是 Java 编译器生成的标准实现；项目源码实际定义的是字段顺序和类型。

### 3.8 分析 JSON 解析的成功与失败分支

#### 3.8.1 stringList 成功分支

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:90-94。

1. raw 非 null 且非 blank 时，第91行条件为 false，函数进入 try。
2. 第92行 ObjectMapper.readValue 读取 JSON 数组。
3. TypeReference 空匿名类保留 List<String> 的泛型信息，避免被反序列化为原始 List<Object>。
4. 读取成功时同一行立即 return，catch 不执行。
5. JSON 的每个元素类型不符合 String 或文本格式非法时，ObjectMapper 抛异常并进入第93行。
6. 第93行有意忽略异常变量，只返回 List.of；列表接口仍能返回其他简历项。

#### 3.8.2 mapList 成功分支

文件：ResumeAnalysisService.java:96-100。

1. 第96行声明 issues JSON 的转换函数。
2. 第97行执行与 stringList 相同的空值短路。
3. 第98行用 ObjectMapper 和 TypeReference 解析 List<Map<String,Object>>。
4. 第99行捕获全部 Exception 并返回空 List。
5. 第100行结束。该函数在列表接口中虽会被 toView 调用，但列表 Service 不把 issues 字段放入最终 Map；它仍会执行，因为 toView 总是构造完整 DTO。

### 3.9 访问控制 getter 的逐条核对

文件：java-backend/src/main/java/com/interviewguide/resume/domain/CandidateEntity.java:25-29。

1. getId 第25行 return id。
2. getUserId 第26行 return userId；ResumeService.owns 唯一调用它完成 owner 比较。
3. getDisplayName 第27行 return displayName，本接口不调用。
4. getCurrentResumeId 第28行 return currentResumeId，本接口不调用。
5. setCurrentResumeId 第29行赋值 currentResumeId，本接口不调用。
6. 因此列表接口只读取 CandidateEntity，不改变 candidate 的当前简历指针。

文件：java-backend/src/main/java/com/interviewguide/resume/domain/ResumeEntity.java:37-46。

1. getId 第37行 return id。
2. getCandidateId 第38行 return candidateId。
3. getVersion 第39行 return version，本接口不调用。
4. getContent 第40行 return content。
5. getFileHash 第41行 return fileHash，本接口不调用。
6. getOriginalFilename 第42行 return originalFilename。
7. getFileSize 第43行 return fileSize。
8. getContentType 第44行 return contentType，本接口不调用。
9. getStorageKey 第45行 return storageKey，本接口不调用。
10. getCreatedAt 第46行 return createdAt。
11. 所有这些函数均为单行无副作用 getter；实际调用点和未调用点已按源码区分。

### 3.10 审核结论

1. 代码搜索确认 ResumeController.list 只调用 ResumeService.list。
2. Service 源码确认唯一下游为 ResumeRepository、CandidateRepository、InterviewSessionRepository 和 ResumeAnalysisService/Persistence Service。
3. 全文没有把上传接口的 RabbitWorker、Python DTO 或 /v1 请求移植到列表接口。
4. 文档中的“无 Python 调用”由 ResumeService.java:140-159 的完整函数体和 Controller.java:39-43 的完整入口证明。
5. 该文档可作为第 2 个接口的审核通过版本；下一接口应从 GET /api/resumes/{id}/detail 的前端访问点重新追踪，而不能复用本接口的调用链。

