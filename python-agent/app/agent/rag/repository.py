"""向量仓库端口与测试用内存实现。"""

from math import sqrt
from typing import Protocol

from .models import KnowledgeChunk, RagSearchResult
from app.core.exceptions import RagFilterUnsupported


class VectorRepository(Protocol):
    async def delete_by_knowledge_base(self, knowledge_base_id: str) -> None: ...

    async def add(self, chunks: list[KnowledgeChunk]) -> None: ...

    async def search(
        self,
        query_embedding: list[float],
        *,
        top_k: int,
        min_score: float,
        knowledge_base_ids: tuple[str, ...],
        apply_metadata_filter: bool = True,
    ) -> list[RagSearchResult]: ...


class InMemoryVectorRepository:
    """仅用于单元测试；生产环境使用 PostgreSQL/pgvector 实现。"""

    def __init__(self, *, supports_metadata_filter: bool = True) -> None:
        self._chunks: dict[str, KnowledgeChunk] = {}
        self._supports_metadata_filter = supports_metadata_filter

    async def delete_by_knowledge_base(self, knowledge_base_id: str) -> None:
        self._chunks = {
            key: value
            for key, value in self._chunks.items()
            if value.knowledge_base_id != knowledge_base_id
        }

    async def add(self, chunks: list[KnowledgeChunk]) -> None:
        self._chunks.update({chunk.chunk_id: chunk.model_copy(deep=True) for chunk in chunks})

    async def search(
        self,
        query_embedding: list[float],
        *,
        top_k: int,
        min_score: float,
        knowledge_base_ids: tuple[str, ...],
        apply_metadata_filter: bool = True,
    ) -> list[RagSearchResult]:
        if apply_metadata_filter and not self._supports_metadata_filter:
            raise RagFilterUnsupported()
        candidates = list(self._chunks.values())
        if apply_metadata_filter and knowledge_base_ids:
            candidates = [
                item for item in candidates if item.knowledge_base_id in knowledge_base_ids
            ]
        results = [
            RagSearchResult(chunk=item, score=_cosine(query_embedding, item.embedding))
            for item in candidates
        ]
        return [
            item
            for item in sorted(results, key=lambda result: result.score, reverse=True)
            if item.score >= min_score
        ][:top_k]


def _cosine(left: list[float], right: list[float]) -> float:
    if not left or not right or len(left) != len(right):
        return 0.0
    denominator = sqrt(sum(value * value for value in left)) * sqrt(
        sum(value * value for value in right)
    )
    return sum(a * b for a, b in zip(left, right)) / denominator if denominator else 0.0
