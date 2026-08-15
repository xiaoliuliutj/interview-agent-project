# GET /api/tools/web/crawl/{previewToken}/archive：下载抓取归档的完整函数调用链逐行解析

## 1. 接口定义

该接口下载此前 `/api/tools/web/crawl` 创建的溯源 Markdown 归档。归档包含入口 URL、抓取状态、页面目录、SHA-256、正文和拒绝原因，并带有 `rag_index_enabled: false`，明确不参与知识库向量化。接口不重新抓网页、不开启 RabbitMQ 任务，也不调用 Python；它只从 Java 进程内、30 分钟有效且 owner 隔离的预览中读取已保存的 `archiveMarkdown`。

| 项目 | 实现 |
| --- | --- |
| 前端入口 | `KnowledgeBaseUploadPage.handleDownloadArchive`（`frontend/src/pages/KnowledgeBaseUploadPage.tsx:84-94`） |
| Java 路径 | `GET /api/tools/web/crawl/{previewToken}/archive`（`WebToolController.java:51-60`） |
| 路径参数 | previewToken，必须存在、未过期且归属当前 `X-User-Id` |
| 响应 | 附件 `web-crawl-sources.md`，`Content-Type: text/markdown;charset=UTF-8` |
| Python 调用 | 无；Python 仅在之前的 `/crawl` 阶段生成归档 |

## 2. 函数调用链

```text
KnowledgeBaseUploadPage.handleDownloadArchive
 → knowledgeBaseApi.downloadWebCrawlArchive
 → request.getInstance → Axios request interceptor
 → WebToolController.downloadArchive
 → WebToolService.archive
 → WebCrawlPreviewService.archive
 → WebCrawlPreviewService.requireOwned → UserIdentityResolver.require
 → KnowledgeBaseService.DownloadedDocument
 → ResponseEntity.ok/contentType/header/body
 → 浏览器 Blob → URL.createObjectURL → <a>.click 下载

（本接口没有新的 Python 函数调用；archiveMarkdown 由先前 crawl_public_site
 → _archive_markdown 生成并在 WebCrawlPreviewService.save 中保存。）
```

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `KnowledgeBaseUploadPage.handleDownloadArchive`

文件：`frontend/src/pages/KnowledgeBaseUploadPage.tsx:84-94`。

1. 第 84 行声明异步下载函数；第 85 行没有 crawlPreview 时直接返回，因此不会构造空 token 请求。
2. 第 86 行开始 try；第 87 行用预览 token 调用 `knowledgeBaseApi.downloadWebCrawlArchive`，等待二进制 Blob。
3. 第 88 行用 `URL.createObjectURL` 将 Blob 映射为临时浏览器 URL。
4. 第 89 行创建 `<a>`，设置 href 和固定下载名 `web-crawl-sources.md`。
5. 第 90 行把链接加入 document、触发 click、移除节点、撤销临时 URL，防止 DOM 和 Blob URL 泄漏。
6. 第 91-93 行把任何异常转为错误提示“溯源归档下载失败”。

#### 3.1.2 `knowledgeBaseApi.downloadWebCrawlArchive` 与请求拦截器

文件：`frontend/src/api/knowledgebase.ts:105-111`、`frontend/src/api/request.ts:47-73`。

1. 第 105 行声明返回 Blob；第 106 行取得共享 Axios 实例，而不是 `request.get`，因为下载不应接受 JSON `ApiResult` 解包。
2. 第 107 行对 token 用 `encodeURIComponent`，避免路径字符破坏路由；第 108 行设置 `responseType: 'blob'` 与 `skipResultTransform` 标记；第 110 行返回 `response.data`。
3. Axios 请求拦截器第 64-73 行确保 headers 存在，调用 `currentUserId`（52-58）获取/生成 localStorage 用户 ID，调用 `createClientId`（47-50）生成请求 ID，并写入 `X-User-Id`、`X-Request-Id`。
4. 如果服务器错误响应仍是 Blob，响应错误拦截器第 136-153 行会调用 `decodeErrorData`（101-108）把 JSON Blob 解析后交给 `parseApiError`（83-98），否则按 HTTP 状态生成 ApiRequestError。

### 3.2 Java 控制器与服务函数

#### 3.2.1 `WebToolController.downloadArchive`

