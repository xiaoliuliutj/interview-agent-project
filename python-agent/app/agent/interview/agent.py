"""基于大模型的面试规划与受约束决策。"""

import json
from typing import Protocol

from langchain_core.messages import HumanMessage, SystemMessage

from app.agent.skills.loader import SkillRegistry
from app.agent.memory.models import MemoryContext
from app.engineering.reliability.retry import AsyncRetryExecutor
from app.core.prompt_loader import PromptLoader

from .models import (
    CandidateProfile,
    GeneratedQuestion,
    InterviewEvaluation,
    InterviewPlan,
    InterviewRoute,
    InterviewSession,
    InterviewSummary,
)


class StructuredChatModel(Protocol):
    def with_structured_output(self, schema: type[object]) -> "StructuredChatModel": ...

    async def ainvoke(self, input_value: object) -> object: ...


class InterviewPlanner:
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

    async def create_plan(self, profile: CandidateProfile) -> InterviewPlan:
        planner = self._model.with_structured_output(InterviewPlan)
        skills = self._skill_registry.select_for_interview(
            target_role=profile.target_role,
            jd_text=profile.jd_text,
            requested_skill_id=profile.requested_skill_id,
        )
        system_prompt = self._prompt_loader.render(
            "interview/planner.md",
            {"skill_instructions": "\n\n".join(item.instructions for item in skills)},
        )
        input_payload = profile.model_dump(mode="json")
        messages = [
            SystemMessage(content=system_prompt),
            HumanMessage(content=json.dumps(input_payload, ensure_ascii=False)),
        ]
        result = await self._invoke(planner, messages)
        if not isinstance(result, InterviewPlan):
            raise TypeError("模型未返回 InterviewPlan")
        planned_question_count = sum(item.max_primary_questions for item in result.stages)
        if planned_question_count != profile.question_count:
            raise ValueError(
                f"面试计划题量与上层请求不一致: expected={profile.question_count}, actual={planned_question_count}"
            )
        if any(item.difficulty != profile.desired_difficulty for item in result.stages):
            raise ValueError("面试计划阶段难度与上层请求不一致")
        # Skill 选择属于下层 Agent 决策，写入计划快照，后续恢复会话不重新漂移。
        if not result.selected_skills:
            result = result.model_copy(update={"selected_skills": [item.skill_id for item in skills]})
        return result

    async def _invoke(self, model: StructuredChatModel, messages: object) -> object:
        if self._retry_executor is None:
            return await model.ainvoke(messages)
        return await self._retry_executor.execute(lambda: model.ainvoke(messages))


class InterviewEvaluationAgent:
    """First workflow node: score the answer without deciding what happens next."""

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
        session: InterviewSession,
        candidate_answer: str,
        memory_context: MemoryContext,
    ) -> InterviewEvaluation:
        evaluator = self._model.with_structured_output(InterviewEvaluation)
        context = {
            "current_stage": session.current_stage,
            "difficulty": session.difficulty,
            "current_question": session.current_question,
            # 这是出题时已经保存的证据缓存，只能作为当前题的事实参考；本节点不调用 RAG。
            "cached_question_reference": session.current_question_evidence,
            "candidate_answer": candidate_answer,
            "short_term_memory": memory_context.recent_turns,
            "long_term_memory": {
                "historical_summary": memory_context.historical_summary,
                "active_resume": memory_context.active_resume,
                "technical_stack": memory_context.technical_stack,
                "technical_depth": memory_context.technical_depth,
                "preferences": memory_context.preferences,
                "weak_topics": memory_context.weak_topics,
                "notes": memory_context.notes,
                "question_catalog": memory_context.question_catalog,
            },
        }
        # 评分标准必须稳定且与题目素材解耦。岗位领域 Skill 只参与规划、路由和
        # 出题；它们可能声明 rag.search 等工具，不能被注入评分节点，否则模型
        # 可能把知识库事实误当成评分标准，或在评分阶段尝试检索。
        scoring_skill = self._skill_registry.get("interview-coach")
        system_prompt = self._prompt_loader.render(
            "interview/evaluation.md",
            {"skill_instructions": scoring_skill.instructions},
        )
        messages = [
            SystemMessage(content=system_prompt),
            HumanMessage(content=json.dumps(context, ensure_ascii=False, default=str)),
        ]
        if self._retry_executor is None:
            result = await evaluator.ainvoke(messages)
        else:
            result = await self._retry_executor.execute(
                lambda: evaluator.ainvoke(messages)
            )
        if not isinstance(result, InterviewEvaluation):
            raise TypeError("模型未返回 InterviewEvaluation")
        return result


