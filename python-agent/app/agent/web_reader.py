"""Bounded public-web article fetching and HTML-to-Markdown extraction."""

from __future__ import annotations

import asyncio
import hashlib
import ipaddress
import re
import socket
from dataclasses import replace
from collections import deque
from dataclasses import dataclass
from datetime import datetime, timezone
from html.parser import HTMLParser
from time import monotonic
from typing import Protocol
from urllib.parse import parse_qsl, urlencode, urljoin, urlparse, urlunparse

import httpx

from app.core.exceptions import AgentDependencyError


MAX_REDIRECTS = 3
MAX_BYTES = 5 * 1024 * 1024
MAX_MARKDOWN_CHARS = 180_000
TIMEOUT_SECONDS = 120.0
MAX_RETRIES = 2
CRAWL_MAX_DEPTH = 2
CRAWL_MAX_VALID_PAGES = 20
CRAWL_MAX_ATTEMPTS = 100
CRAWL_MAX_TOTAL_BYTES = 50 * 1024 * 1024
CRAWL_MAX_MARKDOWN_CHARS = 1_500_000
CRAWL_TIMEOUT_SECONDS = 600.0


@dataclass(frozen=True)
class WebDocument:
    url: str
    title: str
    fetched_at: str
    content_hash: str
    markdown: str
    content_type: str
    links: tuple[str, ...] = ()
    raw_byte_size: int = 0

    def as_dict(self) -> dict[str, str | int]:
        return {
            "url": self.url,
            "title": self.title,
            "fetchedAt": self.fetched_at,
            "contentHash": self.content_hash,
            "markdown": self.markdown,
            "contentType": self.content_type,
            "characterCount": len(self.markdown),
            "links": list(self.links),
            "rawByteSize": self.raw_byte_size,
        }


@dataclass(frozen=True)
class CrawlPage:
    document: WebDocument
    depth: int
    parent_url: str | None
    filename: str


@dataclass(frozen=True)
class CrawlResult:
    entry_url: str
    pages: tuple[CrawlPage, ...]
    rejected: tuple[dict[str, str], ...]
    status: str
    stop_reason: str | None
    archive_markdown: str

    def as_dict(self) -> dict[str, object]:
        return {
            "entryUrl": self.entry_url,
            "status": self.status,
            "stopReason": self.stop_reason,
            "validPageCount": len(self.pages),
            "rejectedCount": len(self.rejected),
            "pages": [{**page.document.as_dict(), "depth": page.depth,
                        "parentUrl": page.parent_url, "filename": page.filename}
                       for page in self.pages],
            "rejected": list(self.rejected),
            "archiveMarkdown": self.archive_markdown,
        }


class CrawlPageAssessor(Protocol):
    async def assess(self, *, title: str, url: str, topic: str | None,
                     markdown: str, candidate_links: list[str]) -> object: ...


class _ArticleParser(HTMLParser):
    """Small dependency-free article extractor for ordinary technical pages."""

    SKIP = {
        "script", "style", "noscript", "svg", "canvas", "nav", "footer",
        "form", "aside", "dialog", "template", "iframe",
    }
    BOILERPLATE_HINTS = {
        "advert", "banner", "breadcrumb", "cookie", "comment", "feedback",
        "footer", "header", "menu", "modal", "newsletter", "pagination",
        "popup", "recommend", "related", "share", "sidebar", "social",
        "subscribe", "toolbar",
    }

    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.title_parts: list[str] = []
        self.blocks: list[str] = []
        self._title_depth = 0
        self._skip_depth = 0
        self._block: list[str] = []
        self._tag_stack: list[str] = []
        self.links: list[str] = []
        self._skip_stack: list[bool] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        tag = tag.lower()
        values = dict(attrs)
        if tag == "a" and not self._skip_depth:
            href = values.get("href")
            if href:
                self.links.append(href)
        self._tag_stack.append(tag)
        hint = " ".join(filter(None, (values.get("id"), values.get("class"), values.get("role")))).casefold()
        skip_this = tag in self.SKIP or any(item in hint for item in self.BOILERPLATE_HINTS)
        self._skip_stack.append(skip_this)
        if skip_this:
            self._skip_depth += 1
            return
        if self._skip_depth:
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
        skip_this = self._skip_stack.pop() if self._skip_stack else False
        if skip_this and self._skip_depth:
            self._skip_depth -= 1
            if self._tag_stack:
                self._tag_stack.pop()
            return
        if self._skip_depth:
            if self._tag_stack:
                self._tag_stack.pop()
            return
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
                        links=tuple(parser.links),
                        raw_byte_size=len(body),
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


