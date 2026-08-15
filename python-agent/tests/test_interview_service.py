import asyncio
from collections import deque

import pytest
from types import SimpleNamespace

from app.agents.interview.models import (
    CandidateProfile,
    Difficulty,
    InterviewAction,
    InterviewEvaluation,
    InterviewPlan,
    InterviewRoute,
    InterviewSession,
    InterviewStage,
    StagePlan,
)
from app.agents.interview.service import InterviewAgentService
from app.agents.interview.workflow import InterviewWorkflow
from app.memory.models import LongTermMemory
from app.memory.policy import MemoryPolicy
from app.memory.service import MemoryService
from app.rag.models import KnowledgeChunk, RagSearchResult
from app.common.contracts import SessionStatus
from app.common.exceptions import AgentDependencyError, ConsistencyError
from app.common.prompt_loader import PromptLoader


class InMemorySessionRepository:
    def __init__(self) -> None:
        self.sessions: dict[str, InterviewSession] = {}

    async def create(self, session: InterviewSession) -> InterviewSession:
        if session.session_id in self.sessions:
            raise ConsistencyError("Agent 会话已存在")
        self.sessions[session.session_id] = session.model_copy(deep=True)
        return session.model_copy(deep=True)

    async def get(self, session_id: str) -> InterviewSession | None:
        session = self.sessions.get(session_id)
        return session.model_copy(deep=True) if session else None

    async def save(self, session: InterviewSession, *, expected_version: int) -> InterviewSession:
        existing = self.sessions.get(session.session_id)
        if existing is None or existing.state_version != expected_version:
            raise ConsistencyError("Agent 会话状态已被并发修改")
        saved = session.model_copy(update={"state_version": expected_version + 1}, deep=True)
        self.sessions[session.session_id] = saved
        return saved.model_copy(deep=True)


class InMemoryLongTermMemoryRepository:
    def __init__(self) -> None:
        self.memories: dict[str, LongTermMemory] = {}

    async def get(self, user_id: str) -> LongTermMemory | None:
        memory = self.memories.get(user_id)
        return memory.model_copy(deep=True) if memory else None

    async def create(self, memory: LongTermMemory) -> LongTermMemory:
        self.memories[memory.user_id] = memory.model_copy(deep=True)
        return memory.model_copy(deep=True)

    async def save(self, memory: LongTermMemory, *, expected_version: int) -> LongTermMemory:
        existing = self.memories.get(memory.user_id)
        if existing is None or existing.state_version != expected_version:
            raise ConsistencyError("长期记忆已被并发修改")
        saved = memory.model_copy(update={"state_version": expected_version + 1}, deep=True)
        self.memories[memory.user_id] = saved
        return saved.model_copy(deep=True)


class StaticPlanner:
    async def create_plan(self, profile: CandidateProfile) -> InterviewPlan:
        return build_plan()


class QueueEvaluationAgent:
    def __init__(self, evaluations: list[InterviewEvaluation], events: list[str]) -> None:
        self.evaluations = deque(evaluations)
        self.events = events
        self.evidence_seen: list[list[dict[str, object]]] = []

    async def evaluate(self, session, candidate_answer, memory_context) -> InterviewEvaluation:
        self.events.append("evaluate")
        self.evidence_seen.append(session.current_question_evidence)
        return self.evaluations.popleft()


class QueueRoutingAgent:
    def __init__(self, routes: list[InterviewRoute], events: list[str]) -> None:
        self.routes = deque(routes)
        self.events = events
        self.evaluations_seen: list[InterviewEvaluation] = []

    async def route(self, session, evaluation, allowed_actions, next_stage_name, memory_context) -> InterviewRoute:
        self.events.append("route")
        self.evaluations_seen.append(evaluation)
        return self.routes.popleft()


class StaticQuestionAgent:
    def __init__(self, events: list[str]) -> None:
        self.events = events
        self.short_term_turn_counts: list[int] = []

    async def generate(self, session, route, evidence, memory_context) -> str:
        self.events.append("question")
        self.short_term_turn_counts.append(len(memory_context.recent_turns))
        return f"{route.next_topic} 的具体问题"


