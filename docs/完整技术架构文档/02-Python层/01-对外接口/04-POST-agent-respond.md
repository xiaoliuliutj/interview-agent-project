# POST /v1/agent/respond：提交回答并推进面试

## 1. 接口定义

该接口接收候选人当前答案以及上层持有的会话状态版本，在 150 秒总超时内完成答案评价、路由决策、证据检索、下一题生成、会话持久化和记忆同步。响应只暴露候选人可见字段，内部推理数据不会原样返回。

| 项目 | 内容 |
|---|---|
| HTTP 方法 | POST |
| 路径 | `/v1/agent/respond` |
| 请求模型 | `AgentRespondRequest` |
| 路由函数 | `respond` |
| 文件 | `python-agent/app/api/application.py:96-133` |
| 总超时 | `INTERVIEW_TURN_TIMEOUT_SECONDS = 150.0` |

## 2. 函数调用链

```text
respond
 -> _remember_request_context
 -> _resolve_service
    -> （进程冷启动）build_interview_agent_service
       -> get_settings / create_session_factory / InterviewWorkflow.load
       -> LLMFactory.create_chat_model / RetryPolicy.load / build_rag_service
       -> build_cache / build_memory_service / IdempotencyPolicy.load
 -> asyncio.wait_for
 -> InterviewAgentService.submit_answer_for_run
 -> InterviewAgentService._submit_answer
    -> PostgresInterviewSessionRepository.get
       -> _cache_key -> RedisCache.get_json
       -> （缓存损坏）RedisCache.delete
       -> （数据库回源）_from_entity -> _cache_session -> RedisCache.set_json
    -> （runId 幂等命中）_synchronize_turn_memory -> MemoryService.record_turn
    -> _validate_expected_state
    -> MemoryService.build_context
       -> PostgresLongTermMemoryRepository.get
       -> （长期记忆不存在）MemoryContext.empty
    -> _run_interview_node -> _report_progress -> RedisCache.set_json
       -> InterviewEvaluationAgent.evaluate
          -> SkillRegistry.get -> PromptLoader.render -> PromptLoader.load -> PromptLoader._resolve
          -> StructuredOutputInvoker.invoke
             -> _few_shot_output -> _invoke_model -> AsyncRetryExecutor.execute
             -> _validate -> _content_as_text -> _strip_json_fence
             -> （格式错误）_readable_validation_error -> 有限纠错
    -> （开场分支）_run_interview_node -> _replan_after_opening
       -> InterviewPlanner.create_plan
          -> SkillRegistry.available_for_interview / select_for_interview / selection_catalog
          -> SkillRegistry.public_catalog -> _validate_public_item
          -> SkillRegistry.get / resolve_for_interview
          -> StructuredOutputInvoker.invoke -> _missing_coverage -> _coverage_matrix
          -> InterviewPlan.validate_stage_order / InterviewPlan.get_stage
    -> _allowed_actions -> _canonical_topic_key
    -> _next_stage
    -> _run_interview_node -> InterviewRoutingAgent.route
       -> SkillRegistry.resolve_for_interview -> PromptLoader.render
       -> StructuredOutputInvoker.invoke
    -> _enforce_route_limits
       -> _fallback_route / _current_stage_topic / _next_stage_route / _canonical_topic_key
    -> _record_turn
    -> _compact_session_history
    -> _apply_route -> _next_stage / _complete
    -> MemoryService.build_context
    -> （结束分支）_report_progress -> _fallback_summary
       -> InterviewSummaryAgent.summarize -> PromptLoader.render -> StructuredOutputInvoker.invoke
       -> （模型总结失败）_fallback_evaluation
    -> （继续分支）_question_evidence
       -> _evidence_cache_key -> _report_progress -> RedisCache.get_json
       -> RagSearchTool.search_for_question_generation -> RagService.search
          -> RedisCache.get_json / RedisCache.delete / RedisCache.set_json
          -> OpenAIEmbeddingProvider.embed_query -> AsyncRetryExecutor.execute
          -> PostgresRagVectorRepository.search -> _from_entity
       -> _evidence_is_insufficient
       -> WebEvidenceTool.search_for_question_generation
          -> _ResultLinkParser.handle_starttag
          -> _unwrap_search_url / _allowed_technical_url
          -> validate_public_url -> _is_public_host
          -> fetch_public_article -> validate_public_url -> _is_public_host
             -> _ArticleParser.handle_starttag / handle_endtag / handle_data / close / _flush
       -> RedisCache.set_json
       -> _run_interview_node -> InterviewQuestionAgent.generate
          -> SkillRegistry.resolve_for_interview -> PromptLoader.render
          -> StructuredOutputInvoker.invoke
       -> _register_question -> _canonical_topic_key
    -> _candidate_visible_output
       -> （完成但缺总结）_fallback_evaluation
    -> PostgresInterviewSessionRepository.save
       -> _cache_key / RedisCache.delete / _cache_session / RedisCache.set_json
    -> MemoryService.record_turn
       -> PostgresLongTermMemoryRepository.get
       -> _append_summary / _merge_items
       -> PostgresLongTermMemoryRepository.save
    -> （终态分支）MemoryService.finalize_session
       -> PostgresLongTermMemoryRepository.get
       -> _append_summary / _merge_items
       -> PostgresLongTermMemoryRepository.save
    -> _report_progress
 -> _candidate_response_output
 -> _success_response
    -> AgentResponse.validate_code_category
```

异常分支：

```text
请求模型校验失败
 -> request_validation_error -> _error_json_response
 -> _error_response -> _session_status_or_failed / _string_or_none
 -> ExceptionHandler.to_code / ExceptionHandler.to_error_info
 -> AgentResponse.validate_code_category
 -> AgentResponse.to_json_dict

150 秒接口级超时
 -> mark_progress_failed -> RedisCache.set_json（后台尽力写）
 -> AgentDependencyError

任意 BaseException
 -> mark_progress_failed -> 原异常继续抛出

ApplicationException
 -> application_error -> _mark_failed_interview_progress
 -> _request_context -> _string_or_none -> mark_progress_failed
 -> _error_json_response -> _error_response
 -> ExceptionHandler.to_code / ExceptionHandler.to_error_info
 -> AgentResponse.to_json_dict

其他 Exception
 -> unexpected_error -> 记录异常日志
 -> 与 ApplicationException 相同的失败进度和统一错误响应链
```

## 3. 函数解析

### 3.1 `respond`

文件：`python-agent/app/api/application.py:96-133`

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

1. 第 96 行：`@app.post` 把 HTTP `POST /v1/agent/respond` 注册到 FastAPI，并指定返回值必须通过 `AgentResponse` 校验与序列化。
2. 第 97 行：定义异步路由函数；`payload` 已经由 `AgentRespondRequest` 完成字段、枚举与别名校验，`request` 提供应用状态和请求上下文。
3. 第 98 行：调用项目函数 `_remember_request_context`，把已校验请求按 JSON 别名保存到 `request.state`，使后续异常响应仍能恢复 `requestId`、`runId`、`sessionId` 等字段。
4. 第 99 行：调用项目函数 `_resolve_service`；优先取应用内已缓存的 `InterviewAgentService`，冷启动时才组装真实依赖。
5. 第 100 行：进入同时覆盖服务调用和总超时转换的 `try` 区域。
6. 第 101 行：调用标准库 `asyncio.wait_for`，把整轮面试处理协程置于 150 秒的接口级时间边界内。
7. 第 102 行：调用项目函数 `InterviewAgentService.submit_answer_for_run`；该公开入口继续委托 `_submit_answer` 执行业务状态机。
8. 第 103 行：把请求中的 `userId` 传为 `user_id`，供服务层校验会话所有者。
9. 第 104 行：传入 `sessionId`，用于读取唯一面试会话。
10. 第 105 行：把本轮候选人答案传入 `candidate_answer`，后续会用于评价、记录和幂等校验。
11. 第 106 行：传入 `runId`；服务层用它保存或命中本轮快照，防止重复请求再次调用模型。
12. 第 107 行：传入上层看到的 `sessionStatus`，作为跨服务状态一致性检查的一部分。
13. 第 108 行：传入上层看到的 `stateVersion`，与数据库乐观锁版本共同阻止陈旧请求覆盖新状态。
14. 第 109 行：结束 `submit_answer_for_run` 参数列表，得到一个待等待的协程对象。
15. 第 110 行：把 `INTERVIEW_TURN_TIMEOUT_SECONDS` 作为 `wait_for` 的 `timeout`；该常量当前为 `150.0` 秒。
16. 第 111 行：结束等待表达式；成功结果保存到 `result`，其中包含保存后的会话和本轮不可变快照。
17. 第 112 行：捕获由接口级 `wait_for` 抛出的 `TimeoutError`，并将原异常绑定为 `error`。
18. 第 113 行：通过 `getattr` 读取 `mark_progress_failed`，这样测试替身即使没有该方法也不会在异常转换阶段再次失败。
19. 第 114 行：用 `callable` 判断读取到的属性确实可调用。
20. 第 115 行：调用项目函数 `mark_progress_failed(sessionId)`，把本机进度和可用的 Redis 进度快照标为 `FAILED`。
21. 第 116 行：开始构造项目异常 `AgentDependencyError`。
22. 第 117 行：给出明确的 150 秒超时提示，并要求调用方保留当前回答。
23. 第 118 行：设置 `retryable=False`；接口无法确认被取消协程的外部副作用，因而不鼓励上层自动重复提交。
24. 第 119 行：以 `raise ... from error` 抛出转换后的应用异常，同时保留原始超时异常链。
25. 第 120 行：捕获其余 `BaseException`，包含普通异常以及协程取消等不属于 `Exception` 的控制流异常。
26. 第 121 行：再次兼容性读取 `mark_progress_failed`。
27. 第 122 行：确认失败标记属性可调用。
28. 第 123 行：把当前会话进度标为 `FAILED`，避免进度接口长期停留在某个处理中阶段。
29. 第 124 行：不改变异常类型，原样重新抛出并交给 FastAPI 异常处理器或上层取消逻辑。
30. 第 125 行：正常路径调用项目函数 `_success_response` 构造统一协议响应。
31. 第 126 行：传回请求中的 `apiVersion` 与 `requestId`，用于上下层协议关联。
32. 第 127 行：传回 `runId`，并把服务层保存后的 `result.session` 交给响应构造器读取用户和会话标识。
33. 第 128 行：使用快照中的下一题/总结作为 `answer`；同时调用项目函数 `_candidate_response_output` 对服务层输出再次执行候选人字段白名单过滤。
34. 第 129 行：使用快照版本而不是临时会话版本，确保返回值与该 `runId` 的幂等结果完全一致。
35. 第 130 行：使用快照中的会话状态，使重复请求也返回首次执行时的状态。
36. 第 131 行：返回被评价答案所属的 `turnStage`。
37. 第 132 行：读取快照的 `current_stage`；`getattr(..., None)` 兼容升级前持久化的旧快照。
38. 第 133 行：结束 `_success_response` 调用并返回 `AgentResponse`。

### 3.2 `_candidate_response_output`

文件：`python-agent/app/api/application.py:426-435`

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

1. 第 426 行：定义候选人输出过滤器；输入允许是字典或 `None`，返回值同样允许为空。
2. 第 427 行：文档字符串说明该函数位于下层服务边界，采用显式白名单而非黑名单。
3. 第 428 行：判断 `output` 是否为 `None` 或空字典。
4. 第 429 行：空输入直接返回 `None`，不构造无意义的空 `output` 对象。
5. 第 430 行：开始定义允许离开 Python 服务的键集合。
6. 第 431 行：允许本轮评价摘要、分数、优点、缺点以及主问题计数。
7. 第 432 行：允许追问计数、总题数、题量预算和最终评价。
8. 第 433 行：结束白名单集合；未列出的记忆、RAG 证据和路由理由都不允许返回。
9. 第 434 行：遍历原输出的全部键值，只把键属于 `allowed` 的项目复制到新字典 `visible`，原字典不被修改。
10. 第 435 行：白名单过滤后若仍为空则返回 `None`，否则返回 `visible`。

### 3.3 `InterviewAgentService.submit_answer_for_run`

文件：`python-agent/app/agents/interview/service.py:279-296`

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

1. 第 279 行：定义异步公开方法 `submit_answer_for_run`。
2. 第 280 行：声明实例参数 `self`。
3. 第 281 行：`*` 强制其后的参数必须以关键字传入，避免多个字符串参数错位。
4. 第 282 行：`user_id` 表示请求所属用户。
5. 第 283 行：`session_id` 表示目标 Agent 会话。
6. 第 284 行：`candidate_answer` 保存本轮原始回答。
7. 第 285 行：`run_id` 是必填幂等键。
8. 第 286 行：`expected_session_status` 是 Java 上层持有的会话状态快照。
9. 第 287 行：`expected_state_version` 是 Java 上层持有的版本快照。
10. 第 288 行：声明返回 `AgentSubmissionResult`，其中同时包含会话和该运行快照。
11. 第 289 行：等待并直接返回项目内部函数 `_submit_answer` 的结果；本方法本身不复制业务逻辑。
12. 第 290 行：原样传递 `user_id`。
13. 第 291 行：原样传递 `session_id`。
14. 第 292 行：原样传递候选人答案。
15. 第 293 行：原样传递 `run_id`，保留幂等语义。
16. 第 294 行：原样传递预期会话状态。
17. 第 295 行：原样传递预期状态版本。
18. 第 296 行：结束内部调用；其返回值直接成为公开方法返回值。

### 3.4 `InterviewAgentService._submit_answer`

文件：`python-agent/app/agents/interview/service.py:298-419`

```python
    async def _submit_answer(
        self,
        *,
        user_id: str,
        session_id: str,
        candidate_answer: str,
        run_id: str | None,
        expected_session_status: SessionStatus | None = None,
        expected_state_version: int | None = None,
    ) -> AgentSubmissionResult:
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
            return AgentSubmissionResult(
                session=session, snapshot=persisted_snapshot
            )
        if expected_session_status is not None and expected_state_version is not None:
            self._validate_expected_state(
                session,
                expected_session_status=expected_session_status,
                expected_state_version=expected_state_version,
            )
        if session.status not in {SessionStatus.ACTIVE, SessionStatus.PAUSED}:
            raise ConsistencyError("当前 Agent 会话不可继续回答")

        if session.status == SessionStatus.PAUSED:
            session.status = SessionStatus.ACTIVE
            session.interrupted = False

        expected_version = session.state_version
        memory_context = await self._memory_service.build_context(session)
        evaluation = await self._run_interview_node(
            session_id, "EVALUATING", lambda: self._evaluation_agent.evaluate(
                session, candidate_answer, memory_context
            )
        )
        if session.current_stage == InterviewStage.OPENING:
            await self._run_interview_node(
                session_id, "PLANNING", lambda: self._replan_after_opening(session, candidate_answer)
            )
        allowed_actions = self._allowed_actions(session, evaluation)
        next_stage = self._next_stage(session)
        route = await self._run_interview_node(
            session_id, "ROUTING", lambda: self._routing_agent.route(
                session,
                evaluation,
                {item.value for item in allowed_actions},
                next_stage.value if next_stage else None,
                memory_context,
            )
        )
        route = self._enforce_route_limits(session, route, allowed_actions, next_stage, evaluation)

        turn = self._record_turn(session, candidate_answer, evaluation, route, run_id)
        self._compact_session_history(session)
        self._apply_route(session, route)
        # The evaluated turn is part of session short-term memory before evidence
        # lookup and question generation.  Long-term persistence remains after the
        # full next state is saved, preventing a failed RAG/model call from leaving
        # a durable memory entry for a turn the session never accepted.
        next_question_memory_context = await self._memory_service.build_context(session)
        if session.status == SessionStatus.COMPLETED:
            await self._report_progress(session_id, "SUMMARIZING")
            session.final_summary = self._fallback_summary(session, interrupted=False)
            if self._summary_agent is not None and session.turns:
                try:
                    session.final_evaluation = await self._run_interview_node(
                        session_id, "SUMMARIZING", lambda: self._summary_agent.summarize(session)
                    )
                    session.final_summary = session.final_evaluation.summary
                except Exception as error:
                    logger.warning("面试会话总结生成失败: session_id=%s", session_id, exc_info=error)
            session.final_evaluation = session.final_evaluation or self._fallback_evaluation(session)
            session.final_summary = session.final_evaluation.summary
            session.current_question = session.final_summary
        elif session.status != SessionStatus.COMPLETED:
            if route.next_topic is None or not route.next_topic.strip():
                raise AgentDependencyError(
                    "模型在需要出题的路由中未返回 nextTopic", retryable=False
                )
            evidence = await self._question_evidence(session, route)
            session.current_question = await self._run_interview_node(
                session_id, "GENERATING_QUESTION", lambda: self._question_agent.generate(
                    session, route, evidence, next_question_memory_context
                )
            )
            session.current_topic = route.next_topic
            self._register_question(
                session, session.current_question, session.current_stage, route.next_topic,
                is_followup=route.action == InterviewAction.FOLLOW_UP,
            )
            # 下轮评分复用这份快照，不为评分额外发起知识库检索。
            session.current_question_evidence = evidence
        session.updated_at = datetime.now(timezone.utc)
        snapshot = AgentRunSnapshot(
            submitted_answer=candidate_answer,
            answer=session.current_question,
            session_status=session.status,
            state_version=expected_version + 1,
            turn_stage=turn.stage,
            current_stage=session.current_stage,
            output=self._candidate_visible_output(session, turn),
        )
        if run_id:
            session.run_snapshots[run_id] = snapshot
            while len(session.run_snapshots) > self._idempotency_policy.max_run_snapshots:
                session.run_snapshots.pop(next(iter(session.run_snapshots)))
        saved = await self._repository.save(session, expected_version=expected_version)
        await self._memory_service.record_turn(session=saved, turn=turn)
        if saved.status in {SessionStatus.COMPLETED, SessionStatus.FAILED}:
            await self._memory_service.finalize_session(
                session=saved, interrupted=saved.status == SessionStatus.FAILED
            )
        await self._report_progress(session_id, "COMPLETED" if saved.status == SessionStatus.COMPLETED else "IDLE")
        return AgentSubmissionResult(session=saved, snapshot=snapshot)
```

逐行解释：

