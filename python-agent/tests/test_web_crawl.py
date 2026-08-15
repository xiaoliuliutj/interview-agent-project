import asyncio

import pytest

from app.tools.web_reader import WebDocument, crawl_public_site


def document(url: str, title: str, links: tuple[str, ...] = ()) -> WebDocument:
    return WebDocument(
        url=url, title=title, fetched_at="2026-08-12T00:00:00Z", content_hash=title,
        markdown=f"# {title}\n\n" + "technical content " * 30,
        content_type="text/html", links=links, raw_byte_size=100,
    )


@pytest.mark.asyncio
async def test_crawl_counts_only_valid_unique_pages(monkeypatch):
    pages = {
        "https://example.com/index": document(
            "https://example.com/index", "Index",
            ("https://example.com/good", "https://example.com/empty", "https://example.com/duplicate"),
        ),
        "https://example.com/good": document("https://example.com/good", "Good"),
        "https://example.com/empty": WebDocument(
            url="https://example.com/empty", title="Empty", fetched_at="now", content_hash="empty",
            markdown="# Empty\n\nshort", content_type="text/html", raw_byte_size=100,
        ),
        "https://example.com/duplicate": document("https://example.com/duplicate", "Good"),
    }
    monkeypatch.setattr("app.tools.web_reader.validate_public_url", lambda url: url)
    monkeypatch.setattr("app.tools.web_reader.fetch_public_article", lambda url: _resolve(pages[url]))
    result = await crawl_public_site("https://example.com/index", topic="technical")
    assert [page.document.title for page in result.pages] == ["Index", "Good"]
    assert len(result.rejected) == 2
    assert "sources" not in result.archive_markdown.casefold()
    assert "https://example.com/good" in result.archive_markdown


@pytest.mark.asyncio
async def test_directory_can_expand_without_counting_as_valid_page(monkeypatch):
    pages = {
        "https://example.com/index": document(
            "https://example.com/index", "Directory", ("https://example.com/child",)),
        "https://example.com/child": document("https://example.com/child", "Child"),
    }
    assessor = DirectoryAssessor()
    monkeypatch.setattr("app.tools.web_reader.validate_public_url", lambda url: url)
    monkeypatch.setattr("app.tools.web_reader.fetch_public_article", lambda url: _resolve(pages[url]))
    result = await crawl_public_site("https://example.com/index", assessor=assessor)
    assert [page.document.title for page in result.pages] == ["Child"]
    assert len(result.rejected) == 1
    assert "rag_index_enabled: true" in result.pages[0].document.markdown


@pytest.mark.asyncio
async def test_agent_cannot_invent_links(monkeypatch):
    pages = {
        "https://example.com/index": document(
            "https://example.com/index", "Directory", ("https://example.com/allowed",)),
        "https://example.com/allowed": document("https://example.com/allowed", "Allowed"),
    }
    visited = []
    async def fetch(url):
        visited.append(url)
        return pages[url]
    monkeypatch.setattr("app.tools.web_reader.validate_public_url", lambda url: url)
    monkeypatch.setattr("app.tools.web_reader.fetch_public_article", fetch)

    await crawl_public_site("https://example.com/index", assessor=InventingAssessor())

    assert visited == ["https://example.com/index", "https://example.com/allowed"]


@pytest.mark.asyncio
async def test_redirected_cross_domain_page_is_not_counted(monkeypatch):
    redirected = document("https://other.example/page", "Redirected")
    monkeypatch.setattr("app.tools.web_reader.validate_public_url", lambda url: url)
    monkeypatch.setattr("app.tools.web_reader.fetch_public_article", lambda url: _resolve(redirected))

    result = await crawl_public_site("https://example.com/index")

    assert result.pages == ()
    assert result.rejected == ({"url": "https://other.example/page", "reason": "重定向后离开入口域名"},)


