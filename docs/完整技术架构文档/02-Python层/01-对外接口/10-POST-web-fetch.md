# POST /v1/tools/web/fetch：抓取单个公开网页

## 1. 接口定义

此 Python 内部接口接收 Java 发送的 `AgentWebFetchRequest`，对 URL 做 SSRF 防护后下载一个公开 HTML 页面，提取可读正文并返回 Markdown 与溯源信息。页面内容不会执行，也不会直接写入向量库；上层需要获得用户确认后才可导入知识库。

| 项目 | 内容 |
| --- | --- |
| HTTP 方法/路径 | `POST /v1/tools/web/fetch` |
| 路由函数 | `fetch_web`，`python-agent/app/api/application.py:255-271` |
| 请求 | `AgentWebFetchRequest`：协议字段、用户/会话/追踪字段、`url` |
| 成功 | AgentResponse，`code=100`、output 为网页 Markdown/哈希/来源字段 |
| 限制 | 公开 HTTP(S)、端口 80/443、3 次重定向、5MB、120 秒、最多 2 次网络重试 |

## 2. 函数调用链

```text
FastAPI → fetch_web → _remember_request_context → fetch_public_article
 → validate_public_url → _is_public_host
 → httpx.AsyncClient.get（第三方）
 → _ArticleParser.__init__/handle_starttag/handle_endtag/handle_data/close/_flush
 → WebDocument.as_dict → AgentResponse → AgentResponse.validate_code_category

每次入口 URL 与重定向 URL：validate_public_url → _is_public_host
每次构造 AgentDependencyError：ApplicationException.__init__

请求校验失败：request_validation_error → ApplicationException.__init__
 → _error_json_response → _error_response → _request_context
 → _session_status_or_failed/_string_or_none
 → ExceptionHandler.to_code/ExceptionHandler.to_error_info
 → AgentResponse.validate_code_category → AgentResponse.to_json_dict

项目异常：application_error → _mark_failed_interview_progress
 → _error_json_response → _error_response → _request_context
 → _session_status_or_failed/_string_or_none
 → ExceptionHandler.to_code/ExceptionHandler.to_error_info
 → AgentResponse.validate_code_category → AgentResponse.to_json_dict

未预期异常：unexpected_error → _mark_failed_interview_progress
 → _error_json_response → _error_response → _request_context
 → _session_status_or_failed/_string_or_none
 → ExceptionHandler.to_code/ExceptionHandler.to_error_info
 → AgentResponse.validate_code_category → AgentResponse.to_json_dict
```

## 3. 函数解析

### 3.1 `fetch_web`

文件：`python-agent/app/api/application.py:255-271`。

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

1. 第 255 行：注册 `POST /v1/tools/web/fetch`，并把 `AgentResponse` 指定为响应模型。
2. 第 256 行：定义异步路由，接收校验后的 `AgentWebFetchRequest` 与当前 FastAPI `Request`。
3. 第 257 行：文档字符串说明本函数只抓取并提取一个明确指定的公开 HTML 页面。
4. 第 258 行：文档字符串中的空行分隔概要和安全约束。
5. 第 259 行：声明抓取到的页面内容不会被执行，也不会作为系统指令输入。
6. 第 260 行：说明调用方得到的是 Markdown 与来源信息。
7. 第 261 行：说明上层必须先获得明确确认，才能把预览结果写入索引。
8. 第 262 行：结束路由文档字符串。
9. 第 263 行：调用项目函数 `_remember_request_context`，保存协议上下文供异常响应恢复。
10. 第 264 行：调用并等待项目函数 `fetch_public_article(payload.url)`，完成 URL 安全校验、下载与正文提取。
11. 第 265 行：开始构造成功 `AgentResponse`；构造时触发项目校验器 `validate_code_category`。
12. 第 266 行：复制 `apiVersion` 和 `requestId`。
13. 第 267 行：复制 `runId`，设置成功码 `100` 和运行状态 `COMPLETED`。
14. 第 268 行：复制 `userId` 和 `sessionId`。
15. 第 269 行：网页预览不推进面试状态，因此会话状态固定为 `ACTIVE`，版本为 `0`。
16. 第 270 行：把网页标题作为 `answer`，调用项目函数 `WebDocument.as_dict()` 生成 `output`，并把 `error` 设为 `None`。
17. 第 271 行：结束并返回响应。

