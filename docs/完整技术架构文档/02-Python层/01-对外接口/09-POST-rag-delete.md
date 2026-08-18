# POST /v1/agent/rag/delete：删除知识库索引

## 1. 接口定义

该接口删除指定知识库下的全部向量切片并清除检索缓存，不删除 Java 知识库业务元数据，也不触及其他知识库。

| 项目 | 内容 |
|---|---|
| HTTP 方法 | POST |
| 路径 | `/v1/agent/rag/delete` |
| 请求模型 | `AgentRagDeleteRequest` |
| 响应模型 | `AgentResponse` |
| 路由函数 | `delete_rag` |
| 路由源码 | `python-agent/app/api/application.py:243-253` |

## 2. 函数调用链

```text
请求进入 FastAPI
  -> AgentRagDeleteRequest 字段约束校验
  -> delete_rag
     -> _remember_request_context
     -> _resolve_rag_service
        -> [已缓存] 返回 request.app.state.rag_service
        -> [首次请求] build_rag_service
           -> get_settings
           -> create_session_factory -> create_engine
           -> RetryPolicy.load -> AsyncRetryExecutor.__init__
           -> PostgresRagVectorRepository.__init__
           -> OpenAIEmbeddingProvider.__init__
           -> RagPolicy.load
           -> build_cache -> RedisCache.__init__
           -> RagService.__init__ -> TokenChunker.__init__
     -> RagService.delete_knowledge_base
        -> RagService._lock_for
        -> PostgresRagVectorRepository.delete_by_knowledge_base
        -> RagService.invalidate_cache
           -> [配置 Redis 时] RedisCache.delete_matching
     -> AgentResponse -> AgentResponse.validate_code_category
  -> FastAPI 按 response_model 序列化成功响应

请求校验失败：RequestValidationError
  -> request_validation_error -> RequestError -> ApplicationException.__init__
  -> _error_json_response -> _error_response
  -> _request_context / _session_status_or_failed / _string_or_none
  -> ExceptionHandler.to_code / ExceptionHandler.to_error_info
  -> AgentResponse.validate_code_category -> AgentResponse.to_json_dict

项目异常：ApplicationException
  -> application_error -> _mark_failed_interview_progress
  -> _error_json_response -> _error_response -> _request_context
  -> _session_status_or_failed / _string_or_none
  -> ExceptionHandler.to_code / ExceptionHandler.to_error_info
  -> AgentResponse.validate_code_category -> AgentResponse.to_json_dict

未预期异常：Exception
  -> unexpected_error -> _mark_failed_interview_progress
  -> _error_json_response -> _error_response -> _request_context
  -> _session_status_or_failed / _string_or_none
  -> ExceptionHandler.to_code / ExceptionHandler.to_error_info
  -> AgentResponse.validate_code_category -> AgentResponse.to_json_dict
```

## 3. 函数解析

### 3.1 `delete_rag`

文件：`python-agent/app/api/application.py:243-253`

```python
    @app.post("/v1/agent/rag/delete", response_model=AgentResponse)
    async def delete_rag(payload: AgentRagDeleteRequest, request: Request) -> AgentResponse:
        _remember_request_context(request, payload)
        await _resolve_rag_service(request).delete_knowledge_base(payload.knowledge_base_id)
        return AgentResponse(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, code=100, status=RunStatus.COMPLETED,
            user_id=payload.user_id, session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE, state_version=0,
            answer=None, output=None, error=None,
        )
```

逐行解释：

1. 第 243 行：用 `@app.post` 注册 `POST /v1/agent/rag/delete`，并要求成功结果满足 `AgentResponse`。
2. 第 244 行：定义异步路由；`payload` 已由 FastAPI 按 `AgentRagDeleteRequest` 完成字段校验，`request` 提供应用状态和请求上下文。
3. 第 245 行：调用项目函数 `_remember_request_context`，把协议字段保存到 `request.state`，供统一异常响应复用。
4. 第 246 行：先调用项目函数 `_resolve_rag_service(request)` 取得或构建 RAG 服务，再调用项目函数 `delete_knowledge_base`，传入请求中的唯一知识库 ID，并等待删除完成。
5. 第 247 行：开始构造成功 `AgentResponse`；构造期间会执行项目字段校验器 `validate_code_category`。
6. 第 248 行：原样复制 `apiVersion` 和 `requestId`，使响应与调用请求关联。
7. 第 249 行：复制 `runId`，把业务码设为 `100`，并把运行状态设为 `COMPLETED`。
8. 第 250 行：复制 `userId` 和 `sessionId`。
9. 第 251 行：删除知识库索引不推进面试状态，因此响应会话状态固定为 `ACTIVE`，状态版本固定为 `0`。
10. 第 252 行：删除接口没有答案和结构化业务输出，故 `answer`、`output`、`error` 均为 `None`。
11. 第 253 行：结束响应构造并返回给 FastAPI。

