from types import SimpleNamespace

import pytest

from app.agents.interview.models import (
    CandidateProfile,
    Difficulty,
    InterviewAction,
    InterviewStage,
    TurnRecord,
)
from app.memory.models import LongTermMemory
from app.memory.policy import MemoryPolicy
from app.memory.service import MemoryService
from app.common.exceptions import ConsistencyError
from app.agents.evaluation.models import ResumeEvaluation


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
        score=70,
        answer_summary=f"回答摘要 {index}",
    )


@pytest.mark.asyncio
async def test_memory_keeps_five_recent_turns_and_user_resume_snapshot() -> None:
    service = build_service()
    profile = CandidateProfile(
        candidate_id="candidate-1",
        resume_id="resume-1",
        resume_text="有 Redis 项目经验",
        jd_text="Java 后端岗位",
        target_role="Java 后端",
        interview_duration_minutes=30,
        desired_difficulty=Difficulty.MEDIUM,
        question_count=6,
        custom_categories=[],
        system_knowledge_base_ids=[],
        user_knowledge_base_ids=[],
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
        resume_text="有 Redis 项目经验",
        jd_text="Java 后端岗位",
        target_role="Java 后端",
        interview_duration_minutes=30,
        desired_difficulty=Difficulty.MEDIUM,
        question_count=6,
        custom_categories=[],
        system_knowledge_base_ids=[],
        user_knowledge_base_ids=[],
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


@pytest.mark.asyncio
async def test_old_resume_evaluation_cannot_overwrite_newly_activated_profile() -> None:
    service = build_service()
    await service.activate_resume(
        user_id="user-1", resume_id="resume-old", candidate_id="candidate-1",
        resume_text="旧简历", target_role="Java 后端",
    )
    await service.activate_resume(
        user_id="user-1", resume_id="resume-new", candidate_id="candidate-1",
        resume_text="新简历", target_role="Java 后端",
    )

    stale = await service.record_resume_analysis(
        user_id="user-1", resume_id="resume-old", candidate_id="candidate-1",
        resume_text="旧简历", target_role="Java 后端", summary="旧画像",
        questions=[], priorities=[], suggestions=[], technical_stack=["OldStack"],
        technical_depth=["旧深度"], career_preferences=["旧偏好"],
    )
    current = await service.record_resume_analysis(
        user_id="user-1", resume_id="resume-new", candidate_id="candidate-1",
        resume_text="新简历", target_role="Java 后端", summary="新画像",
        questions=[], priorities=[], suggestions=[], technical_stack=["Java"],
        technical_depth=["并发"], career_preferences=["后端"],
    )

    assert stale is None
    assert current is not None
    context = await service.build_context(SimpleNamespace(
        user_id="user-1", session_id="session-1", resume_id="resume-new", turns=[]
    ))
    assert context.active_resume is not None
    assert context.active_resume.analysis_summary == "新画像"
    assert context.technical_stack == ["Java"]
    assert "OldStack" not in context.technical_stack


@pytest.mark.asyncio
async def test_resume_evaluation_run_is_idempotent_and_rejects_changed_input() -> None:
    service = build_service()
    await service.activate_resume(
        user_id="user-1", resume_id="resume-1", candidate_id="candidate-1",
        resume_text="Java", target_role="Java 后端",
    )
    evaluation = ResumeEvaluation(
        overallScore=80, contentScore=80, structureScore=80,
        skillMatchScore=80, expressionScore=80, projectScore=80,
        summary="summary",
    )
    await service.record_resume_analysis(
        user_id="user-1", resume_id="resume-1", candidate_id="candidate-1",
        resume_text="Java", target_role="Java 后端", summary=evaluation.summary,
        questions=[], priorities=[], suggestions=[], technical_stack=["Java"],
        technical_depth=["backend"], career_preferences=[], run_id="run-1",
        evaluation_fingerprint="fingerprint-1", evaluation=evaluation,
    )
    replay = await service.get_resume_evaluation_run(
        user_id="user-1", resume_id="resume-1", run_id="run-1",
        evaluation_fingerprint="fingerprint-1",
    )
    assert replay == evaluation
    with pytest.raises(ConsistencyError):
        await service.get_resume_evaluation_run(
            user_id="user-1", resume_id="resume-1", run_id="run-1",
            evaluation_fingerprint="fingerprint-2",
        )


@pytest.mark.asyncio
async def test_resume_activation_replay_preserves_completed_profile() -> None:
    service = build_service()
    await service.activate_resume(
        user_id="user-1", resume_id="resume-1", candidate_id="candidate-1",
        resume_text="Java", target_role="Java 后端", run_id="activate-1",
    )
    evaluation = ResumeEvaluation(
        overallScore=80, contentScore=80, structureScore=80,
        skillMatchScore=80, expressionScore=80, projectScore=80,
        summary="summary",
    )
    await service.record_resume_analysis(
        user_id="user-1", resume_id="resume-1", candidate_id="candidate-1",
        resume_text="Java", target_role="Java 后端", summary=evaluation.summary,
        questions=[], priorities=[], suggestions=[], technical_stack=["Java"],
        technical_depth=[], career_preferences=[], run_id="evaluation-1",
        evaluation_fingerprint="fingerprint-1", evaluation=evaluation,
    )
    await service.activate_resume(
        user_id="user-1", resume_id="resume-1", candidate_id="candidate-1",
        resume_text="Java", target_role="Java 后端", run_id="activate-1",
    )
    context = await service.build_context(SimpleNamespace(
        user_id="user-1", session_id="session-1", resume_id="resume-1", turns=[]
    ))
    assert context.technical_stack == ["Java"]