1. 第 298 行：定义内部异步函数 `_submit_answer`；它是一次回答从读取会话到持久化新状态的事务编排入口。
2. 第 299 行：声明实例参数 `self`。
3. 第 300 行：`*` 之后的所有业务参数必须以关键字方式传入。
4. 第 301 行：`user_id` 用于确认调用者与会话所有者一致。
5. 第 302 行：`session_id` 用于读取面试会话。
6. 第 303 行：`candidate_answer` 是本轮需要评价和保存的候选人回答。
7. 第 304 行：`run_id` 允许为空，以兼容内部调用；对当前公开接口它由上一层保证非空。
8. 第 305 行：`expected_session_status` 默认为空；存在时参与跨服务状态校验。
9. 第 306 行：`expected_state_version` 默认为空；存在时参与版本校验。
10. 第 307 行：声明返回 `AgentSubmissionResult`。
11. 第 308 行：调用项目仓储函数 `PostgresInterviewSessionRepository.get(session_id)`；仓储会先查 Redis，再回源 PostgreSQL。
12. 第 309 行：判断仓储是否没有找到会话。
13. 第 310 行：不存在时抛 `ConsistencyError`，阻止为未知 session 临时创建状态。
14. 第 311 行：比较持久化会话的 `user_id` 与请求用户。
15. 第 312 行：用户不匹配时抛一致性错误，避免跨用户访问会话。
16. 第 313 行：检查 `run_id` 是否非空并已经存在于会话的 `run_snapshots` 中，这是幂等命中分支。
17. 第 314 行：读取首次执行该 `run_id` 时保存的快照。
18. 第 315 行：比较旧快照的 `submitted_answer` 与本次回答。
19. 第 316 行：同一 `run_id` 携带不同回答时抛一致性错误，防止幂等键被复用于另一业务输入。
20. 第 317 行：调用 `_synchronize_turn_memory`，补偿“会话已保存但长期记忆写入失败”的窗口。
21. 第 318 行：开始构造幂等返回结果。
22. 第 319 行：返回当前持久化会话和首次运行快照，不重新评价、路由或出题。
23. 第 320 行：结束幂等返回对象并立即退出函数。
24. 第 321 行：只有预期状态和预期版本都存在时才执行强一致性检查；这是对旧调用方的兼容边界。
25. 第 322 行：调用项目函数 `_validate_expected_state`。
26. 第 323 行：把当前持久化会话传入校验函数。
27. 第 324 行：传入上层预期的会话状态。
28. 第 325 行：传入上层预期的状态版本。
29. 第 326 行：结束校验调用；不一致会在函数内抛出异常。
30. 第 327 行：判断会话状态是否既不是 `ACTIVE` 也不是 `PAUSED`。
31. 第 328 行：对已完成、失败等状态拒绝继续回答。
32. 第 330 行：检测暂停会话；暂停状态允许候选人通过回答恢复。
33. 第 331 行：把状态恢复为 `ACTIVE`。
34. 第 332 行：清除 `interrupted` 标记，使后续总结按正常完成处理。
35. 第 334 行：把读取时的 `state_version` 保存为 `expected_version`，后续 PostgreSQL 更新以它作为乐观锁条件。
36. 第 335 行：调用 `MemoryService.build_context` 读取短期会话历史与用户长期记忆，得到本轮评价上下文。
37. 第 336 行：调用项目函数 `_run_interview_node` 运行评价节点。
38. 第 337 行：传入会话号和进度阶段 `EVALUATING`；第三个参数是延迟执行的 lambda。
39. 第 338 行：lambda 调用项目函数 `InterviewEvaluationAgent.evaluate(session, candidate_answer, memory_context)`。
40. 第 339-340 行：结束 lambda 和节点调用；结构化评价保存为 `evaluation`。
41. 第 341 行：判断当前是否处于 `OPENING`；只有自我介绍回答会触发二次规划。
42. 第 342 行：再次通过 `_run_interview_node` 执行规划节点。
43. 第 343 行：阶段标为 `PLANNING`，lambda 调用项目函数 `_replan_after_opening(session, candidate_answer)`。
44. 第 344 行：结束开场重新规划调用；非开场分支直接跳过。
45. 第 345 行：调用 `_allowed_actions(session, evaluation)`，由程序规则计算模型本轮可以选择的动作集合。
46. 第 346 行：调用 `_next_stage(session)` 预先计算工作流中下一个有效阶段。
47. 第 347 行：通过 `_run_interview_node` 启动路由节点。
48. 第 348 行：阶段标为 `ROUTING`，lambda 调用 `InterviewRoutingAgent.route`。
49. 第 349 行：向路由 Agent 传入当前会话。
50. 第 350 行：传入已经完成本地结构校验的评价。
51. 第 351 行：把 `InterviewAction` 枚举集合转换成字符串集合；模型只能在这些值中决策。
52. 第 352 行：存在下一阶段时传入其枚举值，否则传入 `None`。
53. 第 353 行：传入评价前构建的记忆上下文，供路由结合历史和候选人画像。
54. 第 354-355 行：结束路由 Agent 和节点调用，得到结构化 `route`。
55. 第 356 行：调用 `_enforce_route_limits`，以确定性题量、主题和阶段规则再次校正模型给出的软决策。
56. 第 358 行：调用 `_record_turn`，把当前问题、回答、评价、路由和 runId 组成 `TurnRecord` 并追加到会话。
57. 第 359 行：调用 `_compact_session_history`，为模型构造最多 2000 字的早期轮次摘要；原始 `turns` 并不删除。
58. 第 360 行：调用 `_apply_route`，根据动作修改状态、阶段和题量计数。
59. 第 361 行：注释说明当前已评价轮次必须先进入会话短期记忆。
60. 第 362 行：注释说明下一步证据检索和出题应看到该轮次。
61. 第 363 行：注释说明长期记忆仍要等完整新状态保存成功后再写。
62. 第 364 行：注释给出原因：RAG 或模型失败时不能留下会话未接受的永久记忆。
63. 第 365 行：再次调用 `MemoryService.build_context`，得到已经包含本轮的下一题上下文。
64. 第 366 行：判断 `_apply_route` 后会话是否已经完成。
65. 第 367 行：调用 `_report_progress(session_id, "SUMMARIZING")`，同步本机、Redis 和可选回调进度。
66. 第 368 行：调用 `_fallback_summary` 先写入确定性完成文本，确保总结模型失败时仍有可展示内容。
67. 第 369 行：仅在已配置总结 Agent 且存在至少一轮回答时调用模型总结。
68. 第 370 行：进入总结 Agent 的局部 `try`，因为总结失败不应推翻已经完成的面试。
69. 第 371 行：通过 `_run_interview_node` 执行总结节点。
70. 第 372 行：传入 `SUMMARIZING` 阶段，并延迟调用 `InterviewSummaryAgent.summarize(session)`。
71. 第 373 行：结束节点调用，将结构化总结保存到 `session.final_evaluation`。
72. 第 374 行：把结构化总结的 `summary` 同步到兼容字段 `final_summary`。
73. 第 375 行：捕获总结过程中的任意普通异常。
74. 第 376 行：记录带会话号和堆栈的 warning；异常被有意吞掉，函数继续使用确定性兜底。
75. 第 377 行：若模型总结为空，调用 `_fallback_evaluation(session)` 生成结构化评分报告；已有模型结果则保持不变。
76. 第 378 行：再次从最终结构化评价同步 `final_summary`，保证两条分支字段一致。
77. 第 379 行：把最终总结放入 `current_question`，复用统一响应中的 `answer` 字段返回结果。
78. 第 380 行：未完成时进入继续出题分支；条件写成 `elif status != COMPLETED`，覆盖所有非完成状态。
79. 第 381 行：检查路由是否提供了非空 `next_topic`。
80. 第 382 行：缺失主题时开始构造 `AgentDependencyError`。
81. 第 383 行：错误消息明确模型缺少出题方向，并设置 `retryable=False`，因为这是结构化业务输出违约。
82. 第 384 行：结束并抛出异常，不在未知主题下生成泛化问题。
83. 第 385 行：调用 `_question_evidence`；该函数按主题执行会话/Redis 缓存、RAG 和可选网页证据检索。
84. 第 386 行：通过 `_run_interview_node` 执行问题生成节点。
85. 第 387 行：阶段设为 `GENERATING_QUESTION`，lambda 调用 `InterviewQuestionAgent.generate`。
86. 第 388 行：向出题 Agent 传入会话、已约束路由、证据和包含本轮的记忆上下文。
87. 第 389-390 行：结束 Agent 和节点调用，把生成的题目字符串保存为 `session.current_question`。
88. 第 391 行：把路由主题保存为当前主题，作为下一轮评分和追问边界。
89. 第 392 行：调用 `_register_question` 登记新问题。
90. 第 393 行：传入会话、问题文本、当前阶段和主题。
91. 第 394 行：只有动作为 `FOLLOW_UP` 时将 `is_followup` 设为真，避免把追问计入主问题数。
92. 第 395 行：结束问题登记调用。
93. 第 396 行：注释说明下一轮评分直接复用本次出题证据。
94. 第 397 行：把证据快照保存到 `current_question_evidence`，评分节点无需再次检索。
95. 第 398 行：用 UTC 当前时间更新会话业务对象的 `updated_at`。
96. 第 399 行：开始构造与本次 `run_id` 绑定的 `AgentRunSnapshot`。
97. 第 400 行：记录原始提交答案，用于幂等重复请求的输入一致性比较。
98. 第 401 行：保存本次执行后应返回的新问题或最终总结。
99. 第 402 行：保存执行后的会话状态。
100. 第 403 行：快照版本明确设为旧版本 `expected_version + 1`，与仓储成功更新后的版本一致。
101. 第 404 行：保存刚记录轮次所属阶段，而不是路由切换后的阶段。
102. 第 405 行：保存当前执行后的阶段，供上层更新页面状态。
103. 第 406 行：调用 `_candidate_visible_output` 生成候选人可见的评价和题量字段。
104. 第 407 行：结束快照构造。
105. 第 408 行：只有存在 `run_id` 时才持久化幂等快照。
106. 第 409 行：以 `run_id` 为键写入本次快照。
107. 第 410 行：当快照数量超过 `IdempotencyPolicy.max_run_snapshots` 时循环清理。
108. 第 411 行：按字典插入顺序删除最旧快照，限制会话 JSON 的无界增长。
109. 第 412 行：调用 `PostgresInterviewSessionRepository.save(session, expected_version)`；数据库更新使用旧版本作为 WHERE 条件。
110. 第 413 行：会话提交成功后才调用 `MemoryService.record_turn` 写长期记忆，避免失败轮次污染画像。
111. 第 414 行：判断保存后状态是否为 `COMPLETED` 或 `FAILED`。
112. 第 415 行：终态时调用 `MemoryService.finalize_session` 归档整个会话。
113. 第 416 行：传入保存后的会话，保证归档读取的状态版本与数据库一致。
114. 第 417 行：只有失败状态才把 `interrupted` 设为真；正常完成使用完成摘要。
115. 第 418 行：结束终态记忆归档调用。
116. 第 418 行：调用 `_report_progress` 写入最终可观察进度；完成状态写 `COMPLETED`，其余仍可继续的状态写 `IDLE`。
117. 第 419 行：返回 `AgentSubmissionResult(session=saved, snapshot=snapshot)`，使入口拿到已提交会话和幂等快照。

### 3.5 `_remember_request_context`

文件：`python-agent/app/api/application.py:391-394`

逐行解释：

1. 第 391 行：定义无返回值辅助函数，参数是 FastAPI `Request` 与已经校验的请求模型 `payload`。
2. 第 392 行：通过 `getattr` 读取 Pydantic 模型的 `model_dump`；测试替身或非 Pydantic 对象不会因此抛 `AttributeError`。
3. 第 393 行：确认读取到的属性可调用。
4. 第 394 行：调用 `model_dump(by_alias=True, mode="json")`，把字段转成上层协议别名和 JSON 可序列化值，再保存到 `request.state.agent_context`。

### 3.6 `_resolve_service` 与冷启动依赖组装

文件：`python-agent/app/api/application.py:315-320`

逐行解释：

1. 第 315 行：定义面试服务解析函数。
2. 第 316 行：从 `request.app.state.interview_agent_service` 读取应用级单例。
3. 第 317 行：判断当前进程是否尚未创建服务。
4. 第 318 行：冷启动时调用项目函数 `build_interview_agent_service()`。
5. 第 319 行：把新服务写回应用状态，后续请求复用同一依赖图。
6. 第 320 行：返回可用的 `InterviewAgentService`。

`build_interview_agent_service` 文件：`python-agent/app/bootstrap.py:45-79`

逐行解释：

1. 第 45-47 行：定义接受可选 `Settings` 的工厂；未传配置时从进程环境读取。
2. 第 48 行：文档字符串声明生产依赖不会自动建表，也不会在数据库不可用时退化为临时文件。
3. 第 50 行：选择调用方配置或调用项目函数 `get_settings()` 读取配置。
4. 第 51 行：调用项目函数 `create_session_factory(current)` 创建 SQLAlchemy 异步会话工厂。
5. 第 52 行：实例化 `PromptLoader`，默认根目录是 `resources/prompts`。
6. 第 53 行：实例化 `SkillRegistry`，默认读取 `resources/skills`。
7. 第 54 行：调用 `InterviewWorkflow.load(prompt_loader)` 加载项目工作流阶段配置。
8. 第 55 行：调用 `LLMFactory.create_chat_model(current)` 创建 OpenAI-compatible 模型客户端。
9. 第 56 行：调用 `RetryPolicy.load()` 读取重试策略，再构造 `AsyncRetryExecutor`。
10. 第 57 行：只有配置了 embedding 模型才调用 `build_rag_service(current)` 并包装成 `RagSearchTool`；否则 RAG 工具为 `None`。
11. 第 59 行：开始构造 `InterviewAgentService`。
12. 第 60-62 行：创建 `InterviewPlanner`，注入模型、Prompt、Skill 与统一重试器。
13. 第 63-65 行：以相同基础依赖创建评价 Agent。
14. 第 66-68 行：以相同基础依赖创建路由 Agent。
15. 第 69 行：创建出题 Agent。
16. 第 70 行：注入可选 RAG 搜索工具。
17. 第 71 行：创建 PostgreSQL 会话仓储，并调用 `build_cache(current)` 为它注入 Python 独立 Redis。
18. 第 72 行：注入工作流定义。
19. 第 73 行：注入 PromptLoader。
20. 第 74 行：调用 `build_memory_service(current)` 创建长期记忆服务及其 PostgreSQL 仓储。
21. 第 75 行：创建会话总结 Agent。
22. 第 76 行：调用 `IdempotencyPolicy.load()` 读取快照保留上限。
23. 第 77 行：实例化 `WebEvidenceTool` 作为本地证据不足时的可选补充层。
24. 第 78 行：再次调用 `build_cache(current)`，为进度与证据缓存创建 Redis 包装器。
25. 第 79 行：结束服务构造并返回完整依赖图。

### 3.7 `mark_progress_failed`

文件：`python-agent/app/agents/interview/service.py:114-124`

逐行解释：

1. 第 114 行：定义同步失败进度标记函数，输入为会话号。
2. 第 115 行：文档字符串说明失败运行必须保持可观察，不能伪装成空闲。
3. 第 116 行：忽略空会话号，避免产生共享的空键。
4. 第 117 行：把进程内 `_progress[session_id]` 设为 `FAILED`，使同实例查询立即可见。
5. 第 118 行：判断是否配置 Redis 缓存。
6. 第 119 行：进入事件循环创建任务的保护块。
7. 第 120 行：取得当前运行事件循环并创建后台任务，避免同步异常处理函数阻塞等待 Redis。
8. 第 121 行：后台调用项目函数 `RedisCache.set_json`，键为 `python:agent-progress:{session_id}`，值为失败阶段，TTL 为 86400 秒。
9. 第 122 行：结束后台任务创建。
10. 第 123 行：捕获当前线程不存在运行事件循环时的 `RuntimeError`。
11. 第 124 行：无运行循环时只保留本机失败状态；该兜底不会覆盖原业务异常。

### 3.8 `_report_progress`

文件：`python-agent/app/agents/interview/service.py:126-133`

逐行解释：

1. 第 126 行：定义异步进度上报函数。
2. 第 127 行：先更新当前进程的进度字典，保证本地读取不依赖 Redis。
3. 第 128 行：判断是否配置缓存。
4. 第 129 行：调用项目函数 `RedisCache.set_json`。
5. 第 130 行：写入独立 Python Redis 的会话进度键，TTL 为一天，支持跨实例查询。
6. 第 131 行：结束 Redis 写入。
7. 第 132 行：判断是否注册了额外异步进度回调。
8. 第 133 行：存在回调时等待其完成，把相同 sessionId 和阶段传给外部观察者。

### 3.9 `_run_interview_node`

文件：`python-agent/app/agents/interview/service.py:135-148`

逐行解释：

1. 第 135 行：定义统一模型节点执行器。
2. 第 136 行：接收会话号、阶段名和一个调用后才产生协程的 `operation`。
3. 第 137 行：返回类型是任意节点结果对象。
4. 第 138 行：节点开始前先调用 `_report_progress`，使查询方看到真实阶段。
5. 第 139 行：进入节点超时转换保护。
6. 第 140 行：使用标准库 `asyncio.wait_for` 等待传入操作。
7. 第 141 行：调用 `operation()` 创建实际 Agent 协程，并把单节点上限设为 `INTERVIEW_MODEL_NODE_TIMEOUT_SECONDS`。
8. 第 142 行：成功时直接返回 Agent 结果。
9. 第 143 行：捕获节点级 `TimeoutError`。
10. 第 144 行：调用 `mark_progress_failed` 标记失败。
11. 第 145 行：开始构造 `AgentDependencyError`。
12. 第 146 行：错误消息包含实际阶段与整数秒超时上限。
13. 第 147 行：设置 `retryable=True`，表示单个模型节点超时可由受控调用方重试。
14. 第 148 行：从原超时异常抛出项目异常，保留异常链。

### 3.10 `_validate_expected_state`

文件：`python-agent/app/agents/interview/service.py:427-440`

逐行解释：

1. 第 427 行：`@staticmethod` 表明校验不读取服务实例状态。
2. 第 428 行：定义状态校验函数。
3. 第 429 行：接收数据库读取到的 `InterviewSession`。
4. 第 430 行：`*` 强制预期值必须具名传入。
5. 第 431 行：接收上层预期会话状态。
6. 第 432 行：接收上层预期状态版本。
7. 第 433 行：函数只校验，正常时不返回业务值。
8. 第 434 行：开始组合不一致条件。
9. 第 435 行：比较持久化状态与请求状态。
10. 第 436 行：用 `or` 再比较持久化版本与请求版本。
11. 第 437 行：结束条件；任一不相同即进入异常分支。
12. 第 438 行：构造 `ConsistencyError`。
13. 第 439 行：提示上层先恢复最新会话状态，而不是覆盖下层新状态。
14. 第 440 行：结束并抛出异常。

### 3.11 `_allowed_actions`

文件：`python-agent/app/agents/interview/service.py:442-490`

逐行解释：

1. 第 442-444 行：定义允许动作计算函数，输入会话和可选评价，返回 `InterviewAction` 集合。
2. 第 445 行：检查是否仍在开场阶段。
3. 第 446 行：开场回答后只允许 `NEXT_STAGE`，禁止追问、普通换题或直接结束。
4. 第 448 行：调用计划对象 `get_stage` 取得当前阶段题量约束。
5. 第 449 行：创建空动作集合。
6. 第 450-452 行：读取总题数；兼容旧会话缺少 `total_question_count` 时退回主问题总数。
7. 第 453 行：比较总题数与“会话目标题数、系统最大题数”中的较小值。
8. 第 454 行：达到整场上限时只允许 `END_INTERVIEW`。
9. 第 455 行：读取当前阶段已登记的主问题数量，缺失时为 0。
10. 第 456 行：算法阶段上限固定为 2。
11. 第 457-459 行：其他阶段上限取计划值并夹在系统最小、最大主问题数之间。
12. 第 460 行：只有存在评价时才计算是否需要追问。
13. 第 461-462 行：分数不高于 60 或存在弱项即认为需要追问。
14. 第 463 行：读取并去除当前主题两端空白。
15. 第 464 行：调用 `_canonical_topic_key` 把主题归一到计划主题键。
16. 第 465 行：读取该主题已经累计的问题数。
17. 第 466-470 行：同时检查答案确需追问、追问数未达阶段/系统上限、主题题数未达上限。
18. 第 471 行：条件全部成立时加入 `FOLLOW_UP`。
19. 第 472 行：进入算法阶段的专门规则。
20. 第 473 行：注释说明算法第二题只在第一题严重不足时允许。
21. 第 474-478 行：当阶段正好完成 1 题、评价存在且分数低于严重阈值时满足强制补题条件。
22. 第 479 行：直接返回只含 `NEXT_QUESTION` 的集合。
23. 第 480-481 行：注释说明其余算法分支必须进入总结，不能暴露普通提前结束动作。
24. 第 482 行：返回只含 `NEXT_STAGE` 的集合。
25. 第 483 行：非算法阶段尚未达到最低主问题覆盖数时进入补题分支。
26. 第 484 行：加入 `NEXT_QUESTION`。
27. 第 485 行：达到最低覆盖后进入可扩展分支。
28. 第 486 行：同时允许继续换主问题或推进阶段，让路由 Agent 结合评价决策。
29. 第 487 行：检查当前阶段是否已经达到硬上限。
30. 第 488 行：达到上限时移除 `NEXT_QUESTION`。
31. 第 489 行：确保加入 `NEXT_STAGE`。
32. 第 490 行：返回最终允许集合。

