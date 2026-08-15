# GET /api/resumes/{id}/detail：查看简历详情完整函数调用链

> 本文对应 Java 接口清单第 3 个接口。源码结论：该请求只读取 Java 后端 PostgreSQL 投影，不调用 RabbitMQ、PythonAgentClient、HttpPythonAgentClient 或 Python Agent；Python 调用链长度为零。

## 1. 接口定义

### 1.1 功能和作用

该接口按简历 ID 返回当前用户拥有的一份简历详情，包括文件元数据、解析后的简历文本、该简历关联的文本面试会话投影，以及所有历史简历分析记录。它只读，不创建分析、不修改会话，也不请求 Python。

### 1.2 基本信息

| 项目 | 内容 |
| --- | --- |
| HTTP 方法 | GET |
| 路径 | /api/resumes/{id}/detail |
| 路径变量 | id，String 类型简历主键 |
| Controller | ResumeController.detail |
| Service | ResumeService.detail |
| 返回 | ApiResult<Map<String,Object>> |
| 授权 | ResumeService.owned 与 ResumeService.owns |
| Python 调用 | 无 |
| 消息队列 | 无 |

### 1.3 前端入口

ResumeDetailPage 初次挂载调用 loadResumeDetail；分析为 PENDING、PROCESSING 或 analyses 为空时，页面五秒轮询调用 loadResumeDetailSilent。两条回调最终都访问 historyApi.getResumeDetail。

## 2. 函数调用链

~~~text
ResumeDetailPage.loadResumeDetail
  或 ResumeDetailPage.loadResumeDetailSilent
 -> historyApi.getResumeDetail
 -> request.get
 -> Axios 请求拦截器
    -> currentUserId -> createClientId
 -> GET /api/resumes/{id}/detail
 -> RequestIdFilter.doFilterInternal -> normalize
 -> SimpleRateLimitFilter.doFilterInternal
 -> ResumeController.detail
 -> ResumeService.detail
    -> ResumeService.owned
       -> ResumeRepository.findById
       -> UserIdentityResolver.require
       -> ResumeService.owns
          -> CandidateRepository.findById
          -> CandidateEntity.getUserId
    -> ResumeEntity getter
    -> UserIdentityResolver.require
    -> InterviewSessionRepository.findByUserIdOrderByCreatedAtDesc
    -> InterviewSessionEntity.getResumeId
    -> ResumeService.toInterviewView
       -> InterviewSessionEntity getter
       -> ResumeService.parseFinalEvaluation
    -> ResumeAnalysisService.list
       -> ResumeAnalysisPersistenceService.list
       -> ResumeAnalysisRepository.findByResumeIdOrderByCreatedAtDesc
       -> ResumeAnalysisService.toView
          -> ResumeAnalysisEntity getter
          -> stringList -> ObjectMapper.readValue 或 List.of
          -> mapList -> ObjectMapper.readValue 或 List.of
 -> ApiResult.success
 -> Axios 响应拦截器
 -> ResumeDetailPage.setResume
~~~

链路在 ResumeService.detail 第176行 return 后结束。Python 层没有入口函数被调用，不应在本接口文档中加入 /v1 路径。

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 ResumeDetailPage.loadResumeDetail

文件：frontend/src/pages/ResumeDetailPage.tsx:42-52。

1. 第42行用 useCallback 建立异步函数，依赖 resumeId，resumeId 改变时创建新回调。
2. 第43行 setLoading(true)，完整加载会显示加载界面。
3. 第45行 await historyApi.getResumeDetail(resumeId)，网络请求未完成前后续语句不运行。
4. 第46行 setResume(data)，把解包后的 ResumeDetail 写入 React 状态。
5. 第47-48行捕获异常并 console.error；该分支不向 Java 发送额外请求。
6. 第49-51行 finally 总是 setLoading(false)，成功和失败都关闭加载态。
7. 第52行结束函数。

#### 3.1.2 ResumeDetailPage.loadResumeDetailSilent

