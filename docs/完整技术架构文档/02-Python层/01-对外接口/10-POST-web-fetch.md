# POST /v1/tools/web/fetch：抓取单个公开网页

## 1. 接口定义

该接口校验 URL 只能指向公开 HTTP(S) 主机，限制重定向、响应大小和内容类型，提取正文为 Markdown，并返回标题、哈希、来源、链接等溯源数据。网页文本只作为数据返回，不会作为系统指令执行。

| 项目 | 内容 |
|---|---|
| HTTP 方法 | POST |
| 路径 | `/v1/tools/web/fetch` |
| 路由函数 | `fetch_web` |
| 文件 | `python-agent/app/api/application.py:252-268` |

## 2. 函数调用链

```text
fetch_web -> _remember_request_context -> fetch_public_article
 -> validate_public_url -> _is_public_host
 -> httpx.AsyncClient.get
 -> （重定向）validate_public_url
 -> _ArticleParser.__init__/handle_starttag/handle_endtag/handle_data/close/_flush
 -> WebDocument -> WebDocument.as_dict -> AgentResponse
```

## 3. 函数解析

### 3.1 `fetch_web`

文件：`python-agent/app/api/application.py:252-268`

```python
    @app.post("/v1/tools/web/fetch", response_model=AgentResponse)
    async def fetch_web(payload: AgentWebFetchRequest, request: Request) -> AgentResponse:
        """Fetch and extract a single public HTML page for preview/import.

        No page content is executed or fed into system instructions.  The
        caller receives Markdown plus provenance so the upper layer can ask
        for an explicit confirmation before indexing it.
        """
        _remember_request_context(request, payload)
        document = await fetch_public_article(payload.url)
        return AgentResponse(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, code=100, status=RunStatus.COMPLETED,
            user_id=payload.user_id, session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE, state_version=0,
            answer=document.title, output=document.as_dict(), error=None,
        )
```

逐行解释：

1. 第 252-253 行：注册单页抓取接口并声明请求、响应类型。
2. 第 254-259 行：文档字符串规定安全边界：网页内容不执行、不进入系统指令，上层必须显式确认后才能索引。
3. 第 260 行：缓存协议上下文。
4. 第 261 行：异步调用 `fetch_public_article`，所有 URL、网络和正文限制都在该函数执行。
5. 第 262-268 行：返回完成响应；标题作为答案，`as_dict` 生成 Markdown 与溯源字段。

### 3.2 `validate_public_url`

文件：`python-agent/app/tools/web_reader.py:203-215`

```python
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
```

逐行解释：

1. 第 203-204 行：去除两端空白并解析 URL。
2. 第 205-206 行：只允许 http/https 且必须有主机名。
3. 第 207-210 行：读取端口，语法无效时包装为不可重试业务依赖错误。
4. 第 211-212 行：拒绝 URL 凭据及 80、443 之外端口。
5. 第 213-214 行：调用 `_is_public_host` 做 DNS/IP 安全检查，拒绝内网和特殊地址。
6. 第 215 行：返回解析器规范化后的 URL。

### 3.3 `fetch_public_article`

文件：`python-agent/app/tools/web_reader.py:218-270`

```python
async def fetch_public_article(url: str) -> WebDocument:
    current_url = validate_public_url(url)
    last_error: Exception | None = None
    async with httpx.AsyncClient(timeout=httpx.Timeout(TIMEOUT_SECONDS),
        follow_redirects=False, headers={"User-Agent": "InterviewAgentWebReader/1.0"}) as client:
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
                    return WebDocument(url=current_url, title=title[:500],
                        fetched_at=datetime.now(timezone.utc).isoformat(),
                        content_hash=hashlib.sha256(markdown.encode("utf-8")).hexdigest(),
                        markdown=markdown, content_type=content_type,
                        links=tuple(parser.links), raw_byte_size=len(body))
                raise AgentDependencyError("too many web redirects", retryable=False)
            except AgentDependencyError:
                raise
            except (httpx.TimeoutException, httpx.NetworkError, httpx.HTTPStatusError) as error:
                last_error = error
                if attempt >= MAX_RETRIES:
                    break
                await asyncio.sleep(0.5 * (attempt + 1))
    raise AgentDependencyError("web page fetch failed after retries", retryable=True) from last_error
```

逐句解释：

1. 入口先验证初始 URL，并预留最后一次网络异常。
2. 创建不自动跟随重定向的异步客户端，配置总请求超时和固定 User-Agent。
3. 外层循环最多进行初次请求加两次网络重试；内层循环最多接受三次重定向。
4. 每次重定向必须有 location，并把拼接后的新 URL 再次执行公开主机校验，防止跳转到内网。
5. 非重定向响应先检查 HTTP 状态，再限制为 HTML/XHTML 和 5MB 大小。
6. `_ArticleParser` 解码并解析正文；`errors="replace"` 防止坏字节中断解析。
7. 标题为空时回退 URL，正文块过滤过短项，组装并截断 Markdown。
8. 可读文本少于 80 字符时拒绝，避免导入空壳页面。
9. 成功构造不可变 `WebDocument`，包含 UTC 时间、SHA-256、链接和原始字节数。
10. 业务安全错误不重试；网络、超时和 HTTP 状态错误按线性退避重试。
11. 重试耗尽后抛可重试依赖错误，并保留最后异常原因。

## 4. 审核结论

单页抓取链路覆盖了 SSRF 防护、重定向复验、网络重试、大小限制、HTML 解析和溯源序列化。
