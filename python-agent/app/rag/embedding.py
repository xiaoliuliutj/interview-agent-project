"""Embedding Provider 端口与 OpenAI-compatible 实现。"""

from typing import Protocol

from langchain_openai import OpenAIEmbeddings

from app.common.config import Settings
from app.common.exceptions import RagConfigurationError
from app.infrastructure.reliability.retry import AsyncRetryExecutor


class EmbeddingProvider(Protocol):
    async def embed_documents(self, texts: list[str]) -> list[list[float]]: ...

    async def embed_query(self, text: str) -> list[float]: ...


class OpenAIEmbeddingProvider:
    def __init__(
        self, settings: Settings, retry_executor: AsyncRetryExecutor | None = None
    ) -> None:
        if not settings.embedding_model:
            raise RagConfigurationError("EMBEDDING_MODEL 未配置")
        kwargs: dict[str, object] = {
            "model": settings.embedding_model,
            "api_key": settings.embedding_api_key or settings.model_api_key,
            "timeout": min(settings.request_timeout_seconds, 120),
            "max_retries": 0,
            # Some OpenAI-compatible embedding providers accept only
            # ``input: string[]`` and reject OpenAI's token-array form.
            # RAG chunks are already bounded by TokenChunker, so bypass the
            # SDK's context-length tokenization and send original strings.
            "check_embedding_ctx_length": False,
        }
        base_url = settings.embedding_base_url or settings.model_base_url
        if base_url:
            kwargs["base_url"] = base_url
        self._client = OpenAIEmbeddings(**kwargs)
        self._retry_executor = retry_executor

    async def embed_documents(self, texts: list[str]) -> list[list[float]]:
        if self._retry_executor is None:
            return await self._client.aembed_documents(texts)
        return await self._retry_executor.execute(
            lambda: self._client.aembed_documents(texts)
        )

    async def embed_query(self, text: str) -> list[float]:
        if self._retry_executor is None:
            return await self._client.aembed_query(text)
        return await self._retry_executor.execute(lambda: self._client.aembed_query(text))
