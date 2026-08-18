# POST /v1/agent/sessions/complete：暂停或完成面试会话

## 1. 接口定义

该接口依据请求中的 `operation` 在“暂停”和“完成”之间分支。暂停只把会话置为 `PAUSED` 并保留恢复能力；完成会生成或兜底最终评价、保存会话、归档长期记忆并返回最终评价。

| 项目 | 内容 |
|---|---|
| HTTP 方法 | POST |
| 路径 | `/v1/agent/sessions/complete` |
| 路由函数 | `complete_session` |
| 请求模型 | `AgentSessionCompletionRequest` |
| 文件 | `python-agent/app/api/application.py:135-157` |

## 2. 函数调用链

```text
complete_session
 -> _remember_request_context
 -> _resolve_service
    -> （冷启动）build_interview_agent_service
       -> get_settings -> create_session_factory -> create_engine
       -> InterviewWorkflow.load -> PromptLoader.load -> PromptLoader._resolve
       -> LLMFactory.create_chat_model
       -> RetryPolicy.load / IdempotencyPolicy.load
       -> build_cache / build_memory_service -> MemoryPolicy.load
       -> （配置 embedding）build_rag_service -> RagPolicy.load / build_cache
 -> operation == "agent.session.pause"
    -> InterviewAgentService.pause_session
       -> PostgresInterviewSessionRepository.get
          -> _cache_key -> RedisCache.get_json
          -> （缓存损坏）RedisCache.delete
          -> （数据库回源）_from_entity -> _cache_session -> RedisCache.set_json
       -> （终态）直接返回
       -> _validate_expected_state
       -> PostgresInterviewSessionRepository.save
          -> （版本冲突）_cache_key -> RedisCache.delete -> ConsistencyError
          -> （成功）_cache_session -> _cache_key -> RedisCache.set_json
 -> 否则 InterviewAgentService.complete_session
       -> PostgresInterviewSessionRepository.get
          -> _cache_key -> RedisCache.get_json
          -> （缓存损坏）RedisCache.delete
          -> （数据库回源）_from_entity -> _cache_session -> RedisCache.set_json
       -> （COMPLETED）直接返回
       -> （FAILED）MemoryService.finalize_session -> PostgresLongTermMemoryRepository.get/save
       -> _validate_expected_state -> _report_progress -> RedisCache.set_json
       -> _fallback_summary
       -> InterviewSummaryAgent.summarize（有 turns 且 Agent 可用）
          -> PromptLoader.render -> PromptLoader.load -> PromptLoader._resolve
          -> StructuredOutputInvoker.invoke
             -> _few_shot_output -> _invoke_model -> AsyncRetryExecutor.execute
             -> _validate -> _content_as_text -> _strip_json_fence
             -> （格式错误）_readable_validation_error -> 有限纠错
       -> _fallback_evaluation（模型总结失败或为空）
       -> PostgresInterviewSessionRepository.save
          -> （版本冲突）_cache_key -> RedisCache.delete -> ConsistencyError
          -> （成功）_cache_session -> _cache_key -> RedisCache.set_json
       -> MemoryService.finalize_session
          -> PostgresLongTermMemoryRepository.get
          -> _append_summary / _merge_items
          -> PostgresLongTermMemoryRepository.save
       -> _report_progress -> RedisCache.set_json
 -> _success_response
    -> AgentResponse.validate_code_category
```

异常分支：

```text
请求模型校验失败
 -> request_validation_error -> _error_json_response
 -> _error_response -> _request_context / _session_status_or_failed / _string_or_none
 -> ExceptionHandler.to_code / ExceptionHandler.to_error_info
 -> AgentResponse.validate_code_category -> AgentResponse.to_json_dict

ApplicationException
 -> application_error -> _mark_failed_interview_progress（本路径立即返回）
 -> _error_json_response -> 其余统一错误链

其他 Exception
 -> unexpected_error -> 记录异常日志
 -> _mark_failed_interview_progress（本路径立即返回）
 -> _error_json_response -> 其余统一错误链
```

## 3. 函数解析

### 3.1 `complete_session` 路由函数

文件：`python-agent/app/api/application.py:135-157`

```python
    @app.post("/v1/agent/sessions/complete", response_model=AgentResponse)
    async def complete_session(payload: AgentSessionCompletionRequest, request: Request) -> AgentResponse:
        _remember_request_context(request, payload)
        service = _resolve_service(request)
        session = (await service.pause_session(
                       user_id=payload.user_id, session_id=payload.session_id,
                       expected_session_status=payload.session_status,
                       expected_state_version=payload.state_version,
                   ) if payload.operation == "agent.session.pause"
                   else await service.complete_session(
                       user_id=payload.user_id, session_id=payload.session_id,
                       expected_session_status=payload.session_status,
                       expected_state_version=payload.state_version,
                   ))
        return _success_response(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, session=session,
            output=(
                {"finalEvaluation": session.final_evaluation.model_dump(by_alias=True)}
                if getattr(session, "final_evaluation", None) is not None else None
            ),
            state_version=session.state_version, session_status=session.status,
        )
```

逐行解释：

1. 第 135 行：`@app.post` 注册 HTTP `POST /v1/agent/sessions/complete`，并要求响应通过 `AgentResponse` 校验。
2. 第 136 行：定义异步路由；`payload` 已由 `AgentSessionCompletionRequest` 校验，`request` 提供应用状态与异常上下文。
3. 第 137 行：调用项目函数 `_remember_request_context`，按协议别名保存已校验请求，供异常处理恢复关联字段。
4. 第 138 行：调用项目函数 `_resolve_service` 取得进程级 `InterviewAgentService`；冷启动时组装真实依赖。
5. 第 139 行：开始条件表达式；暂停分支等待 `service.pause_session`。
6. 第 140 行：传入 userId 与 sessionId，暂停服务会校验会话归属。
7. 第 141 行：传入上层观测到的 sessionStatus。
8. 第 142 行：传入上层观测到的 stateVersion。
9. 第 143 行：只有 operation 精确等于 `agent.session.pause` 才选择暂停分支。
10. 第 144 行：其余已通过请求模型校验的操作等待 `service.complete_session`。
11. 第 145 行：完成分支传入相同 userId 和 sessionId。
12. 第 146 行：传入预期会话状态。
13. 第 147 行：传入预期状态版本。
14. 第 148 行：结束条件表达式，任一分支返回的持久化会话保存为 `session`。
15. 第 149 行：调用项目函数 `_success_response` 构造统一协议响应。
16. 第 150 行：复制 apiVersion 与 requestId。
17. 第 151 行：复制 runId，并传入服务层返回的会话；用户和 sessionId 将从会话读取。
18. 第 152 行：开始构造可选 output。
19. 第 153 行：最终评价存在时按字段别名序列化并包装为 `finalEvaluation`。
20. 第 154 行：通过 `getattr(..., None)` 兼容旧会话缺少字段；暂停分支通常返回 `None`。
21. 第 155 行：结束条件输出表达式。
22. 第 156 行：显式返回数据库保存后的 stateVersion 和 sessionStatus。
23. 第 157 行：结束并返回 `AgentResponse`。

### 3.2 `InterviewAgentService.pause_session`

文件：`python-agent/app/agents/interview/service.py:258-277`

