# POST /v1/tools/web/crawl：受限站点抓取

## 1. 接口定义

该接口从公开入口 URL 开始，在同域、深度、页面数、总字节、总 Markdown 字符和十分钟时限内广度抓取。每个页面可由 `WebCrawlPlanningAgent` 判断是否纳入知识、是否展开链接，最终返回页面工件和只归档、不直接参与 RAG 的总归档文本。

| 项目 | 内容 |
|---|---|
| HTTP 方法 | POST |
| 路径 | `/v1/tools/web/crawl` |
| 路由函数 | `crawl_web` |
| 请求模型 | `AgentWebCrawlRequest` |
| 响应模型 | `AgentResponse` |
| 文件 | `python-agent/app/api/application.py:273-290` |

## 2. 函数调用链

```text
请求进入 FastAPI
 -> AgentWebCrawlRequest 字段约束校验
 -> crawl_web
    -> _remember_request_context
    -> LLMFactory.create_chat_model
       -> get_settings
    -> PromptLoader.__init__
    -> RetryPolicy.load
    -> AsyncRetryExecutor.__init__
    -> WebCrawlPlanningAgent.__init__
       -> StructuredOutputInvoker.__init__
    -> crawl_public_site
       -> validate_public_url -> _is_public_host
       -> normalize_crawl_url
       -> fetch_public_article
          -> validate_public_url -> _is_public_host
          -> _ArticleParser.__init__
          -> HTMLParser.feed（标准库）
             -> _ArticleParser.handle_starttag / handle_endtag / handle_data
             -> _ArticleParser._flush
          -> _ArticleParser.close -> _ArticleParser._flush
       -> [每个页面链接] normalize_crawl_url
       -> [配置 assessor] WebCrawlPlanningAgent.assess
          -> PromptLoader.render -> PromptLoader.load -> PromptLoader._resolve
          -> StructuredOutputInvoker.invoke
             -> PromptLoader.render -> load -> _resolve -> replace
             -> _few_shot_output
             -> AsyncRetryExecutor.max_output_correction_attempts
             -> StructuredOutputInvoker._invoke_model
                -> AsyncRetryExecutor.execute
                   -> _is_retryable / _backoff_seconds
             -> StructuredOutputInvoker._validate
                -> _content_as_text -> _strip_json_fence
             -> [输出校验失败] _readable_validation_error
                -> _content_as_text（把错误输出加入纠错上下文）
       -> _page_is_rich
       -> [未获得模型决策] _page_is_relevant
       -> _archive_markdown
       -> CrawlResult
    -> CrawlResult.as_dict
       -> [每个有效页面] WebDocument.as_dict
    -> AgentResponse -> AgentResponse.validate_code_category
 -> FastAPI 按 response_model 序列化成功响应

项目异常对象构造：
RequestError / ModelConfigurationError / ReliabilityConfigurationError /
PromptConfigurationError / ModelOutputError / AgentDependencyError
 -> ApplicationException.__init__

请求校验失败：request_validation_error -> _error_json_response
 -> _error_response -> _request_context / _session_status_or_failed / _string_or_none
 -> ExceptionHandler.to_code / ExceptionHandler.to_error_info
 -> AgentResponse.validate_code_category -> AgentResponse.to_json_dict

项目异常：application_error -> _mark_failed_interview_progress
 -> _error_json_response -> _error_response -> _request_context
 -> _session_status_or_failed / _string_or_none
 -> ExceptionHandler.to_code / ExceptionHandler.to_error_info
 -> AgentResponse.validate_code_category -> AgentResponse.to_json_dict

未预期异常：unexpected_error -> _mark_failed_interview_progress
 -> _error_json_response -> _error_response -> _request_context
 -> _session_status_or_failed / _string_or_none
 -> ExceptionHandler.to_code / ExceptionHandler.to_error_info
 -> AgentResponse.validate_code_category -> AgentResponse.to_json_dict
```

## 3. 函数解析

### 3.1 `crawl_web`

文件：`python-agent/app/api/application.py:273-290`

```python
    @app.post("/v1/tools/web/crawl", response_model=AgentResponse)
    async def crawl_web(payload: AgentWebCrawlRequest, request: Request) -> AgentResponse:
        _remember_request_context(request, payload)
        from app.agents.llm.factory import LLMFactory
        from app.agents.web_crawl.agent import WebCrawlPlanningAgent
        from app.infrastructure.reliability.policy import RetryPolicy
        from app.infrastructure.reliability.retry import AsyncRetryExecutor
        assessor = WebCrawlPlanningAgent(
            LLMFactory.create_chat_model(), PromptLoader(), AsyncRetryExecutor(RetryPolicy.load())
        )
        result = await crawl_public_site(payload.url, topic=payload.topic, assessor=assessor)
        return AgentResponse(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, code=100, status=RunStatus.COMPLETED,
            user_id=payload.user_id, session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE, state_version=0,
            answer=f"{len(result.pages)} valid pages", output=result.as_dict(), error=None,
        )
```

逐行解释：

1. 第 273 行：用 `@app.post` 注册 `POST /v1/tools/web/crawl`，并要求返回值满足 `AgentResponse`。
2. 第 274 行：定义异步路由；`payload` 已由 FastAPI 按 `AgentWebCrawlRequest` 校验，`request` 提供请求状态。
3. 第 275 行：调用项目函数 `_remember_request_context` 保存协议字段，供统一异常响应恢复请求号、运行号和身份字段。
4. 第 276 行：延迟导入 `LLMFactory`；只有调用抓取接口时才加载模型工厂。
5. 第 277 行：从当前实际路径 `app.agents.web_crawl.agent` 延迟导入 `WebCrawlPlanningAgent`。
6. 第 278 行：延迟导入 `RetryPolicy`。
7. 第 279 行：延迟导入 `AsyncRetryExecutor`。
8. 第 280 行：开始构造页面规划 Agent。
9. 第 281 行：依次调用项目函数 `LLMFactory.create_chat_model()`、`PromptLoader()`、`RetryPolicy.load()` 和 `AsyncRetryExecutor(...)`，并把三项依赖注入规划 Agent。
10. 第 282 行：结束 `WebCrawlPlanningAgent` 构造；其构造函数还会创建 `StructuredOutputInvoker`。
11. 第 283 行：调用并等待项目函数 `crawl_public_site`，传入入口 URL、可选主题和规划 Agent。
12. 第 284 行：开始构造成功 `AgentResponse`；构造时会调用项目校验器 `validate_code_category`。
13. 第 285 行：复制 `apiVersion` 与 `requestId`。
14. 第 286 行：复制 `runId`，设置成功业务码 `100` 和运行状态 `COMPLETED`。
15. 第 287 行：复制 `userId` 与 `sessionId`。
16. 第 288 行：站点抓取不推进面试状态，因此会话状态固定为 `ACTIVE`，版本为 `0`。
17. 第 289 行：用有效页面数生成英文答案；调用项目函数 `CrawlResult.as_dict()` 生成完整输出，并把错误设为 `None`。
18. 第 290 行：结束响应构造并返回。

### 3.2 `normalize_crawl_url`

文件：`python-agent/app/tools/web_reader.py:273-281`

```python
def normalize_crawl_url(url: str, *, base_url: str) -> str | None:
    candidate = urljoin(base_url, url).strip()
    parsed = urlparse(candidate)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        return None
    query = [(key, value) for key, value in parse_qsl(parsed.query, keep_blank_values=True)
             if not key.casefold().startswith(("utm_", "fbclid", "gclid", "spm"))]
    return urlunparse((parsed.scheme.lower(), parsed.hostname.lower(), parsed.path or "/",
                       parsed.params, urlencode(query), ""))
```

逐行解释：

1. 第 273 行：定义 URL 规范化函数；`url` 可以是绝对或相对链接，`base_url` 必须按关键字传入。
2. 第 274 行：用标准库 `urljoin` 按当前页面基址合成绝对地址，再去除首尾空白。
3. 第 275 行：调用 `urlparse` 把候选地址拆成协议、主机、路径、参数、查询和 fragment。
4. 第 276 行：检查协议必须是 HTTP/HTTPS，且必须存在 hostname。
5. 第 277 行：非法协议、锚点链接或缺少主机的结果返回 `None`，由调用方跳过。
6. 第 278 行：调用 `parse_qsl` 把查询串解析成键值对，并保留空值；开始列表推导式。
7. 第 279 行：把查询键转为大小写无关形式，排除以 `utm_`、`fbclid`、`gclid` 或 `spm` 开头的跟踪参数。
8. 第 280 行：调用 `urlunparse` 重建 URL；协议和主机统一为小写，空路径统一为 `/`。
9. 第 281 行：保留 path params，用 `urlencode` 重编码过滤后的查询，并把 fragment 固定为空字符串后返回规范化 URL。

### 3.3 `WebCrawlPlanningAgent.__init__` 与 `assess`

`__init__` 文件：`python-agent/app/agents/web_crawl/agent.py:27-31`

