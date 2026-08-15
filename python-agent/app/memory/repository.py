"""长期记忆持久化端口。"""

from typing import Protocol

from .models import LongTermMemory


class LongTermMemoryRepository(Protocol):
    async def get(self, user_id: str) -> LongTermMemory | None: ...

    async def create(self, memory: LongTermMemory) -> LongTermMemory: ...

    async def save(
        self, memory: LongTermMemory, *, expected_version: int
    ) -> LongTermMemory: ...