文件：frontend/src/pages/ResumeDetailPage.tsx:33-40。

1. 第33行定义静默异步回调。
2. 第35行调用同一个 historyApi.getResumeDetail，参数仍是 resumeId。
3. 第36行成功时 setResume(data)。
4. 第37-39行失败时只记录错误，不写 loading，避免轮询造成页面闪烁。
5. 第40行结束。该函数的请求语义和完整加载相同，区别仅在 UI 状态处理。

#### 3.1.3 ResumeDetailPage 的首次加载和轮询 effect

文件：frontend/src/pages/ResumeDetailPage.tsx:54-65。

1. 第54行定义首次加载 effect；第55行调用 loadResumeDetail；第56行声明依赖。
2. 第60行定义轮询 effect。
3. 第61行要求 resume 存在。
4. 第62行判断第一项分析状态 PENDING。
5. 第63行判断第一项分析状态 PROCESSING。
6. 第64行把 analyses 缺失或长度为零也视为可能仍等待分析。
7. 条件为真时后续代码创建每五秒调用 loadResumeDetailSilent 的 timer；cleanup 清理 timer。
8. 此 effect 只由前端重复发 GET，不表示后端递归或 Python 推送。

#### 3.1.4 historyApi.getResumeDetail 与 request.get

文件：frontend/src/api/history.ts:84-87；frontend/src/api/request.ts:157-160。

1. historyApi 第84行定义 API 对象。
2. 第86行定义 getResumeDetail，参数允许 string 或 number。
3. 第86行以动态 id 拼接详情路径，再调用 request.get<ResumeDetail>。
4. request.get 第158行声明泛型函数；第159行 instance.get 发送请求，then 返回 response.data；第160行结束。
5. 泛型不改变运行时请求；响应对象的 data 已被 Axios 成功拦截器解开。

#### 3.1.5 createClientId、currentUserId 和请求拦截器

文件：frontend/src/api/request.ts:47-73。

1. createClientId 第47行定义 prefix 默认 anonymous。
2. 第48行浏览器支持 crypto.randomUUID 时直接返回 UUID。
3. 第49行是降级分支，拼接前缀、当前时间和两段随机数。
4. currentUserId 第52行定义；第53行读取 localStorage。
5. 第54行存在非空 ID 时复用；第55行否则生成；第56行保存；第57行返回。
6. 请求拦截器第64行接收 config；第65行确保 headers 对象。
7. 第66-69行内部 setHeader 判断 headers.set 是否存在，分别调用 set 或索引赋值。
8. 第70行写 X-User-Id；第71行写 X-Request-Id；第72行返回 config。
9. getUserId 第60-62行只是公开包装，本请求由拦截器直接调用 currentUserId。

#### 3.1.6 响应和错误辅助函数

文件：frontend/src/api/request.ts:75-155。

1. 成功拦截器第124行读 response；第125行读 result。
2. 第126行调用 isRecord；isRecord 第75-77行只接受非 null 的非数组 object。
3. 第127-129行当 code 是200时把 response.data 改为 result.data 并返回。
4. 第131-132行非200时调用 parseApiError 并拒绝 Promise。
5. stringValue 第79-81行只返回非空字符串。
6. parseApiError 第83-99行读取嵌套 error 或外层 code，构造包含 requestId、runId、stage 的 ApiRequestError。
7. 失败拦截器第136-153行对无响应调用 transportError，对有响应调用 decodeErrorData 和 parseApiError。
8. decodeErrorData 第101-108行只解析 JSON Blob；transportError 第110-121行按超时或连接中断生成错误。

### 3.2 Java 入口函数

#### 3.2.1 RequestIdFilter.doFilterInternal 与 normalize

文件：java-backend/src/main/java/com/interviewguide/infrastructure/web/RequestIdFilter.java:23-41。

