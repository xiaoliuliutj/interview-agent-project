"""上下层 JSON 交互契约。"""

from datetime import datetime, timezone
from enum import StrEnum
from typing import Literal

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


class AgentRequest(BaseModel):
    """上层发送给下层的最小请求，不携带上层业务上下文。"""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    api_version: str = Field(alias="apiVersion")
    request_id: str = Field(alias="requestId", min_length=1)
    run_id: str = Field(alias="runId", min_length=1)
    user_id: str = Field(alias="userId", min_length=1)
    session_id: str = Field(alias="sessionId", min_length=1)
    operation: str = Field(default="agent.respond", min_length=1)
    question: str = Field(min_length=1)
    knowledge_base_ids: list[str] | None = Field(default=None, alias="knowledgeBaseIds")
    use_case: str | None = Field(default=None, alias="useCase")
    document_id: str | None = Field(default=None, alias="documentId")
    source_name: str | None = Field(default=None, alias="sourceName")
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class CandidateSnapshot(BaseModel):
    """初始化时由上层传入、供下层建立 Agent 上下文的资料快照。"""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    candidate_id: str = Field(alias="candidateId", min_length=1)
    resume_id: str = Field(alias="resumeId", min_length=1)
    jd_id: str | None = Field(default=None, alias="jdId")
    resume_text: str = Field(default="", alias="resumeText")
    jd_text: str = Field(default="", alias="jdText")
    target_role: str = Field(alias="targetRole", min_length=1)
    interview_duration_minutes: int = Field(
        default=40, alias="interviewDurationMinutes", ge=15, le=120
    )
    desired_difficulty: Literal["EASY", "MEDIUM", "HARD"] = Field(
        default="MEDIUM", alias="desiredDifficulty"
    )


class AgentInitializationRequest(BaseModel):
    """只在创建 Agent 会话时传递的初始化快照。"""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    api_version: str = Field(default="v1", alias="apiVersion", min_length=1)
    request_id: str = Field(alias="requestId", min_length=1)
    run_id: str = Field(alias="runId", min_length=1)
    user_id: str = Field(alias="userId", min_length=1)
    session_id: str = Field(alias="sessionId", min_length=1)
    operation: Literal["agent.session.initialize"] = "agent.session.initialize"
    candidate: CandidateSnapshot
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class AgentSessionCompletionRequest(BaseModel):
    """上层结束业务会话时请求下层关闭 Agent 会话，不携带业务回答。"""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    api_version: str = Field(default="v1", alias="apiVersion", min_length=1)
    request_id: str = Field(alias="requestId", min_length=1)
    run_id: str = Field(alias="runId", min_length=1)
    user_id: str = Field(alias="userId", min_length=1)
    session_id: str = Field(alias="sessionId", min_length=1)
    operation: Literal["agent.session.complete"] = "agent.session.complete"
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class AgentEvaluationRequest(BaseModel):
    """通用输入评价请求；首期 subjectType 只开放 RESUME。"""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    api_version: str = Field(default="v1", alias="apiVersion", min_length=1)
    request_id: str = Field(alias="requestId", min_length=1)
    run_id: str = Field(alias="runId", min_length=1)
    user_id: str = Field(alias="userId", min_length=1)
    session_id: str = Field(alias="sessionId", min_length=1)
    operation: Literal["agent.resume.evaluate"] = "agent.resume.evaluate"
    subject_type: Literal["RESUME"] = Field(default="RESUME", alias="subjectType")
    subject_id: str = Field(alias="subjectId", min_length=1)
    input_text: str = Field(alias="inputText", min_length=1)
    target_role: str = Field(alias="targetRole", min_length=1)
    knowledge_base_ids: list[str] | None = Field(default=None, alias="knowledgeBaseIds")
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class AgentResponse(BaseModel):
    """下层返回上层的统一响应；成功与失败使用完全相同的字段集合。"""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    api_version: str = Field(alias="apiVersion")
    request_id: str = Field(alias="requestId", min_length=1)
    run_id: str = Field(alias="runId", min_length=1)
    code: int = Field(ge=100, le=599)
    status: RunStatus
    user_id: str = Field(alias="userId", min_length=1)
    session_id: str = Field(alias="sessionId", min_length=1)
    session_status: SessionStatus = Field(alias="sessionStatus")
    state_version: int = Field(alias="stateVersion", ge=0)
    answer: str | None = None
    output: dict[str, object] | None = None
    error: ErrorInfo | None = None
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

    @field_validator("code")
    @classmethod
    def validate_code_category(cls, value: int) -> int:
        if value // 100 not in {1, 2, 3, 4, 5}:
            raise ValueError("code 首位必须属于 1 到 5")
        return value

    def to_json_dict(self) -> dict:
        """按上下层约定的 camelCase 字段输出，并保留值为 null 的字段。"""

        return self.model_dump(mode="json", by_alias=True, exclude_none=False)