```python
    async def pause_session(
        self, *, user_id: str, session_id: str,
        expected_session_status: SessionStatus,
        expected_state_version: int,
    ) -> InterviewSession:
        session = await self._repository.get(session_id)
        if session is None or session.user_id != user_id:
            raise ConsistencyError("Agent session not found")
        if session.status in {SessionStatus.COMPLETED, SessionStatus.FAILED}:
            return session
        self._validate_expected_state(
            session,
            expected_session_status=expected_session_status,
            expected_state_version=expected_state_version,
        )
        expected_version = session.state_version
        session.status = SessionStatus.PAUSED
        session.interrupted = True
        saved = await self._repository.save(session, expected_version=expected_version)
        return saved
```

逐行解释：

1. 第 258 行：定义异步暂停函数。
2. 第 259 行：接收实例、用户 ID 和会话 ID；`*` 强制业务参数具名传入。
3. 第 260 行：接收上层预期会话状态。
4. 第 261 行：接收上层预期版本。
5. 第 262 行：声明返回 `InterviewSession`。
6. 第 263 行：调用 `PostgresInterviewSessionRepository.get(session_id)` 读取当前会话。
7. 第 264 行：把“不存在”和“用户不匹配”合并判断，避免泄露会话是否属于其他用户。
8. 第 265 行：抛统一 `ConsistencyError("Agent session not found")`。
9. 第 266 行：检查会话是否已经完成或失败。
10. 第 267 行：终态直接返回当前会话，暂停请求幂等且不能让状态倒退。
11. 第 268 行：调用项目函数 `_validate_expected_state`。
12. 第 269 行：传入数据库读取到的会话。
13. 第 270 行：传入请求预期状态。
14. 第 271 行：传入请求预期版本。
15. 第 272 行：结束校验；任一不一致都会抛错。
16. 第 273 行：保存当前版本作为 PostgreSQL 乐观锁条件。
17. 第 274 行：把内存会话状态改为 `PAUSED`。
18. 第 275 行：设置 `interrupted=True`，标识当前流程被暂停但数据保留。
19. 第 276 行：调用 `PostgresInterviewSessionRepository.save`，以旧版本更新唯一一行。
20. 第 277 行：返回版本已递增的保存会话。

### 3.3 `InterviewAgentService.complete_session`

文件：`python-agent/app/agents/interview/service.py:214-256`

```python
    async def complete_session(
        self, *, user_id: str, session_id: str,
        expected_session_status: SessionStatus,
        expected_state_version: int,
    ) -> InterviewSession:
        """关闭本次 Agent 会话，但不删除用户级长期记忆。"""
        session = await self._repository.get(session_id)
        if session is None:
            raise ConsistencyError("Agent 会话不存在")
        if session.user_id != user_id:
            raise ConsistencyError("用户与 Agent 会话不匹配")
        if session.status == SessionStatus.COMPLETED:
            return session
        if session.status == SessionStatus.FAILED:
            await self._memory_service.finalize_session(session=session, interrupted=True)
            return session
        self._validate_expected_state(
            session,
            expected_session_status=expected_session_status,
            expected_state_version=expected_state_version,
        )

        expected_version = session.state_version
        await self._report_progress(session_id, "SUMMARIZING")
        session.status = SessionStatus.COMPLETED
        session.final_summary = session.final_summary or self._fallback_summary(
            session, interrupted=False
        )
        if self._summary_agent is not None and session.turns:
            try:
                session.final_evaluation = await self._summary_agent.summarize(session)
                session.final_summary = session.final_evaluation.summary
            except Exception as error:
                # 总结不影响已完成会话的可恢复性，但必须保留可观测日志。
                logger.warning("面试会话总结生成失败: session_id=%s", session_id, exc_info=error)
        session.final_evaluation = session.final_evaluation or self._fallback_evaluation(session)
        session.final_summary = session.final_evaluation.summary
        session.updated_at = datetime.now(timezone.utc)
        session.rag_evidence_cache.clear()
        saved = await self._repository.save(session, expected_version=expected_version)
        await self._memory_service.finalize_session(session=saved, interrupted=False)
        await self._report_progress(session_id, "COMPLETED")
        return saved
```

逐行解释：

1. 第 214 行：定义异步完成会话函数。
2. 第 215 行：接收实例、用户 ID 和会话 ID，并强制具名传参。
3. 第 216 行：接收预期状态。
4. 第 217 行：接收预期版本。
5. 第 218 行：声明返回 `InterviewSession`。
6. 第 219 行：文档字符串说明只关闭本次会话，不删除用户长期记忆。
7. 第 220 行：调用会话仓储 `get(session_id)`。
8. 第 221 行：检查会话不存在。
9. 第 222 行：抛中文一致性错误。
10. 第 223 行：检查会话用户与请求用户不一致。
11. 第 224 行：抛用户不匹配错误。
12. 第 225 行：检查会话已经 `COMPLETED`。
13. 第 226 行：已完成时直接返回，重复完成请求不再调用模型或增加版本。
14. 第 227 行：检查会话已经 `FAILED`。
15. 第 228 行：失败终态调用 `MemoryService.finalize_session(session, interrupted=True)` 补偿中断归档。
16. 第 229 行：归档后返回失败会话，不把它改写成完成。
17. 第 230 行：非终态调用 `_validate_expected_state`。
18. 第 231 行：传入当前会话。
19. 第 232 行：传入上层预期状态。
20. 第 233 行：传入上层预期版本。
21. 第 234 行：结束状态校验。
22. 第 236 行：保存当前版本作为数据库乐观锁条件。
23. 第 237 行：调用 `_report_progress(sessionId, "SUMMARIZING")` 更新本机、Redis 与可选回调进度。
24. 第 238 行：先把业务状态置为 `COMPLETED`。
25. 第 239 行：已有 finalSummary 时保持不变，否则调用 `_fallback_summary`。
26. 第 240 行：传入 `interrupted=False`，生成正常完成文本。
27. 第 241 行：结束兜底赋值。
28. 第 242 行：只有总结 Agent 已配置且至少存在一轮回答时调用模型。
29. 第 243 行：进入局部 `try`；总结失败不能撤销会话完成。
30. 第 244 行：调用 `InterviewSummaryAgent.summarize(session)`。
31. 第 245 行：成功时把结构化评价摘要同步到 finalSummary。
32. 第 246 行：捕获总结过程任意普通异常。
33. 第 247 行：注释说明必须保留可观察日志但不破坏完成可恢复性。
34. 第 248 行：记录会话号和异常堆栈。
35. 第 249 行：若模型结果为空则调用 `_fallback_evaluation`；已有评价保持不变。
36. 第 250 行：以最终结构化评价的 summary 覆盖 finalSummary，统一两条分支。
37. 第 251 行：更新 UTC 时间。
38. 第 252 行：清空会话 RAG 证据缓存，完成后不再用于出题。
39. 第 253 行：调用会话仓储 `save(session, expected_version)` 乐观保存。
40. 第 254 行：保存成功后调用 `MemoryService.finalize_session(saved, interrupted=False)` 归档长期记忆。
41. 第 255 行：调用 `_report_progress(sessionId, "COMPLETED")` 写最终进度。
42. 第 256 行：返回版本已递增的保存会话。

### 3.4 `_remember_request_context`

文件：`python-agent/app/api/application.py:391-394`

逐行解释：