@pytest.mark.asyncio
async def test_agent_cannot_include_a_thin_page(monkeypatch):
    thin = WebDocument(
        url="https://example.com/thin", title="Thin", fetched_at="now", content_hash="thin",
        markdown="# Thin\n\nOnly a short fact.", content_type="text/html", raw_byte_size=30,
    )
    monkeypatch.setattr("app.tools.web_reader.validate_public_url", lambda url: url)
    monkeypatch.setattr("app.tools.web_reader.fetch_public_article", lambda url: _resolve(thin))
    result = await crawl_public_site("https://example.com/thin", assessor=AlwaysIncludeAssessor())
    assert len(result.pages) == 0
    assert result.rejected[0]["reason"] == "清洗后正文内容不足"


@pytest.mark.asyncio
async def test_crawl_fetch_is_bounded_by_remaining_task_deadline(monkeypatch):
    async def never_finishes(_url):
        await asyncio.sleep(10)

    ticks = iter((0.0, 0.0, 599.99))
    monkeypatch.setattr("app.tools.web_reader.validate_public_url", lambda url: url)
    monkeypatch.setattr("app.tools.web_reader.fetch_public_article", never_finishes)
    monkeypatch.setattr("app.tools.web_reader.monotonic", lambda: next(ticks, 599.99))
    monkeypatch.setattr("app.tools.web_reader.CRAWL_TIMEOUT_SECONDS", 600.0)
    result = await crawl_public_site("https://example.com/index")
    assert result.pages == ()
    assert result.status == "PARTIAL_COMPLETED"
    assert result.stop_reason == "总抓取时长达到 10 分钟"


def test_article_parser_removes_common_boilerplate() -> None:
    from app.tools.web_reader import _ArticleParser
    parser = _ArticleParser()
    parser.feed("""
        <html><head><title>Thread pools</title></head><body>
        <div class='cookie-banner'>Accept cookies</div>
        <nav><a href='/menu'>Menu</a></nav>
        <main><article><h1>Thread pools</h1><p>A thread pool reuses worker threads for tasks.</p>
        <pre>executor.submit(task)</pre></article></main>
        <section class='related-posts'><a href='/unrelated'>Related</a></section>
        <a href='/next'>Next chapter</a></body></html>
    """)
    parser.close()
    markdown = "\n".join(parser.blocks)
    assert "worker threads" in markdown
    assert "executor.submit" in markdown
    assert "Accept cookies" not in markdown
    assert "Related" not in markdown
    assert parser.links == ["/next"]


class AlwaysIncludeAssessor:
    async def assess(self, **kwargs):
        return type("Decision", (), {"include_as_knowledge": True, "expand_links": False,
                                      "selected_links": [], "reason": "include"})()


class InventingAssessor:
    async def assess(self, **kwargs):
        if kwargs["url"].endswith("/index"):
            choices = [
                type("Choice", (), {"url": "https://example.com/invented", "priority": 1})(),
                type("Choice", (), {"url": "https://outside.example/page", "priority": 2})(),
                type("Choice", (), {"url": "https://example.com/allowed", "priority": 3})(),
            ]
            return type("Decision", (), {"include_as_knowledge": False, "expand_links": True,
                                          "selected_links": choices, "reason": "directory"})()
        return type("Decision", (), {"include_as_knowledge": True, "expand_links": False,
                                      "selected_links": [], "reason": "content"})()


class DirectoryAssessor:
    async def assess(self, **kwargs):
        url = kwargs["url"]
        if url.endswith("/index"):
            choice = type("Choice", (), {"url": "https://example.com/child", "priority": 1})()
            return type("Decision", (), {"include_as_knowledge": False, "expand_links": True,
                                          "selected_links": [choice], "reason": "directory only"})()
        return type("Decision", (), {"include_as_knowledge": True, "expand_links": False,
                                      "selected_links": [], "reason": "rich content"})()


async def _resolve(value):
    return value