### 3.2 `RagService.delete_knowledge_base`

文件：`python-agent/app/rag/service.py:60-65`

```python
    async def delete_knowledge_base(self, knowledge_base_id: str) -> None:
        if not knowledge_base_id.strip():
            raise ValueError("knowledge_base_id is required")
        async with self._lock_for(knowledge_base_id):
            await self._repository.delete_by_knowledge_base(knowledge_base_id)
        await self.invalidate_cache()
```

逐行解释：

1. 第 60 行：定义异步删除方法，接收待清理的 `knowledge_base_id`，成功时无返回值。
2. 第 61 行：先调用字符串 `strip()` 去除首尾空白，再判断结果是否为空，避免空白 ID 形成含义不明确的删除条件。
3. 第 62 行：ID 为空时抛出 `ValueError`；该异常会由 FastAPI 的统一异常处理链转换为失败响应。
4. 第 63 行：调用项目函数 `_lock_for(knowledge_base_id)` 取得当前 Python 进程内的知识库级异步锁，并进入锁上下文。
5. 第 64 行：在锁内调用项目函数 `PostgresRagVectorRepository.delete_by_knowledge_base`，等待数据库删除和事务提交完成后才释放锁。
6. 第 65 行：数据库删除成功后调用并等待项目函数 `invalidate_cache`，清空进程内缓存并尽力清理 Redis 搜索缓存。

### 3.3 `RagService._lock_for`

文件：`python-agent/app/rag/service.py:150-155`

1. 第 150 行：定义按知识库 ID 返回 `asyncio.Lock` 的项目函数。
2. 第 151 行：从 `_knowledge_base_locks` 字典读取该知识库已有的锁。
3. 第 152 行：判断锁是否尚未创建。
4. 第 153 行：不存在时创建新的进程内 `asyncio.Lock`。
5. 第 154 行：以知识库 ID 为键保存新锁，保证同一进程后续删除或索引复用同一把锁。
6. 第 155 行：返回已有或新建的锁。

### 3.4 `PostgresRagVectorRepository.delete_by_knowledge_base`

文件：`python-agent/app/infrastructure/persistence/rag_vector_repository.py:31-40`

1. 第 31 行：定义按知识库删除全部向量切片的异步仓储方法。
2. 第 32 行：在方法内部导入 SQLAlchemy `delete` 构造器。
3. 第 34 行：调用会话工厂创建异步数据库会话，并进入自动关闭上下文。
4. 第 35 行：调用 `db_session.execute(...)` 执行删除语句。
5. 第 36 行：以 `RagChunkEntity` 表实体作为删除目标，并开始设置筛选条件。
6. 第 37 行：限定实体的 `knowledge_base_id` 必须等于方法参数，删除范围不会扩展到其他知识库。
7. 第 38 行：结束 SQLAlchemy `where(...)` 调用。
8. 第 39 行：结束 `execute(...)` 并等待数据库完成删除操作。
9. 第 40 行：提交事务，使本次删除持久生效；提交失败会抛给 `RagService` 上层异常处理链。

### 3.5 `RagService.invalidate_cache`

文件：`python-agent/app/rag/service.py:145-148`

1. 第 145 行：定义异步缓存失效项目函数。
2. 第 146 行：清空当前 Python 进程的 `_search_cache`，避免继续返回已删除知识库的本地检索结果。
3. 第 147 行：检查服务是否注入了 `RedisCache`。
4. 第 148 行：配置 Redis 时调用项目函数 `delete_matching("python:rag:search:*")`，使跨请求共享的 RAG 查询缓存失效。

### 3.6 `RedisCache.delete_matching`

