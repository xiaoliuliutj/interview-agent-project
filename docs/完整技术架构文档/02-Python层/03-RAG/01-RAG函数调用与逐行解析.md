# RAG：索引、检索、删除函数调用与逐行解析

## 1. 接口定义

RAG 模块向外由 `/v1/agent/rag/index` 和 `/v1/agent/rag/delete` 使用，向内由面试出题链通过 `RagSearchTool` 检索。它负责文档解析、标题优先 Token 切片、embedding、pgvector 仓储调用、元数据过滤回退、缓存和同知识库并发锁。

## 2. 函数调用链

```text
index_document -> TokenChunker.split -> _split_by_headings -> append_current -> _split_section
 -> _lock_for -> OpenAIEmbeddingProvider.embed_documents -> AsyncRetryExecutor.execute
 -> VectorRepository.replace_for_knowledge_base -> invalidate_cache

search_for_question_generation -> RagService.search
 -> OpenAIEmbeddingProvider.embed_query -> repository.search
 -> （RagFilterUnsupported）repository.search(无过滤) -> Python 二次过滤 -> 缓存

delete_knowledge_base -> _lock_for -> repository.delete_by_knowledge_base -> invalidate_cache
```

## 3. 函数解析

### 3.1 `RagService.__init__`

文件：`python-agent/app/rag/service.py:17-33`

```python
    def __init__(
        self,
        repository: VectorRepository,
        embedding_provider: EmbeddingProvider,
        policy: RagPolicy,
        parser: KnowledgeDocumentParser | None = None,
    ) -> None:
        self._repository = repository
        self._embedding_provider = embedding_provider
        self._policy = policy
        self._parser = parser or KnowledgeDocumentParser()
        self._chunker = TokenChunker(
            chunk_size_tokens=policy.chunk_size_tokens,
            overlap_tokens=policy.chunk_overlap_tokens,
        )
        self._search_cache: dict[str, tuple[float, list[RagSearchResult]]] = {}
        self._knowledge_base_locks: dict[str, asyncio.Lock] = {}
```

逐行解释：

1. 第 17-23 行声明向量仓储、embedding、策略和可替换解析器。
2. 第 24-27 行保存依赖；没有解析器时创建真实文档解析器。
3. 第 28-31 行从策略创建 TokenChunker，切片大小与重叠不由请求控制。
4. 第 32-33 行初始化搜索缓存和按知识库 ID 保存的异步锁表。

### 3.2 `RagService.index_document`

文件：`python-agent/app/rag/service.py:35-54`

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
        self.invalidate_cache()
        return len(chunks)
```

逐行解释：切片器先生成 chunks；同库锁包住向量生成和替换。循环按策略批量 embedding，严格检查数量，再按 zip 写回向量。仓储只在全部批次成功后替换。已有 RAG 错误原样抛出，其他错误统一包装。离开锁后清缓存并返回数量。

### 3.3 `delete_knowledge_base`、`invalidate_cache`、`_lock_for`

文件：`python-agent/app/rag/service.py:56-61,128-136`

```python
    async def delete_knowledge_base(self, knowledge_base_id: str) -> None:
        if not knowledge_base_id.strip():
            raise ValueError("knowledge_base_id is required")
        async with self._lock_for(knowledge_base_id):
            await self._repository.delete_by_knowledge_base(knowledge_base_id)
        self.invalidate_cache()

    def invalidate_cache(self) -> None:
        self._search_cache.clear()

    def _lock_for(self, knowledge_base_id: str) -> asyncio.Lock:
        lock = self._knowledge_base_locks.get(knowledge_base_id)
        if lock is None:
            lock = asyncio.Lock()
            self._knowledge_base_locks[knowledge_base_id] = lock
        return lock