### 3.12 `_replan_after_opening`

文件：`python-agent/app/agents/interview/service.py:492-520`

逐行解释：

1. 第 492-496 行：定义异步重新规划函数，接收当前会话和候选人自我介绍，不返回业务对象。
2. 第 497 行：文档字符串说明自我介绍中的新增事实要纳入正式计划。
3. 第 498-499 行：注释说明旧会话可能缺少真实简历、岗位或时长快照，此时不得伪造信息。
4. 第 500 行：检查 `resume_text`、`target_role` 和 `interview_duration_minutes` 是否齐全。
5. 第 501 行：任一关键快照缺失时保留原计划并返回。
6. 第 502 行：开始构造新的 `CandidateProfile`。
7. 第 503 行：复制候选人标识。
8. 第 504 行：复制简历标识。
9. 第 505 行：复制 JD 标识。
10. 第 506 行：开始构造补充后的简历文本。
11. 第 507 行：在原简历后追加“候选人自我介绍”和去除首尾空白后的本轮回答。
12. 第 508 行：结束文本表达式。
13. 第 509 行：复制 JD 文本快照。
14. 第 510 行：复制目标岗位。
15. 第 511 行：复制面试时长。
16. 第 512 行：把当前会话难度作为期望难度。
17. 第 513 行：把当前目标题数放入画像。
18. 第 514 行：复制面试方向。
19. 第 515 行：复制自定义分类。
20. 第 516 行：复制系统知识库 ID。
21. 第 517 行：复制用户知识库 ID。
22. 第 518 行：结束画像构造。
23. 第 519 行：调用项目函数 `InterviewPlanner.create_plan(profile)`，用补充后的候选人事实生成新计划。
24. 第 520 行：把新计划实际选择的 Skill 同步到会话。

### 3.13 `_enforce_route_limits`

文件：`python-agent/app/agents/interview/service.py:522-580`

逐行解释：

1. 第 522-529 行：定义路由硬约束函数，接收会话、模型路由、允许动作、下一阶段和可选评价，返回合法 `InterviewRoute`。
2. 第 530 行：文档字符串说明模型只是软决策，最终必须收敛到程序边界。
3. 第 531-533 行：兼容读取会话总题数。
4. 第 534 行：检查是否达到会话目标或系统最大题数。
5. 第 535 行：达到整场上限时无条件返回 `END_INTERVIEW`。
6. 第 537 行：检查模型动作是否不在 `_allowed_actions` 集合内。
7. 第 538 行：越界时调用 `_fallback_route` 生成确定性合法动作。
8. 第 540-542 行：模型选择追问但当前评价分数高于 60 且没有弱项时，判定追问理由不足。
9. 第 543 行：调用 `_fallback_route`。
10. 第 544-547 行：传入会话、被拒绝的追问动作、移除追问后的允许集合和下一阶段。
11. 第 548 行：返回替代路由。
12. 第 550 行：进入仍然合法的 `FOLLOW_UP` 分支。
13. 第 551 行：注释规定追问不能偷换主题。
14. 第 552 行：优先使用当前主题，缺失时才退到模型主题，并去除空白。
15. 第 553 行：调用 `_canonical_topic_key` 归一主题。
16. 第 554 行：主题为空或该主题题数达到上限时判定无法继续追问。
17. 第 555-560 行：调用 `_fallback_route`，并从允许集合移除 `FOLLOW_UP`，防止递归选择同一非法动作。
18. 第 561 行：合法时返回固定当前主题的 `FOLLOW_UP` 路由。
19. 第 563 行：进入 `NEXT_QUESTION` 分支。
20. 第 564 行：读取并清理模型建议主题。
21. 第 565 行：非空时调用 `_canonical_topic_key`，空主题使用空键。
22. 第 566 行：主题为空或已达到主题上限时需要重新选择。
23. 第 567 行：调用 `_current_stage_topic` 从计划中找尚可覆盖的主题。
24. 第 568 行：重新选择后仍没有主题时进入回退。
25. 第 569-574 行：调用 `_fallback_route`，并移除 `NEXT_QUESTION` 防止再次选择不可执行动作。
26. 第 575 行：主题有效时返回 `NEXT_QUESTION` 和最终主题。
27. 第 577 行：进入 `NEXT_STAGE` 分支。
28. 第 578 行：调用 `_next_stage_route`，校正跨阶段主题并返回确定性路由。
29. 第 580 行：其余已合法动作原样返回。

### 3.14 `_fallback_route`

文件：`python-agent/app/agents/interview/service.py:582-610`

逐行解释：

1. 第 582-588 行：定义越界路由回退函数，输入被拒绝动作和剩余允许动作，返回确定性路由。
2. 第 589 行：文档字符串说明回退仍须符合当前阶段语义。
3. 第 590-591 行：注释解释模型错误提前结束时应推进阶段而非跳过后续考察。
4. 第 592 行：检查被拒绝的是 `END_INTERVIEW` 且程序允许 `NEXT_STAGE`。
5. 第 593 行：调用 `_next_stage_route` 推进到下一阶段。
6. 第 595-596 行：注释说明其他越界情况优先在当前阶段换主问题。
7. 第 597 行：检查剩余集合是否允许 `NEXT_QUESTION`。
8. 第 598 行：调用 `_current_stage_topic` 选择主题。
9. 第 599 行：确认选择到了非空主题。
10. 第 600 行：返回当前阶段的 `NEXT_QUESTION` 路由。
11. 第 602 行：若不能换题，检查是否允许推进阶段。
12. 第 603 行：允许时调用 `_next_stage_route`。
13. 第 605 行：再检查是否仅剩追问动作。
14. 第 606 行：优先当前主题，否则调用 `_current_stage_topic`，最后去除空白。
15. 第 607 行：确认追问主题非空。
16. 第 608 行：返回固定主题的 `FOLLOW_UP` 路由。
17. 第 610 行：所有其他动作都不可执行时返回 `END_INTERVIEW` 作为最终安全终止。

### 3.15 `_current_stage_topic`

文件：`python-agent/app/agents/interview/service.py:612-628`

逐行解释：

1. 第 612 行：定义当前阶段主题选择函数。
2. 第 613 行：文档字符串规定优先选择未覆盖且未达到上限的主题。
3. 第 614 行：调用计划对象 `get_stage(current_stage)` 取得当前阶段主题列表。
4. 第 615 行：第一次遍历计划主题。
5. 第 616 行：调用 `_canonical_topic_key` 归一当前候选主题。
6. 第 617 行：检查该主题累计题数是否为 0。
7. 第 618 行：发现从未覆盖的主题时立即返回，优先保证主题覆盖面。
8. 第 619 行：没有全新主题时第二次遍历计划主题。
9. 第 620 行：再次归一主题键。
10. 第 621 行：检查题数是否仍低于 `MAX_QUESTIONS_PER_TOPIC`。
11. 第 622 行：返回第一个仍有容量的计划主题。
12. 第 623 行：计划主题都无法选择时，读取并清理当前主题。
13. 第 624 行：确认当前主题非空。
14. 第 625 行：调用 `_canonical_topic_key` 归一当前主题。
15. 第 626 行：检查当前主题题数是否仍低于上限。
16. 第 627 行：仍有容量时返回当前主题作为最后可用选择。
17. 第 628 行：没有任何合法主题时返回 `None`。

### 3.16 `_next_stage_route`

文件：`python-agent/app/agents/interview/service.py:630-649`

逐行解释：

1. 第 630-635 行：定义跨阶段路由构造函数，接收会话、可选下一阶段和模型建议主题。
2. 第 636 行：下一阶段不存在或已经是 `SUMMARY` 时表示业务应结束。
3. 第 637 行：返回不带主题的 `NEXT_STAGE`；随后 `_apply_route` 会调用 `_complete`。
4. 第 638 行：读取下一阶段计划主题列表。
5. 第 639-640 行：注释说明模型可以细化主题，但不能把当前阶段原主题直接带到下一阶段。
6. 第 641 行：压缩建议主题内部空白并转为大小写无关形式。
7. 第 642 行：开始构造当前阶段主题规范化集合。
8. 第 643 行：逐个压缩空白并 `casefold`。
9. 第 644 行：主题来源是当前阶段计划。
10. 第 645 行：结束集合推导。
11. 第 646 行：保留建议主题原显示文本并清理首尾空白。
12. 第 647 行：建议为空或等同当前阶段主题时判定建议无效。
13. 第 648 行：优先选下一阶段第一个计划主题；阶段主题为空时使用阶段枚举值。
14. 第 649 行：返回带校正主题的 `NEXT_STAGE` 路由。

### 3.17 `_next_stage`

文件：`python-agent/app/agents/interview/service.py:651-657`

逐行解释：

1. 第 651 行：定义后继阶段查找函数。
2. 第 652 行：在工作流阶段列表中找到当前阶段下标。
3. 第 653 行：遍历当前阶段之后的所有阶段。
4. 第 654 行：读取候选阶段的计划配置。
5. 第 655 行：`SUMMARY` 总是有效；其他阶段只有最大主问题数大于 0 才有效。
6. 第 656 行：返回第一个有效后继阶段。
7. 第 657 行：遍历完仍未找到时返回 `None`。

### 3.18 `_synchronize_turn_memory`

文件：`python-agent/app/agents/interview/service.py:659-665`

逐行解释：

1. 第 659-661 行：定义幂等补偿函数，接收会话和已命中的 `run_id`。
2. 第 662 行：从最新轮次向前遍历，通常最快找到对应运行。
3. 第 663 行：比较每个 `turn.run_id`。
4. 第 664 行：命中后调用 `MemoryService.record_turn`；该函数自身以 `turn_id` 幂等，因此重复补写安全。
5. 第 665 行：补偿一次后立即返回；找不到对应轮次时自然结束而不伪造记忆。

### 3.19 `_record_turn`

文件：`python-agent/app/agents/interview/service.py:667-690`

逐行解释：

1. 第 667-674 行：定义轮次记录函数，接收会话、原始回答、结构化评价、最终路由和可选 runId，返回 `TurnRecord`。
2. 第 675 行：开始构造 `TurnRecord`；未显式传入的 `turn_id`、时间等由模型默认工厂生成。
3. 第 676 行：保存 runId，供幂等记忆补偿定位。
4. 第 677 行：保存回答发生时的阶段；它在 `_apply_route` 之前读取。
5. 第 678 行：保存当前主题。
6. 第 679 行：保存候选人实际看到的问题。
7. 第 680 行：保存候选人原始回答。
8. 第 681 行：保存经过硬约束后的动作。
9. 第 682 行：保存评价摘要。
10. 第 683 行：保存评分。
11. 第 684 行：保存模型生成的答案摘要，后续用于压缩历史。
12. 第 685 行：保存优点列表。
13. 第 686 行：保存弱项列表。
14. 第 687 行：保存从回答中识别的偏好。
15. 第 688 行：结束轮次对象构造。
16. 第 689 行：把完整轮次追加到会话 `turns`。
17. 第 690 行：返回同一个轮次对象，供快照输出和长期记忆写入复用。

### 3.20 `_register_question` 与 `_canonical_topic_key`

`_register_question` 文件：`python-agent/app/agents/interview/service.py:692-710`

逐行解释：

1. 第 692 行：声明静态方法，不读取服务实例字段。
2. 第 693-700 行：定义问题登记函数，接收会话、问题、阶段、主题和追问标志，不返回值。
3. 第 701 行：把新问题压缩空白并转为大小写无关文本，用于去重。
4. 第 702 行：对已问题录逐项做相同规范化，构造集合。
5. 第 703 行：检查规范化问题是否尚未出现。
6. 第 704 行：新问题才追加到 `asked_question_catalog`；原始显示文本保持不变。
7. 第 705 行：取阶段枚举值作为计数字典键。
8. 第 706 行：追问不计为主问题，只有非追问才更新阶段主问题数。
9. 第 707 行：读取旧计数并加一。
10. 第 708 行：主题存在且去除空白后非空时才更新主题计数。
11. 第 709 行：调用 `_canonical_topic_key` 把模型细化主题归并到计划主题。
12. 第 710 行：读取该主题旧计数并加一。

`_canonical_topic_key` 文件：`python-agent/app/agents/interview/service.py:712-725`

逐行解释：

1. 第 712 行：声明静态方法。
2. 第 713 行：定义主题规范键函数。
3. 第 714 行：压缩连续空白并转为大小写无关文本。
4. 第 715 行：判断规范化结果是否为空。
5. 第 716 行：空主题直接返回空键。
6. 第 717 行：进入当前阶段计划读取保护。
7. 第 718 行：读取当前阶段候选主题。
8. 第 719 行：捕获阶段不存在或值非法。
9. 第 720 行：无法读取计划时使用空候选列表，后续仍可返回规范化模型主题。
10. 第 721 行：遍历计划主题。
11. 第 722 行：对计划主题执行同样规范化。
12. 第 723 行：非空计划主题与输入存在双向包含关系时视为同一主题。
13. 第 724 行：返回计划主题键，使“Java 线程池参数”和“线程池”等细化描述共用计数。
14. 第 725 行：没有匹配计划主题时返回输入自身的规范化键。

### 3.21 `_compact_session_history`

文件：`python-agent/app/agents/interview/service.py:727-739`

逐行解释：

1. 第 727 行：声明静态方法。
2. 第 728 行：定义历史压缩函数，默认保留最近 5 轮不进入摘要。
3. 第 729 行：文档字符串强调原始问答仍完整存放在 `session.turns`。
4. 第 730 行：切片取得最近 `limit` 轮之前的较早轮次。
5. 第 731 行：检查是否存在需要压缩的旧轮次。
6. 第 732 行：没有旧轮次时直接返回，保持原摘要不变。
7. 第 733 行：创建摘要条目列表。
8. 第 734 行：遍历所有较早轮次。
9. 第 735 行：优先使用轮次主题，缺失时用阶段名。
10. 第 736 行：开始追加一条摘要字符串。
11. 第 737 行：摘要保留主题、原问题、答案摘要和评分，不把完整原回答再次放入模型上下文。
12. 第 738 行：结束追加调用。
13. 第 739 行：用换行拼接、去除首尾空白并只保留最后 2000 个字符，限制提示词大小。

### 3.22 `_apply_route` 与 `_complete`

`_apply_route` 文件：`python-agent/app/agents/interview/service.py:741-769`

逐行解释：

1. 第 741-743 行：定义路由状态应用函数，直接修改传入会话。
2. 第 744 行：检查动作为 `FOLLOW_UP`。
3. 第 745 行：追问计数加一。
4. 第 746 行：总题数加一。
5. 第 747 行：结束追问分支；主问题和阶段保持不变。
6. 第 749 行：检查动作为 `NEXT_QUESTION`。
7. 第 750 行：当前阶段主问题序号加一。
8. 第 751 行：整场主问题总数加一。
9. 第 752 行：总题数加一。
10. 第 753 行：换主问题后重置当前题的追问计数。
11. 第 754 行：结束换题分支。
12. 第 756 行：检查动作为 `END_INTERVIEW`。
13. 第 757 行：调用 `_complete(session)` 进入完成状态。
14. 第 758 行：结束终止分支。
15. 第 760 行：其余合法动作是推进阶段，调用 `_next_stage` 再次取得后继阶段。
16. 第 761 行：下一阶段不存在或为 `SUMMARY` 时说明无需再出题。
17. 第 762 行：调用 `_complete`。
18. 第 763 行：结束完成分支。
19. 第 765 行：把会话当前阶段切换为下一阶段。
20. 第 766 行：新阶段的当前主问题序号重置为 1。
21. 第 767 行：整场主问题总数加一，因为即将生成新阶段第一题。
22. 第 768 行：总题数同步加一。
23. 第 769 行：新主问题追问数重置为 0。

`_complete` 文件：`python-agent/app/agents/interview/service.py:867-874`

逐行解释：

1. 第 867 行：声明静态方法。
2. 第 868 行：定义会话完成状态转换函数。
3. 第 869 行：把当前阶段设为 `SUMMARY`。
4. 第 870 行：把会话状态设为 `COMPLETED`。
5. 第 871 行：先清空旧 `final_summary`，后续完成分支会生成当前总结。
6. 第 872 行：清空旧问题，防止完成响应误返回上一题。
7. 第 873 行：清空当前问题证据。
8. 第 874 行：清空会话级 RAG 证据缓存，终态不再需要这些出题材料。

### 3.23 `_question_evidence`

文件：`python-agent/app/agents/interview/service.py:771-849`

逐行解释：

