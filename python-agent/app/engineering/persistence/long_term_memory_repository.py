"""基于 PostgreSQL 的用户长期记忆持久化。"""

from datetime import datetime, timezone

from sqlalchemy import DateTime, Integer, JSON, String, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from sqlalchemy.orm import Mapped, mapped_column

from app.agent.memory.models import LongTermMemory
from app.agent.memory.repository import LongTermMemoryRepository
from app.core.exceptions import ConsistencyError

from .interview_session_repository import Base


class LongTermMemoryEntity(Base):
    __tablename__ = "agent_long_term_memories"

    user_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    state_version: Mapped[int] = mapped_column(Integer, nullable=False)
    memory_data: Mapped[dict] = mapped_column(JSON, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class PostgresLongTermMemoryRepository(LongTermMemoryRepository):
    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory

    async def get(self, user_id: str) -> LongTermMemory | None:
        async with self._session_factory() as db_session:
            entity = await db_session.scalar(
                select(LongTermMemoryEntity).where(
                    LongTermMemoryEntity.user_id == user_id
                )
            )
        return LongTermMemory.model_validate(entity.memory_data) if entity else None

    async def create(self, memory: LongTermMemory) -> LongTermMemory:
        try:
            async with self._session_factory() as db_session:
                db_session.add(self._to_entity(memory))
                await db_session.commit()
        except IntegrityError as error:
            raise ConsistencyError("用户长期记忆已存在") from error
        return memory

    async def save(
        self, memory: LongTermMemory, *, expected_version: int
    ) -> LongTermMemory:
        saved = memory.model_copy(
            update={
                "state_version": expected_version + 1,
                "updated_at": datetime.now(timezone.utc),
            }
        )
        statement = (
            update(LongTermMemoryEntity)
            .where(
                LongTermMemoryEntity.user_id == saved.user_id,
                LongTermMemoryEntity.state_version == expected_version,
            )
            .values(
                state_version=saved.state_version,
                memory_data=saved.model_dump(mode="json"),
                updated_at=saved.updated_at,
            )
        )
        async with self._session_factory() as db_session:
            result = await db_session.execute(statement)
            if result.rowcount != 1:
                await db_session.rollback()
                raise ConsistencyError("用户长期记忆已被并发修改")
            await db_session.commit()
        return saved

    @staticmethod
    def _to_entity(memory: LongTermMemory) -> LongTermMemoryEntity:
        return LongTermMemoryEntity(
            user_id=memory.user_id,
            state_version=memory.state_version,
            memory_data=memory.model_dump(mode="json"),
            created_at=memory.created_at,
            updated_at=memory.updated_at,
        )