```

逐行解释：

1. 删除函数拒绝空 ID，在同库锁内删除，完成后清空所有查询缓存。
2. `invalidate_cache` 只调用字典 clear，不保留可能引用旧向量的结果。
3. `_lock_for` 先查锁表；不存在时创建并缓存，最后总返回同知识库共享锁。

### 3.4 `index_file`

文件：`python-agent/app/rag/service.py:63-72`

```python
    async def index_file(
        self,
        path: Path,
        *,
        knowledge_base_id: str,
        document_id: str,
    ) -> int:
        return await self.index_document(self._parser.parse_file(
            path, knowledge_base_id=knowledge_base_id, document_id=document_id
        ))
```

逐行解释：声明路径和两个业务 ID；先调用项目解析器把文件转成 `KnowledgeDocument`，再复用 `index_document` 的完整切片、向量和替换链，返回切片数。

### 3.5 `RagService.search`

文件：`python-agent/app/rag/service.py:74-126`

```python
    async def search(
        self, query: str, *, use_case: RagUseCase,
        knowledge_base_ids: tuple[str, ...] | None = None,
        top_k: int | None = None, min_score: float | None = None,
    ) -> list[RagSearchResult]:
        if use_case not in self._policy.allowed_use_cases:
            raise ValueError(f"RAG use case is not allowed: {use_case}")
        normalized_query = query.strip()
        if not normalized_query:
            return []
        if not knowledge_base_ids:
            raise ValueError("knowledge_base_ids must be provided explicitly")
        selected_kbs = tuple(dict.fromkeys(knowledge_base_ids))
        selected_top_k = top_k or self._policy.default_top_k
        selected_min_score = self._policy.default_min_score if min_score is None else min_score
        cache_key = "|".join([
            str(use_case), ",".join(sorted(selected_kbs)), normalized_query.lower(),
            str(selected_top_k), str(selected_min_score),
        ])
        cached = self._search_cache.get(cache_key)
        if cached and (
            self._policy.cache_ttl_seconds == 0
            or monotonic() - cached[0] < self._policy.cache_ttl_seconds
        ):
            return [item.model_copy(deep=True) for item in cached[1]]
        query_vector = await self._embedding_provider.embed_query(normalized_query)
        try:
            results = await self._repository.search(
                query_vector, top_k=selected_top_k, min_score=selected_min_score,
                knowledge_base_ids=selected_kbs, apply_metadata_filter=True,
            )
        except RagFilterUnsupported:
            fallback = await self._repository.search(
                query_vector,
                top_k=selected_top_k * self._policy.fallback_candidate_multiplier,
                min_score=selected_min_score,
                knowledge_base_ids=selected_kbs,
                apply_metadata_filter=False,
            )
            results = [
                result for result in fallback
                if result.chunk.knowledge_base_id in selected_kbs
            ][:selected_top_k]
        self._search_cache[cache_key] = (
            monotonic(), [item.model_copy(deep=True) for item in results]
        )
        while len(self._search_cache) > self._policy.cache_max_entries:
            self._search_cache.pop(next(iter(self._search_cache)))
        return results
```

逐行解释：

1. useCase 必须在策略白名单；空查询返回空，但知识库列表缺失直接报错，禁止跨库默认搜索。
2. 知识库按原顺序去重；topK、最低分缺失时采用策略默认值。
3. 缓存键包含用途、排序后的库、规范化查询和两个阈值；命中且 TTL 有效时返回深拷贝。
4. 未命中先生成查询向量，再请求仓储执行带元数据过滤的相似度搜索。
5. 仓储不支持过滤时扩大候选数无过滤检索，再在 Python 中按 knowledgeBaseId 过滤并截断。
6. 缓存保存结果深拷贝；超过最大条目时删除最早插入项；最后返回本次结果。

### 3.6 `RagSearchTool.__init__` 与 `search_for_question_generation`

文件：`python-agent/app/rag/service.py:142-152`

```python
    def __init__(self, service: RagService) -> None:
        self._service = service

    async def search_for_question_generation(
        self, query: str, *, knowledge_base_ids: tuple[str, ...] | None = None
    ) -> list[RagSearchResult]:
        return await self._service.search(
            query,
            use_case=RagUseCase.QUESTION_GENERATION,
            knowledge_base_ids=knowledge_base_ids,
        )
