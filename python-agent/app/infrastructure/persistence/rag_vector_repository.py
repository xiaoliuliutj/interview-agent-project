"""基于 PostgreSQL/pgvector 的 RAG 向量仓库。"""

from sqlalchemy import Integer, JSON, String, Text, delete, select
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from sqlalchemy.orm import Mapped, mapped_column
from pgvector.sqlalchemy import Vector

from app.rag.models import KnowledgeChunk, RagSearchResult
from app.rag.repository import VectorRepository

from .interview_session_repository import Base


class RagChunkEntity(Base):
    __tablename__ = "agent_rag_chunks"

    chunk_id: Mapped[str] = mapped_column(String(256), primary_key=True)
    knowledge_base_id: Mapped[str] = mapped_column(String(128), nullable=False, index=True)
    document_id: Mapped[str] = mapped_column(String(256), nullable=False, index=True)
    source_name: Mapped[str] = mapped_column(String(512), nullable=False)
    chunk_index: Mapped[int] = mapped_column(Integer, nullable=False)
    content: Mapped[str] = mapped_column(Text, nullable=False)
    chunk_metadata: Mapped[dict] = mapped_column(JSON, nullable=False)
    embedding: Mapped[list[float]] = mapped_column(Vector(), nullable=False)


class PostgresRagVectorRepository(VectorRepository):
    def __init__(self, session_factory: async_sessionmaker[AsyncSession]) -> None:
        self._session_factory = session_factory

    async def delete_by_knowledge_base(self, knowledge_base_id: str) -> None:
        from sqlalchemy import delete

        async with self._session_factory() as db_session:
            await db_session.execute(
                delete(RagChunkEntity).where(
                    RagChunkEntity.knowledge_base_id == knowledge_base_id
                )
            )
            await db_session.commit()

    async def add(self, chunks: list[KnowledgeChunk]) -> None:
        async with self._session_factory() as db_session:
            db_session.add_all([self._to_entity(chunk) for chunk in chunks])
            await db_session.commit()

    async def replace_for_knowledge_base(
        self, knowledge_base_id: str, chunks: list[KnowledgeChunk]
    ) -> None:
        async with self._session_factory() as db_session:
            await db_session.execute(
                delete(RagChunkEntity).where(
                    RagChunkEntity.knowledge_base_id == knowledge_base_id
                )
            )
            db_session.add_all([self._to_entity(chunk) for chunk in chunks])
            await db_session.commit()

    async def search(
        self,
        query_embedding: list[float],
        *,
        top_k: int,
        min_score: float,
        knowledge_base_ids: tuple[str, ...],
        apply_metadata_filter: bool = True,
    ) -> list[RagSearchResult]:
        distance = RagChunkEntity.embedding.cosine_distance(query_embedding)
        score = (1 - distance).label("score")
        statement = select(RagChunkEntity, score)
        if apply_metadata_filter and knowledge_base_ids:
            statement = statement.where(
                RagChunkEntity.knowledge_base_id.in_(knowledge_base_ids)
            )
        statement = (
            statement.where(score >= min_score)
            .order_by(distance)
            .limit(top_k)
        )
        async with self._session_factory() as db_session:
            rows = (await db_session.execute(statement)).all()
        return [
            RagSearchResult(chunk=self._from_entity(entity), score=float(raw_score))
            for entity, raw_score in rows
        ]

    @staticmethod
    def _to_entity(chunk: KnowledgeChunk) -> RagChunkEntity:
        return RagChunkEntity(
            chunk_id=chunk.chunk_id,
            knowledge_base_id=chunk.knowledge_base_id,
            document_id=chunk.document_id,
            source_name=chunk.source_name,
            chunk_index=chunk.chunk_index,
            content=chunk.content,
            chunk_metadata=chunk.metadata,
            embedding=chunk.embedding,
        )

    @staticmethod
    def _from_entity(entity: RagChunkEntity) -> KnowledgeChunk:
        return KnowledgeChunk(
            chunk_id=entity.chunk_id,
            knowledge_base_id=entity.knowledge_base_id,
            document_id=entity.document_id,
            source_name=entity.source_name,
            chunk_index=entity.chunk_index,
            content=entity.content,
            metadata=entity.chunk_metadata,
            embedding=list(entity.embedding),
        )
