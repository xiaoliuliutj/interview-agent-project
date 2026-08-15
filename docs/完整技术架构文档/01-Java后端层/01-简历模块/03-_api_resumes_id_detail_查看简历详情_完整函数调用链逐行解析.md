# GET /api/resumes/{id}/detail：查看简历详情完整函数调用链逐行解析

> 以当前代码为准。本接口只读取 Java 数据与 Java Redis 快照，不调用 RabbitMQ 或 Python；如果已有异步分析任务，页面只读取其已持久化/已缓存状态。以下只列项目定义的函数，库函数只说明其调用效果。

## 1. 接口定义

### 1.1 功能与作用

`GET /api/resumes/{id}/detail` 返回一份指定简历的元数据、文本、关联面试会话和全部分析历史。它先验证该简历属于当前用户，因而不能通过替换 URL 中的 ID 读取其他用户简历。详情页用分析集合显示每次分析结果，用面试集合显示关联面试记录。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| HTTP 方法与路径 | `GET /api/resumes/{id}/detail` |
| Controller | `ResumeController.detail`，`java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:45-49` |
| 路径参数 | `id`：简历业务 ID |
| 身份输入 | `X-User-Id`，由服务层强制校验 |
| 响应 | `ApiResult<Map<String,Object>>`，包含 `id`、文件元数据、`resumeText`、`interviews`、`analyses` |
| 数据来源 | PostgreSQL：resumes、candidates、interview_sessions、resume_analyses；分析历史本接口直接读 PostgreSQL |
| Python / RabbitMQ | 无调用。页面轮询只会重新读取本接口，不能在查询时执行 Agent。|

### 1.3 前端访问入口

主要入口为 `frontend/src/pages/ResumeDetailPage.tsx:42-56` 的 `loadResumeDetail`。组件挂载后调用它；静默轮询函数 `loadResumeDetailSilent` 位于同文件 `33-40`，两者均调用 `historyApi.getResumeDetail(resumeId)`。应用壳也在 `frontend/src/App.tsx:111` 为当前选择简历调用同一 API。

## 2. 函数调用链

```text
ResumeDetailPage.loadResumeDetail 或 loadResumeDetailSilent
  -> historyApi.getResumeDetail
  -> request.get
  -> Axios 请求拦截器 -> currentUserId -> createClientId
  -> RequestIdFilter.doFilterInternal -> normalize
  -> SimpleRateLimitFilter.doFilterInternal -> JavaRedisStore.incrementInFixedWindow
     ->（Redis 不可用）ConcurrentHashMap 本机限流回退
  -> IdempotencyFilter.shouldNotFilter（GET，跳过）
  -> ResumeController.detail
  -> ResumeService.detail
     -> ResumeService.owned -> ResumeRepository.findById -> ResumeRepository.xml.findById
        -> UserIdentityResolver.require -> ResumeService.owns
           -> CandidateRepository.findById -> CandidateRepository.xml.findById
     -> UserIdentityResolver.require
     -> InterviewSessionRepository.findByUserIdOrderByCreatedAtDesc -> XML SQL
     -> ResumeService.toInterviewView -> parseFinalEvaluation
     -> ResumeAnalysisService.list -> ResumeAnalysisPersistenceService.list
        -> ResumeAnalysisRepository.findByResumeIdOrderByCreatedAtDesc -> XML SQL
        -> ResumeAnalysisService.toView -> stringList / mapList
  -> ApiResult.success -> Axios 响应拦截器 -> 前端 setResume / finally
```

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `ResumeDetailPage.loadResumeDetail`

**文件与行号：** `frontend/src/pages/ResumeDetailPage.tsx:42-52`。

1. 第 42 行以 `useCallback` 创建依赖于 `resumeId` 的异步加载函数，防止无关渲染改变函数引用。
2. 第 43 行把 `loading` 设为真。第 44 行进入 `try`。
3. 第 45 行调用 `historyApi.getResumeDetail(resumeId)`；第 46 行把成功返回的详情对象写入 `setResume`。
4. 第 47-48 行仅将请求失败写入浏览器控制台，不把旧详情替换为空对象。
5. 第 49-51 行的 `finally` 总会将 `loading` 复位为假；第 52 行结束回调。

#### 3.1.2 `ResumeDetailPage.loadResumeDetailSilent` 与挂载 Effect

**文件与行号：** `frontend/src/pages/ResumeDetailPage.tsx:33-40、54-56`。

1. 静默函数第 33 行声明异步回调；第 35 行发起同一详情请求；第 36 行仅更新 `resume`，不改变加载动画。
2. 第 37-39 行吞下前台不需打断用户的轮询错误并记录控制台；第 40 行结束。
3. 第 54 行注册 Effect；第 55 行调用 `loadResumeDetail`；第 56 行以函数引用作为依赖。由此首次进入详情页一定会读取后端。

