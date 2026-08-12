from app.agent.rag.embedding import OpenAIEmbeddingProvider
from app.core.config import Settings


def test_openai_compatible_embedding_sends_strings_without_token_arrays() -> None:
    provider = OpenAIEmbeddingProvider(Settings(
        embedding_model="text-embedding-test",
        embedding_api_key="test-key",
        embedding_base_url="https://example.com/v1",
    ))
    assert provider._client.check_embedding_ctx_length is False