1. 第 391 行：定义无返回值请求上下文记录函数。
2. 第 392 行：通过 `getattr` 读取 payload 的 `model_dump`，兼容测试替身。
3. 第 393 行：确认该属性可调用。
4. 第 394 行：按字段别名和 JSON 模式导出请求，保存到 `request.state.agent_context`，供异常响应恢复协议字段。

### 3.5 `_resolve_service`

文件：`python-agent/app/api/application.py:315-320`

逐行解释：

1. 第 315 行：定义面试服务解析函数。
2. 第 316 行：从 FastAPI 应用状态读取已缓存服务。
3. 第 317 行：检查当前进程是否尚未构造服务。
4. 第 318 行：冷启动时调用项目函数 `build_interview_agent_service()`。
5. 第 319 行：把新服务写回应用状态，后续请求复用。
6. 第 320 行：返回服务。

### 3.6 `_validate_expected_state`

文件：`python-agent/app/agents/interview/service.py:427-440`

逐行解释：

1. 第 427 行：声明静态方法。
2. 第 428 行：定义跨服务状态校验函数。
3. 第 429 行：接收 PostgreSQL/Redis 恢复出的会话。
4. 第 430 行：强制预期值具名传入。
5. 第 431 行：接收上层预期状态。
6. 第 432 行：接收上层预期版本。
7. 第 433 行：函数正常时无返回业务值。
8. 第 434 行：开始不一致条件。
9. 第 435 行：比较真实状态与预期状态。
10. 第 436 行：以 `or` 比较真实版本与预期版本。
11. 第 437 行：结束条件，任一不等即失败。
12. 第 438 行：构造 `ConsistencyError`。
13. 第 439 行：错误要求上层先恢复最新会话状态。
14. 第 440 行：结束并抛出。

### 3.7 `_report_progress`

文件：`python-agent/app/agents/interview/service.py:126-133`

逐行解释：

1. 第 126 行：定义异步进度上报函数。
2. 第 127 行：先更新进程内 `_progress` 字典。
3. 第 128 行：检查是否配置 Python Redis。
4. 第 129 行：调用项目函数 `RedisCache.set_json`。
5. 第 130 行：写 `python:agent-progress:{sessionId}`，值为阶段，TTL 为 86400 秒。
6. 第 131 行：结束 Redis 写入。
7. 第 132 行：检查是否设置额外异步 reporter。
8. 第 133 行：存在时等待回调，传入同一会话号和阶段。

### 3.8 `_fallback_summary`

文件：`python-agent/app/agents/interview/service.py:902-907`

逐行解释：

1. 第 902 行：声明静态方法。
2. 第 903 行：定义确定性摘要函数。
3. 第 904 行：计算会话轮次数。
4. 第 905 行：检查中断标志。
5. 第 906 行：中断时返回记录已保存且可恢复的文本。
6. 第 907 行：正常完成时返回完成轮次数文本。

### 3.9 `_fallback_evaluation`

文件：`python-agent/app/agents/interview/service.py:909-931`

逐行解释：

1. 第 909 行：声明静态方法。
2. 第 910 行：定义结构化兜底评价函数。
3. 第 911 行：文档说明模型不可用时也必须产生可用报告。
4. 第 912 行：函数内导入 `InterviewSummary`，避免模块循环依赖。
5. 第 914 行：检查没有任何轮次。
6. 第 915 行：开始构造空作答报告。
7. 第 916 行：总分为 0，摘要说明无有效作答。
8. 第 917 行：优点为空，弱项和建议使用确定性文本。
9. 第 918 行：返回空报告。
10. 第 919 行：有轮次时计算并四舍五入平均分。
11. 第 920 行：按轮次顺序展平优点并截取前 5 项。
12. 第 921 行：展平弱项并截取前 5 项。
13. 第 922 行：检查优点是否为空。
14. 第 923 行：为空时加入完成主要问答的通用优点。
15. 第 924 行：检查弱项是否为空。
16. 第 925 行：为空时加入补充原理与落地细节的通用弱项。
17. 第 926 行：开始构造 `InterviewSummary`。
18. 第 927 行：写入平均总分。
19. 第 928 行：摘要写入轮次数和平均分。
20. 第 929 行：写入最终优点。
21. 第 930 行：写入最终弱项。
22. 第 931 行：写入固定改进建议并返回。

### 3.10 `InterviewSummaryAgent.summarize`

文件：`python-agent/app/agents/interview/agent.py:350-360`

逐行解释：

1. 第 350 行：定义异步总结函数，输入完整会话。
2. 第 351 行：开始构造总结输入。
3. 第 352 行：放入会话难度。
4. 第 353 行：序列化最终计划。
5. 第 354 行：逐项序列化全部轮次，避免只总结最后一轮。
6. 第 355 行：结束输入。
7. 第 356 行：调用 `StructuredOutputInvoker.invoke`。
8. 第 357 行：传入模型和 `InterviewSummary` schema。
9. 第 358 行：调用 `PromptLoader.render("interview/summary.md", {})` 生成业务 Prompt。
10. 第 359 行：传入总结输入。
11. 第 360 行：返回通过结构校验的总结。

### 3.11 `StructuredOutputInvoker.invoke` 及其项目辅助函数

文件：`python-agent/app/infrastructure/reliability/structured_output.py:30-120`

`invoke`（第 30-70 行）逐行解释：

1. 第 30-37 行：定义通用结构化调用函数，接收模型、schema、业务 Prompt 和映射输入，返回对应 Pydantic 实例。
2. 第 38 行：调用 `PromptLoader.render` 生成统一格式约束。
3. 第 39 行：加载 `shared/structured-output.md`。
4. 第 40 行：开始变量字典。
5. 第 41 行：把 schema JSON 以字段别名和中文安全格式序列化。
6. 第 42 行：加入固定 few-shot 输入。
7. 第 43 行：调用 `_few_shot_output(schema)` 并序列化当前结构示例。
8. 第 44-45 行：结束格式变量和 Prompt 渲染。
9. 第 46 行：创建消息列表。
10. 第 47 行：把业务 Prompt 与格式约束合并为 SystemMessage。
11. 第 48 行：把业务输入序列化为 HumanMessage，非 JSON 原生值用字符串表示。
12. 第 49 行：结束消息列表。
13. 第 50 行：有重试器时读取输出纠错次数，否则为 0。
14. 第 52 行：遍历初次调用和有限纠错次数。
15. 第 53 行：调用 `_invoke_model` 获取原始模型结果。
16. 第 54 行：进入本地结构校验保护。
17. 第 55 行：调用 `_validate`，成功立即返回。
18. 第 56 行：捕获 JSON、Pydantic、类型和值错误。
19. 第 57 行：调用 `_readable_validation_error` 生成安全原因。
20. 第 58 行：检查纠错次数是否耗尽。
21. 第 59 行：构造 `ModelOutputError`。
22. 第 60-61 行：错误包含失败次数、schema 名和最后原因。
23. 第 62 行：从原校验错误抛出。
24. 第 63 行：仍可修复时扩展消息。
25. 第 64 行：调用 `_content_as_text` 把上一轮错误输出加入 AIMessage。
26. 第 65 行：开始纠错用户消息。
27. 第 66-67 行：要求只返回完整 JSON，不输出解释或 Markdown，并附校验原因。
28. 第 68-69 行：结束消息扩展，进入下一轮。
29. 第 70 行：理论不可达保护。

