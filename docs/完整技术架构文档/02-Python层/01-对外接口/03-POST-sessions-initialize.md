# POST /v1/agent/sessions/initialize：初始化面试会话

## 1. 接口定义

该接口把候选人资料转换成 Python Agent 的 `CandidateProfile`，调用面试服务生成面试计划、开场问题并初始化用户长期记忆，最后以统一 `AgentResponse` 返回会话和题量统计。它是面试链路的同步入口；规划模型超时或持久化失败会交给统一异常处理器。

| 项目 | 内容 |
|---|---|
| HTTP 方法 | POST |
| 路径 | `/v1/agent/sessions/initialize` |
| 路由函数 | `initialize_session` |
| 请求模型 | `AgentInitializationRequest` |
| 文件 | `python-agent/app/api/application.py:70-91` |
| 主要下游 | `_remember_request_context`、`_resolve_service`、`CandidateProfile.model_validate`、`InterviewAgentService.initialize_session`、`_success_response` |

## 2. 函数调用链

```text
FastAPI/Pydantic 请求解析
 -> initialize_session
 -> _remember_request_context
 -> _resolve_service
 -> CandidateProfile.model_validate（Pydantic）
 -> InterviewAgentService.initialize_session
    -> repository.get
    -> _run_interview_node
       -> _report_progress
       -> InterviewPlanner.create_plan（Agent/Reliability/Skills/Prompt/LLM）
    -> InterviewWorkflow.opening_message
       -> PromptLoader.render
    -> _profile_fingerprint
    -> _register_question
    -> MemoryService.initialize_user_memory
    -> repository.create
 -> _success_response
 -> AgentResponse（Pydantic）
```

## 3. 函数解析

### 3.1 `initialize_session`

文件：`python-agent/app/api/application.py:70-91`

```python
    @app.post("/v1/agent/sessions/initialize", response_model=AgentResponse)
    async def initialize_session(payload: AgentInitializationRequest, request: Request) -> AgentResponse:
        _remember_request_context(request, payload)
        session = await _resolve_service(request).initialize_session(
            user_id=payload.user_id,
            session_id=payload.session_id,
            profile=CandidateProfile.model_validate(
                {**payload.candidate.model_dump(), "question_count": DEFAULT_TARGET_QUESTION_COUNT}
            ),
            run_id=payload.run_id,
        )
        return _success_response(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, session=session,
            output={
                "currentPrimaryQuestionCount": getattr(session, "primary_question_count", 1),
                "totalPrimaryQuestionCount": getattr(session, "total_primary_question_count", 1),
                "currentFollowupCount": getattr(session, "followup_count", 0),
                "totalQuestionCount": getattr(session, "total_question_count", 1),
                "questionBudget": getattr(session, "target_question_count", None),
            },
        )
```

逐行解释：

1. 第 70 行：注册 POST 路由并声明响应模型；FastAPI 会在返回前按 `AgentResponse` 校验和序列化。
2. 第 71 行：声明异步入口，Pydantic 已把请求体解析为 `AgentInitializationRequest`。
3. 第 72 行：调用 `_remember_request_context` 保存别名字段形式的请求上下文，为异常响应提供 requestId、runId 和会话信息。
4. 第 73 行：解析面试服务并异步调用其初始化方法。
5. 第 74 行：把候选人所属用户 ID 传入服务，用于权限和长期记忆隔离。
6. 第 75 行：把请求中的会话 ID 传入，服务据此做幂等读取和持久化。
7. 第 76 行：开始构造领域层 `CandidateProfile`，而不是把接口 DTO 直接传进 Agent。
8. 第 77 行：复制候选人子模型字段，并强制覆盖 `question_count` 为项目默认题量，防止上层绕过题量策略。
9. 第 78 行：结束 `model_validate` 调用；Pydantic 在此执行类型和字段校验。
10. 第 79 行：把 runId 传给服务，用于同一初始化请求的幂等快照。
11. 第 80 行：结束异步服务调用，得到已创建或幂等返回的 `InterviewSession`。
12. 第 81 行：进入统一成功响应构造函数。
13. 第 82 行：透传 API 版本和请求 ID。
14. 第 83 行：透传运行 ID 和会话对象。
15. 第 84 行：开始组装候选人可见的题量统计输出。
16. 第 85 行：读取当前主问题数；兼容缺少字段的替身对象时回退为 1。
17. 第 86 行：读取累计主问题数，缺失时回退为 1。
18. 第 87 行：读取当前追问数，缺失时回退为 0。
19. 第 88 行：读取总问题数，缺失时回退为 1。
20. 第 89 行：读取服务计算的题量预算，缺失时返回 `None`。
21. 第 90 行：结束统计字典。
22. 第 91 行：结束 `_success_response` 调用并返回 `AgentResponse`。

### 3.2 `_remember_request_context`

文件：`python-agent/app/api/application.py:388-391`

```python
def _remember_request_context(request: Request, payload: object) -> None:
    dumper = getattr(payload, "model_dump", None)
    if callable(dumper):
        request.state.agent_context = dumper(by_alias=True, mode="json")
```

逐行解释：

1. 第 388 行：定义同步上下文缓存函数，参数接受 FastAPI 请求和任意 payload。
2. 第 389 行：从 payload 动态读取 Pydantic 的 `model_dump`，不强制依赖具体请求模型。
3. 第 390 行：只有对象确实提供可调用序列化方法时才继续。
4. 第 391 行：以接口别名和 JSON 模式序列化，保存到请求状态；异常处理器稍后直接读取，避免重新消费请求体。

