# GET /api/tools/web/crawl/{previewToken}/archive：下载抓取归档的完整函数调用链

## 1. 接口定义

接口按 previewToken 和 X-User-Id 读取 Java 进程内仍有效且属于当前用户的抓取预览，将其中 `archiveMarkdown` 以 UTF-8 Markdown 附件返回。归档带 `rag_index_enabled:false`，用于来源审计而不参与向量索引。它不调用 Python。

| 项目 | 内容 |
| --- | --- |
| 方法/路径 | GET `/api/tools/web/crawl/{previewToken}/archive` |
| 返回 | `text/markdown;charset=UTF-8` byte[] |
| 文件名 | `web-crawl-sources.md` |
| 有效性 | token 存在、未过期、owner 匹配 |
| Python 调用 | 无 |

## 2. 函数调用链

~~~text
KnowledgeBaseUploadPage.handleDownloadArchive
 -> knowledgeBaseApi.downloadWebCrawlArchive → encodeURIComponent
 -> request.getInstance().get(blob) → Axios/Filter
 -> WebToolController.downloadArchive → WebToolService.archive
 -> WebCrawlPreviewService.archive → requireOwned
    -> UserIdentityResolver.require → previews.get → expiresAt/owner 校验
 -> new DownloadedDocument → ResponseEntity.ok/contentType/header/body
 -> Blob URL 下载
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `KnowledgeBaseUploadPage.handleDownloadArchive`

文件：`frontend/src/pages/KnowledgeBaseUploadPage.tsx:84-94`。

1. 第 85 行无 crawlPreview 直接 return。
2. 第 87 行传 previewToken 下载 Blob；第 88 行创建 Blob URL。
3. 第 89-90 行创建 a、设置固定文件名、插入/点击/移除并 revoke URL。
4. 第 91-93 行错误转 UI 文案；第 94 行结束。

#### 3.1.2 `knowledgeBaseApi.downloadWebCrawlArchive`

文件：`frontend/src/api/knowledgebase.ts:105-111`。

1. 第 105 行定义 Blob 返回函数。
2. 第 106-109 行 getInstance().get；previewToken 经 encodeURIComponent 后放路径，响应类型 blob 且跳过 Result 转换。
3. 第 110 行返回 response.data；第 111 行结束。

#### 3.1.3 Axios 实例与拦截器

文件：`frontend/src/api/request.ts:47-73、123-155、180-182`。

1. getInstance 第 180-182 行返回共享 instance。
2. createClientId/currentUserId 第 47-58 行提供 requestId/owner；请求拦截器第 64-73 行写两个头。
3. Blob 不满足带 code 的 JSON 条件，成功回调第 134 行原样返回；失败回调会解析服务返回的 JSON Blob 错误。

### 3.2 Java 函数

#### 3.2.1 `WebToolController.downloadArchive`

文件：`java-backend/src/main/java/com/interviewguide/web/controller/WebToolController.java:51-60`。

1. 第 51 行映射动态 token 路径；第 52-54 行绑定 token 和用户头。
2. 第 55 行调用 webToolService.archive；第 56-59 行以 document MIME、文件名和 byte[] 构造附件响应；第 60 行结束。

#### 3.2.2 `WebToolService.archive`

文件：`java-backend/src/main/java/com/interviewguide/web/service/WebToolService.java:52-54`。

1. 第 52 行声明返回 DownloadedDocument；第 53 行原样委托 previews.archive(userId,token)；第 54 行结束。

#### 3.2.3 `WebCrawlPreviewService.archive`

文件：`java-backend/src/main/java/com/interviewguide/web/service/WebCrawlPreviewService.java:120-125`。

1. 第 121 行 requireOwned 获取授权预览。
2. 第 122-124 行构造固定文件名/MIME，把 preview.archiveMarkdown 用 UTF-8 编成 byte[]；第 125 行结束。

#### 3.2.4 `WebCrawlPreviewService.requireOwned`

文件：`WebCrawlPreviewService.java:127-141`。

1. 第 128 行 identity.require；第 129-131 行 token 空白抛 PREVIEW_REQUIRED。
2. 第 132 行 previews.get；第 133-136 行不存在或过期时条件移除并抛 PREVIEW_EXPIRED。
3. 第 137-139 行 owner 不符抛 ACCESS_DENIED；第 140 行返回 Preview；第 141 行结束。

#### 3.2.5 `UserIdentityResolver.require` 与 `DownloadedDocument`

文件：`common/security/UserIdentityResolver.java:14-19`；`knowledgebase/service/KnowledgeBaseService.java:28`。

1. require 第 15-17 行拒绝 null/blank，第 18 行 strip，第 19 行返回 owner。
2. DownloadedDocument record 由编译器生成 filename/contentType/content 访问器；Controller 用其构造响应。

### 3.3 Python 与存储边界

1. archive 从 Java `ConcurrentHashMap<String,Preview>` 读取，未访问文件系统、数据库或 Python。
2. Java 进程重启、TTL 到期或多实例请求落到无该 token 的实例时返回 EXPIRED，这是内存预览设计的实际边界。
3. 调用链不含 PythonAgentClient、crawlWeb、RAG 或 `/v1/**`，Java→Python 次数为零。

## 4. 审核结论

1. 已覆盖前端 Blob 下载、token 编码、Java token/TTL/owner 校验和 UTF-8 附件构造。
2. 所有可达项目函数均标明文件/行号并逐句解释；确认不调用 Python。
