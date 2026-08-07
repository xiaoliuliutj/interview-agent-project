"""PostgreSQL 异步连接与开发期建表入口。"""

from sqlalchemy.ext.asyncio import AsyncEngine, AsyncSession, async_sessionmaker, create_async_engine

from app.core.config import Settings, get_settings
from app.core.exceptions import PersistenceConfigurationError


def create_engine(settings: Settings | None = None) -> AsyncEngine:
    current = settings or get_settings()
    if not current.database_url:
        raise PersistenceConfigurationError("DATABASE_URL 未配置")
    return create_async_engine(current.database_url, pool_pre_ping=True)


def create_session_factory(
    settings: Settings | None = None,
) -> async_sessionmaker[AsyncSession]:
    return async_sessionmaker(create_engine(settings), expire_on_commit=False)


async def create_schema(engine: AsyncEngine) -> None:
    """开发期建表入口；生产部署将改由 Alembic 迁移执行。"""

    from .interview_session_repository import Base
    from .long_term_memory_repository import LongTermMemoryEntity
    from .rag_vector_repository import RagChunkEntity

    async with engine.begin() as connection:
        await connection.run_sync(Base.metadata.create_all)
