# POST /api/tools/web/fetch：抓取单个网页的完整函数调用链逐行解析

## 1. 接口定义

该接口接收前端提供的公开 HTTP(S) 地址，Java 后端将请求封装为 `AgentWebFetchRequest`，通过 HTTP 适配器调用 Python 的 `/v1/tools/web/fetch`。Python 负责 SSRF 校验、有限重定向、HTML 下载、正文提取和 Markdown/来源信息生成；Java 只返回预览数据，用户确认后才通过知识库上传接口入库。

| 项目 | 实现 |
| --- | --- |
| 前端入口 | `KnowledgeBaseUploadPage.handleReadWeb`（`frontend/src/pages/KnowledgeBaseUploadPage.tsx:35-48`） |
| Java 路径 | `POST /api/tools/web/fetch`（`WebToolController.java:35-40`） |
| Java 请求 | `FetchRequest.url`：非空且最长 2048 字符（`WebToolController.java:69-70`） |
| Python 路径 | `POST /v1/tools/web/fetch`（`python-agent/app/api/application.py:255-271`） |
| 主要限制 | 仅 HTTP/HTTPS 公网主机、端口 80/443、最多 3 次重定向、响应体 5MB、正文 Markdown 180000 字符 |
| 返回字段 | `url`、`title`、`fetchedAt`、`contentHash`、`markdown`、`contentType`、`characterCount`、`links`、`rawByteSize` |

## 2. 函数调用链

```text
KnowledgeBaseUploadPage.handleReadWeb
 → knowledgeBaseApi.fetchWebPage
 → request.post
 → Axios request interceptor（currentUserId/createClientId）
 → WebToolController.fetch
 → WebToolService.fetch
 → UserIdentityResolver.require
 → HttpPythonAgentClient.fetchWeb
 → AgentCallExecutor.execute
 → HttpPythonAgentClient.post
 → HttpPythonAgentClient.validateRequest
 → Python fetch_web
 → _remember_request_context
 → fetch_public_article
 → validate_public_url
 → _is_public_host
 → _ArticleParser.__init__/handle_starttag/handle_endtag/handle_data/close/_flush
 → WebDocument.as_dict
 → AgentResponse
 → WebToolService.output
 → ApiResult.success
 → Axios response interceptor
 → setWebPreview
```

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `KnowledgeBaseUploadPage.handleReadWeb`

文件：`frontend/src/pages/KnowledgeBaseUploadPage.tsx:35-48`。

1. 第 35 行声明异步函数，允许等待 HTTP 请求。
2. 第 36 行对 `url` 去除首尾空白；为空时立即返回，避免向后端发送无效请求。
3. 第 37 行把 `webLoading` 设为 `true`，按钮进入“读取中”状态。
4. 第 38 行清空旧错误；第 39 行清空旧预览，防止新请求失败时继续展示旧页面。
5. 第 40 行开始 `try`；第 41 行再次 `trim` URL，并调用 `knowledgeBaseApi.fetchWebPage` 等待 Java 返回。
6. 第 42 行将返回对象写入 `webPreview`，React 重新渲染标题、来源、字符数和 Markdown。
7. 第 43-45 行捕获异常：若是 `Error` 使用其 message，否则使用“网页读取失败”；`finally` 保证第 46 行关闭加载状态。
8. 第 48 行结束函数。

#### 3.1.2 `knowledgeBaseApi.fetchWebPage`

文件：`frontend/src/api/knowledgebase.ts:91-93`。

1. 第 91 行声明带字符串 URL、返回 `Promise<WebFetchResult>` 的异步函数。
2. 第 92 行调用 `request.post`，路径为 `/api/tools/web/fetch`，请求体是 `{url}`；泛型让 TypeScript 按 `WebFetchResult` 检查返回字段。
3. 第 93 行返回 Promise，异常交给页面函数处理。

#### 3.1.3 `request.post` 与请求拦截器

文件：`frontend/src/api/request.ts:47-73、123-163`。