1. 第 27 行：定义规划 Agent 构造函数并接收原始聊天模型、PromptLoader 和可选重试器。
2. 第 28 行：声明 `retry_executor` 可以为 `None`，结束多行函数签名。
3. 第 29 行：保存模型到 `_model`，页面评估时复用同一客户端。
4. 第 30 行：保存 PromptLoader 到 `_prompt_loader`。
5. 第 31 行：调用项目函数 `StructuredOutputInvoker.__init__`，把 PromptLoader 和重试器注入统一结构化输出器。

`assess` 文件：`python-agent/app/agents/web_crawl/agent.py:33-40`

```python
    async def assess(self, *, title: str, url: str, topic: str | None,
                     markdown: str, candidate_links: list[str]) -> CrawlPageDecision:
        return await self._invoker.invoke(
            model=self._model, schema=CrawlPageDecision,
            business_prompt=self._prompt_loader.render("web-crawl/planner.md", {}),
            input_payload={"requestedTopic": topic, "pageUrl": url, "pageTitle": title,
                           "cleanedPageText": markdown[:12000], "candidateLinks": candidate_links[:100]},
        )
```

逐行解释：

1. 第 33 行：定义异步页面评估函数，开始声明仅限关键字参数的标题、URL 和主题。
2. 第 34 行：继续声明清洗后的 Markdown、规范化候选链接列表，并指定返回 `CrawlPageDecision`。
3. 第 35 行：调用并等待项目函数 `StructuredOutputInvoker.invoke`；校验成功后直接返回结构化决策。
4. 第 36 行：传入共享模型，并把 Pydantic schema 指定为 `CrawlPageDecision`。
5. 第 37 行：调用项目函数 `PromptLoader.render("web-crawl/planner.md", {})`，从外部 Prompt 文件加载业务规则；空变量字典表示模板不应要求动态占位符。
6. 第 38 行：开始构造不可信网页输入，写入用户请求主题、页面 URL 和标题。
7. 第 39 行：正文最多保留 12000 字符，候选链接最多保留 100 个，限制模型上下文和输出选择面。
8. 第 40 行：结束输入字典和结构化调用。

### 3.4 `crawl_public_site`

文件：`python-agent/app/tools/web_reader.py:325-440`

```python
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
```

逐行解释：

1. 第 325 行：定义异步站点抓取函数，接收入口 URL，并开始声明可选参数。
2. 第 326 行：声明可选主题和可选页面评估器，返回结构化 `CrawlResult`。
3. 第 327 行：调用项目函数 `validate_public_url` 对入口地址做协议、端口、凭证和公网 IP 校验。
4. 第 328 行：解析校验后的入口主机名并用 `casefold` 规范化，作为整个任务不可改变的同域边界。
5. 第 329 行：记录单调时钟起点；单调时钟不受系统时间校准影响，适合计算十分钟总时限。
6. 第 330 行：创建广度优先队列，首项保存入口 URL、深度 `0` 和空父页面。
7. 第 331 行：创建已处理规范 URL 集合，防止循环链接重复访问。
8. 第 332 行：创建正文哈希集合，防止不同 URL 的相同内容重复占用有效页面配额。
9. 第 333 行：创建有效页面工件列表。
10. 第 334 行：创建拒绝页面及原因列表。
11. 第 335 行：把累计原始响应字节数初始化为 `0`。
12. 第 336 行：把候选页面访问次数初始化为 `0`。
13. 第 337 行：初始化停止原因为 `None`，表示尚未触发任何硬限制。
14. 第 338 行：只在队列非空且有效页面少于 `CRAWL_MAX_VALID_PAGES` 时继续主循环。
15. 第 339 行：用当前单调时间减起点，检查是否达到任务总时限。
16. 第 340 行：达到十分钟时写入明确停止原因。
17. 第 341 行：跳出主循环，不再访问新页面。
18. 第 342 行：检查累计原始响应字节是否达到总量上限。
19. 第 343 行：达到 50MB 时写入停止原因。
20. 第 344 行：结束抓取循环。
21. 第 345 行：检查候选页面访问次数是否达到安全上限。
22. 第 346 行：达到上限时记录对应停止原因。
23. 第 347 行：结束主循环。
24. 第 348 行：从队列左端取出 URL、深度和父页面，实现广度优先遍历。
25. 第 349 行：调用项目函数 `normalize_crawl_url`，以入口 URL 为基址生成稳定去重键。
26. 第 350 行：检查规范化失败或 URL 已经处理。
27. 第 351 行：不合格或重复 URL 直接进入下一轮，不计访问次数。
28. 第 352 行：再次解析候选主机并与固定入口主机比较，执行抓取前同域约束。
29. 第 353 行：跨域链接加入拒绝列表；规范化结果为空时回退记录原 URL。
30. 第 354 行：跨域候选处理结束，继续下一轮。
31. 第 355 行：在真正发起网络请求前把规范 URL 加入 `seen`。
32. 第 356 行：访问计数加一，失败页面同样占用安全访问预算。
33. 第 357 行：进入单页抓取异常保护，使单页失败通常不会终止整个任务。
34. 第 358 行：从十分钟总预算减去已耗时，计算当前页剩余可用秒数。
35. 第 359 行：检查剩余时间是否已耗尽。
36. 第 360 行：耗尽时设置总时长停止原因。
37. 第 361 行：结束整个抓取循环。
38. 第 362 行：调用 `asyncio.wait_for`，以剩余总预算限制单页抓取。
39. 第 363 行：调用项目函数 `fetch_public_article(normalized)`，并把剩余秒数作为外层超时。
40. 第 364 行：结束等待，成功时得到经过 SSRF 校验和正文清洗的 `WebDocument`。
41. 第 365 行：在累计前预判加入本页原始字节后是否超过 50MB。
42. 第 366 行：会超限时设置总字节停止原因。
43. 第 367 行：不纳入当前页面并终止抓取。
44. 第 368 行：未超限时把当前页原始字节数计入任务总量。
45. 第 369 行：捕获 `wait_for` 产生的 `TimeoutError`。
46. 第 370 行：把外层超时解释为任务总时限到达。
47. 第 371 行：终止整个任务，而不是继续消耗已耗尽的时间预算。
48. 第 372 行：捕获当前候选页面的其他异常。
49. 第 373 行：把规范 URL 和截断到 200 字符的异常消息加入拒绝列表。
50. 第 374 行：跳过失败页面并继续抓取其他候选。
51. 第 375 行：抓取完成后检查最终重定向 URL 是否仍属于入口域名。
52. 第 376 行：若重定向离开入口域名，则记录最终 URL 和拒绝原因。
53. 第 377 行：拒绝该页面，既不评估正文也不展开其链接。
54. 第 378 行：遍历页面解析器收集的每个原始子链接，开始构造规范候选列表。
55. 第 379 行：逐项调用项目函数 `normalize_crawl_url`；使用海象表达式只保留返回非空的规范链接。
56. 第 380 行：把模型决策初始化为 `None`，该值代表稍后使用确定性回退规则。
57. 第 381 行：判断调用方是否注入页面评估器。
58. 第 382 行：进入模型评估保护；模型失败不能中断整个爬取任务。
59. 第 383 行：调用并等待项目函数 `WebCrawlPlanningAgent.assess`。
60. 第 384 行：传入页面标题、最终 URL 和用户主题。
61. 第 385 行：传入清洗后的 Markdown 和规范候选链接列表。
62. 第 386 行：结束评估调用，成功时得到 `CrawlPageDecision`。
63. 第 387 行：捕获评估器产生的任意普通异常，包括模型、Prompt 和输出校验失败。
64. 第 388 行：评估失败时把决策恢复为 `None`，触发确定性规则而不是终止抓取。
65. 第 389 行：调用项目函数 `_page_is_rich` 执行不可被模型绕过的正文质量硬门槛。
66. 第 390 行：只有正文足够丰富时才继续计算是否纳入知识页。
67. 第 391 行：模型决策不存在时调用项目函数 `_page_is_relevant(document, topic)`。
68. 第 392 行：模型决策存在时读取 `include_as_knowledge` 并显式转为布尔值。
69. 第 393 行：结束纳入条件表达式；质量硬门槛与相关性/模型判断必须同时成立。
70. 第 394 行：没有模型决策时，只要存在候选链接就允许展开。
71. 第 395 行：有模型决策时使用其 `expand_links` 字段决定是否继续遍历。
72. 第 396 行：仅对计划纳入的页面检查正文哈希是否已经出现。
73. 第 397 行：判断当前页面是否与已纳入内容重复。
74. 第 398 行：重复时把 URL 和“清洗后内容重复”加入拒绝列表。
75. 第 399 行：把 `include_page` 改为 `False`，防止重复页占用 20 页配额。
76. 第 400 行：开始计算若纳入当前页面后的 Markdown 总字符数。
77. 第 401 行：累加已纳入页面 Markdown 长度，再加当前文档长度。
78. 第 402 行：结束字符数计算。
79. 第 403 行：检查计划纳入的页面是否会使总 Markdown 超过配置上限。
80. 第 404 行：会超限时写入明确停止原因。
81. 第 405 行：终止任务，避免返回超过上层可处理范围的工件集合。
82. 第 406 行：判断当前页面最终是否应纳入有效页面列表。
83. 第 407 行：把正文哈希加入集合，供后续不同 URL 内容去重。
84. 第 408 行：用正则把标题中的非字母、数字或中文字符替换为连字符，去除两端连字符并限制 60 字符。
85. 第 409 行：以当前页面序号和 slug 生成稳定 Markdown 文件名；空 slug 回退为 `web-page`。
86. 第 410 行：开始构造页面级 YAML front matter 行列表。
87. 第 411 行：写分隔符和安全标题；标题中的双引号替换为单引号。
88. 第 412 行：写最终来源 URL 和抓取时间。
89. 第 413 行：写内容哈希和当前抓取深度。
90. 第 414 行：写父 URL、`rag_index_enabled: true`、结束分隔符和空行。
91. 第 415 行：用换行连接 front matter。
92. 第 416 行：调用标准库 `dataclasses.replace` 复制不可变文档，并把 front matter 前置到 Markdown。
93. 第 417 行：构造 `CrawlPage`，保存工件文档、深度、父 URL 和文件名，再加入有效页面列表。
94. 第 418 行：若页面未纳入且不是重复内容，则进入拒绝原因分支。
95. 第 419 行：正文不够丰富时使用固定原因；否则开始读取模型原因。
96. 第 420 行：从决策读取 `reason`，不存在时用确定性默认原因，并截断为 200 字符。
97. 第 421 行：把规范 URL 和最终原因加入拒绝列表。
98. 第 422 行：重新计算已纳入页面 Markdown 总长度，检查是否已经达到上限。
99. 第 423 行：达到上限时写停止原因。
100. 第 424 行：结束主循环。
101. 第 425 行：只有当前深度小于 `CRAWL_MAX_DEPTH` 且允许展开时才处理子链接。
102. 第 426 行：默认选择全部规范候选链接。
103. 第 427 行：有模型决策时进入受控链接选择分支。
104. 第 428 行：把实际候选链接转成集合，作为模型输出白名单。
105. 第 429 行：读取模型 `selected_links`，并按 `priority` 从小到大稳定排序。
106. 第 430 行：缺少 priority 时使用 100，结束排序调用。
107. 第 431 行：只保留模型返回且确实存在于候选白名单中的 URL，禁止模型编造链接。
108. 第 432 行：遍历最终选择的规范子链接。
109. 第 433 行：检查子链接尚未处理。
110. 第 434 行：把子链接、下一深度和当前页面 URL 加入队列尾部。
111. 第 435 行：主循环结束后，若没有其他停止原因但队列仍有内容，说明有效页面配额先耗尽。
112. 第 436 行：把停止原因设为“有效页面数量达到 20 页”。
113. 第 437 行：只有队列耗尽且没有停止原因才标记 `COMPLETED`，否则标记 `PARTIAL_COMPLETED`。
114. 第 438 行：调用项目函数 `_archive_markdown` 生成只归档、不参与 RAG 的整站溯源文本。
115. 第 439 行：开始构造并返回 `CrawlResult`，把页面和拒绝列表转换成不可变元组。
116. 第 440 行：写入状态、停止原因和归档 Markdown，结束结果构造并返回。