1. 第 771 行：定义异步证据获取函数，输入会话和已经确定的路由，返回证据字典列表。
2. 第 772 行：文档字符串说明先读会话证据缓存，未命中才检索。
3. 第 774 行：规定调用前路由节点必须已经确定 `next_topic`。
4. 第 775-776 行：说明证据只用于构造下一题，不能反向修改本轮评分或路由。
5. 第 778 行：检查路由主题是否缺失或仅含空白。
6. 第 779 行：缺少主题时抛不可重试的 `AgentDependencyError`。
7. 第 780 行：把已校验主题保存为局部变量。
8. 第 781 行：合并系统与用户知识库 ID，并用 `dict.fromkeys` 按原顺序去重后转成元组。
9. 第 782 行：调用 `_evidence_cache_key` 构造稳定缓存键。
10. 第 783-785 行：键输入包含当前阶段、主题和去重后的知识库集合。
11. 第 786 行：结束键构造。
12. 第 787 行：调用 `_report_progress` 把阶段标为 `CACHE_LOOKUP`。
13. 第 788 行：开始构造 Redis 证据键。
14. 第 789 行：键前缀包含会话号，实现会话隔离。
15. 第 790 行：对完整逻辑缓存键做 SHA-256，避免主题文本直接进入 Redis 键并限制键长。
16. 第 791 行：结束 Redis 键构造。
17. 第 792 行：配置缓存时调用 `RedisCache.get_json`；未配置时直接得到 `None`。
18. 第 793 行：Redis 返回列表时优先使用，否则回退到会话持久化的 `rag_evidence_cache`。
19. 第 794 行：判断任一缓存层是否命中。
20. 第 795 行：注释说明不能把持久化列表对象直接交给下游修改。
21. 第 796 行：逐项复制为新字典并返回，实现浅层隔离。
22. 第 797 行：缓存未命中时初始化空检索结果。
23. 第 798 行：只有知识库 ID 非空且配置了 RAG 工具才执行本地知识检索。
24. 第 799 行：上报 `RAG_RETRIEVING` 进度。
25. 第 800 行：进入可选增强层异常保护。
26. 第 801 行：以 `asyncio.wait_for` 限制 RAG 调用时间。
27. 第 802 行：调用项目函数 `RagSearchTool.search_for_question_generation`。
28. 第 803 行：传入主题和显式知识库 ID，禁止跨知识库隐式搜索。
29. 第 804 行：结束搜索调用。
30. 第 805 行：使用 `INTERVIEW_RAG_TIMEOUT_SECONDS` 作为上限。
31. 第 806 行：成功结果保存到 `results`。
32. 第 807 行：捕获 RAG 层全部普通异常，包括 embedding、数据库和超时错误。
33. 第 808-809 行：注释说明证据增强是可选层，不得冻结面试主流程。
34. 第 810 行：开始记录 warning。
35. 第 811 行：日志文本说明将无 RAG 证据继续出题并包含会话号占位符。
36. 第 812 行：传入实际会话号。
37. 第 813 行：通过 `exc_info=error` 保留异常堆栈。
38. 第 814 行：结束日志调用。
39. 第 815 行：失败时把结果重置为空列表。
40. 第 816-817 行：把每个 `RagSearchResult` 转为只含内容、相似度和知识库 ID 的提示词证据，embedding 不进入业务会话。
41. 第 818 行：调用 `_evidence_is_insufficient`；只有本地证据不足且配置网页工具才进入公网补充层。
42. 第 819 行：上报 `WEB_RETRIEVING` 进度。
43. 第 820 行：进入网页增强异常保护。
44. 第 821 行：以 `asyncio.wait_for` 限制网页工具调用。
45. 第 822 行：调用项目函数 `WebEvidenceTool.search_for_question_generation(topic)`。
46. 第 823 行：使用较短的 `INTERVIEW_WEB_TIMEOUT_SECONDS`，避免公网阻塞面试。
47. 第 824 行：成功时得到网页文档列表。
48. 第 825 行：捕获网页层全部普通异常。
49. 第 826-828 行：注释说明公网搜索属于尽力而为的第三层，失败时必须继续主流程。
50. 第 829 行：开始记录 warning。
51. 第 830 行：日志说明使用已有证据继续出题并包含会话号占位符。
52. 第 831 行：传入会话号。
53. 第 832 行：保留网页异常堆栈。
54. 第 833 行：结束日志调用。
55. 第 834 行：失败时使用空文档列表。
56. 第 835 行：把网页文档生成器扩展到证据列表。
57. 第 836-837 行：注释说明提示词只保留有界网页正文，完整 Markdown 仍可由显式知识库导入保存。
58. 第 838 行：正文截取前 12000 字符，限制单文档上下文占用。
59. 第 839 行：网页证据没有向量相似度，显式设为 0。
60. 第 840 行：来源类型标记为 `WEB`。
61. 第 841 行：保存来源 URL。
62. 第 842 行：保存页面标题。
63. 第 843 行：保存抓取时间。
64. 第 844 行：保存内容哈希，支持来源追踪和去重。
65. 第 845 行：结束生成器扩展。
66. 第 846 行：把最终证据写入会话内持久化缓存。
67. 第 847 行：判断 Redis 是否可用。
68. 第 848 行：调用 `RedisCache.set_json` 写跨实例证据缓存，TTL 为 3600 秒。
69. 第 849 行：逐项复制最终证据并返回，防止调用方修改缓存对象。

### 3.24 `_evidence_cache_key` 与 `_evidence_is_insufficient`

`_evidence_cache_key` 文件：`python-agent/app/agents/interview/service.py:851-859`

逐行解释：

1. 第 851 行：声明静态方法。
2. 第 852 行：定义缓存键函数。
3. 第 853 行：强制参数具名传入。
4. 第 854 行：接收阶段枚举。
5. 第 855 行：接收主题。
6. 第 856 行：接收知识库 ID 元组。
7. 第 857 行：声明返回字符串。
8. 第 858 行：压缩主题内部空白并转为大小写无关形式。
9. 第 859 行：以竖线拼接阶段、规范主题和排序后的知识库 ID，使不同输入得到稳定隔离的键。

`_evidence_is_insufficient` 文件：`python-agent/app/agents/interview/service.py:861-865`

逐行解释：

1. 第 861 行：声明静态方法。
2. 第 862 行：定义证据充足性判断函数。
3. 第 863 行：文档字符串规定至少需要两个相关本地片段才跳过网页检索。
4. 第 864 行：筛选 `sourceType` 为 `RAG` 的证据；缺少字段时默认按 RAG 处理。
5. 第 865 行：本地片段少于 2 个，或最高分低于 0.5 时返回真；空集合最高分默认 0。

### 3.25 `RagSearchTool.search_for_question_generation` 与 `RagService.search`

`RagSearchTool.search_for_question_generation` 文件：`python-agent/app/rag/service.py:164-171`

逐行解释：

1. 第 164-166 行：定义内部出题搜索工具，接收查询和可选知识库元组，返回 `RagSearchResult` 列表。
2. 第 167 行：等待底层项目函数 `RagService.search`。
3. 第 168 行：原样传入查询主题。
4. 第 169 行：把用例固定为 `QUESTION_GENERATION`，禁止该工具用于评分。
5. 第 170 行：原样传入显式知识库范围。
6. 第 171 行：返回底层检索结果。

`RagService.search` 文件：`python-agent/app/rag/service.py:78-143`

逐行解释：

1. 第 78-86 行：定义通用异步搜索函数，参数包括查询、用例、知识库范围、可选 topK 和最低分。
2. 第 87 行：检查用例是否在 `RagPolicy.allowed_use_cases` 中。
3. 第 88 行：非法用例抛 `ValueError`。
4. 第 89 行：去除查询首尾空白。
5. 第 90 行：判断规范化查询是否为空。
6. 第 91 行：空查询返回空结果，不调用 embedding。
7. 第 92 行：检查是否显式提供知识库范围。
8. 第 93 行：未提供时抛错，防止默认跨库检索。
9. 第 94 行：按输入顺序去重知识库 ID。
10. 第 95 行：选择调用方 topK 或策略默认值。
11. 第 96 行：选择调用方最低分或策略默认值；显式 0 不会被 `or` 覆盖。
12. 第 97-100 行：用例、排序知识库、规范查询、topK 和最低分共同组成逻辑缓存键。
13. 第 101 行：对逻辑键做 SHA-256 并加 `python:rag:search:` 前缀。
14. 第 102 行：可用时调用 `RedisCache.get_json`。
15. 第 103 行：Redis 值必须是列表才尝试恢复。
16. 第 104 行：进入缓存反序列化保护。
17. 第 105 行：逐项通过 `RagSearchResult.model_validate` 恢复领域结果并直接返回。
18. 第 106 行：捕获缓存结构或值错误。
19. 第 107 行：调用 `RedisCache.delete` 删除损坏缓存，继续回源。
20. 第 108 行：读取当前进程的二级搜索缓存。
21. 第 109-112 行：命中且策略 TTL 为 0（不失效）或尚未过期时有效。
22. 第 113 行：深拷贝缓存结果返回，隔离调用方修改。
23. 第 114 行：调用 embedding provider 的 `embed_query` 生成查询向量。
24. 第 115 行：进入支持/不支持元数据过滤的仓储兼容保护。
25. 第 116 行：调用向量仓储搜索。
26. 第 117-119 行：传入向量、topK、最低分、显式知识库集合，并要求数据库执行元数据过滤。
27. 第 120 行：捕获仓储不支持过滤的 `RagFilterUnsupported`。
28. 第 121 行：再次调用仓储搜索获取扩大候选集。
29. 第 122-127 行：候选数乘回退倍数，保留最低分和知识库参数，但关闭仓储元数据过滤。
30. 第 128-131 行：在应用层筛选知识库 ID，再截取 topK。
31. 第 132-134 行：把当前单调时钟和结果深拷贝写入进程缓存。
32. 第 135 行：当缓存条目超过策略上限时循环清理。
33. 第 136 行：删除插入顺序最旧的一项。
34. 第 137 行：判断是否配置 Redis。
35. 第 138 行：调用 `RedisCache.set_json`。
36. 第 139-140 行：写入去除 chunk.embedding 的 JSON 结果，避免缓存大向量。
37. 第 141 行：TTL 至少为 1 秒；策略值为 0 时使用 600 秒跨实例 TTL。
38. 第 142 行：结束缓存写入。
39. 第 143 行：返回仓储检索结果。

### 3.26 `WebEvidenceTool.search_for_question_generation`

文件：`python-agent/app/tools/web_search.py:63-100`

逐行解释：

1. 第 63 行：定义面向出题的异步网页证据搜索函数。
2. 第 64 行：压缩主题内部空白得到查询。
3. 第 65 行：判断查询是否为空。
4. 第 66 行：空查询直接返回空文档列表。
5. 第 67 行：进入搜索请求与 HTML 解析保护。
6. 第 68 行：创建短生命周期 `httpx.AsyncClient`。
7. 第 69 行：总超时为 20 秒且禁止自动跟随重定向。
8. 第 70 行：设置固定 User-Agent，标识自动证据工具。
9. 第 71 行：进入客户端上下文。
10. 第 72 行：把主题加上 `technical documentation` 后 URL 编码并请求 DuckDuckGo HTML 端点。
11. 第 73 行：非 2xx 响应通过 `raise_for_status` 转为异常。
12. 第 74 行：创建项目 HTML 链接解析器 `_ResultLinkParser`。
13. 第 75 行：把响应文本送入解析器。
14. 第 76 行：结束解析，确保缓冲内容处理完毕。
15. 第 77 行：捕获 HTTP 与 Unicode 错误。
16. 第 78-79 行：注释说明公网搜索失败不能中断面试。
17. 第 80 行：失败返回空列表。
18. 第 82 行：创建选中 URL 列表。
19. 第 83 行：遍历解析到的原始搜索链接。
20. 第 84 行：调用 `_unwrap_search_url` 解出搜索引擎跳转参数中的真实 URL。
21. 第 85 行：调用 `_allowed_technical_url` 校验技术站点白名单，并排除重复 URL。
22. 第 86 行：不合法或重复链接直接继续下一项。
23. 第 87 行：进入公共 URL 安全校验保护。
24. 第 88 行：调用项目函数 `validate_public_url` 执行协议、主机与 SSRF 检查，合法结果才加入列表。
25. 第 89 行：捕获单条 URL 的任意验证异常。
26. 第 90 行：丢弃该链接，继续其他候选。
27. 第 91 行：检查是否已选满 `MAX_SEARCH_RESULTS`，当前为 2。
28. 第 92 行：达到上限即停止扫描。
29. 第 94 行：创建最终文档列表。
30. 第 95 行：遍历经过安全校验的 URL。
31. 第 96 行：进入单页面抓取保护。
32. 第 97 行：调用项目函数 `fetch_public_article` 抓取并解析公共文章，成功文档加入列表。
33. 第 98 行：捕获单页面任意错误。
34. 第 99 行：忽略失败页面，不影响其他页面和面试流程。
35. 第 100 行：返回成功抓取的有界文档列表。

### 3.27 `RedisCache.get_json`、`set_json` 与 `delete`

文件：`python-agent/app/infrastructure/cache/redis_cache.py:31-57`

`get_json` 逐行解释：

1. 第 31 行：定义异步 JSON 读取，返回字典、列表或 `None`。
2. 第 32 行：判断 Redis 客户端是否未配置。
3. 第 33 行：未配置时返回缓存未命中。
4. 第 34 行：进入失败降级保护。
5. 第 35 行：调用 Redis `GET` 读取字符串。
6. 第 36 行：非空字符串通过 `json.loads` 解析；空值返回 `None`。
7. 第 37 行：捕获 Redis 连接/命令错误、损坏 JSON 和类型错误。
8. 第 38 行：记录带键和堆栈的 warning，说明将回退持久化存储。
9. 第 39 行：异常统一表现为未命中，不中断业务。

`set_json` 逐行解释：

1. 第 41 行：定义异步 JSON 写入，返回是否成功。
2. 第 42 行：检查客户端是否存在。
3. 第 43 行：未配置时返回 `False`。
4. 第 44 行：进入写入失败保护。
5. 第 45 行：以紧凑 JSON 序列化值并执行带过期秒数的 Redis `SET`。
6. 第 46 行：写入成功返回 `True`。
7. 第 47 行：捕获 Redis、序列化类型和值错误。
8. 第 48 行：记录 warning，强调持久化数据不受影响。
9. 第 49 行：失败返回 `False`，不向业务层抛出。

`delete` 逐行解释：

1. 第 51 行：定义可删除一个或多个键的异步函数。
2. 第 52 行：无客户端或空键列表时无需操作。
3. 第 53 行：立即返回。
4. 第 54 行：进入删除失败保护。
5. 第 55 行：调用 Redis `DELETE` 删除全部键。
6. 第 56 行：捕获 Redis 错误。
7. 第 57 行：记录 warning 并依赖 TTL 清理残留项，不中断业务。

### 3.28 `InterviewPlanner.create_plan`

文件：`python-agent/app/agents/interview/agent.py:41-144`

该函数只在回答开场自我介绍时通过 `_replan_after_opening` 进入本接口调用链。

逐行解释：

1. 第 41 行：定义异步规划函数，输入补充自我介绍后的 `CandidateProfile`，返回 `InterviewPlan`。
2. 第 42 行：调用 `SkillRegistry.available_for_interview` 读取当前安装且可展示的 Skill。
3. 第 43 行：按 `skill_id` 建立可用 Skill 映射，用于过滤模型返回的未知 ID。
4. 第 44 行：调用 `SkillRegistry.select_for_interview` 做确定性候选预选。
5. 第 45 行：传入目标岗位。
6. 第 46 行：传入 JD 文本。
7. 第 47 行：传入业务面试方向。
8. 第 48 行：结束预选调用。
9. 第 49 行：调用 `StructuredOutputInvoker.invoke` 让模型选择 Skill。
10. 第 50 行：传入共享聊天模型。
11. 第 51 行：要求输出符合 `InterviewSkillSelection`。
12. 第 52 行：调用 `PromptLoader.render("interview/skill-selection.md", {})` 加载选择 Prompt。
13. 第 53 行：开始构造模型输入。
14. 第 54 行：把候选人画像按 JSON 模式序列化。
15. 第 55 行：调用 `SkillRegistry.selection_catalog` 提供安全元数据目录。
16. 第 56 行：把确定性预选结果转换成建议 Skill ID。
17. 第 57 行：明确 `interview-coach` 是必需 Skill。
18. 第 58-59 行：结束输入和结构化调用，得到 `selection`。
19. 第 60-63 行：只保留模型选择中确实存在于 `available_by_id` 的 Skill ID。
20. 第 64 行：检查过滤后是否为空。
21. 第 65 行：模型没有给出有效选择时退回确定性建议列表。
22. 第 66 行：初始化必需 ID 列表为 `interview-coach`。
23. 第 67-69 行：注释说明已知业务方向至少应提供一个 Python 领域 Skill 候选，这是内部安全下限。
24. 第 70-72 行：从建议列表中去掉通用教练，得到领域 Skill ID。
25. 第 73-75 行：若存在领域建议但模型选择未包含任何一个，则触发安全补全。
26. 第 76 行：把第一个确定性领域 Skill 加入必需列表。
27. 第 77 行：按必需项在前合并、去重，并限制最多 4 个 Skill。
28. 第 78 行：调用 `SkillRegistry.resolve_for_interview` 把 ID 解析为完整 Skill 定义。
29. 第 79 行：调用 `PromptLoader.render` 构造规划系统 Prompt。
30. 第 80 行：选择 `interview/planner.md`。
31. 第 81 行：把所有 Skill 指令用双换行连接后注入唯一受控变量。
32. 第 82 行：结束 Prompt 渲染。
33. 第 83 行：开始构造规划输入。
34. 第 84 行：展开候选人画像 JSON 字段。
35. 第 85 行：加入最终受控 Skill ID。
36. 第 86 行：结束输入字典。
37. 第 87 行：调用 `StructuredOutputInvoker.invoke` 生成初版计划。
38. 第 88 行：传入模型、`InterviewPlan` schema 和渲染后的系统 Prompt。
39. 第 89 行：传入规划输入。
40. 第 90 行：得到结构化初版 `result`。
41. 第 91-92 行：注释说明程序会检查三类必考覆盖，仅在有缺口时有限修订。
42. 第 93 行：最多循环 `MAX_PLAN_REVISIONS` 次，当前为 2。
43. 第 94 行：调用 `_missing_coverage(result)` 计算缺失能力。
44. 第 95 行：检查是否没有缺口。
45. 第 96 行：用 Pydantic `model_copy` 生成更新后的不可变风格计划。
46. 第 97 行：调用 `_coverage_matrix(result)` 写入最终覆盖矩阵。
47. 第 98 行：记录实际修订次数。
48. 第 99 行：结束更新字典。
49. 第 100 行：覆盖完整时跳出循环。
50. 第 101 行：有缺口时用 `asyncio.wait_for` 限制单次计划评审。
51. 第 102 行：再次调用结构化输出器。
52. 第 103-104 行：传入模型和 `InterviewPlan` schema。
53. 第 105 行：调用 `PromptLoader.render` 渲染修订 Prompt。
54. 第 106 行：使用 `interview/planner-revision.md`。
55. 第 107 行：注入原系统 Prompt 和用顿号连接的缺口说明。
56. 第 108 行：结束修订 Prompt 渲染。
57. 第 109 行：开始修订输入。
58. 第 110 行：继续展开原规划输入。
59. 第 111 行：加入初版计划 JSON。
60. 第 112 行：加入结构化缺口列表。
61. 第 113-114 行：结束输入和 invoke。
62. 第 115 行：单次评审上限为 45 秒。
63. 第 116 行：修订结果覆盖 `result`，进入下一轮检查。
64. 第 117 行：只有循环没有 `break` 时进入 `for ... else`。
65. 第 118 行：再次计算最终缺口。
66. 第 119 行：仍有缺口时进入失败分支。
67. 第 120 行：构造 `ValueError`。
68. 第 121 行：错误列出两次修订后仍缺失的能力。
69. 第 122 行：结束并抛出错误。
70. 第 123 行：检查任一阶段难度是否与上层期望不一致。
71. 第 124 行：不一致时抛 `ValueError`，禁止模型自行改变难度。
72. 第 125-126 行：注释说明模型阶段题量只是上限，实际题数由运行时动态决定。
73. 第 127 行：创建规范化阶段列表。
74. 第 128 行：遍历模型返回的阶段。
75. 第 129 行：识别开场阶段。
76. 第 130 行：固定为 1 个主问题、0 次追问。
77. 第 131 行：识别总结阶段。
78. 第 132 行：同样固定为 1、0。
79. 第 133 行：识别算法阶段。
80. 第 134 行：固定最多 2 个主问题、0 次追问。
81. 第 135 行：进入其余中间阶段。
82. 第 136-137 行：注释说明三个中间阶段应动态使用 2~4 个主问题并允许追问。
83. 第 138 行：硬设上限为 4 个主问题、每题 2 次追问。
84. 第 139 行：复制当前阶段并以硬限制覆盖模型值，加入新列表。
85. 第 140 行：复制计划并替换规范化阶段。
86. 第 141-142 行：注释说明 Skill 选择是独立决策，规划响应不能注入新 ID。
87. 第 143 行：复制计划并用最终去重 Skill ID 覆盖模型字段。
88. 第 144 行：返回完成覆盖、难度、题量和 Skill 校验的计划。

`_coverage_matrix`（`python-agent/app/agents/interview/agent.py:146-156`）逐行解释：

