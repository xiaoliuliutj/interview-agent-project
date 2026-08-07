"""记忆领域模型；不包含 Java 侧业务实体。"""

from datetime import datetime, timezone

from pydantic import BaseModel, Field

from app.agent.interview.models import CandidateProfile, InterviewSession, TurnRecord


class ResumeMemory(BaseModel):
    """下层保存的、与简历版本绑定的 Agent 使用快照。"""

    resume_id: str
    candidate_id: str
    target_role: str
    resume_text: str = ""
    jd_id: str | None = None
    jd_text: str = ""
    updated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class LongTermMemory(BaseModel):
    """按用户隔离的跨会话记忆，不承载某次会话的原始消息。"""

    user_id: str
    historical_summary: str = ""
    resume_snapshots: list[ResumeMemory] = Field(default_factory=list)
    preferences: list[str] = Field(default_factory=list)
    weak_topics: list[str] = Field(default_factory=list)
    notes: list[str] = Field(default_factory=list)
    state_version: int = Field(default=0, ge=0)
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class MemoryContext(BaseModel):
    """一次 Agent 决策允许读取的上下文视图。"""

    recent_turns: list[TurnRecord]
    historical_summary: str
    active_resume: ResumeMemory | None
    preferences: list[str]
    weak_topics: list[str]
    notes: list[str]

    @classmethod
    def empty(cls, session: InterviewSession) -> "MemoryContext":
        return cls(
            recent_turns=[],
            historical_summary="",
            active_resume=None,
            preferences=[],
            weak_topics=[],
            notes=[],
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
