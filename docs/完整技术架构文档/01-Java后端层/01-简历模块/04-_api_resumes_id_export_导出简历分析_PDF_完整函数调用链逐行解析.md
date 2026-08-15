# GET /api/resumes/{id}/export：导出简历分析 PDF 完整函数调用链逐行解析

> 本文以当前实现为准。导出是 Java 同步生成 PDF 的只读操作；它不会向 RabbitMQ 投递消息，也不调用 Python。分析结果只从 Java Redis 快照或 PostgreSQL 读取。

## 1. 接口定义

### 1.1 功能与作用

该接口将当前用户的一份简历正文与最新分析结果组合为 PDF 字节流，并以附件响应下载。若尚无分析，PDF 仍包含简历文本；若服务器没有配置可读取的 CJK 字体文件，接口明确返回 `RESUME_PDF_FONT_REQUIRED`，避免中文文字生成乱码或丢字。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 方法与路径 | `GET /api/resumes/{id}/export` |
| Controller | `ResumeController.export`，`java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:51-55` |
| 输入 | 路径 `id`、请求头 `X-User-Id` |
| 成功响应 | `ResponseEntity<byte[]>`，`application/pdf`，`Content-Disposition: attachment` |
| 数据 | resumes、candidates、最新 resume_analyses；字体路径来自 `agent.pdf-font-path` |
| 异步边界 | 不调用 Python、不发 RabbitMQ；只读取已生成的分析结果。|

### 1.3 前端入口

`frontend/src/pages/ResumeDetailPage.tsx:112-129` 的 `handleExportAnalysisPdf` 由分析面板的导出按钮触发。它调用 `historyApi.exportAnalysisPdf`，然后把 Blob 转为临时 URL 并模拟下载。

## 2. 函数调用链

```text
ResumeDetailPage.handleExportAnalysisPdf
  -> historyApi.exportAnalysisPdf
  -> request.getInstance().get（blob，跳过统一结果解包）
  -> Axios 请求拦截器 -> currentUserId / createClientId
  -> RequestIdFilter.doFilterInternal -> normalize
  -> SimpleRateLimitFilter.doFilterInternal -> JavaRedisStore.incrementInFixedWindow
     ->（Redis 故障）ConcurrentHashMap 回退
  -> IdempotencyFilter.shouldNotFilter（GET，跳过）
  -> ResumeController.export
  -> ResumeService.export
     -> ResumeService.owned -> ResumeRepository.findById -> ResumeService.owns
        -> UserIdentityResolver.require -> CandidateRepository.findById
     -> ResumeAnalysisService.latest
        -> JavaTaskStatusCache.latestResumeAnalysis / JavaRedisStore.getJson
        ->（未命中）ResumeAnalysisPersistenceService.latest -> ResumeAnalysisRepository XML
        -> toCachedView 或 toView -> JSON 列表转换函数
     -> ResumeService.addTextPages
  -> PDF byte[] 响应 -> 前端 Blob URL 下载与 finally
```

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `ResumeDetailPage.handleExportAnalysisPdf`

**文件与行号：** `frontend/src/pages/ResumeDetailPage.tsx:112-129`。

1. 第 112 行声明异步导出处理函数。第 113 行把 `exporting` 置为 `analysis`，使界面可禁用重复点击。
2. 第 114 行进入 `try`；第 115 行调用 `historyApi.exportAnalysisPdf(resumeId)` 并等待 PDF Blob。
3. 第 116 行用 `URL.createObjectURL` 创建浏览器临时地址；第 117 行创建 `<a>` 元素；第 118 行赋值 href；第 119 行以当前文件名或简历 ID 组成下载文件名。
4. 第 120 行临时插入 DOM；第 121 行触发点击；第 122 行立即移除元素；第 123 行调用 `revokeObjectURL` 释放浏览器内存。
5. 第 124-125 行捕获任意请求或浏览器下载异常并提示用户。第 126-128 行在 finally 中清空 `exporting`；第 129 行结束。

#### 3.1.2 `historyApi.exportAnalysisPdf`

**文件与行号：** `frontend/src/api/history.ts:88-91`。