### 3.3 `InterviewAgentService.initialize_session`

文件：`python-agent/app/agents/interview/service.py:127-189`

```python
    async def initialize_session(
        self,
        *,
        user_id: str,
        session_id: str,
        profile: CandidateProfile,
        run_id: str | None = None,
    ) -> InterviewSession:
        existing = await self._repository.get(session_id)
        if existing is not None:
            if (
                run_id
                and existing.user_id == user_id
                and existing.initialization_run_id == run_id
            ):
                expected_fingerprint = self._profile_fingerprint(profile)
                if (
                    existing.initialization_fingerprint is not None
                    and existing.initialization_fingerprint != expected_fingerprint
                ):
                    raise ConsistencyError("同一初始化 runId 不能提交不同的会话参数")
                return existing
            raise ConsistencyError("Agent 会话已存在")
        plan = await self._run_interview_node(
            session_id,
            "PLANNING",
            lambda: self._planner.create_plan(profile),
        )
        session = InterviewSession(
            session_id=session_id,
            user_id=user_id,
            candidate_id=profile.candidate_id,
            resume_id=profile.resume_id,
            jd_id=profile.jd_id,
            resume_text=profile.resume_text,
            jd_text=profile.jd_text,
            target_role=profile.target_role,
            interview_duration_minutes=profile.interview_duration_minutes,
            interview_direction=profile.interview_direction,
            custom_categories=profile.custom_categories,
            difficulty=profile.desired_difficulty,
            plan=plan,
            target_question_count=min(profile.question_count, MAX_TOTAL_QUESTIONS),
            selected_skills=plan.selected_skills,
            current_question=self._workflow.opening_message(
                self._prompt_loader, profile.target_role
            ),
            current_topic="自我介绍",
            system_knowledge_base_ids=profile.system_knowledge_base_ids,
            user_knowledge_base_ids=profile.user_knowledge_base_ids,
            initialization_run_id=run_id,
            initialization_fingerprint=self._profile_fingerprint(profile),
        )
        self._register_question(session, session.current_question, InterviewStage.OPENING, "自我介绍")
        await self._memory_service.initialize_user_memory(
            user_id=user_id, profile=profile
        )
        return await self._repository.create(session)
```

逐行解释：

1. 第 127-134 行：定义异步初始化函数及其关键参数；用户、会话、候选人资料和可选 runId 构成一次初始化请求。
2. 第 135 行：按会话 ID 查询持久化记录。
3. 第 136 行：进入已有会话分支。
4. 第 137-141 行：只有 runId 存在、用户一致且初始化 runId 一致时，才把请求视为幂等重放。
5. 第 142 行：对当前资料计算指纹。
6. 第 143-147 行：若历史指纹存在且与当前不同，抛出一致性错误，禁止同一 runId 改写会话参数。
7. 第 148 行：幂等参数一致时直接返回原会话，不重复调用模型或写入记忆。
8. 第 149 行：其他已有会话情况统一拒绝，避免覆盖用户会话。
9. 第 155-159 行：通过 `_run_interview_node` 把规划 Agent 包在进度上报和 45 秒节点超时内。
10. 第 160-184 行：用资料和模型生成的 `plan` 构造完整 `InterviewSession`；其中题量用 `MAX_TOTAL_QUESTIONS` 截断，技能取规划结果，开场题由工作流模板生成，知识库 ID 和幂等指纹一并保存。
11. 第 185 行：登记开场问题，更新问题目录和题量统计。
12. 第 186-188 行：初始化用户级长期记忆；这一步与会话创建前后保持同一用户边界。
13. 第 189 行：调用仓储创建并返回最终会话；真正的数据库实现由注入的 Repository 完成。

### 3.4 `_success_response`

文件：`python-agent/app/api/application.py:357-374`

```python
def _success_response(*, api_version: str, request_id: str, run_id: str,
                      session: InterviewSession, answer: str | None = None,
                      output: dict[str, object] | None = None,
                      state_version: int | None = None,
                      session_status: SessionStatus | None = None,
                      turn_stage: str | None = None,
                      current_stage: str | None = None) -> AgentResponse:
    return AgentResponse(
        api_version=api_version, request_id=request_id, run_id=run_id,
        code=100, status=RunStatus.COMPLETED, user_id=session.user_id,
        session_id=session.session_id, session_status=session_status or session.status,
        state_version=state_version if state_version is not None else session.state_version,
        answer=answer if answer is not None else session.current_question,
        turn_stage=turn_stage,
        current_stage=current_stage or getattr(session, "current_stage", None),
        output=output, error=None,
    )
```

逐行解释：

1. 第 357-365 行：声明统一成功响应的关键字参数，允许接口覆盖答案、状态版本、阶段和输出。
2. 第 366 行：开始构造 `AgentResponse`。
3. 第 367 行：复制协议元数据，并固定成功业务码 100。
4. 第 368 行：从会话取得用户和会话 ID；状态优先使用调用方传入值。
5. 第 369 行：传入显式版本，否则使用会话当前版本。
6. 第 370 行：有显式答案就返回答案，否则返回当前问题。
7. 第 371-372 行：写入本轮阶段和当前阶段，当前阶段缺失时保持 `None`。
8. 第 373 行：附加白名单后的业务输出。
9. 第 374 行：成功响应不携带错误对象。

## 4. 审核结论

初始化入口的实际业务核心是 `InterviewAgentService.initialize_session`；规划 Agent、工作流模板、记忆服务和持久化仓储均通过依赖注入进入调用链。本文未把 FastAPI/Pydantic 自动生成的框架方法冒充项目自定义函数。