class RecordingRagTool:
    def __init__(self, events: list[str]) -> None:
        self.events = events
        self.calls: list[tuple[str, tuple[str, ...]]] = []

    async def search_for_question_generation(self, query: str, *, knowledge_base_ids: tuple[str, ...]):
        self.events.append("rag")
        self.calls.append((query, knowledge_base_ids))
        return [RagSearchResult(chunk=KnowledgeChunk(
            chunk_id="chunk-1", knowledge_base_id="system-kb", document_id="doc-1",
            source_name="system.md", chunk_index=0, content="retrieved material",
        ), score=0.9)]


class LowScoreRagTool:
    async def search_for_question_generation(self, query: str, *, knowledge_base_ids: tuple[str, ...]):
        return [RagSearchResult(chunk=KnowledgeChunk(
            chunk_id="chunk-low", knowledge_base_id="system-kb", document_id="doc-low",
            source_name="system.md", chunk_index=0, content="thin local evidence",
        ), score=0.2)]


class RecordingWebEvidenceTool:
    def __init__(self) -> None:
        self.calls: list[str] = []

    async def search_for_question_generation(self, topic: str):
        self.calls.append(topic)
        return [SimpleNamespace(
            markdown="# Redis\n\npublic technical evidence", url="https://redis.io/docs",
            title="Redis docs", fetched_at="2026-08-12T00:00:00Z", content_hash="hash",
        )]


class HangingRagTool:
    async def search_for_question_generation(self, query: str, *, knowledge_base_ids: tuple[str, ...]):
        await asyncio.sleep(10)


class HangingWebEvidenceTool:
    async def search_for_question_generation(self, topic: str):
        await asyncio.sleep(10)


class HangingEvaluationAgent:
    async def evaluate(self, session, candidate_answer, memory_context):
        await asyncio.sleep(10)


class HangingPlanner:
    async def create_plan(self, profile: CandidateProfile) -> InterviewPlan:
        await asyncio.sleep(10)


def build_plan() -> InterviewPlan:
    return InterviewPlan(
        candidate_summary="测试候选人", strategy_summary="测试计划",
        stages=[
            StagePlan(stage=InterviewStage.OPENING, max_primary_questions=1, max_followups_per_question=0, difficulty=Difficulty.EASY, topics=["自我介绍"], time_budget_minutes=2),
            StagePlan(stage=InterviewStage.PROJECT, max_primary_questions=4, max_followups_per_question=2, difficulty=Difficulty.MEDIUM, topics=["项目"], time_budget_minutes=8),
            StagePlan(stage=InterviewStage.FUNDAMENTAL, max_primary_questions=4, max_followups_per_question=2, difficulty=Difficulty.MEDIUM, topics=["Java"], time_budget_minutes=10),
            StagePlan(stage=InterviewStage.SCENARIO, max_primary_questions=4, max_followups_per_question=2, difficulty=Difficulty.MEDIUM, topics=["一致性"], time_budget_minutes=8),
            StagePlan(stage=InterviewStage.CODING, max_primary_questions=2, max_followups_per_question=0, difficulty=Difficulty.MEDIUM, topics=["算法"], time_budget_minutes=10),
            StagePlan(stage=InterviewStage.SUMMARY, max_primary_questions=1, max_followups_per_question=0, difficulty=Difficulty.EASY, topics=["总结"], time_budget_minutes=2),
        ],
    )


def evaluation(score: int = 80, summary: str = "回答完整") -> InterviewEvaluation:
    return InterviewEvaluation(
        score=score,
        evaluation_summary=summary,
        answer_summary="候选人回答摘要",
    )


def build_service(repository, evaluations, routes, events, rag_tool=None, web_evidence_tool=None):
    prompt_loader = PromptLoader()
    memory_service = MemoryService(
        InMemoryLongTermMemoryRepository(),
        MemoryPolicy(short_term_turn_limit=5, history_summary_max_characters=2000, max_resume_snapshots=3),
    )
    evaluation_agent = QueueEvaluationAgent(evaluations, events)
    routing_agent = QueueRoutingAgent(routes, events)
    question_agent = StaticQuestionAgent(events)
    service = InterviewAgentService(
        StaticPlanner(), evaluation_agent, routing_agent, question_agent, rag_tool,
        repository, InterviewWorkflow.load(prompt_loader), prompt_loader, memory_service,
        web_evidence_tool=web_evidence_tool,
    )
    return service, evaluation_agent, routing_agent, question_agent