1. 第 146 行：声明静态方法。
2. 第 147 行：定义覆盖矩阵计算函数。
3. 第 148 行：把计划阶段映射到主题列表。
4. 第 149 行：开始返回布尔矩阵。
5. 第 150 行：项目/实习覆盖取决于 `PROJECT` 阶段是否有主题。
6. 第 151 行：技术栈覆盖取决于 `FUNDAMENTAL` 阶段。
7. 第 152-155 行：知识与实操覆盖要求 `SCENARIO` 或 `CODING` 至少一项存在。
8. 第 156 行：结束矩阵。

`_missing_coverage`（`python-agent/app/agents/interview/agent.py:158-168`）逐行解释：

1. 第 158 行：声明类方法。
2. 第 159 行：定义缺口计算函数。
3. 第 160-164 行：建立内部矩阵键到中文能力说明的映射。
4. 第 165 行：开始列表推导。
5. 第 166 行：调用 `_coverage_matrix`，对每个未覆盖项取中文标签。
6. 第 167 行：只保留布尔值为假的项。
7. 第 168 行：返回缺失能力列表。

### 3.29 `InterviewEvaluationAgent.evaluate`

文件：`python-agent/app/agents/interview/agent.py:186-223`

逐行解释：

1. 第 186-191 行：定义评价节点，输入会话、候选人回答和记忆上下文，返回 `InterviewEvaluation`。
2. 第 192 行：开始构造评价输入。
3. 第 193 行：放入当前阶段。
4. 第 194 行：放入会话难度。
5. 第 195 行：放入当前问题。
6. 第 196 行：注释说明证据来自出题时快照，本节点不调用 RAG。
7. 第 197 行：放入当前问题证据，仅作为事实参考。
8. 第 198 行：放入候选人原始回答。
9. 第 199 行：放入短期最近轮次。
10. 第 200 行：放入会话压缩摘要。
11. 第 201 行：开始长期记忆子对象。
12. 第 202 行：放入历史摘要。
13. 第 203 行：存在当前简历时序列化，否则为 `None`。
14. 第 204 行：放入技术栈。
15. 第 205 行：放入技术深度。
16. 第 206 行：放入偏好。
17. 第 207 行：放入弱项主题。
18. 第 208 行：放入长期笔记。
19. 第 209 行：放入历史问题目录。
20. 第 210-211 行：结束长期记忆和总输入。
21. 第 212-214 行：注释说明评分标准必须稳定，领域 Skill 和检索工具不得注入评价节点。
22. 第 215 行：调用 `SkillRegistry.get("interview-coach")` 读取唯一评分 Skill。
23. 第 216 行：调用 `PromptLoader.render`。
24. 第 217 行：加载 `interview/evaluation.md`。
25. 第 218 行：只注入教练 Skill 指令。
26. 第 219 行：得到评价系统 Prompt。
27. 第 220 行：调用 `StructuredOutputInvoker.invoke` 并等待结果。
28. 第 221 行：传入模型、`InterviewEvaluation` schema 和系统 Prompt。
29. 第 222 行：传入完整评价上下文。
30. 第 223 行：返回通过 JSON 与 Pydantic 校验的评价。

### 3.30 `InterviewRoutingAgent.route`

文件：`python-agent/app/agents/interview/agent.py:242-286`

逐行解释：

1. 第 242-249 行：定义路由节点，输入会话、评价、允许动作、下一阶段名和记忆，返回 `InterviewRoute`。
2. 第 250 行：开始构造路由上下文。
3. 第 251-253 行：放入当前阶段、问题和主题。
4. 第 254 行：把结构化评价序列化为 JSON。
5. 第 255-259 行：放入当前主问题序号、主问题总数、总题数、预算和追问数。
6. 第 260 行：调用计划 `get_stage` 放入当前阶段计划。
7. 第 261 行：排序允许动作，获得稳定 Prompt 输入。
8. 第 262 行：放入下一阶段名。
9. 第 263-264 行：放入阶段与主题计数。
10. 第 265 行：放入历史问题目录，帮助避免重复。
11. 第 266 行：放入最近轮次。
12. 第 267 行：放入会话摘要。
13. 第 268 行：开始候选人上下文。
14. 第 269 行：存在简历时序列化当前简历。
15. 第 270-273 行：放入技术栈、技术深度、偏好和笔记。
16. 第 274 行：结束候选人上下文。
17. 第 275 行：放入弱项主题。
18. 第 276 行：结束总上下文。
19. 第 277 行：按“会话选择、计划选择、默认教练”顺序确定 Skill ID。
20. 第 278 行：调用 `SkillRegistry.resolve_for_interview` 解析并补全教练 Skill。
21. 第 279 行：调用 `PromptLoader.render`。
22. 第 280 行：加载 `interview/routing.md`。
23. 第 281 行：把已解析 Skill 指令连接后注入。
24. 第 282 行：得到路由系统 Prompt。
25. 第 283 行：调用 `StructuredOutputInvoker.invoke`。
26. 第 284 行：传入模型、`InterviewRoute` schema 和 Prompt。
27. 第 285 行：传入路由上下文。
28. 第 286 行：返回通过校验的路由。

### 3.31 `InterviewQuestionAgent.generate`

文件：`python-agent/app/agents/interview/agent.py:300-337`

逐行解释：

1. 第 300-301 行：定义异步出题函数，输入会话、路由、证据和记忆，返回题目字符串。
2. 第 302 行：再次检查路由主题是否为空。
3. 第 303 行：为空时抛 `ValueError`，防止无主题出题。
4. 第 304 行：从会话或计划取得选中 Skill ID。
5. 第 305 行：调用 `SkillRegistry.resolve_for_interview` 解析 Skill。
6. 第 306 行：调用 `PromptLoader.render`。
7. 第 307 行：加载 `interview/question.md` 并注入合并后的 Skill 指令。
8. 第 308 行：得到出题 Prompt。
9. 第 309 行：开始构造输入。
10. 第 310 行：放入当前阶段。
11. 第 311 行：放入难度。
12. 第 312 行：放入已约束主题。
13. 第 313 行：放入已问题录。
14. 第 314 行：放入最近轮次。
15. 第 315 行：放入会话摘要。
16. 第 316 行：开始候选人上下文。
17. 第 317 行：存在当前简历时序列化。
18. 第 318-321 行：放入技术栈、深度、偏好和笔记。
19. 第 322 行：结束候选人上下文。
20. 第 323-325 行：放入阶段计数、主题计数和整场预算。
21. 第 326 行：放入 RAG/网页证据。
22. 第 327 行：开始加入证据安全规则。
23. 第 328 行：声明证据是不可信参考文本，只能提取技术事实。
24. 第 329 行：禁止执行证据内指令、改变系统规则。
25. 第 330 行：禁止因证据内容调用工具。
26. 第 331 行：结束安全规则字符串。
27. 第 332 行：结束出题输入。
28. 第 333 行：调用 `StructuredOutputInvoker.invoke`。
29. 第 334 行：传入模型、`GeneratedQuestion` schema 和 Prompt。
30. 第 335 行：传入出题输入。
31. 第 336 行：得到结构化结果。
32. 第 337 行：只返回其中的 `question` 字符串。

### 3.32 `InterviewSummaryAgent.summarize`

文件：`python-agent/app/agents/interview/agent.py:350-360`

逐行解释：

1. 第 350 行：定义异步总结函数，输入完整会话并返回 `InterviewSummary`。
2. 第 351 行：开始构造总结输入。
3. 第 352 行：放入会话难度。
4. 第 353 行：把最终面试计划序列化为 JSON。
5. 第 354 行：把每个完整轮次序列化，确保总结不只依据最后一轮。
6. 第 355 行：结束输入。
7. 第 356 行：调用 `StructuredOutputInvoker.invoke`。
8. 第 357 行：传入模型和 `InterviewSummary` schema。
9. 第 358 行：调用 `PromptLoader.render("interview/summary.md", {})` 生成总结 Prompt。
10. 第 359 行：传入完整输入。
11. 第 360 行：返回通过校验的结构化总结。

### 3.33 `StructuredOutputInvoker.invoke`

文件：`python-agent/app/infrastructure/reliability/structured_output.py:30-70`

逐行解释：

1. 第 30-37 行：定义通用结构化输出调用器，接收模型、Pydantic schema、业务 Prompt 和映射输入，返回对应 schema 实例。
2. 第 38 行：调用 `PromptLoader.render` 构造统一格式约束 Prompt。
3. 第 39 行：加载 `shared/structured-output.md`。
4. 第 40 行：开始受控变量字典。
5. 第 41 行：调用 schema 的 `model_json_schema(by_alias=True)`，再以中文安全 JSON 序列化。
6. 第 42 行：加入固定 few-shot 输入示例。
7. 第 43 行：调用项目函数 `_few_shot_output(schema)` 取得当前结构的合法最小输出，再序列化。
8. 第 44-45 行：结束变量与格式 Prompt 渲染。
9. 第 46 行：创建消息列表。
10. 第 47 行：把业务 Prompt 与格式约束合并为系统消息。
11. 第 48 行：把业务输入序列化为用户消息；`default=str` 处理枚举、日期等对象。
12. 第 49 行：结束初始消息列表。
13. 第 50 行：存在重试器时读取 `max_output_correction_attempts`，否则不做格式纠错。
14. 第 52 行：从第 0 次开始遍历“初次输出 + 纠错次数”。
15. 第 53 行：调用项目函数 `_invoke_model(model, messages)` 获得原始模型响应。
16. 第 54 行：进入 JSON/模型校验保护。
17. 第 55 行：调用项目函数 `_validate(schema, raw_result)`；成功立即返回模型实例。
18. 第 56 行：捕获 JSON、Pydantic、类型和值校验错误。
19. 第 57 行：调用 `_readable_validation_error` 生成可控长度原因。
20. 第 58 行：判断纠错次数是否已经耗尽。
21. 第 59 行：耗尽时构造 `ModelOutputError`。
22. 第 60 行：错误文本包含实际失败次数和 schema 名。
23. 第 61 行：附加最后一次可读原因。
24. 第 62 行：从原校验错误抛出项目异常。
25. 第 63 行：仍可纠错时扩展消息历史。
26. 第 64 行：调用 `_content_as_text` 把错误输出作为 AI 消息放回上下文。
27. 第 65 行：构造纠错用户消息。
28. 第 66 行：要求只修复完整 JSON，不省略字段。
29. 第 67 行：禁止解释或 Markdown，并附校验原因。
30. 第 68-69 行：结束消息和列表扩展，下一轮重新调用模型。
31. 第 70 行：理论不可达保护；循环必然返回或抛错。

`_invoke_model`（`python-agent/app/infrastructure/reliability/structured_output.py:72-75`）逐行解释：

1. 第 72 行：定义原始模型调用函数。
2. 第 73 行：检查是否没有统一重试器。
3. 第 74 行：无重试器时直接等待模型 `ainvoke`。
4. 第 75 行：有重试器时调用 `AsyncRetryExecutor.execute`，lambda 每次重新发起同一模型请求。

`_validate`（`python-agent/app/infrastructure/reliability/structured_output.py:77-84`）逐行解释：

1. 第 77 行：定义原始响应校验函数。
2. 第 78 行：若模型适配器已经返回目标 schema 实例则无需再次解析。
3. 第 79 行：直接返回该实例。
4. 第 80 行：调用 `_content_as_text` 提取文本。
5. 第 81 行：先调用 `_strip_json_fence` 移除 Markdown 围栏，再用 `json.loads` 解析。
6. 第 82 行：检查根节点是否为字典。
7. 第 83 行：数组、字符串等根节点抛 `TypeError`。
8. 第 84 行：调用 schema 的 `model_validate` 执行字段、枚举和业务验证。

`_content_as_text`（`python-agent/app/infrastructure/reliability/structured_output.py:87-104`）逐行解释：

1. 第 87 行：定义多供应商响应文本提取函数。
2. 第 88 行：原始结果已经是字符串时命中。
3. 第 89 行：直接返回。
4. 第 90 行：优先读取响应的 `content` 属性，不存在则使用原对象。
5. 第 91 行：检查 content 是否为字符串。
6. 第 92 行：直接返回字符串。
7. 第 93 行：检查 content 是否为多块列表。
8. 第 94 行：创建文本片段列表。
9. 第 95 行：遍历内容块。
10. 第 96 行：字符串块直接可用。
11. 第 97 行：追加字符串。
12. 第 98 行：映射块只有 `text` 为字符串时可用。
13. 第 99 行：追加 `text`。
14. 第 100 行：检查是否收集到片段。
15. 第 101 行：无分隔连接并返回。
16. 第 102 行：检查整个 content 是否为映射。
17. 第 103 行：将映射序列化为 JSON 文本。
18. 第 104 行：其余响应形态抛 `TypeError`。

`_strip_json_fence`（`python-agent/app/infrastructure/reliability/structured_output.py:107-112`）逐行解释：

1. 第 107 行：定义 JSON 围栏清理函数。
2. 第 108 行：去除首尾空白。
3. 第 109 行：同时以三反引号开头和结尾时识别为 fenced block。
4. 第 110 行：按行拆分。
5. 第 111 行：去掉首尾围栏行，再连接并清理空白。
6. 第 112 行：返回清理后的文本。

`_readable_validation_error`（`python-agent/app/infrastructure/reliability/structured_output.py:115-120`）逐行解释：

1. 第 115 行：定义错误摘要函数。
2. 第 116 行：识别 Pydantic `ValidationError`。
3. 第 117 行：把每项错误定位路径连接成点号字段名。
4. 第 118 行：最多返回前 8 个失败字段。
5. 第 119 行：其他错误转字符串、去空白并把换行替换为空格。
6. 第 120 行：最多保留 500 字符；空消息时使用异常类名。

`_few_shot_output`（`python-agent/app/infrastructure/reliability/structured_output.py:123-154`）逐行解释：

1. 第 123 行：定义按 schema 返回最小合法示例的函数。
2. 第 124 行：文档字符串说明示例必须对应实际结构。
3. 第 125-149 行：建立各业务 schema 名到合法示例 JSON 的映射；本接口可能使用 `InterviewSkillSelection`、`InterviewPlan`、`InterviewEvaluation`、`InterviewRoute`、`GeneratedQuestion` 和 `InterviewSummary` 六项。
4. 第 150 行：单独识别网页爬取 schema `CrawlPageDecision`。
5. 第 151-153 行：为该结构返回专用示例；该分支不由本接口触发。
6. 第 154 行：按 schema 类名返回示例，未知结构返回空字典。

### 3.34 `AsyncRetryExecutor.execute`

文件：`python-agent/app/infrastructure/reliability/retry.py:23-50`

逐行解释：

1. 第 23 行：定义统一异步重试执行函数，参数是每次调用都能创建新协程的 `operation`。
2. 第 24 行：尝试编号从 1 到策略 `max_attempts`。
3. 第 25 行：进入单次调用保护。
4. 第 26-27 行：注释说明超时适用于所有模型/外部 Agent 调用，且会取消悬挂协程。
5. 第 28 行：通过 `asyncio.wait_for` 等待操作。
6. 第 29 行：调用 `operation()` 并使用策略单次超时。
7. 第 30 行：成功立即返回结果。
8. 第 31 行：捕获单次普通异常。
9. 第 32 行：调用 `_is_retryable`；不可重试或已到最后一次时不再循环。
10. 第 33 行：区分“可重试但次数耗尽”。
11. 第 34 行：构造 `AgentDependencyError`。
12. 第 35 行：错误说明有限重试后依赖仍不可用。
13. 第 36 行：标记为可重试，允许更高层基于 runId 做人工/受控重试。
14. 第 37 行：保留最后一次原异常。
15. 第 38 行：不可重试异常原样抛出。
16. 第 39 行：尚可重试时调用 `_backoff_seconds(attempt)` 并异步休眠。
17. 第 40 行：理论不可达保护。
18. 第 42 行：定义可重试判断函数。
19. 第 43 行：以异常类名是否在策略集合中判断，避免基础类过度扩大重试范围。
20. 第 45 行：定义指数退避计算函数。
21. 第 46 行：开始取最大退避与指数值中的较小值。
22. 第 47 行：提供策略最大毫秒数。
23. 第 48 行：初始毫秒数乘以 `2 ** (attempt - 1)`。
24. 第 49 行：结束最小值计算。
25. 第 50 行：毫秒除以 1000 转成 `asyncio.sleep` 所需秒数。

### 3.35 `PromptLoader.render`、`load` 与 `_resolve`

文件：`python-agent/app/common/prompt_loader.py:19-46`

`render` 逐行解释：

1. 第 26 行：定义受控变量渲染函数。
2. 第 27 行：调用项目函数 `load(prompt_id)` 读取模板。
3. 第 29 行：定义只在本次渲染闭包内使用的 `replace`。
4. 第 30 行：从正则匹配取得占位符变量名。
5. 第 31 行：检查调用方变量字典是否包含该键。
6. 第 32 行：缺失时构造 `PromptConfigurationError`。
7. 第 33 行：错误包含 Prompt ID 与缺失键。
8. 第 34 行：结束并抛错。
9. 第 35 行：存在变量时转为字符串返回。
10. 第 37 行：用预编译占位符正则替换模板全部匹配。
11. 第 38 行：再次搜索渲染结果，防止变量值或异常模板留下占位符。
12. 第 39 行：残留时抛 `PromptConfigurationError`。
13. 第 40 行：返回完全渲染的 Prompt。

`load` 逐行解释：

1. 第 19 行：定义 Prompt 文件加载函数。
2. 第 20 行：调用项目函数 `_resolve(prompt_id)` 得到经过目录边界校验的路径。
3. 第 21 行：进入文件读取保护。
4. 第 22 行：以 UTF-8 读取完整文本。
5. 第 23 行：捕获文件不存在。
6. 第 24 行：转换为包含 Prompt ID 的 `PromptConfigurationError`。

`_resolve` 逐行解释：

1. 第 42 行：定义路径解析函数。
2. 第 43 行：把根目录与 Prompt ID 拼接并解析绝对路径。
3. 第 44 行：检查 Prompt 根目录是否仍是目标路径的父目录。
4. 第 45 行：越界时抛配置错误，阻止 `../` 目录穿越。
5. 第 46 行：返回安全路径。

### 3.36 `SkillRegistry` 调用链

文件：`python-agent/app/tools/skills/loader.py`

`get`（第 47-84 行）逐行解释：

1. 第 47 行：定义按 ID 加载 Skill 的函数。
2. 第 48-50 行：要求 ID 是字符串并完全匹配小写字母数字加单连字符格式。
3. 第 51 行：格式不合法时抛 `SkillConfigurationError`。
4. 第 52 行：构造 Skill 目录。
5. 第 53 行：构造 `skill.json` 路径。
6. 第 54 行：构造 `SKILL.md` 路径。
7. 第 55 行：进入文件读取和 JSON 解析保护。
8. 第 56 行：读取并解析元数据。
9. 第 57 行：读取完整指令文本。
10. 第 58 行：捕获任一文件不存在。
11. 第 59 行：抛包含 Skill ID 的配置错误。
12. 第 60 行：捕获元数据 JSON 损坏。
13. 第 61 行：转换为格式错误。
14. 第 63 行：检查 `enabled`，缺失时默认启用。
15. 第 64 行：禁用 Skill 抛错。
16. 第 65 行：检查元数据内部 ID 与目录 ID 一致。
17. 第 66 行：不一致抛错。
18. 第 67 行：读取 `allowedTools`，缺失时为空列表。
19. 第 68-70 行：要求其为数组且每项是非空字符串。
20. 第 71 行：格式错误时抛错。
21. 第 72 行：计算声明工具与运行时支持工具集合的差集。
22. 第 73 行：检查是否有未实现工具。
23. 第 74 行：排序并连接未支持工具名。
24. 第 75-77 行：抛包含 Skill 与工具列表的配置错误。
25. 第 78 行：构造不可变 `SkillDefinition`。
26. 第 79-82 行：复制 ID、名称、描述和指令。
27. 第 83 行：按顺序去重工具并转元组。
28. 第 84 行：返回定义。

