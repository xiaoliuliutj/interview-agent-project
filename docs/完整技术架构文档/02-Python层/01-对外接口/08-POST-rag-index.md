# POST /v1/agent/rag/index：索引知识文档

## 1. 接口定义

该接口把文档内容包装成 `KnowledgeDocument`，按标题和 Token 预算切片，批量生成向量并替换指定知识库的旧索引，返回新切片数量。

| 项目 | 内容 |
|---|---|
| HTTP 方法 | POST |
| 路径 | `/v1/agent/rag/index` |
| 路由函数 | `index_rag` |
| 文件 | `python-agent/app/api/application.py:226-241` |

## 2. 函数调用链

```text
请求进入 FastAPI
  -> AgentRagIndexRequest 字段约束校验
  -> index_rag
     -> _remember_request_context
     -> _resolve_rag_service
        -> [已缓存] 直接返回 request.app.state.rag_service
        -> [首次请求冷启动] build_rag_service
           -> get_settings
           -> create_session_factory
              -> create_engine
                 -> get_settings（仅 create_engine 未收到显式 settings 时；本链路实际已传入，不进入）
           -> RetryPolicy.load
           -> AsyncRetryExecutor.__init__
           -> PostgresRagVectorRepository.__init__
           -> OpenAIEmbeddingProvider.__init__
           -> RagPolicy.load
           -> build_cache
              -> RedisCache.__init__
           -> RagService.__init__
              -> TokenChunker.__init__
     -> KnowledgeDocument（Pydantic 模型构造与字段校验，无项目自定义校验函数）
     -> RagService.index_document
        -> TokenChunker.split
           -> TokenChunker._split_by_headings
              -> append_current（嵌套函数，遇到标题以及循环结束时调用）
           -> TokenChunker._split_section
        -> RagService._lock_for
        -> [每个 embedding 批次] OpenAIEmbeddingProvider.embed_documents
           -> AsyncRetryExecutor.execute
              -> [调用失败] AsyncRetryExecutor._is_retryable
              -> [允许重试] AsyncRetryExecutor._backoff_seconds
              -> [可重试错误耗尽] AgentDependencyError -> ApplicationException.__init__
        -> PostgresRagVectorRepository.replace_for_knowledge_base
           -> [每个切片] PostgresRagVectorRepository._to_entity
        -> RagService.invalidate_cache
           -> [配置了 Redis] RedisCache.delete_matching
     -> AgentResponse
        -> AgentResponse.validate_code_category
  -> FastAPI 按 response_model 序列化成功响应

本接口可能创建的项目异常对象：
RequestError / PersistenceConfigurationError / ReliabilityConfigurationError
/ RagConfigurationError / AgentDependencyError / RagDependencyError
  -> ApplicationException.__init__

请求体校验失败分支：
RequestValidationError
  -> request_validation_error
  -> _error_json_response
     -> _error_response
        -> [_error_json_response 未提供 Mapping 上下文时] _request_context
        -> _session_status_or_failed
        -> _string_or_none
        -> ExceptionHandler.to_code
        -> ExceptionHandler.to_error_info
        -> AgentResponse.validate_code_category
     -> AgentResponse.to_json_dict

项目异常分支：
ApplicationException
  -> application_error
  -> _mark_failed_interview_progress（当前路径不是 /v1/agent/respond，立即返回）
  -> _error_json_response
     -> _error_response
        -> _request_context
        -> _session_status_or_failed
        -> _string_or_none
        -> ExceptionHandler.to_code
        -> ExceptionHandler.to_error_info
        -> AgentResponse.validate_code_category
     -> AgentResponse.to_json_dict

未预期异常分支：
Exception
  -> unexpected_error
  -> _mark_failed_interview_progress（当前路径不是 /v1/agent/respond，立即返回）
  -> _error_json_response
     -> _error_response
        -> _request_context
        -> _session_status_or_failed
        -> _string_or_none
        -> ExceptionHandler.to_code
        -> ExceptionHandler.to_error_info
        -> AgentResponse.validate_code_category
     -> AgentResponse.to_json_dict
```

## 3. 函数解析

### 3.1 `index_rag`

文件：`python-agent/app/api/application.py:226-241`

```python
    @app.post("/v1/agent/rag/index", response_model=AgentResponse)
    async def index_rag(payload: AgentRagIndexRequest, request: Request) -> AgentResponse:
        _remember_request_context(request, payload)
        count = await _resolve_rag_service(request).index_document(KnowledgeDocument(
            knowledge_base_id=payload.knowledge_base_ids[0],
            document_id=payload.document_id,
            source_name=payload.source_name,
            content=payload.document_content,
        ))
        return AgentResponse(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, code=100, status=RunStatus.COMPLETED,
            user_id=payload.user_id, session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE, state_version=0,
            answer=str(count), output=None, error=None,
        )
```

逐行解释：

1. 第 226 行：注册 HTTP `POST /v1/agent/rag/index`，响应模型为 AgentResponse。
2. 第 227 行：定义异步路由并接收已校验请求和 Request。
3. 第 228 行：调用 _remember_request_context 保存协议上下文。
4. 第 229 行：调用 _resolve_rag_service，并把 KnowledgeDocument 传给 index_document。
5. 第 230 行：明确取 knowledgeBaseIds 列表第一个值作为索引目标库。
6. 第 231 行：传入 documentId。
7. 第 232 行：传入 sourceName。
8. 第 233 行：传入完整 documentContent。
9. 第 234 行：结束文档与服务调用，等待得到 chunk 数量。
10. 第 235 行：开始构造 AgentResponse。
11. 第 236 行：复制 apiVersion 与 requestId。
12. 第 237 行：复制 runId，code 100，运行状态 COMPLETED。
13. 第 238 行：复制 userId 与 sessionId。
14. 第 239 行：RAG 索引不推进面试会话，状态固定 ACTIVE、版本 0。
15. 第 240 行：把切片数转字符串作为 answer，output/error 为 None。
16. 第 241 行：返回响应。

