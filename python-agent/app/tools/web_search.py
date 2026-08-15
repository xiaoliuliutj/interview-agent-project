"""Constrained technical-documentation search for interview evidence.

Search is deliberately narrow: it queries a public search endpoint, accepts
only a small allowlist of documentation domains, then delegates every result
to ``web_reader`` for the SSRF and content checks used by manual imports.
"""

from __future__ import annotations

from html.parser import HTMLParser
from urllib.parse import parse_qs, quote_plus, urlparse

import httpx

from app.tools.web_reader import WebDocument, fetch_public_article, validate_public_url


SEARCH_ENDPOINT = "https://html.duckduckgo.com/html/?q="
SEARCH_TIMEOUT_SECONDS = 20.0
MAX_SEARCH_RESULTS = 2

# The automatic tool reads only established technical documentation sites.
# Manual knowledge-base import remains available for other public sources.
TECHNICAL_DOMAIN_SUFFIXES = (
    "developer.mozilla.org", "docs.python.org", "docs.oracle.com",
    "learn.microsoft.com", "kubernetes.io", "docs.docker.com", "docker.com",
    "postgresql.org", "redis.io", "spring.io", "docs.spring.io", "react.dev",
    "typescriptlang.org", "nodejs.org", "git-scm.com", "nginx.org",
    "openjdk.org", "docs.aws.amazon.com",
)


class _ResultLinkParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.links: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.lower() != "a":
            return
        values = dict(attrs)
        css_class = values.get("class") or ""
        href = values.get("href")
        if href and "result__a" in css_class:
            self.links.append(href)


def _allowed_technical_url(url: str) -> bool:
    host = (urlparse(url).hostname or "").casefold().rstrip(".")
    return any(host == suffix or host.endswith("." + suffix) for suffix in TECHNICAL_DOMAIN_SUFFIXES)


def _unwrap_search_url(url: str) -> str:
    parsed = urlparse(url)
    if parsed.hostname and parsed.hostname.endswith("duckduckgo.com") and parsed.path.startswith("/l/"):
        return parse_qs(parsed.query).get("uddg", [""])[0]
    return url


class WebEvidenceTool:
    """Search and read a bounded number of public technical documents."""

    async def search_for_question_generation(self, topic: str) -> list[WebDocument]:
        query = " ".join(topic.split())
        if not query:
            return []
        try:
            async with httpx.AsyncClient(
                timeout=httpx.Timeout(SEARCH_TIMEOUT_SECONDS), follow_redirects=False,
                headers={"User-Agent": "InterviewAgentWebEvidence/1.0"},
            ) as client:
                response = await client.get(SEARCH_ENDPOINT + quote_plus(f"{query} technical documentation"))
                response.raise_for_status()
            parser = _ResultLinkParser()
            parser.feed(response.text)
            parser.close()
        except (httpx.HTTPError, UnicodeError):
            # Lack of public search must never fail an interview; RAG results
            # (including an empty result) are still valid evidence input.
            return []

        selected: list[str] = []
        for raw_url in parser.links:
            candidate = _unwrap_search_url(raw_url)
            if not _allowed_technical_url(candidate) or candidate in selected:
                continue
            try:
                selected.append(validate_public_url(candidate))
            except Exception:
                continue
            if len(selected) >= MAX_SEARCH_RESULTS:
                break

        documents: list[WebDocument] = []
        for candidate in selected:
            try:
                documents.append(await fetch_public_article(candidate))
            except Exception:
                continue
        return documents
