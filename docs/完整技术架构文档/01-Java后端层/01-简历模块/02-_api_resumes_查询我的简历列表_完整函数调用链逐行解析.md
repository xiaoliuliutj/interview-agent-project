# GET /api/resumes：查询当前用户简历列表完整函数调用链逐行解析

> 本文以当前工作区代码为准。该接口是查询接口：它不会发布 RabbitMQ 消息，也不会调用 Python 服务；调用链在 Java 读取 PostgreSQL 与 Java 专属 Redis 缓存后结束。文中只把项目自行定义的函数列入函数链，Spring、Axios、MyBatis、Redis 客户端的库内实现只说明作用而不虚构为项目函数。

## 1. 接口定义

### 1.1 功能与作用

`GET /api/resumes` 返回当前 `X-User-Id` 所属候选人的全部简历版本。每项包含文件名、文件大小、解析出的简历文本、上传时间、关联面试场次，以及最新简历分析的分数、状态、时间和错误信息。列表页用它展示历史简历；面试配置 Hook 也用它加载可选简历。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| HTTP 方法与路径 | `GET /api/resumes` |
| Controller | `ResumeController.list`，`java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:39-43` |
| 身份输入 | `X-User-Id`；Controller 允许头缺失，但服务层 `UserIdentityResolver.require` 会拒绝空值 |
| 追踪输入与输出 | 前端请求拦截器写入 `X-Request-Id`；`RequestIdFilter` 在响应头回传合法或新生成的 ID |
| 成功响应 | `ApiResult<List<Map<String,Object>>>`，前端响应拦截器解包为数组 |
| 数据来源 | PostgreSQL 的 `resumes`、`candidates`、`interview_sessions`、`resume_analyses`；分析最新状态优先读取 Java Redis |
| Python / RabbitMQ | 本接口不触发它们。若分析仍为 `PENDING` 或 `PROCESSING`，前端每 5 秒轮询本接口读取已有任务状态。|

### 1.3 前端访问入口

历史页入口是 `frontend/src/pages/HistoryPage.tsx:13-15`：组件挂载时调用 `load`，当任一项仍在分析时由定时器重复调用。面试配置的第二个入口是 `frontend/src/hooks/useInterviewConfig.ts:27-33`，它在 Hook 自动加载时执行 `loadResumes`。两个入口最终都调用 `historyApi.getResumes`。

## 2. 函数调用链

```text
HistoryPage.load（或 useInterviewConfig.loadResumes）
  -> historyApi.getResumes
  -> request.get
  -> Axios 请求拦截器 -> currentUserId / createClientId
  -> RequestIdFilter.doFilterInternal -> normalize
  -> SimpleRateLimitFilter.doFilterInternal -> JavaRedisStore.incrementInFixedWindow
     ->（Redis 不可用）ConcurrentHashMap 本机窗口回退
  -> IdempotencyFilter.shouldNotFilter（GET，不进入 doFilterInternal）
  -> ResumeController.list
  -> ResumeService.list
     -> UserIdentityResolver.require
     -> ResumeRepository.findAll -> ResumeRepository.xml.findAll SQL
     -> ResumeService.owns -> CandidateRepository.findById -> CandidateRepository.xml.findById SQL
     -> ResumeAnalysisService.latest
        -> JavaTaskStatusCache.latestResumeAnalysis -> JavaRedisStore.getJson
        ->（未命中或 Redis 故障）ResumeAnalysisPersistenceService.latest
           -> ResumeAnalysisRepository.findFirstByResumeIdOrderByCreatedAtDesc -> XML SQL
           -> ResumeAnalysisService.toView -> stringList / mapList
        ->（缓存命中）ResumeAnalysisService.toCachedView -> number / integerOrNull /
           string / nullableString / parseInstant / stringList / mapList
     -> InterviewSessionRepository.findByUserIdOrderByCreatedAtDesc -> XML SQL
  -> ApiResult.success
  -> Axios 响应拦截器
  -> HistoryPage.load 成功、失败与 finally 分支（或 Hook 的 setResumes）
```

链路没有 Python 调用是代码事实，而不是遗漏：`ResumeService.list` 中没有 `PythonAgentClient`、`RabbitTemplate` 或任何 Python URL 调用。Python 只会由上传、重新分析、面试等写操作的异步任务调用。

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `HistoryPage.load`

**文件与行号：** `frontend/src/pages/HistoryPage.tsx:13`。

