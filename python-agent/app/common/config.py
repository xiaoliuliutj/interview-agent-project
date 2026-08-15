"""统一读取下层服务配置。配置值来自环境变量或项目根目录下的 .env 文件。"""

from functools import lru_cache
from pathlib import Path

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


PROJECT_DIR = Path(__file__).resolve().parents[2]


class Settings(BaseSettings):
    """下层服务的集中配置模型。"""

    app_name: str = Field(default="interview-agent")
    environment: str = Field(default="dev")
    host: str = Field(default="0.0.0.0")
    port: int = Field(default=8000, ge=1, le=65535)
    log_level: str = Field(default="INFO")
    # 单次模型/Embedding HTTP 调用的客户端超时；可靠性策略会再次强制该上限。
    request_timeout_seconds: float = Field(default=120.0, gt=0, le=120)

    model_provider: str = Field(default="openai")
    model_name: str = Field(default="")
    model_api_key: str = Field(default="")
    model_base_url: str = Field(default="")
    model_temperature: float = Field(default=0.2, ge=0, le=2)
    model_max_tokens: int | None = Field(default=None, gt=0)
    embedding_model: str = Field(default="")
    embedding_api_key: str = Field(default="")
    embedding_base_url: str = Field(default="")
    database_url: str = Field(default="")
    # Python Agent owns this Redis instance.  It must never point at Java's
    # operational Redis because the two services have independent failure and
    # capacity characteristics.
    redis_url: str = Field(default="")

    model_config = SettingsConfigDict(
        env_file=(PROJECT_DIR / ".env",),
        env_file_encoding="utf-8",
        case_sensitive=False,
        extra="ignore",
    )


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """返回进程级只读配置快照；测试时可清理缓存后重新读取。"""

    return Settings()
