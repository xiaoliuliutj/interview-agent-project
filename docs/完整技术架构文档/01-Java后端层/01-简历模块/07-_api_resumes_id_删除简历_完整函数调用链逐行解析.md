# DELETE /api/resumes/{id}：删除简历及关联数据的完整函数调用链

> 本文对应接口汇总中的第 7 个 Java 接口。所有函数、文件和行号均以当前工作区源码为准。该接口没有 Java 调用 Python Agent 的分支：它会先把尚未消费的简历分析任务标记为取消，再删除其数据库记录、关联面试和原始文件，最终删除简历记录。

## 1. 接口定义

### 1.1 功能与作用

接口删除当前用户拥有的一个简历版本。删除范围不仅是 `resumes` 表中的 ResumeEntity：还包括该简历的分析记录、引用该简历的面试会话及其所有 turn，以及文件存储目录中的原始上传文件。若被删除的是候选人的 `currentResumeId`，代码会从剩余简历中选版本号最大的一个作为新的当前简历；没有剩余版本时写入 `null`。

删除前会把 PENDING/PROCESSING 分析记录取消。这样即使 RabbitMQ 中已经存在旧的分析消息，ResumeAnalysisWorker 未来收到消息也会因任务已取消而退出，不会再调用 Python 或把分析结果写回一个已经删除的简历。

### 1.2 基本信息

| 项目 | 内容 |
| --- | --- |
| HTTP 方法 | DELETE |
| 完整路径 | `/api/resumes/{id}` |
| 类级路径 | `/api/resumes` |
| Controller 入口 | `ResumeController.delete` |
| 路径参数 | `id`：简历主键 |
| 身份头 | `X-User-Id`（Controller 可缺省；Service 会拒绝空值） |
| 成功响应 | `ApiResult<Void>`，即 `{ code: 200, message: "success", data: null }` |
| 修改对象 | 分析任务、会话及回合、候选人当前简历指针、原始文件、简历记录 |
| Python 调用 | 无；本接口只取消旧任务，不会发起 `/v1/**` HTTP 请求 |

### 1.3 前端访问入口

简历管理页的删除确认框把“确认”按钮绑定到 `HistoryPage.remove`。该函数以当前 `pendingDelete.id` 调用 `historyApi.deleteResume`；API 封装再调用通用 `request.delete`。Axios 请求拦截器补充 `X-User-Id` 和 `X-Request-Id` 后，浏览器发出 DELETE 请求。

## 2. 函数调用链

~~~text
DeleteConfirmDialog.onConfirm
 -> HistoryPage.remove
 -> historyApi.deleteResume
 -> request.delete
 -> Axios 请求拦截器
    -> currentUserId
       -> createClientId（仅 localStorage 尚无用户 ID 时）
    -> createClientId("web")
 -> RequestIdFilter.doFilterInternal
    -> RequestIdFilter.normalize
 -> SimpleRateLimitFilter.doFilterInternal
 -> ResumeController.delete
 -> ResumeService.delete
    -> ResumeService.owned
       -> ResumeRepository.findById
       -> UserIdentityResolver.require
       -> ResumeService.owns
          -> ResumeEntity.getCandidateId
          -> CandidateRepository.findById
          -> CandidateEntity.getUserId
    -> CandidateRepository.findById
       -> ResumeEntity.getCandidateId
    -> ResumeAnalysisService.cancelActiveForResumeIds
       -> ResumeAnalysisPersistenceService.cancelActiveForResumeIds
          -> ResumeAnalysisRepository.findByResumeIdInAndStatusIn
          -> ResumeAnalysisEntity.cancel（零到多条）
    -> ResumeAnalysisService.deleteByResumeId
       -> ResumeAnalysisPersistenceService.deleteByResumeId
          -> ResumeAnalysisRepository.deleteByResumeId
    -> UserIdentityResolver.require
    -> InterviewSessionRepository.findByUserIdOrderByCreatedAtDesc
       -> InterviewSessionEntity.getResumeId
       -> InterviewSessionEntity.getId（匹配的每个会话）
       -> InterviewTurnRepository.deleteBySessionId
       -> InterviewSessionRepository.delete
    -> CandidateEntity.getCurrentResumeId
       ->（若删除当前版本）CandidateEntity.getId
          -> ResumeRepository.findByCandidateId
          -> ResumeEntity.getId / ResumeEntity.getVersion（每个剩余版本）
          -> CandidateEntity.setCurrentResumeId
          -> CandidateRepository.save
    -> ResumeEntity.getStorageKey
    -> ResumeFileStorageService.delete
    -> ResumeRepository.delete
 -> ApiResult.success(null)
 -> Axios 响应拦截器（成功解包或失败分支）
 -> HistoryPage.remove：清空 pendingDelete 并调用 load 刷新列表