1. 第25行读取 X-Request-Id 并调用 normalize。
2. normalize 第36-41行仅接受非空、长度不超过128、匹配允许正则的值；第38行返回合法值，第40行非法时生成 UUID。
3. 第26行把 ID 存到 request attribute。
4. 第27行写入响应头。
5. 第28行放入 MDC。
6. 第30行 chain.doFilter 进入下一个过滤器。
7. 第31-33行 finally 清除 MDC，线程复用不会携带旧日志 ID。

#### 3.2.2 SimpleRateLimitFilter.doFilterInternal

文件：java-backend/src/main/java/com/interviewguide/infrastructure/ratelimit/SimpleRateLimitFilter.java:38-61。

1. 第40-43行只放行 health 和 actuator；详情路径继续限流。
2. 第44行以来源地址和 URI 生成键。
3. 第45行计算当前分钟。
4. 第46-47行 ConcurrentHashMap.compute 创建新 Window、跨分钟替换 Window、或复用旧 Window。
5. 第48行原子增加计数并比较 limit。
6. 超限时第49-57行设置429、Retry-After、JSON 响应和 requestId，第58行 return。
7. 未超限第60行继续 filterChain。

#### 3.2.3 ResumeController.detail 与 ApiResult.success

文件：java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:45-49；java-backend/src/main/java/com/interviewguide/common/web/dto/ApiResult.java:3-6。

1. Controller 第45行的映射与类级 /api/resumes 组合为详情路径。
2. 第46行 @PathVariable 绑定 id，返回类型是 ApiResult<Map<String,Object>>。
3. 第47行读取可能缺失的 X-User-Id。
4. 第48行调用 resumeService.detail(id,userId)，成功后调用 ApiResult.success。
5. success 第4行接收 Map；第5行构造 code=200、message=success、data=Map。
6. 空用户头不是 Spring 绑定错误，而由 Service 中 require 抛业务异常。

### 3.3 ResumeService.detail 与授权函数

#### 3.3.1 ResumeService.detail

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:160-177。

1. 第160-161行声明 id、userId 和返回 Map。
2. 第162行调用 owned，只有授权成功才继续。
3. 第163行创建 HashMap。
4. 第164行写 id，调用 resume.getId。
5. 第165行写 filename，调用 getOriginalFilename。
6. 第166行写 fileSize，调用 getFileSize。
7. 第167行写 contentType，调用 getContentType。
8. 第168行写 uploadedAt，调用 getCreatedAt。
9. 第169行写 resumeText，调用 getContent。
10. 第170-174行查询 interviews；第171行再次 require userId；第172行比较 resume.getId 与 session.getResumeId；第173行 map this::toInterviewView；第174行 toList。
11. 第175行调用 analysisService.list(id) 写 analyses。
12. 第176行 return result；第177行结束。没有写 Entity，没有 Python 调用。

#### 3.3.2 ResumeService.owned

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:276-283。

1. 第276行定义私有授权加载函数。
2. 第277行调用 resumeRepository.findById。
3. 第278行 Optional 为空时抛 RESUME_NOT_FOUND。
4. 第279行调用 identity.require，再调用 owns。
5. 第280行 owns=false 时抛 RESUME_ACCESS_DENIED。
6. 第282行返回 ResumeEntity；第283行结束。
7. 因而 id 不存在、用户头缺失、简历不属于用户是三个独立失败分支。

#### 3.3.3 ResumeService.owns

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:285-288。

1. 第285行声明布尔函数。
2. 第286行用 resume.getCandidateId 调 CandidateRepository.findById。
3. 第287行 Optional.map 调 candidate.getUserId，与传入 userId equals 比较。
4. 同一行 orElse(false) 处理候选人缺失。
5. 第288行结束，不写候选人、不抛候选人不存在异常。

#### 3.3.4 UserIdentityResolver 和 Repository

文件：java-backend/src/main/java/com/interviewguide/common/security/UserIdentityResolver.java:14-19；ResumeRepository.java:8-12；CandidateRepository.java:7-9。

