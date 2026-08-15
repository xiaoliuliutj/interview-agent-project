# POST /v1/agent/rag/index：索引知识文档

## 1. 接口定义

该接口把文档内容包装成 `KnowledgeDocument`，按标题和 Token 预算切片，批量生成向量并替换指定知识库的旧索引，返回新切片数量。

| 项目 | 内容 |
|---|---|
| HTTP 方法 | POST |
| 路径 | `/v1/agent/rag/index` |
| 路由函数 | `index_rag` |
| 文件 | `python-agent/app/api/application.py:223-238` |

## 2. 函数调用链

```text
index_rag -> _remember_request_context -> _resolve_rag_service
 -> KnowledgeDocument -> RagService.index_document
 -> TokenChunker.split -> _lock_for -> EmbeddingProvider.embed_documents
 -> VectorRepository.replace_for_knowledge_base -> invalidate_cache -> AgentResponse
```

## 3. 函数解析

### 3.1 `index_rag`

文件：`python-agent/app/api/application.py:223-238`

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

1. 第 223-224 行：注册 RAG 索引接口并接收请求。
2. 第 225 行：保存请求上下文。
3. 第 226-232 行：解析服务并构造领域文档；当前实现明确取 `knowledge_base_ids[0]`。
4. 第 233 行：等待服务完成切片、向量化和替换，得到切片数。
5. 第 234-238 行：返回完成响应，计数转成字符串答案，不返回向量和正文。

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

逐行解释：

1. 第 35-36 行：定义入口并先调用切片器。
2. 第 37 行：按知识库取得异步锁，避免同库并发替换。
3. 第 38-41 行：进入错误归一化区域并按策略批大小截取切片。
4. 第 42-44 行：只把正文批量交给 embedding provider。
5. 第 45-46 行：向量数与切片数不一致时抛 RAG 依赖错误。
6. 第 47-48 行：把每个向量写回对应切片。
7. 第 49 行：原子替换该知识库的旧切片。
8. 第 50-53 行：保留已有 RAG 错误，其他异常统一包装。
9. 第 54 行前：清空检索缓存；最后返回新切片数量。

## 4. 审核结论

索引入口的所有下游类别已纳入链路，切片器、向量提供者和仓储在 RAG 模块文档继续逐函数解析。