~~~

`ResumeAnalysisWorker.process`、`HttpPythonAgentClient`、RabbitTemplate、Python FastAPI 路由均不在这次 DELETE 的主动调用链内。取消操作只改变分析任务的数据库状态；它不向 RabbitMQ 发送新消息，也不产生 Python 请求。

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `HistoryPage.remove`

文件：`frontend/src/pages/HistoryPage.tsx:16`。

该源文件将该函数压缩成一行，按 JavaScript 的实际求值顺序拆开说明如下。

1. `const remove = async () => {` 定义无参异步回调；删除确认组件的 `onConfirm` 通过 `() => void remove()` 调用它。
2. `if (!pendingDelete) return;` 检查 React 状态。用户取消、状态已被清空或重复触发时直接结束，不会向后端发 DELETE。
3. `setDeleting(true);` 把删除中的状态传给确认框，通常用于禁用重复确认。
4. `try { await historyApi.deleteResume(pendingDelete.id);` 调用 API 层，并等待 HTTP 成功响应。`pendingDelete.id` 是用户在列表中选择的简历 ID。
5. `setPendingDelete(null);` 仅在服务端 DELETE 成功后关闭确认框并清除待删除对象。
6. `await load();` 调用同组件第 13 行的列表加载函数，以服务端当前数据刷新 UI；它不是 DELETE HTTP 链本身，但属于成功后的实际前端函数调用。
7. `} finally { setDeleting(false); }` 无论请求、刷新成功还是失败，都会恢复按钮状态；异常没有在本函数捕获，会继续交给 React 调用环境。

#### 3.1.2 `historyApi.deleteResume`

文件：`frontend/src/api/history.ts:100`。

1. 第 100 行定义箭头函数，参数 `id` 接受 string 或 number。
2. 模板字符串把参数插入 `/api/resumes/${id}`，形成当前接口的 URL。
3. 调用泛型函数 `request.delete<void>`；`void` 表示成功响应解包后的 data 预期为 null/undefined。
4. 箭头函数直接返回 Promise，没有在前端再转换响应内容。

#### 3.1.3 `request.delete`

文件：`frontend/src/api/request.ts:170-172`。

1. 第 170 行声明泛型方法，接收 URL 和可选 Axios 配置，返回 `Promise<T>`。
2. 第 171 行调用共享 `instance.delete(url, config)`。发送前会经过同文件已注册的请求拦截器，返回后会经过响应拦截器。
3. 同一行的 `.then(response => response.data)` 取出拦截器处理后的 data；第 172 行结束。

#### 3.1.4 `currentUserId` 与 `createClientId`

文件：`frontend/src/api/request.ts:47-58`。

1. `createClientId` 第 47 行定义带默认前缀 `anonymous` 的 ID 函数。
2. 第 48 行优先调用浏览器的 `crypto.randomUUID()`；可用时直接返回标准 UUID。
3. 第 49 行是兼容分支：用前缀、毫秒时间和两段随机十六进制字符串拼接一个客户端 ID；第 50 行结束。
4. `currentUserId` 第 52 行定义读取函数；第 53 行从 localStorage 读取固定键 `interview-agent-user-id`。
5. 第 54 行对读取到的非空白值直接返回，保证同一浏览器持续使用同一用户标识。
6. 第 55 行在首次访问时调用 `createClientId()`；第 56 行写回 localStorage；第 57 行返回新值；第 58 行结束。

