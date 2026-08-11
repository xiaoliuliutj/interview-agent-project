"""Bounded public-web article fetching and HTML-to-Markdown extraction."""

from __future__ import annotations

import asyncio
import hashlib
import ipaddress
import re
import socket
from dataclasses import dataclass
from datetime import datetime, timezone
from html.parser import HTMLParser
from urllib.parse import urljoin, urlparse

import httpx

from app.core.exceptions import AgentDependencyError


MAX_REDIRECTS = 3
MAX_BYTES = 5 * 1024 * 1024
MAX_MARKDOWN_CHARS = 180_000
TIMEOUT_SECONDS = 120.0
MAX_RETRIES = 2


@dataclass(frozen=True)
class WebDocument:
    url: str
    title: str
    fetched_at: str
    content_hash: str
    markdown: str
    content_type: str

    def as_dict(self) -> dict[str, str | int]:
        return {
            "url": self.url,
            "title": self.title,
            "fetchedAt": self.fetched_at,
            "contentHash": self.content_hash,
            "markdown": self.markdown,
            "contentType": self.content_type,
            "characterCount": len(self.markdown),
        }


class _ArticleParser(HTMLParser):
    """Small dependency-free article extractor for ordinary technical pages."""

    SKIP = {"script", "style", "noscript", "svg", "canvas", "nav", "footer", "form", "aside"}

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.title_parts: list[str] = []
        self.blocks: list[str] = []
        self._title_depth = 0
        self._skip_depth = 0
        self._block: list[str] = []
        self._tag_stack: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        tag = tag.lower()
        self._tag_stack.append(tag)
        if tag in self.SKIP:
            self._skip_depth += 1
            return
        if tag == "title":
            self._title_depth += 1
        if tag in {"p", "div", "section", "article", "h1", "h2", "h3", "h4", "li", "pre", "blockquote", "br"}:
            self._flush()
        if tag.startswith("h") and len(tag) == 2 and tag[1].isdigit():
            self._block.append("#" * min(int(tag[1]), 6) + " ")
        if tag == "li":
            self._block.append("- ")

    def handle_endtag(self, tag: str) -> None:
        tag = tag.lower()
        if tag in self.SKIP and self._skip_depth:
            self._skip_depth -= 1
        if tag == "title" and self._title_depth:
            self._title_depth -= 1
        if tag in {"p", "div", "section", "article", "h1", "h2", "h3", "h4", "li", "pre", "blockquote", "br"}:
            self._flush()
        if self._tag_stack:
            self._tag_stack.pop()

    def handle_data(self, data: str) -> None:
        if self._skip_depth:
            return
        value = re.sub(r"\s+", " ", data).strip()
        if not value:
            return
        if self._title_depth:
            self.title_parts.append(value)
        self._block.append(value)

    def close(self) -> None:
        super().close()
        self._flush()

    def _flush(self) -> None:
        if self._block:
            text = " ".join(self._block).strip()
            if text:
                self.blocks.append(text)
            self._block = []


def _is_public_host(host: str) -> bool:
    try:
        addresses = socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)
    except socket.gaierror as error:
        raise AgentDependencyError("web host could not be resolved", retryable=False) from error
    for item in addresses:
        address = ipaddress.ip_address(item[4][0])
        if (address.is_private or address.is_loopback or address.is_link_local
                or address.is_multicast or address.is_reserved or address.is_unspecified):
            return False
    return True


def validate_public_url(url: str) -> str:
    parsed = urlparse(url.strip())
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise AgentDependencyError("only public http(s) URLs are supported", retryable=False)
    try:
        port = parsed.port
    except ValueError as error:
        raise AgentDependencyError("URL port is invalid", retryable=False) from error
    if parsed.username or parsed.password or port not in {None, 80, 443}:
        raise AgentDependencyError("URL credentials and non-standard ports are not supported", retryable=False)
    if not _is_public_host(parsed.hostname):
        raise AgentDependencyError("URL host is not publicly reachable", retryable=False)
    return parsed.geturl()


async def fetch_public_article(url: str) -> WebDocument:
    current_url = validate_public_url(url)
    last_error: Exception | None = None
    async with httpx.AsyncClient(
        timeout=httpx.Timeout(TIMEOUT_SECONDS),
        follow_redirects=False,
        headers={"User-Agent": "InterviewAgentWebReader/1.0"},
    ) as client:
        for attempt in range(MAX_RETRIES + 1):
            try:
                for _ in range(MAX_REDIRECTS + 1):
                    response = await client.get(current_url)
                    if response.is_redirect:
                        location = response.headers.get("location")
                        if not location:
                            raise AgentDependencyError("web redirect has no location", retryable=False)
                        current_url = validate_public_url(urljoin(current_url, location))
                        continue
                    response.raise_for_status()
                    content_type = response.headers.get("content-type", "").split(";", 1)[0].lower()
                    if content_type not in {"text/html", "application/xhtml+xml"}:
                        raise AgentDependencyError("URL does not return an HTML page", retryable=False)
                    body = response.content
                    if len(body) > MAX_BYTES:
                        raise AgentDependencyError("web page is larger than the allowed limit", retryable=False)
                    parser = _ArticleParser()
                    parser.feed(body.decode(response.encoding or "utf-8", errors="replace"))
                    parser.close()
                    title = " ".join(parser.title_parts).strip() or current_url
                    blocks = [item for item in parser.blocks if len(item) > 1]
                    markdown = f"# {title}\n\n" + "\n\n".join(blocks)
                    markdown = markdown[:MAX_MARKDOWN_CHARS].strip()
                    if len(markdown) < 80:
                        raise AgentDependencyError("web page does not contain enough readable text", retryable=False)
                    return WebDocument(
                        url=current_url,
                        title=title[:500],
                        fetched_at=datetime.now(timezone.utc).isoformat(),
                        content_hash=hashlib.sha256(markdown.encode("utf-8")).hexdigest(),
                        markdown=markdown,
                        content_type=content_type,
                    )
                raise AgentDependencyError("too many web redirects", retryable=False)
            except AgentDependencyError:
                raise
            except (httpx.TimeoutException, httpx.NetworkError, httpx.HTTPStatusError) as error:
                last_error = error
                if attempt >= MAX_RETRIES:
                    break
                await asyncio.sleep(0.5 * (attempt + 1))
    raise AgentDependencyError("web page fetch failed after retries", retryable=True) from last_error
