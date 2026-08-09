"""记忆领域模型；不包含 Java 侧业务实体。"""

from datetime import datetime, timezone

from pydantic import BaseModel, Field

from app.agent.interview.models import CandidateProfile, InterviewSession, TurnRecord
from app.agent.evaluation.models import ResumeEvaluation


class ResumeMemory(BaseModel):
    """下层保存的、与简历版本绑定的 Agent 使用快照。"""

    resume_id: str
    candidate_id: str
    target_role: str
    resume_text: str
    jd_id: str | None = None
    jd_text: str | None = None
    analysis_summary: str = ""
    analysis_questions: list[str] = Field(default_factory=list)
    analysis_priorities: list[str] = Field(default_factory=list)
    analysis_suggestions: list[str] = Field(default_factory=list)
    updated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class ResumeEvaluationRun(BaseModel):
    run_id: str
    resume_id: str
    fingerprint: str
    evaluation: ResumeEvaluation


class ResumeActivationRun(BaseModel):
    run_id: str
    resume_id: str
    fingerprint: str


class LongTermMemory(BaseModel):
    """按用户隔离的跨会话记忆，不承载某次会话的原始消息。"""

    user_id: str
    # Only the newest resume version is allowed to update the resume-derived profile.
    # It is deliberately separate from session identity: a user can have many sessions
    # while having exactly one active resume version.
    active_resume_id: str | None = None
    historical_summary: str = ""
    resume_snapshots: list[ResumeMemory] = Field(default_factory=list)
    technical_stack: list[str] = Field(default_factory=list)
    technical_depth: list[str] = Field(default_factory=list)
    preferences: list[str] = Field(default_factory=list)
    weak_topics: list[str] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)
    question_catalog: list[str] = Field(default_factory=list)
    interview_summaries: list[str] = Field(default_factory=list)
    recorded_turn_ids: list[str] = Field(default_factory=list)
    finalized_session_ids: list[str] = Field(default_factory=list)
    resume_evaluation_runs: dict[str, ResumeEvaluationRun] = Field(default_factory=dict)
    resume_activation_runs: dict[str, ResumeActivationRun] = Field(default_factory=dict)
    state_version: int = Field(default=0, ge=0)
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class MemoryContext(BaseModel):
    """一次 Agent 决策允许读取的上下文视图。"""

    recent_turns: list[TurnRecord]
    historical_summary: str
    active_resume: ResumeMemory | None
    technical_stack: list[str]
    technical_depth: list[str]
    preferences: list[str]
    weak_topics: list[str]
    notes: list[str]
    question_catalog: list[str] = Field(default_factory=list)

    @classmethod
    def empty(cls, session: InterviewSession) -> "MemoryContext":
        return cls(
            recent_turns=[],
            historical_summary="",
            active_resume=None,
            technical_stack=[],
            technical_depth=[],
            preferences=[],
            weak_topics=[],
            notes=[],
            question_catalog=[],
        )


def to_resume_memory(profile: CandidateProfile) -> ResumeMemory:
    return ResumeMemory(
        resume_id=profile.resume_id,
        candidate_id=profile.candidate_id,
        target_role=profile.target_role,
        resume_text=profile.resume_text,
        jd_id=profile.jd_id,
        jd_text=profile.jd_text,
    )
