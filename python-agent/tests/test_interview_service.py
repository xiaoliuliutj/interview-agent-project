from collections import deque

import pytest

from app.agent.interview.models import (
    CandidateProfile,
    Difficulty,
    InterviewAction,
    InterviewDecision,
    InterviewPlan,
    InterviewSession,
    InterviewStage,
    StagePlan,
)
from app.agent.interview.service import InterviewAgentService
from app.agent.interview.workflow import InterviewWorkflow
from app.agent.memory.models import LongTermMemory, MemoryContext
from app.agent.memory.policy import MemoryPolicy
from app.agent.memory.service import MemoryService
from app.core.exceptions import ConsistencyError
from app.core.prompt_loader import PromptLoader


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

    async def save(
        self, session: InterviewSession, *, expected_version: int
    ) -> InterviewSession:
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

    async def save(
        self, memory: LongTermMemory, *, expected_version: int
    ) -> LongTermMemory:
        existing = self.memories.get(memory.user_id)
        if existing is None or existing.state_version != expected_version:
            raise ConsistencyError("长期记忆已被并发修改")
        saved = memory.model_copy(
            update={"state_version": expected_version + 1}, deep=True
        )
        self.memories[memory.user_id] = saved
        return saved.model_copy(deep=True)


class StaticPlanner:
    def __init__(self, plan: InterviewPlan) -> None:
        self.plan = plan

    async def create_plan(self, profile: CandidateProfile) -> InterviewPlan:
        return self.plan


class QueueDecisionAgent:
    def __init__(self, decisions: list[InterviewDecision]) -> None:
        self.decisions = deque(decisions)

    async def decide(self, *args, **kwargs) -> InterviewDecision:
        return self.decisions.popleft()


def build_plan() -> InterviewPlan:
    return InterviewPlan(
        candidate_summary="测试候选人",
        strategy_summary="测试计划",
        stages=[
            StagePlan(
                stage=InterviewStage.OPENING,
                max_primary_questions=1,
                max_followups_per_question=0,
                difficulty=Difficulty.EASY,
                topics=["自我介绍"],
                time_budget_minutes=2,
            ),
            StagePlan(
                stage=InterviewStage.PROJECT,
                max_primary_questions=1,
                max_followups_per_question=1,
                difficulty=Difficulty.MEDIUM,
                topics=["项目"],
                time_budget_minutes=8,
            ),
            StagePlan(
                stage=InterviewStage.FUNDAMENTAL,
                max_primary_questions=2,
                max_followups_per_question=1,
                difficulty=Difficulty.MEDIUM,
                topics=["Java"],
                time_budget_minutes=10,
            ),
            StagePlan(
                stage=InterviewStage.SCENARIO,
                max_primary_questions=1,
                max_followups_per_question=1,
                difficulty=Difficulty.MEDIUM,
                topics=["一致性"],
                time_budget_minutes=8,
            ),
            StagePlan(
                stage=InterviewStage.CODING,
                max_primary_questions=1,
                max_followups_per_question=0,
                difficulty=Difficulty.MEDIUM,
                topics=["算法"],
                time_budget_minutes=10,
            ),
            StagePlan(
                stage=InterviewStage.SUMMARY,
                max_primary_questions=1,
                max_followups_per_question=0,
                difficulty=Difficulty.EASY,
                topics=["总结"],
                time_budget_minutes=2,
            ),
        ],
    )


def build_service(
    repository: InMemorySessionRepository,
    decisions: list[InterviewDecision],
) -> InterviewAgentService:
    prompt_loader = PromptLoader()
    memory_service = MemoryService(
        InMemoryLongTermMemoryRepository(),
        MemoryPolicy(
            short_term_turn_limit=5,
            history_summary_max_characters=2000,
            max_resume_snapshots=3,
        ),
    )
    return InterviewAgentService(
        StaticPlanner(build_plan()),
        QueueDecisionAgent(decisions),
        repository,
        InterviewWorkflow.load(prompt_loader),
        prompt_loader,
        memory_service,
    )


def build_profile() -> CandidateProfile:
    return CandidateProfile(
        candidate_id="candidate-1",
        resume_id="resume-1",
        target_role="Java 后端",
    )


