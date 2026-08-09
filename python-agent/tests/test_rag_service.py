import pytest

from app.agent.rag.models import KnowledgeDocument, RagUseCase
from app.agent.rag.parser import TokenChunker
from app.agent.rag.policy import RagPolicy
from app.agent.rag.repository import InMemoryVectorRepository
from app.agent.rag.service import RagService
from app.core.exceptions import RagDependencyError


class FakeEmbeddingProvider:
    def __init__(self) -> None:
        self.document_batches: list[int] = []

    async def embed_documents(self, texts: list[str]) -> list[list[float]]:
        self.document_batches.append(len(texts))
        return [[1.0, 0.0] for _ in texts]

    async def embed_query(self, text: str) -> list[float]:
        return [1.0, 0.0]


class FailingEmbeddingProvider(FakeEmbeddingProvider):
    def __init__(self) -> None:
        super().__init__()
        self.fail_document_embedding = False

    async def embed_documents(self, texts: list[str]) -> list[list[float]]:
        if self.fail_document_embedding:
            raise ConnectionError("embedding provider unavailable")
        return await super().embed_documents(texts)


def build_policy() -> RagPolicy:
    return RagPolicy.load()


@pytest.mark.asyncio
async def test_index_uses_800_token_chunks_and_batch_size_ten() -> None:
    embedding = FakeEmbeddingProvider()
    service = RagService(
        InMemoryVectorRepository(), embedding, build_policy()
    )
    document = KnowledgeDocument(
        knowledge_base_id="kb-1",
        document_id="doc-1",
        source_name="reference.md",
        content="缓存一致性 " * 1200,
    )

    count = await service.index_document(document)

    assert count >= 2
    assert sum(embedding.document_batches) == count
    assert embedding.document_batches[0] == 10
    assert all(size <= 10 for size in embedding.document_batches)


@pytest.mark.asyncio
async def test_search_falls_back_to_local_knowledge_base_filter() -> None:
    repository = InMemoryVectorRepository(supports_metadata_filter=False)
    embedding = FakeEmbeddingProvider()
    service = RagService(repository, embedding, build_policy())
    await service.index_document(
        KnowledgeDocument(
            knowledge_base_id="kb-allowed",
            document_id="doc-allowed",
            source_name="allowed.md",
            content="一致性与并发控制",
        )
    )
    await service.index_document(
        KnowledgeDocument(
            knowledge_base_id="kb-other",
            document_id="doc-other",
            source_name="other.md",
            content="无关资料",
        )
    )

    results = await service.search(
        "一致性",
        use_case=RagUseCase.QUESTION_GENERATION,
        knowledge_base_ids=("kb-allowed",),
    )

    assert results
    assert all(item.chunk.knowledge_base_id == "kb-allowed" for item in results)


@pytest.mark.asyncio
async def test_search_rejects_an_implicit_default_knowledge_base() -> None:
    service = RagService(InMemoryVectorRepository(), FakeEmbeddingProvider(), build_policy())

    with pytest.raises(ValueError, match="knowledge_base_ids"):
        await service.search("缓存一致性", use_case=RagUseCase.QUESTION_GENERATION)


def test_chunker_rejects_invalid_overlap_parameters() -> None:
    with pytest.raises(Exception):
        TokenChunker(chunk_size_tokens=20, overlap_tokens=20)


@pytest.mark.asyncio
async def test_failed_reindex_keeps_previously_searchable_vectors() -> None:
    repository = InMemoryVectorRepository()
    embedding = FailingEmbeddingProvider()
    service = RagService(repository, embedding, build_policy())
    await service.index_document(KnowledgeDocument(
        knowledge_base_id="kb-1", document_id="doc-old", source_name="old.md",
        content="旧的缓存一致性资料",
    ))

    embedding.fail_document_embedding = True
    with pytest.raises(RagDependencyError):
        await service.index_document(KnowledgeDocument(
            knowledge_base_id="kb-1", document_id="doc-new", source_name="new.md",
            content="新的缓存一致性资料",
        ))

    results = await service.search(
        "缓存一致性", use_case=RagUseCase.QUESTION_GENERATION,
        knowledge_base_ids=("kb-1",),
    )
    assert [item.chunk.document_id for item in results] == ["doc-old"]


@pytest.mark.asyncio
async def test_delete_knowledge_base_removes_its_vectors() -> None:
    service = RagService(InMemoryVectorRepository(), FakeEmbeddingProvider(), build_policy())
    await service.index_document(KnowledgeDocument(
        knowledge_base_id="kb-1", document_id="doc-1", source_name="reference.md",
        content="缓存一致性资料",
    ))
    await service.delete_knowledge_base("kb-1")

    results = await service.search(
        "缓存一致性", use_case=RagUseCase.QUESTION_GENERATION,
        knowledge_base_ids=("kb-1",),
    )
    assert results == []
