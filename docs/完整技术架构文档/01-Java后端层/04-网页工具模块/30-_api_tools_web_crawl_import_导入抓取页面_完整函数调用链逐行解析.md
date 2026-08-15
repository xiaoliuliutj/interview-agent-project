# POST /api/tools/web/crawl/import：导入抓取页面的完整函数调用链逐行解析

## 1. 接口定义

接口把已由 `/crawl` 创建的、仍在有效期且属于当前用户的预览页面导入知识库。它不重新抓取网页：每页 Markdown、来源 URL、标题、抓取时间、哈希来自预览；Java 同步写入 PostgreSQL，并发布 RabbitMQ 向量化任务。消费者再调用 Python `/v1/agent/rag/index` 完成 RAG 索引。

| 项目 | 实现 |
| --- | --- |
| 前端入口 | `KnowledgeBaseUploadPage.handleImportCrawl`（`frontend/src/pages/KnowledgeBaseUploadPage.tsx:96-113`） |
| Java 路径 | `POST /api/tools/web/crawl/import`（`WebToolController.java:42-49`） |
| 请求体 | `previewToken`、1-20 个唯一 `selectedPageIds`、可选 category（最长 100） |
| 预览存储 | Java 进程内 `ConcurrentHashMap`，owner 隔离，TTL 30 分钟 |
| 异步下游 | RabbitMQ `interview.agent.work.execute` → Java consumer → Python `/v1/agent/rag/index` |

## 2. 函数调用链

```text
handleImportCrawl → knowledgeBaseApi.importWebCrawl → request.post
 → WebToolController.importCrawl → WebToolService.importCrawl
 → WebCrawlPreviewService.importSelected → requireOwned
 → KnowledgeBaseService.uploadMarkdown → persistDocument
 → BusinessIdGenerator.next → KnowledgeBaseEntity 构造/attachOriginalBytes/attachWebSource
 → KnowledgeBaseRepository.save/upsert（MyBatis）
 → KnowledgeBaseIndexWorker.index → RabbitTemplate.convertAndSend
 → RabbitAgentWorkConsumer.consume → KnowledgeBaseIndexWorker.process
 → KnowledgeBasePersistenceService.markIndexing
 → HttpPythonAgentClient.indexRag → AgentCallExecutor.execute → post/validateRequest
 → Python index_rag → _remember_request_context → _resolve_rag_service → RagService.index_document
 → KnowledgeBasePersistenceService.markIndexed → JavaTaskStatusCache.updateKnowledgeBaseIndex
 → WebCrawlPreviewService.ImportedPage → ApiResult.success → onUploadComplete
```

## 3. 函数解析

### 3.1 前端函数

#### `KnowledgeBaseUploadPage.handleImportCrawl`

文件：`frontend/src/pages/KnowledgeBaseUploadPage.tsx:96-113`。

1. 第 96 行声明异步导入函数；第 97 行没有 crawl 预览即返回。
2. 第 98 行以 `selectedPages` 过滤预览页；第 99 行没有选择任何页即返回。
3. 第 100 行开启加载并清空错误；第 101-104 行把 previewToken 和每页 id 传给 `importWebCrawl`。
4. 第 105 行取得第一条导入结果；第 106 行调用外部传入的 `onUploadComplete`。它构造兼容上传页回调的对象：第一条知识库 ID、汇总名称、空分类和所有页面 `characterCount` 的和。
5. 第 107-110 行捕获异常并写入错误，finally 关闭 loading。

#### `knowledgeBaseApi.importWebCrawl` 与 `request.post`

文件：`frontend/src/api/knowledgebase.ts:99-103`、`frontend/src/api/request.ts:47-73、123-163`。

1. 第 99 行声明参数；第 100-102 行 POST 导入路径，请求体逐字保留 token、id 数组、category，并设置 300 秒超时。
2. `request.post`（161-163）把 Axios 响应 data 返回；请求拦截器（64-73）调用 `currentUserId`（52-58）和 `createClientId`（47-50）写入用户与请求头。
3. 响应拦截器（123-155）只对 `ApiResult.code=200` 返回 data；其余通过 `parseApiError`（83-98）、`decodeErrorData`（101-108）、`transportError`（110-121）构造可展示异常。

