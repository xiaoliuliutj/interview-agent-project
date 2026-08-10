"""面试 Agent 的领域模型。"""

from datetime import datetime, timezone
from typing import Any
from enum import StrEnum
from uuid import uuid4

from pydantic import BaseModel, Field, model_validator

from app.core.contracts import SessionStatus


class InterviewStage(StrEnum):
    OPENING = "OPENING"
    PROJECT = "PROJECT"
    FUNDAMENTAL = "FUNDAMENTAL"
    SCENARIO = "SCENARIO"
    CODING = "CODING"
    SUMMARY = "SUMMARY"


# 这些是下层的硬边界，模型只能在边界内做软决策。
MAX_PRIMARY_QUESTIONS_PER_STAGE = 4
MAX_QUESTIONS_PER_TOPIC = 3


class InterviewAction(StrEnum):
    FOLLOW_UP = "FOLLOW_UP"
    NEXT_QUESTION = "NEXT_QUESTION"
    NEXT_STAGE = "NEXT_STAGE"
    END_INTERVIEW = "END_INTERVIEW"


class Difficulty(StrEnum):
    EASY = "EASY"
    MEDIUM = "MEDIUM"
    HARD = "HARD"


class CandidateProfile(BaseModel):
    """创建会话时一次性提供的候选人资料。"""

    candidate_id: str
    resume_id: str
    jd_id: str | None = None
    resume_text: str | None = None
    jd_text: str | None
    target_role: str
    interview_duration_minutes: int = Field(ge=15, le=120)
    desired_difficulty: Difficulty
    question_count: int = Field(ge=2, le=30)
    requested_skill_id: str | None = None
    custom_categories: list[dict[str, Any]]
    system_knowledge_base_ids: list[str]
    user_knowledge_base_ids: list[str]


class StagePlan(BaseModel):
    stage: InterviewStage
    max_primary_questions: int = Field(ge=0, le=10)
    max_followups_per_question: int = Field(ge=0, le=3)
    difficulty: Difficulty
    topics: list[str] = Field(default_factory=list)
    time_budget_minutes: int = Field(ge=0, le=60)


class InterviewPlan(BaseModel):
    """一次面试固定使用的规划，初始化后不在每轮重新生成。"""

    candidate_summary: str
    strategy_summary: str
    stages: list[StagePlan]
    selected_skills: list[str] = Field(default_factory=list, alias="selectedSkills")

    model_config = {"populate_by_name": True}

    @model_validator(mode="after")
    def validate_stage_order(self) -> "InterviewPlan":
        expected = list(InterviewStage)
        actual = [item.stage for item in self.stages]
        if actual != expected:
            raise ValueError("面试计划必须按六个固定阶段完整配置")

        stage_by_name = {item.stage: item for item in self.stages}
        if stage_by_name[InterviewStage.OPENING].max_primary_questions != 1:
            raise ValueError("OPENING 阶段固定一轮")
        if stage_by_name[InterviewStage.SUMMARY].max_primary_questions != 1:
            raise ValueError("SUMMARY 阶段固定一次输出")
        for item in self.stages:
            if item.max_primary_questions > MAX_PRIMARY_QUESTIONS_PER_STAGE:
                raise ValueError("单个阶段的主问题上限不能超过 4")
        return self

    def get_stage(self, stage: InterviewStage) -> StagePlan:
        return next(item for item in self.stages if item.stage == stage)


class InterviewEvaluation(BaseModel):
    """对当前回答的评分结果；该节点不决定面试流程。"""

    evaluation_summary: str = Field(min_length=1, max_length=500)
    score: int = Field(ge=0, le=100)
    answer_summary: str = Field(min_length=1, max_length=1000)
    strengths: list[str] = Field(default_factory=list, max_length=10)
    weaknesses: list[str] = Field(default_factory=list, max_length=10)
    preferences: list[str] = Field(default_factory=list, max_length=10)


class InterviewRoute(BaseModel):
    """评分完成后确定的流程动作与下一题方向；不包含评分字段。"""

    action: InterviewAction
    # 结束面试或直接进入总结时没有下一题方向，不能伪造题目作为占位值。
    next_topic: str | None = Field(default=None, min_length=1, max_length=300)


