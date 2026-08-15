# GET /api/resumes/{id}/download：下载原始简历完整函数调用链逐行解析

> 本接口返回上传时保存在 Java 文件存储目录中的原始字节。它不调用 Python、RabbitMQ 或 Redis 业务缓存；Redis 只参与请求限流。所有路径均按当前代码标注。

## 1. 接口定义

### 1.1 功能与作用

`GET /api/resumes/{id}/download` 验证当前用户对简历的所有权后，从受控本地存储根目录读取原始文件，以数据库记录的 MIME 类型和文件名作为附件下载。存储键来自数据库，且文件服务再次做根目录边界检查，防止路径穿越。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 方法与路径 | `GET /api/resumes/{id}/download` |
| Controller | `ResumeController.download`，`java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:57-61` |
| 输入 | 路径 `id`、`X-User-Id` |
| 响应 | 原始 `byte[]`，Content-Type 为记录值或 `application/octet-stream`，附件文件名为原始文件名 |
| 读取位置 | `agent.file-storage.root` 下的 `<resumeId>/<safeFilename>` |
| Python / MQ | 没有调用。|

### 1.3 前端入口

入口是 `frontend/src/pages/ResumeDetailPage.tsx:131-` 的 `handleDownloadResume`，它调用 `historyApi.downloadResume`。API 函数位于 `frontend/src/api/history.ts:92-95`，以 Blob 响应类型请求下载端点。

## 2. 函数调用链

```text
ResumeDetailPage.handleDownloadResume
  -> historyApi.downloadResume -> request.getInstance().get(blob)
  -> Axios 请求拦截器 -> currentUserId / createClientId
  -> RequestIdFilter.doFilterInternal -> normalize
  -> SimpleRateLimitFilter.doFilterInternal -> JavaRedisStore.incrementInFixedWindow
     ->（Redis 失败）ConcurrentHashMap 本机回退
  -> IdempotencyFilter.shouldNotFilter（GET，跳过）
  -> ResumeController.download -> ResumeService.download
     -> ResumeService.owned -> ResumeRepository.findById -> UserIdentityResolver.require
        -> ResumeService.owns -> CandidateRepository.findById
     -> ResumeFileStorageService.read
  -> ResponseEntity<byte[]> -> 浏览器 Blob 下载
```

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `ResumeDetailPage.handleDownloadResume`

**文件与行号：** `frontend/src/pages/ResumeDetailPage.tsx:131` 起。

1. 第 131 行声明下载处理函数。其后 `try` 块调用 `historyApi.downloadResume(resumeId)`，获取 Blob。
2. 后续代码与 PDF 导出相同：创建 Object URL、创建临时 `<a>`、设置下载名、点击、移除元素并释放 URL；异常时提示用户。它不解析或修改文件字节。

#### 3.1.2 `historyApi.downloadResume`

**文件与行号：** `frontend/src/api/history.ts:92-95`。

1. 第 92 行声明返回 `Promise<Blob>` 的异步函数。第 93 行通过 Axios 实例请求 `/api/resumes/${resumeId}/download`，设置 `responseType: 'blob'` 与 `skipResultTransform: true`。
2. 第 94 行返回 `response.data`。Blob 不含 Java `ApiResult.code`，因此 `request.ts:123-135` 的成功拦截器在第 134 行原样返回它。

#### 3.1.3 `createClientId`、`currentUserId` 和请求拦截器

**文件与行号：** `frontend/src/api/request.ts:47-72`。

1. `createClientId` 第 47-49 行用 `crypto.randomUUID` 或旧环境随机回退创建 ID。
2. `currentUserId` 第 52-57 行读取 localStorage，首次缺失时生成、持久化并返回临时用户 ID。
3. 请求拦截器第 64-72 行确保 headers 可写，写入 `X-User-Id` 与新的 `X-Request-Id`，再返回 Axios 配置。下载失败时第 136-154 行错误拦截器会从 Blob 尝试解码统一错误 JSON。

### 3.2 Java 过滤和入口函数

#### 3.2.1 `RequestIdFilter.doFilterInternal` 与 `normalize`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/infrastructure/web/RequestIdFilter.java:23-41`。

1. 第 25 行读取头并调用 `normalize`；第 26-28 行存 request attribute、回写响应头、写 MDC。
2. 第 29-30 行继续过滤链；第 31-33 行 finally 清理 MDC。`normalize` 第 36-41 行接受安全短 ID，否则生成 UUID。