def normalize_crawl_url(url: str, *, base_url: str) -> str | None:
    candidate = urljoin(base_url, url).strip()
    parsed = urlparse(candidate)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        return None
    query = [(key, value) for key, value in parse_qsl(parsed.query, keep_blank_values=True)
             if not key.casefold().startswith(("utm_", "fbclid", "gclid", "spm"))]
    return urlunparse((parsed.scheme.lower(), parsed.hostname.lower(), parsed.path or "/",
                       parsed.params, urlencode(query), ""))


def _page_is_relevant(document: WebDocument, topic: str | None) -> bool:
    if len(document.markdown) < 160:
        return False
    if not topic or not topic.strip():
        return True
    tokens = {token.casefold() for token in re.findall(r"[\w\u4e00-\u9fff]{2,}", topic)}
    if not tokens:
        return True
    haystack = f"{document.title} {document.markdown[:5000]}".casefold()
    return any(token in haystack for token in tokens)


def _page_is_rich(document: WebDocument) -> bool:
    """Enforce a deterministic minimum before an Agent may spend a valid-page slot."""
    text = re.sub(r"[#*_>`\-\s]+", " ", document.markdown).strip()
    words = re.findall(r"[A-Za-z0-9_]+|[\u4e00-\u9fff]", text)
    return len(text) >= 160 and len(words) >= 35


def _archive_markdown(result_entry: str, pages: list[CrawlPage], rejected: list[dict[str, str]],
                      status: str, stop_reason: str | None) -> str:
    lines = ["---", "rag_index_enabled: false", "document_type: crawl_archive", "---", "",
             "# 网页抓取溯源归档", "", f"- 入口 URL：{result_entry}",
             f"- 状态：{status}", f"- 有效页面：{len(pages)}", f"- 无效/重复页面：{len(rejected)}"]
    if stop_reason:
        lines.append(f"- 停止原因：{stop_reason}")
    lines.extend(["", "## 来源目录", ""])
    for index, page in enumerate(pages, 1):
        doc = page.document
        lines.extend([f"{index}. [{doc.title}]({doc.url})", f"   - 文件：`{page.filename}`",
                      f"   - 深度：{page.depth}", f"   - SHA-256：`{doc.content_hash}`", ""])
    lines.extend(["## 页面内容（仅归档，不参与 RAG）", ""])
    for page in pages:
        lines.extend([f"## {page.document.title}", f"来源：[{page.document.url}]({page.document.url})", "",
                      page.document.markdown, ""])
    if rejected:
        lines.extend(["## 未纳入页面", ""])
        lines.extend(f"- {item['url']}：{item['reason']}" for item in rejected)
    return "\n".join(lines).strip() + "\n"