### 3.2 `_remember_request_context`

文件：`python-agent/app/api/application.py:391-394`。

1. 第 391 行：定义请求上下文记忆函数，接收当前请求与任意 payload 对象。
2. 第 392 行：使用 `getattr` 读取可选 `model_dump` 属性；属性不存在时返回 `None`。
3. 第 393 行：用 `callable` 确认读取结果确实可调用。
4. 第 394 行：按字段别名和 JSON 模式转储 Pydantic 请求模型，并写入 `request.state.agent_context`。

### 3.3 URL 安全函数

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

### 3.4 `fetch_public_article`

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

### 3.5 `_ArticleParser` 与 `WebDocument.as_dict`

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

`WebDocument.as_dict` 文件：`python-agent/app/tools/web_reader.py:48-59`。

1. 第 48 行：定义网页文档到协议字典的项目函数。
2. 第 49 行：开始构造并返回字典。
3. 第 50 行：输出最终 URL。
4. 第 51 行：输出页面标题。
5. 第 52 行：把 `fetched_at` 转为 camelCase 键 `fetchedAt`。
6. 第 53 行：把内容摘要输出为 `contentHash`。
7. 第 54 行：输出 Markdown 正文。
8. 第 55 行：把媒体类型输出为 `contentType`。
9. 第 56 行：动态计算 Markdown 字符数并输出为 `characterCount`。
10. 第 57 行：把不可变链接元组转换成 JSON 可序列化列表。
11. 第 58 行：把原始响应字节数输出为 `rawByteSize`。
12. 第 59 行：结束并返回字典。


### 3.6 `AgentResponse.validate_code_category`

文件：`python-agent/app/common/contracts.py:177-182`

1. 第 177 行：注册 code 字段校验器。
2. 第 178 行：声明类方法。
3. 第 179 行：定义校验。
4. 第 180 行：要求首位类别属于 1~5。
5. 第 181 行：不满足抛 ValueError。
6. 第 182 行：返回合法 code。

### 3.7 FastAPI 异常与统一错误响应

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

该继承构造函数会在本接口创建 `RequestError` 或 `AgentDependencyError` 时执行。

1. 第 15 行：定义项目异常基类构造函数；接收对外消息，以及可选的实例级 `retryable` 覆盖值。
2. 第 16 行：调用 Python `Exception` 基类构造函数，使标准异常参数、字符串表示和堆栈机制正常工作。
3. 第 17 行：把消息另存为 `self.message`，供 `ExceptionHandler.to_error_info` 直接读取。
4. 第 18 行：检查调用方是否显式提供了 `retryable`；`None` 表示沿用具体异常类的类属性。
5. 第 19 行：提供覆盖值时写入实例属性，例如重试执行器在耗尽次数后显式保留可重试语义。

## 4. 主流构建分析

更主流的网页读取架构会把下载放到隔离的 fetch worker（容器级网络策略、域名限速、对象存储）并用异步任务返回 taskId。优点是隔离风险、避免长请求占用 API Worker，并能扩展大页面处理；缺点是要维护任务状态、队列、存储和轮询。

本项目的单页预览有严格 URL、大小、超时和重试限制，需要立即反馈给用户，当前同步实现适合。若业务扩展，可保留 `fetch_public_article` 作为 Worker 核心，Java 创建任务并由队列驱动 Python Worker，结果写入对象存储，以 taskId 状态接口或 SSE 回传预览。