1. 第 13 行用 `useCallback` 创建异步 `load`，使两个 `useEffect` 可以稳定引用它。
2. 同行首先调用 `setLoading(true)`，页面随即显示加载状态。
3. 同行的 `try` 中 `await historyApi.getResumes()` 发起请求；成功后把已解包的数组写入 `setResumes`，再用 `setError('')` 清除旧错误。
4. 同行的 `catch` 捕获网络、限流和业务错误；若对象是 `Error` 则展示其 `message`，否则写入固定的“加载简历失败”兜底文本。
5. 同行的 `finally` 无论请求成败均调用 `setLoading(false)`，避免按钮和页面永久停留在加载态。

#### 3.1.2 `HistoryPage` 的两个轮询 Effect

**文件与行号：** `frontend/src/pages/HistoryPage.tsx:14-15`。

1. 第 14 行的 `useEffect` 在组件首次挂载及 `load` 变化后执行 `void load()`；`void` 表明 React 不等待 Promise。
2. 第 15 行先检查数组中是否存在 `PENDING` 或 `PROCESSING` 项；不存在即返回，不创建定时器。
3. 同行在存在未完成任务时调用 `window.setInterval`，每 5000 毫秒触发一次 `load`。
4. 同行返回清理函数 `clearInterval(timer)`；组件卸载或依赖改变时它停止旧定时器，避免重复轮询。

#### 3.1.3 `useInterviewConfig.loadResumes`

**文件与行号：** `frontend/src/hooks/useInterviewConfig.ts:27`。

1. 第 27 行声明异步函数并调用 `historyApi.getResumes()`。
2. 同行把返回列表交给 `setResumes`，供面试配置下拉框使用。
3. 同行返回同一份列表，使调用者也可继续使用数据；它不修改后端状态。

#### 3.1.4 `historyApi.getResumes`

**文件与行号：** `frontend/src/api/history.ts:84-86`。

1. 第 84 行创建 `historyApi` 对象。
2. 第 85 行定义无参数箭头函数，调用 `request.get<ResumeListItem[]>('/api/resumes')`。
3. 泛型 `ResumeListItem[]` 仅约束 TypeScript 编译期数据形状；实际 Java 包装层由响应拦截器在返回前去除。

#### 3.1.5 `request` 的身份、GET 与响应拦截函数

**文件与行号：** `frontend/src/api/request.ts:47-57、64-72、123-154、162-164`。

1. `createClientId` 第 47 行声明生成器；第 48 行优先用浏览器 `crypto.randomUUID`；第 49 行为旧环境拼接前缀、时间和随机十六进制值作为回退。
2. `currentUserId` 第 52 行声明读取函数；第 53 行读取 localStorage；第 54 行有旧值则复用；第 55 行无值则调用 `createClientId`；第 56 行写回 localStorage；第 57 行返回该临时用户标识。
3. 请求拦截器第 64 行注册；第 65 行确保 `headers` 存在；第 66-68 行兼容 AxiosHeaders 的 `set` 与普通对象赋值；第 70 行写 `X-User-Id`；第 71 行为每次请求新建 `X-Request-Id`；第 72 行把配置交给 Axios。
4. `request.get` 第 162 行声明通用 GET 包装；第 163 行调用 Axios `get`；第 164 行返回已经被成功响应拦截器处理后的 `response.data`。
5. 成功响应拦截器第 123-135 行读取 Java `ApiResult`；第 126 行确认存在 `code`；第 127 行识别 `200`；第 128 行将外层 `data` 解包；第 131-132 行把非成功业务码转换成 `ApiRequestError`；第 134 行让非项目响应原样通过。
6. 错误拦截器第 136-154 行先识别 Axios 错误；第 138 行处理无响应网络错误；第 140 行尝试解析 Blob 错误体；第 141-153 行从项目错误体或 HTTP 状态构造安全的前端错误对象；最后重新拒绝 Promise。

### 3.2 Java Web 入口与保护函数

#### 3.2.1 `RequestIdFilter.doFilterInternal` 与 `normalize`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/infrastructure/web/RequestIdFilter.java:23-41`。

1. `doFilterInternal` 第 23-24 行声明 Servlet 过滤函数及可抛出的容器异常。
2. 第 25 行读取请求头并调用项目函数 `normalize`；第 26 行把结果存为 request attribute；第 27 行写入响应头；第 28 行写入 MDC 日志上下文。
3. 第 29-30 行调用后续过滤器和 Controller；第 31-33 行在 `finally` 中移除 MDC，避免线程池复用时串号。
4. `normalize` 第 36 行声明私有函数；第 37 行只接受长度不超过 128、字符集匹配的值；第 38 行返回合格值；第 40 行对缺失或不合格值创建 UUID；第 41 行结束。

#### 3.2.2 `SimpleRateLimitFilter.doFilterInternal` 与 `JavaRedisStore.incrementInFixedWindow`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/infrastructure/ratelimit/SimpleRateLimitFilter.java:48-82`，`java-backend/src/main/java/com/interviewguide/infrastructure/redis/JavaRedisStore.java:31-39`。

