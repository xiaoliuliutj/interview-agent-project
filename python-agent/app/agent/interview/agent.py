"""基于大模型的面试规划与受约束决策。"""

import json
from typing import Protocol

from langchain_core.messages import HumanMessage, SystemMessage

from app.agent.skills.loader import SkillRegistry
from app.agent.memory.models import MemoryContext
from app.agent.rag.service import RagSearchTool
from app.engineering.reliability.retry import AsyncRetryExecutor
from app.core.prompt_loader import PromptLoader

from .models import CandidateProfile, InterviewDecision, InterviewPlan, InterviewSession


class StructuredChatModel(Protocol):
    def with_structured_output(self, schema: type[object]) -> "StructuredChatModel": ...

    async def ainvoke(self, input_value: object) -> object: ...


class InterviewPlanner:
    def __init__(
        self,
        model: StructuredChatModel,
        prompt_loader: PromptLoader,
        skill_registry: SkillRegistry,
        rag_tool: RagSearchTool | None = None,
        retry_executor: AsyncRetryExecutor | None = None,
    ) -> None:
        self._model = model
        self._prompt_loader = prompt_loader
        self._skill_registry = skill_registry
        self._rag_tool = rag_tool
        self._retry_executor = retry_executor

    async def create_plan(self, profile: CandidateProfile) -> InterviewPlan:
        planner = self._model.with_structured_output(InterviewPlan)
        skill = self._skill_registry.get("interview-coach")
        system_prompt = self._prompt_loader.render(
            "interview/planner.md",
            {"skill_instructions": skill.instructions},
        )
        input_payload = profile.model_dump(mode="json")
        if self._rag_tool is not None:
            evidence = await self._rag_tool.search_for_question_generation(
                f"{profile.target_role}\n{profile.resume_text}\n{profile.jd_text}"
            )
            input_payload["ragEvidence"] = [
                {"content": item.chunk.content, "score": item.score}
                for item in evidence
            ]
        messages = [
            SystemMessage(content=system_prompt),
            HumanMessage(content=json.dumps(input_payload, ensure_ascii=False)),
        ]
        result = await self._invoke(planner, messages)
        if not isinstance(result, InterviewPlan):
            raise TypeError("模型未返回 InterviewPlan")
        return result

    async def _invoke(self, model: StructuredChatModel, messages: object) -> object:
        if self._retry_executor is None:
            return await model.ainvoke(messages)
        return await self._retry_executor.execute(lambda: model.ainvoke(messages))


class InterviewDecisionAgent:
    def __init__(
        self,
        model: StructuredChatModel,
        prompt_loader: PromptLoader,
        skill_registry: SkillRegistry,
        rag_tool: RagSearchTool | None = None,
        retry_executor: AsyncRetryExecutor | None = None,
    ) -> None:
        self._model = model
        self._prompt_loader = prompt_loader
        self._skill_registry = skill_registry
        self._rag_tool = rag_tool
        self._retry_executor = retry_executor

    async def decide(
        self,
        session: InterviewSession,
        candidate_answer: str,
        allowed_actions: set[str],
        next_stage_name: str | None,
        memory_context: MemoryContext,
    ) -> InterviewDecision:
        decider = self._model.with_structured_output(InterviewDecision)
        context = {
            "current_stage": session.current_stage,
            "current_question": session.current_question,
            "candidate_answer": candidate_answer,
            "primary_question_count": session.primary_question_count,
            "followup_count": session.followup_count,
            "stage_plan": session.plan.get_stage(session.current_stage),
            "allowed_actions": sorted(allowed_actions),
            "next_stage": next_stage_name,
            "short_term_memory": memory_context.recent_turns,
            "long_term_memory": {
                "historical_summary": memory_context.historical_summary,
                "active_resume": memory_context.active_resume,
                "preferences": memory_context.preferences,
                "weak_topics": memory_context.weak_topics,
                "notes": memory_context.notes,
            },
        }
        if self._rag_tool is not None:
            evidence = await self._rag_tool.search_for_resume_evaluation(
                f"{session.current_question}\n{candidate_answer}"
            )
            context["rag_evidence"] = [
                {"content": item.chunk.content, "score": item.score}
                for item in evidence
            ]
        skill = self._skill_registry.get("interview-coach")
        system_prompt = self._prompt_loader.render(
            "interview/decision.md",
            {"skill_instructions": skill.instructions},
        )
        messages = [
            SystemMessage(content=system_prompt),
            HumanMessage(content=json.dumps(context, ensure_ascii=False, default=str)),
        ]
        if self._retry_executor is None:
            result = await decider.ainvoke(messages)
        else:
            result = await self._retry_executor.execute(
                lambda: decider.ainvoke(messages)
            )
        if not isinstance(result, InterviewDecision):
            raise TypeError("模型未返回 InterviewDecision")
        return result