def build_profile() -> CandidateProfile:
    return CandidateProfile(
        candidate_id="candidate-1", resume_id="resume-1", resume_text="候选人有 Redis 项目经验",
        jd_text="Java 后端岗位", target_role="Java 后端", interview_duration_minutes=30,
        desired_difficulty=Difficulty.MEDIUM, question_count=20, custom_categories=[],
        system_knowledge_base_ids=[], user_knowledge_base_ids=[],
    )


@pytest.mark.asyncio
async def test_evaluation_precedes_routing_and_question_generation() -> None:
    repository, events = InMemorySessionRepository(), []
    service, evaluator, router, question_agent = build_service(
        repository, [evaluation()], [InterviewRoute(action=InterviewAction.NEXT_STAGE, next_topic="项目架构")], events,
    )
    await service.initialize_session(user_id="user-1", session_id="session-1", profile=build_profile())
    result = await service.submit_answer_for_run(
        user_id="user-1", session_id="session-1", candidate_answer="我做过电商项目",
        run_id="run-1", expected_session_status=SessionStatus.ACTIVE,
        expected_state_version=0,
    )
    updated = result.session

    assert events == ["evaluate", "route", "question"]
    assert question_agent.short_term_turn_counts == [1]
    assert router.evaluations_seen[0].score == 80
    assert evaluator.evidence_seen == [[]]
    assert updated.current_stage == InterviewStage.PROJECT
    assert updated.current_question == "项目架构 的具体问题"
    assert updated.turns[0].score == 80
    assert result.snapshot.turn_stage == InterviewStage.OPENING


@pytest.mark.asyncio
async def test_follow_up_is_routed_after_evaluation() -> None:
    repository, events = InMemorySessionRepository(), []
    service, _, _, _ = build_service(
        repository,
        [evaluation(), evaluation(60, "技术细节不足")],
        [
            InterviewRoute(action=InterviewAction.NEXT_STAGE, next_topic="项目架构"),
            InterviewRoute(action=InterviewAction.FOLLOW_UP, next_topic="缓存一致性细节"),
        ],
        events,
    )
    await service.initialize_session(user_id="user-1", session_id="session-1", profile=build_profile())
    await service.submit_answer_for_run(
        user_id="user-1", session_id="session-1", candidate_answer="项目介绍",
        run_id="run-1", expected_session_status=SessionStatus.ACTIVE,
        expected_state_version=0,
    )
    updated = (await service.submit_answer_for_run(
        user_id="user-1", session_id="session-1", candidate_answer="用了 Redis",
        run_id="run-2", expected_session_status=SessionStatus.ACTIVE,
        expected_state_version=1,
    )).session

    assert updated.current_stage == InterviewStage.PROJECT
    assert updated.followup_count == 1
    assert updated.current_question == "项目架构 的具体问题"


@pytest.mark.asyncio
async def test_end_route_in_middle_stage_advances_instead_of_skipping_remaining_stages() -> None:
    repository, events = InMemorySessionRepository(), []
    service, _, _, _ = build_service(
        repository, [evaluation(), evaluation(), evaluation()],
        [
            InterviewRoute(action=InterviewAction.NEXT_STAGE, next_topic="项目架构"),
            InterviewRoute(action=InterviewAction.NEXT_QUESTION, next_topic="项目架构"),
            InterviewRoute(action=InterviewAction.END_INTERVIEW),
        ],
        events,
    )
    await service.initialize_session(user_id="user-1", session_id="session-1", profile=build_profile())
    await service.submit_answer_for_run(
        user_id="user-1", session_id="session-1", candidate_answer="开场回答",
        run_id="run-1", expected_session_status=SessionStatus.ACTIVE,
        expected_state_version=0,
    )

    await service.submit_answer_for_run(
        user_id="user-1", session_id="session-1", candidate_answer="结束面试",
        run_id="run-2", expected_session_status=SessionStatus.ACTIVE,
        expected_state_version=1,
    )

    updated = (await service.submit_answer_for_run(
        user_id="user-1", session_id="session-1", candidate_answer="确认结束",
        run_id="run-3", expected_session_status=SessionStatus.ACTIVE,
        expected_state_version=2,
    )).session

    assert updated.status == "ACTIVE"
    assert updated.current_stage == InterviewStage.FUNDAMENTAL
    assert updated.current_question == "Java 的具体问题"
    assert updated.final_evaluation is None
    assert events == ["evaluate", "route", "question", "evaluate", "route", "question", "evaluate", "route", "question"]


