# POST /v1/agent/respond：提交回答并推进面试

## 1. 接口定义

该接口接收候选人当前答案以及上层持有的会话状态版本，在 150 秒总超时内完成答案评价、路由决策、证据检索、下一题生成、会话持久化和记忆同步。响应只暴露候选人可见字段，内部推理数据不会原样返回。

| 项目 | 内容 |
|---|---|
| HTTP 方法 | POST |
| 路径 | `/v1/agent/respond` |
| 请求模型 | `AgentRespondRequest` |
| 路由函数 | `respond` |
| 文件 | `python-agent/app/api/application.py:93-130` |
| 总超时 | `INTERVIEW_TURN_TIMEOUT_SECONDS = 150.0` |

## 2. 函数调用链

```text
respond
 -> _remember_request_context
 -> _resolve_service
 -> asyncio.wait_for
 -> InterviewAgentService.submit_answer_for_run
 -> InterviewAgentService._submit_answer
    -> repository.get
    -> _validate_expected_state
    -> MemoryService.build_context
    -> _run_interview_node -> InterviewEvaluationAgent.evaluate
    -> （开场分支）_run_interview_node -> _replan_after_opening
    -> _allowed_actions -> _next_stage
    -> _run_interview_node -> InterviewRoutingAgent.route
    -> _enforce_route_limits
    -> _record_turn -> _compact_session_history -> _apply_route
    -> MemoryService.build_context
    -> （结束分支）InterviewSummaryAgent.summarize / _fallback_evaluation
    -> （继续分支）_question_evidence -> InterviewQuestionAgent.generate -> _register_question
    -> _candidate_visible_output
    -> repository.save -> MemoryService.record_turn
    -> （终态分支）MemoryService.finalize_session
    -> _report_progress
 -> _candidate_response_output
 -> _success_response
```

异常分支：

```text
150 秒超时 -> mark_progress_failed -> AgentDependencyError
任意 BaseException -> mark_progress_failed -> 原异常继续抛出
ApplicationException/Exception -> application_error/unexpected_error
 -> _mark_failed_interview_progress -> _error_json_response
```

## 3. 函数解析

### 3.1 `respond`

文件：`python-agent/app/api/application.py:93-130`

```python
    @app.post("/v1/agent/respond", response_model=AgentResponse)
    async def respond(payload: AgentRespondRequest, request: Request) -> AgentResponse:
        _remember_request_context(request, payload)
        service = _resolve_service(request)
        try:
            result = await asyncio.wait_for(
                service.submit_answer_for_run(
                    user_id=payload.user_id,
                    session_id=payload.session_id,
                    candidate_answer=payload.answer,
                    run_id=payload.run_id,
                    expected_session_status=payload.session_status,
                    expected_state_version=payload.state_version,
                ),
                timeout=INTERVIEW_TURN_TIMEOUT_SECONDS,
            )
        except TimeoutError as error:
            marker = getattr(service, "mark_progress_failed", None)
            if callable(marker):
                marker(payload.session_id)
            raise AgentDependencyError(
                "本轮面试处理超过 150 秒，请保留当前回答后重试",
                retryable=False,
            ) from error
        except BaseException:
            marker = getattr(service, "mark_progress_failed", None)
            if callable(marker):
                marker(payload.session_id)
            raise
        return _success_response(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, session=result.session,
            answer=result.snapshot.answer, output=_candidate_response_output(result.snapshot.output),
            state_version=result.snapshot.state_version,
            session_status=result.snapshot.session_status,
            turn_stage=result.snapshot.turn_stage,
            current_stage=getattr(result.snapshot, "current_stage", None),
        )
```

逐行解释：

1. 第 93 行：注册回答接口并规定统一响应模型。
2. 第 94 行：接收经过 Pydantic 校验的回答请求和 FastAPI 请求上下文。
3. 第 95 行：保存请求上下文，保证错误响应仍能携带协议标识。
4. 第 96 行：解析并缓存面试服务实例。
5. 第 97 行：进入总超时保护的 `try` 区域。
6. 第 98 行：用 `asyncio.wait_for` 等待完整一轮处理。
7. 第 99 行：调用 `submit_answer_for_run`，该函数继续委托 `_submit_answer`。
8. 第 100-105 行：分别传入用户、会话、答案、runId、预期会话状态和预期版本；后两项用于乐观一致性检查。
9. 第 106 行：结束服务协程参数。
10. 第 107 行：把整轮最长等待时间限定为 150 秒。
11. 第 108 行：结束 `wait_for` 并把结果保存为 `result`。
12. 第 109 行：捕获总超时。
13. 第 110 行：动态读取失败进度标记函数，以兼容测试替身。
14. 第 111-112 行：函数存在时把当前会话进度改为 `FAILED`。
15. 第 113-116 行：抛出不可重试的依赖错误并保留原超时为异常原因，避免上层重复提交仍在执行的请求。
16. 第 117 行：捕获包括取消异常在内的其他基础异常。
17. 第 118-120 行：同样尝试标记进度失败。
18. 第 121 行：原样重新抛出，交给异常处理链。
19. 第 122 行：正常路径开始构造统一成功响应。
20. 第 123-124 行：复制协议字段、会话和新问题答案。
21. 第 124 行：调用 `_candidate_response_output` 再次过滤内部输出。
22. 第 125-128 行：返回新状态版本、会话状态、本轮阶段和当前面试阶段。
23. 第 129 行：结束并返回响应。

