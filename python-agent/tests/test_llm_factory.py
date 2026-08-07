from langchain_openai import ChatOpenAI
import pytest

from app.core.config import Settings
from app.core.exceptions import ModelConfigurationError
from app.agent.llm import LLMFactory


def test_create_chat_model_from_settings_without_network_call() -> None:
    settings = Settings(
        model_name="test-model",
        model_api_key="test-key",
        model_base_url="https://example.invalid/v1",
    )

    model = LLMFactory.create_chat_model(settings)

    assert isinstance(model, ChatOpenAI)
    assert model.model_name == "test-model"
    assert model.temperature == 0.2
    assert model.max_retries == 0


def test_missing_model_name_is_configuration_error() -> None:
    settings = Settings(model_name="", model_api_key="test-key")

    with pytest.raises(ModelConfigurationError):
        LLMFactory.create_chat_model(settings)


def test_openai_compatible_provider_is_supported() -> None:
    settings = Settings(
        model_provider="openai-compatible",
        model_name="gpt-compatible-model",
        model_api_key="test-key",
        model_base_url="https://example.invalid/v1",
    )

    model = LLMFactory.create_chat_model(settings)

    assert isinstance(model, ChatOpenAI)
