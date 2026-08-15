import pytest
import json

from app.common.exceptions import ReliabilityConfigurationError

from app.common.exceptions import AgentDependencyError
from app.infrastructure.reliability.policy import RetryPolicy
from app.infrastructure.reliability.retry import AsyncRetryExecutor


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


def test_retry_policy_rejects_more_than_five_total_attempts(tmp_path) -> None:
    path = tmp_path / "reliability.json"
    path.write_text(json.dumps({
        "maxAttempts": 6,
        "attemptTimeoutSeconds": 120,
        "maxOutputCorrectionAttempts": 2,
        "initialBackoffMilliseconds": 0,
        "maxBackoffMilliseconds": 0,
        "retryableErrors": ["TimeoutError"],
    }), encoding="utf-8")

    with pytest.raises(ReliabilityConfigurationError):
        RetryPolicy.load(path)
