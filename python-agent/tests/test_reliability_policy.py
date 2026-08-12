from app.engineering.reliability.policy import RetryPolicy


def test_openai_server_error_is_retryable() -> None:
    policy = RetryPolicy.load()

    assert "InternalServerError" in policy.retryable_errors