#### 3.1.5 Axios 请求拦截器与其内部 `setHeader`

文件：`frontend/src/api/request.ts:64-73`。

1. 第 64 行向共享 Axios instance 注册请求拦截函数，所有 `request.delete` 请求都经过它。
2. 第 65 行确保 `config.headers` 存在；否则初始化为空对象。
3. 第 66 行定义内部项目函数 `setHeader(name,value)`，统一兼容 AxiosHeaders 和普通对象两种 header 表示。
4. 第 67 行若 headers 有 `.set` 方法则调用它；第 68 行否则按对象键赋值；第 69 行结束内部函数。
5. 第 70 行调用 `setHeader('X-User-Id', currentUserId())`，写入稳定用户 ID。
6. 第 71 行调用 `createClientId('web')` 并写入每次请求独立的 `X-Request-Id`。
7. 第 72 行返回修改后的 config；第 73 行结束。后端的 `UserIdentityResolver.require` 依赖第 70 行的头。

#### 3.1.6 Axios 成功响应拦截器

文件：`frontend/src/api/request.ts:123-135`。

1. 第 123 行注册成功与失败两个回调；本节先解释成功回调。
2. 第 124-125 行取得 `response.data` 并按项目 `Result` 结构看待。
3. 第 126 行仅在 data 是对象且含 `code` 键时采用 ApiResult 协议；普通二进制响应不会被误解包。
4. 第 127 行判断 code 是数值 200 或字符串 `'200'`；第 128 行把响应 data 替换成其中的业务 data（本接口为 null）；第 129 行返回 response。
5. 第 131-132 行对非 200 的 JSON 响应调用 `parseApiError`；无法解析时构造 `ApiRequestError` 并拒绝 Promise。
6. 第 134 行对非 ApiResult 原样返回响应；第 135 行结束。

#### 3.1.7 Axios 失败响应拦截器及错误辅助函数

文件：`frontend/src/api/request.ts:75-121、136-155`。

1. `isRecord` 第 75-77 行只认定非 null、object 且非数组的值为可按键读取的错误对象。
2. `stringValue` 第 79-81 行只返回非空白 string，其他类型返回 undefined。
3. `parseApiError` 第 83 行接收响应体、状态和 requestId；第 84 行以 `isRecord` 拒绝非对象；第 85-89 行读取嵌套 error 或外层 code/message；第 90-98 行构造含重试性、HTTP 状态和关联 ID 的 `ApiRequestError`；第 99 行返回。
4. `decodeErrorData` 第 101-108 行只对 JSON Blob 执行 `text()` 和 `JSON.parse`，解析失败保留原 Blob。
5. `transportError` 第 110-121 行按超时或无连接构造可重试网络错误；本 DELETE URL 不含 `/upload`，故使用通用请求文案。
6. 失败拦截器第 136 行接收未知异常；第 137 行对非 AxiosError 原样拒绝；第 138 行对无 response 调 `transportError`。
7. 第 139-142 行读取响应、await `decodeErrorData`、调用 `parseApiError`；解析成功则拒绝该项目异常。
8. 第 144-153 行在无法解析时按 5xx/非 5xx 构造 `SERVER_RESPONSE_INVALID` 或 `HTTP_REQUEST_FAILED`；第 154-155 行结束。

### 3.2 Java Web 入口函数

#### 3.2.1 `RequestIdFilter.doFilterInternal`

文件：`java-backend/src/main/java/com/interviewguide/infrastructure/web/RequestIdFilter.java:23-34`。

