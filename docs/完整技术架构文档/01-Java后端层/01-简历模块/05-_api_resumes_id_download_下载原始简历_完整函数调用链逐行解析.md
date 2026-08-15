# GET /api/resumes/{id}/download：下载原始简历完整函数调用链

## 1. 接口定义

### 1.1 功能和作用

该接口按简历 ID 下载当前用户拥有的原始文件。Java 查询被授权的 ResumeEntity，确定 MIME 类型与原始文件名，再用实体的 storageKey 从固定文件存储根目录读取 byte[]，作为附件返回。它不读取分析结果、不写数据库、不投递 RabbitMQ，也不调用 Python。

### 1.2 基本信息

| 项目 | 内容 |
| --- | --- |
| HTTP 方法 | GET |
| 路径 | /api/resumes/{id}/download |
| Controller | ResumeController.download |
| Service | ResumeService.download |
| 响应 | ResponseEntity<byte[]> |
| MIME | 实体 contentType，缺失时 application/octet-stream |
| 下载名 | 原始文件名 |
| Python 调用 | 无 |

## 2. 函数调用链

~~~text
ResumeDetailPage.handleDownloadResume
 -> historyApi.downloadResume
 -> request.getInstance -> Axios get(blob)
 -> 请求拦截器 -> currentUserId -> createClientId
 -> RequestIdFilter.doFilterInternal -> normalize
 -> SimpleRateLimitFilter.doFilterInternal
 -> ResumeController.download
 -> ResumeService.download
    -> ResumeService.owned
       -> ResumeRepository.findById
       -> UserIdentityResolver.require
       -> ResumeService.owns
          -> CandidateRepository.findById -> CandidateEntity.getUserId
    -> ResumeEntity.getContentType/getOriginalFilename/getStorageKey
    -> ResumeFileStorageService.read
       -> Path.resolve/normalize/startsWith(root)
       -> Files.readAllBytes
    -> ResponseEntity.ok/contentType/header/body
 -> 前端 Blob URL 下载
~~~

从 ResumeService.download 的第233行返回后，项目函数调用结束。当前源码中没有 PythonAgentClient、HttpPythonAgentClient、RabbitTemplate、Worker 或 Python /v1 路径。

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 ResumeDetailPage.handleDownloadResume

文件：frontend/src/pages/ResumeDetailPage.tsx:133-147。

1. 第133行定义异步下载函数。
2. 第135行 await historyApi.downloadResume(resumeId)，请求未完成前不创建下载链接。
3. 第136行 URL.createObjectURL(blob) 注册临时浏览器 URL。
4. 第137行创建 a 元素；第138行设置 href。
5. 第139行设置下载文件名，优先详情页的 resume.filename，缺失时使用 resume-{resumeId}。
6. 第140行插入 document.body；第141行 a.click 触发浏览器下载。
7. 第142行移除元素；第143行 revokeObjectURL 回收 Blob URL。
8. 第144-145行捕获任意请求或下载准备错误并 alert；第146-147行结束。

#### 3.1.2 historyApi.downloadResume

文件：frontend/src/api/history.ts:92-95。

1. 第92行定义异步函数，输入 string 或 number resumeId，返回 Promise<Blob>。
2. 第93行调用 request.getInstance()，再对动态下载路径执行 GET。
3. responseType='blob' 使 Axios 返回二进制；skipResultTransform=true 作为项目的二进制请求配置。
4. 第94行 return response.data；第95行结束。

#### 3.1.3 request.getInstance、身份 ID 与 Axios 拦截器

文件：frontend/src/api/request.ts:47-73、180-182。

1. getInstance 第180-182行返回共享 Axios instance。
2. createClientId 第47-50行优先 crypto.randomUUID，失败时以 prefix、时间和随机数生成 ID。
3. currentUserId 第52-58行读取 localStorage，缺失时生成并保存用户 ID。
4. 拦截器第64行接收 config；第65行确保 headers；第66-69行的 setHeader 兼容 AxiosHeaders 和普通对象。
5. 第70行写 X-User-Id；第71行写 X-Request-Id；第72行返回修改后的配置。