### 3.2 `RagService.index_document`

文件：`python-agent/app/rag/service.py:39-58`

```python
    async def index_document(self, document: KnowledgeDocument) -> int:
        chunks = self._chunker.split(document)
        async with self._lock_for(document.knowledge_base_id):
            try:
                for start in range(0, len(chunks), self._policy.embedding_batch_size):
                    batch = chunks[start : start + self._policy.embedding_batch_size]
                    vectors = await self._embedding_provider.embed_documents(
                        [chunk.content for chunk in batch]
                    )
                    if len(vectors) != len(batch):
                        raise RagDependencyError("embedding result count does not match chunk count")
                    for chunk, vector in zip(batch, vectors):
                        chunk.embedding = vector
                await self._repository.replace_for_knowledge_base(document.knowledge_base_id, chunks)
            except RagDependencyError:
                raise
            except Exception as error:
                raise RagDependencyError("RAG document embedding failed") from error
        await self.invalidate_cache()
        return len(chunks)
```

逐行解释：

1. 第 39 行：定义异步文档索引函数。
2. 第 40 行：调用 TokenChunker.split(document) 生成无向量切片。
3. 第 41 行：调用 _lock_for(knowledgeBaseId)，在同一库的异步锁内执行替换。
4. 第 42 行：进入异常归一化保护。
5. 第 43 行：按 embeddingBatchSize 递增遍历切片起点。
6. 第 44 行：切出当前批次。
7. 第 45 行：调用 EmbeddingProvider.embed_documents。
8. 第 46 行：只传入当前批次正文列表。
9. 第 47 行：得到向量列表。
10. 第 48 行：检查向量数与批次切片数。
11. 第 49 行：数量不一致时抛 RagDependencyError。
12. 第 50 行：用 zip 遍历切片与向量。
13. 第 51 行：把向量写回对应 KnowledgeChunk.embedding。
14. 第 52 行：调用 PostgresRagVectorRepository.replace_for_knowledge_base 原子替换整库切片。
15. 第 53 行：捕获已经是 RagDependencyError 的异常。
16. 第 54 行：原样抛出，保留明确错误语义。
17. 第 55 行：捕获其他 Exception。
18. 第 56 行：统一包装为 RagDependencyError 并保留原异常。
19. 第 57 行：锁释放后调用 invalidate_cache，清空进程与 Redis 搜索缓存。
20. 第 58 行：返回切片数量。

### 3.3 `TokenChunker.split`

文件：`python-agent/app/rag/parser.py:54-78`

逐行解释：

1. 第 54 行：定义文档切片函数。
2. 第 55 行：调用项目函数 `_split_by_headings(document.content)` 生成标题分区。
3. 第 56 行：创建最终 KnowledgeChunk 列表。
4. 第 57 行：遍历标题路径、标题层级和分区正文。
5. 第 58 行：调用 `_split_section` 按 Token 预算细分当前分区。
6. 第 59 行：传入标题路径与分区正文。
7. 第 60 行：得到每个正文片段和分区内序号。
8. 第 61 行：向结果追加 KnowledgeChunk。
9. 第 62 行：开始构造切片。
10. 第 63 行：chunkId 由 documentId 和当前总切片数组成。
11. 第 64 行：复制 knowledgeBaseId。
12. 第 65 行：复制 documentId。
13. 第 66 行：复制 sourceName。
14. 第 67 行：chunkIndex 使用追加前列表长度。
15. 第 68 行：写含标题前缀的片段正文。
16. 第 69 行：开始 metadata。
17. 第 70 行：保留原文档 metadata。
18. 第 71 行：标记切片策略 heading_then_token。
19. 第 72 行：把标题路径文本用大于号连接。
20. 第 73 行：把标题层级转字符串。
21. 第 74 行：把分区内片段序号转字符串。
22. 第 75 行：结束 metadata。
23. 第 76 行：结束 `KnowledgeChunk(...)` 构造，至此切片标识、来源、正文、顺序和元数据已经全部写入对象。
24. 第 77 行：结束 `chunks.append(...)` 调用，把刚构造的 `KnowledgeChunk` 真正加入结果列表。
25. 第 78 行：返回全部切片。

### 3.4 `TokenChunker._split_by_headings`

文件：`python-agent/app/rag/parser.py:80-116`

逐行解释：

