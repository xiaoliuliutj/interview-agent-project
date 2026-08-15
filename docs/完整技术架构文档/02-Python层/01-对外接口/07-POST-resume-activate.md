# POST /v1/agent/resume/activate：激活简历长期记忆

## 1. 接口定义

该接口不调用评价模型，只把候选人的简历文本、目标岗位和候选人标识合并到用户长期记忆的 active resume 快照中。Java 简历分析异步任务通常先调用该接口，再调用评价接口。

| 项目 | 内容 |
|---|---|
| HTTP 方法 | POST |
| 路径 | `/v1/agent/resume/activate` |
| 路由函数 | `activate_resume_memory` |
| 文件 | `python-agent/app/api/application.py:205-221` |

## 2. 函数调用链

```text
activate_resume_memory
 -> _remember_request_context
 -> _resolve_memory_service
 -> MemoryService.activate_resume
    -> _resume_activation_fingerprint
    -> repository.get/create/save
 -> AgentResponse
```

## 3. 函数解析

### 3.1 `activate_resume_memory`

文件：`python-agent/app/api/application.py:205-221`

```python
    @app.post("/v1/agent/resume/activate", response_model=AgentResponse)
    async def activate_resume_memory(
        payload: AgentResumeMemoryActivationRequest, request: Request
    ) -> AgentResponse:
        _remember_request_context(request, payload)
        await _resolve_memory_service(request).activate_resume(
            user_id=payload.user_id, resume_id=payload.subject_id,
            candidate_id=payload.candidate_id, resume_text=payload.input_text,
            target_role=payload.target_role, run_id=payload.run_id,
        )
        return AgentResponse(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, code=100, status=RunStatus.COMPLETED,
            user_id=payload.user_id, session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE, state_version=0,
            answer=None, output=None, error=None,
        )
```

逐行解释：

1. 第 205-208 行：注册路径并声明请求模型和响应模型。
2. 第 209 行：缓存请求上下文。
3. 第 210-214 行：懒加载记忆服务并转发用户、简历、候选人、原文、岗位和 runId；函数返回值不向接口暴露。
4. 第 215-221 行：构造固定成功响应；该操作没有新问题或评价答案，因此 `answer`、`output` 都是 `None`。

### 3.2 `MemoryService.activate_resume`

文件：`python-agent/app/memory/service.py:49-97`

```python
    async def activate_resume(
        self, *, user_id: str, resume_id: str, candidate_id: str, resume_text: str,
        target_role: str, run_id: str | None = None,
    ) -> LongTermMemory:
        fingerprint = self._resume_activation_fingerprint(
            resume_id=resume_id, candidate_id=candidate_id,
            resume_text=resume_text, target_role=target_role,
        )
        snapshot = ResumeMemory(
            resume_id=resume_id, candidate_id=candidate_id, target_role=target_role,
            resume_text=resume_text,
        )
        existing = await self._repository.get(user_id)
        if existing is None:
            memory = LongTermMemory(
                user_id=user_id, active_resume_id=resume_id, resume_snapshots=[snapshot]
            )
            if run_id:
                memory.resume_activation_runs[run_id] = ResumeActivationRun(
                    run_id=run_id, resume_id=resume_id, fingerprint=fingerprint
                )
            return await self._repository.create(memory)
        existing_run = existing.resume_activation_runs.get(run_id) if run_id else None
        if existing_run is not None:
            if existing_run.resume_id != resume_id or existing_run.fingerprint != fingerprint:
                raise ConsistencyError("同一 resume activation runId 不能提交不同的输入")
            return existing
        expected_version = existing.state_version
        existing.active_resume_id = resume_id
        existing.resume_snapshots = self._merge_resume_snapshot(existing.resume_snapshots, snapshot)
        existing.technical_stack = []
        existing.technical_depth = []
        existing.preferences = []
        if run_id:
            existing.resume_activation_runs[run_id] = ResumeActivationRun(
                run_id=run_id, resume_id=resume_id, fingerprint=fingerprint
            )
            while len(existing.resume_activation_runs) > self._policy.max_resume_evaluation_runs:
                existing.resume_activation_runs.pop(next(iter(existing.resume_activation_runs)))
        existing.updated_at = datetime.now(timezone.utc)
        return await self._repository.save(existing, expected_version=expected_version)
```

逐行解释：

1. 第 49-53 行：声明激活函数及用户、简历、候选人、文本、岗位和可选 runId。
2. 指纹语句：调用 `_resume_activation_fingerprint` 将四项输入归一为幂等指纹。
3. `ResumeMemory` 构造：建立不含评价结果的新简历快照。
4. 仓储读取语句：按 userId 读取长期记忆；不存在时进入创建分支。
5. 新记忆分支：设置 active resume，按需记录 `ResumeActivationRun`，再调用 repository.create。
6. 已有记忆分支：按 runId 读取历史激活；命中时核对简历 ID 和指纹，内容变化抛错，一致直接返回。
7. 正常更新分支：保存 stateVersion，切换 active 指针并合并快照。
8. 清空三个旧简历派生画像列表。
9. 保存 runId 快照并按策略淘汰最旧记录。
10. 更新时间并带 expectedVersion 保存。

## 4. 审核结论

激活接口是纯记忆写入链路；源码没有调用 `ResumeEvaluationAgent` 或 LLM。