`resolve_for_interview`（第 86-102 行）逐行解释：

1. 第 86 行：定义持久化/模型 Skill ID 解析函数。
2. 第 87 行：文档说明陈旧 ID 不应终止面试。
3. 第 88 行：创建解析结果列表。
4. 第 89 行：创建去重集合。
5. 第 90 行：遍历输入 ID。
6. 第 91 行：跳过非字符串、空白或重复项。
7. 第 92 行：继续下一项。
8. 第 93 行：进入单 Skill 配置保护。
9. 第 94 行：调用 `get(skill_id)` 并追加。
10. 第 95 行：记录已解析 ID。
11. 第 96 行：捕获单个 Skill 配置错误。
12. 第 97 行：记录 warning 并忽略陈旧项。
13. 第 98 行：检查最终是否一个都没解析。
14. 第 99 行：回退调用 `get("interview-coach")`。
15. 第 100 行：有其他 Skill 但缺少教练时进入补全。
16. 第 101 行：在首位插入教练 Skill。
17. 第 102 行：返回不可变元组。

`available_for_interview`（第 104-116 行）逐行解释：

1. 第 104 行：定义可用 Skill 枚举函数。
2. 第 105 行：文档说明结果必须同时已展示且已安装。
3. 第 106 行：以教练 Skill 开头构造 ID 列表。
4. 第 107 行：调用 `public_catalog` 并提取每项 ID。
5. 第 108 行：结束列表构造。
6. 第 109 行：创建结果列表。
7. 第 110 行：创建去重集合。
8. 第 111 行：遍历 ID。
9. 第 112 行：跳过重复 ID。
10. 第 113 行：继续下一项。
11. 第 114 行：调用 `get` 验证并加载 Skill。
12. 第 115 行：记录 ID。
13. 第 116 行：返回元组。

`selection_catalog`（第 118-128 行）逐行解释：

1. 第 118 行：定义给规划模型的安全目录函数。
2. 第 119 行：文档说明仅暴露元数据。
3. 第 120 行：开始列表推导。
4. 第 121 行：开始单项字典。
5. 第 122-124 行：放入 ID、名称和描述。
6. 第 125 行：把允许工具元组转为列表。
7. 第 126 行：结束字典。
8. 第 127 行：数据源为 `available_for_interview()`，因此每项均已验证安装。
9. 第 128 行：返回目录；内部 `SKILL.md` 指令不会进入此字段。

`select_for_interview`（第 130-158 行）逐行解释：

1. 第 130-132 行：定义确定性候选选择函数，输入岗位、JD 和可选方向。
2. 第 133 行：文档说明上层只传业务上下文，具体 Skill 在 Python 内选择。
3. 第 134 行：把岗位与 JD 合并并转小写。
4. 第 135 行：默认选择教练 Skill。
5. 第 136-137 行：注释说明面试方向只用于准备候选，最终允许列表由规划模型选择。
6. 第 138 行：按方向映射遍历候选 Skill ID。
7. 第 139 行：只处理本地目录确实存在的 Skill。
8. 第 140 行：调用 `get` 并追加。
9. 第 141-142 行：注释说明关键词选择也必须与镜像内文件对齐。
10. 第 143-152 行：定义领域 Skill 到岗位/JD 关键词的映射。
11. 第 153 行：遍历领域映射。
12. 第 154 行：构造 Skill 目录。
13. 第 155 行：目录存在且任一关键词命中时满足选择。
14. 第 156 行：调用 `get` 并追加。
15. 第 157 行：按 Skill ID 建字典去重，后出现项覆盖同 ID 但顺序保持首次插入位置。
16. 第 158 行：返回定义元组。

`public_catalog`（第 160-178 行）逐行解释：

1. 第 160 行：定义展示目录读取函数。
2. 第 161 行：文档说明不暴露内部指令。
3. 第 162 行：构造 `catalog.json` 路径。
4. 第 163 行：进入读取保护。
5. 第 164 行：读取并解析 JSON。
6. 第 165 行：捕获文件不存在。
7. 第 166 行：抛展示目录不存在错误。
8. 第 167 行：捕获 JSON 损坏。
9. 第 168 行：抛格式错误。
10. 第 169 行：检查根节点是否列表。
11. 第 170 行：非数组时抛错。
12. 第 171 行：逐项调用 `_validate_public_item` 校验。
13. 第 172 行：提取全部目录 ID。
14. 第 173 行：比较列表长度和集合长度检测重复。
15. 第 174 行：重复时抛错。
16. 第 175 行：遍历已校验展示项。
17. 第 176 行：注释规定 API 不得宣传无法加载的 Skill。
18. 第 177 行：调用 `get` 验证对应文件确实存在且有效。
19. 第 178 行：返回已校验目录。

### 3.37 `MemoryService.build_context`

文件：`python-agent/app/memory/service.py:99-120`

逐行解释：

1. 第 99 行：定义上下文构建函数，输入当前面试会话，返回 `MemoryContext`。
2. 第 100 行：调用 `PostgresLongTermMemoryRepository.get(session.user_id)` 读取用户长期记忆。
3. 第 101 行：判断用户是否尚无长期记忆记录。
4. 第 102 行：调用项目函数 `MemoryContext.empty(session)` 构造空长期记忆视图，再用 Pydantic `model_copy` 更新短期字段。
5. 第 103 行：开始更新字典。
6. 第 104 行：按策略 `short_term_turn_limit` 取会话最近若干完整轮次。
7. 第 105 行：读取会话压缩摘要；兼容旧会话缺少字段时使用空字符串。
8. 第 106-107 行：结束更新并返回无长期记录的上下文。
9. 第 108 行：长期记忆存在时，在简历快照中查找与当前会话 `resume_id` 相同的活动快照，找不到则为 `None`。
10. 第 109 行：开始构造完整 `MemoryContext`。
11. 第 110 行：放入有界最近轮次。
12. 第 111 行：放入当前会话压缩摘要。
13. 第 112 行：放入跨会话历史摘要。
14. 第 113 行：放入当前简历快照。
15. 第 114 行：放入技术栈。
16. 第 115 行：放入技术深度。
17. 第 116 行：放入偏好。
18. 第 117 行：放入弱项主题。
19. 第 118 行：放入长期笔记。
20. 第 119 行：放入历史问题目录。
21. 第 120 行：结束并返回上下文。

`MemoryContext.empty` 文件：`python-agent/app/memory/models.py:81-94`

逐行解释：

1. 第 81 行：声明类方法。
2. 第 82 行：定义空上下文工厂，输入会话。
3. 第 83 行：以当前类 `cls` 开始构造对象。
4. 第 84 行：最近轮次初始为空。
5. 第 85 行：仍保留会话已有压缩摘要。
6. 第 86 行：跨会话历史摘要为空。
7. 第 87 行：活动简历为空。
8. 第 88-92 行：技术栈、深度、偏好、弱项和笔记均为空列表。
9. 第 93 行：问题目录为空。
10. 第 94 行：结束并返回对象。

### 3.38 `MemoryService.record_turn`

文件：`python-agent/app/memory/service.py:122-144`

逐行解释：

1. 第 122 行：定义本轮长期记忆写入函数，返回更新后记忆或 `None`。
2. 第 123 行：调用长期记忆仓储 `get(user_id)`。
3. 第 124 行：检查用户记忆是否不存在。
4. 第 125 行：不存在时返回 `None`；面试会话已成功保存，不因可选长期记忆缺失失败。
5. 第 126 行：检查 `turn_id` 是否已在 `recorded_turn_ids`。
6. 第 127 行：已经记录时返回当前记忆，实现幂等。
7. 第 128 行：保存当前记忆版本作为乐观锁期望值。
8. 第 129 行：优先使用轮次主题，缺失时用阶段枚举值。
9. 第 130 行：构造包含 session、阶段、主题、评分和摘要的单行事件。
10. 第 131 行：调用 `_append_summary` 把事件追加到有界历史摘要。
11. 第 132 行：调用 `_merge_items` 合并问题目录，最多 100 项。
12. 第 133 行：合并弱项，最多 30 项。
13. 第 134 行：把本轮优点合并进长期 notes，最多 30 项。
14. 第 135 行：合并偏好，最多 30 项。
15. 第 136 行：合并 turnId 幂等目录，最多 500 项。
16. 第 137 行：更新 UTC 时间。
17. 第 138 行：进入乐观保存冲突补偿保护。
18. 第 139 行：调用 `PostgresLongTermMemoryRepository.save(memory, expected_version)`。
19. 第 140 行：捕获版本冲突 `ConsistencyError`。
20. 第 141 行：重新读取最新长期记忆。
21. 第 142 行：若其他并发请求已经写入同一 turnId，则认为目标已完成。
22. 第 143 行：返回最新记忆。
23. 第 144 行：否则原样抛出冲突，避免覆盖其他更新。

### 3.39 `MemoryService.finalize_session`

文件：`python-agent/app/memory/service.py:146-175`

逐行解释：

1. 第 146 行：定义会话归档函数，输入会话和中断标志。
2. 第 147 行：从长期记忆仓储读取用户记录。
3. 第 148 行：检查记录是否不存在。
4. 第 149 行：不存在时返回 `None`。
5. 第 150 行：检查 sessionId 是否已在归档目录。
6. 第 151 行：已归档时返回当前记忆，实现幂等。
7. 第 152 行：保存当前版本作为乐观锁期望值。
8. 第 153 行：提取全部轮次分数。
9. 第 154 行：有分数时计算四舍五入平均值，否则为 0。
10. 第 155 行：展平全部轮次弱项。
11. 第 156 行：展平全部轮次优点。
12. 第 157 行：开始构造归档摘要。
13. 第 158 行：写入 sessionId 和 completed/interrupted 状态。
14. 第 159 行：写入轮次数和平均分。
15. 第 160 行：写入最终总结；缺失时使用固定英文占位文本。
16. 第 161 行：结束摘要。
17. 第 162 行：调用 `_append_summary` 追加到历史摘要。
18. 第 163 行：调用 `_merge_items` 合并面试总结，最多 20 项。
19. 第 164 行：合并会话全部问题，最多 100 项。
20. 第 165 行：合并全部弱项，最多 30 项。
21. 第 166 行：合并全部优点到 notes，最多 30 项。
22. 第 167 行：合并 sessionId 归档目录，最多 100 项。
23. 第 168 行：更新 UTC 时间。
24. 第 169 行：进入乐观保存保护。
25. 第 170 行：调用长期记忆仓储 `save`。
26. 第 171 行：捕获并发冲突。
27. 第 172 行：重新读取最新记忆。
28. 第 173 行：若其他请求已经归档同一 sessionId，则目标已完成。
29. 第 174 行：返回最新记忆。
30. 第 175 行：否则重新抛出冲突。

`_append_summary`（`python-agent/app/memory/service.py:254-255`）逐行解释：

1. 第 254 行：定义摘要追加函数。
2. 第 255 行：以换行拼接旧摘要和新事件，去首尾空白，再只保留策略允许的最后若干字符。

`_merge_items`（`python-agent/app/memory/service.py:269-272`）逐行解释：

1. 第 269 行：声明静态方法。
2. 第 270 行：定义有界字符串列表合并函数。
3. 第 271 行：合并旧、新列表，过滤空值并去除每项首尾空白。
4. 第 272 行：用字典键按首次出现去重，再保留最后 `limit` 项，使新信息优先留存。

### 3.40 `PostgresLongTermMemoryRepository.get` 与 `save`

文件：`python-agent/app/infrastructure/persistence/long_term_memory_repository.py:31-76`

`get` 逐行解释：

1. 第 31 行：定义按 userId 读取长期记忆的异步函数。
2. 第 32 行：从异步会话工厂创建数据库会话并进入上下文。
3. 第 33 行：执行只返回单标量的查询。
4. 第 34 行：构造 `LongTermMemoryEntity` 查询。
5. 第 35 行：WHERE 条件是主键 userId。
6. 第 36-37 行：结束查询和数据库上下文。
7. 第 38 行：实体存在时用 `LongTermMemory.model_validate(memory_data)` 恢复领域对象，否则返回 `None`。

`save` 逐行解释：

1. 第 49-51 行：定义带 `expected_version` 的乐观保存函数。
2. 第 52 行：用 Pydantic `model_copy` 构造保存版本。
3. 第 53 行：开始更新字段。
4. 第 54 行：状态版本加一。
5. 第 55 行：更新时间取 UTC 当前值。
6. 第 56-57 行：结束复制得到 `saved`。
7. 第 58 行：开始构造 SQL UPDATE。
8. 第 59 行：目标表为长期记忆实体。
9. 第 60 行：开始 WHERE 条件。
10. 第 61 行：主键必须等于目标用户。
11. 第 62 行：数据库版本必须等于调用方读取版本。
12. 第 63 行：结束条件。
13. 第 64 行：开始更新值。
14. 第 65 行：写入新版本。
15. 第 66 行：把完整记忆序列化进 JSON 字段。
16. 第 67 行：写入更新时间。
17. 第 68-69 行：结束值和 SQL 语句。
18. 第 70 行：打开数据库会话。
19. 第 71 行：执行 UPDATE。
20. 第 72 行：检查受影响行数是否不是 1。
21. 第 73 行：冲突或记录缺失时回滚。
22. 第 74 行：抛 `ConsistencyError`。
23. 第 75 行：唯一行更新成功时提交事务。
24. 第 76 行：返回版本已增加的领域对象。

### 3.41 `PostgresInterviewSessionRepository.get`

文件：`python-agent/app/infrastructure/persistence/interview_session_repository.py:49-65`

逐行解释：

1. 第 49 行：定义按 sessionId 读取面试会话的异步函数。
2. 第 50 行：配置 Redis 时调用 `get_json(_cache_key(session_id))`，否则视为未命中。
3. 第 51 行：只有缓存值为字典才尝试恢复会话。
4. 第 52 行：进入缓存模型校验保护。
5. 第 53 行：用 `InterviewSession.model_validate` 恢复并直接返回。
6. 第 54 行：捕获缓存字段或枚举不符合当前模型的 `ValueError`。
7. 第 55 行：调用 `RedisCache.delete` 删除损坏缓存，再继续数据库回源。
8. 第 56 行：创建数据库会话。
9. 第 57 行：执行单标量 SELECT。
10. 第 58 行：查询 `InterviewSessionEntity`。
11. 第 59 行：WHERE 条件为 sessionId。
12. 第 60-61 行：结束查询。
13. 第 62 行：实体存在时调用项目函数 `_from_entity(entity)`，否则为 `None`。
14. 第 63 行：检查回源是否得到会话。
15. 第 64 行：调用 `_cache_session(session)` 回填 Redis。
16. 第 65 行：返回会话或 `None`。

`_cache_key`（`python-agent/app/infrastructure/persistence/interview_session_repository.py:105-107`）逐行解释：

1. 第 105 行：声明静态方法。
2. 第 106 行：定义缓存键函数。
3. 第 107 行：返回 `python:interview-session:{session_id}`，与 Java Redis 命名空间隔离。

`_cache_session`（`python-agent/app/infrastructure/persistence/interview_session_repository.py:109-113`）逐行解释：

1. 第 109 行：定义异步会话缓存写入函数。
2. 第 110 行：检查缓存包装器是否存在。
3. 第 111 行：调用 `RedisCache.set_json`。
4. 第 112 行：使用会话缓存键、完整 JSON 会话和 7200 秒 TTL。
5. 第 113 行：结束写入；RedisCache 自身会吞掉缓存故障。

`_from_entity`（`python-agent/app/infrastructure/persistence/interview_session_repository.py:128-130`）逐行解释：

1. 第 128 行：声明静态方法。
2. 第 129 行：定义实体到领域模型转换函数。
3. 第 130 行：用 `InterviewSession.model_validate(entity.session_data)` 校验并恢复完整会话。

### 3.42 `PostgresInterviewSessionRepository.save`

文件：`python-agent/app/infrastructure/persistence/interview_session_repository.py:67-103`

逐行解释：

1. 第 67-69 行：定义带预期版本的异步会话保存函数。
2. 第 70 行：计算下一版本。
3. 第 71 行：复制领域会话。
4. 第 72 行：开始更新字典。
5. 第 73 行：写入下一版本。
6. 第 74 行：更新时间取 UTC 当前值。
7. 第 75-76 行：结束复制得到 `saved_session`。
8. 第 77 行：把保存会话序列化为 JSON payload。
9. 第 78 行：开始构造 UPDATE。
10. 第 79 行：目标表为 `InterviewSessionEntity`。
11. 第 80 行：开始 WHERE 条件。
12. 第 81 行：sessionId 必须相同。
13. 第 82 行：数据库版本必须仍等于 `expected_version`。
14. 第 83 行：结束条件。
15. 第 84 行：开始更新值。
16. 第 85 行：更新可索引状态列。
17. 第 86 行：更新可索引当前阶段列。
18. 第 87 行：更新版本列。
19. 第 88 行：更新完整会话 JSON。
20. 第 89 行：更新时间。
21. 第 90-91 行：结束值和语句。
22. 第 92 行：打开数据库会话。
23. 第 93 行：执行 UPDATE。
24. 第 94 行：受影响行数不是 1 时表示版本冲突或会话不存在。
25. 第 95 行：回滚事务。
26. 第 96 行：检查是否配置缓存。
27. 第 97 行：调用 `RedisCache.delete(_cache_key(session_id))` 删除可能陈旧的会话缓存。
28. 第 98 行：抛 `ConsistencyError`，禁止覆盖并发新版本。
29. 第 99 行：唯一行更新成功时提交事务。
30. 第 100-101 行：注释强调 PostgreSQL 先提交，缓存故障不能制造虚假版本。
31. 第 102 行：调用 `_cache_session(saved_session)` 尽力刷新缓存。
32. 第 103 行：返回版本已增加的保存会话。

### 3.43 `_candidate_visible_output`

文件：`python-agent/app/agents/interview/service.py:876-900`

逐行解释：

1. 第 876 行：声明静态方法。
2. 第 877-879 行：定义服务层候选人输出函数，输入最终会话和本轮记录，返回字典。
3. 第 880 行：文档字符串说明只返回候选人应见信息。
4. 第 882-883 行：说明内部记忆、RAG 证据和路由理由留在下层，上层只拿紧凑评估与计数。
5. 第 885 行：开始构造输出字典。
6. 第 886 行：放入本轮评价摘要。
7. 第 887 行：放入本轮分数。
8. 第 888 行：放入本轮优点。
9. 第 889 行：放入本轮弱项。
10. 第 890 行：放入当前阶段主问题序号。
11. 第 891 行：放入整场主问题总数。
12. 第 892 行：放入当前主问题追问数。
13. 第 893 行：放入整场总题数。
14. 第 894 行：放入目标题量预算。
15. 第 895 行：结束基础输出。
16. 第 896 行：检查是否已有模型或兜底最终评价。
17. 第 897 行：存在时按字段别名序列化为 `finalEvaluation`。
18. 第 898 行：没有最终评价但会话已完成时进入额外兜底。
19. 第 899 行：调用 `_fallback_evaluation(session)` 并序列化，保证完成响应始终包含最终报告。
20. 第 900 行：返回候选人输出。

### 3.44 `_fallback_summary` 与 `_fallback_evaluation`