class GeneratedQuestion(BaseModel):
    question: str = Field(min_length=1, max_length=1200)


class TurnRecord(BaseModel):
    turn_id: str = Field(default_factory=lambda: uuid4().hex, min_length=1)
    run_id: str | None = None
    stage: InterviewStage
    topic: str | None = None
    question: str
    candidate_answer: str
    action: InterviewAction
    evaluation_summary: str
    score: int = Field(ge=0, le=100)
    answer_summary: str = Field(min_length=1, max_length=1000)
    strengths: list[str] = Field(default_factory=list, max_length=10)
    weaknesses: list[str] = Field(default_factory=list, max_length=10)
    preferences: list[str] = Field(default_factory=list, max_length=10)
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class InterviewSummary(BaseModel):
    overall_score: int = Field(ge=0, le=100, alias="overallScore")
    summary: str = Field(min_length=1, max_length=2000)
    strengths: list[str] = Field(default_factory=list, max_length=10)
    weaknesses: list[str] = Field(default_factory=list, max_length=10)
    suggestions: list[str] = Field(default_factory=list, max_length=10)

    model_config = {"populate_by_name": True}


class AgentRunSnapshot(BaseModel):
    """同一 runId 重放时返回的稳定下层结果。"""

    submitted_answer: str = Field(min_length=1, alias="submittedAnswer")
    answer: str
    session_status: SessionStatus
    state_version: int = Field(ge=0)
    turn_stage: InterviewStage | None = Field(default=None, alias="turnStage")
    current_stage: InterviewStage = Field(alias="currentStage")
    output: dict[str, object] | None = None

    model_config = {"populate_by_name": True}


class InterviewSession(BaseModel):
    """下层持久化的单个 Agent 面试会话。"""

    session_id: str
    user_id: str
    candidate_id: str
    resume_id: str
    jd_id: str | None = None
    resume_text: str = Field(min_length=1)
    jd_text: str | None = None
    target_role: str | None = None
    interview_duration_minutes: int | None = Field(default=None, ge=15, le=120)
    requested_skill_id: str | None = None
    custom_categories: list[dict[str, Any]] = Field(default_factory=list)
    difficulty: Difficulty
    selected_skills: list[str] = Field(default_factory=list)
    plan: InterviewPlan
    # questionCount 是总主问题预算，不是初始化时分配给各阶段的固定数量。
    target_question_count: int = Field(default=30, alias="targetQuestionCount", ge=2, le=30)
    status: SessionStatus = SessionStatus.ACTIVE
    current_stage: InterviewStage = InterviewStage.OPENING
    primary_question_count: int = Field(default=1, ge=0)
    total_primary_question_count: int = Field(default=1, alias="totalPrimaryQuestionCount", ge=0)
    followup_count: int = Field(default=0, ge=0)
    state_version: int = Field(default=0, ge=0)
    current_question: str
    system_knowledge_base_ids: list[str] = Field(default_factory=list)
    user_knowledge_base_ids: list[str] = Field(default_factory=list)
    # 生成当前题目时命中的资料快照。下一轮评分只能读取该缓存，不能再次检索。
    current_question_evidence: list[dict[str, object]] = Field(default_factory=list)
    rag_evidence_cache: dict[str, list[dict[str, object]]] = Field(default_factory=dict)
    turns: list[TurnRecord] = Field(default_factory=list)
    asked_question_catalog: list[str] = Field(default_factory=list)
    topic_question_counts: dict[str, int] = Field(default_factory=dict, alias="topicQuestionCounts")
    stage_question_counts: dict[str, int] = Field(default_factory=dict, alias="stageQuestionCounts")
    history_summary: str = Field(default="", alias="historySummary")
    current_topic: str | None = Field(default=None, alias="currentTopic")
    final_summary: str | None = None
    final_evaluation: InterviewSummary | None = None
    interrupted: bool = False
    initialization_run_id: str | None = None
    initialization_fingerprint: str | None = None
    run_snapshots: dict[str, AgentRunSnapshot] = Field(default_factory=dict)
    created_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
    updated_at: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))

    model_config = {"populate_by_name": True}
