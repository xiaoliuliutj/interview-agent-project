"""统一异步调用重试执行器。"""

import asyncio
from collections.abc import Awaitable, Callable
from typing import TypeVar

from app.core.exceptions import AgentDependencyError

from .policy import RetryPolicy


T = TypeVar("T")


class AsyncRetryExecutor:
    def __init__(self, policy: RetryPolicy) -> None:
        self._policy = policy

    async def execute(self, operation: Callable[[], Awaitable[T]]) -> T:
        for attempt in range(1, self._policy.max_attempts + 1):
            try:
                return await operation()
            except Exception as error:
                if not self._is_retryable(error) or attempt == self._policy.max_attempts:
                    if self._is_retryable(error):
                        raise AgentDependencyError(
                            "模型或外部 Agent 依赖在有限重试后仍不可用",
                            retryable=True,
                        ) from error
                    raise
                await asyncio.sleep(self._backoff_seconds(attempt))
        raise AssertionError("unreachable")

    def _is_retryable(self, error: BaseException) -> bool:
        return type(error).__name__ in self._policy.retryable_errors

    def _backoff_seconds(self, attempt: int) -> float:
        milliseconds = min(
            self._policy.max_backoff_milliseconds,
            self._policy.initial_backoff_milliseconds * (2 ** (attempt - 1)),
        )
        return milliseconds / 1000
