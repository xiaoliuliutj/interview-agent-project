import pytest

from app.agent.rag.models import KnowledgeDocument, RagUseCase
from app.agent.rag.parser import TokenChunker
from app.agent.rag.policy import RagPolicy
from app.agent.rag.repository import InMemoryVectorRepository
from app.agent.rag.service import RagService


class FakeEmbeddingProvider:
    def __init__(self) -> None:
        self.document_batches: list[int] = []

    async def embed_documents(self, texts: list[str]) -> list[list[float]]:
        self.document_batches.append(len(texts))
        return [[1.0, 0.0] for _ in texts]

    async def embed_query(self, text: str) -> list[float]:
        return [1.0, 0.0]


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


def test_chunker_rejects_invalid_overlap_parameters() -> None:
    with pytest.raises(Exception):
        TokenChunker(chunk_size_tokens=20, overlap_tokens=20)