@pytest.mark.asyncio
async def test_completed_session_always_contains_a_candidate_visible_final_evaluation() -> None:
    repository, events = InMemorySessionRepository(), []
    service, _, _, _ = build_service(
        repository,
        [evaluation(72, "基础回答完整")],
        [InterviewRoute(action=InterviewAction.NEXT_STAGE, next_topic="项目架构")],
        events,
    )
    await service.initialize_session(user_id="user-1", session_id="session-1", profile=build_profile())
    await service.submit_answer_for_run(
        user_id="user-1", session_id="session-1", candidate_answer="开场回答",
        run_id="run-1", expected_session_status=SessionStatus.ACTIVE,
        expected_state_version=0,
    )

    completed = await service.complete_session(
        user_id="user-1", session_id="session-1",
        expected_session_status=SessionStatus.ACTIVE, expected_state_version=1,
    )

    assert completed.status == SessionStatus.COMPLETED
    assert completed.final_evaluation is not None
    assert completed.final_evaluation.overall_score == 72
    assert "综合表现评分" in completed.final_evaluation.summary


@pytest.mark.asyncio
async def test_duplicate_session_is_rejected() -> None:
    repository, events = InMemorySessionRepository(), []
    service, _, _, _ = build_service(repository, [], [], events)
    await service.initialize_session(user_id="user-1", session_id="session-1", profile=build_profile())
    with pytest.raises(ConsistencyError):
        await service.initialize_session(user_id="user-1", session_id="session-1", profile=build_profile())


@pytest.mark.asyncio
async def test_same_initialization_run_id_rejects_changed_profile() -> None:
    repository, events = InMemorySessionRepository(), []
    service, _, _, _ = build_service(repository, [], [], events)
    await service.initialize_session(
        user_id="user-1", session_id="session-1", profile=build_profile(), run_id="init-1"
    )
    changed = build_profile().model_copy(update={"target_role": "Python 后端"})

    with pytest.raises(ConsistencyError):
        await service.initialize_session(
            user_id="user-1", session_id="session-1", profile=changed, run_id="init-1"
        )


@pytest.mark.asyncio
async def test_same_run_id_returns_persisted_snapshot_without_reinvoking_agents() -> None:
    repository, events = InMemorySessionRepository(), []
    service, _, _, _ = build_service(
        repository, [evaluation()], [InterviewRoute(action=InterviewAction.NEXT_STAGE, next_topic="项目架构")], events,
    )
    await service.initialize_session(user_id="user-1", session_id="session-1", profile=build_profile(), run_id="init-1")
    first = await service.submit_answer_for_run(
        user_id="user-1", session_id="session-1", candidate_answer="项目介绍",
        run_id="run-1", expected_session_status=SessionStatus.ACTIVE,
        expected_state_version=0,
    )
    replay = await service.submit_answer_for_run(
        user_id="user-1", session_id="session-1", candidate_answer="项目介绍",
        run_id="run-1", expected_session_status=SessionStatus.ACTIVE,
        expected_state_version=0,
    )

    assert replay.snapshot == first.snapshot
    assert events == ["evaluate", "route", "question"]