#### 3.2.2 `SimpleRateLimitFilter.doFilterInternal` 与 `JavaRedisStore.incrementInFixedWindow`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/infrastructure/ratelimit/SimpleRateLimitFilter.java:48-82`，`java-backend/src/main/java/com/interviewguide/infrastructure/redis/JavaRedisStore.java:31-39`。

1. 第 54-58 行按 IP、URI、分钟调用 Redis 固定窗口递增。Redis 函数第 32-35 行执行 INCR、首次设置 TTL、返回计数；第 36-38 行异常返回空值。
2. 过滤器第 60-67 行在空值时使用 `ConcurrentHashMap` 本机窗口；第 69-79 行超限返回 429；第 81 行放行。

#### 3.2.3 `IdempotencyFilter.shouldNotFilter` 与 `ResumeController.download`

**文件与行号：** `IdempotencyFilter.java:41-44`，`ResumeController.java:57-61`，均在 `java-backend/src/main/java/com/interviewguide/`。

1. `shouldNotFilter` 第 42-44 行只处理带幂等键的写方法；GET 下载被跳过，不调用幂等占位函数。
2. Controller 第 57 行映射下载路径；第 58 行绑定 id；第 59 行绑定可选用户头；第 60 行委托 `resumeService.download`；第 61 行结束。二进制响应不经过 `ApiResult.success`。

### 3.3 授权、文件读取与响应函数

#### 3.3.1 `ResumeService.download`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:225-233`。

1. 第 225-227 行声明下载函数并调用 `owned`，所以读取文件前已校验存在性与所有权。
2. 第 228-229 行在数据库 contentType 为空或空白时回退 `application/octet-stream`，否则使用原值。
3. 第 230 行构造 200 与 MediaType；第 231 行设置以原始文件名为值的 attachment 头；第 232 行调用 `fileStorage.read(resume.getStorageKey())` 并把字节放入 body；第 233 行结束。

#### 3.3.2 `ResumeService.owned`、`owns`、身份和 Mapper 函数

**文件与行号：** `ResumeService.java:276-288`，`common/security/UserIdentityResolver.java:14-19`，`resume/mapper/ResumeRepository.java:13`，`CandidateRepository.java:11`。

1. `owned` 第 277 行调用 `ResumeRepository.findById`，第 278 行把空结果转为 `RESUME_NOT_FOUND`。
2. 第 279 行先调用 `UserIdentityResolver.require`，再调用 `owns`；第 280 行拒绝非所有者；第 282 行返回实体。
3. `require` 第 15-17 行拒绝空用户头，第 18 行去首尾空白，第 19 行返回用户 ID。
4. `owns` 第 286 行按 candidateId 调用 `CandidateRepository.findById`，第 287 行比较候选人 userId。两个 Mapper 分别由 `resources/mapper/resume/ResumeRepository.xml` 和 `CandidateRepository.xml` 的 `<select>` SQL 实现，属于 MyBatis。

#### 3.3.3 `ResumeFileStorageService.read`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeFileStorageService.java:43-47`。

1. 第 43 行声明可抛出 IOException 的读取函数。第 44 行将存储键拼入配置根目录后调用 `normalize`。
2. 第 45 行检查规范化结果仍以 root 开头；失败时抛出 `invalid storage path`，阻断诸如 `../` 的路径穿越。
3. 第 46 行用 `Files.readAllBytes` 读取整个文件；第 47 行结束。Controller 的 `throws IOException` 交由全局异常处理转换为 HTTP 错误。

## 4. 主流构建分析

当前做法将文件存在 Java 容器可写目录，优点是代码少、权限检查集中、适合本地开发和小型部署；缺点是多实例间文件不共享、容器重建可能丢失文件、`readAllBytes` 会把大文件一次载入内存。

主流生产实现通常使用对象存储（MinIO/S3/OSS）并由后端签发短期预签名下载 URL，或由后端流式代理下载。优点是横向扩容、持久化和大文件处理更可靠；缺点是引入存储凭据、桶策略、生命周期和 URL 过期管理。

本项目部署到 Docker 多副本前适合迁移。实现时可抽象 `ResumeFileStorageService` 接口，保留当前本地实现作为开发 profile，新增 MinIO 实现；数据库仅保存对象 key；下载时在 `owned` 授权后签发只读短期 URL，或用 `InputStreamResource` 流式响应。继续保留 `normalize`/根目录或对象 key 白名单验证，避免把数据库中的异常键直接暴露给存储客户端。
