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


class FakeRagTool:
    def __init__(self):
        self.calls = []

    async def search_for_resume_evaluation(self, query, *, knowledge_base_ids=None):
        self.calls.append((query, knowledge_base_ids))
        return []


@pytest.mark.asyncio
async def test_resume_evaluation_uses_external_skill_and_optional_rag() -> None:
    rag_tool = FakeRagTool()
    agent = ResumeEvaluationAgent(
        FakeStructuredModel(), PromptLoader(), SkillRegistry(), rag_tool=rag_tool
    )

    result = await agent.evaluate(
        subject_id="resume-1",
        input_text="熟悉 Java",
        target_role="Java 后端",
        knowledge_base_ids=("kb-1",),
    )

    assert result.overall_score == 80
    assert rag_tool.calls
    assert rag_tool.calls[0][1] == ("kb-1",)
