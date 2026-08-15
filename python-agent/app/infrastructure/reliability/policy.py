"""从 JSON 读取下层调用重试策略。"""

import json
from dataclasses import dataclass
from pathlib import Path

from app.common.config import PROJECT_DIR
from app.common.exceptions import ReliabilityConfigurationError


@dataclass(frozen=True)
class RetryPolicy:
    max_attempts: int
    initial_backoff_milliseconds: int
    max_backoff_milliseconds: int
    retryable_errors: frozenset[str]
    attempt_timeout_seconds: float = 120.0
    max_output_correction_attempts: int = 2

    @classmethod
    def load(cls, path: Path | None = None) -> "RetryPolicy":
        config_path = path or PROJECT_DIR / "resources" / "agent" / "reliability.json"
        try:
            raw = json.loads(config_path.read_text(encoding="utf-8"))
            policy = cls(
                max_attempts=int(raw["maxAttempts"]),
                initial_backoff_milliseconds=int(raw["initialBackoffMilliseconds"]),
                max_backoff_milliseconds=int(raw["maxBackoffMilliseconds"]),
                retryable_errors=frozenset(str(item) for item in raw["retryableErrors"]),
                attempt_timeout_seconds=float(raw.get("attemptTimeoutSeconds", 120)),
                max_output_correction_attempts=int(raw.get("maxOutputCorrectionAttempts", 2)),
            )
        except (FileNotFoundError, KeyError, ValueError, TypeError, json.JSONDecodeError) as error:
            raise ReliabilityConfigurationError("Agent 重试策略配置无效") from error
        if not 1 <= policy.max_attempts <= 5 or policy.initial_backoff_milliseconds < 0:
            raise ReliabilityConfigurationError("Agent 重试总尝试次数必须在 1 到 5 次之间")
        if policy.max_backoff_milliseconds < policy.initial_backoff_milliseconds:
            raise ReliabilityConfigurationError("最大退避时间不能小于初始退避时间")
        if policy.attempt_timeout_seconds <= 0 or policy.attempt_timeout_seconds > 120:
            raise ReliabilityConfigurationError("单次模型调用超时必须在 0 到 120 秒之间")
        if policy.max_output_correction_attempts < 0 or policy.max_output_correction_attempts > 2:
            raise ReliabilityConfigurationError("结构化输出修复次数必须在 0 到 2 次之间")
        if not policy.retryable_errors:
            raise ReliabilityConfigurationError("可重试异常集合不能为空")
        return policy
