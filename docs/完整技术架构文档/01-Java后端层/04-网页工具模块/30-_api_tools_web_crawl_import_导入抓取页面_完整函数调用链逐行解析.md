# POST /api/tools/web/crawl/import：导入抓取页面并异步索引的完整函数调用链

## 1. 接口定义

接口用 previewToken 和选中 page ID，把 Java 内存预览中的页面逐个保存为 Markdown 知识库。导入函数不直接 HTTP 调 Python，但 `KnowledgeBaseService.uploadMarkdown` 为每个新页面投递 RabbitMQ，消费者最终调用 Python `/v1/agent/rag/index`。同一 preview/page 重试会返回已保存 ImportedPage，不重复创建。

| 项目 | 内容 |
| --- | --- |
| 方法/路径 | POST `/api/tools/web/crawl/import` |
| 请求 | previewToken、selectedPageIds、可选 category |
| 同步返回 | importedCount、knowledgeBases（初始通常 PENDING） |
| Python | 每个新页面异步进入 `/v1/agent/rag/index` |
| 幂等范围 | 同一内存 Preview 生命周期内按 page.id 去重 |

## 2. 函数调用链

~~~text
KnowledgeBaseUploadPage.handleImportCrawl → knowledgeBaseApi.importWebCrawl → request.post
 -> Axios/Filter → WebToolController.importCrawl → WebToolService.importCrawl
 -> WebCrawlPreviewService.importSelected
    -> requireOwned → UserIdentityResolver.require
    -> KnowledgeBaseService.uploadMarkdown（每个首次页面）
       -> persistDocument → Entity/Repository.save → KnowledgeBaseIndexWorker.index
    -> ImportedPage 缓存/返回
 -> ApiResult.success（HTTP 返回）

RabbitAgentWorkConsumer.consume → KnowledgeBaseIndexWorker.process
 -> HttpPythonAgentClient.indexRag → Python index_rag
 -> RagService.index_document → 分块/embedding/向量替换
 -> Persistence.markIndexed/markIndexFailed
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `KnowledgeBaseUploadPage.handleImportCrawl`

文件：`frontend/src/pages/KnowledgeBaseUploadPage.tsx:96-110`。

1. 第 97 行无 crawlPreview return；第 98 行按 selectedPages Set 过滤；第 99 行无选择 return。
2. 第 100 行进入加载/清错误；第 102-104 行传 previewToken 和 page.id 数组。
3. 第 105-106 行取首条并构造 UploadKnowledgeBaseResponse，名称显示导入数量，contentLength 汇总选中页面字符数，调用 onUploadComplete。
4. 第 107-109 行错误写 UI，finally 清加载。

#### 3.1.2 `knowledgeBaseApi.importWebCrawl`

文件：`frontend/src/api/knowledgebase.ts:99-103`。

1. 第 99 行声明参数和返回；第 100-102 行 POST 三字段，timeout=300000ms；第 103 行结束。
2. request.post 与身份/响应拦截器位于 `api/request.ts:47-73、123-163`。

### 3.2 Java 导入函数

#### 3.2.1 `WebToolController.importCrawl`、`WebToolService.importCrawl`

文件：`WebToolController.java:42-49`；`WebToolService.java:47-50`。

1. Controller 第 42 行映射；第 43-45 行 @Valid 绑定 CrawlImportRequest 和用户头。
2. 第 46-47 行调用 Service；第 48 行返回 importedCount/knowledgeBases；第 49 行结束。
3. Service 第 47-50 行不变更参数，直接委托 previews.importSelected。

#### 3.2.2 `WebCrawlPreviewService.requireOwned`

文件：`WebCrawlPreviewService.java:127-141`。

1. 第 128 行 identity.require；第 129-131 行拒绝空 token。
2. 第 132 行从 ConcurrentHashMap 取 preview；第 133-136 行不存在/过期时移除并抛 EXPIRED。
3. 第 137-139 行 owner 不匹配抛 ACCESS_DENIED；第 140 行返回。

#### 3.2.3 `WebCrawlPreviewService.importSelected`

文件：`WebCrawlPreviewService.java:86-118`。

1. 第 88 行 requireOwned；第 89-91 行选择为空抛 SELECTION_REQUIRED。
2. 第 92-95 行 Set.copyOf 并要求无重复；第 96-99 行从预览匹配页面，数量不符表示未知 ID。
3. 第 100 行创建结果；第 101 行 synchronized(preview)，序列化同一 token 的并发导入。
4. 第 102-107 行每页先查 importedPages；存在则加入旧结果并 continue。
5. 第 108-110 行调用 uploadMarkdown，传文件名、标题、分类、preview owner、带来源 front matter 的 Markdown、URL/标题/时间/hash。
6. 第 111-115 行构造 ImportedPage，写 preview.importedPages 并加入结果；第 116-118 行退出锁并返回。

#### 3.2.4 `KnowledgeBaseService.uploadMarkdown` 与 `persistDocument`

文件：`KnowledgeBaseService.java:89-125`。

1. uploadMarkdown 第 91 行 require owner；第 92-97 行拒绝空文件名/Markdown。
2. 第 98 行 UTF-8 bytes；第 99 行解析显示名；第 100-101 行以 text/markdown 委托 persistDocument。
3. persistDocument 第 107-118 行生成 ID、构造实体、附原始 bytes/网页来源并 save。
4. 第 119-123 行 indexWorker.index；投递失败 markIndexFailed并重抛；第 125 行 toView。

### 3.3 异步 Python 索引函数

#### 3.3.1 `KnowledgeBaseIndexWorker.index/process`

文件：`KnowledgeBaseIndexWorker.java:38-100`；`RabbitAgentWorkConsumer.java:22-39`。

1. index 第 39-41 行投递 KNOWLEDGE_BASE_INDEX 消息；浏览器同步响应不等待 process。
2. consume 验证消息并进入 process；process 查实体/owner/删除状态，markIndexing，构造 AgentRagIndexRequest。
3. Python 非成功时 markIndexFailed；成功后检查删除竞态并 markIndexed(chunk count)；临时异常重抛触发 Rabbit 重试。

#### 3.3.2 Java-Python HTTP 与 Python RAG

文件：`HttpPythonAgentClient.java:48、65-96`；`python-agent/app/api/application.py:223-238`；`rag/service.py:35-54`。

1. Java indexRag 调 `/v1/agent/rag/index`，post 校验/发送/解析并有限重试。
2. Python index_rag 构造 KnowledgeDocument，调用 RagService.index_document，并在 answer 返回 chunk 数。
3. index_document 用 TokenChunker.split、KB 锁、分批 embed_documents、`replace_for_knowledge_base` 与 invalidate_cache 完成向量替换。

### 3.4 幂等与边界

1. importedPages 的去重只在 Java 进程内 Preview 和 TTL 存续期间有效；服务重启会丢预览，旧 token 无法继续导入。
2. 第一次知识库保存成功但 Rabbit 投递失败时 uploadMarkdown 抛异常且实体标 FAILED；由于 importedPages 尚未写入，重试可能创建新实体，这是源码真实失败边界。
3. 导入只接受预览中 page ID，不能由客户端提交任意 Markdown。

## 4. 审核结论

1. 已覆盖预览授权、选择校验、页级幂等、Markdown 知识库持久化与异步 Python 向量链。
2. 每个可达项目函数均注明文件/行号并逐句解释；同步返回与异步索引边界已明确。