### 3.5 `_page_is_relevant`

文件：`python-agent/app/tools/web_reader.py:284-293`

1. 第 284 行：定义确定性主题相关性判断函数，接收网页文档和可选主题。
2. 第 285 行：首先检查 Markdown 长度是否少于 160 字符。
3. 第 286 行：正文过短直接返回 `False`，该规则不受主题或模型影响。
4. 第 287 行：检查主题不存在或去空白后为空。
5. 第 288 行：没有主题约束时把足够长的页面视为相关。
6. 第 289 行：用正则从主题提取长度至少为 2 的单词或中文片段，统一大小写后放入集合。
7. 第 290 行：检查主题没有产生有效检索词。
8. 第 291 行：无有效词时回退为相关，避免纯标点主题误杀页面。
9. 第 292 行：把标题和正文前 5000 字符拼成检索文本并统一大小写。
10. 第 293 行：任一主题 token 出现在检索文本中即返回 `True`，否则返回 `False`。

### 3.6 `_page_is_rich`

文件：`python-agent/app/tools/web_reader.py:296-300`

1. 第 296 行：定义页面正文丰富度硬校验函数。
2. 第 297 行：文档字符串说明该确定性门槛先于 Agent 决策，防止低质量页占用有效页面名额。
3. 第 298 行：用正则把 Markdown 标记和空白统一替换为空格，再去除首尾空白，得到纯文本近似值。
4. 第 299 行：提取英文数字标识符或单个中文字符，形成可计数词元列表。
5. 第 300 行：只有纯文本至少 160 字符且词元至少 35 个时返回 `True`。

### 3.7 `_archive_markdown`

文件：`python-agent/app/tools/web_reader.py:303-322`

1. 第 303 行：定义归档 Markdown 构造函数，接收入口、有效页面、拒绝项，并开始多行签名。
2. 第 304 行：继续接收任务状态和可选停止原因，返回字符串。
3. 第 305 行：创建输出行列表，写 YAML 分隔符、禁止直接 RAG 索引标记、文档类型和标题。
4. 第 306 行：继续写归档标题、入口 URL。
5. 第 307 行：写任务状态、有效页数量和无效/重复页数量。
6. 第 308 行：检查是否存在停止原因。
7. 第 309 行：存在时把原因追加到元数据区。
8. 第 310 行：追加来源目录标题和空行。
9. 第 311 行：从 1 开始枚举每个有效页面。
10. 第 312 行：读取页面中的 `WebDocument`，减少后续重复属性访问。
11. 第 313 行：追加带标题和来源 URL 的有序目录项，以及对应文件名。
12. 第 314 行：继续追加抓取深度、SHA-256 和空行。
13. 第 315 行：追加“仅归档、不参与 RAG”的页面内容总标题。
14. 第 316 行：遍历所有有效页面工件。
15. 第 317 行：为每页追加二级标题、来源链接和空行。
16. 第 318 行：追加页面 Markdown 和尾随空行。
17. 第 319 行：检查拒绝列表非空。
18. 第 320 行：存在拒绝项时追加“未纳入页面”标题。
19. 第 321 行：用生成器逐项追加 `URL：原因`，保留拒绝审计轨迹。
20. 第 322 行：用换行连接所有行，去除首尾空白，再确保归档以单个换行结束并返回。

### 3.8 `CrawlResult.as_dict` 与 `WebDocument.as_dict`

`CrawlResult.as_dict` 文件：`python-agent/app/tools/web_reader.py:79-91`

1. 第 79 行：定义抓取结果到协议字典的转换函数。
2. 第 80 行：开始构造并返回结果字典。
3. 第 81 行：把入口 URL 输出为 camelCase 键 `entryUrl`。
4. 第 82 行：输出完成或部分完成状态。
5. 第 83 行：输出可选停止原因。
6. 第 84 行：计算有效页面元组长度并输出 `validPageCount`。
7. 第 85 行：计算拒绝项数量并输出 `rejectedCount`。
8. 第 86 行：遍历每个 `CrawlPage`，先调用项目函数 `page.document.as_dict()`，再合并抓取深度。
9. 第 87 行：继续为每页写父 URL 和文件名。
10. 第 88 行：结束页面列表推导式。
11. 第 89 行：把拒绝项元组转换为 JSON 可序列化列表。
12. 第 90 行：输出完整归档 Markdown。
13. 第 91 行：结束并返回字典。

`WebDocument.as_dict` 文件：`python-agent/app/tools/web_reader.py:48-59`

1. 第 48 行：定义单页文档到协议字典的转换函数。
2. 第 49 行：开始构造并返回字典。
3. 第 50 行：输出经过重定向后的最终 URL。
4. 第 51 行：输出页面标题。
5. 第 52 行：把抓取时间输出为 `fetchedAt`。
6. 第 53 行：把内容哈希输出为 `contentHash`。
7. 第 54 行：输出含页面 front matter 的 Markdown。
8. 第 55 行：把媒体类型输出为 `contentType`。
9. 第 56 行：动态计算 Markdown 字符数并输出 `characterCount`。
10. 第 57 行：把不可变链接元组转换为列表。
11. 第 58 行：把原始响应字节数输出为 `rawByteSize`。
12. 第 59 行：结束并返回字典。

### 3.9 `_remember_request_context`

文件：`python-agent/app/api/application.py:391-394`

1. 第 391 行：定义请求上下文记忆函数，接收当前请求和任意 payload。
2. 第 392 行：使用 `getattr` 读取 payload 的可选 `model_dump` 方法，不存在时返回 `None`。
3. 第 393 行：检查读取结果是否可调用。
4. 第 394 行：按字段别名和 JSON 模式转储请求模型，并保存到 `request.state.agent_context`。

