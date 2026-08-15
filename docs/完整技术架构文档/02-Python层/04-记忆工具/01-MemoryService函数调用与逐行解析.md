# 记忆工具：MemoryService 函数调用与逐行解析

## 1. 接口定义

记忆模块本身没有 HTTP 路由，由会话初始化、回答推进、会话结束、简历激活和简历评价接口调用。它以 userId 隔离长期记忆，用 stateVersion 做乐观锁，以 runId、指纹、turnId 和 sessionId 实现幂等补偿。

## 2. 函数调用链

```text
initialize_session -> initialize_user_memory -> to_resume_memory -> _merge_resume_snapshot -> repository.save
respond -> build_context -> repository.get
respond -> record_turn -> _append_summary/_merge_items -> repository.save
complete/respond终态 -> finalize_session -> _append_summary/_merge_items -> repository.save
resume/activate -> activate_resume -> _resume_activation_fingerprint/_merge_resume_snapshot -> repository.save
evaluate/resume -> get_resume_evaluation_run -> record_resume_analysis -> _unique_items -> repository.save
```

## 3. 函数解析

### 3.1 `MemoryService.__init__`

文件：`python-agent/app/memory/service.py:24-26`

```python
    def __init__(self, repository: LongTermMemoryRepository, policy: MemoryPolicy) -> None:
        self._repository = repository
        self._policy = policy
```

逐行解释：第 24 行声明依赖；第 25 行保存仓储；第 26 行保存条数和字符数策略，所有后续函数都复用这两个对象。

### 3.2 `initialize_user_memory`

文件：`python-agent/app/memory/service.py:28-47`

```python
    async def initialize_user_memory(self, *, user_id: str, profile: CandidateProfile) -> LongTermMemory:
        existing = await self._repository.get(user_id)
        if existing is None:
            return await self._repository.create(LongTermMemory(
                user_id=user_id,
                active_resume_id=profile.resume_id,
                resume_snapshots=[to_resume_memory(profile)],
            ))
        expected_version = existing.state_version
        if existing.active_resume_id != profile.resume_id:
            existing.technical_stack = []
            existing.technical_depth = []
            existing.preferences = []
            existing.notes = []
        existing.active_resume_id = profile.resume_id
        existing.resume_snapshots = self._merge_resume_snapshot(existing.resume_snapshots, to_resume_memory(profile))
        existing.updated_at = datetime.now(timezone.utc)
        return await self._repository.save(existing, expected_version=expected_version)
```

逐行解释：

1. 第 28 行定义初始化函数；第 29 行按 userId 读取长期记忆。
2. 第 30-35 行：无记录时用当前简历作为 active resume，调用 `to_resume_memory` 生成首个快照并创建。
3. 第 36 行：已有记录先保存 stateVersion，后续保存必须匹配该版本。
4. 第 39-43 行：简历版本变化时清空由旧简历派生的技术栈、深度、偏好和备注。
5. 第 44 行：切换 active resume 指针。
6. 第 45 行：把当前资料转换为快照并调用 `_merge_resume_snapshot` 替换同 ID 版本。
7. 第 46-47 行：更新时间，使用期望版本保存并返回。

### 3.3 `activate_resume`

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

1. 第 49-53 行定义参数；resumeId、candidateId、文本和岗位决定激活内容，runId 可选。
2. 第 60-63 行调用 `_resume_activation_fingerprint`，保证同输入得到相同 SHA-256。
3. 第 65-68 行创建没有分析结果的新简历快照。
4. 第 69 行读取用户记忆；第 70-79 行无记录时创建记忆、可选记录激活 run，并直接插入数据库。
5. 第 80 行按 runId 读取历史激活；第 81-85 行命中时核对 resumeId 和指纹，不同输入报错，相同输入幂等返回。
6. 第 86-88 行保存版本、切换 active 指针并合并快照。
7. 第 91-93 行清空旧简历派生的三个画像列表。
8. 第 94-100 行保存激活 run；超过策略上限时按插入顺序删除最旧项。
9. 第 101-102 行更新时间并带 expectedVersion 保存。

### 3.4 `build_context`

文件：`python-agent/app/memory/service.py:99-120`