#### 3.1.4 Blob 响应与错误函数

文件：frontend/src/api/request.ts:75-155。

1. 成功拦截器第124-134行只解包带 code 的 JSON；下载响应是 Blob，因此第134行原样返回。
2. isRecord 第75-77行拒绝 null、数组和非对象；stringValue 第79-81行只取非空字符串。
3. parseApiError 第83-99行构造包含重试性、HTTP 状态和 requestId 的 ApiRequestError。
4. decodeErrorData 第101-108行只对 JSON Blob 解析文本；transportError 第110-121行映射超时和网络错误。
5. 第136-153行失败拦截器把异常拒绝给 handleDownloadResume 的 catch。

### 3.2 Java Web 入口

#### 3.2.1 RequestIdFilter.doFilterInternal 与 normalize

文件：java-backend/src/main/java/com/interviewguide/infrastructure/web/RequestIdFilter.java:23-41。

1. 第25行读取 X-Request-Id 并调用 normalize。
2. normalize 第36-41行只接受非空、长度不超过128且符合正则的值；合法时第38行返回，非法时第40行生成 UUID。
3. 第26-28行写 request attribute、响应头和 MDC。
4. 第30行继续 filterChain；第31-33行 finally 清理 MDC。

#### 3.2.2 SimpleRateLimitFilter.doFilterInternal

文件：java-backend/src/main/java/com/interviewguide/infrastructure/ratelimit/SimpleRateLimitFilter.java:38-61。

1. 第40-43行只放行 health/actuator，下载路径继续。
2. 第44-47行按远端地址、URI 和当前分钟得到 Window。
3. 第48行 incrementAndGet 后比较 limit。
4. 超限第49-58行返回429、Retry-After 和 ApiErrorResponse；正常第60行继续。

#### 3.2.3 ResumeController.download

文件：java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:57-61。

1. 第57行映射与类级 /api/resumes 组成路径。
2. 第58行绑定 id 并声明 ResponseEntity<byte[]>。
3. 第59行读取 X-User-Id，声明 IOException 可传播。
4. 第60行调用 resumeService.download(id,userId)；第61行结束。Controller 不读取文件。

### 3.3 ResumeService.download

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:225-233。

1. 第225-226行声明下载函数。
2. 第227行调用 owned，先取得授权简历。
3. 第228行读取 getContentType 并判断 null/blank。
4. 第229行缺失 MIME 时选 APPLICATION_OCTET_STREAM_VALUE，否则保留实体 MIME。
5. 第230行 ResponseEntity.ok 后调用 MediaType.parseMediaType(contentType)。
6. 第231行读取 getOriginalFilename，设置 Content-Disposition 附件名。
7. 第232行读取 getStorageKey，调用 fileStorage.read，并以返回 byte[] 调 body。
8. 第233行结束。无 AnalysisService、无 Python 客户端。

#### 3.3.1 ResumeService.owned 与 owns

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:276-288。

1. owned 第277行 ResumeRepository.findById；第278行找不到抛 RESUME_NOT_FOUND。
2. 第279行 identity.require 后调用 owns；第280行不归属抛 RESUME_ACCESS_DENIED；第282行返回实体。
3. owns 第286行用 getCandidateId 查询 CandidateRepository.findById。
4. 第287行把 candidate.getUserId 与请求 ID比较；候选人不存在时 orElse(false)；第288行结束。

#### 3.3.2 UserIdentityResolver.require 与 Repository

文件：java-backend/src/main/java/com/interviewguide/common/security/UserIdentityResolver.java:14-19；java-backend/src/main/java/com/interviewguide/resume/mapper/ResumeRepository.java:8-12；CandidateRepository.java:7-9。

1. require 第15-17行拒绝 null/blank；第18行 strip；第19行返回。
2. ResumeRepository.findById 和 CandidateRepository.findById 是 JPA 继承的只读主键查询。
3. 该链不会调用 save、delete、RabbitMQ 或 Python。

### 3.4 ResumeFileStorageService.read

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeFileStorageService.java:43-47。

