"""预置资料解析与 800-token、零重叠切片。"""

from pathlib import Path

import tiktoken

from app.core.exceptions import RagConfigurationError

from .models import KnowledgeChunk, KnowledgeDocument


class KnowledgeDocumentParser:
    def parse_file(
        self, path: Path, *, knowledge_base_id: str, document_id: str
    ) -> KnowledgeDocument:
        if not path.is_file():
            raise RagConfigurationError(f"RAG 资料不存在: {path.name}")
        suffix = path.suffix.lower()
        if suffix in {".txt", ".md"}:
            content = path.read_text(encoding="utf-8")
        elif suffix == ".pdf":
            from pypdf import PdfReader

            content = "\n".join(
                page.extract_text() or "" for page in PdfReader(path).pages
            )
        elif suffix == ".docx":
            from docx import Document

            content = "\n".join(
                paragraph.text for paragraph in Document(path).paragraphs
            )
        else:
            raise RagConfigurationError(f"不支持的 RAG 资料格式: {suffix}")
        return KnowledgeDocument(
            knowledge_base_id=knowledge_base_id,
            document_id=document_id,
            source_name=path.name,
            content=content.strip(),
        )


class TokenChunker:
    def __init__(self, *, chunk_size_tokens: int, overlap_tokens: int) -> None:
        if chunk_size_tokens < 1 or overlap_tokens < 0 or overlap_tokens >= chunk_size_tokens:
            raise RagConfigurationError("切片重叠 Token 必须小于切片大小")
        self._encoding = tiktoken.get_encoding("cl100k_base")
        self._chunk_size = chunk_size_tokens
        self._step = chunk_size_tokens - overlap_tokens

    def split(self, document: KnowledgeDocument) -> list[KnowledgeChunk]:
        tokens = self._encoding.encode(document.content)
        chunks: list[KnowledgeChunk] = []
        for index, start in enumerate(range(0, len(tokens), self._step)):
            content = self._encoding.decode(
                tokens[start : start + self._chunk_size]
            ).strip()
            if not content:
                continue
            chunks.append(
                KnowledgeChunk(
                    chunk_id=f"{document.document_id}:{index}",
                    knowledge_base_id=document.knowledge_base_id,
                    document_id=document.document_id,
                    source_name=document.source_name,
                    chunk_index=index,
                    content=content,
                )
            )
        return chunks
