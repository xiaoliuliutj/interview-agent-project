"""面试 Agent 规划、记忆、幂等和受约束流程推进。"""

import logging
import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timezone

from app.agent.memory.service import MemoryService
from app.core.exceptions import AgentDependencyError, ConsistencyError
from app.core.contracts import SessionStatus
from app.core.prompt_loader import PromptLoader
from app.engineering.idempotency.policy import IdempotencyPolicy

from .agent import (
    InterviewEvaluationAgent,
    InterviewPlanner,
    InterviewQuestionAgent,
    InterviewRoutingAgent,
    InterviewSummaryAgent,
)
from app.agent.rag.service import RagSearchTool
from .models import (
    AgentRunSnapshot,
    CandidateProfile,
    InterviewAction,
    InterviewEvaluation,
    InterviewRoute,
    InterviewSession,
    InterviewStage,
    TurnRecord,
)
from .repository import InterviewSessionRepository
from .workflow import InterviewWorkflow


logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class AgentSubmissionResult:
    session: InterviewSession
    snapshot: AgentRunSnapshot


class InterviewAgentService:
    """协调规划、记忆读取、幂等、决策和会话持久化。"""

    def __init__(
        self,
        planner: InterviewPlanner,
        evaluation_agent: InterviewEvaluationAgent,
        routing_agent: InterviewRoutingAgent,
        question_agent: InterviewQuestionAgent,
        rag_tool: RagSearchTool | None,
        repository: InterviewSessionRepository,
        workflow: InterviewWorkflow,
        prompt_loader: PromptLoader,
        memory_service: MemoryService,
        summary_agent: InterviewSummaryAgent | None = None,
        idempotency_policy: IdempotencyPolicy | None = None,
    ) -> None:
        self._planner = planner
        self._evaluation_agent = evaluation_agent
        self._routing_agent = routing_agent
        self._question_agent = question_agent
        self._rag_tool = rag_tool
        self._repository = repository
        self._workflow = workflow
        self._prompt_loader = prompt_loader
        self._memory_service = memory_service
        self._summary_agent = summary_agent
        self._idempotency_policy = idempotency_policy or IdempotencyPolicy(100)

    async def initialize_session(
        self,
        *,
        user_id: str,
        session_id: str,
        profile: CandidateProfile,
        run_id: str | None = None,
    ) -> InterviewSession:
        existing = await self._repository.get(session_id)
        if existing is not None:
            if (
                run_id
                and existing.user_id == user_id
                and existing.initialization_run_id == run_id
            ):
                expected_fingerprint = self._profile_fingerprint(profile)
                if (
                    existing.initialization_fingerprint is not None
                    and existing.initialization_fingerprint != expected_fingerprint
                ):
                    raise ConsistencyError("同一初始化 runId 不能提交不同的会话参数")
                return existing
            raise ConsistencyError("Agent 会话已存在")

        plan = await self._planner.create_plan(profile)
        session = InterviewSession(
            session_id=session_id,
            user_id=user_id,
            candidate_id=profile.candidate_id,
            resume_id=profile.resume_id,
            jd_id=profile.jd_id,
            difficulty=profile.desired_difficulty,
            plan=plan,
            selected_skills=plan.selected_skills,
            current_question=self._workflow.opening_message(
                self._prompt_loader, profile.target_role
            ),
            system_knowledge_base_ids=profile.system_knowledge_base_ids,
            user_knowledge_base_ids=profile.user_knowledge_base_ids,
            initialization_run_id=run_id,
            initialization_fingerprint=self._profile_fingerprint(profile),
        )
        await self._memory_service.initialize_user_memory(
            user_id=user_id, profile=profile
        )
        return await self._repository.create(session)

    async def complete_session(
        self, *, user_id: str, session_id: str,
        expected_session_status: SessionStatus,
        expected_state_version: int,
    ) -> InterviewSession:
        """关闭本次 Agent 会话，但不删除用户级长期记忆。"""
        session = await self._repository.get(session_id)
        if session is None:
            raise ConsistencyError("Agent 会话不存在")
        if session.user_id != user_id:
            raise ConsistencyError("用户与 Agent 会话不匹配")
        if session.status == SessionStatus.COMPLETED:
            return session
        if session.status == SessionStatus.FAILED:
            await self._memory_service.finalize_session(session=session, interrupted=True)
            return session
        self._validate_expected_state(
            session,
            expected_session_status=expected_session_status,
            expected_state_version=expected_state_version,
        )

        expected_version = session.state_version
        session.status = SessionStatus.COMPLETED
        session.final_summary = session.final_summary or self._fallback_summary(
            session, interrupted=False
        )
        if self._summary_agent is not None and session.turns:
            try:
                session.final_evaluation = await self._summary_agent.summarize(session)
                session.final_summary = session.final_evaluation.summary
            except Exception as error:
                # 总结不影响已完成会话的可恢复性，但必须保留可观测日志。
                logger.warning("面试会话总结生成失败: session_id=%s", session_id, exc_info=error)
        session.updated_at = datetime.now(timezone.utc)
        saved = await self._repository.save(session, expected_version=expected_version)
        await self._memory_service.finalize_session(session=saved, interrupted=False)
        return saved

    async def pause_session(
        self, *, user_id: str, session_id: str,
        expected_session_status: SessionStatus,
        expected_state_version: int,
    ) -> InterviewSession:
        session = await self._repository.get(session_id)
        if session is None or session.user_id != user_id:
            raise ConsistencyError("Agent session not found")
        if session.status in {SessionStatus.COMPLETED, SessionStatus.FAILED}:
            return session
        self._validate_expected_state(
            session,
            expected_session_status=expected_session_status,
            expected_state_version=expected_state_version,
        )
        expected_version = session.state_version
        session.status = SessionStatus.PAUSED
        session.interrupted = True
        saved = await self._repository.save(session, expected_version=expected_version)
        return saved

    async def submit_answer_for_run(
        self,
        *,
        user_id: str,
        session_id: str,
        candidate_answer: str,
        run_id: str,
        expected_session_status: SessionStatus,
        expected_state_version: int,
    ) -> AgentSubmissionResult:
        return await self._submit_answer(
            user_id=user_id,
            session_id=session_id,
            candidate_answer=candidate_answer,
            run_id=run_id,
            expected_session_status=expected_session_status,
            expected_state_version=expected_state_version,
        )

    async def _submit_answer(
        self,
        *,
        user_id: str,
        session_id: str,
        candidate_answer: str,
        run_id: str | None,
        expected_session_status: SessionStatus | None = None,
        expected_state_version: int | None = None,
    ) -> AgentSubmissionResult:
        session = await self._repository.get(session_id)
        if session is None:
            raise ConsistencyError("Agent 会话不存在")
        if session.user_id != user_id:
            raise ConsistencyError("用户与 Agent 会话不匹配")
        if run_id and run_id in session.run_snapshots:
            persisted_snapshot = session.run_snapshots[run_id]
            if persisted_snapshot.submitted_answer != candidate_answer:
                raise ConsistencyError("同一 runId 不能提交不同的回答")
            await self._synchronize_turn_memory(session, run_id)
            return AgentSubmissionResult(
                session=session, snapshot=persisted_snapshot
            )
        if expected_session_status is not None and expected_state_version is not None:
            self._validate_expected_state(
                session,
                expected_session_status=expected_session_status,
                expected_state_version=expected_state_version,
            )
        if session.status not in {SessionStatus.ACTIVE, SessionStatus.PAUSED}:
            raise ConsistencyError("当前 Agent 会话不可继续回答")

        if session.status == SessionStatus.PAUSED:
            session.status = SessionStatus.ACTIVE
            session.interrupted = False

        expected_version = session.state_version
        memory_context = await self._memory_service.build_context(session)
        allowed_actions = self._allowed_actions(session)
        next_stage = self._next_stage(session)
        evaluation = await self._evaluation_agent.evaluate(
            session,
            candidate_answer,
            memory_context,
        )
        route = await self._routing_agent.route(
            session,
            evaluation,
            {item.value for item in allowed_actions},
            next_stage.value if next_stage else None,
            memory_context,
        )
        if route.action not in allowed_actions:
            raise AgentDependencyError(
                "模型返回了不允许的流程动作", retryable=False
            )

        turn = self._record_turn(session, candidate_answer, evaluation, route, run_id)
        self._apply_route(session, route)
        # The evaluated turn is part of session short-term memory before evidence
        # lookup and question generation.  Long-term persistence remains after the
        # full next state is saved, preventing a failed RAG/model call from leaving
        # a durable memory entry for a turn the session never accepted.
        next_question_memory_context = await self._memory_service.build_context(session)
        if session.status == SessionStatus.COMPLETED:
            session.final_summary = self._fallback_summary(session, interrupted=False)
            if self._summary_agent is not None and session.turns:
                try:
                    session.final_evaluation = await self._summary_agent.summarize(session)
                    session.final_summary = session.final_evaluation.summary
                except Exception as error:
                    logger.warning("面试会话总结生成失败: session_id=%s", session_id, exc_info=error)
            session.current_question = session.final_summary
        elif session.status != SessionStatus.COMPLETED:
            if route.next_topic is None or not route.next_topic.strip():
                raise AgentDependencyError(
                    "模型在需要出题的路由中未返回 nextTopic", retryable=False
                )
            evidence = await self._question_evidence(session, route)
            session.current_question = await self._question_agent.generate(
                session, route, evidence, next_question_memory_context
            )
            # 下轮评分复用这份快照，不为评分额外发起知识库检索。
            session.current_question_evidence = evidence
        session.updated_at = datetime.now(timezone.utc)
        snapshot = AgentRunSnapshot(
            submitted_answer=candidate_answer,
            answer=session.current_question,
            session_status=session.status,
            state_version=expected_version + 1,
            turn_stage=turn.stage,
            current_stage=session.current_stage,
            output=None,
        )
        if run_id:
            session.run_snapshots[run_id] = snapshot
            while len(session.run_snapshots) > self._idempotency_policy.max_run_snapshots:
                session.run_snapshots.pop(next(iter(session.run_snapshots)))
        saved = await self._repository.save(session, expected_version=expected_version)
        await self._memory_service.record_turn(session=saved, turn=turn)
        if saved.status in {SessionStatus.COMPLETED, SessionStatus.FAILED}:
            await self._memory_service.finalize_session(
                session=saved, interrupted=saved.status == SessionStatus.FAILED
            )
        return AgentSubmissionResult(session=saved, snapshot=snapshot)

    @staticmethod
    def _profile_fingerprint(profile: CandidateProfile) -> str:
        payload = profile.model_dump(mode="json", by_alias=True)
        encoded = json.dumps(payload, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(encoded.encode("utf-8")).hexdigest()

    @staticmethod
    def _validate_expected_state(
        session: InterviewSession,
        *,
        expected_session_status: SessionStatus,
        expected_state_version: int,
    ) -> None:
        if (
            session.status != expected_session_status
            or session.state_version != expected_state_version
        ):
            raise ConsistencyError(
                "上层与下层 Agent 会话状态不一致，请先恢复最新会话状态"
            )

    def _allowed_actions(self, session: InterviewSession) -> set[InterviewAction]:
        if session.current_stage == InterviewStage.OPENING:
            return {InterviewAction.NEXT_STAGE}

        stage_plan = session.plan.get_stage(session.current_stage)
        actions = {InterviewAction.NEXT_STAGE, InterviewAction.END_INTERVIEW}
        if session.followup_count < stage_plan.max_followups_per_question:
            actions.add(InterviewAction.FOLLOW_UP)
        if session.primary_question_count < stage_plan.max_primary_questions:
            actions.add(InterviewAction.NEXT_QUESTION)
        return actions

    def _next_stage(self, session: InterviewSession) -> InterviewStage | None:
        current_index = self._workflow.stages.index(session.current_stage)
        for stage in self._workflow.stages[current_index + 1 :]:
            stage_plan = session.plan.get_stage(stage)
            if stage == InterviewStage.SUMMARY or stage_plan.max_primary_questions > 0:
                return stage
        return None

    async def _synchronize_turn_memory(
        self, session: InterviewSession, run_id: str
    ) -> None:
        for turn in reversed(session.turns):
            if turn.run_id == run_id:
                await self._memory_service.record_turn(session=session, turn=turn)
                return

    def _record_turn(
        self,
        session: InterviewSession,
        candidate_answer: str,
        evaluation: InterviewEvaluation,
        route: InterviewRoute,
        run_id: str | None,
    ) -> TurnRecord:
        turn = TurnRecord(
            run_id=run_id,
            stage=session.current_stage,
            question=session.current_question,
            candidate_answer=candidate_answer,
            action=route.action,
            evaluation_summary=evaluation.evaluation_summary,
            score=evaluation.score,
            answer_summary=evaluation.answer_summary,
            strengths=evaluation.strengths,
            weaknesses=evaluation.weaknesses,
            preferences=evaluation.preferences,
        )
        session.turns.append(turn)
        session.asked_question_catalog.append(turn.question)
        return turn

    def _apply_route(
        self, session: InterviewSession, route: InterviewRoute
    ) -> None:
        if route.action == InterviewAction.FOLLOW_UP:
            session.followup_count += 1
            return

        if route.action == InterviewAction.NEXT_QUESTION:
            session.primary_question_count += 1
            session.followup_count = 0
            return

        if route.action == InterviewAction.END_INTERVIEW:
            self._complete(session)
            return

        next_stage = self._next_stage(session)
        if next_stage is None or next_stage == InterviewStage.SUMMARY:
            self._complete(session)
            return

        session.current_stage = next_stage
        session.primary_question_count = 1
        session.followup_count = 0

    async def _question_evidence(self, session: InterviewSession, route: InterviewRoute) -> list[dict[str, object]]:
        """Read the session evidence cache and retrieve only after routing.

        The routing node must determine ``next_topic`` before this method is
        called. RAG evidence is question material only; it cannot score an
        answer or change the route decision.
        """
        if route.next_topic is None or not route.next_topic.strip():
            raise AgentDependencyError("RAG 出题缺少已确定的题目方向", retryable=False)
        topic = route.next_topic
        ids = tuple(dict.fromkeys([*session.system_knowledge_base_ids, *session.user_knowledge_base_ids]))
        if not ids or self._rag_tool is None:
            return []
        cache_key = self._evidence_cache_key(
            stage=session.current_stage,
            topic=topic,
            knowledge_base_ids=ids,
        )
        cached = session.rag_evidence_cache.get(cache_key)
        if cached is not None:
            # Keep the persisted cache isolated from downstream prompt code.
            return [dict(item) for item in cached]
        results = await self._rag_tool.search_for_question_generation(
            topic, knowledge_base_ids=ids
        )
        evidence = [{"content": item.chunk.content, "score": item.score,
                     "knowledgeBaseId": item.chunk.knowledge_base_id} for item in results]
        session.rag_evidence_cache[cache_key] = evidence
        return [dict(item) for item in evidence]

    @staticmethod
    def _evidence_cache_key(
        *,
        stage: InterviewStage,
        topic: str,
        knowledge_base_ids: tuple[str, ...],
    ) -> str:
        normalized_topic = " ".join(topic.split()).casefold()
        return "|".join([stage.value, normalized_topic, *sorted(knowledge_base_ids)])

    @staticmethod
    def _complete(session: InterviewSession) -> None:
        session.current_stage = InterviewStage.SUMMARY
        session.status = SessionStatus.COMPLETED
        session.final_summary = None
        session.current_question = ""
        session.current_question_evidence = []

    @staticmethod
    def _fallback_summary(session: InterviewSession, *, interrupted: bool) -> str:
        turn_count = len(session.turns)
        if interrupted:
            return f"本次面试在完成前中断，已保存 {turn_count} 轮问答记录，可在恢复后继续。"
        return f"本次面试已完成，共保存 {turn_count} 轮问答记录。"