### 3.2 `_candidate_response_output`

文件：`python-agent/app/api/application.py:423-432`

```python
def _candidate_response_output(output: dict[str, object] | None) -> dict[str, object] | None:
    """Whitelist candidate-facing fields at the lower-layer boundary."""
    if not output:
        return None
    allowed = {
        "evaluationSummary", "evaluationScore", "strengths", "weaknesses", "currentPrimaryQuestionCount", "totalPrimaryQuestionCount",
        "currentFollowupCount", "totalQuestionCount", "questionBudget", "finalEvaluation",
    }
    visible = {key: value for key, value in output.items() if key in allowed}
    return visible or None
```

逐行解释：

1. 第 423 行：定义候选人输出过滤器，输入可为空。
2. 第 424 行：文档字符串明确这是 Python 服务边界的白名单。
3. 第 425-426 行：空字典或 `None` 直接转成 `None`。
4. 第 427-430 行：声明唯一允许离开 Python 服务的评分、优缺点、题量和最终评价字段。
5. 第 431 行：遍历原输出，只保留白名单键；模型内部字段被丢弃。
6. 第 432 行：过滤后仍为空则返回 `None`，否则返回新字典。

### 3.3 `InterviewAgentService.submit_answer_for_run`

文件：`python-agent/app/agents/interview/service.py:256-273`

```python
    async def submit_answer_for_run(
        self,
        *,
        user_id: str,
        session_id: str,
        candidate_answer: str,
        run_id: str,
        expected_session_status: SessionStatus,
        expected_state_version: int,
    ) -> AgentSubmissionResult:
        return await self._submit_answer(
            user_id=user_id,
            session_id=session_id,
            candidate_answer=candidate_answer,
            run_id=run_id,
            expected_session_status=expected_session_status,
            expected_state_version=expected_state_version,
        )
```

逐行解释：

1. 第 256-265 行：声明公开提交入口，要求 runId、预期状态和版本，不允许调用方绕过幂等及并发控制。
2. 第 266 行：直接等待内部 `_submit_answer`。
3. 第 267-272 行：逐项原样转发业务参数，保持公开方法只承担协议约束。
4. 第 273 行：返回内部方法生成的会话和运行快照。

### 3.4 `InterviewAgentService._submit_answer`

文件：`python-agent/app/agents/interview/service.py:275-397`