1. 第43行定义 read，输入实体持久化的 storage key，返回 byte[]。
2. 第44行 root.resolve(key).normalize，组合固定根目录和 key 并折叠路径元素。
3. 第45行 path 不以 root 开头时抛 IOException，阻止 .. 路径穿越。
4. 第46行 Files.readAllBytes 读取完整文件；第47行结束。
5. read 不接收浏览器传入的任意路径，key 来自 owned 成功后的 ResumeEntity，但仍执行根目录校验。

#### 3.4.1 ResumeFileStorageService 构造函数

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeFileStorageService.java:17-19。

1. 第17行接收 agent.file-storage.root 配置。
2. 第18行 Path.of(root).toAbsolutePath().normalize，把根目录固定为绝对规范路径。
3. 第19行结束。read/store/delete 都使用该同一 root。

#### 3.4.2 ResumeEntity getter

文件：java-backend/src/main/java/com/interviewguide/resume/domain/ResumeEntity.java:37-46。

1. getId 第37行返回 id；getCandidateId 第38行返回 candidateId，owned/owns 使用。
2. getContent 第40行返回解析正文，本下载接口不调用。
3. getOriginalFilename 第42行供 header；getContentType 第44行供 MIME；getStorageKey 第45行供 read。
4. getVersion、第39行，getFileHash、第41行，getFileSize、第43行，getCreatedAt、第46行在本下载接口不调用。
5. 每个 getter 都是单句 return，不访问磁盘、不修改状态。

### 3.5 二进制失败边界

文件：java-backend/src/main/java/com/interviewguide/common/web/ApiExceptionHandler.java:31-35、86-92、115-140。

1. handleBusiness 第32-35行处理 USER_ID_REQUIRED、RESUME_NOT_FOUND、RESUME_ACCESS_DENIED。
2. handleDataAccess 第87-92行记录数据库错误并返回503。
3. handleUnexpected 第116-120行记录 IOException 等异常并返回500。
4. response 第123-129行构造 ApiErrorDetail 和 ResponseEntity；requestId 第131-136行取 attribute/header 或生成 UUID；firstNonBlank 第138-140行选择非空值。
5. 文件不存在、非法 key、权限不足会从 read 抛 IOException；代码不会把绝对文件路径返回给客户端。

### 3.6 下载链路的独立辅助函数审计

#### 3.6.1 request.ts 的 `isRecord`

文件：`frontend/src/api/request.ts:75-77`。

1. 第 75 行声明私有类型收窄函数，入参是未知值 `value`，返回值类型为 `Record<string, unknown>`。
2. 第 76 行依次排除 `null`、非 object 的基本类型和数组；数组虽然也是 object，但不能按错误对象的键读取。
3. 第 77 行结束函数。下载失败时，响应拦截器借此决定能否读取服务端 JSON 错误体。

#### 3.6.2 request.ts 的 `stringValue`

文件：`frontend/src/api/request.ts:79-81`。

1. 第 79 行声明字符串提取函数，输入是可能为空的未知值。
2. 第 80 行只接受 `string` 类型且 `trim()` 后非空的值；满足条件时返回原字符串，不满足时返回 `undefined`。
3. 第 81 行结束。`parseApiError` 用它读取 code、message、requestId 等可选字段，避免把数字或空白字符串写入错误对象。

#### 3.6.3 request.ts 的 `parseApiError`

文件：`frontend/src/api/request.ts:83-99`。

1. 第 83 行定义解析函数，接收 Axios 的 HTTP 状态、响应体和默认错误信息。
2. 第 84 行先调用 `isRecord`；不符合对象条件时立即构造默认 `ApiRequestError`。
3. 第 85-86 行读取嵌套 `error` 对象；如果不存在则继续以最外层对象作为错误来源。
4. 第 87-98 行逐项使用 `stringValue` 或布尔判断抽取错误码、消息、是否可重试、requestId、runId、sessionId 和 stage，并用这些值构造项目定义的 `ApiRequestError`。
5. 第 99 行返回异常对象；Axios 的失败拦截器以 rejected Promise 把它交给页面的 `catch`。

