import pytest
import tiktoken

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


def test_chunker_splits_markdown_by_heading_before_token_budget() -> None:
    chunker = TokenChunker(chunk_size_tokens=100, overlap_tokens=0)
    document = KnowledgeDocument(
        knowledge_base_id="kb-1",
        document_id="doc-heading",
        source_name="heading.md",
        content=(
            "# Java 面试资料\n\n"
            "## 集合\n集合用于组织对象。\n\n"
            "## 并发\n并发需要关注可见性与原子性。"
        ),
    )

    chunks = chunker.split(document)

    assert len(chunks) == 2
    assert "# Java 面试资料\n## 集合" in chunks[0].content
    assert "# Java 面试资料\n## 并发" in chunks[1].content
    assert chunks[0].metadata["headingPath"] == "Java 面试资料 > 集合"
    assert chunks[1].metadata["chunkingStrategy"] == "heading_then_token"


def test_chunker_token_splits_an_oversized_heading_section_with_context() -> None:
    chunker = TokenChunker(chunk_size_tokens=30, overlap_tokens=4)
    document = KnowledgeDocument(
        knowledge_base_id="kb-1",
        document_id="doc-long-heading",
        source_name="long.md",
        content="# Java 面试资料\n\n## JVM\n" + "垃圾回收与内存模型。" * 80,
    )

    chunks = chunker.split(document)
    encoding = tiktoken.get_encoding("cl100k_base")

    assert len(chunks) > 1
    assert all(chunk.content.startswith("# Java 面试资料\n## JVM") for chunk in chunks)
    assert all(len(encoding.encode(chunk.content)) <= 30 for chunk in chunks)
    assert [chunk.metadata["sectionPartIndex"] for chunk in chunks] == [
        str(index) for index in range(len(chunks))
    ]


def test_chunker_does_not_treat_a_code_comment_as_a_markdown_heading() -> None:
    chunker = TokenChunker(chunk_size_tokens=100, overlap_tokens=0)
    document = KnowledgeDocument(
        knowledge_base_id="kb-1",
        document_id="doc-code",
        source_name="code.md",
        content="## RAG\n\n```python\n# 这是一条代码注释\nprint('ok')\n```",
    )

    chunks = chunker.split(document)

    assert len(chunks) == 1
    assert chunks[0].metadata["headingPath"] == "RAG"
    assert "# 这是一条代码注释" in chunks[0].content


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
