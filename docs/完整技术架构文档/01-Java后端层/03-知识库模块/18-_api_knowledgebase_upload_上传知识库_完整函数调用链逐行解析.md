# POST /api/knowledgebase/upload：上传知识库并异步向量索引的完整函数调用链

## 1. 接口定义

接口接收 multipart 文档和可选名称、分类、网页来源元数据。Java 同步解析文本、保存原始字节和数据库实体，并向 RabbitMQ 投递索引任务后返回；消费者异步调用 Python `/v1/agent/rag/index`。Python 分块、批量生成 embedding、替换该知识库的向量记录，Java 再把向量数量和 COMPLETED 状态写回。

| 项目 | 内容 |
| --- | --- |
| 方法/路径 | POST `/api/knowledgebase/upload` |
| 请求 | multipart/form-data；必填 file，其余可选 |
| 同步返回 | knowledgeBase 基本字段；投递成功不等于向量已完成 |
| Python | Rabbit 消费后 POST `/v1/agent/rag/index` |
| 初始/过程状态 | PENDING → PROCESSING → COMPLETED 或 FAILED |

## 2. 函数调用链

~~~text
KnowledgeBaseUploadPage.handleUpload
 -> knowledgeBaseApi.uploadKnowledgeBase → request.upload → Axios 拦截器
 -> RequestIdFilter → SimpleRateLimitFilter → KnowledgeBaseController.upload
 -> KnowledgeBaseService.upload
    -> UserIdentityResolver.require → isPlainTextDocument
    -> persistDocument → BusinessIdGenerator.next
       -> KnowledgeBaseEntity 构造/attachOriginalBytes/attachWebSource
       -> Repository.save → KnowledgeBaseIndexWorker.index → RabbitTemplate.convertAndSend
       -> KnowledgeBaseService.toView
 -> ApiResult.success（HTTP 返回）

RabbitAgentWorkConsumer.consume
 -> KnowledgeBaseIndexWorker.process
    -> Repository.findById/getOwnerId/hasDeletionRequest
    -> Persistence.markIndexing
    -> HttpPythonAgentClient.indexRag → AgentCallExecutor.execute → post/validateRequest
    -> Python index_rag → _remember_request_context → _resolve_rag_service
       -> RagService.index_document
          -> TokenChunker.split → _lock_for
          -> EmbeddingProvider.embed_documents（分批）
          -> VectorRepository.replace_for_knowledge_base
          -> invalidate_cache
    -> Persistence.markIndexed 或 markIndexFailed
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `KnowledgeBaseUploadPage.handleUpload`

文件：`frontend/src/pages/KnowledgeBaseUploadPage.tsx:21-33`。

1. 第 21 行接收 File 和可选 name；第 22-23 行进入上传状态并清错误。
2. 第 25-27 行 await uploadKnowledgeBase，成功后调用父组件 onUploadComplete。
3. 第 28-31 行捕获错误、选择 Error.message/默认文案，并恢复 uploading=false；成功路径由父组件导航离开页面。

#### 3.1.2 `knowledgeBaseApi.uploadKnowledgeBase`

文件：`frontend/src/api/knowledgebase.ts:77-89`。

1. 第 77 行定义文件、名称、分类和可选网页来源参数；第 78 行创建 FormData。
2. 第 79 行追加 file；第 80-81 行仅在有值时追加 name/category。
3. 第 82-87 行有网页来源时逐项追加 URL、标题、抓取时间和 hash。
4. 第 88 行调用 `request.upload('/api/knowledgebase/upload',formData)`；第 89 行结束。

#### 3.1.3 `request.upload` 与拦截器

文件：`frontend/src/api/request.ts:47-73、123-155、173-179`。

1. upload 第 173 行声明函数；第 174-178 行 POST FormData、设 300000ms 与 multipart Content-Type，并允许调用方覆盖配置；随后取 response.data。
2. createClientId/currentUserId 第 47-58 行生成请求 ID、读取/保存 owner；请求拦截器第 64-73 行写 X-User-Id 和 X-Request-Id。
3. 成功拦截器第 123-135 行解包 ApiResult；失败回调第 136-155 行将 Blob JSON、HTTP 或网络错误转为 ApiRequestError。