#### 3.1.3 `historyApi.getResumeDetail`

**文件与行号：** `frontend/src/api/history.ts:84-87`。

1. 第 84 行定义 API 对象。第 86 行接收字符串或数字 ID，并通过模板字符串生成 `/api/resumes/${id}/detail`。
2. 同行调用 `request.get<ResumeDetail>`；泛型只约束前端数据形状，实际统一响应由拦截器解包。

#### 3.1.4 `request` 身份、GET 和响应处理

**文件与行号：** `frontend/src/api/request.ts:47-57、64-72、123-164`。

1. `createClientId` 第 47-49 行优先使用 `crypto.randomUUID`，旧环境使用时间和随机段回退。
2. `currentUserId` 第 52-57 行先读取 localStorage，缺失时调用生成器、写回并返回；它提供当前工程的临时用户边界，不是登录令牌。
3. 请求拦截器第 64-72 行确保 headers 存在，兼容两种 header 写法，然后写 `X-User-Id` 与新的 `X-Request-Id` 并返回配置。
4. `request.get` 第 162-164 行调用 Axios GET 并返回 `response.data`。
5. 成功拦截器第 123-135 行检查 Java `code`；200 时第 128 行取内层 data，非 200 时第 131-132 行抛出 `ApiRequestError`。错误拦截器第 136-154 行处理无响应、Blob 错误体和 HTTP 错误后拒绝 Promise。

### 3.2 Java Web 保护与 Controller 函数

#### 3.2.1 `RequestIdFilter.doFilterInternal` 与 `normalize`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/infrastructure/web/RequestIdFilter.java:23-41`。

1. 第 25 行读取 `X-Request-Id` 并调用 `normalize`；第 26 行保存 request attribute；第 27 行回写响应头；第 28 行写入 MDC。
2. 第 29-30 行把请求传给后续过滤器；第 31-33 行在 finally 删除 MDC。
3. `normalize` 第 36-41 行只保留长度不超过 128 的安全字符 ID，否则第 40 行生成 UUID；这样错误日志和响应能可靠关联。

#### 3.2.2 `SimpleRateLimitFilter.doFilterInternal` 与 `JavaRedisStore.incrementInFixedWindow`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/infrastructure/ratelimit/SimpleRateLimitFilter.java:48-82`，`java-backend/src/main/java/com/interviewguide/infrastructure/redis/JavaRedisStore.java:31-39`。

1. 第 50-52 行放行 health/actuator；第 54-55 行用 IP、URI 和当前分钟组成窗口。
2. 第 56-58 行尝试 Redis 原子计数。`incrementInFixedWindow` 第 32-35 行执行 INCR、首次设置 65 秒 TTL、返回计数；第 36-38 行在 Redis 异常时返回空 Optional。
3. 过滤器第 60-67 行命中 Redis 就用分布式计数，失败则用 `ConcurrentHashMap.compute` 与 `AtomicInteger` 做本机回退。
4. 第 69-79 行超限时返回带 requestId 的 429；第 81 行未超限时继续请求。

#### 3.2.3 `IdempotencyFilter.shouldNotFilter`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/infrastructure/idempotency/IdempotencyFilter.java:41-44`。

1. 第 41 行声明跳过判断。第 42-44 行只允许含幂等键的写方法进入幂等处理。
2. 当前 GET 请求被跳过，因此不会执行该过滤器的 `doFilterInternal`、不会写 Redis 幂等键；这是代码决定的分支。

#### 3.2.4 `ResumeController.detail` 与 `ApiResult.success`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:45-49`，`java-backend/src/main/java/com/interviewguide/common/web/dto/ApiResult.java:3-6`。

1. 第 45 行映射 `/{id}/detail`。第 46 行把路径段绑定为 `id`；第 47 行绑定可选用户头。
2. 第 48 行调用 `resumeService.detail`，将结果交给 `ApiResult.success`；第 49 行结束。
3. `success` 第 4 行接收泛型 data，第 5 行构造固定 `code=200` 和 `message=success`，第 6 行结束。

### 3.3 Java 详情装配、MyBatis 与 JSON 函数

#### 3.3.1 `ResumeService.detail`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:160-177`。

1. 第 160-161 行声明详情方法。第 162 行首先调用项目函数 `owned`，完成存在性和所有权检查。
2. 第 163 行创建可变 Map；第 164-169 行依次写简历 ID、文件名、大小、内容类型、上传时间和文本。
3. 第 170-174 行先以 `identity.require(userId)` 取得当前 owner，再查询该用户所有面试会话，按 `resumeId` 过滤，逐项调用 `toInterviewView`，最后收集为列表并写入 `interviews`。
4. 第 175 行调用 `analysisService.list(id)` 取得全部分析历史并写 `analyses`；第 176 行返回 Map；第 177 行结束。它不创建任务，也不调用 Python。

