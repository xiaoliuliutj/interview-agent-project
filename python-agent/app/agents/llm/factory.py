"""大模型客户端工厂。这里只负责按配置创建模型客户端，不执行模型请求。"""

from langchain_openai import ChatOpenAI

from app.common.config import Settings, get_settings
from app.common.exceptions import ModelConfigurationError


class LLMFactory:
    """根据统一配置创建 OpenAI-compatible 的聊天模型客户端。"""

    @staticmethod
    def create_chat_model(settings: Settings | None = None) -> ChatOpenAI:
        current = settings or get_settings()

        supported_providers = {"openai", "openai-compatible", "custom"}
        if current.model_provider.lower() not in supported_providers:
            raise ModelConfigurationError(
                f"暂不支持模型提供方: {current.model_provider}"
            )
        if not current.model_name:
            raise ModelConfigurationError("MODEL_NAME 未配置")
        if not current.model_api_key:
            raise ModelConfigurationError("MODEL_API_KEY 未配置")

        model_kwargs: dict[str, object] = {
            "model": current.model_name,
            "api_key": current.model_api_key,
            "temperature": current.model_temperature,
            "timeout": current.request_timeout_seconds,
            # 重试统一由下层 engineering 层接管，避免 SDK 与业务层重复重试。
            "max_retries": 0,
        }
        if current.model_base_url:
            model_kwargs["base_url"] = current.model_base_url
        if current.model_max_tokens is not None:
            model_kwargs["max_tokens"] = current.model_max_tokens

        return ChatOpenAI(**model_kwargs)
