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
            f"{turn.evaluation_summary}"
        )
        memory.historical_summary = self._append_summary(
            memory.historical_summary, event
        )
        memory.updated_at = datetime.now(timezone.utc)
        try:
            return await self._repository.save(memory, expected_version=expected_version)
        except ConsistencyError:
            # 会话写入已成功；长期摘要是可重建的派生数据，交给本次调用的重试机制补偿。
            raise

    def _merge_resume_snapshot(
        self, snapshots: list[ResumeMemory], incoming: ResumeMemory
    ) -> list[ResumeMemory]:
        remaining = [item for item in snapshots if item.resume_id != incoming.resume_id]
        return [incoming, *remaining][: self._policy.max_resume_snapshots]

    def _append_summary(self, current: str, event: str) -> str:
        combined = f"{current}\n{event}".strip()
        return combined[-self._policy.history_summary_max_characters :]