#### 3.3.2 `ResumeService.owned`、`owns` 与身份函数

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:276-288`，`java-backend/src/main/java/com/interviewguide/common/security/UserIdentityResolver.java:14-19`。

1. `owned` 第 277 行调用 `ResumeRepository.findById`；第 278 行在空 Optional 时抛出 `RESUME_NOT_FOUND`。
2. 第 279 行调用 `identity.require` 并把结果交给 `owns`；第 280 行在不属于当前用户时抛出 `RESUME_ACCESS_DENIED`；第 282 行返回可访问的实体。
3. `require` 第 15-17 行拒绝空头，第 18 行 `strip`，第 19 行返回 owner。
4. `owns` 第 286 行调用 `CandidateRepository.findById`，第 287 行仅在候选人存在且 `candidate.userId` 等于 owner 时返回真；第 288 行结束。
5. `ResumeRepository.findById` 在 `ResumeRepository.java:13`，其 XML 同名 `<select>` 按主键查询；`CandidateRepository.findById` 在 `CandidateRepository.java:11`，XML `CandidateRepository.xml:4` 按主键查询。两者都是 MyBatis 方法，没有 JPA 实体自动加载。

#### 3.3.3 面试查询、`toInterviewView` 与 `parseFinalEvaluation`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:290-308`，`java-backend/src/main/resources/mapper/interview/InterviewSessionRepository.xml:6`。

1. `InterviewSessionRepository.findByUserIdOrderByCreatedAtDesc` 的 XML 第 6 行以 user_id 查询并按 created_at 倒序返回会话；详情函数第 170-172 行只留下当前 resumeId 的会话。
2. `toInterviewView` 第 290 行声明转换；第 291-298 行按 `InterviewView` 构造器顺序复制会话 ID、用户/候选人/简历/JD、方向、难度、题数、状态、Agent 版本、当前题与阶段、提问统计、创建和更新时间；第 297 行调用 `parseFinalEvaluation`。
3. `parseFinalEvaluation` 第 301 行声明函数；第 302 行将空 JSON 规范为空 Map；第 303-304 行用 ObjectMapper 反序列化；第 305-306 行捕获任何格式错误并回退空 Map；第 307-308 行结束。历史异常 JSON 不会阻断详情读取。

#### 3.3.4 `ResumeAnalysisService.list`、持久化列表与 `toView`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:76-78、91-97、127-137`，`ResumeAnalysisPersistenceService.java:64-66`。

1. `list` 第 76 行声明方法；第 77 行调用 `persistence.list(resumeId)`，对每个实体调用 `toView` 并收集为列表；第 78 行结束。本接口需要完整历史，所以不会走 Redis 最新快照。
2. `ResumeAnalysisPersistenceService.list` 第 64 行声明方法；第 65 行调用 Mapper `findByResumeIdOrderByCreatedAtDesc`；第 66 行结束。Mapper 接口在 `ResumeAnalysisRepository.java:20`，对应 XML 以 resume_id 查询并按创建时间倒序。
3. `toView` 第 91 行声明转换；第 92-94 行复制 ID、状态、六项分数、摘要和更新时间；第 95 行两次调用 `stringList` 恢复优点与建议；第 96 行调用 `mapList` 恢复问题列表并复制错误；第 97 行结束。
4. `stringList` 第 127-131 行对空值返回空列表，否则反序列化 JSON，解析异常也回退空列表。`mapList` 第 133-137 行以相同规则将问题 JSON 恢复为 `List<Map<String,Object>>`。这两个函数只进行展示转换，不改变数据库。

## 4. 主流构建分析

当前详情接口采用服务层 Map 聚合：优点是实现直观、权限校验在读取简历后立即执行、单个面试或分析记录格式异常可回退为空集合。缺点是先查询该用户全部面试会话再在 Java 内存筛选，并且分析历史无分页；用户的长期历史增加时，响应体和查询量会同时膨胀。

主流实现会定义强类型 `ResumeDetailResponse`，并让 MyBatis 分三条受索引支持的查询分别按 `resume_id` 获取简历、面试和分页分析记录，或使用 MyBatis resultMap/批量查询组装。优点是字段契约明确、SQL 条件下推、便于对分析历史做 cursor 分页；缺点是 DTO、Mapper 和前端分页状态需要更多代码。

本项目适合采用该演进方式。实施时应：新增 `findByResumeIdAndUserId`，把所有权校验下推为 `resumes JOIN candidates`；新增 `findByResumeIdOrderByCreatedAtDesc(resumeId, limit, cursor)`；将 `interview_sessions` 按 `resume_id` 查询；以 DTO 替代 `Map`；为 `resume_analyses(resume_id, created_at)` 和 `interview_sessions(resume_id, created_at)` 建索引。仍应保持查询接口不调用 Python，避免详情页轮询放大 Agent 流量。
