"""面试 Agent 规划、记忆、幂等和受约束流程推进。"""

import asyncio
import hashlib
import logging
import hashlib
import json
from dataclasses import dataclass
from datetime import datetime, timezone
from collections.abc import Awaitable, Callable

from app.memory.service import MemoryService
from app.common.exceptions import AgentDependencyError, ConsistencyError
from app.common.contracts import SessionStatus
from app.common.prompt_loader import PromptLoader
from app.infrastructure.cache.redis_cache import RedisCache
from app.infrastructure.idempotency.policy import IdempotencyPolicy

from .agent import (
    InterviewEvaluationAgent,
    InterviewPlanner,
    InterviewQuestionAgent,
    InterviewRoutingAgent,
    InterviewSummaryAgent,
)
from app.rag.service import RagSearchTool
from app.tools.web_search import WebEvidenceTool
from .models import (
    AgentRunSnapshot,
    CandidateProfile,
    InterviewAction,
    InterviewEvaluation,
    InterviewRoute,
    InterviewSession,
    InterviewStage,
    TurnRecord,
    MAX_QUESTIONS_PER_TOPIC,
    MAX_PRIMARY_QUESTIONS_PER_STAGE,
    MAX_TOTAL_QUESTIONS,
    MAX_FOLLOWUPS_PER_PRIMARY,
    ALGORITHM_SEVERE_SCORE_THRESHOLD,
    MIN_PRIMARY_QUESTIONS_PER_STAGE,
)
from .repository import InterviewSessionRepository
from .workflow import InterviewWorkflow


logger = logging.getLogger(__name__)

