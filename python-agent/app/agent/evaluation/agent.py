"""基于简历事实、岗位要求与外置 Skill 的简历评价 Agent。"""

import json
from typing import Protocol

from langchain_core.messages import HumanMessage, SystemMessage

from app.agent.skills.loader import SkillRegistry
from app.core.prompt_loader import PromptLoader
from app.engineering.reliability.retry import AsyncRetryExecutor

from .models import ResumeEvaluation


class StructuredChatModel(Protocol):
    def with_structured_output(self, schema: type[object]) -> "StructuredChatModel": ...

    async def ainvoke(self, input_value: object) -> object: ...


class ResumeEvaluationAgent:
    def __init__(
        self,
        model: StructuredChatModel,
        prompt_loader: PromptLoader,
        skill_registry: SkillRegistry,
        retry_executor: AsyncRetryExecutor | None = None,
    ) -> None:
        self._model = model
        self._prompt_loader = prompt_loader
        self._skill_registry = skill_registry
        self._retry_executor = retry_executor

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
        evaluator = self._model.with_structured_output(ResumeEvaluation)
        messages = [
            SystemMessage(content=system_prompt),
            HumanMessage(content=json.dumps(payload, ensure_ascii=False)),
        ]
        result = (
            await self._retry_executor.execute(lambda: evaluator.ainvoke(messages))
            if self._retry_executor is not None
            else await evaluator.ainvoke(messages)
        )
        if not isinstance(result, ResumeEvaluation):
            raise TypeError("模型未返回 ResumeEvaluation")
        return result