```python
    async def build_context(self, session: InterviewSession) -> MemoryContext:
        memory = await self._repository.get(session.user_id)
        if memory is None:
            return MemoryContext.empty(session).model_copy(
                update={
                    "recent_turns": session.turns[-self._policy.short_term_turn_limit :],
                    "conversation_summary": getattr(session, "history_summary", ""),
                }
            )
        active_resume = next((item for item in memory.resume_snapshots if item.resume_id == session.resume_id), None)
        return MemoryContext(
            recent_turns=session.turns[-self._policy.short_term_turn_limit :],
            conversation_summary=getattr(session, "history_summary", ""),
            historical_summary=memory.historical_summary,
            active_resume=active_resume,
            technical_stack=memory.technical_stack,
            technical_depth=memory.technical_depth,
            preferences=memory.preferences,
            weak_topics=memory.weak_topics,
            notes=memory.notes,
            question_catalog=memory.question_catalog,
        )
```

逐行解释：

1. 第 99-100 行按会话用户读取长期记忆。
2. 第 101-108 行没有记录时从 `MemoryContext.empty` 起步，只覆盖策略允许的最近轮次和会话压缩摘要。
3. 第 109 行仅查找与本会话 resumeId 相同的快照，不能误用用户当前其他简历。
4. 第 110-120 行组合短期轮次、会话摘要、历史摘要、简历、技术画像、弱项、备注和问题目录，形成 Agent 唯一可读的上下文视图。

### 3.5 `record_turn`

文件：`python-agent/app/memory/service.py:122-144`

```python
    async def record_turn(self, *, session: InterviewSession, turn: TurnRecord) -> LongTermMemory | None:
        memory = await self._repository.get(session.user_id)
        if memory is None:
            return None
        if turn.turn_id in memory.recorded_turn_ids:
            return memory
        expected_version = memory.state_version
        topic = turn.topic or turn.stage.value
        event = f"[{session.session_id}/{turn.stage}/{topic}] score={turn.score}; {turn.evaluation_summary}"
        memory.historical_summary = self._append_summary(memory.historical_summary, event)
        memory.question_catalog = self._merge_items(memory.question_catalog, [turn.question], limit=100)
        memory.weak_topics = self._merge_items(memory.weak_topics, turn.weaknesses, limit=30)
        memory.notes = self._merge_items(memory.notes, turn.strengths, limit=30)
        memory.preferences = self._merge_items(memory.preferences, turn.preferences, limit=30)
        memory.recorded_turn_ids = self._merge_items(memory.recorded_turn_ids, [turn.turn_id], limit=500)
        memory.updated_at = datetime.now(timezone.utc)
        try:
            return await self._repository.save(memory, expected_version=expected_version)
        except ConsistencyError:
            latest = await self._repository.get(session.user_id)
            if latest is not None and turn.turn_id in latest.recorded_turn_ids:
                return latest
            raise
```

逐行解释：

1. 读取记忆；不存在返回 `None`，不为单轮自动创建用户画像。
2. turnId 已记录时幂等返回；否则保存 stateVersion。
3. 主题优先用本轮 topic，缺失时用阶段；event 串记录会话、阶段、主题、得分和摘要。
4. `_append_summary` 截断累积摘要，多个 `_merge_items` 分别合并问题、弱项、优点、偏好和 turnId，并去重限长。
5. 更新时间后按期望版本保存。
6. 乐观锁冲突时重新读取；若并发请求已写入同 turnId，则返回最新记录，否则保留冲突异常。

### 3.6 `finalize_session`

文件：`python-agent/app/memory/service.py:146-175`

```python
    async def finalize_session(self, *, session: InterviewSession, interrupted: bool = False) -> LongTermMemory | None:
        memory = await self._repository.get(session.user_id)
        if memory is None:
            return None
        if session.session_id in memory.finalized_session_ids:
            return memory
        expected_version = memory.state_version
        scores = [turn.score for turn in session.turns]
        average = round(sum(scores) / len(scores)) if scores else 0
        weaknesses = [item for turn in session.turns for item in turn.weaknesses]
        strengths = [item for turn in session.turns for item in turn.strengths]
        summary = (
            f"session={session.session_id}; {'interrupted' if interrupted else 'completed'}; "
            f"turns={len(session.turns)}; averageScore={average}; "
            f"summary={session.final_summary or 'No final summary'}"
        )
        memory.historical_summary = self._append_summary(memory.historical_summary, summary)
        memory.interview_summaries = self._merge_items(memory.interview_summaries, [summary], limit=20)
        memory.question_catalog = self._merge_items(memory.question_catalog, [turn.question for turn in session.turns], limit=100)
        memory.weak_topics = self._merge_items(memory.weak_topics, weaknesses, limit=30)
        memory.notes = self._merge_items(memory.notes, strengths, limit=30)
        memory.finalized_session_ids = self._merge_items(memory.finalized_session_ids, [session.session_id], limit=100)
        memory.updated_at = datetime.now(timezone.utc)
        try:
            return await self._repository.save(memory, expected_version=expected_version)
        except ConsistencyError:
            latest = await self._repository.get(session.user_id)
            if latest is not None and session.session_id in latest.finalized_session_ids:
                return latest
            raise
```