@pytest.mark.asyncio
async def test_initialize_and_advance_from_opening_to_project() -> None:
    repository = InMemorySessionRepository()
    service = build_service(
        repository,
        [
            InterviewDecision(
                action=InterviewAction.NEXT_STAGE,
                next_message="请介绍一个你最熟悉的项目。",
                evaluation_summary="自我介绍完整。",
            )
        ],
    )

    created = await service.initialize_session(
        user_id="user-1",
        session_id="session-1",
        profile=build_profile(),
    )
    updated = await service.submit_answer(
        user_id="user-1",
        session_id="session-1",
        candidate_answer="我有一个电商项目。",
    )

    assert created.current_stage == InterviewStage.OPENING
    assert updated.current_stage == InterviewStage.PROJECT
    assert updated.current_question == "请介绍一个你最熟悉的项目。"
    assert updated.state_version == 1
    assert len(updated.turns) == 1


@pytest.mark.asyncio
async def test_complete_session_is_idempotent_and_keeps_agent_history() -> None:
    repository = InMemorySessionRepository()
    service = build_service(repository, [])
    await service.initialize_session(
        user_id="user-1", session_id="session-1", profile=build_profile()
    )

    completed = await service.complete_session(
        user_id="user-1", session_id="session-1"
    )
    retried = await service.complete_session(
        user_id="user-1", session_id="session-1"
    )

    assert completed.status == "COMPLETED"
    assert completed.state_version == 1
    assert retried.state_version == 1
    assert await repository.get("session-1") is not None


@pytest.mark.asyncio
async def test_follow_up_is_persisted_in_current_stage() -> None:
    repository = InMemorySessionRepository()
    service = build_service(
        repository,
        [
            InterviewDecision(
                action=InterviewAction.NEXT_STAGE,
                next_message="请介绍一个你最熟悉的项目。",
                evaluation_summary="开场完成。",
            ),
            InterviewDecision(
                action=InterviewAction.FOLLOW_UP,
                next_message="这个项目中最难解决的问题是什么？",
                evaluation_summary="项目描述缺少技术细节。",
            ),
        ],
    )
    profile = build_profile()
    await service.initialize_session(
        user_id="user-1", session_id="session-1", profile=profile
    )
    await service.submit_answer(
        user_id="user-1", session_id="session-1", candidate_answer="项目介绍"
    )
    updated = await service.submit_answer(
        user_id="user-1", session_id="session-1", candidate_answer="用了 Redis"
    )

    assert updated.current_stage == InterviewStage.PROJECT
    assert updated.followup_count == 1
    assert updated.current_question == "这个项目中最难解决的问题是什么？"
    assert updated.state_version == 2


@pytest.mark.asyncio
async def test_duplicate_session_is_rejected() -> None:
    repository = InMemorySessionRepository()
    service = build_service(repository, [])
    profile = build_profile()
    await service.initialize_session(
        user_id="user-1", session_id="session-1", profile=profile
    )

    with pytest.raises(ConsistencyError):
        await service.initialize_session(
            user_id="user-1", session_id="session-1", profile=profile
        )


@pytest.mark.asyncio
async def test_same_run_id_returns_the_persisted_snapshot_without_reinvoking_agent() -> None:
    repository = InMemorySessionRepository()
    service = build_service(
        repository,
        [
            InterviewDecision(
                action=InterviewAction.NEXT_STAGE,
                next_message="请介绍一个你最熟悉的项目。",
                evaluation_summary="开场完成。",
            )
        ],
    )
    await service.initialize_session(
        user_id="user-1", session_id="session-1", profile=build_profile(), run_id="init-1"
    )

    first = await service.submit_answer_for_run(
        user_id="user-1",
        session_id="session-1",
        candidate_answer="项目介绍",
        run_id="run-1",
    )
    replay = await service.submit_answer_for_run(
        user_id="user-1",
        session_id="session-1",
        candidate_answer="重复提交",
        run_id="run-1",
    )

    assert replay.snapshot == first.snapshot
    assert replay.session.state_version == first.session.state_version
