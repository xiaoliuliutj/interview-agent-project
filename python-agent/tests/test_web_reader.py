import pytest
import httpx
from datetime import datetime, timezone

from app.tools.web_reader import validate_public_url
from app.common.exceptions import AgentDependencyError
from app.tools.web_reader import WebDocument
from app.api.application import create_app


@pytest.mark.parametrize("url", [
    "file:///etc/passwd",
    "ftp://example.com/article",
    "http://127.0.0.1/",
    "http://localhost/",
    "http://169.254.169.254/latest/meta-data",
    "https://user:password@example.com/article",
    "https://example.com:8443/article",
])
def test_web_reader_rejects_unsafe_urls(url: str):
    with pytest.raises(AgentDependencyError):
        validate_public_url(url)


def test_web_reader_accepts_standard_public_url(monkeypatch):
    monkeypatch.setattr(
        "app.tools.web_reader.socket.getaddrinfo",
        lambda *args, **kwargs: [(None, None, None, None, ("93.184.216.34", 0))],
    )
    assert validate_public_url("https://example.com/article") == "https://example.com/article"


@pytest.mark.anyio
async def test_web_fetch_endpoint_returns_provenance(monkeypatch):
    document = WebDocument(
        url="https://example.com/article", title="Example", fetched_at="2026-08-12T00:00:00Z",
        content_hash="abc", markdown="# Example\n\nReadable article text", content_type="text/html",
    )
    monkeypatch.setattr("app.api.application.fetch_public_article", lambda url: _resolved(document))
    payload = {
        "apiVersion": "v1", "requestId": "request-1", "runId": "run-1", "userId": "user-1",
        "sessionId": "web-tool", "operation": "tool.web.fetch", "url": document.url,
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }
    transport = httpx.ASGITransport(app=create_app())
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/v1/tools/web/fetch", json=payload)
    assert response.status_code == 200
    assert response.json()["output"]["contentHash"] == "abc"


async def _resolved(value):
    return value