1. 第 80 行：定义 `_split_by_headings` 实例方法并开始多行参数声明。
2. 第 81 行：声明 `content: str` 参数，表示待按 Markdown 标题拆分的完整文档正文。
3. 第 82 行：声明返回类型；列表中的每项依次包含标题路径、当前标题层级和该节正文。
4. 第 83 行：文档字符串说明该方法只返回非空文本分区，并为每个分区关联 Markdown 标题路径。
5. 第 84 行：创建标题栈 `heading_path`；每个元素保存标题层级与标题文本。
6. 第 85 行：创建当前分区的行缓冲 `current_lines`。
7. 第 86 行：创建最终分区列表 `sections`。
8. 第 87 行：把 fenced code 状态初始化为 `False`，用于避免把代码块中的 `#` 误判成标题。
9. 第 89 行：定义嵌套项目函数 `append_current`；遇到新标题和全文遍历结束时均会调用它。
10. 第 90 行：用换行符拼接当前缓冲的所有行并去除首尾空白，得到候选分区正文 `text`。
11. 第 91 行：只在 `text` 非空时保存分区，防止连续标题或纯空白生成空切片。
12. 第 92 行：复制当前标题路径，取最后一个标题的层级或在无标题时取 `0`，再与正文一起追加到 `sections`。
13. 第 94 行：使用 `splitlines()` 按原文行序遍历正文。
14. 第 95 行：去掉行首空白后检查是否以三个反引号开头，从而识别代码围栏。
15. 第 96 行：将围栏行本身保留进正文缓冲，确保不丢失 Markdown 结构。
16. 第 97 行：翻转 `in_fenced_code_block`，进入或退出 fenced code block。
17. 第 98 行：围栏行处理完毕后直接进入下一轮，不执行标题匹配。
18. 第 99 行：判断当前行是否位于 fenced code block 内部。
19. 第 100 行：代码块内部的行原样加入正文缓冲。
20. 第 101 行：结束当前轮，保证代码中的伪标题不会改变标题路径。
21. 第 102 行：在非代码块状态下，用 `_HEADING_PATTERN` 匹配 Markdown 标题。
22. 第 103 行：判断匹配是否失败，即当前行不是标题。
23. 第 104 行：把普通正文行加入 `current_lines`。
24. 第 105 行：普通行处理完毕后进入下一轮。
25. 第 106 行：遇到新标题时先调用嵌套函数 `append_current`，提交上一标题对应的正文。
26. 第 107 行：清空正文缓冲，为新标题下的正文重新收集行。
27. 第 108 行：用第一捕获组中 `#` 的数量计算新标题层级。
28. 第 109 行：读取第二捕获组并去除首尾空白，得到标题文本。
29. 第 110 行：过滤掉同级或更深的旧标题，只保留新标题的祖先路径。
30. 第 111 行：把当前标题的层级和文本压入标题路径。
31. 第 112 行：全文循环结束后再次调用 `append_current`，提交最后一个标题分区。
32. 第 114 行：判断是否至少生成了一个非空分区。
33. 第 115 行：存在分区时直接返回 `sections`。
34. 第 116 行：没有标题分区时，若原文非空则返回一个标题路径为空、层级为 `0` 的分区；原文为空则返回空列表。

### 3.5 `TokenChunker._split_section`

文件：`python-agent/app/rag/parser.py:118-142`

逐行解释：

1. 第 118 行：定义 `_split_section` 实例方法并开始多行参数声明。
2. 第 119 行：声明标题路径 `heading_path` 与该标题下的正文 `section_content` 两个参数。
3. 第 120 行：声明返回值为 `(切片文本, 分区内序号)` 元组列表。
2. 第 121 行：文档说明每个片段重复标题上下文。
3. 第 122 行：开始构造标题前缀。
4. 第 123 行：按层级生成 Markdown 标题行。
5. 第 124 行：用换行连接。
6. 第 125 行：检查前缀非空。
7. 第 126 行：追加两个换行与正文分隔。
8. 第 127 行：用 tiktoken 编码标题前缀。
9. 第 128 行：检查前缀 Token 数是否达到整块上限。
10. 第 129 行：超限抛 RagConfigurationError。
11. 第 131 行：编码分区正文。
12. 第 132 行：可用正文预算等于总上限减标题 Token。
13. 第 133 行：重叠取配置值与正文预算减一中的较小值。
14. 第 134 行：步长等于正文预算减重叠。
15. 第 135 行：创建结果列表。
16. 第 136 行：按步长遍历 token 起点，并用 enumerate 生成片段序号。
17. 第 137 行：开始解码当前窗口。
18. 第 138 行：窗口最多 availableBodyTokens。
19. 第 139 行：解码后去空白。
20. 第 140 行：检查正文非空。
21. 第 141 行：连接标题前缀与正文，去空白后连同 partIndex 加入结果。
22. 第 142 行：返回分区片段。

### 3.6 `RagService._lock_for` 与 `invalidate_cache`

`_lock_for` 文件：`python-agent/app/rag/service.py:150-155`

1. 第 150 行：定义按知识库取得进程内 asyncio.Lock 的函数。
2. 第 151 行：从字典读取已有锁。
3. 第 152 行：检查不存在。
4. 第 153 行：创建新 asyncio.Lock。
5. 第 154 行：按 knowledgeBaseId 保存。
6. 第 155 行：返回锁。

`invalidate_cache` 文件：`python-agent/app/rag/service.py:145-148`

1. 第 145 行：定义异步缓存失效函数。
2. 第 146 行：清空进程内 searchCache。
3. 第 147 行：检查 RedisCache 存在。
4. 第 148 行：调用 RedisCache.delete_matching 删除 python:rag:search:*。

### 3.7 `OpenAIEmbeddingProvider.embed_documents`

文件：`python-agent/app/rag/embedding.py:41-46`

1. 第 41 行：定义批量文本向量化函数。
2. 第 42 行：检查无统一重试器。
3. 第 43 行：无重试时直接等待第三方 aembed_documents。
4. 第 44 行：有重试器时调用 AsyncRetryExecutor.execute。
5. 第 45 行：lambda 每次重新调用 aembed_documents(texts)。
6. 第 46 行：返回批量向量。

### 3.8 `PostgresRagVectorRepository.replace_for_knowledge_base` 与 `_to_entity`

`replace_for_knowledge_base` 文件：`python-agent/app/infrastructure/persistence/rag_vector_repository.py:47-57`