### 3.10 `LLMFactory.create_chat_model` 与 `get_settings`

`LLMFactory.create_chat_model` 文件：`python-agent/app/agents/llm/factory.py:12-39`

1. 第 12 行：声明该模型工厂函数为静态方法。
2. 第 13 行：定义聊天模型客户端创建函数，允许调用方显式传入 Settings。
3. 第 14 行：显式配置优先；本路由未传入，因此调用项目函数 `get_settings()` 取得进程级配置。
4. 第 16 行：创建允许的 provider 集合，只接受 OpenAI、OpenAI-compatible 和 custom。
5. 第 17 行：把配置中的 provider 转小写并检查是否在白名单中。
6. 第 18 行：不支持时开始构造 `ModelConfigurationError`。
7. 第 19 行：错误消息包含实际 provider，便于定位部署配置。
8. 第 20 行：结束并抛出项目异常；构造时调用 `ApplicationException.__init__`。
9. 第 21 行：检查模型名称是否非空。
10. 第 22 行：缺失时抛 `ModelConfigurationError`。
11. 第 23 行：检查模型 API Key 是否非空。
12. 第 24 行：缺失时抛 `ModelConfigurationError`。
13. 第 26 行：开始构造传给 LangChain 客户端的关键字参数字典。
14. 第 27 行：写入模型名称。
15. 第 28 行：写入 API Key。
16. 第 29 行：写入模型温度。
17. 第 30 行：写入统一请求超时秒数。
18. 第 31 行：注释说明重试由项目工程层统一控制，避免 SDK 与上层叠加重试。
19. 第 32 行：把 SDK `max_retries` 设为 `0`，落实单一重试责任。
20. 第 33 行：结束基础参数字典。
21. 第 34 行：检查是否配置自定义模型 base URL。
22. 第 35 行：存在时加入 `base_url`，支持 OpenAI-compatible 服务。
23. 第 36 行：检查是否显式配置最大输出 token。
24. 第 37 行：存在时加入 `max_tokens`。
25. 第 39 行：创建并返回 `ChatOpenAI` 客户端；此处只建客户端，不发起模型请求。

`get_settings` 文件：`python-agent/app/common/config.py:47-51`

1. 第 47 行：用 `lru_cache(maxsize=1)` 缓存配置函数结果，保证进程内只构造一个配置快照。
2. 第 48 行：定义无参数配置读取函数。
3. 第 49 行：文档字符串说明这是进程级只读快照，测试可清理缓存后重读。
4. 第 51 行：实例化 `Settings`，由 Pydantic Settings 从环境变量和 `.env` 读取并校验配置。

### 3.11 `PromptLoader.__init__`

文件：`python-agent/app/common/prompt_loader.py:16-17`

1. 第 16 行：定义 PromptLoader 构造函数，允许测试或调用方覆盖 Prompt 根目录。
2. 第 17 行：显式根目录优先；本路由未传入，因此使用项目 `resources/prompts` 目录。

### 3.12 `RetryPolicy.load` 与 `AsyncRetryExecutor.__init__`

`RetryPolicy.load` 文件：`python-agent/app/infrastructure/reliability/policy.py:20-45`

1. 第 20 行：声明类方法。
2. 第 21 行：定义重试策略加载函数，允许显式配置文件路径。
3. 第 22 行：显式路径优先，否则使用 `resources/agent/reliability.json`。
4. 第 23 行：进入文件读取、JSON 解析和类型转换保护。
5. 第 24 行：以 UTF-8 读取并解析 JSON。
6. 第 25 行：开始构造不可变 `RetryPolicy`。
7. 第 26 行：读取 `maxAttempts` 并转为整数。
8. 第 27 行：读取初始退避毫秒数。
9. 第 28 行：读取最大退避毫秒数。
10. 第 29 行：把可重试异常类名转成字符串冻结集合。
11. 第 30 行：读取单次超时秒数，缺失时使用 120。
12. 第 31 行：读取结构化输出纠错次数，缺失时使用 2。
13. 第 32 行：结束策略构造。
14. 第 33 行：捕获文件不存在、字段缺失、值/类型错误或 JSON 格式错误。
15. 第 34 行：统一转换为 `ReliabilityConfigurationError` 并保留原异常。
16. 第 35 行：检查总尝试次数在 1 到 5 之间，且初始退避非负。
17. 第 36 行：任一条件不满足时抛配置错误。
18. 第 37 行：检查最大退避不得小于初始退避。
19. 第 38 行：不满足时抛配置错误。
20. 第 39 行：检查单次模型调用超时大于 0 且不超过 120 秒。
21. 第 40 行：超出范围时抛配置错误。
22. 第 41 行：检查结构化输出纠错次数位于 0 到 2。
23. 第 42 行：不满足时抛配置错误。
24. 第 43 行：检查可重试异常集合非空。
25. 第 44 行：为空时抛配置错误。
26. 第 45 行：全部读取与边界校验通过后返回策略。

`AsyncRetryExecutor.__init__` 文件：`python-agent/app/infrastructure/reliability/retry.py:16-17`

1. 第 16 行：定义重试执行器构造函数并接收已校验策略。
2. 第 17 行：把策略保存到 `_policy`，供超时、重试判断和退避计算使用。

### 3.13 `WebCrawlPlanningAgent.__init__` 调用的 `StructuredOutputInvoker.__init__`

文件：`python-agent/app/infrastructure/reliability/structured_output.py:26-28`

1. 第 26 行：定义结构化输出器构造函数，接收 PromptLoader 与可选重试器。
2. 第 27 行：保存 PromptLoader，统一格式提示和业务 Prompt 均通过它加载。
3. 第 28 行：保存可选重试器，控制模型网络重试和最大格式纠错次数。

### 3.14 `StructuredOutputInvoker.invoke`

文件：`python-agent/app/infrastructure/reliability/structured_output.py:30-70`

1. 第 30 行：定义异步结构化输出调用函数并开始多行签名。
2. 第 31 行：声明实例参数。
3. 第 32 行：使用 `*` 强制后续依赖按关键字传入，避免模型、schema 和 Prompt 位置混淆。
4. 第 33 行：接收符合 `RawChatModel` 协议的聊天模型。
5. 第 34 行：接收目标 Pydantic schema 类型；本链路为 `CrawlPageDecision`。
6. 第 35 行：接收已经由网页规划 Prompt 构造的业务系统提示。
7. 第 36 行：接收只读映射形式的业务输入。
8. 第 37 行：声明返回对应 schema 实例并结束签名。
9. 第 38 行：调用项目函数 `PromptLoader.render` 构造共享 JSON 格式约束。
10. 第 39 行：选择 `shared/structured-output.md` 模板。
11. 第 40 行：开始传入模板变量。
12. 第 41 行：调用 schema 的 `model_json_schema(by_alias=True)`，再序列化为保留中文的 JSON。
13. 第 42 行：加入固定 few-shot 输入 `{"task": "格式示例"}`。
14. 第 43 行：调用项目函数 `_few_shot_output(schema)` 生成当前 schema 的合法最小示例，再序列化。
15. 第 44 行：结束变量映射。
16. 第 45 行：结束 Prompt 渲染，得到 `format_prompt`。
17. 第 46 行：创建发送给模型的初始消息列表。
18. 第 47 行：把网页规划业务 Prompt 与共享格式约束用双换行合并成系统消息。
19. 第 48 行：把页面输入序列化为中文安全 JSON 用户消息；`default=str` 处理非原生 JSON 对象。
20. 第 49 行：结束初始消息列表。
21. 第 50 行：有重试器时调用项目属性 `max_output_correction_attempts`，否则把纠错次数设为 0。
22. 第 52 行：从 0 开始遍历“首次输出加有限纠错”的总次数。
23. 第 53 行：调用并等待项目函数 `_invoke_model(model, messages)` 获得原始响应。
24. 第 54 行：进入 JSON 解析和 Pydantic 校验保护。
25. 第 55 行：调用项目函数 `_validate`；成功时立即返回 `CrawlPageDecision`。
26. 第 56 行：捕获 JSON 格式、Pydantic 字段、类型或值错误。
27. 第 57 行：调用项目函数 `_readable_validation_error` 把异常转成受控长度原因。
28. 第 58 行：检查当前纠错轮次是否已经达到上限。
29. 第 59 行：耗尽时开始构造 `ModelOutputError`。
30. 第 60 行：错误消息记录实际失败次数和目标 schema 名。
31. 第 61 行：追加最后一次可读校验原因。
32. 第 62 行：结束项目异常构造，并用 `from error` 保留原始校验异常。
33. 第 63 行：仍可纠错时扩展消息历史。
34. 第 64 行：调用项目函数 `_content_as_text`，把上一轮错误输出作为 AI 消息加入上下文。
35. 第 65 行：开始构造纠错用户消息。
36. 第 66 行：明确要求只修复并返回完整 JSON，不得省略字段。
37. 第 67 行：禁止解释和 Markdown，并向模型提供受控校验原因。
38. 第 68 行：结束纠错用户消息。
39. 第 69 行：结束消息列表扩展，下一轮会携带失败输出和纠错指令重新调用模型。
40. 第 70 行：理论不可达保护；循环必然通过第 55 行返回，或从第 59 行开始构造异常、在第 62 行完成抛出。