```python
    async def _submit_answer(self, *, user_id: str, session_id: str,
        candidate_answer: str, run_id: str | None,
        expected_session_status: SessionStatus | None = None,
        expected_state_version: int | None = None) -> AgentSubmissionResult:
        session = await self._repository.get(session_id)
        if session is None:
            raise ConsistencyError("Agent 会话不存在")
        if session.user_id != user_id:
            raise ConsistencyError("用户与 Agent 会话不匹配")
        if run_id and run_id in session.run_snapshots:
            persisted_snapshot = session.run_snapshots[run_id]
            if persisted_snapshot.submitted_answer != candidate_answer:
                raise ConsistencyError("同一 runId 不能提交不同的回答")
            await self._synchronize_turn_memory(session, run_id)
            return AgentSubmissionResult(session=session, snapshot=persisted_snapshot)
        if expected_session_status is not None and expected_state_version is not None:
            self._validate_expected_state(session,
                expected_session_status=expected_session_status,
                expected_state_version=expected_state_version)
        if session.status not in {SessionStatus.ACTIVE, SessionStatus.PAUSED}:
            raise ConsistencyError("当前 Agent 会话不可继续回答")
        if session.status == SessionStatus.PAUSED:
            session.status = SessionStatus.ACTIVE
            session.interrupted = False
        expected_version = session.state_version
        memory_context = await self._memory_service.build_context(session)
        evaluation = await self._run_interview_node(session_id, "EVALUATING",
            lambda: self._evaluation_agent.evaluate(session, candidate_answer, memory_context))
        if session.current_stage == InterviewStage.OPENING:
            await self._run_interview_node(session_id, "PLANNING",
                lambda: self._replan_after_opening(session, candidate_answer))
        allowed_actions = self._allowed_actions(session, evaluation)
        next_stage = self._next_stage(session)
        route = await self._run_interview_node(session_id, "ROUTING",
            lambda: self._routing_agent.route(session, evaluation,
                {item.value for item in allowed_actions},
                next_stage.value if next_stage else None, memory_context))
        route = self._enforce_route_limits(session, route, allowed_actions, next_stage, evaluation)
        turn = self._record_turn(session, candidate_answer, evaluation, route, run_id)
        self._compact_session_history(session)
        self._apply_route(session, route)
        next_question_memory_context = await self._memory_service.build_context(session)
        if session.status == SessionStatus.COMPLETED:
            await self._report_progress(session_id, "SUMMARIZING")
            session.final_summary = self._fallback_summary(session, interrupted=False)
            if self._summary_agent is not None and session.turns:
                try:
                    session.final_evaluation = await self._run_interview_node(
                        session_id, "SUMMARIZING", lambda: self._summary_agent.summarize(session))
                    session.final_summary = session.final_evaluation.summary
                except Exception as error:
                    logger.warning("面试会话总结生成失败: session_id=%s", session_id, exc_info=error)
            session.final_evaluation = session.final_evaluation or self._fallback_evaluation(session)
            session.final_summary = session.final_evaluation.summary
            session.current_question = session.final_summary
        else:
            if route.next_topic is None or not route.next_topic.strip():
                raise AgentDependencyError("模型在需要出题的路由中未返回 nextTopic", retryable=False)
            evidence = await self._question_evidence(session, route)
            session.current_question = await self._run_interview_node(
                session_id, "GENERATING_QUESTION",
                lambda: self._question_agent.generate(session, route, evidence, next_question_memory_context))
            session.current_topic = route.next_topic
            self._register_question(session, session.current_question, session.current_stage,
                route.next_topic, is_followup=route.action == InterviewAction.FOLLOW_UP)
            session.current_question_evidence = evidence
        session.updated_at = datetime.now(timezone.utc)
        snapshot = AgentRunSnapshot(submitted_answer=candidate_answer,
            answer=session.current_question, session_status=session.status,
            state_version=expected_version + 1, turn_stage=turn.stage,
            current_stage=session.current_stage,
            output=self._candidate_visible_output(session, turn))
        if run_id:
            session.run_snapshots[run_id] = snapshot
            while len(session.run_snapshots) > self._idempotency_policy.max_run_snapshots:
                session.run_snapshots.pop(next(iter(session.run_snapshots)))
        saved = await self._repository.save(session, expected_version=expected_version)
        await self._memory_service.record_turn(session=saved, turn=turn)
        if saved.status in {SessionStatus.COMPLETED, SessionStatus.FAILED}:
            await self._memory_service.finalize_session(
                session=saved, interrupted=saved.status == SessionStatus.FAILED)
        await self._report_progress(session_id,
            "COMPLETED" if saved.status == SessionStatus.COMPLETED else "IDLE")
        return AgentSubmissionResult(session=saved, snapshot=snapshot)
```

逐句解释：

1. 函数签名接收一次回答所需全部身份、幂等和并发参数，并返回会话及快照组合。
2. `repository.get` 读取会话；不存在或 userId 不匹配分别抛出一致性错误。
3. `run_snapshots` 命中时校验答案完全一致；一致则同步可能遗漏的长期记忆并返回旧快照，不再调用模型。
4. 请求携带预期状态和版本时调用 `_validate_expected_state`，阻止上层用陈旧状态覆盖新会话。
5. 只有 `ACTIVE`、`PAUSED` 可以回答；暂停会话先恢复活动并清除中断标记。
6. 保存当前版本作为乐观锁基准，再读取短期和长期记忆上下文。
7. `EVALUATING` 节点调用评价 Agent；开场回答之后额外触发重新规划。
8. `_allowed_actions` 计算程序允许的动作，`_next_stage` 计算阶段后继，路由 Agent 只能从该集合中选择。
9. `_enforce_route_limits` 再用硬编码题量、追问和阶段规则修正模型路由。
10. `_record_turn` 记录本轮，随后压缩历史、应用路由，并为下一题重新构建包含本轮的记忆上下文。
11. 完成分支先设置兜底总结，再尽力调用总结 Agent；模型失败仅记录日志，最终仍保证 `final_evaluation` 和当前展示文本存在。
12. 继续分支要求 `next_topic` 非空，检索 RAG/网页证据，在节点超时保护下生成下一题，更新主题、题量目录和证据快照。
13. 更新会话时间并生成 `AgentRunSnapshot`；状态版本明确为保存前版本加一。
14. runId 存在时保存快照并按策略上限删除最旧项，防止会话 JSON 无限增长。
15. Repository 使用 `expected_version` 乐观保存；成功后才持久化长期记忆，避免半完成轮次污染记忆。
16. 终态会话继续归档记忆；最后更新可查询进度并返回保存后的会话与本轮快照。

## 4. 审核结论

回答接口包含幂等重放、乐观状态校验、四类模型节点、RAG/网页证据、短期/长期记忆和持久化分支。各被调内部函数的完整源码与逐行解析继续在 `02-Agent`、`03-RAG`、`04-记忆工具` 和 `06-网页抓取工具` 文档中展开。