逐行解释：

1. 读取记忆；不存在返回空，sessionId 已归档则幂等返回。
2. 保存版本，收集所有分数并计算四舍五入平均值；空轮次为 0。
3. 双层列表推导汇总所有弱项和优点。
4. summary 明确记录完成/中断、轮次数、平均分和最终摘要。
5. 将摘要、问题、弱项、优点和 sessionId 分别去重限长合并。
6. 更新时间并乐观保存；冲突后若最新记录已包含 sessionId，则视为并发幂等成功，否则继续抛错。

### 3.7 `record_resume_analysis`

文件：`python-agent/app/memory/service.py:177-234`

```python
    async def record_resume_analysis(
        self, *, user_id: str, resume_id: str, candidate_id: str, resume_text: str,
        target_role: str, summary: str, questions: list[str], priorities: list[str],
        suggestions: list[str], technical_stack: list[str], technical_depth: list[str],
        career_preferences: list[str], run_id: str | None = None,
        evaluation_fingerprint: str | None = None,
        evaluation: ResumeEvaluation | None = None,
    ) -> LongTermMemory | None:
        memory = await self._repository.get(user_id)
        if memory is None:
            return None
        existing_run = memory.resume_evaluation_runs.get(run_id) if run_id else None
        if existing_run is not None:
            if (existing_run.resume_id != resume_id
                    or existing_run.fingerprint != evaluation_fingerprint):
                raise ConsistencyError("同一 resume evaluation runId 不能提交不同的输入")
            return memory
        if memory.active_resume_id != resume_id:
            return None
        expected_version = memory.state_version
        snapshots: list[ResumeMemory] = []
        matched_resume = False
        for snapshot in memory.resume_snapshots:
            if snapshot.resume_id == resume_id:
                matched_resume = True
                snapshot = snapshot.model_copy(update={
                    "analysis_summary": summary,
                    "analysis_questions": questions[:20],
                    "analysis_priorities": priorities[:20],
                    "analysis_suggestions": suggestions[:20],
                    "updated_at": datetime.now(timezone.utc),
                })
            snapshots.append(snapshot)
        if not matched_resume:
            snapshots.append(ResumeMemory(
                resume_id=resume_id, candidate_id=candidate_id, target_role=target_role,
                resume_text=resume_text, analysis_summary=summary,
                analysis_questions=questions[:20], analysis_priorities=priorities[:20],
                analysis_suggestions=suggestions[:20],
            ))
        memory.resume_snapshots = snapshots[: self._policy.max_resume_snapshots]
        memory.technical_stack = self._unique_items(technical_stack, limit=30)
        memory.technical_depth = self._unique_items(technical_depth, limit=30)
        memory.notes = self._unique_items(suggestions, limit=30)
        memory.preferences = self._unique_items(career_preferences, limit=30)
        if run_id and evaluation_fingerprint and evaluation is not None:
            memory.resume_evaluation_runs[run_id] = ResumeEvaluationRun(
                run_id=run_id, resume_id=resume_id,
                fingerprint=evaluation_fingerprint, evaluation=evaluation,
            )
            while len(memory.resume_evaluation_runs) > self._policy.max_resume_evaluation_runs:
                memory.resume_evaluation_runs.pop(next(iter(memory.resume_evaluation_runs)))
        memory.updated_at = datetime.now(timezone.utc)
        return await self._repository.save(memory, expected_version=expected_version)
```

逐行解释：

1. 参数区接收简历原始信息、结构化评价列表和可选幂等快照。
2. 读取记忆；不存在不创建。runId 命中时必须同时匹配 resumeId 和指纹，否则报错；匹配则幂等返回。
3. active_resume_id 不等于结果所属简历时返回 `None`，阻止旧异步评价覆盖新简历。
4. 保存版本并遍历快照；命中 resumeId 时用 `model_copy` 替换分析字段，每类最多 20 项。
5. 没有匹配快照时补建完整 `ResumeMemory`。
6. 快照列表按策略截断；四类画像使用替换语义和 `_unique_items`，不与旧评价无限累积。
7. runId、指纹、评价均存在时保存完整可重放评价，并按策略淘汰最旧运行。
8. 更新时间，按期望版本保存。

### 3.8 `get_resume_evaluation_run`

文件：`python-agent/app/memory/service.py:236-248`

