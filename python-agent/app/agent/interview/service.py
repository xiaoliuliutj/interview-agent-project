"""面试 Agent 规划、记忆、幂等和受约束流程推进。"""

from dataclasses import dataclass
from datetime import datetime, timezone

from app.agent.memory.service import MemoryService
from app.core.exceptions import AgentDependencyError, ConsistencyError
from app.core.contracts import SessionStatus
from app.core.prompt_loader import PromptLoader
from app.engineering.idempotency.policy import IdempotencyPolicy

from .agent import InterviewDecisionAgent, InterviewPlanner
from .models import (
    AgentRunSnapshot,
    CandidateProfile,
    InterviewAction,
    InterviewDecision,
    InterviewSession,
    InterviewStage,
    TurnRecord,
)
from .repository import InterviewSessionRepository
from .workflow import InterviewWorkflow


@dataclass(frozen=True)
class AgentSubmissionResult:
    session: InterviewSession
    snapshot: AgentRunSnapshot


class InterviewAgentService:
    """协调规划、记忆读取、幂等、决策和会话持久化。"""

    def __init__(
        self,
        planner: InterviewPlanner,
        decision_agent: InterviewDecisionAgent,
        repository: InterviewSessionRepository,
        workflow: InterviewWorkflow,
        prompt_loader: PromptLoader,
        memory_service: MemoryService,
        idempotency_policy: IdempotencyPolicy | None = None,
    ) -> None:
        self._planner = planner
        self._decision_agent = decision_agent
        self._repository = repository
        self._workflow = workflow
        self._prompt_loader = prompt_loader
        self._memory_service = memory_service
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
                return existing
            raise ConsistencyError("Agent 会话已存在")

        plan = await self._planner.create_plan(profile)
        session = InterviewSession(
            session_id=session_id,
            user_id=user_id,
            candidate_id=profile.candidate_id,
            resume_id=profile.resume_id,
            jd_id=profile.jd_id,
            plan=plan,
            current_question=self._workflow.opening_message(
                self._prompt_loader, profile.target_role
            ),
            initialization_run_id=run_id,
        )
        created = await self._repository.create(session)
        await self._memory_service.initialize_user_memory(
            user_id=user_id, profile=profile
        )
        return created

    async def submit_answer(
        self, *, user_id: str, session_id: str, candidate_answer: str
    ) -> InterviewSession:
        return (
            await self._submit_answer(
                user_id=user_id,
                session_id=session_id,
                candidate_answer=candidate_answer,
                run_id=None,
            )
        ).session

    async def complete_session(
        self, *, user_id: str, session_id: str
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
            raise ConsistencyError("失败的 Agent 会话不能直接完成")

        expected_version = session.state_version
        session.status = SessionStatus.COMPLETED
        session.updated_at = datetime.now(timezone.utc)
        return await self._repository.save(session, expected_version=expected_version)

    async def submit_answer_for_run(
        self,
        *,
        user_id: str,
        session_id: str,
        candidate_answer: str,
        run_id: str,
    ) -> AgentSubmissionResult:
        return await self._submit_answer(
            user_id=user_id,
            session_id=session_id,
            candidate_answer=candidate_answer,
            run_id=run_id,
        )

    async def _submit_answer(
        self,
        *,
        user_id: str,
        session_id: str,
        candidate_answer: str,
        run_id: str | None,
    ) -> AgentSubmissionResult:
        session = await self._repository.get(session_id)
        if session is None:
            raise ConsistencyError("Agent 会话不存在")
        if session.user_id != user_id:
            raise ConsistencyError("用户与 Agent 会话不匹配")
        if run_id and run_id in session.run_snapshots:
            return AgentSubmissionResult(
                session=session, snapshot=session.run_snapshots[run_id]
            )
        if session.status != "ACTIVE":
            raise ConsistencyError("当前 Agent 会话不可继续回答")

        expected_version = session.state_version
        memory_context = await self._memory_service.build_context(session)
        allowed_actions = self._allowed_actions(session)
        next_stage = self._next_stage(session)
        decision = await self._decision_agent.decide(
            session,
            candidate_answer,
            {item.value for item in allowed_actions},
            next_stage.value if next_stage else None,
            memory_context,
        )
        if decision.action not in allowed_actions:
            raise AgentDependencyError(
                "模型返回了不允许的流程动作", retryable=False
            )

        turn = self._record_turn(session, candidate_answer, decision)
        self._apply_decision(session, decision)
        session.updated_at = datetime.now(timezone.utc)
        snapshot = AgentRunSnapshot(
            answer=session.current_question,
            session_status=session.status,
            state_version=expected_version + 1,
            output={
                "evaluationSummary": decision.evaluation_summary,
                "action": decision.action.value,
                "stage": turn.stage.value,
            },
        )
        if run_id:
            session.run_snapshots[run_id] = snapshot
            while len(session.run_snapshots) > self._idempotency_policy.max_run_snapshots:
                session.run_snapshots.pop(next(iter(session.run_snapshots)))
        saved = await self._repository.save(session, expected_version=expected_version)
        await self._memory_service.record_turn(session=saved, turn=turn)
        return AgentSubmissionResult(session=saved, snapshot=snapshot)

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

    def _record_turn(
        self,
        session: InterviewSession,
        candidate_answer: str,
        decision: InterviewDecision,
    ) -> TurnRecord:
        turn = TurnRecord(
            stage=session.current_stage,
            question=session.current_question,
            candidate_answer=candidate_answer,
            action=decision.action,
            evaluation_summary=decision.evaluation_summary,
        )
        session.turns.append(turn)
        return turn

    def _apply_decision(
        self, session: InterviewSession, decision: InterviewDecision
    ) -> None:
        if decision.action == InterviewAction.FOLLOW_UP:
            session.followup_count += 1
            session.current_question = decision.next_message
            return

        if decision.action == InterviewAction.NEXT_QUESTION:
            session.primary_question_count += 1
            session.followup_count = 0
            session.current_question = decision.next_message
            return

        if decision.action == InterviewAction.END_INTERVIEW:
            self._complete(session, decision.next_message)
            return

        next_stage = self._next_stage(session)
        if next_stage is None or next_stage == InterviewStage.SUMMARY:
            self._complete(session, decision.next_message)
            return

        session.current_stage = next_stage
        session.primary_question_count = 1
        session.followup_count = 0
        session.current_question = decision.next_message

    @staticmethod
    def _complete(session: InterviewSession, summary: str) -> None:
        session.current_stage = InterviewStage.SUMMARY
        session.status = "COMPLETED"
        session.current_question = summary