`_invoke_model`（`python-agent/app/infrastructure/reliability/structured_output.py:72-75`）逐行解释：

1. 第 72 行：定义原始模型调用函数。
2. 第 73 行：检查是否没有重试器。
3. 第 74 行：无重试时直接等待模型 `ainvoke`。
4. 第 75 行：有重试时调用 `AsyncRetryExecutor.execute`，lambda 每次重新请求模型。

`_validate`（`python-agent/app/infrastructure/reliability/structured_output.py:77-84`）逐行解释：

1. 第 77 行：定义结构验证函数。
2. 第 78 行：若原始结果已经是目标 schema 实例则命中。
3. 第 79 行：直接返回。
4. 第 80 行：调用 `_content_as_text` 提取文本。
5. 第 81 行：调用 `_strip_json_fence` 后解析 JSON。
6. 第 82 行：检查根节点是否字典。
7. 第 83 行：其他根节点抛 `TypeError`。
8. 第 84 行：调用 schema `model_validate` 执行字段与业务校验。

`_content_as_text`（`python-agent/app/infrastructure/reliability/structured_output.py:87-104`）逐行解释：

1. 第 87 行：定义多响应形态文本提取函数。
2. 第 88 行：原结果是字符串时命中。
3. 第 89 行：直接返回。
4. 第 90 行：优先读取 `content` 属性。
5. 第 91 行：content 是字符串时命中。
6. 第 92 行：返回字符串。
7. 第 93 行：content 是列表时命中。
8. 第 94 行：创建片段列表。
9. 第 95 行：遍历内容块。
10. 第 96-97 行：字符串块直接加入。
11. 第 98-99 行：映射块的 `text` 为字符串时加入。
12. 第 100 行：检查是否收集到片段。
13. 第 101 行：连接并返回。
14. 第 102 行：整个 content 是映射时命中。
15. 第 103 行：序列化为 JSON 文本。
16. 第 104 行：其余形态抛 `TypeError`。

`_strip_json_fence`（`python-agent/app/infrastructure/reliability/structured_output.py:107-112`）逐行解释：

1. 第 107 行：定义围栏清理函数。
2. 第 108 行：去除首尾空白。
3. 第 109 行：同时以三反引号开头结尾时命中。
4. 第 110 行：按行拆分。
5. 第 111 行：去掉首尾围栏行并重新连接。
6. 第 112 行：返回结果。

`_readable_validation_error`（`python-agent/app/infrastructure/reliability/structured_output.py:115-120`）逐行解释：

1. 第 115 行：定义错误摘要函数。
2. 第 116 行：识别 Pydantic ValidationError。
3. 第 117 行：把每项错误位置连接为字段路径。
4. 第 118 行：最多返回前 8 项。
5. 第 119 行：其他错误转单行字符串。
6. 第 120 行：最多 500 字符，空消息时用异常类名。

`_few_shot_output` 文件：`python-agent/app/infrastructure/reliability/structured_output.py:123-154`

逐行解释：

1. 第 123 行：定义按 schema 返回合法最小示例的函数。
2. 第 124 行：文档说明示例对应实际结构。
3. 第 125-149 行：建立业务 schema 示例映射；本接口完成分支使用 `InterviewSummary` 项。
4. 第 150-153 行：为爬取判断结构提供专用示例，本接口不触发该分支。
5. 第 154 行：按 schema 类名返回示例，未知结构返回空字典。

### 3.12 `AsyncRetryExecutor.execute`

文件：`python-agent/app/infrastructure/reliability/retry.py:23-50`

逐行解释：

1. 第 23 行：定义异步重试函数。
2. 第 24 行：尝试编号从 1 到策略最大次数。
3. 第 25 行：进入单次保护。
4. 第 26-27 行：注释说明 wait_for 会取消超时协程。
5. 第 28 行：调用 `asyncio.wait_for`。
6. 第 29 行：创建操作协程并使用单次超时。
7. 第 30 行：成功立即返回。
8. 第 31 行：捕获普通异常。
9. 第 32 行：调用 `_is_retryable`；不可重试或最后一次时停止循环。
10. 第 33 行：区分可重试但次数耗尽。
11. 第 34 行：构造 `AgentDependencyError`。
12. 第 35 行：消息说明有限重试后依赖仍不可用。
13. 第 36 行：标记可重试。
14. 第 37 行：链接最后原异常。
15. 第 38 行：不可重试异常原样抛出。
16. 第 39 行：尚可重试时调用 `_backoff_seconds` 并异步等待。
17. 第 40 行：理论不可达保护。
18. 第 42 行：定义 `_is_retryable`。
19. 第 43 行：按异常类名是否在策略集合判断。
20. 第 45 行：定义退避计算函数。
21. 第 46-49 行：取策略最大退避与指数退避中的较小毫秒值。
22. 第 50 行：除以 1000 转为秒。

### 3.13 `PromptLoader.render`、`load` 与 `_resolve`

文件：`python-agent/app/common/prompt_loader.py:19-46`

`render` 逐行解释：

1. 第 26 行：定义受控变量渲染函数。
2. 第 27 行：调用项目函数 `load` 读取模板。
3. 第 29 行：定义闭包替换函数。
4. 第 30 行：读取占位符变量名。
5. 第 31 行：检查变量是否存在。
6. 第 32-34 行：缺失时抛包含 Prompt ID 和变量名的配置错误。
7. 第 35 行：存在时转字符串返回。
8. 第 37 行：执行全部占位符替换。
9. 第 38 行：再次检查是否残留占位符。
10. 第 39 行：残留时抛配置错误。
11. 第 40 行：返回渲染结果。

`load` 逐行解释：

1. 第 19 行：定义 Prompt 加载函数。
2. 第 20 行：调用 `_resolve` 获得安全路径。
3. 第 21 行：进入读取保护。
4. 第 22 行：以 UTF-8 读取文本。
5. 第 23 行：捕获文件不存在。
6. 第 24 行：转换为 Prompt 配置错误。

`_resolve` 逐行解释：

1. 第 42 行：定义路径解析函数。
2. 第 43 行：拼接根目录和 Prompt ID 后解析绝对路径。
3. 第 44 行：检查目标仍位于根目录下。
4. 第 45 行：越界时抛配置错误。
5. 第 46 行：返回安全路径。

### 3.14 `RedisCache.get_json`、`set_json` 与 `delete`

文件：`python-agent/app/infrastructure/cache/redis_cache.py:31-57`

`get_json` 逐行解释：

1. 第 31 行：定义异步 JSON 读取函数。
2. 第 32 行：检查客户端是否未配置。
3. 第 33 行：未配置时返回 `None`。
4. 第 34 行：进入失败降级保护。
5. 第 35 行：执行 Redis GET。
6. 第 36 行：有值时解析 JSON，否则返回 `None`。
7. 第 37 行：捕获 Redis、JSON 和类型错误。
8. 第 38 行：记录 warning，说明回退持久化存储。
9. 第 39 行：异常表现为缓存未命中。

`set_json` 逐行解释：

1. 第 41 行：定义异步 JSON 写入函数。
2. 第 42 行：检查客户端。
3. 第 43 行：未配置返回 `False`。
4. 第 44 行：进入写入保护。
5. 第 45 行：紧凑序列化并执行带 TTL 的 SET。
6. 第 46 行：成功返回 `True`。
7. 第 47 行：捕获 Redis、类型和值错误。
8. 第 48 行：记录 warning，强调持久化数据不变。
9. 第 49 行：失败返回 `False`。

