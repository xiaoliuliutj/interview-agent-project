"""评价 Agent 的受控结构化输出。"""

from pydantic import BaseModel, ConfigDict, Field


class ResumeIssue(BaseModel):
    question: str = Field(min_length=1)
    priority: str = Field(default="NORMAL", min_length=1)
    suggestion: str = Field(min_length=1)


class ResumeEvaluation(BaseModel):
    overall_score: int = Field(ge=0, le=100, alias="overallScore")
    content_score: int = Field(ge=0, le=100, alias="contentScore")
    structure_score: int = Field(ge=0, le=100, alias="structureScore")
    skill_match_score: int = Field(ge=0, le=100, alias="skillMatchScore")
    expression_score: int = Field(ge=0, le=100, alias="expressionScore")
    project_score: int = Field(ge=0, le=100, alias="projectScore")
    summary: str = Field(min_length=1, max_length=2000)
    strengths: list[str] = Field(default_factory=list, max_length=10)
    suggestions: list[str] = Field(default_factory=list, max_length=10)
    issues: list[ResumeIssue] = Field(default_factory=list, max_length=20)

    model_config = ConfigDict(populate_by_name=True)