#### 3.6.4 request.ts 的 `decodeErrorData` 与 `transportError`

文件：`frontend/src/api/request.ts:101-121`。

1. `decodeErrorData` 第 101 行接收错误响应体；第 102 行只对 `Blob` 且 Content-Type 含 JSON 的二进制错误体执行解析。
2. 第 103-105 行 await `blob.text()` 后 `JSON.parse`；第 106-107 行解析失败时返回原 `Blob`，不会把解析失败掩盖为业务 JSON。
3. `transportError` 第 110 行接收 AxiosError；第 111-113 行从 URL 和请求内容类型判断是否为上传，决定默认文案。
4. 第 114-120 行把超时映射为 `NETWORK_TIMEOUT`，其他无响应网络错误映射为 `NETWORK_UNAVAILABLE`，并携带 retryable=true。
5. 第 121 行结束。下载接口的文件读取或网络失败均可能经过此分支；这两个函数不改变下载文件。

#### 3.6.5 `RequestIdFilter.normalize`

文件：`java-backend/src/main/java/com/interviewguide/infrastructure/web/RequestIdFilter.java:36-41`。

1. 第 36 行定义静态规范化函数，输入浏览器请求头的原始值。
2. 第 37 行检查值非空、长度不超过 128，并匹配允许字符正则。
3. 第 38 行对合法值直接返回；它保留客户端请求链路标识。
4. 第 40 行对缺失或非法值生成新的 UUID；第 41 行结束。

#### 3.6.6 `ResumeService.owned` 与 `ResumeService.owns`

文件：`java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:276-288`。

1. `owned` 第 276 行定义授权查询；第 277 行按 `id` 调用 `resumeRepository.findById`。
2. 第 278 行 `orElseThrow` 在简历不存在时抛出 `RESUME_NOT_FOUND`，因此后续不会读任何文件。
3. 第 279 行先调用 `identity.require(userId)`，再把得到的 owner 传给 `owns`；第 280 行对 false 抛出 `RESUME_ACCESS_DENIED`。
4. 第 282 行返回已同时满足“存在”和“属于当前用户”的实体；第 283 行结束。
5. `owns` 第 285 行定义归属判断；第 286 行按 `resume.getCandidateId()` 查询候选人。
6. 第 287 行仅在候选人存在时比较其 `getUserId()` 与 owner，缺失候选人使用 `orElse(false)` 拒绝访问；第 288 行结束。

#### 3.6.7 `UserIdentityResolver.require`

文件：`java-backend/src/main/java/com/interviewguide/common/security/UserIdentityResolver.java:14-19`。

1. 第 14 行定义用户 ID 校验函数。
2. 第 15 行检查 `null` 或 `isBlank()`；第 16-17 行以 `USER_ID_REQUIRED` 抛业务异常。
3. 第 18 行对合格值执行 `strip()`，消除首尾空白；第 19 行返回规范后的 owner。

#### 3.6.8 `ResumeFileStorageService.read`

文件：`java-backend/src/main/java/com/interviewguide/resume/service/ResumeFileStorageService.java:43-47`。

1. 第 43 行定义受控文件读取函数，参数不是 HTTP 路径，而是数据库记录的 `storageKey`。
2. 第 44 行在配置根目录上 `resolve(key)` 并 `normalize()`，得到候选文件路径。
3. 第 45 行检查候选路径必须以 root 开头；不满足时抛 `IOException`，阻止 `..` 越界。
4. 第 46 行调用 `Files.readAllBytes(path)` 读取原始字节；第 47 行返回。这是此下载接口最后一个项目定义的业务函数。

## 4. 审核结论

1. 已定义路径、响应、MIME、文件名和授权规则。
2. 已列出前端 Blob、过滤器、Controller、所有权、路径防穿越和字节响应函数。
3. 每个实际可达项目函数均标注文件和行号，逐语句解释。
4. ResumeController.java:57-61、ResumeService.java:225-233、ResumeFileStorageService.java:43-47 证明本接口无 Python /v1 调用。
5. 下一接口为 POST /api/resumes/{id}/reanalyze。