1. 第 47 行：定义 `replace_for_knowledge_base` 异步方法并开始多行参数声明。
2. 第 48 行：接收知识库标识和待写入的 `KnowledgeChunk` 列表。
3. 第 49 行：声明该异步方法无返回值并结束函数签名。
4. 第 50 行：打开异步数据库会话，同一会话覆盖删除与新增。
5. 第 51 行：调用 `db_session.execute(...)` 执行删除语句。
6. 第 52 行：以 `RagChunkEntity` 为删除目标并开始拼接 `where` 条件。
7. 第 53 行：限定 `knowledge_base_id` 必须等于当前请求的知识库标识。
8. 第 54 行：结束 SQLAlchemy `where(...)` 调用。
9. 第 55 行：结束 `db_session.execute(...)` 调用；删除仍处于当前未提交事务中。
10. 第 56 行：对每个切片调用项目函数 `_to_entity`，再用 `add_all` 把新实体加入同一会话。
11. 第 57 行：提交事务，使旧切片删除和新切片插入作为同一事务持久化。

`_to_entity` 文件：`python-agent/app/infrastructure/persistence/rag_vector_repository.py:87-98`

1. 第 87 行：声明静态方法。
2. 第 88 行：定义领域切片到实体转换。
3. 第 89 行：开始构造 RagChunkEntity。
4. 第 90 行：复制 chunkId。
5. 第 91 行：复制 knowledgeBaseId。
6. 第 92 行：复制 documentId。
7. 第 93 行：复制 sourceName。
8. 第 94 行：复制 chunkIndex。
9. 第 95 行：复制 content。
10. 第 96 行：复制 metadata 到 chunkMetadata。
11. 第 97 行：复制 embedding。
12. 第 98 行：返回实体。

### 3.9 `RedisCache.delete_matching`

文件：`python-agent/app/infrastructure/cache/redis_cache.py:59-68`

1. 第 59 行：定义按模式尽力删除函数。
2. 第 60 行：文档限制该函数只用于小型有界命名空间。
3. 第 61 行：检查 Redis 客户端不存在。
4. 第 62 行：不存在时返回。
5. 第 63 行：进入删除保护。
6. 第 64 行：使用 scanIter 分批扫描匹配键，count 提示 200。
7. 第 65 行：检查扫描结果非空。
8. 第 66 行：一次 DELETE 删除已收集键。
9. 第 67 行：捕获 RedisError。
10. 第 68 行：记录 warning，并依赖 TTL 清理，不中断索引成功结果。

### 3.10 `_remember_request_context` 与 `_resolve_rag_service`

`_remember_request_context` 文件：`python-agent/app/api/application.py:391-394`

1. 第 391 行：定义上下文记录函数。
2. 第 392 行：兼容读取 model_dump。
3. 第 393 行：确认可调用。
4. 第 394 行：按别名和 JSON 模式导出并保存到 request.state。

`_resolve_rag_service` 文件：`python-agent/app/api/application.py:334-340`

1. 第 334 行：定义 RAG 服务解析函数。
2. 第 335 行：从应用状态读取服务。
3. 第 336 行：检查未构造。
4. 第 337 行：函数内导入 build_rag_service。
5. 第 338 行：调用工厂。
6. 第 339 行：写回应用状态。
7. 第 340 行：返回服务。

### 3.11 `build_rag_service`

文件：`python-agent/app/bootstrap.py:82-91`

1. 第 82 行：定义 RAG 服务工厂。
2. 第 83 行：选择显式 Settings 或 get_settings。
3. 第 84 行：调用 create_session_factory。
4. 第 85 行：调用 RetryPolicy.load 并创建 AsyncRetryExecutor。
5. 第 86 行：开始构造 RagService。
6. 第 87 行：注入 PostgresRagVectorRepository。
7. 第 88 行：注入 OpenAIEmbeddingProvider。
8. 第 89 行：调用 RagPolicy.load 注入策略。
9. 第 90 行：调用 build_cache 注入 Python Redis。
10. 第 91 行：返回服务。

`build_cache` 文件：`python-agent/app/bootstrap.py:41-43`

1. 第 41 行：定义缓存工厂。
2. 第 42 行：选择配置。
3. 第 43 行：以 Python 专属 redisUrl 构造 RedisCache。

`AsyncRetryExecutor.__init__` 文件：`python-agent/app/infrastructure/reliability/retry.py:16-17`

1. 第 16 行：定义重试执行器构造函数，接收已经加载并校验通过的 `RetryPolicy`。
2. 第 17 行：把策略保存到 `_policy`，后续 `execute`、`_is_retryable` 和 `_backoff_seconds` 都从该字段读取限制。

`PostgresRagVectorRepository.__init__` 文件：`python-agent/app/infrastructure/persistence/rag_vector_repository.py:28-29`

1. 第 28 行：定义 PostgreSQL RAG 仓储构造函数，接收异步会话工厂。
2. 第 29 行：把会话工厂保存到 `_session_factory`，使每次仓储操作都能创建独立异步会话。

`RedisCache.__init__` 文件：`python-agent/app/infrastructure/cache/redis_cache.py:19-25`

1. 第 19 行：定义 Redis 缓存适配器构造函数，接收 Python 服务自己的 Redis URL。
2. 第 20 行：开始为 `_client` 赋值；其结果可以是异步 Redis 客户端或 `None`。
3. 第 21 行：当 URL 有值时调用 Redis 库的 `Redis.from_url(...)` 创建客户端。
4. 第 22 行：传入 URL，启用字符串解码，并把连接超时和命令超时都限制为 `0.2` 秒，避免缓存故障长期阻塞主流程。
5. 第 23 行：结束 `Redis.from_url(...)` 调用。
6. 第 24 行：使用条件表达式；URL 为空时不创建连接对象，而是把客户端设为 `None`。
7. 第 25 行：结束 `_client` 赋值表达式；后续缓存方法以 `None` 判断 Redis 是否启用。