@pytest.mark.asyncio
async def test_same_run_id_rejects_a_different_answer() -> None:
    repository, events = InMemorySessionRepository(), []
    service, _, _, _ = build_service(
        repository, [evaluation()],
        [InterviewRoute(action=InterviewAction.NEXT_STAGE, next_topic="项目架构")],
        events,
    )
    await service.initialize_session(
        user_id="user-1", session_id="session-1", profile=build_profile()
    )
    await service.submit_answer_for_run(
        user_id="user-1", session_id="session-1", candidate_answer="原始回答",
        run_id="run-1", expected_session_status=SessionStatus.ACTIVE,
        expected_state_version=0,
    )

    with pytest.raises(ConsistencyError):
        await service.submit_answer_for_run(
            user_id="user-1", session_id="session-1", candidate_answer="篡改后的回答",
            run_id="run-1", expected_session_status=SessionStatus.ACTIVE,
            expected_state_version=0,
        )

    assert events == ["evaluate", "route", "question"]


@pytest.mark.asyncio
async def test_new_run_rejects_stale_upper_layer_agent_state() -> None:
    repository, events = InMemorySessionRepository(), []
    service, _, _, _ = build_service(
        repository, [evaluation()],
        [InterviewRoute(action=InterviewAction.NEXT_STAGE, next_topic="项目架构")],
        events,
    )
    await service.initialize_session(
        user_id="user-1", session_id="session-1", profile=build_profile()
    )

    with pytest.raises(ConsistencyError):
        await service.submit_answer_for_run(
            user_id="user-1", session_id="session-1", candidate_answer="过期回答",
            run_id="stale-run", expected_session_status=SessionStatus.ACTIVE,
            expected_state_version=1,
        )

    assert events == []


@pytest.mark.asyncio
async def test_paused_session_can_resume_on_answer_without_reinitializing() -> None:
    repository, events = InMemorySessionRepository(), []
    service, _, _, _ = build_service(
        repository, [evaluation()],
        [InterviewRoute(action=InterviewAction.NEXT_STAGE, next_topic="项目架构")],
        events,
    )
    await service.initialize_session(user_id="user-1", session_id="session-1", profile=build_profile())
    paused = await service.pause_session(
        user_id="user-1", session_id="session-1",
        expected_session_status=SessionStatus.ACTIVE, expected_state_version=0,
    )
    assert paused.status == SessionStatus.PAUSED
    resumed = await service.submit_answer_for_run(
        user_id="user-1", session_id="session-1", candidate_answer="恢复后的回答",
        run_id="run-resume", expected_session_status=SessionStatus.PAUSED,
        expected_state_version=1,
    )
    assert resumed.session.status == SessionStatus.ACTIVE
    assert resumed.snapshot.state_version == 2


@pytest.mark.asyncio
async def test_rag_runs_only_after_routing_and_reuses_evidence_cache_for_same_topic() -> None:
    repository, events = InMemorySessionRepository(), []
    rag_tool = RecordingRagTool(events)
    service, evaluator, _, _ = build_service(
        repository,
        [evaluation(), evaluation(60, "需要继续深挖")],
        [
            InterviewRoute(action=InterviewAction.NEXT_STAGE, next_topic="Redis 缓存一致性"),
            InterviewRoute(action=InterviewAction.FOLLOW_UP, next_topic=" redis 缓存一致性 "),
        ],
        events,
        rag_tool,
    )
    profile = build_profile().model_copy(update={"system_knowledge_base_ids": ["system-kb"], "user_knowledge_base_ids": ["user-kb"]})
    await service.initialize_session(user_id="user-1", session_id="session-1", profile=profile)
    await service.submit_answer_for_run(
        user_id="user-1", session_id="session-1", candidate_answer="开场回答",
        run_id="run-1", expected_session_status=SessionStatus.ACTIVE,
        expected_state_version=0,
    )
    await service.submit_answer_for_run(
        user_id="user-1", session_id="session-1", candidate_answer="项目回答",
        run_id="run-2", expected_session_status=SessionStatus.ACTIVE,
        expected_state_version=1,
    )

    assert events == ["evaluate", "route", "rag", "question", "evaluate", "route", "question"]
    assert rag_tool.calls == [("Redis 缓存一致性", ("system-kb", "user-kb"))]
    assert evaluator.evidence_seen == [[], [{"content": "retrieved material", "score": 0.9, "knowledgeBaseId": "system-kb"}]]