`AsyncRetryExecutor.max_output_correction_attempts` 文件：`python-agent/app/infrastructure/reliability/retry.py:19-21`

1. 第 19 行：声明只读属性。
2. 第 20 行：定义最大输出纠错次数访问器。
3. 第 21 行：返回重试策略中的 `max_output_correction_attempts`。

`StructuredOutputInvoker._invoke_model` 文件：`python-agent/app/infrastructure/reliability/structured_output.py:72-75`

1. 第 72 行：定义原始模型调用函数，接收模型和消息对象。
2. 第 73 行：检查是否没有统一重试器。
3. 第 74 行：无重试器时直接等待模型 `ainvoke`。
4. 第 75 行：有重试器时调用项目函数 `AsyncRetryExecutor.execute`；lambda 使每次重试都重新创建模型协程。

`StructuredOutputInvoker._validate` 文件：`python-agent/app/infrastructure/reliability/structured_output.py:77-84`

1. 第 77 行：定义原始模型响应校验函数。
2. 第 78 行：检查模型适配器是否已经返回目标 schema 实例。
3. 第 79 行：已经是目标实例时直接返回。
4. 第 80 行：否则调用项目函数 `_content_as_text` 提取统一文本。
5. 第 81 行：调用项目函数 `_strip_json_fence` 去除可选 Markdown 围栏，再用 `json.loads` 解析。
6. 第 82 行：检查 JSON 根节点是否为字典。
7. 第 83 行：数组、字符串或其他根节点抛 `TypeError`。
8. 第 84 行：调用目标 schema 的 `model_validate`，执行别名、枚举、范围、长度和嵌套链接校验。

`_content_as_text` 文件：`python-agent/app/infrastructure/reliability/structured_output.py:87-104`

1. 第 87 行：定义多供应商模型响应文本提取函数。
2. 第 88 行：检查原始结果本身是否为字符串。
3. 第 89 行：是字符串时直接返回。
4. 第 90 行：优先读取响应对象的 `content` 属性，不存在时使用原对象。
5. 第 91 行：检查 content 是否为字符串。
6. 第 92 行：字符串 content 直接返回。
7. 第 93 行：检查 content 是否为列表，兼容分块消息格式。
8. 第 94 行：创建字符串片段列表。
9. 第 95 行：遍历每个内容块。
10. 第 96 行：检查当前块是否直接为字符串。
11. 第 97 行：字符串块直接追加。
12. 第 98 行：否则检查当前块是否为 Mapping，且其 `text` 字段为字符串。
13. 第 99 行：把映射块中的文本追加到片段列表。
14. 第 100 行：遍历结束后检查是否收集到片段。
15. 第 101 行：连接所有片段并返回。
16. 第 102 行：若 content 本身为 Mapping，则进入对象序列化分支。
17. 第 103 行：把映射序列化为保留中文的 JSON 字符串。
18. 第 104 行：所有兼容形式都不匹配时抛 `TypeError`。

`_strip_json_fence` 文件：`python-agent/app/infrastructure/reliability/structured_output.py:107-112`

1. 第 107 行：定义 JSON Markdown 围栏清理函数。
2. 第 108 行：去除模型文本首尾空白。
3. 第 109 行：只有同时以三反引号开头和结尾时才视为围栏。
4. 第 110 行：按行拆分围栏文本。
5. 第 111 行：去掉首尾围栏行，重新连接内部 JSON 并清理空白。
6. 第 112 行：返回清理后的 JSON 文本。

`_readable_validation_error` 文件：`python-agent/app/infrastructure/reliability/structured_output.py:115-120`

1. 第 115 行：定义校验错误摘要函数。
2. 第 116 行：检查异常是否为 Pydantic `ValidationError`。
3. 第 117 行：遍历错误项，把每个 `loc` 路径用点号连接成字段名列表。
4. 第 118 行：最多返回前 8 个失败字段，避免纠错 Prompt 无界增长。
5. 第 119 行：其他异常转字符串、去首尾空白并把换行替换为空格。
6. 第 120 行：最多返回 500 字符；空消息时回退到异常类名。

### 3.15 `_few_shot_output`

文件：`python-agent/app/infrastructure/reliability/structured_output.py:123-154`

1. 第 123 行：定义按 Pydantic schema 返回合法最小输出示例的项目函数。
2. 第 124 行：文档字符串说明每个实际结构都应有具体示例，不能只依赖抽象 JSON 规则。
3. 第 125 行：开始创建其他业务 schema 的示例映射；该字典在判断网页 schema 前会实际构造。
4. 第 126 行：开始 `ResumeEvaluation` 示例。
5. 第 127 行：写总体、内容和结构三个示例分数。
6. 第 128 行：写技能匹配、表达和项目分数。
7. 第 129 行：写总结和优势列表。
8. 第 130 行：写建议和问题对象列表。
9. 第 131 行：写技术栈、技术深度和职业偏好列表。
10. 第 132 行：结束简历评价示例。
11. 第 133 行：开始 `InterviewPlan` 示例。
12. 第 134 行：写候选人摘要、策略摘要和选中 Skill。
13. 第 135 行：开始阶段数组。
14. 第 136 行：写开场阶段的题量、追问、难度、主题和时间预算。
15. 第 137 行：写项目阶段示例。
16. 第 138 行：写基础阶段示例。
17. 第 139 行：写场景阶段示例。
18. 第 140 行：写算法阶段示例。
19. 第 141 行：写总结阶段示例。
20. 第 142 行：结束阶段数组。
21. 第 143 行：结束面试计划示例。
22. 第 144 行：写 `InterviewSkillSelection` 最小示例。
23. 第 145 行：写 `InterviewEvaluation` 最小示例。
24. 第 146 行：写 `InterviewRoute` 最小示例。
25. 第 147 行：写 `GeneratedQuestion` 最小示例。
26. 第 148 行：写 `InterviewSummary` 最小示例。
27. 第 149 行：结束通用示例映射。
28. 第 150 行：检查当前 schema 类名是否为本接口使用的 `CrawlPageDecision`。
29. 第 151 行：命中时开始返回网页专用示例，类型为 `CONTENT` 且允许纳入知识。
30. 第 152 行：示例不展开链接，并给出 85 的相关度。
31. 第 153 行：写非空原因和空选择链接列表，结束并返回网页决策示例。
32. 第 154 行：其他 schema 按类名从通用映射取示例，未知结构回退为空字典。

### 3.16 `PromptLoader.render`、`load`、`_resolve` 与 `replace`

`PromptLoader.render` 文件：`python-agent/app/common/prompt_loader.py:26-40`

嵌套函数 `replace` 文件：`python-agent/app/common/prompt_loader.py:29-35`

1. 第 26 行：定义受控 Prompt 变量渲染函数。
2. 第 27 行：调用项目函数 `load(prompt_id)` 读取模板。
3. 第 29 行：定义只在本次渲染闭包中使用的项目嵌套函数 `replace`。
4. 第 30 行：从正则匹配第一捕获组取得占位符变量名。
5. 第 31 行：检查变量字典是否包含该键。
6. 第 32 行：缺失时开始构造 `PromptConfigurationError`。
7. 第 33 行：错误消息包含 Prompt ID 和缺失变量名。
8. 第 34 行：结束并抛出项目异常。
9. 第 35 行：变量存在时转成字符串，作为正则替换结果。
10. 第 37 行：用预编译占位符正则调用嵌套函数 `replace`，替换模板中所有变量。
11. 第 38 行：再次搜索渲染结果，检查是否仍含占位符。
12. 第 39 行：有残留时抛 `PromptConfigurationError`，避免未解析模板进入模型。
13. 第 40 行：返回完全渲染后的 Prompt。

`PromptLoader.load` 文件：`python-agent/app/common/prompt_loader.py:19-24`

1. 第 19 行：定义 Prompt 文件读取函数。
2. 第 20 行：调用项目函数 `_resolve(prompt_id)` 得到经过目录边界校验的路径。
3. 第 21 行：进入文件读取保护。
4. 第 22 行：以 UTF-8 读取完整 Prompt 文本并返回。
5. 第 23 行：捕获文件不存在异常。
6. 第 24 行：转换成包含 Prompt ID 的 `PromptConfigurationError` 并保留原异常。

`PromptLoader._resolve` 文件：`python-agent/app/common/prompt_loader.py:42-46`

1. 第 42 行：定义 Prompt 路径解析函数。
2. 第 43 行：拼接根目录与 Prompt ID，再调用 `resolve()` 得到规范绝对路径。
3. 第 44 行：检查 Prompt 根目录的规范路径是否仍是目标路径的父目录。
4. 第 45 行：越界时抛 `PromptConfigurationError`，阻止 `../` 目录穿越。
5. 第 46 行：返回安全路径。