1. 第 88 行声明异步函数与 Blob 返回类型。第 89 行通过 `request.getInstance()` 取得底层 Axios 实例，并请求 `/api/resumes/${resumeId}/export`。
2. 同行设置 `responseType: 'blob'`，防止 Axios 按 JSON 解析 PDF；`skipResultTransform: true` 是类型传递给配置的标志，实际成功拦截器看到 Blob 不含 `code` 后会在 `request.ts:134` 原样返回。
3. 第 90 行返回 `response.data` Blob；第 91 行结束。

#### 3.1.3 请求身份和响应错误函数

**文件与行号：** `frontend/src/api/request.ts:47-57、64-72、123-154`。

1. `createClientId` 第 47-49 行用安全 UUID 或旧环境回退生成客户端 ID；`currentUserId` 第 52-57 行读取或首次写入 localStorage。
2. 请求拦截器第 64-72 行初始化 headers、兼容 AxiosHeaders/普通对象、写入 `X-User-Id` 和每请求的 `X-Request-Id` 后返回配置。
3. 成功拦截器第 125 行读取 Blob；第 126 行因其没有 `code`，第 134 行原样返回。因此 PDF 不会被错误地当成 `ApiResult` 解包。
4. 失败拦截器第 136-154 行先确认 Axios 错误，再在第 140 行尝试从 Blob 解码错误 JSON；第 141-153 行转换统一错误或 HTTP 错误，保留响应 requestId。

### 3.2 Java 过滤与入口函数

#### 3.2.1 `RequestIdFilter.doFilterInternal` 与 `normalize`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/infrastructure/web/RequestIdFilter.java:23-41`。

1. 第 25 行读取并规范 request ID；第 26 行存 request attribute；第 27 行回写响应头；第 28 行加入 MDC。
2. 第 29-30 行放行链路；第 31-33 行 finally 清理 MDC。`normalize` 第 36-41 行保留合法且不超过 128 位的值，否则创建 UUID。

#### 3.2.2 `SimpleRateLimitFilter.doFilterInternal` 与 `JavaRedisStore.incrementInFixedWindow`

**文件与行号：** `SimpleRateLimitFilter.java:48-82`，`JavaRedisStore.java:31-39`，均位于 `java-backend/src/main/java/com/interviewguide/infrastructure/`。

1. 过滤器第 50-58 行跳过健康探针、按 IP/URI/分钟构造 Redis 限流键。
2. `incrementInFixedWindow` 第 32-35 行原子递增并首次设 65 秒 TTL；第 36-38 行 Redis 异常时返回空 Optional。
3. 过滤器第 60-67 行采用分布式计数或 `ConcurrentHashMap` 本机回退；第 69-79 行超限响应 429；第 81 行放行未超限请求。

#### 3.2.3 `IdempotencyFilter.shouldNotFilter`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/infrastructure/idempotency/IdempotencyFilter.java:41-44`。

1. 第 42-44 行规定仅带幂等键的写请求需要处理。当前为 GET，所以函数决定跳过，既不占用 Redis 幂等键也不调用 `doFilterInternal`。

#### 3.2.4 `ResumeController.export`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:51-55`。

1. 第 51 行映射 `/{id}/export`。第 52 行绑定路径 ID；第 53 行绑定可选用户头。
2. 第 54 行将两者原样委托给 `resumeService.export`；第 55 行结束。该接口返回二进制 `ResponseEntity`，不使用 `ApiResult.success`。

### 3.3 Java 授权、分析读取和 PDF 构建函数

#### 3.3.1 `ResumeService.export`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:178-202`。

1. 第 178-180 行声明函数并通过 `owned` 读取且授权简历。第 181 行调用 `analysisService.latest(id)`，它只读取最新快照/记录。
2. 第 182 行创建内容缓冲并追加简历文本，空文本按空字符串处理。第 183 行仅在存在分析时追加分析段。
3. 第 184-187 行依次追加总分、摘要、优点和建议；字符串列表用分号连接。第 189-191 行检查注入的 `pdfFontPath` 不为空且是可读普通文件，不满足即抛出明确配置错误。
4. 第 192 行创建可自动关闭的 PDF 文档和字节输出流。第 193 行加载 CJK 字体；第 194 行调用项目函数 `addTextPages`；第 195 行把 PDF 写入输出流。
5. 第 196-198 行创建 HTTP 200，设置 `application/pdf`、附件文件名并写入字节数组。第 199-201 行将任何 IOException 转换为 `RESUME_PDF_EXPORT_FAILED`；第 202 行结束。

#### 3.3.2 `ResumeService.owned`、`owns` 与 Mapper 查询