@pytest.mark.asyncio
async def test_insufficient_rag_falls_back_to_web_and_caches_result() -> None:
    repository, events = InMemorySessionRepository(), []
    web_tool = RecordingWebEvidenceTool()
    service, _, _, _ = build_service(
        repository, [evaluation()], [], events, LowScoreRagTool(), web_tool
    )
    profile = build_profile().model_copy(update={"system_knowledge_base_ids": ["system-kb"]})
    session = await service.initialize_session(user_id="user-1", session_id="session-web", profile=profile)
    route = InterviewRoute(action=InterviewAction.NEXT_STAGE, next_topic="Redis caching")

    first = await service._question_evidence(session, route)
    second = await service._question_evidence(session, route)

    assert web_tool.calls == ["Redis caching"]
    assert first == second
    assert first[-1]["sourceType"] == "WEB"
    assert first[-1]["sourceUrl"] == "https://redis.io/docs"


@pytest.mark.asyncio
async def test_slow_optional_evidence_does_not_block_question_generation(monkeypatch) -> None:
    repository, events = InMemorySessionRepository(), []
    monkeypatch.setattr("app.agents.interview.service.INTERVIEW_RAG_TIMEOUT_SECONDS", 0.01)
    monkeypatch.setattr("app.agents.interview.service.INTERVIEW_WEB_TIMEOUT_SECONDS", 0.01)
    service, _, _, _ = build_service(
        repository, [evaluation()], [], events, HangingRagTool(), HangingWebEvidenceTool()
    )
    profile = build_profile().model_copy(update={"system_knowledge_base_ids": ["system-kb"]})
    session = await service.initialize_session(user_id="user-1", session_id="session-slow", profile=profile)
    route = InterviewRoute(action=InterviewAction.NEXT_STAGE, next_topic="Redis caching")

    evidence = await service._question_evidence(session, route)

    assert evidence == []
    assert service.progress_for("session-slow") == "WEB_RETRIEVING"


@pytest.mark.asyncio
async def test_slow_evaluation_fails_promptly_and_keeps_failed_progress(monkeypatch) -> None:
    repository, events = InMemorySessionRepository(), []
    monkeypatch.setattr(
        "app.agents.interview.service.INTERVIEW_MODEL_NODE_TIMEOUT_SECONDS", 0.01
    )
    service, _, _, _ = build_service(repository, [], [], events)
    service._evaluation_agent = HangingEvaluationAgent()
    await service.initialize_session(user_id="user-1", session_id="session-timeout", profile=build_profile())

    with pytest.raises(AgentDependencyError):
        await service.submit_answer_for_run(
            user_id="user-1", session_id="session-timeout", candidate_answer="answer",
            run_id="run-timeout", expected_session_status=SessionStatus.ACTIVE,
            expected_state_version=0,
        )

    assert service.progress_for("session-timeout") == "FAILED"


@pytest.mark.asyncio
async def test_slow_planning_fails_promptly_and_keeps_failed_progress(monkeypatch) -> None:
    repository, events = InMemorySessionRepository(), []
    monkeypatch.setattr(
        "app.agents.interview.service.INTERVIEW_MODEL_NODE_TIMEOUT_SECONDS", 0.01
    )
    service, _, _, _ = build_service(repository, [], [], events)
    service._planner = HangingPlanner()

    with pytest.raises(AgentDependencyError):
        await service.initialize_session(
            user_id="user-1", session_id="session-planning-timeout", profile=build_profile()
        )

    assert service.progress_for("session-planning-timeout") == "FAILED"


@pytest.mark.asyncio
async def test_completion_clears_session_evidence_cache() -> None:
    repository, events = InMemorySessionRepository(), []
    service, _, _, _ = build_service(repository, [evaluation()], [], events)
    session = await service.initialize_session(user_id="user-1", session_id="session-cache", profile=build_profile())
    session.rag_evidence_cache["topic"] = [{"content": "temporary"}]
    await repository.save(session, expected_version=0)

    completed = await service.complete_session(
        user_id="user-1", session_id="session-cache", expected_session_status=SessionStatus.ACTIVE,
        expected_state_version=1,
    )
    assert completed.rag_evidence_cache == {}
