"""RAG indexing, retrieval, replacement, and deletion."""

import asyncio
from pathlib import Path
from time import monotonic

from app.core.exceptions import RagDependencyError, RagFilterUnsupported

from .embedding import EmbeddingProvider
from .models import KnowledgeDocument, RagSearchResult, RagUseCase
from .parser import KnowledgeDocumentParser, TokenChunker
from .policy import RagPolicy
from .repository import VectorRepository


class RagService:
    def __init__(
        self,
        repository: VectorRepository,
        embedding_provider: EmbeddingProvider,
        policy: RagPolicy,
        parser: KnowledgeDocumentParser | None = None,
    ) -> None:
        self._repository = repository
        self._embedding_provider = embedding_provider
        self._policy = policy
        self._parser = parser or KnowledgeDocumentParser()
        self._chunker = TokenChunker(
            chunk_size_tokens=policy.chunk_size_tokens,
            overlap_tokens=policy.chunk_overlap_tokens,
        )
        self._search_cache: dict[str, tuple[float, list[RagSearchResult]]] = {}
        self._knowledge_base_locks: dict[str, asyncio.Lock] = {}

    async def index_document(self, document: KnowledgeDocument) -> int:
        chunks = self._chunker.split(document)
        async with self._lock_for(document.knowledge_base_id):
            try:
                for start in range(0, len(chunks), self._policy.embedding_batch_size):
                    batch = chunks[start : start + self._policy.embedding_batch_size]
                    vectors = await self._embedding_provider.embed_documents(
                        [chunk.content for chunk in batch]
                    )
                    if len(vectors) != len(batch):
                        raise RagDependencyError("embedding result count does not match chunk count")
                    for chunk, vector in zip(batch, vectors):
                        chunk.embedding = vector
                await self._repository.replace_for_knowledge_base(document.knowledge_base_id, chunks)
            except RagDependencyError:
                raise
            except Exception as error:
                raise RagDependencyError("RAG document embedding failed") from error
        self.invalidate_cache()
        return len(chunks)

    async def delete_knowledge_base(self, knowledge_base_id: str) -> None:
        if not knowledge_base_id.strip():
            raise ValueError("knowledge_base_id is required")
        async with self._lock_for(knowledge_base_id):
            await self._repository.delete_by_knowledge_base(knowledge_base_id)
        self.invalidate_cache()

    async def index_file(
        self,
        path: Path,
        *,
        knowledge_base_id: str,
        document_id: str,
    ) -> int:
        return await self.index_document(self._parser.parse_file(
            path, knowledge_base_id=knowledge_base_id, document_id=document_id
        ))

    async def search(
        self,
        query: str,
        *,
        use_case: RagUseCase,
        knowledge_base_ids: tuple[str, ...] | None = None,
        top_k: int | None = None,
        min_score: float | None = None,
    ) -> list[RagSearchResult]:
        if use_case not in self._policy.allowed_use_cases:
            raise ValueError(f"RAG use case is not allowed: {use_case}")
        normalized_query = query.strip()
        if not normalized_query:
            return []
        if not knowledge_base_ids:
            raise ValueError("knowledge_base_ids must be provided explicitly")
        selected_kbs = tuple(dict.fromkeys(knowledge_base_ids))
        selected_top_k = top_k or self._policy.default_top_k
        selected_min_score = self._policy.default_min_score if min_score is None else min_score
        cache_key = "|".join([
            str(use_case), ",".join(sorted(selected_kbs)), normalized_query.lower(),
            str(selected_top_k), str(selected_min_score),
        ])
        cached = self._search_cache.get(cache_key)
        if cached and (
            self._policy.cache_ttl_seconds == 0
            or monotonic() - cached[0] < self._policy.cache_ttl_seconds
        ):
            return [item.model_copy(deep=True) for item in cached[1]]
        query_vector = await self._embedding_provider.embed_query(normalized_query)
        try:
            results = await self._repository.search(
                query_vector, top_k=selected_top_k, min_score=selected_min_score,
                knowledge_base_ids=selected_kbs, apply_metadata_filter=True,
            )
        except RagFilterUnsupported:
            fallback = await self._repository.search(
                query_vector,
                top_k=selected_top_k * self._policy.fallback_candidate_multiplier,
                min_score=selected_min_score,
                knowledge_base_ids=selected_kbs,
                apply_metadata_filter=False,
            )
            results = [
                result for result in fallback
                if result.chunk.knowledge_base_id in selected_kbs
            ][:selected_top_k]
        self._search_cache[cache_key] = (
            monotonic(), [item.model_copy(deep=True) for item in results]
        )
        while len(self._search_cache) > self._policy.cache_max_entries:
            self._search_cache.pop(next(iter(self._search_cache)))
        return results

    def invalidate_cache(self) -> None:
        self._search_cache.clear()

    def _lock_for(self, knowledge_base_id: str) -> asyncio.Lock:
        lock = self._knowledge_base_locks.get(knowledge_base_id)
        if lock is None:
            lock = asyncio.Lock()
            self._knowledge_base_locks[knowledge_base_id] = lock
        return lock


class RagSearchTool:
    """Internal interview RAG tool; it has no Java business semantics."""

    def __init__(self, service: RagService) -> None:
        self._service = service

    async def search_for_question_generation(
        self, query: str, *, knowledge_base_ids: tuple[str, ...] | None = None
    ) -> list[RagSearchResult]:
        return await self._service.search(
            query,
            use_case=RagUseCase.QUESTION_GENERATION,
            knowledge_base_ids=knowledge_base_ids,
        )
