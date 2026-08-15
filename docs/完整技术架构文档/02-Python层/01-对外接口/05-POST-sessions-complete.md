# POST /v1/agent/sessions/complete：暂停或完成面试会话

## 1. 接口定义

该接口依据请求中的 `operation` 在“暂停”和“完成”之间分支。暂停只把会话置为 `PAUSED` 并保留恢复能力；完成会生成或兜底最终评价、保存会话、归档长期记忆并返回最终评价。

| 项目 | 内容 |
|---|---|
| HTTP 方法 | POST |
| 路径 | `/v1/agent/sessions/complete` |
| 路由函数 | `complete_session` |
| 请求模型 | `AgentSessionCompletionRequest` |
| 文件 | `python-agent/app/api/application.py:132-154` |

## 2. 函数调用链

```text
complete_session
 -> _remember_request_context
 -> _resolve_service
 -> operation == "agent.session.pause"
    -> InterviewAgentService.pause_session
       -> repository.get -> _validate_expected_state -> repository.save
 -> 否则 InterviewAgentService.complete_session
       -> repository.get -> _validate_expected_state -> _report_progress
       -> _fallback_summary
       -> InterviewSummaryAgent.summarize（有 turns 且 Agent 可用）
       -> _fallback_evaluation（模型总结失败或为空）
       -> repository.save -> MemoryService.finalize_session -> _report_progress
 -> _success_response
```

## 3. 函数解析

### 3.1 `complete_session` 路由函数

文件：`python-agent/app/api/application.py:132-154`

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

1. 第 132 行：注册统一的会话结束端点。
2. 第 133 行：接收完成请求和请求上下文。
3. 第 134 行：缓存错误响应所需上下文。
4. 第 135 行：解析面试服务。
5. 第 136-141 行：当操作值精确为 `agent.session.pause` 时调用暂停函数，并传入身份、会话和预期状态版本。
6. 第 142-146 行：其他合法操作进入真正完成函数，参数保持一致。
7. 第 147 行：开始构造成功响应。
8. 第 148-149 行：复制 API、请求、运行和会话字段。
9. 第 150-153 行：只有 `final_evaluation` 存在时才调用 Pydantic `model_dump` 输出别名字段；暂停通常返回 `None`。
10. 第 154 行：显式返回持久化后的状态版本和会话状态。

### 3.2 `InterviewAgentService.pause_session`

文件：`python-agent/app/agents/interview/service.py:235-254`

```python
    async def pause_session(self, *, user_id: str, session_id: str,
        expected_session_status: SessionStatus,
        expected_state_version: int) -> InterviewSession:
        session = await self._repository.get(session_id)
        if session is None or session.user_id != user_id:
            raise ConsistencyError("Agent session not found")
        if session.status in {SessionStatus.COMPLETED, SessionStatus.FAILED}:
            return session
        self._validate_expected_state(session,
            expected_session_status=expected_session_status,
            expected_state_version=expected_state_version)
        expected_version = session.state_version
        session.status = SessionStatus.PAUSED
        session.interrupted = True
        saved = await self._repository.save(session, expected_version=expected_version)
        return saved
```

逐行解释：

1. 函数签名要求用户、会话和上层观测到的状态版本。
2. 读取会话；不存在或不属于用户时返回同一个一致性错误，避免泄露会话归属。
3. 已完成或失败属于终态，直接幂等返回，禁止倒退为暂停。
4. `_validate_expected_state` 同时核对状态枚举和版本号。
5. 保存当前版本，作为 Repository 乐观更新条件。
6. 把状态改为 `PAUSED`，并设置 `interrupted=True` 供后续恢复和总结识别。
7. 带期望版本保存，返回持久化后的对象。

### 3.3 `InterviewAgentService.complete_session`

文件：`python-agent/app/agents/interview/service.py:191-233`

```python
    async def complete_session(self, *, user_id: str, session_id: str,
        expected_session_status: SessionStatus,
        expected_state_version: int) -> InterviewSession:
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
        self._validate_expected_state(session,
            expected_session_status=expected_session_status,
            expected_state_version=expected_state_version)
        expected_version = session.state_version
        await self._report_progress(session_id, "SUMMARIZING")
        session.status = SessionStatus.COMPLETED
        session.final_summary = session.final_summary or self._fallback_summary(session, interrupted=False)
        if self._summary_agent is not None and session.turns:
            try:
                session.final_evaluation = await self._summary_agent.summarize(session)
                session.final_summary = session.final_evaluation.summary
            except Exception as error:
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

1. 读取会话并分别验证存在性及用户归属。
2. `COMPLETED` 直接幂等返回；`FAILED` 先按中断归档记忆再返回。
3. 非终态必须通过上层状态和版本检查，再保存当前版本用于乐观锁。
4. 把进度改为 `SUMMARIZING`，会话状态改为 `COMPLETED`，并先写入程序兜底总结。
5. 总结 Agent 存在且有轮次时调用模型；成功覆盖兜底总结，失败只写警告日志。
6. 再次用 `_fallback_evaluation` 保证最终评价绝不为空，并以其 summary 作为最终摘要。
7. 更新时间、清空一次性 RAG 证据缓存，再带版本保存会话。
8. 会话保存成功后归档长期记忆，进度切换为 `COMPLETED`，返回保存结果。

## 4. 审核结论

同一路由实际包含暂停与完成两条互斥链路；文档已分别展开，未把暂停误写成模型总结操作。
