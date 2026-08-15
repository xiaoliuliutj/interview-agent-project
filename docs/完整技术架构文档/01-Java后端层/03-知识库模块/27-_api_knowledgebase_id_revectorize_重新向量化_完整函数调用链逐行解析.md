# POST /api/knowledgebase/{id}/revectorize：重新向量化知识库的完整函数调用链

## 1. 接口定义

接口把指定知识库重新置为 PENDING 并投递 RabbitMQ。HTTP 成功只表示任务已入队；消费者随后调用 Python `/v1/agent/rag/index`，Python 重新分块/生成 embedding 并替换该 KB 全部旧向量，Java 最终写 COMPLETED/FAILED。

| 项目 | 内容 |
| --- | --- |
| 方法/路径 | POST `/api/knowledgebase/{id}/revectorize` |
| 同步返回 | `ApiResult<Void>` |
| Python | 异步 POST `/v1/agent/rag/index` |
| 禁止状态 | DELETING/DELETE_FAILED 删除请求状态 |

## 2. 函数调用链

~~~text
KnowledgeBaseManagePage.handleRevectorize → knowledgeBaseApi.revectorize → request.post
 -> Axios/Filter → KnowledgeBaseController.revectorize
 -> KnowledgeBaseService.revectorize → required → hasDeletionRequest
    -> Persistence.markIndexPending → Entity.markIndexPending
    -> KnowledgeBaseIndexWorker.index → RabbitTemplate.convertAndSend
 -> ApiResult.success（HTTP 返回）
 -> RabbitAgentWorkConsumer.consume → KnowledgeBaseIndexWorker.process
 -> Persistence.markIndexing → HttpPythonAgentClient.indexRag
 -> Python index_rag → RagService.index_document
    -> TokenChunker.split → EmbeddingProvider.embed_documents
    -> VectorRepository.replace_for_knowledge_base → invalidate_cache
 -> Persistence.markIndexed/markIndexFailed
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 页面重新向量化函数

文件：`frontend/src/pages/KnowledgeBaseManagePage.tsx:201-213`。

1. 函数接收 ID，设置对应操作状态并清错误。
2. 第 206 行 await `knowledgeBaseApi.revectorize(id)`；成功后 await loadData 刷新为 PENDING/PROCESSING。
3. catch 写错误，finally 清操作状态；前端不等待 Python COMPLETED。

#### 3.1.2 `knowledgeBaseApi.revectorize`、`request.post`

文件：`frontend/src/api/knowledgebase.ts:153-155`；`api/request.ts:47-73、123-163`。

1. 第 153 行定义函数；第 154 行 POST 动态路径，无 body；第 155 行结束。
2. request.post 第 161-163 行调用 Axios；ID/请求拦截器写 owner/requestId；响应拦截器解包或抛异常。

### 3.2 Java 同步任务投递函数

#### 3.2.1 `KnowledgeBaseController.revectorize`

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/controller/KnowledgeBaseController.java:121-126`。

1. 第 121 行映射 `/{id}/revectorize`；第 122-123 行绑定 id/用户头。
2. 第 124 行 service.revectorize；第 125 行 success(null)；第 126 行结束。

#### 3.2.2 `KnowledgeBaseService.revectorize`

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/service/KnowledgeBaseService.java:202-214`。

1. 第 203 行 required 验证存在/归属；第 204-206 行有删除请求时抛 KNOWLEDGE_BASE_DELETING。
2. 第 207 行 markIndexPending，清旧索引错误并置 PENDING。
3. 第 208-212 行调用 indexWorker.index；投递异常时 markIndexFailed 后重抛；第 214 行结束。

#### 3.2.3 `Persistence.markIndexPending`、实体状态与 `index`

文件：`KnowledgeBasePersistenceService.java:24-27`；`KnowledgeBaseEntity.java:69-90`；`KnowledgeBaseIndexWorker.java:38-42`。

1. markIndexPending 在事务中 required 后调用实体；实体写 PENDING、清 vectorError、更新时间，保留旧 vectorCount 直到新索引完成。
2. worker.index 第 39-41 行选择 exchange/routing key，构造 KNOWLEDGE_BASE_INDEX 消息并发送；消息只含 ID/用户。

### 3.3 异步 Worker 与 Python 函数

#### 3.3.1 `RabbitAgentWorkConsumer.consume` 与 `KnowledgeBaseIndexWorker.process`

文件：`RabbitAgentWorkConsumer.java:22-39`；`KnowledgeBaseIndexWorker.java:44-100`。

1. consume 验证消息字段，按 taskType 进入 process。
2. process 第 45-58 行查实体、验证 owner/删除状态、markIndexing；失败或重复状态 return。
3. 第 60-64 行构造 AgentRagIndexRequest；第 65-73 行非成功响应 markIndexFailed，并仅对可重试错误抛给 Rabbit。
4. 第 74-85 行成功后重查删除竞态；仍有效时用 response.answer 作为 chunkCount markIndexed。
5. 第 86-99 行异常时写 FAILED；业务/不可重试错误确认消息，临时异常重抛触发监听器重试。

#### 3.3.2 `HttpPythonAgentClient.indexRag`

文件：`HttpPythonAgentClient.java:48、65-96`；`AgentCallExecutor.java:22-43`。

1. indexRag 第 48 行执行 `/v1/agent/rag/index` post lambda。
2. post 校验 DTO、发送/解析 AgentResponse，区分结构化 HTTP 和网络异常；execute 对可重试异常执行有限重试。

#### 3.3.3 Python `index_rag` 与 `RagService.index_document`

文件：`python-agent/app/api/application.py:223-238`；`rag/service.py:35-54`。

1. index_rag 第 225 行保存上下文；第 226-231 行构造 KnowledgeDocument 并调用 index_document；第 232-238 行 answer 返回 chunk 数。
2. index_document 第 36 行 TokenChunker.split；第 37 行获取 KB 锁；第 39-47 行按 batch 生成 embedding、校验数量、写回 chunk。
3. 第 48 行 `replace_for_knowledge_base` 替换旧向量；第 49-52 行统一依赖异常；第 53-54 行清缓存并返回数量。

#### 3.3.4 `TokenChunker`、Embedding、向量替换

文件：`rag/parser.py:44-80`；`rag/embedding.py:41-47`；`rag/repository.py:47-78`。

1. TokenChunker 根据 policy 的 chunk_size/overlap 切正文并构造 KnowledgeChunk。
2. embed_documents 直接调用 embedding 客户端或经 AsyncRetryExecutor 执行。
3. replace_for_knowledge_base 在一个数据库事务中删除旧 KB chunks、写入本次全部 chunks并 commit，保证“重新向量化”是替换而非追加。

## 4. 审核结论

1. 已区分 HTTP 投递成功与异步 Python 索引完成，覆盖删除竞态、失败状态与 Rabbit 重试。
2. 所有可达项目函数均标注文件/行号并解释；Python 终点为向量替换与缓存失效。