文件：`python-agent/app/infrastructure/cache/redis_cache.py:59-68`

1. 第 59 行：定义按模式异步删除缓存键的项目函数。
2. 第 60 行：文档字符串声明该操作只适用于小型、有界缓存命名空间。
3. 第 61 行：检查 Redis 客户端是否未配置。
4. 第 62 行：客户端不存在时立即返回；数据库删除结果不依赖 Redis 可用性。
5. 第 63 行：进入 Redis 异常保护块。
6. 第 64 行：使用异步 `scan_iter` 扫描匹配 `python:rag:search:*` 的键，`count=200` 是每轮扫描数量提示，并把结果收集为列表。
7. 第 65 行：判断是否扫描到至少一个匹配键。
8. 第 66 行：有匹配项时调用 Redis `DELETE` 一次删除这些键。
9. 第 67 行：捕获 Redis 客户端抛出的 `RedisError`。
10. 第 68 行：记录警告和异常堆栈，但不把缓存故障抛回删除主链；旧缓存依靠 TTL 最终过期。


### 3.7 `_remember_request_context` 与 `_resolve_rag_service`

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

### 3.8 `build_rag_service`

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

### 3.9 `get_settings` 与数据库工厂

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

### 3.10 `RagPolicy.load`

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

### 3.11 `OpenAIEmbeddingProvider.__init__`

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

### 3.12 `RetryPolicy.load`、`RagService.__init__` 与 `TokenChunker.__init__`

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

### 3.13 `AgentResponse.validate_code_category`

文件：`python-agent/app/common/contracts.py:177-182`

1. 第 177 行：注册 code 字段校验器。
2. 第 178 行：声明类方法。
3. 第 179 行：定义校验。
4. 第 180 行：要求首位类别属于 1~5。
5. 第 181 行：不满足抛 ValueError。
6. 第 182 行：返回合法 code。

### 3.14 FastAPI 异常与统一错误响应

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

该继承构造函数会在本接口创建 `RequestError`、`PersistenceConfigurationError`、`ReliabilityConfigurationError` 或 `RagConfigurationError` 时执行。

1. 第 15 行：定义项目异常基类构造函数；接收对外消息，以及可选的实例级 `retryable` 覆盖值。
2. 第 16 行：调用 Python `Exception` 基类构造函数，使标准异常参数、字符串表示和堆栈机制正常工作。
3. 第 17 行：把消息另存为 `self.message`，供 `ExceptionHandler.to_error_info` 直接读取。
4. 第 18 行：检查调用方是否显式提供了 `retryable`；`None` 表示沿用具体异常类的类属性。
5. 第 19 行：提供覆盖值时写入实例属性，例如重试执行器在耗尽次数后显式保留可重试语义。

## 4. 主流构建分析

主流向量知识库删除通常采用“逻辑删除版本 + 异步物理回收”：接口先把知识库或文档版本标为不可检索并发布清理任务，查询立即通过 activeVersion 或 deletedAt 排除数据，后台 Worker 再分批删除向量、原文和缓存。优点是接口延迟稳定、海量向量删除可重试、失败不会让已声明删除的数据重新可见；缺点是需要任务状态、后台清理器和垃圾数据监控，短时间内仍占用存储空间。

本项目当前使用 PostgreSQL 单事务按 `knowledge_base_id` 物理删除，再失效进程内与 Redis 查询缓存。数据量较小时实现直接、事务边界清晰，且 Redis 故障不会回滚已完成的持久化删除；不足是大量切片会拉长同步请求，进程内 `asyncio.Lock` 不能协调多个 Python 实例，而且当前缓存失效扫描整个 `python:rag:search:*` 命名空间，影响范围大于单个知识库。

本项目在知识库规模扩大后适合引入异步删除任务，但不必立即替换现状。实施时可增加 `rag_delete_jobs` 和知识库 `deleted_at`/`active_version` 字段；接口事务内先标记不可检索并写入 outbox 事件，Worker 按批次删除 `agent_rag_chunks`，成功后删除原文并完成任务。缓存键可增加知识库版本号，删除时递增版本而不是扫描全命名空间；若继续多实例同步删除，应把进程锁替换为 PostgreSQL advisory lock 或受租约保护的分布式锁。