INTERVIEW_RAG_TIMEOUT_SECONDS = 30.0
INTERVIEW_WEB_TIMEOUT_SECONDS = 15.0
# A turn contains several model nodes.  A single node must fail promptly so
# the UI never remains on "evaluating" while the provider retries for minutes.
INTERVIEW_MODEL_NODE_TIMEOUT_SECONDS = 45.0


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
        web_evidence_tool: WebEvidenceTool | None = None,
        cache: RedisCache | None = None,
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
        self._web_evidence_tool = web_evidence_tool
        self._progress: dict[str, str] = {}
        self._progress_reporter: Callable[[str, str], Awaitable[None]] | None = None
        self._cache = cache

    def set_progress_reporter(
        self, reporter: Callable[[str, str], Awaitable[None]] | None
    ) -> None:
        self._progress_reporter = reporter

    def progress_for(self, session_id: str) -> str:
        return self._progress.get(session_id, "IDLE")

    async def progress_for_async(self, session_id: str) -> str:
        """Read the cross-instance progress cache before local fallback."""
        if self._cache is not None:
            cached = await self._cache.get_json(f"python:agent-progress:{session_id}")
            if isinstance(cached, dict) and isinstance(cached.get("stage"), str):
                return cached["stage"]
        return self.progress_for(session_id)

    def mark_progress_failed(self, session_id: str) -> None:
        """Keep a failed run observable instead of reporting a false idle state."""
        if session_id:
            self._progress[session_id] = "FAILED"
            if self._cache is not None:
                try:
                    asyncio.get_running_loop().create_task(self._cache.set_json(
                        f"python:agent-progress:{session_id}", {"stage": "FAILED"}, ttl_seconds=86400
                    ))
                except RuntimeError:
                    pass

    async def _report_progress(self, session_id: str, stage: str) -> None:
        self._progress[session_id] = stage
        if self._cache is not None:
            await self._cache.set_json(
                f"python:agent-progress:{session_id}", {"stage": stage}, ttl_seconds=86400
            )
        if self._progress_reporter is not None:
            await self._progress_reporter(session_id, stage)

    async def _run_interview_node(
        self, session_id: str, stage: str, operation: Callable[[], Awaitable[object]]
    ) -> object:
        await self._report_progress(session_id, stage)
        try:
            return await asyncio.wait_for(
                operation(), timeout=INTERVIEW_MODEL_NODE_TIMEOUT_SECONDS
            )
        except TimeoutError as error:
            self.mark_progress_failed(session_id)
            raise AgentDependencyError(
                f"面试{stage}节点超过 {int(INTERVIEW_MODEL_NODE_TIMEOUT_SECONDS)} 秒未返回，请稍后重试",
                retryable=True,
            ) from error

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

        # Planning is also an LLM-driven ReAct node.  It must use the same
        # bounded execution path as answer evaluation, routing, and question
        # generation; otherwise a stalled model connection during session
        # creation can leave the Java request waiting indefinitely.
        plan = await self._run_interview_node(
            session_id,
            "PLANNING",
            lambda: self._planner.create_plan(profile),
        )
        session = InterviewSession(
            session_id=session_id,
            user_id=user_id,
            candidate_id=profile.candidate_id,
            resume_id=profile.resume_id,
            jd_id=profile.jd_id,
            resume_text=profile.resume_text,
            jd_text=profile.jd_text,
            target_role=profile.target_role,
            interview_duration_minutes=profile.interview_duration_minutes,
            interview_direction=profile.interview_direction,
            custom_categories=profile.custom_categories,
            difficulty=profile.desired_difficulty,
            plan=plan,
            target_question_count=min(profile.question_count, MAX_TOTAL_QUESTIONS),
            selected_skills=plan.selected_skills,
            current_question=self._workflow.opening_message(
                self._prompt_loader, profile.target_role
            ),
            current_topic="自我介绍",
            system_knowledge_base_ids=profile.system_knowledge_base_ids,
            user_knowledge_base_ids=profile.user_knowledge_base_ids,
            initialization_run_id=run_id,
            initialization_fingerprint=self._profile_fingerprint(profile),
        )
        self._register_question(session, session.current_question, InterviewStage.OPENING, "自我介绍")
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
        await self._report_progress(session_id, "SUMMARIZING")
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
        session.final_evaluation = session.final_evaluation or self._fallback_evaluation(session)
        session.final_summary = session.final_evaluation.summary
        session.updated_at = datetime.now(timezone.utc)
        session.rag_evidence_cache.clear()
        saved = await self._repository.save(session, expected_version=expected_version)
        await self._memory_service.finalize_session(session=saved, interrupted=False)
        await self._report_progress(session_id, "COMPLETED")
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
        evaluation = await self._run_interview_node(
            session_id, "EVALUATING", lambda: self._evaluation_agent.evaluate(
                session, candidate_answer, memory_context
            )
        )
        if session.current_stage == InterviewStage.OPENING:
            await self._run_interview_node(
                session_id, "PLANNING", lambda: self._replan_after_opening(session, candidate_answer)
            )
        allowed_actions = self._allowed_actions(session, evaluation)
        next_stage = self._next_stage(session)
        route = await self._run_interview_node(
            session_id, "ROUTING", lambda: self._routing_agent.route(
                session,
                evaluation,
                {item.value for item in allowed_actions},
                next_stage.value if next_stage else None,
                memory_context,
            )
        )
        route = self._enforce_route_limits(session, route, allowed_actions, next_stage, evaluation)

        turn = self._record_turn(session, candidate_answer, evaluation, route, run_id)
        self._compact_session_history(session)
        self._apply_route(session, route)
        # The evaluated turn is part of session short-term memory before evidence
        # lookup and question generation.  Long-term persistence remains after the
        # full next state is saved, preventing a failed RAG/model call from leaving
        # a durable memory entry for a turn the session never accepted.
        next_question_memory_context = await self._memory_service.build_context(session)
        if session.status == SessionStatus.COMPLETED:
            await self._report_progress(session_id, "SUMMARIZING")
            session.final_summary = self._fallback_summary(session, interrupted=False)
            if self._summary_agent is not None and session.turns:
                try:
                    session.final_evaluation = await self._run_interview_node(
                        session_id, "SUMMARIZING", lambda: self._summary_agent.summarize(session)
                    )
                    session.final_summary = session.final_evaluation.summary
                except Exception as error:
                    logger.warning("面试会话总结生成失败: session_id=%s", session_id, exc_info=error)
            session.final_evaluation = session.final_evaluation or self._fallback_evaluation(session)
            session.final_summary = session.final_evaluation.summary
            session.current_question = session.final_summary
        elif session.status != SessionStatus.COMPLETED:
            if route.next_topic is None or not route.next_topic.strip():
                raise AgentDependencyError(
                    "模型在需要出题的路由中未返回 nextTopic", retryable=False
                )
            evidence = await self._question_evidence(session, route)
            session.current_question = await self._run_interview_node(
                session_id, "GENERATING_QUESTION", lambda: self._question_agent.generate(
                    session, route, evidence, next_question_memory_context
                )
            )
            session.current_topic = route.next_topic
            self._register_question(
                session, session.current_question, session.current_stage, route.next_topic,
                is_followup=route.action == InterviewAction.FOLLOW_UP,
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
            output=self._candidate_visible_output(session, turn),
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
        await self._report_progress(session_id, "COMPLETED" if saved.status == SessionStatus.COMPLETED else "IDLE")
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

    def _allowed_actions(
        self, session: InterviewSession, evaluation: InterviewEvaluation | None = None
    ) -> set[InterviewAction]:
        if session.current_stage == InterviewStage.OPENING:
            return {InterviewAction.NEXT_STAGE}

        stage_plan = session.plan.get_stage(session.current_stage)
        actions: set[InterviewAction] = set()
        total_question_count = getattr(
            session, "total_question_count", getattr(session, "total_primary_question_count", 0)
        )
        if total_question_count >= min(session.target_question_count, MAX_TOTAL_QUESTIONS):
            return {InterviewAction.END_INTERVIEW}
        stage_count = session.stage_question_counts.get(session.current_stage.value, 0)
        stage_limit = 2 if session.current_stage == InterviewStage.CODING else max(
            MIN_PRIMARY_QUESTIONS_PER_STAGE,
            min(stage_plan.max_primary_questions, MAX_PRIMARY_QUESTIONS_PER_STAGE),
        )
        answer_needs_followup = evaluation is not None and (
            evaluation.score <= 60 or bool(evaluation.weaknesses)
        )
        current_topic = (session.current_topic or "").strip()
        current_topic_key = self._canonical_topic_key(session, current_topic)
        current_topic_count = session.topic_question_counts.get(current_topic_key, 0)
        if (
            answer_needs_followup
            and session.followup_count < min(stage_plan.max_followups_per_question, MAX_FOLLOWUPS_PER_PRIMARY)
            and current_topic_count < MAX_QUESTIONS_PER_TOPIC
        ):
            actions.add(InterviewAction.FOLLOW_UP)
        if session.current_stage == InterviewStage.CODING:
            # 算法第二题不是普通的阶段扩展：只有第一题严重不足才允许出题。
            if (
                stage_count == 1
                and evaluation is not None
                and evaluation.score < ALGORITHM_SEVERE_SCORE_THRESHOLD
            ):
                return {InterviewAction.NEXT_QUESTION}
            # 第一题不属于严重失分，或强制补问的第二题已经完成，直接进入
            # SUMMARY；不能把算法结束暴露成“可提前结束整场”的普通路由。
            return {InterviewAction.NEXT_STAGE}
        if stage_count < min(MIN_PRIMARY_QUESTIONS_PER_STAGE, stage_limit):
            actions.add(InterviewAction.NEXT_QUESTION)
        else:
            actions.update({InterviewAction.NEXT_QUESTION, InterviewAction.NEXT_STAGE})
        if stage_count >= stage_limit:
            actions.discard(InterviewAction.NEXT_QUESTION)
            actions.add(InterviewAction.NEXT_STAGE)
        return actions

    async def _replan_after_opening(
        self,
        session: InterviewSession,
        self_introduction: str,
    ) -> None:
        """把自我介绍中的新增事实纳入正式计划，再进入项目深挖。"""
        # 兼容升级前已经持久化的会话：新会话一定带真实快照，旧会话缺少
        # 这些字段时沿用原计划，绝不伪造岗位或时长。
        if not session.resume_text or not session.target_role or session.interview_duration_minutes is None:
            return
        profile = CandidateProfile(
            candidate_id=session.candidate_id,
            resume_id=session.resume_id,
            jd_id=session.jd_id,
            resume_text=(
                f"{session.resume_text}\n\n候选人自我介绍：{self_introduction.strip()}"
            ),
            jd_text=session.jd_text,
            target_role=session.target_role,
            interview_duration_minutes=session.interview_duration_minutes,
            desired_difficulty=session.difficulty,
            question_count=session.target_question_count,
            interview_direction=session.interview_direction,
            custom_categories=session.custom_categories,
            system_knowledge_base_ids=session.system_knowledge_base_ids,
            user_knowledge_base_ids=session.user_knowledge_base_ids,
        )
        session.plan = await self._planner.create_plan(profile)
        session.selected_skills = session.plan.selected_skills

    def _enforce_route_limits(
        self,
        session: InterviewSession,
        route: InterviewRoute,
        allowed_actions: set[InterviewAction],
        next_stage: InterviewStage | None,
        evaluation: InterviewEvaluation | None = None,
    ) -> InterviewRoute:
        """将模型的软决策收敛到题量和主题硬边界内。"""
        total_question_count = getattr(
            session, "total_question_count", getattr(session, "total_primary_question_count", 0)
        )
        if total_question_count >= min(session.target_question_count, MAX_TOTAL_QUESTIONS):
            return InterviewRoute(action=InterviewAction.END_INTERVIEW)

        if route.action not in allowed_actions:
            return self._fallback_route(session, route.action, allowed_actions, next_stage)

        if route.action == InterviewAction.FOLLOW_UP and evaluation is not None and (
            evaluation.score > 60 and not evaluation.weaknesses
        ):
            return self._fallback_route(
                session,
                InterviewAction.FOLLOW_UP,
                allowed_actions - {InterviewAction.FOLLOW_UP},
                next_stage,
            )

        if route.action == InterviewAction.FOLLOW_UP:
            # 追问必须围绕同一个主问题，不能借 FOLLOW_UP 偷换新的主题。
            topic = (session.current_topic or route.next_topic or "").strip()
            topic_key = self._canonical_topic_key(session, topic)
            if not topic or session.topic_question_counts.get(topic_key, 0) >= MAX_QUESTIONS_PER_TOPIC:
                return self._fallback_route(
                    session,
                    InterviewAction.FOLLOW_UP,
                    allowed_actions - {InterviewAction.FOLLOW_UP},
                    next_stage,
                )
            return InterviewRoute(action=InterviewAction.FOLLOW_UP, next_topic=topic)

        if route.action == InterviewAction.NEXT_QUESTION:
            topic = (route.next_topic or "").strip()
            topic_key = self._canonical_topic_key(session, topic) if topic else ""
            if not topic or session.topic_question_counts.get(topic_key, 0) >= MAX_QUESTIONS_PER_TOPIC:
                topic = self._current_stage_topic(session)
            if not topic:
                return self._fallback_route(
                    session,
                    InterviewAction.NEXT_QUESTION,
                    allowed_actions - {InterviewAction.NEXT_QUESTION},
                    next_stage,
                )
            return InterviewRoute(action=InterviewAction.NEXT_QUESTION, next_topic=topic)

        if route.action == InterviewAction.NEXT_STAGE:
            return self._next_stage_route(session, next_stage, route.next_topic)

        return route

    def _fallback_route(
        self,
        session: InterviewSession,
        rejected_action: InterviewAction,
        allowed_actions: set[InterviewAction],
        next_stage: InterviewStage | None,
    ) -> InterviewRoute:
        """把越界动作改成仍然符合当前阶段语义的确定性动作。"""
        # 已满足中间阶段最低覆盖后，模型若错误地要求结束整场，应推进到
        # 下一阶段，而不是继续把后续阶段全部跳过。
        if rejected_action == InterviewAction.END_INTERVIEW and InterviewAction.NEXT_STAGE in allowed_actions:
            return self._next_stage_route(session, next_stage)

        # 未达到最低覆盖、算法低分强制补题、或高分时误选追问，都优先在
        # 当前阶段切换主问题。主题必须从当前阶段计划中重新选择。
        if InterviewAction.NEXT_QUESTION in allowed_actions:
            topic = self._current_stage_topic(session)
            if topic:
                return InterviewRoute(action=InterviewAction.NEXT_QUESTION, next_topic=topic)

        if InterviewAction.NEXT_STAGE in allowed_actions:
            return self._next_stage_route(session, next_stage)

        if InterviewAction.FOLLOW_UP in allowed_actions:
            topic = (session.current_topic or self._current_stage_topic(session) or "").strip()
            if topic:
                return InterviewRoute(action=InterviewAction.FOLLOW_UP, next_topic=topic)

        return InterviewRoute(action=InterviewAction.END_INTERVIEW)

    def _current_stage_topic(self, session: InterviewSession) -> str | None:
        """选择当前阶段尚未覆盖、且未达到主题上限的题目方向。"""
        topics = session.plan.get_stage(session.current_stage).topics
        for topic in topics:
            key = self._canonical_topic_key(session, topic)
            if session.topic_question_counts.get(key, 0) == 0:
                return topic
        for topic in topics:
            key = self._canonical_topic_key(session, topic)
            if session.topic_question_counts.get(key, 0) < MAX_QUESTIONS_PER_TOPIC:
                return topic
        current = (session.current_topic or "").strip()
        if current:
            key = self._canonical_topic_key(session, current)
            if session.topic_question_counts.get(key, 0) < MAX_QUESTIONS_PER_TOPIC:
                return current
        return None

    def _next_stage_route(
        self,
        session: InterviewSession,
        next_stage: InterviewStage | None,
        suggested_topic: str | None = None,
    ) -> InterviewRoute:
        if next_stage is None or next_stage == InterviewStage.SUMMARY:
            return InterviewRoute(action=InterviewAction.NEXT_STAGE)
        topics = session.plan.get_stage(next_stage).topics
        # 计划主题是方向约束，模型仍可结合 JD/简历细化具体主题。唯一要
        # 拦截的是把当前阶段的原主题原样带入下一阶段，避免“换阶段但不换题”。
        normalized_suggestion = " ".join((suggested_topic or "").split()).casefold()
        current_topics = {
            " ".join(topic.split()).casefold()
            for topic in session.plan.get_stage(session.current_stage).topics
        }
        topic = (suggested_topic or "").strip()
        if not topic or normalized_suggestion in current_topics:
            topic = topics[0] if topics else next_stage.value
        return InterviewRoute(action=InterviewAction.NEXT_STAGE, next_topic=topic)

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
            topic=session.current_topic,
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
        return turn

    @staticmethod
    def _register_question(
        session: InterviewSession,
        question: str,
        stage: InterviewStage,
        topic: str | None,
        *,
        is_followup: bool = False,
    ) -> None:
        normalized = " ".join(question.split()).casefold()
        existing = {" ".join(item.split()).casefold() for item in session.asked_question_catalog}
        if normalized not in existing:
            session.asked_question_catalog.append(question)
        stage_key = stage.value
        if not is_followup:
            session.stage_question_counts[stage_key] = session.stage_question_counts.get(stage_key, 0) + 1
        if topic and topic.strip():
            topic_key = InterviewAgentService._canonical_topic_key(session, topic)
            session.topic_question_counts[topic_key] = session.topic_question_counts.get(topic_key, 0) + 1

    @staticmethod
    def _canonical_topic_key(session: InterviewSession, topic: str) -> str:
        normalized = " ".join(topic.split()).casefold()
        if not normalized:
            return normalized
        try:
            candidates = session.plan.get_stage(session.current_stage).topics
        except (LookupError, ValueError):
            candidates = []
        for candidate in candidates:
            candidate_key = " ".join(candidate.split()).casefold()
            if candidate_key and (candidate_key in normalized or normalized in candidate_key):
                return candidate_key
        return normalized

    @staticmethod
    def _compact_session_history(session: InterviewSession, limit: int = 5) -> None:
        """仅把较早轮次压缩给模型，原始问答仍完整保存在会话中。"""
        older = session.turns[:-limit]
        if not older:
            return
        entries = []
        for turn in older:
            topic = turn.topic or turn.stage.value
            entries.append(
                f"{topic}: 问={turn.question}; 答={turn.answer_summary}; 分数={turn.score}"
            )
        session.history_summary = "\n".join(entries).strip()[-2000:]

    def _apply_route(
        self, session: InterviewSession, route: InterviewRoute
    ) -> None:
        if route.action == InterviewAction.FOLLOW_UP:
            session.followup_count += 1
            session.total_question_count += 1
            return

        if route.action == InterviewAction.NEXT_QUESTION:
            session.primary_question_count += 1
            session.total_primary_question_count += 1
            session.total_question_count += 1
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
        session.total_primary_question_count += 1
        session.total_question_count += 1
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
        cache_key = self._evidence_cache_key(
            stage=session.current_stage,
            topic=topic,
            knowledge_base_ids=ids,
        )
        await self._report_progress(session.session_id, "CACHE_LOOKUP")
        redis_evidence_key = (
            f"python:interview-evidence:{session.session_id}:"
            f"{hashlib.sha256(cache_key.encode('utf-8')).hexdigest()}"
        )
        redis_cached = await self._cache.get_json(redis_evidence_key) if self._cache else None
        cached = redis_cached if isinstance(redis_cached, list) else session.rag_evidence_cache.get(cache_key)
        if cached is not None:
            # Keep the persisted cache isolated from downstream prompt code.
            return [dict(item) for item in cached]
        results = []
        if ids and self._rag_tool is not None:
            await self._report_progress(session.session_id, "RAG_RETRIEVING")
            try:
                results = await asyncio.wait_for(
                    self._rag_tool.search_for_question_generation(
                        topic, knowledge_base_ids=ids
                    ),
                    timeout=INTERVIEW_RAG_TIMEOUT_SECONDS,
                )
            except Exception as error:
                # Evidence enrichment is optional. A broken embedding provider
                # or slow vector store must not freeze the interview workflow.
                logger.warning(
                    "面试 RAG 检索失败，继续使用无 RAG 证据出题: session_id=%s",
                    session.session_id,
                    exc_info=error,
                )
                results = []
        evidence = [{"content": item.chunk.content, "score": item.score,
                     "knowledgeBaseId": item.chunk.knowledge_base_id} for item in results]
        if self._evidence_is_insufficient(evidence) and self._web_evidence_tool is not None:
            await self._report_progress(session.session_id, "WEB_RETRIEVING")
            try:
                documents = await asyncio.wait_for(
                    self._web_evidence_tool.search_for_question_generation(topic),
                    timeout=INTERVIEW_WEB_TIMEOUT_SECONDS,
                )
            except Exception as error:
                # Public web search is a best-effort third layer. Explicit URL
                # imports keep their larger timeout, but an interview turn must
                # proceed when the public network is slow or unavailable.
                logger.warning(
                    "面试网页证据检索失败，继续使用已有证据出题: session_id=%s",
                    session.session_id,
                    exc_info=error,
                )
                documents = []
            evidence.extend({
                # Keep web evidence bounded for the question prompt. The full
                # Markdown remains available through the explicit KB import.
                "content": document.markdown[:12000],
                "score": 0.0,
                "sourceType": "WEB",
                "sourceUrl": document.url,
                "sourceTitle": document.title,
                "sourceFetchedAt": document.fetched_at,
                "contentHash": document.content_hash,
            } for document in documents)
        session.rag_evidence_cache[cache_key] = evidence
        if self._cache is not None:
            await self._cache.set_json(redis_evidence_key, evidence, ttl_seconds=3600)
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
    def _evidence_is_insufficient(evidence: list[dict[str, object]]) -> bool:
        """Require at least two relevant local chunks before skipping web lookup."""
        local = [item for item in evidence if item.get("sourceType", "RAG") == "RAG"]
        return len(local) < 2 or max((float(item.get("score", 0.0)) for item in local), default=0.0) < 0.5

    @staticmethod
    def _complete(session: InterviewSession) -> None:
        session.current_stage = InterviewStage.SUMMARY
        session.status = SessionStatus.COMPLETED
        session.final_summary = None
        session.current_question = ""
        session.current_question_evidence = []
        session.rag_evidence_cache.clear()

    @staticmethod
    def _candidate_visible_output(
        session: InterviewSession, turn: TurnRecord
    ) -> dict[str, object]:
        """Only return information that a candidate is meant to see.

        Internal memory, RAG evidence and routing rationales remain in the lower
        layer.  The upper layer receives a compact assessment and display counts.
        """
        output: dict[str, object] = {
            "evaluationSummary": turn.evaluation_summary,
            "evaluationScore": turn.score,
            "strengths": turn.strengths,
            "weaknesses": turn.weaknesses,
            "currentPrimaryQuestionCount": session.primary_question_count,
            "totalPrimaryQuestionCount": session.total_primary_question_count,
            "currentFollowupCount": session.followup_count,
            "totalQuestionCount": session.total_question_count,
            "questionBudget": session.target_question_count,
        }
        if session.final_evaluation is not None:
            output["finalEvaluation"] = session.final_evaluation.model_dump(by_alias=True)
        elif session.status == SessionStatus.COMPLETED:
            output["finalEvaluation"] = InterviewAgentService._fallback_evaluation(session).model_dump(by_alias=True)
        return output

    @staticmethod
    def _fallback_summary(session: InterviewSession, *, interrupted: bool) -> str:
        turn_count = len(session.turns)
        if interrupted:
            return f"本次面试在完成前中断，已保存 {turn_count} 轮问答记录，可在恢复后继续。"
        return f"本次面试已完成，共保存 {turn_count} 轮问答记录。"

    @staticmethod
    def _fallback_evaluation(session: InterviewSession) -> "InterviewSummary":
        """Produce a usable report even when the final LLM call is unavailable."""
        from .models import InterviewSummary

        if not session.turns:
            return InterviewSummary(
                overallScore=0, summary="本次面试没有有效作答，暂时无法形成能力评估。",
                strengths=[], weaknesses=["缺少有效的面试回答"], suggestions=["完成一次完整面试后再查看评估。"],
            )
        average = round(sum(turn.score for turn in session.turns) / len(session.turns))
        strengths = [item for turn in session.turns for item in turn.strengths][:5]
        weaknesses = [item for turn in session.turns for item in turn.weaknesses][:5]
        if not strengths:
            strengths = ["能够完成本次面试的主要问答"]
        if not weaknesses:
            weaknesses = ["建议继续补充回答中的技术原理和落地细节"]
        return InterviewSummary(
            overallScore=average,
            summary=f"本次面试共完成 {len(session.turns)} 轮问答，综合表现评分为 {average} 分。",
            strengths=strengths,
            weaknesses=weaknesses,
            suggestions=["结合每轮评估摘要，针对薄弱点补充原理、边界和实践案例。"],
        )