1. 第 23-24 行覆盖 Spring 的单次请求过滤方法，接收 request、response 和后续 FilterChain。
2. 第 25 行读取 `X-Request-Id`，调用同文件 `normalize` 取得安全 requestId。
3. 第 26 行把值写入 request attribute；第 27 行写回响应头；第 28 行放入日志 MDC。
4. 第 29 行开始 try；第 30 行把请求交给下一过滤器。
5. 第 31-33 行 finally 无条件移除 MDC 中的 requestId，避免线程池复用时串号；第 34 行结束。

#### 3.2.2 `RequestIdFilter.normalize`

文件：`java-backend/src/main/java/com/interviewguide/infrastructure/web/RequestIdFilter.java:36-41`。

1. 第 36 行定义静态私有函数。
2. 第 37 行同时要求值非 null、长度最多 128、字符只符合 `[A-Za-z0-9._:-]+`。
3. 第 38 行返回合法的客户端值。
4. 第 40 行对缺失或非法值生成 UUID；第 41 行结束。

#### 3.2.3 `SimpleRateLimitFilter.doFilterInternal`

文件：`java-backend/src/main/java/com/interviewguide/infrastructure/ratelimit/SimpleRateLimitFilter.java:38-61`。

1. 第 38-39 行声明过滤入口和参数。
2. 第 40 行识别 `/health` 与 `/actuator`；DELETE 简历路径不匹配，因此不会在第 41-42 行提前放行。
3. 第 44 行用远端 IP 和 URI 构造限流 key；第 45 行算当前 epoch minute。
4. 第 46-47 行以 ConcurrentHashMap.compute 创建当前分钟 Window，或者复用同分钟窗口。
5. 第 48 行原子递增计数并与 limit 比较。
6. 超限时第 49-57 行设置 429、Retry-After、JSON 内容类型，取 requestId，构造 `ApiErrorDetail` 并用 ObjectMapper 写 `ApiErrorResponse`；第 58 行 return，不进入 Controller。
7. 未超限时第 60 行调用 filterChain；第 61 行结束。

#### 3.2.4 `ResumeController.delete`

文件：`java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:69-74`。

1. 第 69 行的 `@DeleteMapping("/{id}")` 与类注解 `/api/resumes` 拼成完整 DELETE 路径。
2. 第 70 行声明 `ApiResult<Void>` 返回类型并将路径变量绑定为 `id`。
3. 第 71 行读取可选的 `X-User-Id`，同时声明 Service 的 IOException 可向异常处理器传播。
4. 第 72 行调用 `resumeService.delete(id,userId)`；只有该调用成功，才继续返回成功响应。
5. 第 73 行调用 `ApiResult.success(null)`；第 74 行结束。`success` 是 record 工厂，构造 code=200、message=success、data=null。

### 3.3 删除业务函数

#### 3.3.1 `ResumeService.delete`

文件：`java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:247-274`。

1. 第 247-248 行声明 `void delete(id,userId)`，允许文件删除产生 IOException。
2. 第 249 行调用 `owned`，先完成存在性和归属校验，得到 ResumeEntity。
3. 第 250-251 行按 `resume.getCandidateId()` 查候选人；缺失时抛 `CANDIDATE_NOT_FOUND`，后续不会删除。
4. 第 252-253 行注释描述并发目的；第 254 行调用 `analysisService.cancelActiveForResumeIds(List.of(id))`，先标记活动分析取消。
5. 第 255 行调用 `analysisService.deleteByResumeId(id)`，删除同一 resume 的分析记录。
6. 第 256 行再次 `identity.require(userId)` 后查询此用户的全部面试会话；第 257 行 stream filter 仅保留 `session.getResumeId()` 等于被删 id 的会话。
7. 第 258 行对每个匹配会话执行 lambda；第 259 行先删除该会话的回合；第 260 行再删除会话自身；第 261 行结束 lambda。顺序避免留下引用会话的 turn。
8. 第 262 行判断删除对象是否是候选人当前简历。
9. 第 263 行读取该候选人的所有简历；第 264 行排除当前删除 id；第 265 行以 `ResumeEntity.getVersion` 取版本最大值；第 266 行映射为该对象的 `getId`；第 267 行无剩余版本则为 null。
10. 第 268 行把替代 ID 写入 CandidateEntity；第 269 行 `candidateRepository.save` 持久化指针更新；第 270 行结束条件分支。
11. 第 271 行读取 `resume.getStorageKey()` 并调用 `fileStorage.delete` 删除原始文件。
12. 第 272 行执行 `resumeRepository.delete(resume)` 删除简历记录；第 273 行显式 `return`；第 274 行结束。源码没有 `@Transactional`，故文件系统和多个 Repository 操作之间不具备跨资源原子事务。

