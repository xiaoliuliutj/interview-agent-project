"""记忆读取与写入编排。"""

from datetime import datetime, timezone

from app.agent.interview.models import CandidateProfile, InterviewSession, TurnRecord
from app.core.exceptions import ConsistencyError

from .models import LongTermMemory, MemoryContext, ResumeMemory, to_resume_memory
from .policy import MemoryPolicy
from .repository import LongTermMemoryRepository


class MemoryService:
    def __init__(
        self,
        repository: LongTermMemoryRepository,
        policy: MemoryPolicy,
    ) -> None:
        self._repository = repository
        self._policy = policy

    async def initialize_user_memory(
        self, *, user_id: str, profile: CandidateProfile
    ) -> LongTermMemory:
        existing = await self._repository.get(user_id)
        if existing is None:
            memory = LongTermMemory(
                user_id=user_id,
                resume_snapshots=[to_resume_memory(profile)],
            )
            return await self._repository.create(memory)

        expected_version = existing.state_version
        existing.resume_snapshots = self._merge_resume_snapshot(
            existing.resume_snapshots, to_resume_memory(profile)
        )
        existing.updated_at = datetime.now(timezone.utc)
        return await self._repository.save(existing, expected_version=expected_version)

    async def build_context(self, session: InterviewSession) -> MemoryContext:
        memory = await self._repository.get(session.user_id)
        if memory is None:
            return MemoryContext.empty(session).model_copy(
                update={"recent_turns": session.turns[-self._policy.short_term_turn_limit :]}
            )
        active_resume = next(
            (item for item in memory.resume_snapshots if item.resume_id == session.resume_id),
            None,
        )
        return MemoryContext(
            recent_turns=session.turns[-self._policy.short_term_turn_limit :],
            historical_summary=memory.historical_summary,
            active_resume=active_resume,
            preferences=memory.preferences,
            weak_topics=memory.weak_topics,
            notes=memory.notes,
            question_catalog=memory.question_catalog,
        )

    async def record_turn(
        self, *, session: InterviewSession, turn: TurnRecord
    ) -> LongTermMemory | None:
        memory = await self._repository.get(session.user_id)
        if memory is None:
            return None
        expected_version = memory.state_version
        event = (
            f"[{session.session_id}/{turn.stage}] "
            f"score={turn.score}; {turn.evaluation_summary}"
        )
        memory.historical_summary = self._append_summary(
            memory.historical_summary, event
        )
        memory.question_catalog = self._merge_items(
            memory.question_catalog, [turn.question], limit=100
        )
        memory.weak_topics = self._merge_items(
            memory.weak_topics, turn.weaknesses, limit=30
        )
        memory.notes = self._merge_items(
            memory.notes, turn.strengths, limit=30
        )
        memory.preferences = self._merge_items(
            memory.preferences, turn.preferences, limit=30
        )
        memory.updated_at = datetime.now(timezone.utc)
        try:
            return await self._repository.save(memory, expected_version=expected_version)
        except ConsistencyError:
            # 会话写入已成功；长期摘要是可重建的派生数据，交给本次调用的重试机制补偿。
            raise

    async def finalize_session(
        self, *, session: InterviewSession, interrupted: bool = False
    ) -> LongTermMemory | None:
        """把完整会话压缩为跨会话记忆；正常结束和中断都调用。"""
        memory = await self._repository.get(session.user_id)
        if memory is None:
            return None
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
        memory.interview_summaries = self._merge_items(
            memory.interview_summaries, [summary], limit=20
        )
        memory.question_catalog = self._merge_items(
            memory.question_catalog, [turn.question for turn in session.turns], limit=100
        )
        memory.weak_topics = self._merge_items(memory.weak_topics, weaknesses, limit=30)
        memory.notes = self._merge_items(memory.notes, strengths, limit=30)
        memory.updated_at = datetime.now(timezone.utc)
        return await self._repository.save(memory, expected_version=expected_version)

    async def record_resume_analysis(
        self,
        *,
        user_id: str,
        resume_id: str,
        candidate_id: str,
        resume_text: str = "",
        target_role: str = "",
        summary: str,
        questions: list[str],
        priorities: list[str],
        suggestions: list[str],
    ) -> LongTermMemory | None:
        memory = await self._repository.get(user_id)
        if memory is None:
            memory = LongTermMemory(
                user_id=user_id,
                resume_snapshots=[ResumeMemory(
                    resume_id=resume_id, candidate_id=candidate_id, target_role=target_role,
                    resume_text=resume_text
                )],
            )
            await self._repository.create(memory)
        expected_version = memory.state_version
        snapshots = []
        for snapshot in memory.resume_snapshots:
            if snapshot.resume_id == resume_id:
                snapshot = snapshot.model_copy(update={
                    "analysis_summary": summary,
                    "analysis_questions": questions[:20],
                    "analysis_priorities": priorities[:20],
                    "analysis_suggestions": suggestions[:20],
                })
            snapshots.append(snapshot)
        memory.resume_snapshots = snapshots
        memory.notes = self._merge_items(memory.notes, suggestions, limit=30)
        memory.updated_at = datetime.now(timezone.utc)
        return await self._repository.save(memory, expected_version=expected_version)

    def _merge_resume_snapshot(
        self, snapshots: list[ResumeMemory], incoming: ResumeMemory
    ) -> list[ResumeMemory]:
        remaining = [item for item in snapshots if item.resume_id != incoming.resume_id]
        return [incoming, *remaining][: self._policy.max_resume_snapshots]

    def _append_summary(self, current: str, event: str) -> str:
        combined = f"{current}\n{event}".strip()
        return combined[-self._policy.history_summary_max_characters :]

    @staticmethod
    def _merge_items(current: list[str], incoming: list[str], *, limit: int) -> list[str]:
        merged = [item.strip() for item in [*current, *incoming] if item and item.strip()]
        return list(dict.fromkeys(merged))[-limit:]