1. require 第15行检查 null 或 blank；第16行抛 USER_ID_REQUIRED。
2. 第18行 strip 后返回，首尾空白不会成为不同用户 ID。
3. ResumeRepository.findById 和 CandidateRepository.findById 是 JpaRepository 继承的主键查询；它们在 owned/owns 调用。
4. JPA SQL 生成是框架实现，项目定义的资源级授权逻辑是 owned 和 owns。

### 3.4 面试投影函数

#### 3.4.1 InterviewSessionRepository.findByUserIdOrderByCreatedAtDesc

文件：java-backend/src/main/java/com/interviewguide/interview/mapper/InterviewSessionRepository.java:14-20。

1. 第14行声明 Repository。
2. 第15行声明项目派生查询：按 userId 过滤，按 createdAt 降序。
3. 第16-17行未结束会话查询不在本链使用。
4. 第18-20行带悲观锁的 findByIdForUpdate 不在本链使用。
5. ResumeService.detail 第171行先用用户过滤，再以第172行的 Java stream 做 resumeId 过滤。

#### 3.4.2 ResumeService.toInterviewView

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:290-299。

1. 第290行声明私有转换函数。
2. 第291行创建 InterviewView。
3. 第292行读取 id、userId、candidateId、resumeId。
4. 第293行读取 jdId、interviewDirection、difficulty。
5. 第294行读取 totalQuestions、status.name、agentStateVersion。
6. 第295行读取 currentQuestion、currentStage、issuedQuestionCount。
7. 第296行读取 primaryQuestionCount、totalPrimaryQuestionCount、followupCount。
8. 第297行调用 parseFinalEvaluation(session.getFinalEvaluationJson)。
9. 第298行读取 createdAt、updatedAt；第299行结束。
10. InterviewView 字段顺序可在 java-backend/src/main/java/com/interviewguide/interview/dto/InterviewView.java:6-26 对照。

#### 3.4.3 ResumeService.parseFinalEvaluation

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:301-308。

1. 第301行接收 raw JSON。
2. 第302行 null/blank 时返回 Map.of。
3. 第303行进入 try。
4. 第304行 ObjectMapper.readValue 反序列化 Map<String,Object>。
5. 第305-307行任意异常时返回空 Map。
6. 第308行结束。坏的历史评价 JSON 不会使详情接口失败。

#### 3.4.4 InterviewSessionEntity getter

文件：java-backend/src/main/java/com/interviewguide/interview/domain/InterviewSessionEntity.java:118-137。

1. getId 到 getResumeId（118-121行）均单行 return；第172行先用 getResumeId 过滤。
2. getJdId 到 getFollowupCount（122-129行）被 toInterviewView 第293-296行读取。
3. getFinalEvaluationJson 第130行传入 parseFinalEvaluation。
4. getStatus 第131行返回枚举，toInterviewView 调 name。
5. getAgentStateVersion 第133行、getCurrentQuestion 第134行、getCurrentStage 第135行、getCreatedAt 第136行、getUpdatedAt 第137行都被 toInterviewView 读取。
6. activate、applyAgentResponse、complete 等状态写函数不在 GET 详情链路中调用。

### 3.5 分析历史投影函数

#### 3.5.1 ResumeAnalysisService.list 与 Persistence.list

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:67-69；ResumeAnalysisPersistenceService.java:54-56。

1. ResumeAnalysisService.list 第67行声明函数。
2. 第68行调用 persistence.list，再对实体流 map this::toView 并 toList。
3. 第69行结束。
4. Persistence.list 第54行声明函数；第55行调用 repository.findByResumeIdOrderByCreatedAtDesc；第56行结束。
5. Repository 的派生查询位于 resume/mapper/ResumeAnalysisRepository.java:10-14，按 resumeId 筛选、按 createdAt 逆序返回历史记录。

#### 3.5.2 ResumeAnalysisService.toView、stringList、mapList

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:82-100。