### 3.12 `get_settings` 与数据库工厂

`get_settings` 文件：`python-agent/app/common/config.py:47-51`

1. 第 47 行：以 LRU 最大 1 缓存配置。
2. 第 48 行：定义读取函数。
3. 第 49 行：说明返回进程快照。
4. 第 51 行：实例化 Settings 并从环境/.env 校验读取。

`create_engine` 文件：`python-agent/app/infrastructure/persistence/database.py:9-13`

1. 第 9 行：定义数据库引擎工厂。
2. 第 10 行：选择配置。
3. 第 11 行：检查 databaseUrl。
4. 第 12 行：缺失时抛 PersistenceConfigurationError。
5. 第 13 行：创建异步引擎并启用 poolPrePing。

`create_session_factory` 文件：`python-agent/app/infrastructure/persistence/database.py:16-19`

1. 第 16 行：定义 `create_session_factory` 并开始多行函数签名。
2. 第 17 行：接收可选 `Settings`；调用方传入时避免重新读取全局配置。
3. 第 18 行：声明返回类型为 `async_sessionmaker[AsyncSession]` 并结束签名。
4. 第 19 行：调用项目函数 `create_engine(settings)`，再创建 `expire_on_commit=False` 的异步会话工厂，使提交后实体属性不会自动过期。

### 3.13 `RagPolicy.load`

文件：`python-agent/app/rag/policy.py:25-64`

逐行解释：

1. 第 25 行：声明类方法。
2. 第 26 行：定义加载函数。
3. 第 27 行：选择路径或 rag-policy.json。
4. 第 28 行：进入读取保护。
5. 第 29 行：解析 JSON。
6. 第 30 行：开始构造策略。
7. 第 31 行：读取 chunkSizeTokens。
8. 第 32 行：读取 chunkOverlapTokens。
9. 第 33 行：读取 embeddingBatchSize。
10. 第 34 行：读取 defaultTopK。
11. 第 35 行：读取 defaultMinScore。
12. 第 36 行：读取 fallbackCandidateMultiplier。
13. 第 37 行：开始 allowedUseCases 冻结集合。
14. 第 38 行：把每项转换为 RagUseCase。
15. 第 39 行：结束集合。
16. 第 40 行：读取 cacheTtlSeconds。
17. 第 41 行：读取 cacheMaxEntries。
18. 第 42 行：结束策略。
19. 第 43 行：开始声明配置读取和转换阶段允许捕获的异常类型元组。
20. 第 44 行：把配置文件不存在的 `FileNotFoundError` 纳入捕获范围。
21. 第 45 行：把必填 JSON 字段缺失的 `KeyError` 纳入捕获范围。
22. 第 46 行：把数字或枚举值转换失败的 `ValueError` 纳入捕获范围。
23. 第 47 行：把配置值类型错误的 `TypeError` 纳入捕获范围。
24. 第 48 行：把 JSON 文本格式无效的 `json.JSONDecodeError` 纳入捕获范围。
25. 第 49 行：把捕获到的原异常保存为 `error`，供异常链使用。
26. 第 50 行：统一抛出 `RagConfigurationError` 并通过 `from error` 保留根因。
27. 第 52 行：检查切片大小至少为 `1` 且重叠 Token 数非负。
28. 第 53 行：任一条件不满足时抛出切片参数错误。
29. 第 54 行：检查重叠 Token 数必须严格小于单片 Token 上限。
30. 第 55 行：重叠达到或超过切片大小时抛错，避免计算出非正步长。
31. 第 56 行：检查 embedding 批大小至少为 `1`。
32. 第 57 行：批大小无效时抛错，防止后续 `range` 使用零步长。
33. 第 58 行：检查默认 `top_k` 至少为 `1`，且最低相似度位于闭区间 `[0, 1]`。
34. 第 59 行：检索参数越界时抛错。
35. 第 60 行：检查允许的 RAG 用途集合非空。
36. 第 61 行：用途集合为空时抛错，避免服务启动后所有检索均不可用。
37. 第 62 行：检查缓存 TTL 非负且进程内缓存条目上限至少为 `1`。
38. 第 63 行：缓存策略无效时抛出 `RagConfigurationError`。
39. 第 64 行：所有字段读取和边界校验通过后返回不可变策略对象。

### 3.14 `OpenAIEmbeddingProvider.__init__` 与 `AsyncRetryExecutor.execute`

`OpenAIEmbeddingProvider.__init__` 文件：`python-agent/app/rag/embedding.py:19-39`

1. 第 19 行：定义初始化函数。
2. 第 20 行：接收 Settings 和可选重试器。
3. 第 21 行：结束签名。
4. 第 22 行：检查 embeddingModel。
5. 第 23 行：缺失时抛 RagConfigurationError。
6. 第 24 行：开始客户端参数。
7. 第 25 行：写 embedding 模型名。
8. 第 26 行：优先 embeddingApiKey，否则复用 modelApiKey。
9. 第 27 行：超时取配置与 120 秒较小值。
10. 第 28 行：禁用 SDK 重试。
11. 第 29 行：注释指出部分 OpenAI 兼容 embedding 服务只接受特定输入形式。
12. 第 30 行：注释明确这些服务接受 `input: string[]`，却拒绝 OpenAI Token 数组形式。
13. 第 31 行：注释说明 RAG 文本已由 `TokenChunker` 按 Token 上限切分。
14. 第 32 行：注释给出设计结论：绕过 SDK 的上下文长度重新分词，直接发送原始字符串。
15. 第 33 行：把 `check_embedding_ctx_length` 设为 `False`，落实上述兼容策略。
16. 第 34 行：结束客户端参数字典。
17. 第 35 行：优先读取 `embedding_base_url`，为空时复用模型服务的 `model_base_url`。
18. 第 36 行：检查最终 `base_url` 是否非空。
19. 第 37 行：存在自定义地址时把它加入客户端参数。
20. 第 38 行：使用整理后的参数创建 `OpenAIEmbeddings` 客户端。
21. 第 39 行：保存可选重试执行器，供 embedding 方法决定直接调用还是受控重试。