`delete` 逐行解释：

1. 第 51 行：定义可删除多个键的异步函数。
2. 第 52 行：无客户端或无键时命中。
3. 第 53 行：直接返回。
4. 第 54 行：进入删除保护。
5. 第 55 行：执行 Redis DELETE。
6. 第 56 行：捕获 Redis 错误。
7. 第 57 行：记录 warning 并依靠 TTL 清理，不中断会话命令。

### 3.15 `PostgresInterviewSessionRepository.get`

文件：`python-agent/app/infrastructure/persistence/interview_session_repository.py:49-65`

逐行解释：

1. 第 49 行：定义按 sessionId 读取会话函数。
2. 第 50 行：配置缓存时调用 `RedisCache.get_json(_cache_key(sessionId))`。
3. 第 51 行：缓存值为字典时才尝试恢复。
4. 第 52 行：进入模型校验保护。
5. 第 53 行：调用 `InterviewSession.model_validate` 并直接返回。
6. 第 54 行：捕获旧缓存或损坏字段的 `ValueError`。
7. 第 55 行：调用 `RedisCache.delete` 删除损坏键。
8. 第 56 行：打开异步数据库会话。
9. 第 57 行：执行单标量查询。
10. 第 58 行：选择会话实体。
11. 第 59 行：WHERE 条件为 sessionId。
12. 第 60-61 行：结束查询。
13. 第 62 行：实体存在时调用 `_from_entity`，否则为 `None`。
14. 第 63 行：检查是否回源成功。
15. 第 64 行：调用 `_cache_session` 回填缓存。
16. 第 65 行：返回会话或 `None`。

`_cache_key` 文件：`python-agent/app/infrastructure/persistence/interview_session_repository.py:105-107`

1. 第 105 行：声明静态方法。
2. 第 106 行：定义缓存键函数。
3. 第 107 行：返回 `python:interview-session:{sessionId}`，隔离 Python 命名空间。

`_cache_session` 文件：`python-agent/app/infrastructure/persistence/interview_session_repository.py:109-113`

1. 第 109 行：定义异步会话缓存函数。
2. 第 110 行：检查缓存是否配置。
3. 第 111 行：调用 `RedisCache.set_json`。
4. 第 112 行：写入完整会话 JSON，TTL 7200 秒。
5. 第 113 行：结束写入。

`_from_entity` 文件：`python-agent/app/infrastructure/persistence/interview_session_repository.py:128-130`

1. 第 128 行：声明静态方法。
2. 第 129 行：定义实体转换函数。
3. 第 130 行：用 `InterviewSession.model_validate(entity.session_data)` 恢复领域会话。

### 3.16 `PostgresInterviewSessionRepository.save`

文件：`python-agent/app/infrastructure/persistence/interview_session_repository.py:67-103`

逐行解释：

1. 第 67-69 行：定义带 expectedVersion 的乐观保存函数。
2. 第 70 行：计算下一版本。
3. 第 71 行：复制会话。
4. 第 72 行：开始更新字典。
5. 第 73 行：写下一版本。
6. 第 74 行：写 UTC 更新时间。
7. 第 75-76 行：结束复制。
8. 第 77 行：序列化完整保存会话。
9. 第 78 行：开始构造 UPDATE。
10. 第 79 行：目标为会话实体表。
11. 第 80 行：开始 WHERE。
12. 第 81 行：sessionId 必须匹配。
13. 第 82 行：数据库版本必须等于 expectedVersion。
14. 第 83 行：结束 WHERE。
15. 第 84 行：开始更新值。
16. 第 85 行：更新状态列。
17. 第 86 行：更新阶段列。
18. 第 87 行：更新版本列。
19. 第 88 行：更新完整 JSON。
20. 第 89 行：更新时间。
21. 第 90-91 行：结束语句。
22. 第 92 行：打开数据库会话。
23. 第 93 行：执行 UPDATE。
24. 第 94 行：检查受影响行数不是 1。
25. 第 95 行：冲突时回滚。
26. 第 96 行：检查缓存。
27. 第 97 行：调用 `RedisCache.delete(_cache_key(sessionId))` 删除可能陈旧值。
28. 第 98 行：抛并发修改一致性错误。
29. 第 99 行：唯一行更新成功时提交。
30. 第 100-101 行：注释说明 PostgreSQL 提交先于缓存变更，缓存故障不能制造版本。
31. 第 102 行：调用 `_cache_session(saved_session)` 尽力刷新缓存。
32. 第 103 行：返回版本已递增会话。

### 3.17 `MemoryService.finalize_session`

文件：`python-agent/app/memory/service.py:146-175`

逐行解释：

1. 第 146 行：定义会话长期记忆归档函数。
2. 第 147 行：调用长期记忆仓储 `get(userId)`。
3. 第 148 行：检查记忆不存在。
4. 第 149 行：不存在时返回 `None`。
5. 第 150 行：检查 sessionId 已归档。
6. 第 151 行：已归档时幂等返回。
7. 第 152 行：保存当前长期记忆版本。
8. 第 153 行：提取全部轮次分数。
9. 第 154 行：有值时计算平均分，否则 0。
10. 第 155 行：展平全部弱项。
11. 第 156 行：展平全部优点。
12. 第 157 行：开始构造归档摘要。
13. 第 158 行：写 sessionId 和 interrupted/completed。
14. 第 159 行：写轮次数与平均分。
15. 第 160 行：写最终总结，缺失时用固定占位文本。
16. 第 161 行：结束摘要。
17. 第 162 行：调用 `_append_summary` 追加历史摘要。
18. 第 163 行：调用 `_merge_items` 合并会话总结，最多 20 项。
19. 第 164 行：合并全部问题，最多 100 项。
20. 第 165 行：合并弱项，最多 30 项。
21. 第 166 行：合并优点到 notes，最多 30 项。
22. 第 167 行：合并 finalized sessionId，最多 100 项。
23. 第 168 行：更新 UTC 时间。
24. 第 169 行：进入乐观保存保护。
25. 第 170 行：调用 `PostgresLongTermMemoryRepository.save`。
26. 第 171 行：捕获一致性冲突。
27. 第 172 行：重新读取最新记忆。
28. 第 173 行：若其他请求已归档同一 sessionId，则目标已完成。
29. 第 174 行：返回最新记忆。
30. 第 175 行：否则重新抛出冲突。

`_append_summary` 文件：`python-agent/app/memory/service.py:254-255`

1. 第 254 行：定义摘要追加函数。
2. 第 255 行：换行拼接旧摘要和事件、去空白，再只保留策略允许的末尾字符。

`_merge_items` 文件：`python-agent/app/memory/service.py:269-272`

1. 第 269 行：声明静态方法。
2. 第 270 行：定义有界列表合并函数。
3. 第 271 行：合并新旧项、过滤空值并去首尾空白。
4. 第 272 行：按首次出现去重后保留最后 limit 项。

### 3.18 `PostgresLongTermMemoryRepository.get` 与 `save`

文件：`python-agent/app/infrastructure/persistence/long_term_memory_repository.py:31-76`

`get` 逐行解释：