class InterviewRoutingAgent:
    """Second workflow node: route only after a persisted evaluation has been produced."""

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

    async def route(
        self,
        session: InterviewSession,
        evaluation: InterviewEvaluation,
        allowed_actions: set[str],
        next_stage_name: str | None,
        memory_context: MemoryContext,
    ) -> InterviewRoute:
        router = self._model.with_structured_output(InterviewRoute)
        context = {
            "current_stage": session.current_stage,
            "current_question": session.current_question,
            "evaluation": evaluation.model_dump(mode="json"),
            "primary_question_count": session.primary_question_count,
            "followup_count": session.followup_count,
            "stage_plan": session.plan.get_stage(session.current_stage),
            "allowed_actions": sorted(allowed_actions),
            "next_stage": next_stage_name,
            "question_catalog": memory_context.question_catalog,
            "weak_topics": memory_context.weak_topics,
        }
        skill_ids = session.selected_skills or session.plan.selected_skills or ["interview-coach"]
        skills = [self._skill_registry.get(skill_id) for skill_id in skill_ids]
        system_prompt = self._prompt_loader.render(
            "interview/routing.md",
            {"skill_instructions": "\n\n".join(item.instructions for item in skills)},
        )
        messages = [
            SystemMessage(content=system_prompt),
            HumanMessage(content=json.dumps(context, ensure_ascii=False, default=str)),
        ]
        result = (
            await self._retry_executor.execute(lambda: router.ainvoke(messages))
            if self._retry_executor is not None
            else await router.ainvoke(messages)
        )
        if not isinstance(result, InterviewRoute):
            raise TypeError("模型未返回 InterviewRoute")
        return result


class InterviewQuestionAgent:
    """Generate a concrete question only after routing has fixed the topic."""

    def __init__(self, model: StructuredChatModel, prompt_loader: PromptLoader,
                 skill_registry: SkillRegistry, retry_executor: AsyncRetryExecutor | None = None) -> None:
        self._model = model
        self._prompt_loader = prompt_loader
        self._skill_registry = skill_registry
        self._retry_executor = retry_executor

    async def generate(self, session: InterviewSession, route: InterviewRoute,
                       evidence: list[dict[str, object]], memory_context: MemoryContext) -> str:
        if route.next_topic is None or not route.next_topic.strip():
            raise ValueError("question generation requires a routed topic")
        skill_ids = session.selected_skills or session.plan.selected_skills
        skills = [self._skill_registry.get(skill_id) for skill_id in skill_ids]
        prompt = self._prompt_loader.render(
            "interview/question.md", {"skill_instructions": "\n\n".join(item.instructions for item in skills)}
        )
        payload = {
            "stage": session.current_stage,
            "difficulty": session.difficulty,
            "topic": route.next_topic,
            "askedQuestions": session.asked_question_catalog,
            "recentTurns": memory_context.recent_turns,
            "ragEvidence": evidence,
        }
        generator = self._model.with_structured_output(GeneratedQuestion)
        messages = [SystemMessage(content=prompt), HumanMessage(content=json.dumps(payload, ensure_ascii=False, default=str))]
        result = await (self._retry_executor.execute(lambda: generator.ainvoke(messages))
                        if self._retry_executor is not None else generator.ainvoke(messages))
        if not isinstance(result, GeneratedQuestion):
            raise TypeError("model did not return GeneratedQuestion")
        return result.question


class InterviewSummaryAgent:
    """在会话结束时基于完整历史生成综合评分，避免只用最后一轮替代总结。"""

    def __init__(self, model: StructuredChatModel, prompt_loader: PromptLoader,
                 retry_executor: AsyncRetryExecutor | None = None) -> None:
        self._model = model
        self._prompt_loader = prompt_loader
        self._retry_executor = retry_executor

    async def summarize(self, session: InterviewSession) -> InterviewSummary:
        evaluator = self._model.with_structured_output(InterviewSummary)
        payload = {
            "difficulty": session.difficulty,
            "plan": session.plan.model_dump(mode="json"),
            "turns": [turn.model_dump(mode="json") for turn in session.turns],
        }
        messages = [
            SystemMessage(content=self._prompt_loader.render("interview/summary.md", {})),
            HumanMessage(content=json.dumps(payload, ensure_ascii=False)),
        ]
        result = (
            await self._retry_executor.execute(lambda: evaluator.ainvoke(messages))
            if self._retry_executor is not None else await evaluator.ainvoke(messages)
        )
        if not isinstance(result, InterviewSummary):
            raise TypeError("model did not return InterviewSummary")
        return result