1. `createClientId` 第 47-50 行优先使用浏览器 `crypto.randomUUID`；不可用时用时间戳和随机数拼接客户端 ID。
2. `currentUserId` 第 52-58 行读取 localStorage；已有非空值直接复用，否则生成 ID、持久化后返回。请求拦截器第 64-73 行确保 headers 存在，设置 `X-User-Id` 和 `X-Request-Id`，最后返回 Axios 配置。
3. `request.post` 第 161-163 行调用 Axios `instance.post`，并通过 `.then` 取出响应体。
4. 响应成功拦截器第 123-135 行检查统一 `ApiResult`；业务 `code` 为 200 时剥离 `data`，否则调用 `parseApiError` 转成 `ApiRequestError`。
5. `isRecord` 第 75-77 行排除 null、数组并确认对象；`stringValue` 第 79-81 行只接受非空字符串。
6. `parseApiError` 第 83-98 行读取嵌套 error 或顶层 code/message，并填充 retryable、HTTP 状态、request/run/session/stage 字段。
7. 网络错误分支第 136-153 行：无 Axios 响应时由 `transportError`（第 110-121 行）区分超时和连接中断；有响应时 `decodeErrorData` 第 101-108 行解析 JSON Blob，再尝试 `parseApiError`，无法解析则按 5xx 或其他 HTTP 状态构造错误。

### 3.2 Java 函数

#### 3.2.1 `WebToolController.fetch`

文件：`java-backend/src/main/java/com/interviewguide/web/controller/WebToolController.java:35-40`。

1. 第 35 行 `@PostMapping("/fetch")` 与类级 `/api/tools/web` 拼成公开路径。
2. 第 36-38 行接收 JSON 请求体和可选 `X-User-Id`；`@Valid` 触发 `FetchRequest` 的 `@NotBlank`、`@Size(max=2048)` 校验。
3. 第 39 行读取 record 的 `url()`，调用服务层 `fetch`，再用 `ApiResult.success` 包装为统一成功响应。
4. 第 40 行结束控制器函数；控制器不直接访问 Python。

#### 3.2.2 `WebToolService.fetch`

文件：`java-backend/src/main/java/com/interviewguide/web/service/WebToolService.java:26-38`。

1. 构造函数第 26-31 行注入 Python 客户端、身份解析器和预览服务并保存到字段。
2. 第 33 行声明服务方法。
3. 第 34-36 行构造 `AgentWebFetchRequest`：固定 API 版本 `v1`；两个 UUID 分别作为 requestId/runId；`identity.require(userId)` 规范化/校验用户身份；设置 sessionId `web-tool`、operation `tool.web.fetch`、原始 URL 和当前时间。
4. 第 34 行调用 `pythonAgentClient.fetchWeb`，返回 `AgentResponse`。
5. 第 37 行把响应交给私有 `output`；第 38 行结束。

#### 3.2.3 `HttpPythonAgentClient.fetchWeb`、`AgentCallExecutor.execute`、`post`

文件：`HttpPythonAgentClient.java:35-51、65-96`；`AgentCallExecutor.java:16-43`。

1. `fetchWeb` 第 50 行把 Python 路径和请求封装成 lambda，交给 `callExecutor.execute`。
2. `execute` 构造函数第 16-20 行将配置的最大尝试次数至少限制为 1、退避时间至少为 0。第 22-34 行循环调用 lambda；捕获 `PythonAgentException` 后记录最后异常，仅当 `retryable=true` 且未达到上限时调用 `sleepBeforeRetry`，否则原样抛出。
3. `sleepBeforeRetry` 第 36-43 行调用 `Thread.sleep(backoffMillis)`；线程中断时恢复中断标志并抛出不可重试异常。
4. `post` 第 65-66 行先调用 `validateRequest`。第 89-96 行使用 Bean Validator 收集约束违规字段、排序并拼接字段名；有违规则抛出不可重试异常。
5. `post` 第 68-70 行通过 `RestClient` POST Python 路径，反序列化 `AgentResponse`；空响应标记为可重试异常。
6. 第 71-79 行分别处理已包装异常、HTTP 响应异常和网络异常。HTTP 异常先由 `parseStructuredError` 第 82-87 行尝试把错误 JSON 反序列化为带 `error` 的 `AgentResponse`；无法解析时按 5xx 设置 retryable。网络异常统一标为可重试。

### 3.3 Python 函数

#### 3.3.1 `fetch_web`

文件：`python-agent/app/api/application.py:255-271`。

1. 第 255 行注册 POST 路由并声明响应模型 `AgentResponse`。
2. 第 256 行接收 Pydantic payload 和 FastAPI Request；第 257-261 行文档字符串说明只生成预览、不执行网页脚本、不直接写入 RAG。
3. 第 263 行 `_remember_request_context` 保存请求上下文，供统一错误处理和追踪使用。
4. 第 264 行等待 `fetch_public_article(payload.url)`，执行 URL 安全检查、下载和 HTML 解析。
5. 第 265-270 行构造完成响应：复制版本、请求/运行/用户/会话字段，状态为 `COMPLETED`，`answer` 使用标题，`output` 使用 `document.as_dict()`，无错误。

