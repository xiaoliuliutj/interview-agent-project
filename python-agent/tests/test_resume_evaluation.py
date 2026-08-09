import pytest

from app.agent.evaluation.agent import ResumeEvaluationAgent
from app.agent.evaluation.models import ResumeEvaluation
from app.agent.skills.loader import SkillRegistry
from app.core.prompt_loader import PromptLoader


class FakeStructuredModel:
    def with_structured_output(self, schema):
        assert schema is ResumeEvaluation
        return self

    async def ainvoke(self, messages):
        return ResumeEvaluation(
            overallScore=80,
            contentScore=78,
            structureScore=82,
            skillMatchScore=85,
            expressionScore=76,
            projectScore=79,
            summary="信息基本完整。",
            strengths=["项目事实明确"],
            suggestions=["补充量化结果"],
        )


@pytest.mark.asyncio
async def test_resume_evaluation_uses_external_skill() -> None:
    agent = ResumeEvaluationAgent(FakeStructuredModel(), PromptLoader(), SkillRegistry())

    result = await agent.evaluate(
        subject_id="resume-1",
        input_text="熟悉 Java",
        target_role="Java 后端",
    )

    assert result.overall_score == 80
