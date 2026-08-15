# POST /api/tools/web/fetch：抓取单个公共网页的完整函数调用链

## 1. 接口定义

接口接收公共 HTTP(S) URL，经 Java 调用 Python `/v1/tools/web/fetch`。Python 做 SSRF 防护、受限重定向/重试、HTML 内容类型和大小检查，再使用项目 HTMLParser 提取正文、标题、链接并返回 Markdown 与来源信息。接口只返回预览，不自动写知识库或生成向量。

| 项目 | 内容 |
| --- | --- |
| 方法/路径 | POST `/api/tools/web/fetch` |
| 请求体 | `{url}` |
| Java→Python | POST `/v1/tools/web/fetch` |
| 限制 | 公网 80/443、最多 3 次重定向、5MB、120秒、2次重试 |
| 返回 | URL、标题、抓取时间、hash、Markdown、链接等 |

## 2. 函数调用链

~~~text
KnowledgeBaseUploadPage.handleReadWeb → knowledgeBaseApi.fetchWebPage
 -> request.post → Axios/Filter → WebToolController.fetch
 -> WebToolService.fetch → UserIdentityResolver.require
 -> HttpPythonAgentClient.fetchWeb → AgentCallExecutor.execute → post/validateRequest
 -> Python fetch_web → _remember_request_context → fetch_public_article
    -> validate_public_url → _is_public_host
    -> httpx GET/重定向 → _ArticleParser.handle_starttag/handle_endtag/handle_data/_flush/close
    -> WebDocument → WebDocument.as_dict
 -> AgentResponse → WebToolService.output → ApiResult.success
 -> 前端 setWebPreview
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `KnowledgeBaseUploadPage.handleReadWeb`

文件：`frontend/src/pages/KnowledgeBaseUploadPage.tsx:35-48`。

1. 第 36 行 URL trim 后为空直接 return；第 37-39 行进入加载、清错误/旧预览。
2. 第 41 行 await fetchWebPage(url.trim())；第 42 行 setWebPreview。
3. 第 43-46 行错误写 UI，finally 清 webLoading；第 48 行结束。

#### 3.1.2 `knowledgeBaseApi.fetchWebPage`、`request.post`

文件：`frontend/src/api/knowledgebase.ts:91-93`；`api/request.ts:47-73、123-163`。

1. 第 91 行定义函数；第 92 行 POST `/api/tools/web/fetch` 与 `{url}`；第 93 行结束。
2. request.post 第 161-163 行调用 Axios；createClientId/currentUserId 第 47-58 行生成 requestId/owner。
3. 请求拦截器第 64-73 行写两个头；响应拦截器第 123-155 行解包 data 或转 ApiRequestError。

### 3.2 Java 函数

#### 3.2.1 `WebToolController.fetch`

文件：`java-backend/src/main/java/com/interviewguide/web/controller/WebToolController.java:35-40`。

1. 第 35 行映射 `/fetch`；第 36-38 行绑定 @Valid FetchRequest 和用户头。
2. 第 39 行调用 webToolService.fetch(userId,body.url()) 并 ApiResult.success；第 40 行结束。

#### 3.2.2 `WebToolService.fetch` 与 `output`

文件：`java-backend/src/main/java/com/interviewguide/web/service/WebToolService.java:33-38、56-62`。

1. fetch 第 34-36 行构造 AgentWebFetchRequest：API 版本、两个 UUID、identity.require owner、固定 session/operation、URL和时间。
2. 第 37 行调用 output；第 38 行结束。
3. output 第 57 行要求 response 非空、code 为 1xx 且 output 非空；第 58-60 行失败时取结构化错误或 fallback 并抛 WEB_FETCH_FAILED；第 61 行返回 output。

#### 3.2.3 `HttpPythonAgentClient.fetchWeb` 与重试

文件：`HttpPythonAgentClient.java:50、65-96`；`AgentCallExecutor.java:22-43`。

