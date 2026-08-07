"""RAG 索引、检索和回退逻辑。"""

from pathlib import Path

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

    async def index_document(self, document: KnowledgeDocument) -> int:
        chunks = self._chunker.split(document)
        await self._repository.delete_by_knowledge_base(document.knowledge_base_id)
        try:
            for start in range(0, len(chunks), self._policy.embedding_batch_size):
                batch = chunks[start : start + self._policy.embedding_batch_size]
                vectors = await self._embedding_provider.embed_documents(
                    [chunk.content for chunk in batch]
                )
                if len(vectors) != len(batch):
                    raise RagDependencyError("Embedding 返回数量与文本分片数量不一致")
                for chunk, vector in zip(batch, vectors):
                    chunk.embedding = vector
                await self._repository.add(batch)
        except RagDependencyError:
            raise
        except Exception as error:
            raise RagDependencyError("RAG 文档向量化失败") from error
        return len(chunks)

    async def index_file(
        self,
        path: Path,
        *,
        knowledge_base_id: str,
        document_id: str,
    ) -> int:
        document = self._parser.parse_file(
            path,
            knowledge_base_id=knowledge_base_id,
            document_id=document_id,
        )
        return await self.index_document(document)

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
            raise ValueError(f"RAG 用途未开放: {use_case}")
        normalized_query = query.strip()
        if not normalized_query:
            return []
        selected_kbs = knowledge_base_ids or self._policy.default_knowledge_base_ids
        selected_top_k = top_k or self._policy.default_top_k
        selected_min_score = (
            self._policy.default_min_score if min_score is None else min_score
        )
        query_vector = await self._embedding_provider.embed_query(normalized_query)
        try:
            return await self._repository.search(
                query_vector,
                top_k=selected_top_k,
                min_score=selected_min_score,
                knowledge_base_ids=selected_kbs,
                apply_metadata_filter=True,
            )
        except RagFilterUnsupported:
            fallback = await self._repository.search(
                query_vector,
                top_k=selected_top_k * self._policy.fallback_candidate_multiplier,
                min_score=selected_min_score,
                knowledge_base_ids=selected_kbs,
                apply_metadata_filter=False,
            )
            return [
                result
                for result in fallback
                if not selected_kbs
                or result.chunk.knowledge_base_id in selected_kbs
            ][:selected_top_k]


class RagSearchTool:
    """Agent 可调用的通用检索 Tool，不暴露 Java 业务概念。"""

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

    async def search_for_resume_evaluation(
        self, query: str, *, knowledge_base_ids: tuple[str, ...] | None = None
    ) -> list[RagSearchResult]:
        return await self._service.search(
            query,
            use_case=RagUseCase.RESUME_EVALUATION,
            knowledge_base_ids=knowledge_base_ids,
        )