#### 3.3.2 `ResumeService.owned`、`owns` 与 `UserIdentityResolver.require`

文件：`java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:276-288`；`java-backend/src/main/java/com/interviewguide/common/security/UserIdentityResolver.java:14-19`。

1. `owned` 第 277 行调用 `resumeRepository.findById(id)`；第 278 行找不到时抛 `RESUME_NOT_FOUND`。
2. 第 279 行先 `identity.require(userId)`，再调用 `owns(resume,owner)`；第 280 行对 false 抛 `RESUME_ACCESS_DENIED`；第 282 行返回实体；第 283 行结束。
3. `owns` 第 285 行接收简历和 owner；第 286 行按 `resume.getCandidateId()` 查候选人；第 287 行仅在存在时用 `candidate.getUserId()` 与 owner 做 equals，否则为 false；第 288 行结束。
4. `require` 第 14 行定义函数；第 15-17 行拒绝 null/blank userId；第 18 行 strip 首尾空白；第 19 行返回规范化值。

#### 3.3.3 `ResumeAnalysisService.cancelActiveForResumeIds` 与 `deleteByResumeId`

文件：`java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:71-75`。

1. `deleteByResumeId` 第 71 行是单句委托，传入 resumeId 到 Persistence Service；没有创建新任务，也不会调用 worker。
2. `cancelActiveForResumeIds` 第 73 行接收 `List<String>`；第 74 行委托到 Persistence；第 75 行结束。

#### 3.3.4 `ResumeAnalysisPersistenceService.cancelActiveForResumeIds` 与 `deleteByResumeId`

文件：`java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisPersistenceService.java:58-66`。

1. `deleteByResumeId` 第 58 行声明事务；第 59 行调用项目声明的派生 Repository 方法 `repository.deleteByResumeId(resumeId)`，删除关联分析记录。
2. `cancelActiveForResumeIds` 第 61 行声明事务；第 62 行接收集合。
3. 第 63 行对空集合直接 return；第 64 行只查询 PENDING、PROCESSING 两种活动状态。
4. 第 65 行对查询到的每一个实体调用 `ResumeAnalysisEntity.cancel`；第 66 行结束。先取消再删除的意图是给已经在 Worker 中读取的任务一个可见的取消状态。

#### 3.3.5 `ResumeAnalysisEntity.cancel`

文件：`java-backend/src/main/java/com/interviewguide/resume/domain/ResumeAnalysisEntity.java:102-107`。

1. 第 102 行定义取消函数。
2. 第 103 行只允许 PENDING 或 PROCESSING 进入分支，COMPLETED/FAILED 不会被倒退改写。
3. 第 104 行写入 `CANCELLED`；第 105 行写入当前更新时间；第 106 行结束条件；第 107 行结束函数。

#### 3.3.6 会话与回合的 Repository 调用、getter

文件：`java-backend/src/main/java/com/interviewguide/interview/mapper/InterviewSessionRepository.java:14-20`、`InterviewTurnRepository.java:9-12`、`InterviewSessionEntity.java:118-137`。

