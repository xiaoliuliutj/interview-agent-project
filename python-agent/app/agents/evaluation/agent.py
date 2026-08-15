"""基于简历事实、岗位要求与外置 Skill 的简历评价 Agent。"""

from app.tools.skills.loader import SkillRegistry
from app.common.prompt_loader import PromptLoader
from app.infrastructure.reliability.retry import AsyncRetryExecutor
from app.infrastructure.reliability.structured_output import RawChatModel, StructuredOutputInvoker

from .models import ResumeEvaluation


class ResumeEvaluationAgent:
    def __init__(
        self,
        model: RawChatModel,
        prompt_loader: PromptLoader,
        skill_registry: SkillRegistry,
        retry_executor: AsyncRetryExecutor | None = None,
    ) -> None:
        self._model = model
        self._prompt_loader = prompt_loader
        self._skill_registry = skill_registry
        self._retry_executor = retry_executor
        self._structured_output = StructuredOutputInvoker(prompt_loader, retry_executor)

    async def evaluate(
        self,
        *,
        subject_id: str,
        input_text: str,
        target_role: str,
    ) -> ResumeEvaluation:
        normalized_text = input_text.strip()
        if not normalized_text:
            raise ValueError("待评价内容不能为空")

        skill = self._skill_registry.get("resume-analyst")
        system_prompt = self._prompt_loader.render(
            "resume/analysis.md", {"skill_instructions": skill.instructions}
        )
        payload = {
            "subjectId": subject_id,
            "targetRole": target_role,
            "resumeText": normalized_text,
        }
        return await self._structured_output.invoke(
            model=self._model,
            schema=ResumeEvaluation,
            business_prompt=system_prompt,
            input_payload=payload,
        )