1. 第 31 行：定义按 userId 读取长期记忆函数。
2. 第 32 行：打开异步数据库会话。
3. 第 33 行：执行单标量查询。
4. 第 34 行：选择长期记忆实体。
5. 第 35 行：WHERE 条件为 userId。
6. 第 36-37 行：结束查询。
7. 第 38 行：实体存在时校验恢复 `LongTermMemory`，否则返回 `None`。

`save` 逐行解释：

1. 第 49-51 行：定义带 expectedVersion 的保存函数。
2. 第 52 行：复制记忆对象。
3. 第 53 行：开始更新字典。
4. 第 54 行：版本加一。
5. 第 55 行：更新时间取 UTC。
6. 第 56-57 行：结束复制。
7. 第 58 行：开始构造 UPDATE。
8. 第 59 行：目标为长期记忆表。
9. 第 60 行：开始 WHERE。
10. 第 61 行：userId 匹配。
11. 第 62 行：版本匹配。
12. 第 63 行：结束 WHERE。
13. 第 64 行：开始更新值。
14. 第 65 行：写新版本。
15. 第 66 行：写完整记忆 JSON。
16. 第 67 行：写更新时间。
17. 第 68-69 行：结束语句。
18. 第 70 行：打开数据库会话。
19. 第 71 行：执行 UPDATE。
20. 第 72 行：检查受影响行数。
21. 第 73 行：不是 1 时回滚。
22. 第 74 行：抛长期记忆并发修改错误。
23. 第 75 行：成功时提交。
24. 第 76 行：返回新版本记忆。

### 3.19 `_success_response`

文件：`python-agent/app/api/application.py:360-376`

逐行解释：

1. 第 360-366 行：定义统一成功响应构造函数，协议字段与会话必填，其余字段可选。
2. 第 367 行：开始构造 `AgentResponse`。
3. 第 368 行：复制 apiVersion、requestId 和 runId。
4. 第 369 行：code 固定 100、运行状态固定 COMPLETED，userId 从会话读取。
5. 第 370 行：sessionId 从会话读取；显式 sessionStatus 优先。
6. 第 371 行：显式 stateVersion 非 `None` 时优先，否则取会话版本。
7. 第 372 行：显式 answer 非 `None` 时优先，否则取当前问题；本接口未显式传答案。
8. 第 373 行：写入可选 turnStage；本接口为 `None`。
9. 第 374 行：显式 currentStage 优先，否则兼容读取会话当前阶段。
10. 第 375 行：写 output，并明确 error 为 `None`。
11. 第 376 行：返回响应。

`AgentResponse.validate_code_category` 文件：`python-agent/app/common/contracts.py:177-182`

逐行解释：

1. 第 177 行：注册 code 字段校验器。
2. 第 178 行：声明类方法。
3. 第 179 行：定义业务码校验函数。
4. 第 180 行：要求 code 首位类别属于 1~5。
5. 第 181 行：不满足时抛 `ValueError`。
6. 第 182 行：返回合法值。

### 3.20 异常处理入口

`request_validation_error` 文件：`python-agent/app/api/application.py:292-299`

逐行解释：

1. 第 292 行：为请求模型校验错误注册处理器，该异常可在路由函数执行前产生。
2. 第 293 行：定义异步处理函数。
3. 第 294 行：读取原始请求体。
4. 第 295 行：请求体为映射时作为上下文，否则为 `None`。
5. 第 296 行：调用 `_error_json_response`。
6. 第 297 行：把框架错误转换为 `RequestError`，HTTP 状态为 400。
7. 第 298 行：传入可恢复上下文。
8. 第 299 行：返回统一 JSON。

`application_error` 文件：`python-agent/app/api/application.py:301-304`

逐行解释：

1. 第 301 行：为项目 `ApplicationException` 注册处理器。
2. 第 302 行：定义异步函数。
3. 第 303 行：调用 `_mark_failed_interview_progress`；该函数只处理 respond 路径，所以本接口会直接返回。
4. 第 304 行：调用 `_error_json_response`，业务错误按现有跨服务协议使用 HTTP 200。

`unexpected_error` 文件：`python-agent/app/api/application.py:306-310`

逐行解释：

1. 第 306 行：为其他 Exception 注册兜底处理器。
2. 第 307 行：定义异步函数。
3. 第 308 行：记录未处理异常堆栈。
4. 第 309 行：调用 `_mark_failed_interview_progress`，本接口路径不会标 respond 进度。
5. 第 310 行：调用 `_error_json_response` 并使用 HTTP 500。

`_mark_failed_interview_progress` 文件：`python-agent/app/api/application.py:323-331`

逐行解释：

1. 第 323 行：定义失败进度补偿函数。
2. 第 324 行：检查路径是否不是 `/v1/agent/respond`。
3. 第 325 行：本接口为 sessions/complete，因此立即返回。
4. 第 326 行：只有 respond 路径才调用 `_request_context`。
5. 第 327 行：只有 respond 路径才清洗 sessionId。
6. 第 328 行：只有 respond 路径才读取应用服务。
7. 第 329 行：兼容性读取失败标记方法。
8. 第 330 行：检查 sessionId 与方法。
9. 第 331 行：满足时标记失败；本接口不执行该行。

### 3.21 统一错误响应辅助函数

`_request_context` 文件：`python-agent/app/api/application.py:379-388`

逐行解释：

1. 第 379 行：定义异步上下文恢复函数。
2. 第 380 行：读取已记住的 agent_context。
3. 第 381 行：检查是否映射。
4. 第 382 行：是映射时直接返回。
5. 第 383 行：进入原始 body 解析保护。
6. 第 384 行：异步读取 body。
7. 第 385 行：非空时解析 JSON，否则空字典。
8. 第 386 行：根节点为字典才返回。
9. 第 387 行：捕获 JSON、Unicode 和读取运行时错误。
10. 第 388 行：失败返回空字典。

`_error_response` 文件：`python-agent/app/api/application.py:397-411`

逐行解释：

1. 第 397-399 行：定义异常到 AgentResponse 的转换函数。
2. 第 400 行：显式上下文优先，否则调用 `_request_context`。
3. 第 401 行：调用 `_session_status_or_failed` 转换请求状态。
4. 第 402 行：读取 stateVersion。
5. 第 403 行：开始构造失败响应。
6. 第 404 行：调用 `_string_or_none` 转换 apiVersion。
7. 第 405 行：转换 requestId。
8. 第 406 行：转换 runId，并调用 `ExceptionHandler.to_code`。
9. 第 407 行：运行状态为 FAILED，并转换 userId。
10. 第 408 行：转换 sessionId，写会话状态。
11. 第 409 行：stateVersion 必须为非负整数，否则 0。
12. 第 410 行：answer 为空、currentStage 为 FAILED，并调用 `ExceptionHandler.to_error_info`。
13. 第 411 行：返回错误响应。

`_string_or_none` 文件：`python-agent/app/api/application.py:414-415`

1. 第 414 行：定义字符串清洗函数。
2. 第 415 行：值为非空字符串时原样返回，否则 `None`。

`_session_status_or_failed` 文件：`python-agent/app/api/application.py:418-423`

逐行解释：

1. 第 418 行：定义会话状态转换函数。
2. 第 419 行：文档说明运行失败不能错误覆盖既有会话状态。
3. 第 420 行：进入枚举转换保护。
4. 第 421 行：尝试构造 SessionStatus。
5. 第 422 行：捕获类型和值错误。
6. 第 423 行：无法恢复时回退 FAILED。