1. `InterviewSessionRepository.findByUserIdOrderByCreatedAtDesc` 在第 15 行由项目声明，Spring Data 根据名称按 user_id 查询并按 created_at 倒序返回会话列表。
2. `InterviewSessionEntity.getResumeId` 第 121 行单句返回该会话绑定的 resumeId，delete 的第 257 行用它筛选；`getId` 第 118 行单句返回 session 主键，delete 的第 259 行用它删除 turns。
3. `InterviewTurnRepository.deleteBySessionId` 第 12 行是项目声明的派生删除方法，删除会话下的所有 InterviewTurnEntity。
4. `InterviewSessionRepository.delete` 继承自 JpaRepository；它接收第 260 行的 session 实体删除会话行。框架生成具体 SQL，项目未定义实现体，故不能虚构其内部调用。

#### 3.3.7 候选人和简历实体 getter/setter

文件：`java-backend/src/main/java/com/interviewguide/resume/domain/CandidateEntity.java:25-29`；`ResumeEntity.java:37-46`。

1. `CandidateEntity.getUserId` 第 26 行返回 owner，用于 `owns`；`getCurrentResumeId` 第 28 行返回当前版本指针，用于第 262 行判断；`getId` 第 25 行返回候选人主键，用于第 263 行查剩余版本。
2. `setCurrentResumeId` 第 29 行接收替代简历 ID（可能为 null）并赋给字段；它不自行保存，保存由第 269 行 Repository 调用完成。
3. `ResumeEntity.getCandidateId` 第 38 行支持候选人查询；`getStorageKey` 第 45 行提供受控文件键；`getId` 第 37 行和 `getVersion` 第 39 行在替代版本 stream 中分别用于映射和排序。
4. 这些 getter 均为单句 return，没有数据库查询、文件读写或状态副作用。

#### 3.3.8 `ResumeFileStorageService.delete`

文件：`java-backend/src/main/java/com/interviewguide/resume/service/ResumeFileStorageService.java:49-54`。

1. 第 49 行定义文件删除函数，参数是实体中的 storageKey。
2. 第 50 行对 null/blank key 直接 return，使“没有附件元数据”的删除可安全继续。
3. 第 51 行在固定 root 下 resolve 并 normalize key。
4. 第 52 行验证结果仍以 root 开头；不满足则抛 IOException，防止删除 root 外的任意文件。
5. 第 53 行调用 `Files.deleteIfExists(path)`；文件已不存在不会抛异常；第 54 行结束。

### 3.4 返回与失败边界

1. `ApiResult.success` 位于 `java-backend/src/main/java/com/interviewguide/common/web/dto/ApiResult.java:3-6`：第 4 行声明泛型静态工厂，第 5 行构造 code=200、message=success 和传入 data 的 record，第 6 行结束。Controller 第 73 行传入 null。
2. `ResumeRepository.findById`、`findByCandidateId`、`delete`、`CandidateRepository.findById/save`、`ResumeAnalysisRepository.findByResumeIdInAndStatusIn/deleteByResumeId` 都是项目接口声明/继承的 Spring Data 调用。其调用点和语义已在上述函数逐行列出；SQL 实现不在项目源码，本文不臆造。
3. 如果 `owned`、候选人查询或文件删除抛异常，Controller 不会执行 `ApiResult.success(null)`。Spring 的异常处理链生成失败 HTTP 响应；Axios 失败拦截器再把其转为 ApiRequestError。
4. 因第 254 行只取消、没有调用 `ResumeAnalysisWorker.enqueue` 或 RabbitTemplate，本接口从 Java 到 Python 的实际调用次数为零。

## 4. 审核结论

1. 已按接口汇总第 7 项确认路径为 DELETE `/api/resumes/{id}`，而非下载或重新分析路径。
2. 已覆盖前端确认、请求封装、请求/响应拦截、两个 Java Filter、Controller、授权、分析取消/删除、会话及回合删除、当前版本替换、文件删除和数据库删除。
3. 每个项目定义且实际可达的函数均注明源码文件和行号；Spring Data、Axios、JPA、Servlet 与 Files 等框架函数只说明项目调用边界，不虚构实现。
4. 经代码链核对，本接口不会主动调用 Java Python 客户端或 Python `/v1/**` 服务；取消旧分析任务是防止未来旧消息继续执行的保护动作。
