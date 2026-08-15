"""RAG 领域模型。"""

from enum import StrEnum

from pydantic import BaseModel, Field


class RagUseCase(StrEnum):
    QUESTION_GENERATION = "QUESTION_GENERATION"


class KnowledgeDocument(BaseModel):
    knowledge_base_id: str = Field(min_length=1)
    document_id: str = Field(min_length=1)
    source_name: str = Field(min_length=1)
    content: str = Field(min_length=1)
    metadata: dict[str, str] = Field(default_factory=dict)


class KnowledgeChunk(BaseModel):
    chunk_id: str = Field(min_length=1)
    knowledge_base_id: str = Field(min_length=1)
    document_id: str = Field(min_length=1)
    source_name: str = Field(min_length=1)
    chunk_index: int = Field(ge=0)
    content: str = Field(min_length=1)
    metadata: dict[str, str] = Field(default_factory=dict)
    embedding: list[float] = Field(default_factory=list)


class RagSearchResult(BaseModel):
    chunk: KnowledgeChunk
    score: float