```

逐行解释：构造函数保存服务；搜索函数只暴露查询和库列表，并强制用途为 `QUESTION_GENERATION`，再等待 `RagService.search`。

### 3.7 `KnowledgeDocumentParser.parse_file`

文件：`python-agent/app/rag/parser.py:14-41`

```python
    def parse_file(
        self, path: Path, *, knowledge_base_id: str, document_id: str
    ) -> KnowledgeDocument:
        if not path.is_file():
            raise RagConfigurationError(f"RAG 资料不存在: {path.name}")
        suffix = path.suffix.lower()
        if suffix in {".txt", ".md"}:
            content = path.read_text(encoding="utf-8")
        elif suffix == ".pdf":
            from pypdf import PdfReader
            content = "\n".join(page.extract_text() or "" for page in PdfReader(path).pages)
        elif suffix == ".docx":
            from docx import Document
            content = "\n".join(paragraph.text for paragraph in Document(path).paragraphs)
        else:
            raise RagConfigurationError(f"不支持的 RAG 资料格式: {suffix}")
        return KnowledgeDocument(
            knowledge_base_id=knowledge_base_id, document_id=document_id,
            source_name=path.name, content=content.strip(),
        )
```

逐行解释：确认路径是文件；按小写后缀选择 UTF-8 文本、PDF 每页提取或 DOCX 段落提取；其他格式拒绝。最后保留业务 ID、文件名和 trim 后正文构造领域文档。

### 3.8 `TokenChunker.__init__`

文件：`python-agent/app/rag/parser.py:47-52`

```python
    def __init__(self, *, chunk_size_tokens: int, overlap_tokens: int) -> None:
        if chunk_size_tokens < 1 or overlap_tokens < 0 or overlap_tokens >= chunk_size_tokens:
            raise RagConfigurationError("切片重叠 Token 必须小于切片大小")
        self._encoding = tiktoken.get_encoding("cl100k_base")
        self._chunk_size = chunk_size_tokens
        self._overlap_tokens = overlap_tokens
```

逐行解释：切片必须大于零，重叠不能为负或达到切片大小；通过后固定 cl100k_base 编码并保存两个预算。

### 3.9 `TokenChunker.split`

文件：`python-agent/app/rag/parser.py:54-78`

```python
    def split(self, document: KnowledgeDocument) -> list[KnowledgeChunk]:
        sections = self._split_by_headings(document.content)
        chunks: list[KnowledgeChunk] = []
        for heading_path, heading_level, section_content in sections:
            for content, section_part_index in self._split_section(
                heading_path, section_content
            ):
                chunks.append(
                    KnowledgeChunk(
                        chunk_id=f"{document.document_id}:{len(chunks)}",
                        knowledge_base_id=document.knowledge_base_id,
                        document_id=document.document_id,
                        source_name=document.source_name,
                        chunk_index=len(chunks), content=content,
                        metadata={**document.metadata,
                            "chunkingStrategy": "heading_then_token",
                            "headingPath": " > ".join(item[1] for item in heading_path),
                            "headingLevel": str(heading_level),
                            "sectionPartIndex": str(section_part_index)},
                    )
                )
        return chunks
```

逐行解释：先按 Markdown 标题划分 section；每个 section 再按 Token 切分。每片 ID 由 documentId 与当前序号组成，并复制知识库、文档、来源和正文；metadata 合并原元数据及切片策略、标题路径、标题级别和段内序号，最终返回有序列表。

### 3.10 `_split_by_headings`

文件：`python-agent/app/rag/parser.py:80-116`

```python
    def _split_by_headings(self, content: str) -> list[tuple[list[tuple[int, str]], int, str]]:
        heading_path: list[tuple[int, str]] = []
        current_lines: list[str] = []
        sections: list[tuple[list[tuple[int, str]], int, str]] = []
        in_fenced_code_block = False
        def append_current() -> None:
            text = "\n".join(current_lines).strip()
            if text:
                sections.append((heading_path.copy(), heading_path[-1][0] if heading_path else 0, text))
        for line in content.splitlines():
            if line.lstrip().startswith("```"):
                current_lines.append(line)
                in_fenced_code_block = not in_fenced_code_block
                continue
            if in_fenced_code_block:
                current_lines.append(line)
                continue
            match = self._HEADING_PATTERN.match(line)
            if not match:
                current_lines.append(line)
                continue
            append_current()
            current_lines = []
            level = len(match.group(1))
            title = match.group(2).strip()
            heading_path = [item for item in heading_path if item[0] < level]
            heading_path.append((level, title))
        append_current()
        if sections:
            return sections
        return [([], 0, content.strip())] if content.strip() else []