### 3.2 Java 同步上传函数

#### 3.2.1 `KnowledgeBaseController.upload`

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/controller/KnowledgeBaseController.java:35-55`。

1. 第 35 行声明 POST /upload 与 multipart；第 36-44 行绑定 file、可选字段和用户头。
2. 第 45 行 fetchedAt 初始 null；第 46-49 行非空时 Instant.parse，非法时间被忽略，因为来源可选。
3. 第 50-51 行调用 service.upload；第 52-55 行以 view 和 file.getSize 构造成功 Map。

#### 3.2.2 `KnowledgeBaseService.upload`

文件：`KnowledgeBaseService.java:57-87`。

1. 第 59 行 identity.require；第 60-62 行拒绝 null/空文件。
2. 第 63-66 行取原始文件名并拒绝空值。
3. 第 67-78 行按 isPlainTextDocument 分支：文本/Markdown 直接 UTF-8 解码，二进制办公/PDF 交 Tika；异常转 KNOWLEDGE_BASE_PARSE_FAILED。
4. 第 79-81 行拒绝空文本；第 82 行解析显示名；第 83 行读取原始 bytes。
5. 第 84-86 行把所有字段交 persistDocument；第 87 行结束。

#### 3.2.3 `isPlainTextDocument` 与 `persistDocument`

文件：`KnowledgeBaseService.java:104-125、235-241`。

1. isPlainTextDocument 第 235-241 行把 filename/contentType 小写，匹配 `.txt/.md/.markdown` 或 text/plain、text/markdown。
2. persistDocument 第 107 行生成 ID；第 108-113 行构造实体；第 114 行 attachOriginalBytes。
3. 第 115-117 行来源 URL 非空时 strip 并 attachWebSource；第 118 行 repository.save。
4. 第 119-123 行调用 indexWorker.index；投递异常时 markIndexFailed 并重抛。
5. 第 125 行 toView 返回 PENDING 实体投影。

#### 3.2.4 实体、ID 与 `toView`

文件：`KnowledgeBaseEntity.java:55-116`；`BusinessIdGenerator.java:13-16`；`KnowledgeBaseService.java:225-233`。

1. BusinessIdGenerator.next 用 AtomicLong 取当前毫秒/旧值+1较大者并转 String。
2. KnowledgeBaseEntity 构造保存 ID、owner、名称、分类、文件元数据、正文，初始化 vectorStatus=PENDING 和时间；attachOriginalBytes 第 112 行写 byte[]；attachWebSource 第 113-116 行写四个来源字段。
3. toView 第 225-233 行逐个读取实体 ID、名称、分类、文件、内容类型/大小、vectorStatus/vectorCount/error、来源和时间，构造 KnowledgeBaseView。

#### 3.2.5 `KnowledgeBaseIndexWorker.index`

文件：`KnowledgeBaseIndexWorker.java:38-42`。

1. 第 38 行接收知识库 ID/用户；第 39-41 行选择共享 exchange/routing key，构造 taskType=KNOWLEDGE_BASE_INDEX 的 AgentWorkTaskMessage并 convertAndSend；第 42 行结束。
2. 此函数只投递 ID，不把正文塞进消息；浏览器在消息入队后即可收到 HTTP 成功。

### 3.3 Rabbit 消费与 Java Worker

#### 3.3.1 `RabbitAgentWorkConsumer.consume`

文件：`infrastructure/messaging/RabbitAgentWorkConsumer.java:22-39`。

1. 第 24-27 行拒绝 null 或缺 taskType/resourceId/userId 的消息。
2. 第 29-35 行 switch；KNOWLEDGE_BASE_INDEX 分支调用 worker.process(resourceId,userId)。
3. 第 36-38 行只捕获无法转数字的 resume 资源；知识库 ID 作为字符串直接使用。

#### 3.3.2 `KnowledgeBaseIndexWorker.process`

文件：`KnowledgeBaseIndexWorker.java:44-100`。

1. 第 45-49 行查询实体；已删除 return。第 50-52 行 owner 不符抛访问异常；第 53-55 行有删除请求 return。
2. 第 56-58 行 markIndexing 返回 false 时结束，避免重复/非法状态执行。
3. 第 60-64 行构造 AgentRagIndexRequest，携带正文、唯一 KB ID、documentId、原始文件名和时间。
4. 第 65-72 行处理非成功 Python 响应：markIndexFailed；只有 response.retryable 时抛 PythonAgentException 触发 Rabbit 重试。
5. 第 74-84 行 Python 成功后重新查询；若上传后被删除，则调用 deleteRag 补偿清理晚到向量。
6. 第 85 行把 response.answer 解析为 chunk 数并 markIndexed。
7. 第 86-99 行异常时仅对仍存在且未删除实体 markIndexFailed；业务/不可重试网关错误被消费确认，其他异常重抛给 Rabbit。

#### 3.3.3 `KnowledgeBasePersistenceService` 状态函数

文件：`KnowledgeBasePersistenceService.java:18-38`。

1. markIndexed 第 19-23 行 required 后把实体标 COMPLETED 与 count。
2. markIndexing 第 29-32 行 required，调用实体 markIndexing 并返回是否成功进入 PROCESSING。
3. markIndexFailed 第 34-38 行 required 后写 FAILED/error；每个修改函数均在事务中由 JPA dirty checking 提交。

### 3.4 Java HTTP 到 Python RAG

#### 3.4.1 `HttpPythonAgentClient.indexRag`、`post`、重试

文件：`HttpPythonAgentClient.java:48、65-96`；`AgentCallExecutor.java:22-43`。

1. indexRag 第 48 行用 callExecutor 执行固定 `/v1/agent/rag/index`。
2. post 第 66 行 validateRequest；第 68-79 行发送、拒绝空 body、解析结构化错误或包装 HTTP/网络异常。
3. validateRequest 第 89-95 行收集约束字段；execute 仅对可重试 PythonAgentException 按次数和 backoff 重试。

#### 3.4.2 Python `index_rag` 与 `_resolve_rag_service`

文件：`python-agent/app/api/application.py:223-238、331-337`。

1. 路由第 225 行保存上下文；第 226-231 行构造 KnowledgeDocument 并 await RagService.index_document。
2. 第 232-238 行构造 code=100 AgentResponse，answer 为 chunk count 字符串。
3. _resolve_rag_service 第 332-337 行从 app.state 读取；为空时 build_rag_service 并缓存。

#### 3.4.3 `RagService.index_document`、`_lock_for`、`invalidate_cache`

文件：`python-agent/app/rag/service.py:35-54、128-136`。

1. 第 36 行 TokenChunker.split；第 37 行按 knowledge_base_id 获取 asyncio.Lock，防止同一 KB 并发替换。
2. 第 39-47 行按 policy.embedding_batch_size 分批 embed_documents，校验向量数量并赋给每个 chunk。
3. 第 48 行 repository.replace_for_knowledge_base 原子替换；第 49-52 行非 RagDependencyError 统一包装为 RAG embedding 失败。
4. 第 53 行 invalidate_cache 清搜索缓存；第 54 行返回 chunk 数。
5. _lock_for 第 131-136 行从字典取锁，不存在则创建/缓存并返回。

#### 3.4.4 `TokenChunker.split`、Embedding 与向量仓储

文件：`rag/parser.py:54-80`；`rag/embedding.py:41-47`；`rag/repository.py:47-78`。

1. TokenChunker.split 按 token 近似切正文，使用 chunk_size/overlap 生成带序号、内容和 metadata 的 KnowledgeChunk，并拒绝空结果。
2. embed_documents 第 41-47 行无 retry executor 时直接 await 客户端，有执行器时把 aembed_documents 包为异步 operation 交重试器。
3. PostgresVectorRepository.replace_for_knowledge_base 先删除该 KB 旧 chunks，再批量 add 新实体并 commit；失败时事务回滚，避免旧新版本混杂。

## 4. 审核结论

1. 已覆盖同步上传与异步索引两个真实阶段，并明确 HTTP 成功不代表 Python 向量完成。
2. 已覆盖前端、Java 解析/持久化、Rabbit 消费、Java-Python HTTP、Python 分块/embedding/向量替换及状态写回。
3. 每个可达项目函数均标注文件和行号，删除竞态和失败重试分支亦已写入。
