# POST /api/knowledgebase/upload：上传知识库并异步向量化完整函数调用链逐行解析

> 当前实现同步解析并保存文档，然后经 RabbitMQ 异步调用 Python RAG 索引。HTTP 成功只表示知识库已保存且索引消息已投递，不代表向量化完成。

## 1. 接口定义

### 1.1 功能与作用

接口接收文件以及可选名称、分类、网页来源元数据，解析为文本，保存原始字节和知识库记录，随后创建 PENDING 索引任务并投递 RabbitMQ。消费端调用 Python `/v1/agent/rag/index`，将向量条数及状态回写 PostgreSQL/Java Redis。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 路径 | `POST /api/knowledgebase/upload`，multipart/form-data |
| Controller | `KnowledgeBaseController.upload`，`KnowledgeBaseController.java:35-56` |
| 必需字段 | `file`；name/category/source* 为可选 |
| Java 数据 | knowledge_bases（含原始 bytes、解析文本、来源和向量状态） |
| 异步路径 | RabbitMQ agent.work → Python `POST /v1/agent/rag/index` |
| Redis | Java 提交后缓存索引状态；Python 使用其专属 RAG 缓存/向量存储。|

### 1.3 前端入口

`knowledgeBaseApi.uploadKnowledgeBase` 位于 `frontend/src/api/knowledgebase.ts:77-89`，将文件和可选网页来源写为 FormData，调用通用 `request.upload`。

## 2. 函数调用链

```text
knowledgeBaseApi.uploadKnowledgeBase -> request.upload -> Axios interceptor
  -> RequestIdFilter -> SimpleRateLimitFilter -> IdempotencyFilter（可选）
  -> KnowledgeBaseController.upload -> Instant.parse（可选来源时间）
  -> KnowledgeBaseService.upload -> UserIdentityResolver.require
     -> isPlainTextDocument / Tika.parseToString -> persistDocument
        -> BusinessIdGenerator.next -> KnowledgeBaseEntity / attachOriginalBytes / attachWebSource
        -> KnowledgeBaseRepository.save (MyBatis)
        -> KnowledgeBaseIndexWorker.index -> RabbitTemplate.convertAndSend
     -> toView
  -> RabbitAgentWorkConsumer.consume -> KnowledgeBaseIndexWorker.process
     -> KnowledgeBasePersistenceService.markIndexing
     -> HttpPythonAgentClient.indexRag -> AgentCallExecutor -> Python rag_index
     -> markIndexed 或 markIndexFailed / retry
  -> ApiResult.success
```

## 3. 函数解析

### 3.1 前端、过滤器与 Controller 函数

#### 3.1.1 `knowledgeBaseApi.uploadKnowledgeBase` 与 `request.upload`

**文件与行号：** `frontend/src/api/knowledgebase.ts:77-89`，`frontend/src/api/request.ts:47-72、173-178`。

1. 第 78 行创建 FormData；第 79 行写必需 file；第 80-81 行仅在非空时写 name/category。
2. 第 82-87 行有网页来源时写 URL、标题、抓取时间、内容哈希；第 88 行调用 300 秒超时的 `request.upload`；第 89 行结束。
3. `createClientId`/`currentUserId` 在 request.ts 第 47-57 行生成身份；拦截器第 64-72 行写头；`upload` 第 173-178 行 POST multipart、设置 timeout 并返回解包 data。

#### 3.1.2 RequestId、限流、幂等与 `KnowledgeBaseController.upload`

**文件与行号：** `RequestIdFilter.java:23-41`、`SimpleRateLimitFilter.java:48-82`、`IdempotencyFilter.java:41-96`，目录 `java-backend/src/main/java/com/interviewguide/infrastructure/`；`KnowledgeBaseController.java:35-56`。

1. RequestId filter 规范/回传 ID、写 MDC；限流用 Redis 固定窗口、故障回退本机；POST 带幂等键才被 Idempotency filter 占位。
2. Controller 第 35 行声明 multipart POST；第 36-44 行绑定文件、名称、分类、来源和用户头。
3. 第 45 行初始化 fetchedAt；第 46-49 行仅对非空来源时间调用 `Instant.parse`，格式异常忽略，因来源信息可选。第 50-51 行委托 Service。
4. 第 52-55 行构造只含基础信息的返回 Map：类别 null 转空串、内容长度取上传文件大小；第 56 行结束。

