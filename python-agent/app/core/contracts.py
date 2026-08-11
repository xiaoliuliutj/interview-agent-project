"""JSON contracts shared by the upper layer and the lower Agent service."""

from datetime import datetime, timezone
from enum import StrEnum
from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator


class RunStatus(StrEnum):
    COMPLETED = "COMPLETED"
    PROCESSING = "PROCESSING"
    PARTIAL = "PARTIAL"
    FAILED = "FAILED"


class SessionStatus(StrEnum):
    ACTIVE = "ACTIVE"
    PAUSED = "PAUSED"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"


class ErrorInfo(BaseModel):
    type: str
    message: str
    retryable: bool = False


class AgentOperationRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    api_version: str = Field(alias="apiVersion", min_length=1)
    request_id: str = Field(alias="requestId", min_length=1)
    run_id: str = Field(alias="runId", min_length=1)
    user_id: str = Field(alias="userId", min_length=1)
    session_id: str = Field(alias="sessionId", min_length=1)
    timestamp: datetime


class AgentRespondRequest(AgentOperationRequest):
    operation: Literal["agent.respond"]
    session_status: SessionStatus = Field(alias="sessionStatus")
    state_version: int = Field(alias="stateVersion", ge=0)
    answer: str = Field(min_length=1)


class AgentRagIndexRequest(AgentOperationRequest):
    operation: Literal["rag.index"]
    document_content: str = Field(alias="documentContent", min_length=1)
    knowledge_base_ids: list[str] = Field(alias="knowledgeBaseIds", min_length=1, max_length=1)
    document_id: str = Field(alias="documentId", min_length=1)
    source_name: str = Field(alias="sourceName", min_length=1)


class AgentRagDeleteRequest(AgentOperationRequest):
    operation: Literal["rag.delete"]
    knowledge_base_id: str = Field(alias="knowledgeBaseId", min_length=1)


class AgentSkillRequest(AgentOperationRequest):
    operation: Literal["agent.skills.list", "agent.skills.parse-jd"]
    input_text: str | None = Field(default=None, alias="inputText", min_length=1)


class CandidateSnapshot(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    candidate_id: str = Field(alias="candidateId", min_length=1)
    resume_id: str = Field(alias="resumeId", min_length=1)
    jd_id: str | None = Field(default=None, alias="jdId")
    resume_text: str = Field(alias="resumeText", min_length=1)
    jd_text: str | None = Field(alias="jdText")
    target_role: str = Field(alias="targetRole", min_length=1)
    interview_duration_minutes: int = Field(alias="interviewDurationMinutes", ge=15, le=120)
    desired_difficulty: Literal["EASY", "MEDIUM", "HARD"] = Field(alias="desiredDifficulty")
    # Deprecated compatibility input. The lower layer ignores it and always
    # owns the 20-question maximum budget.
    question_count: int = Field(default=20, alias="questionCount", ge=2, le=20)
    requested_skill_id: str | None = Field(default=None, alias="requestedSkillId")
    custom_categories: list[dict[str, Any]] = Field(alias="customCategories")
    system_knowledge_base_ids: list[str] = Field(alias="systemKnowledgeBaseIds")
    user_knowledge_base_ids: list[str] = Field(alias="userKnowledgeBaseIds")


class AgentInitializationRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    api_version: str = Field(alias="apiVersion", min_length=1)
    request_id: str = Field(alias="requestId", min_length=1)
    run_id: str = Field(alias="runId", min_length=1)
    user_id: str = Field(alias="userId", min_length=1)
    session_id: str = Field(alias="sessionId", min_length=1)
    operation: Literal["agent.session.initialize"]
    candidate: CandidateSnapshot
    timestamp: datetime


class AgentSessionCompletionRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    api_version: str = Field(alias="apiVersion", min_length=1)
    request_id: str = Field(alias="requestId", min_length=1)
    run_id: str = Field(alias="runId", min_length=1)
    user_id: str = Field(alias="userId", min_length=1)
    session_id: str = Field(alias="sessionId", min_length=1)
    operation: Literal["agent.session.complete", "agent.session.pause"]
    session_status: SessionStatus = Field(alias="sessionStatus")
    state_version: int = Field(alias="stateVersion", ge=0)
    timestamp: datetime


class AgentEvaluationRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    api_version: str = Field(alias="apiVersion", min_length=1)
    request_id: str = Field(alias="requestId", min_length=1)
    run_id: str = Field(alias="runId", min_length=1)
    user_id: str = Field(alias="userId", min_length=1)
    session_id: str = Field(alias="sessionId", min_length=1)
    operation: Literal["agent.resume.evaluate"]
    subject_type: Literal["RESUME"] = Field(alias="subjectType")
    subject_id: str = Field(alias="subjectId", min_length=1)
    candidate_id: str = Field(alias="candidateId", min_length=1)
    input_text: str = Field(alias="inputText", min_length=1)
    target_role: str = Field(alias="targetRole", min_length=1)
    timestamp: datetime


class AgentResumeMemoryActivationRequest(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    api_version: str = Field(alias="apiVersion", min_length=1)
    request_id: str = Field(alias="requestId", min_length=1)
    run_id: str = Field(alias="runId", min_length=1)
    user_id: str = Field(alias="userId", min_length=1)
    session_id: str = Field(alias="sessionId", min_length=1)
    operation: Literal["agent.resume.activate"]
    subject_id: str = Field(alias="subjectId", min_length=1)
    candidate_id: str = Field(alias="candidateId", min_length=1)
    input_text: str = Field(alias="inputText", min_length=1)
    target_role: str = Field(alias="targetRole", min_length=1)
    timestamp: datetime


class AgentResponse(BaseModel):
    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    api_version: str | None = Field(default=None, alias="apiVersion")
    request_id: str | None = Field(default=None, alias="requestId", min_length=1)
    run_id: str | None = Field(default=None, alias="runId", min_length=1)
    code: int = Field(ge=100, le=599)
    status: RunStatus
    user_id: str | None = Field(default=None, alias="userId", min_length=1)
    session_id: str | None = Field(default=None, alias="sessionId", min_length=1)
    session_status: SessionStatus = Field(alias="sessionStatus")
    state_version: int = Field(alias="stateVersion", ge=0)
    answer: str | None = None
    turn_stage: str | None = Field(default=None, alias="turnStage")
    current_stage: str | None = Field(default=None, alias="currentStage")
    output: dict[str, object] | None = None
    error: ErrorInfo | None = None
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

    @field_validator("code")
    @classmethod
    def validate_code_category(cls, value: int) -> int:
        if value // 100 not in {1, 2, 3, 4, 5}:
            raise ValueError("code first digit must be between 1 and 5")
        return value

    def to_json_dict(self) -> dict:
        return self.model_dump(mode="json", by_alias=True, exclude_none=False)