1. 过滤函数第 50-52 行放行健康检查；第 54 行按远端 IP 与 URI 构造限流维度；第 55 行计算分钟窗口。
2. 第 56-58 行在 Redis 已注入时调用 `incrementInFixedWindow`，键带 `java:rate-limit:` 前缀且 TTL 为 65 秒。
3. `incrementInFixedWindow` 第 31 行声明函数；第 32 行进入异常保护；第 33 行执行原子 `INCR`；第 34 行仅首次计数设置过期；第 35 行返回计数；第 36-38 行在 Redis 异常时记录日志并返回空 Optional。
4. 过滤器第 60-61 行使用 Redis 计数；第 62-67 行在 Redis 未配置或失败时以 `ConcurrentHashMap.compute` 建立本机分钟窗口并递增。
5. 第 69-79 行在超限时返回 429、`Retry-After` 和带 requestId 的统一错误 JSON；第 81 行在未超限时继续链路。

#### 3.2.3 `IdempotencyFilter.shouldNotFilter`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/infrastructure/idempotency/IdempotencyFilter.java:41-44`。

1. 第 41 行声明是否跳过幂等过滤的覆盖方法。
2. 第 42-44 行规定只有带 `X-Idempotency-Key` 的 POST、PUT、PATCH、DELETE 才进入 `doFilterInternal`。
3. 本请求是 GET，因此返回跳过结果，不调用 `JavaRedisStore.acquire`、不占用幂等键，也不产生 409；这也是该链路不包含 `doFilterInternal` 的原因。

#### 3.2.4 `ResumeController.list` 与 `ApiResult.success`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:39-43`，`java-backend/src/main/java/com/interviewguide/common/web/dto/ApiResult.java:3-6`。

1. Controller 第 39 行将无子路径 GET 与类级 `/api/resumes` 组合为接口路径。
2. 第 40-41 行将可选的 `X-User-Id` 注入参数；第 42 行调用 `resumeService.list(userId)` 并马上调用 `ApiResult.success` 包装返回；第 43 行结束。
3. `ApiResult.success` 第 4 行接收泛型数据；第 5 行构造 `code=200`、`message=success` 和 data 的 record；第 6 行结束。

### 3.3 Java 查询、缓存与 MyBatis 函数

#### 3.3.1 `ResumeService.list`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:140-159`。

1. 第 140-141 行声明列表函数与用户参数。第 142 行调用 `identity.require`，空或空白身份立即转为业务错误。
2. 第 143 行调用 `ResumeRepository.findAll` 取得所有简历，并在内存流中用项目函数 `owns` 过滤出当前用户的记录；这是当前实现的权限边界。
3. 同行的 `map` Lambda 对每份简历执行第 144 行 `analysisService.latest`，获取最近分析状态。
4. 第 145 行创建响应 Map；第 146-149 行依次写 ID、原文件名、大小和文本。
5. 第 150-151 行查询当前用户的面试会话，再按 `resumeId` 过滤并 `count`，写入 `interviewCount`。
6. 第 152 行写上传时间；第 153-156 行把 latest 为空时的字段写为 `null`，非空时写总体分、分析时间、状态和错误。
7. 第 157 行返回单个 Map；第 158 行执行 `toList` 得到最终列表；第 159 行结束。函数只读数据，不投递任务。

#### 3.3.2 `UserIdentityResolver.require`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/common/security/UserIdentityResolver.java:14-19`。

1. 第 14 行声明身份规范函数。第 15-17 行拒绝 `null` 或空白头并抛出业务异常。
2. 第 18 行调用 `strip` 去掉首尾空白；第 19 行返回规范化 owner。它由 `ResumeService.list` 在查询前调用。

#### 3.3.3 `ResumeRepository.findAll` 与 `ResumeService.owns`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/mapper/ResumeRepository.java:14`，`java-backend/src/main/resources/mapper/resume/ResumeRepository.xml:5`，`java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:285-288`。

1. Mapper 接口第 14 行声明 `findAll`；MyBatis 将调用绑定到 XML。
2. XML 第 5 行执行 `SELECT * FROM resumes` 并映射为 `ResumeEntity` 列表；SQL 本身不带用户过滤条件。
3. `owns` 第 285 行声明权限判断。第 286 行按简历的 `candidateId` 调用 `CandidateRepository.findById`；第 287 行存在候选人时比较其 `userId` 与当前 owner，不存在则返回 `false`；第 288 行结束。
4. `CandidateRepository.findById` 位于 `java-backend/src/main/java/com/interviewguide/resume/mapper/CandidateRepository.java:11`，对应 `CandidateRepository.xml:4` 的按主键查询 SQL。它是 MyBatis 接口方法，不存在 JPA Repository 或隐式实体加载。