`AsyncRetryExecutor.execute` 文件：`python-agent/app/infrastructure/reliability/retry.py:23-50`

1. 第 23 行：定义异步重试执行。
2. 第 24 行：遍历有限尝试。
3. 第 25 行：进入单次保护。
4. 第 26 行：注释说明此超时上限覆盖所有经该执行器发起的模型和外部 Agent 调用。
5. 第 27 行：注释说明 `wait_for` 会取消超时协程，避免请求在后台无限悬挂。
6. 第 28 行：调用 `asyncio.wait_for(...)` 并等待当前尝试结果。
7. 第 29 行：调用 `operation()` 创建本次操作协程，同时传入策略中的单次超时秒数。
8. 第 30 行：结束 `wait_for` 调用；成功时直接把操作结果返回给上层。
9. 第 31 行：捕获本次尝试产生的任意 `Exception`。
10. 第 32 行：调用项目函数 `_is_retryable(error)`；不可重试或已到最后一次尝试时进入终止分支。
11. 第 33 行：再次调用 `_is_retryable(error)`，区分“可重试但次数耗尽”和“本来就不可重试”。
12. 第 34 行：开始构造统一的 `AgentDependencyError`。
13. 第 35 行：写入“有限重试后仍不可用”的外部错误消息。
14. 第 36 行：把 `retryable` 标记为 `True`，告知上层此次依赖错误具备重试语义。
15. 第 37 行：结束异常构造，并用 `from error` 链接最后一次依赖异常后抛出。
16. 第 38 行：若原异常不在可重试集合中，则保持其类型和堆栈原样抛出。
17. 第 39 行：调用项目函数 `_backoff_seconds(attempt)` 计算退避秒数，并异步等待后进入下一次尝试。
18. 第 40 行：循环理论上不可能自然结束；若发生则抛 `AssertionError` 暴露控制流错误。
19. 第 42 行：定义项目函数 `_is_retryable`，接收待判断异常。
20. 第 43 行：取异常类名，并检查它是否存在于策略的 `retryable_errors` 集合中。
21. 第 45 行：定义项目函数 `_backoff_seconds`，接收当前尝试序号。
22. 第 46 行：调用 `min(...)` 计算受上限约束的退避毫秒数。
23. 第 47 行：把策略的最大退避毫秒数作为第一个候选值。
24. 第 48 行：按 `initial_backoff * 2 ** (attempt - 1)` 计算指数退避值。
25. 第 49 行：结束 `min(...)`；最终值不会超过最大退避配置。
26. 第 50 行：把毫秒除以 `1000` 转成 `asyncio.sleep` 所需的秒并返回。

### 3.15 `RetryPolicy.load`、`RagService.__init__` 与 `TokenChunker.__init__`

`RetryPolicy.load` 文件：`python-agent/app/infrastructure/reliability/policy.py:20-45`

1. 第 20 行：声明类方法。
2. 第 21 行：定义策略加载。
3. 第 22 行：选择路径或 reliability.json。
4. 第 23 行：进入读取保护。
5. 第 24 行：解析 JSON。
6. 第 25 行：开始构造。
7. 第 26 行：读取最大尝试数。
8. 第 27 行：读取初始退避。
9. 第 28 行：读取最大退避。
10. 第 29 行：构造可重试异常集合。
11. 第 30 行：读取单次超时。
12. 第 31 行：读取输出纠错次数。
13. 第 32 行：结束构造。
14. 第 33 行：捕获配置错误。
15. 第 34 行：统一抛 ReliabilityConfigurationError。
16. 第 35 行：同时检查总尝试次数是否位于 `1..5`，以及初始退避是否非负。
17. 第 36 行：上述任一条件不满足时抛出总尝试次数配置错误；该消息覆盖同一条件中的负退避情况。
18. 第 37 行：检查最大退避毫秒数是否小于初始退避毫秒数。
19. 第 38 行：最大值反而更小时抛出 `ReliabilityConfigurationError`。
20. 第 39 行：检查单次调用超时必须大于 `0` 且不超过 `120` 秒。
21. 第 40 行：超出范围时抛出单次模型调用超时配置错误。
22. 第 41 行：检查结构化输出纠错次数必须位于 `0..2`。
23. 第 42 行：纠错次数为负或超过 `2` 时抛出配置错误。
24. 第 43 行：检查可重试异常类名集合是否非空。
25. 第 44 行：集合为空时抛出配置错误，否则重试器永远没有可重试对象。
26. 第 45 行：所有读取、类型转换和边界校验通过后返回不可变重试策略。

`RagService.__init__` 文件：`python-agent/app/rag/service.py:19-37`

