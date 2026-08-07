import pytest

from app.core.exceptions import AgentDependencyError
from app.engineering.reliability.policy import RetryPolicy
from app.engineering.reliability.retry import AsyncRetryExecutor


def build_executor() -> AsyncRetryExecutor:
    return AsyncRetryExecutor(
        RetryPolicy(
            max_attempts=3,
            initial_backoff_milliseconds=0,
            max_backoff_milliseconds=0,
            retryable_errors=frozenset({"TimeoutError"}),
        )
    )


@pytest.mark.asyncio
async def test_retry_executor_retries_transient_model_failure() -> None:
    attempts = 0

    async def operation() -> str:
        nonlocal attempts
        attempts += 1
        if attempts < 3:
            raise TimeoutError("temporary")
        return "ok"

    assert await build_executor().execute(operation) == "ok"
    assert attempts == 3


@pytest.mark.asyncio
async def test_retry_executor_does_not_retry_non_transient_failure() -> None:
    attempts = 0

    async def operation() -> str:
        nonlocal attempts
        attempts += 1
        raise ValueError("invalid structured output")

    with pytest.raises(ValueError):
        await build_executor().execute(operation)
    assert attempts == 1


@pytest.mark.asyncio
async def test_retry_executor_maps_exhausted_transient_failure() -> None:
    async def operation() -> str:
        raise TimeoutError("down")

    with pytest.raises(AgentDependencyError) as error:
        await build_executor().execute(operation)
    assert error.value.retryable is True
