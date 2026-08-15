# POST /v1/agent/evaluate/resume：评估简历并写入记忆

## 1. 接口定义

该接口按简历文本、目标岗位和 subjectId 生成结构化简历评价。它先尝试读取同一 runId 与输入指纹的缓存，未命中才调用 `ResumeEvaluationAgent`；随后把摘要、问题、建议、技术栈和职业偏好写入长期记忆，并返回别名序列化后的评价。

| 项目 | 内容 |
|---|---|
| HTTP 方法 | POST |
| 路径 | `/v1/agent/evaluate/resume` |
| 路由函数 | `evaluate_resume` |
| 请求模型 | `AgentEvaluationRequest` |
| 文件 | `python-agent/app/api/application.py:156-203` |

## 2. 函数调用链

```text
evaluate_resume
 -> _remember_request_context
 -> _resume_evaluation_fingerprint
 -> _resolve_memory_service
 -> MemoryService.get_resume_evaluation_run
 -> （缓存未命中）_resolve_resume_evaluator -> build_resume_evaluation_agent
 -> ResumeEvaluationAgent.evaluate
    -> SkillRegistry.get -> PromptLoader.render/load/_resolve
    -> StructuredOutputInvoker.invoke -> AsyncRetryExecutor.execute -> model.ainvoke
    -> _validate -> ResumeEvaluation.model_validate
 -> MemoryService.record_resume_analysis
 -> （ConsistencyError）再次 get_resume_evaluation_run
 -> AgentResponse
```

## 3. 函数解析

### 3.1 `evaluate_resume`

文件：`python-agent/app/api/application.py:156-203`

```python
    @app.post("/v1/agent/evaluate/resume", response_model=AgentResponse)
    async def evaluate_resume(payload: AgentEvaluationRequest, request: Request) -> AgentResponse:
        _remember_request_context(request, payload)
        fingerprint = _resume_evaluation_fingerprint(payload)
        memory_service = _resolve_memory_service(request)
        result = await memory_service.get_resume_evaluation_run(
            user_id=payload.user_id, resume_id=payload.subject_id,
            run_id=payload.run_id, evaluation_fingerprint=fingerprint,
        )
        if result is None:
            result = await _resolve_resume_evaluator(request).evaluate(
                subject_id=payload.subject_id,
                input_text=payload.input_text,
                target_role=payload.target_role,
            )
        try:
            await memory_service.record_resume_analysis(
                user_id=payload.user_id, resume_id=payload.subject_id,
                candidate_id=payload.candidate_id, resume_text=payload.input_text,
                target_role=payload.target_role, summary=result.summary,
                questions=[item.question for item in result.issues],
                priorities=[item.priority for item in result.issues],
                suggestions=[item.suggestion for item in result.issues] + result.suggestions,
                technical_stack=result.technical_stack,
                technical_depth=result.technical_depth,
                career_preferences=result.career_preferences,
                run_id=payload.run_id, evaluation_fingerprint=fingerprint,
                evaluation=result,
            )
        except ConsistencyError:
            replay = await memory_service.get_resume_evaluation_run(
                user_id=payload.user_id, resume_id=payload.subject_id,
                run_id=payload.run_id, evaluation_fingerprint=fingerprint,
            )
            if replay is None:
                raise
            result = replay
        return AgentResponse(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, code=100, status=RunStatus.COMPLETED,
            user_id=payload.user_id, session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE, state_version=0,
            answer=result.summary, output=result.model_dump(by_alias=True), error=None,
        )
```

逐行解释：

1. 第 156-157 行：注册并接收简历评价请求。
2. 第 158 行：保存请求上下文。
3. 第 159 行：根据 subjectId、原文和岗位计算稳定指纹。
4. 第 160 行：解析记忆服务。
5. 第 161-165 行：查询同一用户、简历、runId 和指纹的历史评价。
6. 第 166-171 行：缓存未命中时懒加载评价 Agent，并传入主体、文本和目标岗位。
7. 第 172 行：开始记忆写入异常保护。
8. 第 173-190 行：把评价摘要、问题推导列表、建议、技术栈、深度、偏好和幂等元数据传给记忆服务。
9. 第 191 行：捕获并发写入导致的一致性错误。
10. 第 192-196 行：按相同幂等条件重新读取；没有可重放结果则继续抛错。
11. 第 197-203 行：构造完成响应，答案为摘要，输出为评价模型的别名 JSON。

### 3.2 `_resume_evaluation_fingerprint`

文件：`python-agent/app/api/application.py:435-442`

```python
def _resume_evaluation_fingerprint(payload: AgentEvaluationRequest) -> str:
    canonical = json.dumps({
        "subjectId": payload.subject_id,
        "inputText": payload.input_text,
        "targetRole": payload.target_role,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()
```

逐行解释：

1. 第 435 行：定义评价输入指纹函数。
2. 第 436-440 行：只选择影响结果的三个字段，并按排序键、紧凑分隔符生成确定性 JSON。
3. 第 441 行：UTF-8 编码后计算 SHA-256，返回固定长度十六进制指纹。

### 3.3 `ResumeEvaluationAgent.evaluate`

文件：`python-agent/app/agents/evaluation/agent.py:25-40`

```python
    async def evaluate(self, *, subject_id: str, input_text: str,
                       target_role: str) -> ResumeEvaluation:
        skill = self._skill_registry.get("resume-analyst")
        prompt = self._prompt_loader.render(
            "resume/analysis.md", {"skill_instructions": skill.instructions}
        )
        return await self._structured_output.invoke(
            model=self._model, schema=ResumeEvaluation,
            business_prompt=prompt,
            input_payload={
                "subjectId": subject_id,
                "inputText": input_text,
                "targetRole": target_role,
            },
        )
```

逐行解释：

1. 第 25-27 行：接收简历主体、原文和目标岗位，返回结构化评价。
2. 第 28 行：读取固定的 `resume-analyst` 技能。
3. 第 29-31 行：加载并渲染简历分析提示模板，把技能说明注入模板。
4. 第 32-40 行：调用结构化输出执行器，指定评价 schema 和三项输入；重试、模型调用和 Pydantic 校验在执行器中完成。

## 4. 审核结论

缓存读取、模型评价和记忆写入均可独立重放；`ConsistencyError` 的回读分支已在调用链中列出。