### 3.17 `AsyncRetryExecutor.execute`、`_is_retryable` 与 `_backoff_seconds`

`AsyncRetryExecutor.execute` 文件：`python-agent/app/infrastructure/reliability/retry.py:23-40`

`AsyncRetryExecutor._is_retryable` 文件：`python-agent/app/infrastructure/reliability/retry.py:42-43`

`AsyncRetryExecutor._backoff_seconds` 文件：`python-agent/app/infrastructure/reliability/retry.py:45-50`

1. 第 23 行：定义统一异步重试函数，参数 operation 必须能为每次尝试创建新协程。
2. 第 24 行：尝试编号从 1 遍历到策略最大尝试次数。
3. 第 25 行：进入当前尝试异常保护。
4. 第 26 行：注释说明单次上限覆盖所有经此执行器的模型和外部 Agent 调用。
5. 第 27 行：注释说明 `wait_for` 会取消超时协程，避免后台无限悬挂。
6. 第 28 行：调用并等待 `asyncio.wait_for`。
7. 第 29 行：调用 operation 创建当前模型协程，并传入策略单次超时。
8. 第 30 行：结束等待；成功时直接返回模型原始响应。
9. 第 31 行：捕获当前尝试产生的任意普通异常。
10. 第 32 行：调用项目函数 `_is_retryable`；不可重试或已到最后一次时进入终止分支。
11. 第 33 行：再次判断是否属于可重试异常，以区分次数耗尽和不可重试。
12. 第 34 行：次数耗尽时开始构造 `AgentDependencyError`。
13. 第 35 行：错误说明模型或外部 Agent 在有限重试后仍不可用。
14. 第 36 行：把错误标记为可重试，允许更高层决定是否重新运行任务。
15. 第 37 行：结束异常构造，并链接最后一次依赖错误后抛出。
16. 第 38 行：原异常不可重试时保持类型和堆栈原样抛出。
17. 第 39 行：尚可重试时调用项目函数 `_backoff_seconds(attempt)` 并异步休眠。
18. 第 40 行：循环理论上不可自然结束；若发生则抛 `AssertionError`。
19. 第 42 行：定义项目函数 `_is_retryable`。
20. 第 43 行：以异常准确类名是否存在于策略集合中判断可重试性。
21. 第 45 行：定义指数退避秒数计算函数。
22. 第 46 行：开始计算最大退避与指数值的较小值。
23. 第 47 行：第一个候选值是策略最大退避毫秒数。
24. 第 48 行：第二个候选值是初始退避乘 `2 ** (attempt - 1)`。
25. 第 49 行：结束 `min` 计算。
26. 第 50 行：把毫秒除以 1000 转成秒并返回。


### 3.18 URL 安全函数

`_is_public_host` 文件：`python-agent/app/tools/web_reader.py:190-200`。

1. 第 190 行：定义主机公网性检查函数，接收已解析出的 hostname。
2. 第 191 行：进入 DNS 解析异常保护。
3. 第 192 行：调用 `socket.getaddrinfo` 解析主机的全部流式套接字地址，而不是只信任一个 DNS 结果。
4. 第 193 行：捕获域名无法解析时产生的 `socket.gaierror`。
5. 第 194 行：构造不可重试 `AgentDependencyError` 并保留 DNS 根因；构造会执行项目函数 `ApplicationException.__init__`。
6. 第 195 行：遍历 `getaddrinfo` 返回的每一个地址，防止多 A/AAAA 记录中夹带内网地址。
7. 第 196 行：取套接字地址元组中的 IP 字符串并转换为 `ipaddress` 地址对象。
8. 第 197 行：开始检查地址是否为私网、回环或链路本地地址。
9. 第 198 行：继续检查组播、保留地址和未指定地址；任一属性为真都不属于允许抓取的公网目标。
10. 第 199 行：发现任意危险地址立即返回 `False`。
11. 第 200 行：全部解析结果均通过检查后返回 `True`。

`validate_public_url` 文件：`python-agent/app/tools/web_reader.py:203-215`。

1. 第 203 行：定义公开 URL 校验函数。
2. 第 204 行：去除输入首尾空白，再用 `urlparse` 拆分 URL。
3. 第 205 行：要求 scheme 只能是 `http` 或 `https`，且解析结果必须含 hostname。
4. 第 206 行：条件不满足时抛不可重试 `AgentDependencyError`，拒绝文件协议、其他协议和相对地址。
5. 第 207 行：进入端口读取保护，因为 `parsed.port` 对非法端口文本会抛 `ValueError`。
6. 第 208 行：读取规范化端口。
7. 第 209 行：捕获非法端口值。
8. 第 210 行：把端口解析错误转换成不可重试项目异常并链接原异常。
9. 第 211 行：同时检查 URL 是否含用户名、密码，或使用了非默认的 80/443 端口。
10. 第 212 行：任一条件命中时抛不可重试项目异常，缩小 SSRF 和代理绕过面。
11. 第 213 行：调用项目函数 `_is_public_host(parsed.hostname)` 检查 DNS 解析出的所有地址。
12. 第 214 行：主机不完全公开时抛不可重试项目异常。
13. 第 215 行：所有检查通过后返回解析器规范化的完整 URL 字符串。

### 3.19 `fetch_public_article`

文件：`python-agent/app/tools/web_reader.py:218-270`。

```python
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
```

1. 第 218 行：定义异步单页抓取函数，接收原始 URL，成功时返回 `WebDocument`。
2. 第 219 行：调用项目函数 `validate_public_url(url)` 校验入口地址，并保存规范化结果为 `current_url`。
3. 第 220 行：初始化 `last_error`，用于在全部网络重试失败后链接最后一次根因。
4. 第 221 行：创建并进入 `httpx.AsyncClient` 异步上下文，退出时自动释放连接池。
5. 第 222 行：把单次客户端超时设为常量 `TIMEOUT_SECONDS`，当前值为 120 秒。
6. 第 223 行：关闭 httpx 自动重定向，保证每个跳转目标都重新经过项目 SSRF 校验。
7. 第 224 行：设置固定 `User-Agent`，便于目标站点和日志识别抓取来源。
8. 第 225 行：结束客户端构造并把实例命名为 `client`。
9. 第 226 行：从 `0` 到 `MAX_RETRIES` 遍历尝试序号；当前配置表示首次请求加两次重试。
10. 第 227 行：进入当前网络尝试的异常保护。
11. 第 228 行：最多处理 `MAX_REDIRECTS + 1` 个响应，当前最多允许三次跳转。
12. 第 229 行：用异步客户端向当前 URL 发起 GET 请求并等待完整响应。
13. 第 230 行：判断响应是否属于重定向。
14. 第 231 行：从响应头读取 `Location`。
15. 第 232 行：检查重定向响应是否缺少目标地址。
16. 第 233 行：缺失时抛不可重试 `AgentDependencyError`，避免对含义不完整的跳转继续处理。
17. 第 234 行：用 `urljoin` 合成绝对目标，再次调用项目函数 `validate_public_url`；重定向到内网、非标准端口或非法协议会在请求前被拒绝。
18. 第 235 行：继续重定向循环，用新 URL 发起下一次请求。
19. 第 236 行：非重定向响应调用 `raise_for_status()`；4xx/5xx 转为 `HTTPStatusError`，进入可重试网络异常分支。
20. 第 237 行：读取 `Content-Type`，去掉分号后的 charset，并转成小写媒体类型。
21. 第 238 行：只允许 `text/html` 或 `application/xhtml+xml`。
22. 第 239 行：其他类型抛不可重试项目异常，防止把二进制文件当网页解析。
23. 第 240 行：读取原始响应字节。
24. 第 241 行：比较响应体长度与 `MAX_BYTES`，当前上限为 5MB。
25. 第 242 行：超限时抛不可重试项目异常，限制内存和解析成本。
26. 第 243 行：创建项目自定义 `_ArticleParser`；构造时调用其 `__init__`。
27. 第 244 行：按响应声明编码或 UTF-8 解码字节，非法字符以替代字符处理，再调用 HTMLParser 的 `feed`；解析过程中框架反复回调项目函数 `handle_starttag`、`handle_endtag` 和 `handle_data`。
28. 第 245 行：调用项目函数 `_ArticleParser.close` 完成解析并提交最后一个正文块。
29. 第 246 行：拼接标题片段并去空白；页面没有标题时使用最终 URL。
30. 第 247 行：过滤长度不超过一个字符的正文块，减少噪声。
31. 第 248 行：用一级标题和双换行分隔的正文块构造 Markdown。
32. 第 249 行：截断到 `MAX_MARKDOWN_CHARS`，当前为 180000 字符，并清理首尾空白。
33. 第 250 行：检查清洗后 Markdown 是否少于 80 字符。
34. 第 251 行：正文不足时抛不可重试项目异常，避免返回无价值预览。
35. 第 252 行：开始构造并返回不可变 `WebDocument`。
36. 第 253 行：记录经过全部重定向校验后的最终 URL。
37. 第 254 行：标题最多保留 500 个字符。
38. 第 255 行：记录 UTC 当前时间并输出 ISO 8601 字符串。
39. 第 256 行：把 Markdown 编码为 UTF-8，计算 SHA-256 十六进制摘要用于去重和溯源。
40. 第 257 行：保存清洗后的 Markdown 正文。
41. 第 258 行：保存规范化媒体类型。
42. 第 259 行：把解析器收集的链接列表转成不可变元组。
43. 第 260 行：保存原始响应字节数。
44. 第 261 行：结束 `WebDocument` 构造并立即返回，成功路径不会继续重试。
45. 第 262 行：重定向循环自然耗尽时抛不可重试项目异常，表示跳转次数超限。
46. 第 263 行：捕获 `AgentDependencyError`；这类异常表示安全或内容规则已给出明确结果。
47. 第 264 行：原样重新抛出项目异常，不对 SSRF 拒绝、类型不符、超限或正文不足进行网络重试。
48. 第 265 行：只捕获 httpx 超时、网络错误和 HTTP 状态错误作为可重试网络失败。
49. 第 266 行：保存本次异常为 `last_error`。
50. 第 267 行：检查当前尝试是否已经达到最大重试次数。
51. 第 268 行：次数耗尽时跳出尝试循环。
52. 第 269 行：仍可重试时按 `0.5 * (attempt + 1)` 秒异步退避，当前依次等待 0.5 秒和 1 秒。
53. 第 270 行：全部尝试失败后抛出可重试 `AgentDependencyError`，并用 `from last_error` 保留最后一次网络根因。

