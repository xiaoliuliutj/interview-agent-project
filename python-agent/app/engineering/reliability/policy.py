"""从 JSON 读取下层调用重试策略。"""

import json
from dataclasses import dataclass
from pathlib import Path

from app.core.config import PROJECT_DIR
from app.core.exceptions import ReliabilityConfigurationError


@dataclass(frozen=True)
class RetryPolicy:
    max_attempts: int
    initial_backoff_milliseconds: int
    max_backoff_milliseconds: int
    retryable_errors: frozenset[str]

    @classmethod
    def load(cls, path: Path | None = None) -> "RetryPolicy":
        config_path = path or PROJECT_DIR / "config" / "agent" / "reliability.json"
        try:
            raw = json.loads(config_path.read_text(encoding="utf-8"))
            policy = cls(
                max_attempts=int(raw["maxAttempts"]),
                initial_backoff_milliseconds=int(raw["initialBackoffMilliseconds"]),
                max_backoff_milliseconds=int(raw["maxBackoffMilliseconds"]),
                retryable_errors=frozenset(str(item) for item in raw["retryableErrors"]),
            )
        except (FileNotFoundError, KeyError, ValueError, TypeError, json.JSONDecodeError) as error:
            raise ReliabilityConfigurationError("Agent 重试策略配置无效") from error
        if policy.max_attempts < 1 or policy.initial_backoff_milliseconds < 0:
            raise ReliabilityConfigurationError("Agent 重试策略参数无效")
        if policy.max_backoff_milliseconds < policy.initial_backoff_milliseconds:
            raise ReliabilityConfigurationError("最大退避时间不能小于初始退避时间")
        if not policy.retryable_errors:
            raise ReliabilityConfigurationError("可重试异常集合不能为空")
        return policy
