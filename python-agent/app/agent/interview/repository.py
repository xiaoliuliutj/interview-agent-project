"""面试会话持久化端口。"""

from typing import Protocol

from .models import InterviewSession


class InterviewSessionRepository(Protocol):
    async def create(self, session: InterviewSession) -> InterviewSession: ...

    async def get(self, session_id: str) -> InterviewSession | None: ...

    async def save(
        self, session: InterviewSession, *, expected_version: int
    ) -> InterviewSession: ...
