"""Long-term memory reads, writes, and idempotent compensation."""

from datetime import datetime, timezone
import hashlib
import json

from app.agents.interview.models import CandidateProfile, InterviewSession, TurnRecord
from app.agents.evaluation.models import ResumeEvaluation
from app.common.exceptions import ConsistencyError

from .models import (
    LongTermMemory,
    MemoryContext,
    ResumeActivationRun,
    ResumeEvaluationRun,
    ResumeMemory,
    to_resume_memory,
)
from .policy import MemoryPolicy
from .repository import LongTermMemoryRepository


class MemoryService:
    def __init__(self, repository: LongTermMemoryRepository, policy: MemoryPolicy) -> None:
        self._repository = repository
        self._policy = policy

    async def initialize_user_memory(self, *, user_id: str, profile: CandidateProfile) -> LongTermMemory:
        existing = await self._repository.get(user_id)
        if existing is None:
            return await self._repository.create(LongTermMemory(
                user_id=user_id,
                active_resume_id=profile.resume_id,
                resume_snapshots=[to_resume_memory(profile)],
            ))
        expected_version = existing.state_version
        # 简历是用户画像的版本源。切换到新简历时，不能继续沿用旧简历提取的
        # 技术栈、深度和偏好，否则后续面试会把旧画像当成当前候选人事实。
        if existing.active_resume_id != profile.resume_id:
            existing.technical_stack = []
            existing.technical_depth = []
            existing.preferences = []
            existing.notes = []
        existing.active_resume_id = profile.resume_id
        existing.resume_snapshots = self._merge_resume_snapshot(existing.resume_snapshots, to_resume_memory(profile))
        existing.updated_at = datetime.now(timezone.utc)
        return await self._repository.save(existing, expected_version=expected_version)

    async def activate_resume(
        self, *, user_id: str, resume_id: str, candidate_id: str, resume_text: str,
        target_role: str, run_id: str | None = None,
    ) -> LongTermMemory:
        """Make a newly uploaded resume the only version allowed to write profile data.

        This is called before asynchronous evaluation.  If an older evaluation returns
        afterwards, ``record_resume_analysis`` rejects it rather than overwriting the
        latest user profile.
        """
        fingerprint = self._resume_activation_fingerprint(
            resume_id=resume_id, candidate_id=candidate_id,
            resume_text=resume_text, target_role=target_role,
        )
        snapshot = ResumeMemory(
            resume_id=resume_id, candidate_id=candidate_id, target_role=target_role,
            resume_text=resume_text,
        )
        existing = await self._repository.get(user_id)
        if existing is None:
            memory = LongTermMemory(
                user_id=user_id, active_resume_id=resume_id, resume_snapshots=[snapshot]
            )
            if run_id:
                memory.resume_activation_runs[run_id] = ResumeActivationRun(
                    run_id=run_id, resume_id=resume_id, fingerprint=fingerprint
                )
            return await self._repository.create(memory)
        existing_run = existing.resume_activation_runs.get(run_id) if run_id else None
        if existing_run is not None:
            if existing_run.resume_id != resume_id or existing_run.fingerprint != fingerprint:
                raise ConsistencyError("同一 resume activation runId 不能提交不同的输入")
            return existing
        expected_version = existing.state_version
        existing.active_resume_id = resume_id
        existing.resume_snapshots = self._merge_resume_snapshot(existing.resume_snapshots, snapshot)
        # These three fields are derived from a resume evaluation.  Clearing them here
        # removes an already-finished old evaluation before the new evaluation arrives.
        existing.technical_stack = []
        existing.technical_depth = []
        existing.preferences = []
        if run_id:
            existing.resume_activation_runs[run_id] = ResumeActivationRun(
                run_id=run_id, resume_id=resume_id, fingerprint=fingerprint
            )
            while len(existing.resume_activation_runs) > self._policy.max_resume_evaluation_runs:
                existing.resume_activation_runs.pop(next(iter(existing.resume_activation_runs)))
        existing.updated_at = datetime.now(timezone.utc)
        return await self._repository.save(existing, expected_version=expected_version)

    async def build_context(self, session: InterviewSession) -> MemoryContext:
        memory = await self._repository.get(session.user_id)
        if memory is None:
            return MemoryContext.empty(session).model_copy(
                update={
                    "recent_turns": session.turns[-self._policy.short_term_turn_limit :],
                    "conversation_summary": getattr(session, "history_summary", ""),
                }
            )
        active_resume = next((item for item in memory.resume_snapshots if item.resume_id == session.resume_id), None)
        return MemoryContext(
            recent_turns=session.turns[-self._policy.short_term_turn_limit :],
            conversation_summary=getattr(session, "history_summary", ""),
            historical_summary=memory.historical_summary,
            active_resume=active_resume,
            technical_stack=memory.technical_stack,
            technical_depth=memory.technical_depth,
            preferences=memory.preferences,
            weak_topics=memory.weak_topics,
            notes=memory.notes,
            question_catalog=memory.question_catalog,
        )

    async def record_turn(self, *, session: InterviewSession, turn: TurnRecord) -> LongTermMemory | None:
        memory = await self._repository.get(session.user_id)
        if memory is None:
            return None
        if turn.turn_id in memory.recorded_turn_ids:
            return memory
        expected_version = memory.state_version
        topic = turn.topic or turn.stage.value
        event = f"[{session.session_id}/{turn.stage}/{topic}] score={turn.score}; {turn.evaluation_summary}"
        memory.historical_summary = self._append_summary(memory.historical_summary, event)
        memory.question_catalog = self._merge_items(memory.question_catalog, [turn.question], limit=100)
        memory.weak_topics = self._merge_items(memory.weak_topics, turn.weaknesses, limit=30)
        memory.notes = self._merge_items(memory.notes, turn.strengths, limit=30)
        memory.preferences = self._merge_items(memory.preferences, turn.preferences, limit=30)
        memory.recorded_turn_ids = self._merge_items(memory.recorded_turn_ids, [turn.turn_id], limit=500)
        memory.updated_at = datetime.now(timezone.utc)
        try:
            return await self._repository.save(memory, expected_version=expected_version)
        except ConsistencyError:
            latest = await self._repository.get(session.user_id)
            if latest is not None and turn.turn_id in latest.recorded_turn_ids:
                return latest
            raise

    async def finalize_session(self, *, session: InterviewSession, interrupted: bool = False) -> LongTermMemory | None:
        memory = await self._repository.get(session.user_id)
        if memory is None:
            return None
        if session.session_id in memory.finalized_session_ids:
            return memory
        expected_version = memory.state_version
        scores = [turn.score for turn in session.turns]
        average = round(sum(scores) / len(scores)) if scores else 0
        weaknesses = [item for turn in session.turns for item in turn.weaknesses]
        strengths = [item for turn in session.turns for item in turn.strengths]
        summary = (
            f"session={session.session_id}; {'interrupted' if interrupted else 'completed'}; "
            f"turns={len(session.turns)}; averageScore={average}; "
            f"summary={session.final_summary or 'No final summary'}"
        )
        memory.historical_summary = self._append_summary(memory.historical_summary, summary)
        memory.interview_summaries = self._merge_items(memory.interview_summaries, [summary], limit=20)
        memory.question_catalog = self._merge_items(memory.question_catalog, [turn.question for turn in session.turns], limit=100)
        memory.weak_topics = self._merge_items(memory.weak_topics, weaknesses, limit=30)
        memory.notes = self._merge_items(memory.notes, strengths, limit=30)
        memory.finalized_session_ids = self._merge_items(memory.finalized_session_ids, [session.session_id], limit=100)
        memory.updated_at = datetime.now(timezone.utc)
        try:
            return await self._repository.save(memory, expected_version=expected_version)
        except ConsistencyError:
            latest = await self._repository.get(session.user_id)
            if latest is not None and session.session_id in latest.finalized_session_ids:
                return latest
            raise

    async def record_resume_analysis(
        self, *, user_id: str, resume_id: str, candidate_id: str, resume_text: str,
        target_role: str, summary: str, questions: list[str], priorities: list[str],
        suggestions: list[str], technical_stack: list[str], technical_depth: list[str],
        career_preferences: list[str], run_id: str | None = None,
        evaluation_fingerprint: str | None = None,
        evaluation: ResumeEvaluation | None = None,
    ) -> LongTermMemory | None:
        memory = await self._repository.get(user_id)
        if memory is None:
            return None
        existing_run = memory.resume_evaluation_runs.get(run_id) if run_id else None
        if existing_run is not None:
            if (existing_run.resume_id != resume_id
                    or existing_run.fingerprint != evaluation_fingerprint):
                raise ConsistencyError("同一 resume evaluation runId 不能提交不同的输入")
            return memory
        if memory.active_resume_id != resume_id:
            return None
        expected_version = memory.state_version
        snapshots: list[ResumeMemory] = []
        matched_resume = False
        for snapshot in memory.resume_snapshots:
            if snapshot.resume_id == resume_id:
                matched_resume = True
                snapshot = snapshot.model_copy(update={
                    "analysis_summary": summary,
                    "analysis_questions": questions[:20],
                    "analysis_priorities": priorities[:20],
                    "analysis_suggestions": suggestions[:20],
                    "updated_at": datetime.now(timezone.utc),
                })
            snapshots.append(snapshot)
        if not matched_resume:
            snapshots.append(ResumeMemory(
                resume_id=resume_id, candidate_id=candidate_id, target_role=target_role,
                resume_text=resume_text, analysis_summary=summary,
                analysis_questions=questions[:20], analysis_priorities=priorities[:20],
                analysis_suggestions=suggestions[:20],
            ))
        memory.resume_snapshots = snapshots[: self._policy.max_resume_snapshots]
        # 同一简历重复评估时使用替换语义，避免旧版本分析结果不断累积；
        # activate_resume 已经保证了旧简历不会覆盖当前版本。
        memory.technical_stack = self._unique_items(technical_stack, limit=30)
        memory.technical_depth = self._unique_items(technical_depth, limit=30)
        memory.notes = self._unique_items(suggestions, limit=30)
        memory.preferences = self._unique_items(career_preferences, limit=30)
        if run_id and evaluation_fingerprint and evaluation is not None:
            memory.resume_evaluation_runs[run_id] = ResumeEvaluationRun(
                run_id=run_id,
                resume_id=resume_id,
                fingerprint=evaluation_fingerprint,
                evaluation=evaluation,
            )
            while len(memory.resume_evaluation_runs) > self._policy.max_resume_evaluation_runs:
                memory.resume_evaluation_runs.pop(next(iter(memory.resume_evaluation_runs)))
        memory.updated_at = datetime.now(timezone.utc)
        return await self._repository.save(memory, expected_version=expected_version)

    async def get_resume_evaluation_run(
        self, *, user_id: str, resume_id: str, run_id: str, evaluation_fingerprint: str
    ):
        memory = await self._repository.get(user_id)
        if memory is None:
            return None
        existing_run = memory.resume_evaluation_runs.get(run_id)
        if existing_run is None:
            return None
        if (existing_run.resume_id != resume_id
                or existing_run.fingerprint != evaluation_fingerprint):
            raise ConsistencyError("同一 resume evaluation runId 不能提交不同的输入")
        return existing_run.evaluation

    def _merge_resume_snapshot(self, snapshots: list[ResumeMemory], incoming: ResumeMemory) -> list[ResumeMemory]:
        remaining = [item for item in snapshots if item.resume_id != incoming.resume_id]
        return [incoming, *remaining][: self._policy.max_resume_snapshots]

    def _append_summary(self, current: str, event: str) -> str:
        return f"{current}\n{event}".strip()[-self._policy.history_summary_max_characters :]

    @staticmethod
    def _resume_activation_fingerprint(
        *, resume_id: str, candidate_id: str, resume_text: str, target_role: str
    ) -> str:
        canonical = json.dumps({
            "resumeId": resume_id,
            "candidateId": candidate_id,
            "resumeText": resume_text,
            "targetRole": target_role,
        }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        return hashlib.sha256(canonical.encode("utf-8")).hexdigest()

    @staticmethod
    def _merge_items(current: list[str], incoming: list[str], *, limit: int) -> list[str]:
        merged = [item.strip() for item in [*current, *incoming] if item and item.strip()]
        return list(dict.fromkeys(merged))[-limit:]

    @staticmethod
    def _unique_items(items: list[str], *, limit: int) -> list[str]:
        values = [item.strip() for item in items if item and item.strip()]
        return list(dict.fromkeys(values))[:limit]