1. 第 19 行：定义初始化函数。
2. 第 20 行：接收实例。
3. 第 21 行：接收向量仓储。
4. 第 22 行：接收 embedding provider。
5. 第 23 行：接收 RagPolicy。
6. 第 24 行：接收可选文档解析器。
7. 第 25 行：接收可选 RedisCache。
8. 第 26 行：结束签名。
9. 第 27 行：保存仓储。
10. 第 28 行：保存 embedding provider。
11. 第 29 行：保存策略。
12. 第 30 行：显式解析器优先，否则创建 KnowledgeDocumentParser。
13. 第 31 行：创建 TokenChunker。
14. 第 32 行：传入策略 chunkSizeTokens。
15. 第 33 行：传入 overlapTokens。
16. 第 34 行：结束切片器构造。
17. 第 35 行：创建进程内搜索缓存。
18. 第 36 行：创建知识库锁字典。
19. 第 37 行：保存 RedisCache。

`TokenChunker.__init__` 文件：`python-agent/app/rag/parser.py:47-52`

1. 第 47 行：定义初始化函数。
2. 第 48 行：检查切片大小、重叠非负且重叠小于大小。
3. 第 49 行：非法时抛 RagConfigurationError。
4. 第 50 行：加载 cl100k_base tiktoken 编码器。
5. 第 51 行：保存切片大小。
6. 第 52 行：保存重叠大小。

### 3.16 `AgentResponse.validate_code_category`

文件：`python-agent/app/common/contracts.py:177-182`

1. 第 177 行：注册 code 字段校验器。
2. 第 178 行：声明类方法。
3. 第 179 行：定义校验。
4. 第 180 行：要求首位类别属于 1~5。
5. 第 181 行：不满足抛 ValueError。
6. 第 182 行：返回合法 code。

### 3.17 FastAPI 异常与统一错误响应

`request_validation_error` 文件：`python-agent/app/api/application.py:292-299`

1. 第 292 行：注册请求校验错误处理器。
2. 第 293 行：定义异步函数。
3. 第 294 行：读取 error.body。
4. 第 295 行：body 为映射时作为上下文。
5. 第 296 行：调用 _error_json_response。
6. 第 297 行：转换 RequestError 并用 HTTP 400。
7. 第 298 行：传入上下文。
8. 第 299 行：返回。

`application_error` 文件：`python-agent/app/api/application.py:301-304`

1. 第 301 行：注册 ApplicationException 处理器。
2. 第 302 行：定义异步函数。
3. 第 303 行：调用 _mark_failed_interview_progress；本 RAG 路径立即返回。
4. 第 304 行：调用 _error_json_response，HTTP 200。

`unexpected_error` 文件：`python-agent/app/api/application.py:306-310`

1. 第 306 行：注册其他 Exception。
2. 第 307 行：定义异步函数。
3. 第 308 行：记录堆栈。
4. 第 309 行：调用 _mark_failed_interview_progress。
5. 第 310 行：调用 _error_json_response，HTTP 500。

`_mark_failed_interview_progress` 文件：`python-agent/app/api/application.py:323-331`

1. 第 323 行：定义失败进度补偿。
2. 第 324 行：检查路径不是 respond。
3. 第 325 行：本 RAG 接口立即返回。
4. 第 326 行：仅 respond 路径恢复请求上下文。
5. 第 327 行：仅 respond 路径清洗 sessionId。
6. 第 328 行：仅 respond 路径读取服务。
7. 第 329 行：兼容读取失败标记方法。
8. 第 330 行：检查 sessionId 和方法。
9. 第 331 行：满足时标记；本接口不执行。

`_request_context` 文件：`python-agent/app/api/application.py:379-388`

1. 第 379 行：定义上下文恢复。
2. 第 380 行：读取已记住上下文。
3. 第 381 行：检查映射。
4. 第 382 行：是映射直接返回。
5. 第 383 行：进入 body 解析保护。
6. 第 384 行：读取 body。
7. 第 385 行：非空解析 JSON。
8. 第 386 行：根节点字典才返回。
9. 第 387 行：捕获 JSON、Unicode 和运行时错误。
10. 第 388 行：失败返回空字典。

`_error_response` 文件：`python-agent/app/api/application.py:397-411`

1. 第 397 行：定义 `_error_response` 异步函数并开始多行签名。
2. 第 398 行：接收当前 `Request`、原始异常和可选协议上下文映射。
3. 第 399 行：声明返回类型为 `AgentResponse` 并结束函数签名。
4. 第 400 行：显式上下文非空时直接使用；否则调用项目函数 `_request_context(request)` 从请求状态或请求体恢复上下文。
5. 第 401 行：读取 `sessionStatus` 并调用项目函数 `_session_status_or_failed` 转换为协议枚举。
6. 第 402 行：读取未经信任的 `stateVersion`，下一步再检查其类型和范围。
7. 第 403 行：开始构造统一失败 `AgentResponse`；构造期间会触发项目校验函数 `validate_code_category`。
8. 第 404 行：读取 `apiVersion` 并调用项目函数 `_string_or_none` 清洗。
9. 第 405 行：读取 `requestId` 并调用 `_string_or_none` 清洗。
10. 第 406 行：清洗 `runId`，同时调用项目函数 `ExceptionHandler.to_code(error)` 生成协议错误码。
11. 第 407 行：把运行状态固定为 `FAILED`，并清洗 `userId`。
12. 第 408 行：清洗 `sessionId`，并写入第 401 行解析出的会话状态。
13. 第 409 行：仅接受非负整数版本；缺失、类型不符或为负数时回退为 `0`。
14. 第 410 行：失败响应不返回答案，把当前阶段设为 `FAILED`，并调用项目函数 `ExceptionHandler.to_error_info(error)` 构造错误详情。
15. 第 411 行：结束 `AgentResponse` 构造并返回统一失败响应。

`_string_or_none` 文件：`python-agent/app/api/application.py:414-415`