#### 3.3.4 `ResumeAnalysisService.latest`、Redis 读取与 PostgreSQL 回退

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:67-74`。

1. 第 67 行声明最新分析查询。第 70 行调用 `taskCache.latestResumeAnalysis(resumeId)`。
2. `JavaTaskStatusCache.latestResumeAnalysis` 位于 `java-backend/src/main/java/com/interviewguide/infrastructure/redis/JavaTaskStatusCache.java`，它按 `java:task:resume-analysis:latest:<resumeId>` 读取 JSON，并委托 `JavaRedisStore.getJson`。`getJson` 捕获 Redis 或 JSON 异常并返回空 Optional，所以缓存不会成为查询失败原因。
3. 第 71 行缓存命中时调用 `toCachedView` 并立即返回。`toCachedView` 在同文件第 99-109 行：第 100-108 行按键读取快照并调用 `number`、`integerOrNull`、`string`、`nullableString`、`parseInstant`、`stringList`、`mapList` 构造不可变视图；第 111-125 行分别将错误类型归一为 0、`null`、`PENDING`、`null` 或当前时间，避免陈旧缓存类型破坏列表。
4. 第 72 行缓存未命中、过期或 Redis 故障时调用 `persistence.latest`；第 73 行无记录返回 `null`，有记录则调用 `toView`；第 74 行结束。
5. `ResumeAnalysisPersistenceService.latest` 位于 `ResumeAnalysisPersistenceService.java:60-62`：第 61 行调用 Mapper 的“按创建时间倒序取一条”查询，空 Optional 转为 `null`。Mapper 声明在 `ResumeAnalysisRepository.java:21`，XML 在 `ResumeAnalysisRepository.xml:6` 使用 `ORDER BY created_at DESC LIMIT 1`。
6. `toView` 位于 `ResumeAnalysisService.java:91-97`：第 92-96 行复制实体字段并调用 `stringList`、`mapList` 反序列化 JSON；`stringList` 第 127-131 行和 `mapList` 第 133-137 行均对空值或 JSON 错误返回空列表，而不使整个列表请求失败。

#### 3.3.5 `InterviewSessionRepository.findByUserIdOrderByCreatedAtDesc`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/interview/mapper/InterviewSessionRepository.java` 的同名方法，`java-backend/src/main/resources/mapper/interview/InterviewSessionRepository.xml:6`。

1. `ResumeService.list` 第 150 行调用该 Mapper，输入是已经通过 `require` 得到的 owner。
2. XML 第 6 行以 `WHERE user_id=#{userId} ORDER BY created_at DESC` 查询会话；返回的实体随后仅由第 151 行按当前简历 ID 过滤和计数。
3. 该查询不会调用面试 Agent 或 Python；它仅提供列表项的历史面试次数。

## 4. 主流构建分析

当前实现采用“Java 聚合查询 + Redis 任务快照优先 + PostgreSQL 回退”的方式。优点是分析状态轮询延迟低、Redis 故障时仍可从数据库得到正确结果、Python 服务没有被只读列表流量放大。缺点是 `ResumeRepository.findAll` 后再用 `owns` 做内存过滤，在简历量增长时会读取不属于当前用户的数据；同时每份简历都分别查询一次分析状态与会话，存在 N+1 查询风险。

主流改进方式是把权限过滤和统计下推至 MyBatis 的单条分页聚合 SQL，例如新增 `findResumeListByUserId(userId, offset, limit)`，在 SQL 中连接 `candidates`，用窗口函数或子查询取最新 `resume_analyses`，并用 `COUNT(interview_sessions.id)` 聚合面试数。优点是权限边界和分页在数据库完成、网络传输与 N+1 显著减少；缺点是 SQL 较长、结果映射更复杂，缓存更新还需继续以数据库为最终事实来源。

本项目适合在简历和面试记录数量增加前采用该方式。实现时：第一，在 `ResumeRepository.java` 增加带 `@Param` 的分页查询方法；第二，在 `ResumeRepository.xml` 新增以 `candidate.user_id=#{userId}` 为条件的聚合 `<select>`；第三，创建专用 `ResumeListRow` DTO，避免用弱类型 `Map`；第四，保持 `ResumeAnalysisService.latest` 的 Redis 优先逻辑，或把最新状态一起缓存为短 TTL 批量快照；第五，为数据库新增 `resumes(candidate_id)`、`resume_analyses(resume_id, created_at)`、`interview_sessions(user_id, resume_id)` 索引并用集成测试核对用户隔离。