async def crawl_public_site(entry_url: str, *, topic: str | None = None,
                            assessor: CrawlPageAssessor | None = None) -> CrawlResult:
    entry = validate_public_url(entry_url)
    entry_host = (urlparse(entry).hostname or "").casefold()
    started = monotonic()
    queue: deque[tuple[str, int, str | None]] = deque([(entry, 0, None)])
    seen: set[str] = set()
    hashes: set[str] = set()
    pages: list[CrawlPage] = []
    rejected: list[dict[str, str]] = []
    total_bytes = 0
    attempts = 0
    stop_reason: str | None = None
    while queue and len(pages) < CRAWL_MAX_VALID_PAGES:
        if monotonic() - started >= CRAWL_TIMEOUT_SECONDS:
            stop_reason = "总抓取时长达到 10 分钟"
            break
        if total_bytes >= CRAWL_MAX_TOTAL_BYTES:
            stop_reason = "总响应字节数达到 50MB"
            break
        if attempts >= CRAWL_MAX_ATTEMPTS:
            stop_reason = "候选页面访问次数达到安全上限"
            break
        url, depth, parent = queue.popleft()
        normalized = normalize_crawl_url(url, base_url=entry)
        if not normalized or normalized in seen:
            continue
        if (urlparse(normalized).hostname or "").casefold() != entry_host:
            rejected.append({"url": normalized or url, "reason": "非入口同域链接"})
            continue
        seen.add(normalized)
        attempts += 1
        try:
            remaining_seconds = CRAWL_TIMEOUT_SECONDS - (monotonic() - started)
            if remaining_seconds <= 0:
                stop_reason = "总抓取时长达到 10 分钟"
                break
            document = await asyncio.wait_for(
                fetch_public_article(normalized), timeout=remaining_seconds
            )
            if total_bytes + document.raw_byte_size > CRAWL_MAX_TOTAL_BYTES:
                stop_reason = "总响应字节数达到 50MB"
                break
            total_bytes += document.raw_byte_size
        except TimeoutError:
            stop_reason = "总抓取时长达到 10 分钟"
            break
        except Exception as error:
            rejected.append({"url": normalized, "reason": str(error)[:200]})
            continue
        if (urlparse(document.url).hostname or "").casefold() != entry_host:
            rejected.append({"url": document.url, "reason": "重定向后离开入口域名"})
            continue
        candidates = [candidate for child in document.links
                      if (candidate := normalize_crawl_url(child, base_url=document.url))]
        decision = None
        if assessor is not None:
            try:
                decision = await assessor.assess(
                    title=document.title, url=document.url, topic=topic,
                    markdown=document.markdown, candidate_links=candidates,
                )
            except Exception:
                decision = None
        rich_enough = _page_is_rich(document)
        include_page = rich_enough and (
            _page_is_relevant(document, topic) if decision is None
            else bool(getattr(decision, "include_as_knowledge", False))
        )
        expand_links = (bool(candidates) if decision is None
                        else bool(getattr(decision, "expand_links", False)))
        duplicate = include_page and document.content_hash in hashes
        if duplicate:
            rejected.append({"url": normalized, "reason": "清洗后内容重复"})
            include_page = False
        projected_markdown_chars = (
            sum(len(item.document.markdown) for item in pages) + len(document.markdown)
        )
        if include_page and projected_markdown_chars > CRAWL_MAX_MARKDOWN_CHARS:
            stop_reason = "清洗后 Markdown 总长度达到上限"
            break
        if include_page:
            hashes.add(document.content_hash)
            slug = re.sub(r"[^a-zA-Z0-9\u4e00-\u9fff]+", "-", document.title).strip("-")[:60]
            filename = f"{len(pages):03d}-{slug or 'web-page'}.md"
            front_matter = "\n".join([
                "---", f'title: "{document.title.replace(chr(34), chr(39))}"',
                f'source_url: "{document.url}"', f'fetched_at: "{document.fetched_at}"',
                f'content_hash: "{document.content_hash}"', f"depth: {depth}",
                f'parent_url: "{parent or ""}"', "rag_index_enabled: true", "---", "",
            ])
            artifact_document = replace(document, markdown=front_matter + document.markdown)
            pages.append(CrawlPage(document=artifact_document, depth=depth, parent_url=parent, filename=filename))
        elif not duplicate:
            reason = ("清洗后正文内容不足" if not rich_enough else
                      str(getattr(decision, "reason", "正文不足或与知识主题无关"))[:200])
            rejected.append({"url": normalized, "reason": reason})
        if sum(len(item.document.markdown) for item in pages) >= CRAWL_MAX_MARKDOWN_CHARS:
            stop_reason = "清洗后 Markdown 总长度达到上限"
            break
        if depth < CRAWL_MAX_DEPTH and expand_links:
            selected = candidates
            if decision is not None:
                allowed = set(candidates)
                choices = sorted(getattr(decision, "selected_links", []),
                                 key=lambda item: getattr(item, "priority", 100))
                selected = [item.url for item in choices if item.url in allowed]
            for normalized_child in selected:
                if normalized_child not in seen:
                    queue.append((normalized_child, depth + 1, document.url))
    if stop_reason is None and queue:
        stop_reason = "有效页面数量达到 20 页"
    status = "COMPLETED" if not queue and stop_reason is None else "PARTIAL_COMPLETED"
    archive = _archive_markdown(entry, pages, rejected, status, stop_reason)
    return CrawlResult(entry_url=entry, pages=tuple(pages), rejected=tuple(rejected),
                       status=status, stop_reason=stop_reason, archive_markdown=archive)
