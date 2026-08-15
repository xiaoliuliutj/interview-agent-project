# POST /v1/agent/rag/delete：删除知识库索引

## 1. 接口定义

该接口删除指定知识库下的全部向量切片并清除检索缓存，不删除 Java 知识库业务元数据，也不触及其他知识库。

## 2. 函数调用链

```text
delete_rag -> _remember_request_context -> _resolve_rag_service
 -> RagService.delete_knowledge_base -> _lock_for
 -> VectorRepository.delete_by_knowledge_base -> invalidate_cache -> AgentResponse
```

## 3. 函数解析

### 3.1 `delete_rag`

文件：`python-agent/app/api/application.py:240-250`

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

1. 第 240-241 行：注册删除接口并接收删除请求。
2. 第 242 行：缓存请求上下文。
3. 第 243 行：懒加载 RAG 服务并按知识库 ID 删除。
4. 第 244-250 行：返回固定完成响应；删除操作没有答案或输出对象。

### 3.2 `RagService.delete_knowledge_base`

文件：`python-agent/app/rag/service.py:56-61`

```python
    async def delete_knowledge_base(self, knowledge_base_id: str) -> None:
        if not knowledge_base_id.strip():
            raise ValueError("knowledge_base_id is required")
        async with self._lock_for(knowledge_base_id):
            await self._repository.delete_by_knowledge_base(knowledge_base_id)
        self.invalidate_cache()
```

逐行解释：

1. 第 56 行：定义异步删除函数。
2. 第 57-58 行：拒绝空白知识库 ID，避免不明确的删除范围。
3. 第 59 行：取得同知识库锁。
4. 第 60 行：委托向量仓储删除该知识库全部切片。
5. 第 61 行：删除完成后清理检索缓存。

## 4. 审核结论

删除范围由 `knowledge_base_id` 明确限定，锁与缓存失效都属于实际调用链。