### 3.2 Java 预览导入函数

#### `WebToolController.importCrawl`、`WebToolService.importCrawl`

文件：`WebToolController.java:42-49`、`WebToolService.java:47-50`。

1. 控制器第 42 行绑定 `/crawl/import`；43-45 行用 `@Valid` 接收 body 与用户头。`CrawlImportRequest`（75-78）要求 token 非空、id 列表 1-20 且元素非空、category 不超过 100。
2. 第 46-47 行调用服务，传递 token、页面 ID、category；第 48 行把导入数和 `ImportedPage` 列表放入不可变 Map，再由 `ApiResult.success` 返回。
3. 服务第 47-50 行不访问 Python；它只把四个参数转交给预览服务，保证导入只能使用先前保存的抓取结果。

#### `WebCrawlPreviewService.importSelected`、`requireOwned` 及解析辅助函数

文件：`WebCrawlPreviewService.java:86-118、127-205`。

1. `importSelected` 第 88 行先 `requireOwned`。该函数第 127-141 行先 `identity.require`，检查 token 非空、查询 map、判断 30 分钟是否过期（过期时原子删除），再比较 ownerId；分别抛出 REQUIRED、EXPIRED、ACCESS_DENIED 业务错误。
2. 第 89-95 行拒绝空选择和重复 ID；第 96-99 行从 preview.pages 过滤选择项，数量不一致说明包含未知页面。
3. 第 100 行创建返回列表；第 101 行以 preview 为锁，避免并发导入同一页产生重复知识库。
4. 第 102-107 行遍历页：已有 `importedPages` 缓存则复用；否则第 108-110 行调用 `knowledgeBaseService.uploadMarkdown`，传入安全文件名、标题、分类、预览 owner、Markdown、来源 URL/标题、`parseInstant` 结果和内容哈希。
5. `parseInstant`（189-192）用 `Instant.parse` 解析时间，格式异常返回 null；第 111-114 行从视图创建 ImportedPage、写入 ConcurrentHashMap 与结果列表；第 117 行返回。
6. 预览此前由 `save` 创建：`parsePages`（148-161）把 Python 页面逐项映射并生成 `page-N`；`safeFilename`（184-187）替换 Windows 非法字符并截断 180；`stringValue`/`nullableString`/`intValue`（194-205）把动态 Map 值安全转换。

### 3.3 Java 持久化与消息发布

#### `KnowledgeBaseService.uploadMarkdown`、`persistDocument`

文件：`KnowledgeBaseService.java:93-126`。

1. `uploadMarkdown` 第 95 行先校验 owner；第 96-101 行拒绝空文件名和空 Markdown；第 102 行把 Markdown 编码为 UTF-8 原始字节；第 103 行决定显示名称；第 104-105 行以 `text/markdown` 调用共享持久化函数。
2. `persistDocument` 第 108-123 行用 `idGenerator.next` 生成业务 ID，构造 `KnowledgeBaseEntity`（初始 PENDING 和创建时间），附加原始字节；若 sourceUrl 有值则调用 `attachWebSource` 保存网页溯源字段。
3. 第 120 行 `repository.save` 调用 MyBatis `upsert`（`KnowledgeBaseRepository.java:11-13`），将实体写入数据库；该接口不是 JPA Repository。
4. 第 122 行调用 `indexWorker.index` 发布异步任务；第 123-126 行若发布过程抛 RuntimeException，先 `persistence.markIndexFailed` 再继续抛出，否则 `toView` 组装响应。

#### `KnowledgeBaseIndexWorker.index` 和 RabbitMQ 消费

文件：`KnowledgeBaseIndexWorker.java:28-110`；`RabbitAgentWorkConsumer.java:16-39`。

