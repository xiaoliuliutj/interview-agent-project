from app.agent.web_search import _allowed_technical_url, _unwrap_search_url


def test_automatic_web_search_is_restricted_to_technical_domains() -> None:
    assert _allowed_technical_url("https://docs.python.org/3/library/asyncio.html")
    assert _allowed_technical_url("https://developer.mozilla.org/en-US/docs/Web/HTTP")
    assert not _allowed_technical_url("https://example.com/technical-documentation")
    assert not _allowed_technical_url("https://127.0.0.1/docs")


def test_search_redirect_target_is_unwrapped_before_validation() -> None:
    wrapped = "https://duckduckgo.com/l/?uddg=https%3A%2F%2Fdocs.python.org%2F3%2F"
    assert _unwrap_search_url(wrapped) == "https://docs.python.org/3/"