### 3.20 `_ArticleParser`

`_ArticleParser.__init__` 文件：`python-agent/app/tools/web_reader.py:113-122`。

1. 第 113 行：定义解析器构造函数。
2. 第 114 行：调用 `HTMLParser` 父类构造函数，并启用字符引用自动转换。
3. 第 115 行：创建页面标题文本片段列表。
4. 第 116 行：创建已经完成清洗的正文块列表。
5. 第 117 行：把当前 title 嵌套深度初始化为 `0`。
6. 第 118 行：把需要跳过的标签嵌套深度初始化为 `0`。
7. 第 119 行：创建当前正文块的文本缓冲。
8. 第 120 行：创建已打开标签栈。
9. 第 121 行：创建页面原始链接列表。
10. 第 122 行：创建逐标签跳过标志栈，使结束标签能恢复对应状态。

`_ArticleParser.handle_starttag` 文件：`python-agent/app/tools/web_reader.py:124-147`。

1. 第 124 行：定义 HTMLParser 遇到开始标签时自动回调的项目函数。
2. 第 125 行：把标签名转为小写，统一后续集合比较。
3. 第 126 行：把属性元组列表转成字典，便于读取 href、id、class 和 role。
4. 第 127 行：仅当标签为链接且当前不在跳过区域时收集链接。
5. 第 128 行：读取 `href` 属性。
6. 第 129 行：检查链接值非空。
7. 第 130 行：把原始链接加入 `links`，后续抓取函数再结合页面 URL 规范化。
8. 第 131 行：把当前标签压入标签栈。
9. 第 132 行：合并 id、class、role，去除空值后转为大小写无关文本，用于识别样板区域。
10. 第 133 行：标签在 `SKIP` 集合中，或属性提示包含广告、菜单、侧栏等词时，把当前标签标为跳过。
11. 第 134 行：把当前标签的跳过决定压入 `_skip_stack`，供结束标签配对弹出。
12. 第 135 行：判断当前标签自身是否需要跳过。
13. 第 136 行：需要跳过时增加跳过嵌套深度。
14. 第 137 行：立即返回，不处理该标签的正文块语义。
15. 第 138 行：若外层已经处于跳过区域，也停止处理当前普通子标签。
16. 第 139 行：结束当前开始标签回调。
17. 第 140 行：判断当前标签是否为页面 `title`。
18. 第 141 行：进入 title 时增加标题深度，使后续文本同时进入 `title_parts`。
19. 第 142 行：检查当前标签是否属于正文块边界集合。
20. 第 143 行：调用项目函数 `_flush` 提交此前累积的正文，避免跨块标签拼接。
21. 第 144 行：识别 h1 到 h9 形式的单数字标题标签。
22. 第 145 行：按标题级别生成最多六个 `#` 的 Markdown 前缀并加入当前块。
23. 第 146 行：识别列表项标签。
24. 第 147 行：为列表项加入 Markdown `- ` 前缀。

`_ArticleParser.handle_endtag` 文件：`python-agent/app/tools/web_reader.py:149-166`。

1. 第 149 行：定义 HTMLParser 遇到结束标签时自动回调的项目函数。
2. 第 150 行：把结束标签名转为小写。
3. 第 151 行：从跳过栈弹出当前标签的标志；栈为空时安全回退为 `False`。
4. 第 152 行：判断当前标签自身是跳过根节点且跳过深度仍大于零。
5. 第 153 行：离开跳过根节点时减少跳过深度。
6. 第 154 行：检查标签栈非空。
7. 第 155 行：弹出对应打开标签。
8. 第 156 行：结束回调，跳过区域内容不会进入正文。
9. 第 157 行：若仍处于更外层的跳过区域，则不处理正文边界。
10. 第 158 行：检查标签栈非空。
11. 第 159 行：弹出当前子标签。
12. 第 160 行：结束回调。
13. 第 161 行：检查是否离开 title 且标题深度为正。
14. 第 162 行：减少标题深度。
15. 第 163 行：检查结束标签是否属于正文块边界集合。
16. 第 164 行：调用项目函数 `_flush` 提交当前正文块。
17. 第 165 行：检查标签栈是否仍有元素。
18. 第 166 行：弹出当前标签，完成栈状态维护。

`_ArticleParser.handle_data` 文件：`python-agent/app/tools/web_reader.py:168-176`。

1. 第 168 行：定义 HTMLParser 遇到文本节点时自动回调的项目函数。
2. 第 169 行：检查当前是否位于脚本、样式、导航或样板区域。
3. 第 170 行：跳过区域内的文本立即丢弃。
4. 第 171 行：把连续空白折叠为单个空格并去除首尾空白。
5. 第 172 行：检查清理后的文本是否为空。
6. 第 173 行：空文本不进入任何缓冲。
7. 第 174 行：判断当前文本是否位于 title 标签内。
8. 第 175 行：位于 title 时把文本加入标题片段。
9. 第 176 行：所有有效文本都加入当前正文块缓冲。

`_ArticleParser.close` 文件：`python-agent/app/tools/web_reader.py:178-180`。

1. 第 178 行：覆盖解析器关闭函数。
2. 第 179 行：调用父类 `close()` 处理缓冲中的未完成 HTML 数据。
3. 第 180 行：调用项目函数 `_flush`，保证最后一个正文块不会因缺少后续边界标签而丢失。

`_ArticleParser._flush` 文件：`python-agent/app/tools/web_reader.py:182-187`。

1. 第 182 行：定义当前正文块提交函数。
2. 第 183 行：只在 `_block` 缓冲非空时处理。
3. 第 184 行：用空格连接块内文本并去除首尾空白。
4. 第 185 行：检查连接结果非空。
5. 第 186 行：把非空文本追加到最终 `blocks`。
6. 第 187 行：无论连接结果是否为空都清空当前块，为下一块重新收集文本。

### 3.21 `AgentResponse.validate_code_category`

文件：`python-agent/app/common/contracts.py:177-182`

1. 第 177 行：注册 code 字段校验器。
2. 第 178 行：声明类方法。
3. 第 179 行：定义校验。
4. 第 180 行：要求首位类别属于 1~5。
5. 第 181 行：不满足抛 ValueError。
6. 第 182 行：返回合法 code。

### 3.22 FastAPI 异常与统一错误响应

`request_validation_error` 文件：`python-agent/app/api/application.py:292-299`

1. 第 292 行：注册请求校验错误处理器。
2. 第 293 行：定义异步函数。
3. 第 294 行：读取 error.body。
4. 第 295 行：body 为映射时作为上下文。
5. 第 296 行：调用 _error_json_response。
6. 第 297 行：转换 RequestError 并用 HTTP 400。
7. 第 298 行：传入上下文。
8. 第 299 行：返回。

`application_error` 文件：`python-agent/app/api/application.py:301-304`

1. 第 301 行：注册 ApplicationException 处理器。
2. 第 302 行：定义异步函数。
3. 第 303 行：调用 _mark_failed_interview_progress；本网页抓取路径立即返回。
4. 第 304 行：调用 _error_json_response，HTTP 200。

`unexpected_error` 文件：`python-agent/app/api/application.py:306-310`

1. 第 306 行：注册其他 Exception。
2. 第 307 行：定义异步函数。
3. 第 308 行：记录堆栈。
4. 第 309 行：调用 _mark_failed_interview_progress。
5. 第 310 行：调用 _error_json_response，HTTP 500。

