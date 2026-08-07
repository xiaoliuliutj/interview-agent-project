"""基于 PostgreSQL 的 Agent 面试会话持久化。"""

from datetime import datetime, timezone

from sqlalchemy import DateTime, Integer, JSON, String, select, update
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

from app.agent.interview.models import InterviewSession
from app.agent.interview.repository import InterviewSessionRepository
from app.core.exceptions import ConsistencyError


class Base(DeclarativeBase):
    pass


class InterviewSessionEntity(Base):
    __tablename__ = "agent_interview_sessions"

    session_id: Mapped[str] = mapped_column(String(128), primary_key=True)
    user_id: Mapped[str] = mapped_column(String(128), nullable=False, index=True)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    current_stage: Mapped[str] = mapped_column(String(32), nullable=False)
    state_version: Mapped[int] = mapped_column(Integer, nullable=False)
    session_data: Mapped[dict] = mapped_column(JSON, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)
    updated_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False)


class PostgresInterviewSessionRepository(InterviewSessionRepository):
    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory

    async def create(self, session: InterviewSession) -> InterviewSession:
        entity = self._to_entity(session)
        try:
            async with self._session_factory() as db_session:
                db_session.add(entity)
                await db_session.commit()
        except IntegrityError as error:
            raise ConsistencyError("Agent 会话已存在") from error
        return session

    async def get(self, session_id: str) -> InterviewSession | None:
        async with self._session_factory() as db_session:
            entity = await db_session.scalar(
                select(InterviewSessionEntity).where(
                    InterviewSessionEntity.session_id == session_id
                )
            )
        return self._from_entity(entity) if entity is not None else None

    async def save(
        self, session: InterviewSession, *, expected_version: int
    ) -> InterviewSession:
        next_version = expected_version + 1
        saved_session = session.model_copy(
            update={
                "state_version": next_version,
                "updated_at": datetime.now(timezone.utc),
            }
        )
        payload = saved_session.model_dump(mode="json")
        statement = (
            update(InterviewSessionEntity)
            .where(
                InterviewSessionEntity.session_id == saved_session.session_id,
                InterviewSessionEntity.state_version == expected_version,
            )
            .values(
                status=saved_session.status,
                current_stage=saved_session.current_stage,
                state_version=next_version,
                session_data=payload,
                updated_at=saved_session.updated_at,
            )
        )
        async with self._session_factory() as db_session:
            result = await db_session.execute(statement)
            if result.rowcount != 1:
                await db_session.rollback()
                raise ConsistencyError("Agent 会话状态已被并发修改")
            await db_session.commit()
        return saved_session

    @staticmethod
    def _to_entity(session: InterviewSession) -> InterviewSessionEntity:
        return InterviewSessionEntity(
            session_id=session.session_id,
            user_id=session.user_id,
            status=session.status,
            current_stage=session.current_stage,
            state_version=session.state_version,
            session_data=session.model_dump(mode="json"),
            created_at=session.created_at,
            updated_at=session.updated_at,
        )

    @staticmethod
    def _from_entity(entity: InterviewSessionEntity) -> InterviewSession:
        return InterviewSession.model_validate(entity.session_data)