1. `index` 第 39-42 行用 `RabbitTemplate.convertAndSend` 向配置的 exchange、`agent.work.execute` routing key 发布 `KNOWLEDGE_BASE_INDEX` 消息，消息携带知识库 ID 和 owner。发布方是 Java Web 服务，处理方也是 Java 的 Rabbit listener，而非 Python 直接消费 RabbitMQ。
2. `RabbitAgentWorkConsumer.consume` 根据消息 kind 分派；知识库分支调用 `knowledgeBaseIndexWorker.process(resourceId,userId)`。
3. `process` 第 45-61 行读取知识库；已删除直接 ack 返回；owner 不匹配抛业务异常；删除中返回；`persistence.markIndexing` 以状态转换防重复，未转换成功则返回。
4. 第 63-74 行构造 `AgentRagIndexRequest` 并调用 Python；空/非 1xx 响应标记 FAILED，若 `response.retryable()` 为真抛可重试网关异常以触发 Rabbit 监听器重试，否则返回确认消息。
5. 第 75-94 行成功后再次读取记录，若已删除则调用 Python deleteRag 清理迟到向量；正常则 `markIndexed(id, Integer.parseInt(response.answer()))`。
6. 第 95-110 行捕获运行时异常；仍存在且非删除中的记录写 FAILED。业务错误和不可重试 Python 错误吞掉（消息 ack），其他错误重新抛给 Rabbit 的重试/DLQ 策略。

### 3.4 Python RAG 调用结束点

文件：`python-agent/app/api/application.py:226-239`、`python-agent/app/rag/service.py:18-58`。

1. `index_rag` 第 227 行接收 AgentRagIndexRequest；第 228 行保存请求上下文；第 229-234 行经 `_resolve_rag_service` 取得/延迟创建 RAG 服务，构造 `KnowledgeDocument` 并等待 `index_document`。
2. 第 235-239 行返回 code 100、COMPLETED，answer 是切分后的 chunk 数。
3. `RagService.__init__` 第 18-36 行保存向量仓库、嵌入器、策略和可选 Python 专属 Redis，创建 `TokenChunker`，并初始化进程内检索缓存和按知识库 ID 的 asyncio 锁。
4. `index_document` 第 38-58 行先用 `_chunker.split` 切分文档；在 `_lock_for` 返回的每知识库锁内，按 `embedding_batch_size` 切 batch，调用 `embed_documents`，逐项校验向量数量并回写到 chunk，最后 `replace_for_knowledge_base` 原子替换该知识库向量。依赖异常原样抛出，其他异常包装为 `RagDependencyError`；释放锁后 `invalidate_cache` 清空进程内及 Python Redis 搜索缓存并返回 chunk 数。
5. `_lock_for`（`rag/service.py:124-129`）读取既有锁，不存在则创建、保存、返回，避免同一知识库并发索引相互覆盖。异常由 FastAPI ApplicationException/Exception handler 转为 AgentResponse 或 HTTP 错误，Java 再按上节规则决定重试/失败。

### 3.5 索引状态持久化

`KnowledgeBasePersistenceService.markIndexing`（`KnowledgeBasePersistenceService.java:34-43`）事务内读取实体、调用 `markVectorProcessing`、保存并在提交后刷新 Java 专属 Redis 状态。`markIndexed`（19-25）写 COMPLETED/chunkCount，`markIndexFailed`（46-52）截断错误至 500 字符并写 FAILED。`afterCommit`（78-88）仅在事务提交后调用 `JavaTaskStatusCache.updateKnowledgeBaseIndex`，没有事务同步时立即执行，避免缓存领先数据库。

## 4. 主流构建分析

更成熟的实现通常采用 Outbox Pattern：网页页面与“需要向量化”的事件在同一个数据库事务中写入，独立 publisher 投递 RabbitMQ，消费者按消息 ID 幂等执行。优点是消除“数据库成功但消息发布失败”或相反的双写窗口，并适合多实例；缺点是增加 outbox 表、轮询/CDC 发布器、清理策略和监控。

本项目现在是同步写知识库后立即发布 RabbitMQ，代码直观且适合低并发，但发布失败会使 API 失败而记录可能已落库。若需增强可靠性，可新增 `outbox_events` MyBatis Mapper，在 `persistDocument` 的事务里写 `KNOWLEDGE_BASE_INDEX` 事件；定时 publisher 成功后标记已发送，消费端用知识库 ID/消息 ID 去重，并保留现有 `markIndexing` 状态转换作为第二层幂等保护。