1. toView 第83-85行读取 id、status、六个评分、summary、updatedAt。
2. 第86行两次调用 stringList，分别解析 strengthsJson、suggestionsJson。
3. 第87行调用 mapList 解析 issuesJson，并取 error，构造 ResumeAnalysisView。
4. stringList 第91行空值返回 List.of；第92行 ObjectMapper 解析；第93行异常返回 List.of。
5. mapList 第97行空值短路；第98行解析 List<Map<String,Object>>；第99行异常返回 List.of。
6. 详情接口返回完整 analyses，因此 strengths、suggestions、issues 都会通过本链传给前端。

#### 3.5.3 ResumeAnalysisService.stringList

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:90-94。

1. 第90行声明接收原始 JSON 字符串并返回 List<String>。
2. 第91行检查 raw 是否为 null 或 blank；是时立即返回 List.of，避免把空值交给 ObjectMapper。
3. 第92行进入 try，调用 objectMapper.readValue，并用 TypeReference 保留 List<String> 的目标泛型。
4. JSON 数组解析成功时第92行直接返回结果；解析失败时控制流转到第93行。
5. 第93行捕获 Exception 并返回空列表，使单条损坏的 strengths 或 suggestions 不会让整个详情接口失败。
6. 第94行结束函数。详情接口中该函数分别解析 strengthsJson 与 suggestionsJson。

#### 3.5.4 ResumeAnalysisService.mapList

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:96-100。

1. 第96行声明接收 issues JSON 并返回 List<Map<String,Object>>。
2. 第97行对 null 或 blank 输入做短路，返回 List.of。
3. 第98行调用 ObjectMapper.readValue，TypeReference 指定目标为列表中的对象映射。
4. 解析成功时第98行直接返回；格式错误、类型不匹配等异常进入第99行。
5. 第99行忽略异常并返回空列表，保证历史分析中 issues 字段损坏时仍能返回其它详情字段。
6. 第100行结束函数；ResumeAnalysisService.toView 第87行调用它。

#### 3.5.3 ResumeAnalysisEntity getter

文件：java-backend/src/main/java/com/interviewguide/resume/domain/ResumeAnalysisEntity.java:111-129。

1. 第111-114行返回 id、resumeId、targetRole、status。
2. 第115-120行返回六项评分。
3. 第121-125行返回 summary、三个 JSON 字段和 error。
4. 第126-129行返回 retryCount、lastAttemptAt、createdAt、updatedAt。
5. toView 使用 id、status、六评分、summary、JSON、error、updatedAt；不使用 retryCount、lastAttemptAt、createdAt、targetRole。
6. 每个 getter 都只有 return 语句，不写状态，不访问数据库，不调用 Python。

### 3.6 ResumeEntity、错误边界和 Python 边界

文件：java-backend/src/main/java/com/interviewguide/resume/domain/ResumeEntity.java:37-46。

1. getId 第37行、getCandidateId 第38行、getContent 第40行、getOriginalFilename 第42行、getFileSize 第43行、getContentType 第44行、getCreatedAt 第46行分别被 owned、detail 和会话过滤使用。
2. 这些 getter 只读取已经由 JPA 水合的字段；原文件读取只会在另一个 download 接口发生。
3. ApiExceptionHandler.handleBusiness（common/web/ApiExceptionHandler.java:31-35）处理 RESUME_NOT_FOUND、RESUME_ACCESS_DENIED、USER_ID_REQUIRED。
4. handleDataAccess（86-92行）将数据库异常映射为503。
5. 当前接口没有 PythonAgentException 的产生路径，因为本 Service 完全没有 Python HTTP 调用。

## 4. 审核结论

1. 接口定义已包含路径、变量、功能、返回、授权和 Python 边界。
2. 调用链已包含前端首次加载/轮询、请求拦截器、过滤器、Controller、授权、面试投影、分析投影和响应。
3. 每个项目定义且实际可达的函数都标注了文件和行号，并说明执行语句和分支。
4. 代码依据是 ResumeController.java:45-49 和 ResumeService.java:160-177；两处均没有 Python 客户端、Agent DTO、RabbitTemplate 或 /v1 调用。
5. 当前文档审核通过后，下一接口按顺序为 GET /api/resumes/{id}/export。