`ExceptionHandler.to_code` 文件：`python-agent/app/common/exceptions.py:139-146`

1. 第 139 行：声明类方法。
2. 第 140 行：定义 code 转换。
3. 第 141 行：识别项目异常。
4. 第 142 行：返回项目 code。
5. 第 143 行：遍历内置异常映射。
6. 第 144 行：按类型匹配。
7. 第 145 行：返回映射 code。
8. 第 146 行：未知异常返回 500。

`ExceptionHandler.to_error_info` 文件：`python-agent/app/common/exceptions.py:116-137`

逐行解释：

1. 第 116 行：声明类方法。
2. 第 117 行：定义错误详情转换。
3. 第 118 行：识别项目异常。
4. 第 119 行：开始构造 ErrorInfo。
5. 第 120 行：写 error_type。
6. 第 121 行：写项目消息。
7. 第 122 行：写 retryable。
8. 第 123 行：返回。
9. 第 125 行：遍历内置映射。
10. 第 126 行：按类型匹配。
11. 第 127-131 行：构造并返回映射错误名、消息和 retryable。
12. 第 133 行：开始未知错误对象。
13. 第 134 行：类型为 INTERNAL_ERROR。
14. 第 135 行：使用固定消息，避免泄漏内部信息。
15. 第 136 行：不可重试。
16. 第 137 行：返回。

`_error_json_response` 文件：`python-agent/app/api/application.py:447-455`

逐行解释：

1. 第 447-453 行：定义 JSON 包装函数，接收 HTTP 状态和可选上下文。
2. 第 454 行：调用 `_error_response`。
3. 第 455 行：调用 `AgentResponse.to_json_dict` 并构造 JSONResponse。

`AgentResponse.to_json_dict` 文件：`python-agent/app/common/contracts.py:184-185`

1. 第 184 行：定义协议对象 JSON 转换函数。
2. 第 185 行：使用 JSON 模式、字段别名并显式保留 null 导出字典。

### 3.22 `build_interview_agent_service`

文件：`python-agent/app/bootstrap.py:45-79`

逐行解释：

1. 第 45-47 行：定义接受可选 Settings 的服务工厂。
2. 第 48 行：文档说明不自动建表、不退化为临时文件。
3. 第 50 行：选择显式配置或调用 `get_settings()`。
4. 第 51 行：调用 `create_session_factory`。
5. 第 52 行：创建 PromptLoader。
6. 第 53 行：创建 SkillRegistry。
7. 第 54 行：调用 `InterviewWorkflow.load(prompt_loader)`。
8. 第 55 行：调用 `LLMFactory.create_chat_model(current)`。
9. 第 56 行：调用 `RetryPolicy.load` 并创建 AsyncRetryExecutor。
10. 第 57 行：配置 embedding 时调用 `build_rag_service` 并包装 RagSearchTool，否则为 None。
11. 第 59 行：开始构造 InterviewAgentService。
12. 第 60-62 行：创建并注入规划 Agent。
13. 第 63-65 行：创建并注入评价 Agent。
14. 第 66-68 行：创建并注入路由 Agent。
15. 第 69 行：创建并注入出题 Agent。
16. 第 70 行：注入可选 RAG 工具。
17. 第 71 行：创建会话仓储并调用 `build_cache` 注入 Redis。
18. 第 72 行：注入工作流。
19. 第 73 行：注入 PromptLoader。
20. 第 74 行：调用 `build_memory_service`。
21. 第 75 行：创建总结 Agent。
22. 第 76 行：调用 `IdempotencyPolicy.load`。
23. 第 77 行：创建网页证据工具。
24. 第 78 行：调用 `build_cache` 为进度/证据层创建 RedisCache。
25. 第 79 行：返回完整服务。

### 3.23 `get_settings`、`create_engine` 与 `create_session_factory`

`get_settings` 文件：`python-agent/app/common/config.py:47-51`

1. 第 47 行：以 LRU 最大 1 缓存配置。
2. 第 48 行：定义配置读取函数。
3. 第 49 行：说明返回进程级快照。
4. 第 51 行：实例化 Settings，从环境和 `.env` 读取并校验。

`create_engine` 文件：`python-agent/app/infrastructure/persistence/database.py:9-13`

1. 第 9 行：定义异步数据库引擎工厂。
2. 第 10 行：选择显式配置或 get_settings。
3. 第 11 行：检查 DATABASE_URL。
4. 第 12 行：未配置时抛 PersistenceConfigurationError。
5. 第 13 行：创建异步引擎并启用 pool_pre_ping。

`create_session_factory` 文件：`python-agent/app/infrastructure/persistence/database.py:16-19`

1. 第 16-18 行：定义异步会话工厂函数。
2. 第 19 行：调用 create_engine，再创建 expire_on_commit=False 的 async_sessionmaker。

### 3.24 `InterviewWorkflow.load`

文件：`python-agent/app/agents/interview/workflow.py:19-36`

逐行解释：

1. 第 19 行：声明类方法。
2. 第 20-24 行：定义加载函数，接收 PromptLoader 与可选路径。
3. 第 25 行：选择显式路径或默认工作流 JSON。
4. 第 26 行：进入读取保护。
5. 第 27 行：读取并解析 JSON。
6. 第 28 行：把阶段值转换为 InterviewStage 元组。
7. 第 29 行：构造工作流并保存 openingPrompt ID。
8. 第 30 行：捕获文件、字段、值与 JSON 错误。
9. 第 31 行：统一抛 WorkflowConfigurationError。
10. 第 33 行：比较阶段与完整枚举顺序。
11. 第 34 行：不完整或乱序时抛错。
12. 第 35 行：调用 PromptLoader.load 提前验证开场 Prompt。
13. 第 36 行：返回工作流。

### 3.25 `LLMFactory.create_chat_model`

文件：`python-agent/app/agents/llm/factory.py:12-39`

逐行解释：

1. 第 12 行：声明静态方法。
2. 第 13 行：定义模型工厂。
3. 第 14 行：选择显式配置或 get_settings。
4. 第 16 行：定义支持 provider 集合。
5. 第 17 行：检查配置 provider。
6. 第 18-20 行：不支持时抛包含名称的 ModelConfigurationError。
7. 第 21 行：检查 modelName。
8. 第 22 行：缺失时抛错。
9. 第 23 行：检查 API Key。
10. 第 24 行：缺失时抛错。
11. 第 26 行：开始模型参数。
12. 第 27 行：写模型名。
13. 第 28 行：写 API Key。
14. 第 29 行：写温度。
15. 第 30 行：写超时。
16. 第 31 行：注释说明工程层统一重试。
17. 第 32 行：SDK maxRetries 设 0。
18. 第 33 行：结束基础参数。
19. 第 34 行：检查自定义 baseUrl。
20. 第 35 行：存在时加入。
21. 第 36 行：检查 maxTokens。
22. 第 37 行：存在时加入。
23. 第 39 行：构造并返回 ChatOpenAI，不发请求。

### 3.26 `RetryPolicy.load` 与 `IdempotencyPolicy.load`

`RetryPolicy.load` 文件：`python-agent/app/infrastructure/reliability/policy.py:20-45`

逐行解释：