`_mark_failed_interview_progress` 文件：`python-agent/app/api/application.py:323-331`

1. 第 323 行：定义失败进度补偿。
2. 第 324 行：检查路径不是 respond。
3. 第 325 行：本网页抓取接口立即返回。
4. 第 326 行：仅 respond 路径恢复请求上下文。
5. 第 327 行：仅 respond 路径清洗 sessionId。
6. 第 328 行：仅 respond 路径读取服务。
7. 第 329 行：兼容读取失败标记方法。
8. 第 330 行：检查 sessionId 和方法。
9. 第 331 行：满足时标记；本接口不执行。

`_request_context` 文件：`python-agent/app/api/application.py:379-388`

1. 第 379 行：定义上下文恢复。
2. 第 380 行：读取已记住上下文。
3. 第 381 行：检查映射。
4. 第 382 行：是映射直接返回。
5. 第 383 行：进入 body 解析保护。
6. 第 384 行：读取 body。
7. 第 385 行：非空解析 JSON。
8. 第 386 行：根节点字典才返回。
9. 第 387 行：捕获 JSON、Unicode 和运行时错误。
10. 第 388 行：失败返回空字典。

`_error_response` 文件：`python-agent/app/api/application.py:397-411`

1. 第 397 行：定义 `_error_response` 异步函数并开始多行签名。
2. 第 398 行：接收当前 `Request`、原始异常和可选协议上下文映射。
3. 第 399 行：声明返回类型为 `AgentResponse` 并结束函数签名。
4. 第 400 行：显式上下文非空时直接使用；否则调用项目函数 `_request_context(request)` 从请求状态或请求体恢复上下文。
5. 第 401 行：读取 `sessionStatus` 并调用项目函数 `_session_status_or_failed` 转换为协议枚举。
6. 第 402 行：读取未经信任的 `stateVersion`，下一步再检查其类型和范围。
7. 第 403 行：开始构造统一失败 `AgentResponse`；构造期间会触发项目校验函数 `validate_code_category`。
8. 第 404 行：读取 `apiVersion` 并调用项目函数 `_string_or_none` 清洗。
9. 第 405 行：读取 `requestId` 并调用 `_string_or_none` 清洗。
10. 第 406 行：清洗 `runId`，同时调用项目函数 `ExceptionHandler.to_code(error)` 生成协议错误码。
11. 第 407 行：把运行状态固定为 `FAILED`，并清洗 `userId`。
12. 第 408 行：清洗 `sessionId`，并写入第 401 行解析出的会话状态。
13. 第 409 行：仅接受非负整数版本；缺失、类型不符或为负数时回退为 `0`。
14. 第 410 行：失败响应不返回答案，把当前阶段设为 `FAILED`，并调用项目函数 `ExceptionHandler.to_error_info(error)` 构造错误详情。
15. 第 411 行：结束 `AgentResponse` 构造并返回统一失败响应。

`_string_or_none` 文件：`python-agent/app/api/application.py:414-415`

1. 第 414 行：定义字符串清洗。
2. 第 415 行：非空字符串返回，否则 None。

`_session_status_or_failed` 文件：`python-agent/app/api/application.py:418-423`

1. 第 418 行：定义状态转换。
2. 第 419 行：说明运行失败不能误改现有会话。
3. 第 420 行：进入保护。
4. 第 421 行：构造 SessionStatus。
5. 第 422 行：捕获类型和值错误。
6. 第 423 行：失败回退 FAILED。

`ExceptionHandler.to_code` 文件：`python-agent/app/common/exceptions.py:139-146`

1. 第 139 行：声明类方法。
2. 第 140 行：定义 code 转换。
3. 第 141 行：识别项目异常。
4. 第 142 行：返回项目 code。
5. 第 143 行：遍历内置映射。
6. 第 144 行：按类型匹配。
7. 第 145 行：返回映射 code。
8. 第 146 行：未知返回 500。

`ExceptionHandler.to_error_info` 文件：`python-agent/app/common/exceptions.py:116-137`

1. 第 116 行：声明类方法。
2. 第 117 行：定义 ErrorInfo 转换。
3. 第 118 行：识别项目异常。
4. 第 119 行：开始构造。
5. 第 120 行：写 errorType。
6. 第 121 行：写 message。
7. 第 122 行：写 retryable。
8. 第 123 行：返回。
9. 第 125 行：遍历内置映射。
10. 第 126 行：按类型匹配。
11. 第 127 行：内置异常类型命中后开始构造并返回 `ErrorInfo`。
12. 第 128 行：把映射中的 `error_name` 写入外部错误类型。
13. 第 129 行：优先使用原异常字符串；消息为空时使用 `error_name`，保证响应始终有可读消息。
14. 第 130 行：把内置映射声明的 `retryable` 布尔值写入错误详情。
15. 第 131 行：结束并返回内置异常对应的 `ErrorInfo`。
16. 第 133 行：没有任何项目异常或内置异常匹配时，开始构造兜底 `ErrorInfo`。
17. 第 134 行：把兜底错误类型固定为 `INTERNAL_ERROR`。
18. 第 135 行：使用不暴露内部异常细节的固定外部消息。
19. 第 136 行：把未知错误标记为不可重试。
20. 第 137 行：结束并返回兜底 `ErrorInfo`。

`_error_json_response` 文件：`python-agent/app/api/application.py:447-455`

1. 第 447 行：定义 `_error_json_response` 异步函数并开始多行签名。
2. 第 448 行：接收当前 FastAPI `Request`。
3. 第 449 行：接收需要转换的原始异常。
4. 第 450 行：使用 `*` 把后续参数限定为仅可按关键字传入，避免状态码和上下文位置混淆。
5. 第 451 行：接收最终 HTTP 状态码；项目异常可保持协议约定的 HTTP 200，未知异常使用 HTTP 500。
6. 第 452 行：接收可选请求上下文；请求校验失败处理器会尽量直接传入原始请求体映射。
7. 第 453 行：声明返回 `JSONResponse` 并结束函数签名。
8. 第 454 行：调用项目函数 `_error_response(request, error, context)` 构造协议层 `AgentResponse`。
9. 第 455 行：调用项目函数 `AgentResponse.to_json_dict()` 转成别名化 JSON 字典，再以指定 HTTP 状态码返回 `JSONResponse`。

`AgentResponse.to_json_dict` 文件：`python-agent/app/common/contracts.py:184-185`

1. 第 184 行：定义 JSON 导出。
2. 第 185 行：以 JSON 模式、别名并保留 null 导出。

`ApplicationException.__init__` 文件：`python-agent/app/common/exceptions.py:15-19`

该继承构造函数会在本接口创建 `RequestError`、`ModelConfigurationError`、`ReliabilityConfigurationError`、`PromptConfigurationError`、`ModelOutputError` 或 `AgentDependencyError` 时执行。

1. 第 15 行：定义项目异常基类构造函数；接收对外消息，以及可选的实例级 `retryable` 覆盖值。
2. 第 16 行：调用 Python `Exception` 基类构造函数，使标准异常参数、字符串表示和堆栈机制正常工作。
3. 第 17 行：把消息另存为 `self.message`，供 `ExceptionHandler.to_error_info` 直接读取。
4. 第 18 行：检查调用方是否显式提供了 `retryable`；`None` 表示沿用具体异常类的类属性。
5. 第 19 行：提供覆盖值时写入实例属性，例如重试执行器在耗尽次数后显式保留可重试语义。

## 4. 主流构建分析

主流生产级站点采集通常采用隔离式异步 Crawl Job：API 只创建任务并返回 taskId，专用 Worker 在受控网络命名空间中执行 DNS 固定、robots 策略、域名限速、内容去重和对象存储落盘，任务进度通过事件或状态接口返回。优点是长任务不会占用 API 连接，爬虫网络权限可与模型服务隔离，失败页面可分批重试并支持断点续跑；缺点是需要任务表、消息队列、工件存储、租约和清理策略，部署与可观测性成本明显增加。

本项目当前实现具有同域、深度、页数、访问次数、总字节、总字符和十分钟硬上限，并在每次网络请求前重新进行公网 URL 校验；对小规模、需要立即预览的技术资料采集是适配的。主要不足是抓取、模型规划和结果组装都占用一个 FastAPI 请求，进程退出后无法恢复，而且 DNS 校验与实际连接之间仍需要基础设施层进一步防御 DNS rebinding。

若本项目扩展到大量站点，建议增加 `web_crawl_jobs`、`web_crawl_pages` 与 outbox 事件：Java 创建任务，Python Worker 按租约领取；下载进程使用仅允许公网 80/443 的网络策略并固定已校验 IP，原始响应与 Markdown 写入对象存储，PostgreSQL 只保存状态和摘要。现有 `validate_public_url`、`fetch_public_article`、`WebCrawlPlanningAgent` 和确定性质量门槛可以直接作为 Worker 内部步骤复用；任务完成后再由用户确认是否把选中页面送入 RAG 索引。