### 3.2 Java 解析、保存、投递函数

#### 3.2.1 `KnowledgeBaseService.upload` 与 `uploadMarkdown`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/knowledgebase/service/KnowledgeBaseService.java:57-105`。

1. 四参数 upload 第 57-59 行是兼容委托，补齐 null 来源后调用主 overload。
2. 主 upload 第 63 行 require owner；第 64-70 行拒绝空文件/文件名。第 71-82 行解析：`isPlainTextDocument` 判定文本/Markdown 时第 78 行 UTF-8 直接读取，否则第 79 行 Tika 解析；异常转 `KNOWLEDGE_BASE_PARSE_FAILED`。
3. 第 83-85 行拒绝空文本；第 86 行决定显示名称；第 87 行取原字节；第 88-90 行调用 `persistDocument`。
4. `uploadMarkdown` 第 93-105 行执行同等 owner、filename、markdown 校验，UTF-8 编码原文、设 text/markdown，然后调用同一持久化函数。

#### 3.2.2 `persistDocument`、实体与 MyBatis 保存

**文件与行号：** `KnowledgeBaseService.java:108-130`。

1. 第 111 行 `idGenerator.next` 创建业务 ID。第 112-117 行构造实体，写 owner、名称、类别、原文件名、大小、类型和解析文本。
2. 第 118 行调用实体 `attachOriginalBytes` 保存下载用二进制。第 119-121 行只有 sourceUrl 非空时调用 `attachWebSource`。
3. 第 122 行 MyBatis `repository.save` 插入记录。第 123-128 行调用 index worker 投递；投递失败第 126 行持久化 FAILED 后重抛。第 129 行 `toView` 转换；第 130 行结束。

#### 3.2.3 `KnowledgeBaseIndexWorker.index`、消费与 Python 索引

**文件与行号：** `KnowledgeBaseIndexWorker.java:39-112`，`infrastructure/messaging/RabbitAgentWorkConsumer.java:22-39`。

1. `index` 第 40-42 行构造 KNOWLEDGE_BASE_INDEX 消息并 RabbitTemplate 发送。
2. 消费者按任务类型转到 `process`。`process` 第 46-59 行读取记录、验证 owner、跳过删除中/已删文档；第 60-62 行 `markIndexing` 原子转状态，失败即返回。
3. 第 64-68 行构造 `AgentRagIndexRequest`，带 runId、文本、知识库 ID、文件名；第 69-76 行非成功响应标记 FAILED，retryable 才抛给 Rabbit 重试。
4. 第 78-94 行索引期间若被删除则调用 Python deleteRag 清理，否则 `markIndexed` 写向量条数。第 95-110 行捕获异常，非业务/临时 Python 异常才继续抛出以触发 MQ 重试。

#### 3.2.4 Python RAG 索引和状态持久化

**文件与行号：** `python-agent/app/api/application.py` 的 `/v1/agent/rag/index` 路由，`KnowledgeBasePersistenceService.java:24-101`。

1. Python 路由接收索引请求，调用 RAG service 将文本切块、嵌入并写 pgvector/索引；完成后回显成功 code 和切块数。
2. `markIndexing`、`markIndexed`、`markIndexFailed` 在 PersistenceService 中事务更新 vectorStatus、计数/错误，并通过 `cacheAfterCommit` 在数据库提交后刷新 `JavaTaskStatusCache`；Redis 故障不改变数据库事实。

## 4. 主流构建分析

当前“同步保存 + MQ 异步索引”适合大文件/模型索引，优点是上传不等待向量化、失败可持久化和重试；缺点是数据库保存与直接 MQ 发送有双写窗口，用户需轮询状态。

主流改进为 Transactional Outbox：同一事务写知识库与 outbox，独立发布器发送消息，消费者使用 Inbox 去重。优点是可靠投递和审计；缺点是新增表、CDC/轮询和运维复杂度。

本项目已具备任务状态和 afterCommit Redis 刷新，适合在索引量上升时引入 Outbox。实现时让 `persistDocument` 写 outbox 而不直接 `index`，为消息加 ID，消费者记录 processed ID，保持删除竞态检查和 PostgreSQL 最终事实来源。