1. 第 414 行：定义字符串清洗。
2. 第 415 行：非空字符串返回，否则 None。

`_session_status_or_failed` 文件：`python-agent/app/api/application.py:418-423`

1. 第 418 行：定义状态转换。
2. 第 419 行：说明运行失败不能误改现有会话。
3. 第 420 行：进入保护。
4. 第 421 行：构造 SessionStatus。
5. 第 422 行：捕获类型和值错误。
6. 第 423 行：失败回退 FAILED。

`ExceptionHandler.to_code` 文件：`python-agent/app/common/exceptions.py:139-146`

1. 第 139 行：声明类方法。
2. 第 140 行：定义 code 转换。
3. 第 141 行：识别项目异常。
4. 第 142 行：返回项目 code。
5. 第 143 行：遍历内置映射。
6. 第 144 行：按类型匹配。
7. 第 145 行：返回映射 code。
8. 第 146 行：未知返回 500。

`ExceptionHandler.to_error_info` 文件：`python-agent/app/common/exceptions.py:116-137`

1. 第 116 行：声明类方法。
2. 第 117 行：定义 ErrorInfo 转换。
3. 第 118 行：识别项目异常。
4. 第 119 行：开始构造。
5. 第 120 行：写 errorType。
6. 第 121 行：写 message。
7. 第 122 行：写 retryable。
8. 第 123 行：返回。
9. 第 125 行：遍历内置映射。
10. 第 126 行：按类型匹配。
11. 第 127 行：内置异常类型命中后开始构造并返回 `ErrorInfo`。
12. 第 128 行：把映射中的 `error_name` 写入外部错误类型。
13. 第 129 行：优先使用原异常字符串；消息为空时使用 `error_name`，保证响应始终有可读消息。
14. 第 130 行：把内置映射声明的 `retryable` 布尔值写入错误详情。
15. 第 131 行：结束并返回内置异常对应的 `ErrorInfo`。
16. 第 133 行：没有任何项目异常或内置异常匹配时，开始构造兜底 `ErrorInfo`。
17. 第 134 行：把兜底错误类型固定为 `INTERNAL_ERROR`。
18. 第 135 行：使用不暴露内部异常细节的固定外部消息。
19. 第 136 行：把未知错误标记为不可重试。
20. 第 137 行：结束并返回兜底 `ErrorInfo`。

`_error_json_response` 文件：`python-agent/app/api/application.py:447-455`

1. 第 447 行：定义 `_error_json_response` 异步函数并开始多行签名。
2. 第 448 行：接收当前 FastAPI `Request`。
3. 第 449 行：接收需要转换的原始异常。
4. 第 450 行：使用 `*` 把后续参数限定为仅可按关键字传入，避免状态码和上下文位置混淆。
5. 第 451 行：接收最终 HTTP 状态码；项目异常可保持协议约定的 HTTP 200，未知异常使用 HTTP 500。
6. 第 452 行：接收可选请求上下文；请求校验失败处理器会尽量直接传入原始请求体映射。
7. 第 453 行：声明返回 `JSONResponse` 并结束函数签名。
8. 第 454 行：调用项目函数 `_error_response(request, error, context)` 构造协议层 `AgentResponse`。
9. 第 455 行：调用项目函数 `AgentResponse.to_json_dict()` 转成别名化 JSON 字典，再以指定 HTTP 状态码返回 `JSONResponse`。

`AgentResponse.to_json_dict` 文件：`python-agent/app/common/contracts.py:184-185`

1. 第 184 行：定义 JSON 导出。
2. 第 185 行：以 JSON 模式、别名并保留 null 导出。

`ApplicationException.__init__` 文件：`python-agent/app/common/exceptions.py:15-19`

该继承构造函数会在本接口创建 `RequestError`、`PersistenceConfigurationError`、`ReliabilityConfigurationError`、`RagConfigurationError`、`AgentDependencyError` 或 `RagDependencyError` 时执行。

1. 第 15 行：定义项目异常基类构造函数；接收对外消息，以及可选的实例级 `retryable` 覆盖值。
2. 第 16 行：调用 Python `Exception` 基类构造函数，使标准异常参数、字符串表示和堆栈机制正常工作。
3. 第 17 行：把消息另存为 `self.message`，供 `ExceptionHandler.to_error_info` 直接读取。
4. 第 18 行：检查调用方是否显式提供了 `retryable`；`None` 表示沿用具体异常类的类属性。
5. 第 19 行：提供覆盖值时写入实例属性，例如重试执行器在耗尽次数后显式保留可重试语义。

## 4. 主流构建分析

主流知识库索引通常采用异步增量流水线：上传后生成 documentVersion，Worker 分阶段执行解析、切片、向量化和批量 upsert，以 staging 版本构建完成后再原子切换 activeVersion；大文档会使用批任务、失败重试、进度事件和内容哈希去重。优点是不会在同步接口内长时间占用连接，旧索引在新版本完成前仍可查询，失败可从批次断点恢复；缺点是需要任务状态、版本垃圾回收和更复杂的存储一致性。

本项目当前以知识库级 asyncio.Lock 串行替换，同一 Python 实例内简单可靠，并已有批量 embedding、PostgreSQL 事务替换和缓存失效；但多实例锁不共享，且删除旧切片后若事务/进程策略变化需特别保证原子性。数据量较小时现状适配。若扩展，可增加 `rag_index_runs` 与 `document_versions`，使用 PostgreSQL advisory lock 或分布式任务队列协调同库任务，先写 staging version，全部向量成功后事务更新 activeVersion；现有 TokenChunker、embedding 批处理和 Redis 失效逻辑可复用。