`_fallback_summary` 文件：`python-agent/app/agents/interview/service.py:902-907`

逐行解释：

1. 第 902 行：声明静态方法。
2. 第 903 行：定义确定性摘要函数，输入会话和中断标志。
3. 第 904 行：计算已保存轮次数。
4. 第 905 行：检查是否中断。
5. 第 906 行：中断时返回说明记录已保存、可恢复继续的文本。
6. 第 907 行：正常完成时返回完成轮次数文本。

`_fallback_evaluation` 文件：`python-agent/app/agents/interview/service.py:909-931`

逐行解释：

1. 第 909 行：声明静态方法。
2. 第 910 行：定义结构化兜底评价函数。
3. 第 911 行：文档字符串说明模型总结不可用时仍需生成报告。
4. 第 912 行：函数内导入 `InterviewSummary`，避免模块级循环依赖。
5. 第 914 行：检查会话是否没有轮次。
6. 第 915 行：开始返回空作答报告。
7. 第 916 行：总分为 0，摘要说明没有有效作答。
8. 第 917 行：优点为空，弱点与建议使用确定性文本。
9. 第 918 行：结束空报告返回。
10. 第 919 行：有轮次时计算平均分并四舍五入。
11. 第 920 行：按轮次顺序展平优点，只取前 5 项。
12. 第 921 行：展平弱项，只取前 5 项。
13. 第 922 行：检查优点是否为空。
14. 第 923 行：为空时加入完成主要问答的通用优点。
15. 第 924 行：检查弱项是否为空。
16. 第 925 行：为空时加入补充原理与落地细节的通用弱项。
17. 第 926 行：开始构造 `InterviewSummary`。
18. 第 927 行：写入平均总分。
19. 第 928 行：摘要包含轮次数与平均分。
20. 第 929 行：写入最终优点。
21. 第 930 行：写入最终弱项。
22. 第 931 行：写入固定改进建议并结束返回。

### 3.45 `_success_response`

文件：`python-agent/app/api/application.py:360-376`

逐行解释：

1. 第 360-366 行：定义统一成功响应构造函数；协议字段、会话必填，答案、输出、版本、状态和阶段可选。
2. 第 367 行：开始构造 `AgentResponse`。
3. 第 368 行：复制 `apiVersion`、`requestId` 和 `runId`。
4. 第 369 行：成功业务码固定为 100、运行状态固定为 `COMPLETED`，用户 ID 来自持久化会话而非请求。
5. 第 370 行：会话 ID 来自会话；调用方显式状态优先，否则用会话状态。
6. 第 371 行：显式版本即使为 0 也优先，否则读取会话版本。
7. 第 372 行：显式答案非 `None` 时优先，否则读取会话当前问题；空字符串属于有效显式答案。
8. 第 373 行：写入本轮所属阶段。
9. 第 374 行：显式当前阶段优先，否则兼容读取会话字段。
10. 第 375 行：写入候选人白名单输出，并明确错误为空。
11. 第 376 行：结束并返回通过 Pydantic 校验的响应。

### 3.46 FastAPI 异常入口与 `_mark_failed_interview_progress`

`request_validation_error` 文件：`python-agent/app/api/application.py:292-299`

逐行解释：

1. 第 292 行：为 `RequestValidationError` 注册专用处理器；该异常可能在 `respond` 函数执行前产生。
2. 第 293 行：定义异步处理函数。
3. 第 294 行：读取 FastAPI 保存的原始请求体。
4. 第 295 行：请求体是映射时作为错误上下文，否则为 `None`。
5. 第 296 行：调用 `_error_json_response`。
6. 第 297 行：把框架校验错误转换为项目 `RequestError`，HTTP 状态设为 400。
7. 第 298 行：传入可恢复的请求上下文。
8. 第 299 行：返回统一 JSON 错误响应。

`application_error` 文件：`python-agent/app/api/application.py:301-304`

逐行解释：

1. 第 301 行：为所有 `ApplicationException` 注册处理器。
2. 第 302 行：定义异步处理函数。
3. 第 303 行：调用 `_mark_failed_interview_progress(request)`，仅对 respond 路径标记进度失败。
4. 第 304 行：调用 `_error_json_response`；业务错误按现有跨服务协议使用 HTTP 200，真实错误类别在响应 code/error 中表达。

`unexpected_error` 文件：`python-agent/app/api/application.py:306-310`

逐行解释：

1. 第 306 行：为未被项目异常覆盖的 `Exception` 注册兜底处理器。
2. 第 307 行：定义异步处理函数。
3. 第 308 行：记录包含堆栈的未处理错误日志。
4. 第 309 行：调用 `_mark_failed_interview_progress`。
5. 第 310 行：调用 `_error_json_response` 并使用 HTTP 500。

`_mark_failed_interview_progress` 文件：`python-agent/app/api/application.py:323-331`

逐行解释：

1. 第 323 行：定义异步失败进度补偿函数。
2. 第 324 行：检查请求路径是否不是 `/v1/agent/respond`。
3. 第 325 行：其他接口立即返回，避免误标会话进度。
4. 第 326 行：调用 `_request_context` 恢复请求字段。
5. 第 327 行：调用 `_string_or_none` 读取合法 sessionId。
6. 第 328 行：直接读取应用状态中的面试服务；异常路径不再冷启动新服务。
7. 第 329 行：兼容性读取 `mark_progress_failed`。
8. 第 330 行：只有 sessionId 非空且属性可调用时才补偿。
9. 第 331 行：调用 `mark_progress_failed(session_id)`。

### 3.47 统一错误响应辅助函数

`_request_context` 文件：`python-agent/app/api/application.py:379-388`

逐行解释：

1. 第 379 行：定义异步请求上下文恢复函数。
2. 第 380 行：优先读取路由开始时记住的 `request.state.agent_context`。
3. 第 381 行：检查其是否实现映射接口。
4. 第 382 行：已记住时直接返回，不重复读取 body。
5. 第 383 行：进入原始请求体回退解析保护。
6. 第 384 行：异步读取 body 字节。
7. 第 385 行：非空时用 `json.loads` 解析，否则使用空字典。
8. 第 386 行：只有根节点为字典时返回，否则返回空字典。
9. 第 387 行：捕获 JSON、UTF-8 和请求体读取运行时错误。
10. 第 388 行：解析失败返回空上下文，错误处理器自身不再失败。

`_error_response` 文件：`python-agent/app/api/application.py:397-411`

逐行解释：

1. 第 397-399 行：定义把异常和可选上下文转换为 `AgentResponse` 的异步函数。
2. 第 400 行：显式上下文优先，否则调用 `_request_context`。
3. 第 401 行：调用 `_session_status_or_failed` 转换请求中的 sessionStatus。
4. 第 402 行：读取 stateVersion 原始值。
5. 第 403 行：开始构造失败响应。
6. 第 404 行：调用 `_string_or_none` 转换 apiVersion。
7. 第 405 行：转换 requestId。
8. 第 406 行：转换 runId，并调用 `ExceptionHandler.to_code(error)` 得到业务码。
9. 第 407 行：运行状态固定为 `FAILED`，并转换 userId。
10. 第 408 行：转换 sessionId，写入保守会话状态。
11. 第 409 行：stateVersion 必须是非负整数，否则回退 0。
12. 第 410 行：答案为空、当前阶段标为 `FAILED`，并调用 `ExceptionHandler.to_error_info(error)` 构造错误详情。
13. 第 411 行：结束并返回响应。

`_string_or_none` 文件：`python-agent/app/api/application.py:414-415`

1. 第 414 行：定义字符串清洗函数。
2. 第 415 行：值必须是字符串且去空白后非空才原样返回，否则返回 `None`。

`_session_status_or_failed` 文件：`python-agent/app/api/application.py:418-423`

逐行解释：

1. 第 418 行：定义请求会话状态转换函数。
2. 第 419 行：文档说明一次运行失败不能错误地把既有面试会话改成失败。
3. 第 420 行：进入枚举转换保护。
4. 第 421 行：尝试构造 `SessionStatus(value)`；合法请求状态原样保留。
5. 第 422 行：捕获类型和值错误。
6. 第 423 行：无法恢复时才使用 `SessionStatus.FAILED`。

`ExceptionHandler.to_code` 文件：`python-agent/app/common/exceptions.py:139-146`

逐行解释：

1. 第 139 行：声明类方法。
2. 第 140 行：定义异常到业务码转换。
3. 第 141 行：识别项目 `ApplicationException`。
4. 第 142 行：返回异常类声明的 code。
5. 第 143 行：遍历内置异常映射。
6. 第 144 行：按 `isinstance` 匹配。
7. 第 145 行：返回映射 code。
8. 第 146 行：未知异常返回 500。

`ExceptionHandler.to_error_info` 文件：`python-agent/app/common/exceptions.py:116-137`

逐行解释：

1. 第 116 行：声明类方法。
2. 第 117 行：定义异常到协议 `ErrorInfo` 的转换。
3. 第 118 行：识别项目异常。
4. 第 119 行：开始构造错误信息。
5. 第 120 行：类型取异常类声明的 `error_type`。
6. 第 121 行：消息取项目异常 message。
7. 第 122 行：复制 retryable。
8. 第 123 行：返回项目错误信息。
9. 第 125 行：遍历内置异常映射。
10. 第 126 行：按类型匹配。
11. 第 127 行：开始构造映射错误。
12. 第 128 行：写入映射名称。
13. 第 129 行：优先使用异常字符串，空字符串时用错误名。
14. 第 130 行：写入 retryable。
15. 第 131 行：返回映射错误。
16. 第 133 行：未知异常开始构造内部错误。
17. 第 134 行：类型固定为 `INTERNAL_ERROR`。
18. 第 135 行：对外使用固定消息，不泄漏内部堆栈。
19. 第 136 行：标为不可重试。
20. 第 137 行：返回内部错误信息。

`_error_json_response` 文件：`python-agent/app/api/application.py:447-455`

逐行解释：

1. 第 447-453 行：定义最终 JSON 包装函数，接收请求、异常、HTTP 状态和可选上下文。
2. 第 454 行：调用 `_error_response` 构造协议对象。
3. 第 455 行：调用项目函数 `AgentResponse.to_json_dict()`，再构造指定 HTTP 状态的 FastAPI `JSONResponse`。

`AgentResponse.to_json_dict` 文件：`python-agent/app/common/contracts.py:184-185`

1. 第 184 行：定义协议对象转 JSON 字典函数。
2. 第 185 行：调用 Pydantic `model_dump(mode="json", by_alias=True, exclude_none=False)`，确保日期/枚举可序列化、使用跨服务字段别名且显式保留 null。

### 3.48 冷启动基础工厂：`get_settings`、`create_engine` 与 `create_session_factory`

`get_settings` 文件：`python-agent/app/common/config.py:47-51`

逐行解释：

1. 第 47 行：`@lru_cache(maxsize=1)` 让进程只构造一次配置快照。
2. 第 48 行：定义配置读取函数。
3. 第 49 行：文档说明测试可清缓存后重新读取。
4. 第 51 行：实例化 `Settings`；Pydantic Settings 按模型配置从环境变量和项目 `.env` 读取并校验值。

`create_session_factory` 文件：`python-agent/app/infrastructure/persistence/database.py:16-19`

逐行解释：

1. 第 16-18 行：定义接受可选 Settings 的异步 SQLAlchemy 会话工厂创建函数。
2. 第 19 行：先调用项目函数 `create_engine(settings)`，再构造 `async_sessionmaker`；`expire_on_commit=False` 保证提交后领域映射仍可读取属性。

`create_engine` 文件：`python-agent/app/infrastructure/persistence/database.py:9-13`

逐行解释：

1. 第 9 行：定义 PostgreSQL 异步引擎创建函数。
2. 第 10 行：选择显式配置或调用 `get_settings()`。
3. 第 11 行：检查 `database_url` 是否为空。
4. 第 12 行：为空时抛 `PersistenceConfigurationError`，不退化为本地文件。
5. 第 13 行：调用 SQLAlchemy `create_async_engine`；`pool_pre_ping=True` 在复用连接前检测失效连接。

### 3.49 `InterviewWorkflow.load`

文件：`python-agent/app/agents/interview/workflow.py:19-36`

逐行解释：

1. 第 19 行：声明类方法。
2. 第 20-24 行：定义工作流加载函数，接收 PromptLoader 和可选配置路径，返回 `InterviewWorkflow`。
3. 第 25 行：选择显式路径或默认 `resources/agent/interview-workflow.json`。
4. 第 26 行：进入文件和结构解析保护。
5. 第 27 行：以 UTF-8 读取并解析 JSON。
6. 第 28 行：把 `stages` 每项转换为 `InterviewStage` 并组成元组。
7. 第 29 行：构造不可变工作流，开场 Prompt ID 转为字符串。
8. 第 30 行：捕获文件、字段、枚举和 JSON 错误。
9. 第 31 行：统一转换为 `WorkflowConfigurationError`。
10. 第 33 行：比较配置阶段列表与枚举完整顺序。
11. 第 34 行：缺失、重复或乱序时抛错。
12. 第 35 行：调用 `PromptLoader.load(opening_prompt)` 提前验证开场 Prompt 文件存在。
13. 第 36 行：返回已验证工作流。

### 3.50 `LLMFactory.create_chat_model`

文件：`python-agent/app/agents/llm/factory.py:12-39`

逐行解释：

1. 第 12 行：声明静态方法。
2. 第 13 行：定义聊天模型工厂。
3. 第 14 行：选择显式配置或调用 `get_settings()`。
4. 第 16 行：定义当前允许的 OpenAI-compatible provider 集合。
5. 第 17 行：把配置 provider 转小写并检查集合成员关系。
6. 第 18 行：构造 `ModelConfigurationError`。
7. 第 19 行：错误包含不支持的 provider。
8. 第 20 行：结束并抛错。
9. 第 21 行：检查模型名。
10. 第 22 行：缺失时抛配置错误。
11. 第 23 行：检查 API Key。
12. 第 24 行：缺失时抛配置错误。
13. 第 26 行：开始构造模型关键字参数。
14. 第 27 行：模型名取配置值。
15. 第 28 行：API Key 取配置值。
16. 第 29 行：温度取配置值。
17. 第 30 行：客户端超时取统一请求上限。
18. 第 31 行：注释说明重试由工程层统一接管。
19. 第 32 行：SDK 内部重试设为 0，防止双重重试放大时延。
20. 第 33 行：结束基础参数。
21. 第 34 行：检查自定义 base URL 是否存在。
22. 第 35 行：存在时加入参数。
23. 第 36 行：检查最大 token 是否显式配置。
24. 第 37 行：存在时加入参数。
25. 第 39 行：实例化并返回 LangChain `ChatOpenAI` 客户端；这里只建客户端，不发模型请求。

### 3.51 `RetryPolicy.load` 与 `IdempotencyPolicy.load`

`RetryPolicy.load` 文件：`python-agent/app/infrastructure/reliability/policy.py:20-45`

逐行解释：

1. 第 20 行：声明类方法。
2. 第 21 行：定义重试策略加载函数。
3. 第 22 行：选择显式路径或默认 `resources/agent/reliability.json`。
4. 第 23 行：进入读取与类型转换保护。
5. 第 24 行：读取并解析 JSON。
6. 第 25 行：开始构造不可变策略。
7. 第 26 行：读取最大尝试次数并转整数。
8. 第 27 行：读取初始退避毫秒。
9. 第 28 行：读取最大退避毫秒。
10. 第 29 行：把可重试异常名转成字符串冻结集合。
11. 第 30 行：读取单次超时，缺失时 120 秒。
12. 第 31 行：读取格式纠错次数，缺失时 2。
13. 第 32 行：结束策略构造。
14. 第 33 行：捕获文件、字段、类型、值和 JSON 错误。
15. 第 34 行：统一抛 `ReliabilityConfigurationError`。
16. 第 35 行：要求总尝试次数在 1~5 且初始退避非负。
17. 第 36 行：不满足时抛错。
18. 第 37 行：要求最大退避不小于初始退避。
19. 第 38 行：不满足时抛错。
20. 第 39 行：要求单次超时大于 0 且不超过 120 秒。
21. 第 40 行：不满足时抛错。
22. 第 41 行：要求结构化纠错次数在 0~2。
23. 第 42 行：不满足时抛错。
24. 第 43 行：要求可重试异常集合非空。
25. 第 44 行：为空时抛错。
26. 第 45 行：返回已验证策略。

`IdempotencyPolicy.load` 文件：`python-agent/app/infrastructure/idempotency/policy.py:15-28`

逐行解释：

1. 第 15 行：声明类方法。
2. 第 16 行：定义幂等策略加载函数。
3. 第 17 行：选择显式路径或默认 `resources/agent/idempotency.json`。
4. 第 18 行：进入读取保护。
5. 第 19 行：开始构造策略。
6. 第 20 行：把配置值转整数。
7. 第 21 行：读取 JSON 的 `maxRunSnapshots`。
8. 第 22-23 行：结束转换和策略构造。
9. 第 24 行：捕获文件、字段、值、类型和 JSON 错误。
10. 第 25 行：统一抛可靠性配置错误。
11. 第 26 行：要求快照窗口至少为 1。
12. 第 27 行：不满足时抛错。
13. 第 28 行：返回策略。

### 3.52 `build_cache`、`build_memory_service` 与 `MemoryPolicy.load`

`build_cache` 文件：`python-agent/app/bootstrap.py:41-43`

逐行解释：

1. 第 41 行：定义 RedisCache 工厂。
2. 第 42 行：选择显式配置或调用 `get_settings()`。
3. 第 43 行：以 Python 专属 `redis_url` 构造 `RedisCache`。

`build_memory_service` 文件：`python-agent/app/bootstrap.py:33-38`

逐行解释：

1. 第 33 行：定义长期记忆服务工厂。
2. 第 34 行：选择显式配置或读取进程配置。
3. 第 35 行：调用 `create_session_factory` 创建数据库会话工厂。
4. 第 36 行：开始构造 `MemoryService`。
5. 第 37 行：注入 PostgreSQL 长期记忆仓储，并调用 `MemoryPolicy.load()` 注入策略。
6. 第 38 行：结束并返回服务。

`MemoryPolicy.load` 文件：`python-agent/app/memory/policy.py:18-40`

逐行解释：

1. 第 18 行：声明类方法。
2. 第 19 行：定义记忆策略加载函数。
3. 第 20 行：选择显式路径或默认 `resources/agent/memory-policy.json`。
4. 第 21 行：进入读取保护。
5. 第 22 行：读取并解析 JSON。
6. 第 23 行：开始构造策略。
7. 第 24 行：读取短期轮次限制。
8. 第 25 行：读取历史摘要字符上限。
9. 第 26 行：读取简历快照上限。
10. 第 27 行：读取简历评价运行保留数。
11. 第 28 行：结束构造。
12. 第 29 行：捕获文件、字段、值和 JSON 错误。
13. 第 30 行：统一抛工作流配置错误。
14. 第 32 行：短期轮次只允许 3、4、5。
15. 第 33 行：不满足时抛错。
16. 第 34 行：历史摘要容量不得小于 200。
17. 第 35 行：不满足时抛错。
18. 第 36 行：至少保留一份简历快照。
19. 第 37 行：不满足时抛错。
20. 第 38 行：评价运行保留数必须为正。
21. 第 39 行：不满足时抛错。
22. 第 40 行：返回策略。

### 3.53 `build_rag_service` 与 `RagPolicy.load`

`build_rag_service` 文件：`python-agent/app/bootstrap.py:82-91`