1. 第 20 行：声明类方法。
2. 第 21 行：定义策略加载函数。
3. 第 22 行：选择显式路径或 reliability.json。
4. 第 23 行：进入读取保护。
5. 第 24 行：解析 JSON。
6. 第 25 行：开始构造策略。
7. 第 26 行：读取最大尝试次数。
8. 第 27 行：读取初始退避。
9. 第 28 行：读取最大退避。
10. 第 29 行：构造可重试异常冻结集合。
11. 第 30 行：读取单次超时，默认 120。
12. 第 31 行：读取输出纠错次数，默认 2。
13. 第 32 行：结束构造。
14. 第 33 行：捕获读取、字段、类型与 JSON 错误。
15. 第 34 行：统一抛 ReliabilityConfigurationError。
16. 第 35 行：验证尝试次数 1~5 和初始退避非负。
17. 第 36 行：失败抛错。
18. 第 37 行：验证最大退避不小于初始值。
19. 第 38 行：失败抛错。
20. 第 39 行：验证单次超时 0~120 秒。
21. 第 40 行：失败抛错。
22. 第 41 行：验证纠错次数 0~2。
23. 第 42 行：失败抛错。
24. 第 43 行：验证异常集合非空。
25. 第 44 行：为空抛错。
26. 第 45 行：返回策略。

`IdempotencyPolicy.load` 文件：`python-agent/app/infrastructure/idempotency/policy.py:15-28`

逐行解释：

1. 第 15 行：声明类方法。
2. 第 16 行：定义幂等策略加载函数。
3. 第 17 行：选择路径或 idempotency.json。
4. 第 18 行：进入读取保护。
5. 第 19 行：开始构造策略。
6. 第 20-22 行：读取 maxRunSnapshots 并转整数。
7. 第 23 行：结束构造。
8. 第 24 行：捕获文件、字段、值、类型和 JSON 错误。
9. 第 25 行：统一抛配置错误。
10. 第 26 行：要求窗口至少 1。
11. 第 27 行：不满足抛错。
12. 第 28 行：返回策略。

### 3.27 `build_cache`、`build_memory_service`、`MemoryPolicy.load` 与 `build_rag_service`

`build_cache` 文件：`python-agent/app/bootstrap.py:41-43`

1. 第 41 行：定义缓存工厂。
2. 第 42 行：选择配置。
3. 第 43 行：以 Python 专属 redisUrl 返回 RedisCache。

`build_memory_service` 文件：`python-agent/app/bootstrap.py:33-38`

1. 第 33 行：定义记忆服务工厂。
2. 第 34 行：选择配置。
3. 第 35 行：调用 create_session_factory。
4. 第 36 行：开始构造 MemoryService。
5. 第 37 行：注入 PostgreSQL 仓储并调用 MemoryPolicy.load。
6. 第 38 行：返回服务。

`MemoryPolicy.load` 文件：`python-agent/app/memory/policy.py:18-40`

逐行解释：

1. 第 18 行：声明类方法。
2. 第 19 行：定义加载函数。
3. 第 20 行：选择路径或 memory-policy.json。
4. 第 21 行：进入读取保护。
5. 第 22 行：解析 JSON。
6. 第 23 行：开始构造。
7. 第 24 行：读取短期轮次上限。
8. 第 25 行：读取摘要字符上限。
9. 第 26 行：读取简历快照上限。
10. 第 27 行：读取评价 run 保留数。
11. 第 28 行：结束构造。
12. 第 29 行：捕获文件、字段、值与 JSON 错误。
13. 第 30 行：统一抛 WorkflowConfigurationError。
14. 第 32 行：短期轮次必须是 3、4、5。
15. 第 33 行：不满足抛错。
16. 第 34 行：摘要容量至少 200。
17. 第 35 行：不满足抛错。
18. 第 36 行：简历快照至少 1。
19. 第 37 行：不满足抛错。
20. 第 38 行：评价 run 保留数为正。
21. 第 39 行：不满足抛错。
22. 第 40 行：返回策略。

`build_rag_service` 文件：`python-agent/app/bootstrap.py:82-91`

1. 第 82 行：定义 RAG 服务工厂。
2. 第 83 行：选择配置。
3. 第 84 行：创建数据库会话工厂。
4. 第 85 行：创建 embedding 重试器。
5. 第 86 行：开始构造 RagService。
6. 第 87 行：注入 PostgreSQL/pgvector 仓储。
7. 第 88 行：注入 OpenAIEmbeddingProvider。
8. 第 89 行：调用 RagPolicy.load。
9. 第 90 行：调用 build_cache。
10. 第 91 行：返回服务。

`RagPolicy.load` 文件：`python-agent/app/rag/policy.py:25-64`

逐行解释：

1. 第 25 行：声明类方法。
2. 第 26 行：定义加载函数。
3. 第 27 行：选择路径或 rag-policy.json。
4. 第 28 行：进入读取保护。
5. 第 29 行：解析 JSON。
6. 第 30 行：开始构造策略。
7. 第 31 行：读取切片 token 大小。
8. 第 32 行：读取切片重叠 token 数。
9. 第 33 行：读取 embedding 批大小。
10. 第 34 行：读取默认 topK。
11. 第 35 行：读取默认最低分。
12. 第 36 行：读取本地过滤回退候选倍数。
13. 第 37 行：开始构造允许用途冻结集合。
14. 第 38 行：把每个值转换为 RagUseCase。
15. 第 39 行：结束冻结集合。
16. 第 40 行：读取缓存 TTL。
17. 第 41 行：读取缓存条目上限。
18. 第 42 行：结束构造。
19. 第 43-49 行：捕获配置读取与类型错误。
20. 第 50 行：统一抛 RagConfigurationError。
21. 第 52 行：检查切片与重叠非负边界。
22. 第 53 行：失败抛错。
23. 第 54 行：重叠必须小于切片。
24. 第 55 行：失败抛错。
25. 第 56 行：批大小至少 1。
26. 第 57 行：失败抛错。
27. 第 58 行：topK 至少 1 且最低分在 0~1。
28. 第 59 行：失败抛错。
29. 第 60 行：允许用途非空。
30. 第 61 行：为空抛错。
31. 第 62 行：TTL 非负且条目上限至少 1。
32. 第 63 行：失败抛错。
33. 第 64 行：返回策略。

## 4. 主流构建分析

主流会话终止接口通常把“暂停”和“完成”拆成两个显式命令端点，或以统一 command endpoint 搭配命令对象、幂等键和状态机校验；完成命令较耗时时常交给工作流引擎异步执行，并通过任务状态或事件流返回总结进度。优点是命令语义更清楚、权限和重试策略可分别配置、长耗时总结可恢复；缺点是 API 数量或异步基础设施增加，前端需要处理 accepted/pending 状态。

本项目当前以 `operation` 在同一路由内分支，并已经具备 stateVersion 乐观锁、终态幂等、Redis 进度和模型总结兜底，规模较小时继续使用较合适。若并发和总结耗时上升，可把暂停拆为 `POST /sessions/{id}/pause` 的同步命令，把完成拆为 `POST /sessions/{id}/completion-runs`：先在 PostgreSQL 记录 runId 与 PENDING，再由 Python Worker 执行总结、归档和状态提交；现有 `_fallback_evaluation`、`finalize_session` 和 progress Redis 可直接复用，前端按 runId 查询或订阅 SSE。代价是要新增命令表、Worker 幂等和失败恢复策略。