文件：`java-backend/src/main/java/com/interviewguide/web/controller/WebToolController.java:51-60`。

1. 第 51 行将 GET 与 `{previewToken}` 绑定到类级 `/api/tools/web` 路径。
2. 第 52-54 行从路径和可选请求头注入 token、用户 ID。
3. 第 55 行调用 `webToolService.archive`，取得包含文件名、内容类型、字节数组的 `DownloadedDocument` record。
4. 第 56 行创建 HTTP 200 响应；第 57 行按 record 内容类型设置 `Content-Type`；第 58 行设置 `Content-Disposition: attachment` 和文件名；第 59 行写入字节数组；第 60 行结束。

#### 3.2.2 `WebToolService.archive`

文件：`java-backend/src/main/java/com/interviewguide/web/service/WebToolService.java:52-54`。

1. 第 52 行声明返回 `KnowledgeBaseService.DownloadedDocument`，复用知识库模块的只读二进制响应类型。
2. 第 53 行不构造 Agent 请求、不调用 Python 客户端，而是直接委派 `previews.archive(userId, token)`。
3. 第 54 行结束。这个边界保证下载不会因网络抓取、LLM、RabbitMQ 或向量化状态变化而改变内容。

#### 3.2.3 `WebCrawlPreviewService.archive` 与 `requireOwned`

文件：`java-backend/src/main/java/com/interviewguide/web/service/WebCrawlPreviewService.java:120-141`。

1. `archive` 第 120 行声明方法；第 121 行通过 `requireOwned` 获取当前用户的有效预览。
2. 第 122-124 行创建 `DownloadedDocument`：固定文件名、UTF-8 Markdown MIME 类型、把 archiveMarkdown 以 UTF-8 编码为 byte[]。
3. `requireOwned` 第 127 行先 `identity.require(userId)`，将缺失/非法身份交给统一安全组件拒绝。
4. 第 128-130 行拒绝 null/空 token，抛 `WEB_CRAWL_PREVIEW_REQUIRED`。
5. 第 131 行从 `ConcurrentHashMap` 查预览；第 132-135 行对未找到或已过期预览返回 `WEB_CRAWL_PREVIEW_EXPIRED`，并在已过期时使用 `remove(token, preview)` 避免误删并发替换的新对象。
6. 第 136-138 行比较预览 owner 与当前 owner，不一致抛 `WEB_CRAWL_PREVIEW_ACCESS_DENIED`；第 140 行只在全部检查通过时返回 Preview。

### 3.3 归档的来源函数（本请求不执行）

Python 代码并不由下载请求触发；为说明数据来源，归档在上一阶段由 `crawl_public_site`（`python-agent/app/tools/web_reader.py:325-399`）最后调用 `_archive_markdown`（302-323）产生。

1. `_archive_markdown` 第 304-307 行建立 YAML front matter，写入 `rag_index_enabled: false` 与 document type，并记录入口、状态、有效和拒绝页面数量。
2. 第 308-315 行可选写停止原因，遍历页面写来源目录、文件名、深度和 SHA-256。
3. 第 316-321 行写“仅归档，不参与 RAG”的页面正文；有拒绝项时再写 URL 与原因。
4. 第 322 行用换行连接并保证结尾换行。`CrawlResult.as_dict`（79-91）把它放入 `archiveMarkdown`；Java `WebCrawlPreviewService.save`（52-83）验证该文本含 `rag_index_enabled: false`、限制长度，并存入 Preview。

## 4. 主流构建分析

主流做法是将归档存入对象存储（如 MinIO/S3）并由短期签名 URL 或受控下载 API 提供，而不是保存在单个应用实例的内存。优点是大文件更节省应用堆内存、多实例共享、重启不丢失，且可设置对象生命周期；缺点是新增对象存储、访问控制和清理成本。

本项目当前归档上限约 3MB、预览只保留 30 分钟，内存方案实现简单，适合单实例演示；但在 Docker 多副本或重启后 token 会失效。需要演进时，可让 `WebCrawlPreviewService.save` 上传归档至 MinIO，Preview 只保存 object key/owner/expiry，`archive` 通过服务端流式读取或生成预签名 URL；仍需保留 `requireOwned`，以避免仅凭 token 越权访问。
