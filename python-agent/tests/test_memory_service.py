from types import SimpleNamespace

import pytest

from app.agent.interview.models import (
    CandidateProfile,
    InterviewAction,
    InterviewStage,
    TurnRecord,
)
from app.agent.memory.models import LongTermMemory
from app.agent.memory.policy import MemoryPolicy
from app.agent.memory.service import MemoryService
from app.core.exceptions import ConsistencyError


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


def build_service() -> MemoryService:
    return MemoryService(
        InMemoryLongTermMemoryRepository(),
        MemoryPolicy(
            short_term_turn_limit=5,
            history_summary_max_characters=2000,
            max_resume_snapshots=3,
        ),
    )


def build_turn(index: int) -> TurnRecord:
    return TurnRecord(
        stage=InterviewStage.FUNDAMENTAL,
        question=f"问题 {index}",
        candidate_answer=f"回答 {index}",
        action=InterviewAction.NEXT_QUESTION,
        evaluation_summary=f"评价 {index}",
    )


@pytest.mark.asyncio
async def test_memory_keeps_five_recent_turns_and_user_resume_snapshot() -> None:
    service = build_service()
    profile = CandidateProfile(
        candidate_id="candidate-1",
        resume_id="resume-1",
        target_role="Java 后端",
        resume_text="有 Redis 项目经验",
    )
    await service.initialize_user_memory(user_id="user-1", profile=profile)

    session = SimpleNamespace(
        user_id="user-1",
        session_id="session-1",
        resume_id="resume-1",
        turns=[build_turn(index) for index in range(6)],
    )
    context = await service.build_context(session)

    assert [turn.question for turn in context.recent_turns] == [
        "问题 1",
        "问题 2",
        "问题 3",
        "问题 4",
        "问题 5",
    ]
    assert context.active_resume is not None
    assert context.active_resume.resume_text == "有 Redis 项目经验"


@pytest.mark.asyncio
async def test_memory_appends_evaluation_to_user_history_summary() -> None:
    service = build_service()
    profile = CandidateProfile(
        candidate_id="candidate-1",
        resume_id="resume-1",
        target_role="Java 后端",
    )
    await service.initialize_user_memory(user_id="user-1", profile=profile)
    session = SimpleNamespace(user_id="user-1", session_id="session-1")

    await service.record_turn(session=session, turn=build_turn(1))
    context = await service.build_context(
        SimpleNamespace(
            user_id="user-1", session_id="session-1", resume_id="resume-1", turns=[]
        )
    )

    assert "评价 1" in context.historical_summary