逐行解释：

1. 第 82 行：定义 RAG 服务工厂。
2. 第 83 行：选择显式配置或读取进程配置。
3. 第 84 行：调用 `create_session_factory` 创建数据库会话工厂。
4. 第 85 行：调用 `RetryPolicy.load` 并构造 embedding 共用的重试执行器。
5. 第 86 行：开始构造 `RagService`。
6. 第 87 行：注入 PostgreSQL/pgvector 仓储。
7. 第 88 行：注入 `OpenAIEmbeddingProvider`，使用当前配置和统一重试器。
8. 第 89 行：调用 `RagPolicy.load` 注入切片、检索和缓存策略。
9. 第 90 行：调用 `build_cache(current)` 注入 Python Redis。
10. 第 91 行：结束并返回 RAG 服务。

`RagPolicy.load` 文件：`python-agent/app/rag/policy.py:25-64`

逐行解释：

1. 第 25 行：声明类方法。
2. 第 26 行：定义 RAG 策略加载函数。
3. 第 27 行：选择显式路径或默认 `resources/rag/rag-policy.json`。
4. 第 28 行：进入读取保护。
5. 第 29 行：读取并解析 JSON。
6. 第 30 行：开始构造策略。
7. 第 31 行：读取切片 token 数。
8. 第 32 行：读取切片重叠 token 数。
9. 第 33 行：读取 embedding 批大小。
10. 第 34 行：读取默认 topK。
11. 第 35 行：读取默认最低分。
12. 第 36 行：读取本地过滤回退候选倍数。
13. 第 37 行：开始构造允许用例冻结集合。
14. 第 38 行：把每个配置值转为 `RagUseCase`。
15. 第 39 行：结束集合。
16. 第 40 行：读取缓存 TTL，缺失时 300 秒。
17. 第 41 行：读取进程缓存条目上限，缺失时 256。
18. 第 42 行：结束策略构造。
19. 第 43-49 行：捕获文件、字段、值、类型和 JSON 错误。
20. 第 50 行：统一抛 `RagConfigurationError`。
21. 第 52 行：切片必须为正且重叠非负。
22. 第 53 行：不满足时抛错。
23. 第 54 行：重叠必须小于切片大小。
24. 第 55 行：不满足时抛错。
25. 第 56 行：embedding 批大小至少 1。
26. 第 57 行：不满足时抛错。
27. 第 58 行：topK 至少 1 且最低分位于 0~1。
28. 第 59 行：不满足时抛错。
29. 第 60 行：允许用例集合不得为空。
30. 第 61 行：为空时抛错。
31. 第 62 行：缓存 TTL 非负且条目上限至少 1。
32. 第 63 行：不满足时抛错。
33. 第 64 行：返回已验证策略。

### 3.54 `OpenAIEmbeddingProvider.embed_query`

文件：`python-agent/app/rag/embedding.py:48-51`

逐行解释：

1. 第 48 行：定义查询文本向量化函数。
2. 第 49 行：检查是否未注入统一重试器。
3. 第 50 行：无重试器时直接调用第三方客户端 `aembed_query`。
4. 第 51 行：有重试器时调用 `AsyncRetryExecutor.execute`，lambda 在每次尝试中重新调用 embedding API。

### 3.55 `PostgresRagVectorRepository.search` 与 `_from_entity`

`search` 文件：`python-agent/app/infrastructure/persistence/rag_vector_repository.py:59-85`

逐行解释：

1. 第 59-67 行：定义 pgvector 搜索函数，输入查询向量、topK、最低分、知识库范围和是否应用元数据过滤。
2. 第 68 行：用 pgvector `cosine_distance` 构造距离表达式。
3. 第 69 行：以 `1 - distance` 构造相似度并命名为 `score`。
4. 第 70 行：创建同时选择实体和分数的 SELECT。
5. 第 71 行：只有要求过滤且知识库集合非空时进入数据库过滤。
6. 第 72 行：追加 WHERE。
7. 第 73 行：知识库列必须属于显式 ID 集合。
8. 第 74 行：结束过滤。
9. 第 75 行：继续构造查询。
10. 第 76 行：只保留相似度不低于阈值的结果。
11. 第 77 行：按余弦距离升序，即相似度从高到低。
12. 第 78 行：限制 topK。
13. 第 79 行：结束语句。
14. 第 80 行：打开异步数据库会话。
15. 第 81 行：执行查询并取全部行。
16. 第 82 行：开始领域结果列表推导。
17. 第 83 行：调用项目函数 `_from_entity` 恢复 chunk，并把数据库分数转 float。
18. 第 84 行：遍历全部实体/分数行。
19. 第 85 行：返回结果列表。

`_from_entity` 文件：`python-agent/app/infrastructure/persistence/rag_vector_repository.py:100-111`

逐行解释：

1. 第 100 行：声明静态方法。
2. 第 101 行：定义向量实体到 `KnowledgeChunk` 的转换函数。
3. 第 102 行：开始构造领域对象。
4. 第 103 行：复制 chunkId。
5. 第 104 行：复制知识库 ID。
6. 第 105 行：复制文档 ID。
7. 第 106 行：复制来源名。
8. 第 107 行：复制切片序号。
9. 第 108 行：复制正文。
10. 第 109 行：复制元数据 JSON。
11. 第 110 行：把 pgvector 值转成普通列表。
12. 第 111 行：结束并返回 chunk。

### 3.56 网页搜索 URL 辅助函数

`_ResultLinkParser.handle_starttag` 文件：`python-agent/app/tools/web_search.py:38-45`

逐行解释：

1. 第 38 行：定义 HTML 开始标签回调；由标准库 `HTMLParser.feed` 自动调用。
2. 第 39 行：只处理 `<a>` 标签。
3. 第 40 行：其他标签立即返回。
4. 第 41 行：把属性元组列表转为字典。
5. 第 42 行：读取 class，缺失时为空字符串。
6. 第 43 行：读取 href。
7. 第 44 行：只接受 href 非空且 class 包含 DuckDuckGo 结果标记 `result__a` 的链接。
8. 第 45 行：把原始 href 加入解析器链接列表。

`_allowed_technical_url` 文件：`python-agent/app/tools/web_search.py:48-50`

逐行解释：

1. 第 48 行：定义技术域名白名单判断函数。
2. 第 49 行：解析 hostname、转大小写无关形式并移除末尾点。
3. 第 50 行：主机必须等于某白名单后缀或是其子域名。

`_unwrap_search_url` 文件：`python-agent/app/tools/web_search.py:53-57`

逐行解释：

1. 第 53 行：定义搜索跳转 URL 解包函数。
2. 第 54 行：解析 URL。
3. 第 55 行：检查主机为 DuckDuckGo 且路径是 `/l/` 跳转形式。
4. 第 56 行：解析查询参数并取第一个 `uddg` 真实地址，缺失时为空字符串。
5. 第 57 行：非跳转 URL 原样返回。

`validate_public_url` 文件：`python-agent/app/tools/web_reader.py:203-215`

逐行解释：

1. 第 203 行：定义公共 URL 安全校验函数。
2. 第 204 行：清理输入并解析。
3. 第 205 行：只允许 http/https 且必须有 hostname。
4. 第 206 行：不满足时抛不可重试依赖错误。
5. 第 207 行：进入端口解析保护。
6. 第 208 行：读取解析端口。
7. 第 209 行：捕获非法端口文本。
8. 第 210 行：转换为不可重试错误。
9. 第 211 行：拒绝用户名、密码和非 80/443 端口。
10. 第 212 行：违规时抛错。
11. 第 213 行：调用项目函数 `_is_public_host(hostname)` 做 DNS/IP SSRF 校验。
12. 第 214 行：非公网主机抛错。
13. 第 215 行：返回解析器规范化后的 URL。

`_is_public_host` 文件：`python-agent/app/tools/web_reader.py:190-200`

逐行解释：

1. 第 190 行：定义公网主机判断函数。
2. 第 191 行：进入 DNS 解析保护。
3. 第 192 行：调用 `socket.getaddrinfo` 获取全部流式连接地址。
4. 第 193 行：捕获 DNS 失败。
5. 第 194 行：转换为不可重试 `AgentDependencyError`。
6. 第 195 行：遍历所有解析地址，防止多 A/AAAA 记录中混入内网地址。
7. 第 196 行：把地址文本转为 `ipaddress` 对象。
8. 第 197-198 行：检查私网、回环、链路本地、组播、保留和未指定地址。
9. 第 199 行：任一非公网地址立即返回假。
10. 第 200 行：全部地址均为公网时返回真。

`fetch_public_article` 文件：`python-agent/app/tools/web_reader.py:218-270`

逐行解释：

1. 第 218 行：定义公共文章抓取函数。
2. 第 219 行：调用 `validate_public_url` 校验入口 URL。
3. 第 220 行：初始化最后一次网络错误。
4. 第 221 行：创建异步 HTTP 客户端。
5. 第 222 行：应用统一抓取超时。
6. 第 223 行：禁止第三方客户端自动重定向，以便每一跳重新做 SSRF 校验。
7. 第 224 行：设置固定 User-Agent。
8. 第 225 行：进入客户端上下文。
9. 第 226 行：按 `MAX_RETRIES + 1` 遍历有限尝试。
10. 第 227 行：进入单次尝试保护。
11. 第 228 行：按 `MAX_REDIRECTS + 1` 手工处理有限重定向。
12. 第 229 行：请求当前 URL。
13. 第 230 行：判断响应是否重定向。
14. 第 231 行：读取 Location。
15. 第 232 行：检查 Location 是否缺失。
16. 第 233 行：缺失时抛不可重试错误。
17. 第 234 行：用 `urljoin` 解析相对跳转，再调用 `validate_public_url` 重新校验目标。
18. 第 235 行：继续下一跳。
19. 第 236 行：非重定向响应执行状态码检查。
20. 第 237 行：读取 MIME 主类型并转小写。
21. 第 238 行：只允许 HTML/XHTML。
22. 第 239 行：其他类型抛不可重试错误。
23. 第 240 行：读取响应字节。
24. 第 241 行：检查大小是否超过 `MAX_BYTES`。
25. 第 242 行：超限抛错。
26. 第 243 行：创建项目文章解析器 `_ArticleParser`。
27. 第 244 行：按响应编码或 UTF-8 解码，错误字符替换后送入解析器。
28. 第 245 行：结束解析。
29. 第 246 行：连接标题片段，空标题时用 URL。
30. 第 247 行：过滤长度不大于 1 的正文块。
31. 第 248 行：用一级标题和双换行正文构造 Markdown。
32. 第 249 行：截断到最大字符并清理空白。
33. 第 250 行：检查可读正文是否少于 80 字符。
34. 第 251 行：过短时抛不可重试错误。
35. 第 252 行：开始构造 `WebDocument`。
36. 第 253 行：保存最终 URL。
37. 第 254 行：标题最多 500 字符。
38. 第 255 行：保存 UTC ISO 抓取时间。
39. 第 256 行：保存 Markdown SHA-256。
40. 第 257 行：保存正文。
41. 第 258 行：保存 MIME。
42. 第 259 行：把解析链接转元组。
43. 第 260 行：保存原始字节数。
44. 第 261 行：返回文档。
45. 第 262 行：超过跳转上限时抛错。
46. 第 263 行：单独捕获项目依赖错误。
47. 第 264 行：安全、格式等确定性错误原样抛出，不重试。
48. 第 265 行：捕获超时、网络和 HTTP 状态错误。
49. 第 266 行：保存为最后错误。
50. 第 267 行：检查重试是否用尽。
51. 第 268 行：用尽时跳出循环。
52. 第 269 行：否则按尝试次数做 0.5 秒线性退避。
53. 第 270 行：最终抛可重试依赖错误并链接最后网络异常。

### 3.57 `_ArticleParser` 的项目回调函数

文件：`python-agent/app/tools/web_reader.py:113-187`

`__init__`（第 113-122 行）逐行解释：

1. 第 113 行：定义解析器初始化函数。
2. 第 114 行：调用标准库父类初始化并启用字符引用转换。
3. 第 115 行：创建标题片段列表。
4. 第 116 行：创建正文块列表。
5. 第 117 行：标题嵌套深度初始为 0。
6. 第 118 行：跳过区域深度初始为 0。
7. 第 119 行：创建当前正文块缓冲。
8. 第 120 行：创建标签栈。
9. 第 121 行：创建链接列表。
10. 第 122 行：创建每层是否跳过的布尔栈。

`handle_starttag`（第 124-147 行）逐行解释：

1. 第 124 行：定义开始标签回调。
2. 第 125 行：标签名转小写。
3. 第 126 行：属性列表转字典。
4. 第 127 行：当前不在跳过区域且标签为链接时处理 href。
5. 第 128 行：读取 href。
6. 第 129 行：检查 href 非空。
7. 第 130 行：加入链接目录。
8. 第 131 行：标签压入栈。
9. 第 132 行：组合 id/class/role 并转为大小写无关提示文本。
10. 第 133 行：标签在固定跳过集合，或提示文本包含样板关键词时标记跳过。
11. 第 134 行：把本层跳过标志压栈。
12. 第 135 行：检查当前标签本身是否应跳过。
13. 第 136 行：跳过深度加一。
14. 第 137 行：立即返回。
15. 第 138 行：当前已经位于外层跳过区域时检查命中。
16. 第 139 行：命中时不处理内容结构。
17. 第 140 行：识别 title 标签。
18. 第 141 行：标题深度加一。
19. 第 142 行：识别需要形成块边界的正文/标题/列表标签。
20. 第 143 行：调用 `_flush` 结束前一块。
21. 第 144 行：识别 h1~h9 形式标题。
22. 第 145 行：按最大六级加入 Markdown `#` 前缀。
23. 第 146 行：识别列表项。
24. 第 147 行：加入 Markdown 列表前缀。

`handle_endtag`（第 149-166 行）逐行解释：

1. 第 149 行：定义结束标签回调。
2. 第 150 行：标签名转小写。
3. 第 151 行：弹出本层跳过标志；栈空时为假。
4. 第 152 行：本层跳过且跳过深度非零时进入恢复。
5. 第 153 行：跳过深度减一。
6. 第 154 行：检查标签栈。
7. 第 155 行：弹出对应标签。
8. 第 156 行：结束处理。
9. 第 157 行：如果仍位于外层跳过区域。
10. 第 158 行：检查标签栈。
11. 第 159 行：弹出标签。
12. 第 160 行：返回。
13. 第 161 行：关闭 title 且标题深度非零时命中。
14. 第 162 行：标题深度减一。
15. 第 163 行：正文块边界标签结束时命中。
16. 第 164 行：调用 `_flush` 完成当前块。
17. 第 165 行：检查标签栈。
18. 第 166 行：弹出标签。

`handle_data`（第 168-176 行）逐行解释：

1. 第 168 行：定义文本数据回调。
2. 第 169 行：位于跳过区域时命中。
3. 第 170 行：忽略数据。
4. 第 171 行：把连续空白压成单空格并去首尾空白。
5. 第 172 行：检查结果是否为空。
6. 第 173 行：空文本返回。
7. 第 174 行：位于标题区域时命中。
8. 第 175 行：加入标题片段。
9. 第 176 行：所有可见文本加入当前正文块。

`close`（第 178-180 行）逐行解释：

1. 第 178 行：覆盖解析器关闭函数。
2. 第 179 行：先调用父类关闭以处理缓冲数据。
3. 第 180 行：调用 `_flush` 保存最后一块。

`_flush`（第 182-187 行）逐行解释：

1. 第 182 行：定义块刷新函数。
2. 第 183 行：检查当前块是否非空。
3. 第 184 行：以单空格连接并清理。
4. 第 185 行：检查连接结果非空。
5. 第 186 行：加入最终正文块列表。
6. 第 187 行：清空当前缓冲，为下一块复用。

### 3.58 模型与 Skill 的项目级校验函数

`InterviewPlan.validate_stage_order` 文件：`python-agent/app/agents/interview/models.py:84-101`

该函数由 Pydantic 在 `StructuredOutputInvoker._validate` 调用 `InterviewPlan.model_validate` 时自动执行。

逐行解释：

1. 第 84 行：注册 `mode="after"` 模型校验器，字段类型转换完成后运行。
2. 第 85 行：定义实例校验函数。
3. 第 86 行：把全部 `InterviewStage` 枚举按声明顺序转为预期列表。
4. 第 87 行：提取模型计划实际阶段顺序。
5. 第 88 行：比较两个列表。
6. 第 89 行：缺失、重复或乱序时抛错。
7. 第 91 行：按阶段建立配置映射。
8. 第 92 行：检查开场主问题数必须为 1。
9. 第 93 行：不满足时抛错。
10. 第 94 行：检查总结输出数必须为 1。
11. 第 95 行：不满足时抛错。
12. 第 96 行：遍历全部阶段。
13. 第 97 行：检查主问题上限不超过系统常量 4。
14. 第 98 行：超限抛错。
15. 第 99 行：检查每题追问上限不超过系统常量 2。
16. 第 100 行：超限抛错。
17. 第 101 行：返回已验证自身。

`InterviewPlan.get_stage` 文件：`python-agent/app/agents/interview/models.py:103-104`

1. 第 103 行：定义按阶段取得 `StagePlan` 的函数。
2. 第 104 行：返回 `stages` 中首个枚举相等项；不存在时标准库 `next` 抛 `StopIteration`。

`SkillRegistry._validate_public_item` 文件：`python-agent/app/tools/skills/loader.py:212-221`

逐行解释：

1. 第 212 行：声明静态方法。
2. 第 213 行：定义展示目录单项校验函数。
3. 第 214 行：检查输入是否字典。
4. 第 215 行：不是对象时抛 Skill 配置错误。
5. 第 216 行：定义必需字段集合。
6. 第 217 行：检查必需集合是否是项目键集合的子集。
7. 第 218 行：缺字段时抛错。
8. 第 219 行：检查 `categories` 是否列表。
9. 第 220 行：不是数组时抛错。
10. 第 221 行：复制并返回字典，避免直接暴露 JSON 解析对象。

`AgentResponse.validate_code_category` 文件：`python-agent/app/common/contracts.py:177-182`

该函数在 `_success_response`、`_error_response` 构造 `AgentResponse` 时由 Pydantic 自动执行。

逐行解释：

1. 第 177 行：把校验器注册到 `code` 字段。
2. 第 178 行：声明类方法。
3. 第 179 行：定义业务码类别校验函数。
4. 第 180 行：取 code 百位以上类别，要求首位属于 1~5。
5. 第 181 行：不满足时抛 `ValueError`。
6. 第 182 行：返回合法原值。

## 4. 主流构建分析

主流实时 Agent 会把一次回答建模为可恢复的工作流实例：API 先以 runId 写入回答和 PENDING step，工作流引擎依次执行评价、检索、路由、出题，每步持久化检查点并通过事件流推送进度。优点是 150 秒以上任务可断点恢复、单节点独立重试、审计清晰；缺点是需要 Temporal/Cadence/自建状态机、活动幂等和更复杂的数据一致性。

本项目已有 runId 快照、stateVersion 乐观锁、节点超时、Redis 进度和确定性回退，单轮同步体验较好，暂不必引入完整工作流引擎。若并发和模型链长度继续增加，可先将 `_submit_answer` 拆成带 step 字段的数据库状态机：Java 接口返回 runId，Python Worker 对 EVALUATE/RETRIEVE/ROUTE/GENERATE 分步落库，复用现有每个 Agent 函数；前端通过现有 progress 接口或 SSE 获取结果，runId 与 stateVersion 继续作为幂等和并发边界。