```

逐行解释：维护标题路径、当前正文和代码围栏状态。局部函数把非空正文连同标题路径副本加入 sections。遍历中代码块内的 `#` 不识别为标题；普通标题出现时先提交上一节，再按级别裁剪父路径并追加新标题。循环后提交最后一节；没有标题但有正文时返回无标题单节，空正文返回空列表。

### 3.11 `_split_section`

文件：`python-agent/app/rag/parser.py:118-143`

```python
    def _split_section(self, heading_path: list[tuple[int, str]],
                       section_content: str) -> list[tuple[str, int]]:
        heading_prefix = "\n".join(f"{'#' * level} {title}" for level, title in heading_path)
        if heading_prefix:
            heading_prefix += "\n\n"
        prefix_tokens = self._encoding.encode(heading_prefix)
        if len(prefix_tokens) >= self._chunk_size:
            raise RagConfigurationError("RAG 标题路径超过切片 Token 上限")
        body_tokens = self._encoding.encode(section_content)
        available_body_tokens = self._chunk_size - len(prefix_tokens)
        overlap = min(self._overlap_tokens, available_body_tokens - 1)
        step = available_body_tokens - overlap
        result: list[tuple[str, int]] = []
        for part_index, start in enumerate(range(0, len(body_tokens), step)):
            body = self._encoding.decode(body_tokens[start : start + available_body_tokens]).strip()
            if body:
                result.append((f"{heading_prefix}{body}".strip(), part_index))
        return result
```

逐行解释：把完整标题路径重复为前缀并编码；前缀本身达到预算就报配置错。正文可用预算等于总预算减前缀；重叠最多为可用预算减一，确保 step 大于零。按 step 滑窗解码正文，非空片重新附加标题前缀和片内序号。

### 3.12 `OpenAIEmbeddingProvider` 三个函数

文件：`python-agent/app/rag/embedding.py:19-51`

```python
    def __init__(self, settings: Settings, retry_executor: AsyncRetryExecutor | None = None) -> None:
        if not settings.embedding_model:
            raise RagConfigurationError("EMBEDDING_MODEL 未配置")
        kwargs: dict[str, object] = {
            "model": settings.embedding_model,
            "api_key": settings.embedding_api_key or settings.model_api_key,
            "timeout": min(settings.request_timeout_seconds, 120),
            "max_retries": 0,
            "check_embedding_ctx_length": False,
        }
        base_url = settings.embedding_base_url or settings.model_base_url
        if base_url:
            kwargs["base_url"] = base_url
        self._client = OpenAIEmbeddings(**kwargs)
        self._retry_executor = retry_executor

    async def embed_documents(self, texts: list[str]) -> list[list[float]]:
        if self._retry_executor is None:
            return await self._client.aembed_documents(texts)
        return await self._retry_executor.execute(lambda: self._client.aembed_documents(texts))

    async def embed_query(self, text: str) -> list[float]:
        if self._retry_executor is None:
            return await self._client.aembed_query(text)
        return await self._retry_executor.execute(lambda: self._client.aembed_query(text))
```

逐行解释：构造函数要求模型名，组合专用/通用密钥，超时不超过 120 秒，关闭 SDK 自带重试和上下文再切分，按需设置 baseUrl，再创建客户端。两个异步方法在没有执行器时直调 SDK，有执行器时用 lambda 交给统一重试策略。

## 4. 审核结论

RAG 的索引、文件解析、标题切片、向量生成、检索回退、缓存、删除和面试搜索适配函数均已附源码与语句说明；框架 SDK 方法只作为边界标明。