**文件与行号：** `ResumeService.java:276-288`，`ResumeRepository.java:13`，`CandidateRepository.java:11`，均位于 `java-backend/src/main/java/com/interviewguide/`。

1. `owned` 第 277 行按 ID 调用 `ResumeRepository.findById`，第 278 行将空结果转换为 `RESUME_NOT_FOUND`。
2. 第 279 行调用 `UserIdentityResolver.require` 后再调用 `owns`；第 280 行拒绝非所有者；第 282 行返回实体。
3. `require` 位于 `common/security/UserIdentityResolver.java:14-19`：第 15-17 行拒绝空身份，第 18 行去首尾空白，第 19 行返回 owner。
4. `owns` 第 286 行调用 `CandidateRepository.findById`，第 287 行比较候选人的 userId。两个 Mapper 分别由 `mapper/resume/ResumeRepository.xml` 与 `CandidateRepository.xml` 的按主键 `<select>` 实现；它们是 MyBatis，不是 JPA。

#### 3.3.3 `ResumeAnalysisService.latest` 与转换函数

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:67-74、91-137`。

1. 第 70 行调用 `JavaTaskStatusCache.latestResumeAnalysis`，后者读取 `java:task:resume-analysis:latest:<resumeId>`；底层 `JavaRedisStore.getJson` 发生 Redis/JSON 异常时返回空 Optional。
2. 第 71 行缓存命中时调用 `toCachedView`；第 72 行未命中时调用 `ResumeAnalysisPersistenceService.latest`；第 73 行无数据库记录返回 null，有记录转 `toView`。
3. `toCachedView` 第 99-109 行从 Map 读取字段，分别调用第 111-125 行的 `number`、`integerOrNull`、`string`、`nullableString`、`parseInstant` 做安全类型回退，并调用 JSON 列表函数。
4. `toView` 第 91-97 行从实体复制字段。`stringList` 第 127-131 行和 `mapList` 第 133-137 行对空/错误 JSON 返回空集合，确保历史脏数据不会阻断导出。
5. `ResumeAnalysisPersistenceService.latest` 在 `ResumeAnalysisPersistenceService.java:60-62` 调用 `ResumeAnalysisRepository.findFirstByResumeIdOrderByCreatedAtDesc`；XML `mapper/resume/ResumeAnalysisRepository.xml:6` 以创建时间倒序取一条。

#### 3.3.4 `ResumeService.addTextPages`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:204-224`。

1. 第 205 行创建行列表。第 206 行按所有换行符切分原文且保留空行。
2. 第 207 行取得当前源行；第 208 行在长度超过 55 时循环切出前 55 个字符并保留剩余部分；第 209 行写入最后一段。因此这是按字符数、不是按字体宽度的换行规则。
3. 第 211-213 行初始化当前页、内容流和页内行数。第 214 行遍历每个文本行。
4. 第 215 行在尚无页面或已达 48 行时新开页。第 216 行先结束并关闭旧流；第 217 行创建并加入 PDF 页；第 218 行创建新内容流；第 219 行开始文字、设置字体/字号/行距/起点并复位计数。
5. 第 221 行把 Tab 替换为空格、写入文本、换行并递增计数。第 223 行在存在流时结束文字和关闭流；第 224 行结束函数。

## 4. 主流构建分析

当前方式在请求线程中用 PDFBox 生成整份 PDF。优点是无需额外服务、简单可靠、下载内容与当前 Java 查询结果一致；缺点是长简历和高并发导出会占用 Web 线程与内存，而且固定“55 字符/48 行”的排版不考虑真实字宽。

主流方案是在导出规模增长时使用异步导出任务：把导出请求写入任务表或消息队列，工作进程生成 PDF 到对象存储，前端轮询任务状态并获取短期签名下载链接。优点是不会阻塞 HTTP 线程、可以重试与审计、适合大文件；缺点是新增任务状态、对象存储、清理策略和最终一致性。

本项目当前导出量较小时适合保留同步实现；若引入异步方案，应复用现有 RabbitMQ 可靠性模式：创建 `resume_export_tasks` 表并在事务内记录任务，消费者调用独立导出服务/Worker，文件写入 MinIO 或 S3，任务完成后写下载键。无论同步或异步，都应继续在部署环境配置真实的 `agent.pdf-font-path`，并用支持 CJK 的字体及真实宽度测量替代固定字符切分。
