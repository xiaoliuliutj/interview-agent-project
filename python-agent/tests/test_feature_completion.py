from types import SimpleNamespace

import pytest

from app.agents.interview.models import InterviewAction, InterviewStage, TurnRecord
from app.memory.models import LongTermMemory
from app.memory.policy import MemoryPolicy
from app.memory.service import MemoryService


class MemoryRepository:
    def __init__(self) -> None:
        self.items: dict[str, LongTermMemory] = {}

    async def get(self, user_id: str):
        item = self.items.get(user_id)
        return item.model_copy(deep=True) if item else None

    async def create(self, memory: LongTermMemory):
        self.items[memory.user_id] = memory.model_copy(deep=True)
        return memory.model_copy(deep=True)

    async def save(self, memory: LongTermMemory, *, expected_version: int):
        saved = memory.model_copy(update={"state_version": expected_version + 1}, deep=True)
        self.items[memory.user_id] = saved
        return saved.model_copy(deep=True)


@pytest.mark.asyncio
async def test_finalized_memory_keeps_catalog_weakness_preferences_and_summary() -> None:
    repository = MemoryRepository()
    service = MemoryService(repository, MemoryPolicy(3, 2000, 3))
    await repository.create(LongTermMemory(user_id="user-1"))
    turn = TurnRecord(
        stage=InterviewStage.FUNDAMENTAL,
        question="Explain a cache consistency strategy",
        candidate_answer="Use delayed double delete",
        action=InterviewAction.NEXT_QUESTION,
        evaluation_summary="Missing failure compensation",
        score=62,
        answer_summary="Explained delayed double delete",
        strengths=["Knows cache patterns"],
        weaknesses=["Consistency compensation"],
        preferences=["Prefers project examples"],
    )
    session = SimpleNamespace(
        user_id="user-1", session_id="session-1", turns=[turn],
        final_summary="Good cache basics, needs compensation design."
    )
    await service.record_turn(session=session, turn=turn)
    memory = await service.finalize_session(session=session)

    assert memory is not None
    assert "Explain a cache consistency strategy" in memory.question_catalog
    assert "Consistency compensation" in memory.weak_topics
    assert "Prefers project examples" in memory.preferences
    assert memory.interview_summaries