1. fetchWeb 第 50 行把 `/v1/tools/web/fetch` post lambda 交 execute。
2. post 第 66 行 Bean Validation；第 68-79 行发送、解析、拒绝空 body并区分结构化 HTTP/网络异常。
3. execute 对可重试 PythonAgentException 按 maxAttempts/backoff 重试；中断由 sleepBeforeRetry 恢复标志后抛出。

### 3.3 Python 路由和 URL 安全函数

#### 3.3.1 `fetch_web`

文件：`python-agent/app/api/application.py:252-268`。

1. 第 260 行 _remember_request_context；第 261 行 await fetch_public_article(payload.url)。
2. 第 262-268 行构造 code=100 AgentResponse；answer=标题，output=document.as_dict。

#### 3.3.2 `validate_public_url` 与 `_is_public_host`

文件：`python-agent/app/tools/web_reader.py:190-215`。

1. _is_public_host 第 192 行 DNS getaddrinfo；解析失败第 193-194 行抛不可重试依赖错误。
2. 第 195-200 行检查每个 IP；private、loopback、link-local、multicast、reserved、unspecified 任一命中即 false。
3. validate_public_url 第 204-206 行只允许 http/https 且必须有 hostname。
4. 第 207-212 行解析端口，拒绝凭证和非 80/443；第 213-214 行要求公网主机；第 215 行返回规范 URL。每次重定向都会重新执行，防止跳转到内网。

### 3.4 Python 抓取和解析函数

#### 3.4.1 `fetch_public_article`

文件：`web_reader.py:218-270`。

1. 第 219 行先 validate；第 221-225 行创建无自动重定向、120 秒、固定 User-Agent 的 AsyncClient。
2. 第 226-228 行外层最多 3 次网络尝试、内层最多 4 个响应（原始+3重定向）。
3. 第 229-235 行 GET；重定向必须有 location，并通过 urljoin 后再次 validate。
4. 第 236-242 行要求成功状态、HTML/XHTML、body≤5MB。
5. 第 243-245 行创建 _ArticleParser、feed 解码 HTML、close flush。
6. 第 246-251 行生成标题/正文块/Markdown，截断 180000 字符并要求至少 80 字符。
7. 第 252-261 行构造 WebDocument：URL、500 字符标题、UTC 时间、SHA-256、Markdown、类型、链接、原始大小。
8. 第 262 行重定向过多错误；第 263-264 行安全/内容错误不重试；第 265-269 行只对网络/超时/HTTP 状态有限退避重试；第 270 行耗尽抛可重试错误。

#### 3.4.2 `_ArticleParser` 项目函数

文件：`web_reader.py:99-187`。

1. 构造函数第 113-122 行初始化标题、正文块、标签/跳过栈和链接。
2. handle_starttag 第 124-147 行小写 tag、收集未跳过的 href，以标签/id/class/role 判定脚本/导航/广告等跳过区域；标题增加深度；块标签先 _flush；标题/列表加入 Markdown 前缀。
3. handle_endtag 第 149-166 行弹 skip 状态、维护深度/栈；块结束时 _flush。
4. handle_data 第 168-176 行跳过非正文，压缩空白；标题数据写 title_parts，其他文本写当前 block。
5. close 第 178-180 行调用父类 close 并 flush；_flush 第 182-187 行合并当前 block、非空时加入 blocks并清数组。

#### 3.4.3 `WebDocument.as_dict`

文件：`web_reader.py:48-59`。

1. 第 49-58 行把 snake_case dataclass 字段映射为 Java/前端需要的 camelCase 字典。
2. characterCount 由 len(markdown)计算；links 转 list；第 59 行结束。

## 4. 审核结论

1. 已覆盖前端预览、Java DTO/HTTP 客户端、Python SSRF 防护、受限下载、HTML解析和输出映射。
2. 每个实际可达项目函数均注明文件/行号并逐句解释；抓取结果只预览，不自动进入知识库索引。