#### 3.3.2 `fetch_public_article`、`validate_public_url`、`_is_public_host`

文件：`python-agent/app/tools/web_reader.py:190-270`。

1. `validate_public_url` 第 203-215 行先 strip 并解析 URL；第 205-206 行只允许 http/https 且必须有 hostname；第 207-210 行捕获非法端口；第 211-212 行拒绝账号密码和非 80/443 端口；第 213-214 行调用 `_is_public_host` 防止访问内网，最后返回规范化 URL。
2. `_is_public_host` 第 190-200 行调用 `socket.getaddrinfo` 解析全部地址；解析失败抛出不可重试依赖异常；逐个地址转为 `ipaddress`，命中 private、loopback、link-local、multicast、reserved 或 unspecified 即返回 `False`，全部通过才返回 `True`。
3. `fetch_public_article` 第 218-225 行先校验入口 URL，初始化最后异常，并创建禁止自动重定向、120 秒超时和固定 User-Agent 的 `httpx.AsyncClient`。
4. 第 226-235 行执行最多 `MAX_RETRIES + 1` 次外层尝试；每次最多处理 `MAX_REDIRECTS + 1` 次响应。重定向必须有 Location，并用 `urljoin` 后重新执行公网 URL 校验。
5. 第 236-242 行要求 HTTP 成功、Content-Type 为 HTML/XHTML，读取响应体并限制 5MB。
6. 第 243-251 行创建 `_ArticleParser`，feed 解码后的 HTML 并 close；拼接标题和正文块为 Markdown，截断到 180000 字符，少于 80 字符则拒绝。
7. 第 252-261 行构造不可变 `WebDocument`：保存最终 URL、最多 500 字符标题、UTC 时间、Markdown SHA-256、内容类型、原始链接和响应字节数。
8. 第 262-270 行处理重定向过多和非网络业务错误（立即抛出）；网络超时、连接错误、HTTP 状态错误按递增 0.5/1.0 秒异步等待后重试，耗尽后抛出可重试依赖异常。

#### 3.3.3 `_ArticleParser` 全部项目函数

文件：`python-agent/app/tools/web_reader.py:99-187`。

1. `__init__` 第 113-122 行调用父类并初始化标题、正文块、标题深度、跳过深度、当前块、标签栈、链接和跳过栈。
2. `handle_starttag` 第 124-147 行统一小写标签、把属性转字典；非跳过区域收集 `<a href>`；压入标签和 skip 标志；脚本、样式及命中广告/导航关键词的节点增加跳过深度；正文块标签先 `_flush`，标题加入 Markdown `#`，列表项加入 `- `。
3. `handle_endtag` 第 149-166 行弹出 skip 标志，维护跳过深度和标签栈；正文标题深度递减；块标签结束时刷新当前文本。
4. `handle_data` 第 168-176 行忽略跳过区，折叠空白并丢弃空文本；标题文本写入 `title_parts`，所有正文写入当前块。
5. `close` 第 178-180 行先调用父类关闭解析器，再刷新末尾块。
6. `_flush` 第 182-187 行将当前块以空格拼接、去首尾空白；非空文本追加到 blocks，并清空当前块。

#### 3.3.4 `WebDocument.as_dict`

文件：`python-agent/app/tools/web_reader.py:48-59`。

1. 第 48 行声明序列化函数。
2. 第 49-55 行将 dataclass 字段映射为前端/Java 约定的 camelCase 键；第 56 行用 Markdown 长度计算 `characterCount`；第 57 行把 tuple 链接转为 JSON 可序列化 list；第 58 行返回原始字节数；第 59 行结束字典。

## 4. 主流构建分析

主流做法是把网页抓取拆成异步任务：API 只创建任务并返回 taskId，队列消费者在隔离的抓取 Worker 中执行 SSRF 校验和解析，结果写入对象存储，前端通过轮询或 SSE 获取进度。优点是不会占用 Java/Python HTTP 请求线程，适合大页面和多页抓取；缺点是需要队列、任务表、过期清理和最终一致性处理，用户无法立即得到预览。

本项目单页预览体量小且需要用户确认，当前同步调用更适合交互；Python 已有明确大小、超时和重试边界。若未来扩展到大批量 URL，可新增 `web_fetch_tasks` 表和 RabbitMQ 任务消息，Java `WebToolService.fetch` 仅返回 taskId，Python Worker 复用 `fetch_public_article`，将 Markdown 写入对象存储并通过独立 Redis 保存短期状态；前端增加 `GET /api/tools/web/fetch/{taskId}` 或 SSE 查询，成功后再走现有知识库上传流程。