```python
    async def get_resume_evaluation_run(
        self, *, user_id: str, resume_id: str, run_id: str, evaluation_fingerprint: str
    ):
        memory = await self._repository.get(user_id)
        if memory is None:
            return None
        existing_run = memory.resume_evaluation_runs.get(run_id)
        if existing_run is None:
            return None
        if (existing_run.resume_id != resume_id
                or existing_run.fingerprint != evaluation_fingerprint):
            raise ConsistencyError("同一 resume evaluation runId 不能提交不同的输入")
        return existing_run.evaluation
```

逐行解释：读取用户记忆和 run；任一不存在返回空。命中后必须核对简历 ID 与输入指纹，不同输入报错，相同则返回此前完整 `ResumeEvaluation`。

### 3.9 `_merge_resume_snapshot`

文件：`python-agent/app/memory/service.py:250-252`

```python
    def _merge_resume_snapshot(self, snapshots: list[ResumeMemory], incoming: ResumeMemory) -> list[ResumeMemory]:
        remaining = [item for item in snapshots if item.resume_id != incoming.resume_id]
        return [incoming, *remaining][: self._policy.max_resume_snapshots]
```

逐行解释：先移除同 resumeId 旧快照，再把新快照放首位，最后按策略上限截断。

### 3.10 `_append_summary`

文件：`python-agent/app/memory/service.py:254-255`

```python
    def _append_summary(self, current: str, event: str) -> str:
        return f"{current}\n{event}".strip()[-self._policy.history_summary_max_characters :]
```

逐行解释：把旧摘要和新事件换行拼接、去两端空白，只保留策略允许的最后若干字符，使最新事件不会被截掉。

### 3.11 `_resume_activation_fingerprint`

文件：`python-agent/app/memory/service.py:258-268`

```python
    @staticmethod
    def _resume_activation_fingerprint(
        *, resume_id: str, candidate_id: str, resume_text: str, target_role: str
    ) -> str:
        canonical = json.dumps({
            "resumeId": resume_id,
            "candidateId": candidate_id,
            "resumeText": resume_text,
            "targetRole": target_role,
        }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()
```

逐行解释：静态函数把四个输入按排序键和紧凑 JSON 规范化，保留 Unicode，UTF-8 编码后返回 SHA-256 十六进制摘要。

### 3.12 `_merge_items`

文件：`python-agent/app/memory/service.py:270-273`

```python
    @staticmethod
    def _merge_items(current: list[str], incoming: list[str], *, limit: int) -> list[str]:
        merged = [item.strip() for item in [*current, *incoming] if item and item.strip()]
        return list(dict.fromkeys(merged))[-limit:]
```

逐行解释：合并旧、新列表，去除空值并 trim；有序字典去重后保留最后 `limit` 项，因此新信息优先保留。

### 3.13 `_unique_items`

文件：`python-agent/app/memory/service.py:275-278`

```python
    @staticmethod
    def _unique_items(items: list[str], *, limit: int) -> list[str]:
        values = [item.strip() for item in items if item and item.strip()]
        return list(dict.fromkeys(values))[:limit]
```

逐行解释：清洗单个输入列表，有序去重后保留前 `limit` 项，用于简历评价的替换语义。

### 3.14 `MemoryContext.empty`

文件：`python-agent/app/memory/models.py:82-94`

```python
    @classmethod
    def empty(cls, session: InterviewSession) -> "MemoryContext":
        return cls(
            recent_turns=[],
            conversation_summary=getattr(session, "history_summary", ""),
            historical_summary="",
            active_resume=None,
            technical_stack=[],
            technical_depth=[],
            preferences=[],
            weak_topics=[],
            notes=[],
            question_catalog=[],
        )
```

逐行解释：类方法从会话创建空上下文；保留会话自身的压缩摘要，其余长期记忆字段使用空值，避免用 `None` 迫使 Agent 分支处理列表。

### 3.15 `to_resume_memory`

文件：`python-agent/app/memory/models.py:97-105`

```python
def to_resume_memory(profile: CandidateProfile) -> ResumeMemory:
    return ResumeMemory(
        resume_id=profile.resume_id,
        candidate_id=profile.candidate_id,
        target_role=profile.target_role,
        resume_text=profile.resume_text,
        jd_id=profile.jd_id,
        jd_text=profile.jd_text,
    )
```

逐行解释：定义资料到记忆快照的纯转换；逐项复制简历 ID、候选人、岗位、简历正文、JD ID 和 JD 正文，不加入评价派生字段。

## 4. 审核结论

MemoryService 的构造、五条业务写链、上下文读取、六个辅助函数和两个模型转换函数均已附源码。并发冲突、异步旧简历覆盖和重复 run/turn/session 的处理均忠于当前实现。
