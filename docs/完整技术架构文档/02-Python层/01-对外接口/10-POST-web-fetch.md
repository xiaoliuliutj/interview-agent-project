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
 → WebDocument.as_dict → AgentResponse
```

## 3. 函数解析

### 3.1 `fetch_web`

文件：`python-agent/app/api/application.py:255-271`。

1. 第 255 行将下一函数注册为 POST 路由并指定 Pydantic 响应模型。
2. 第 256 行接收校验后的 `AgentWebFetchRequest` 与当前 FastAPI Request。
3. 第 257-262 行的说明限定安全边界：HTML 不执行，也不进入系统提示词；调用方需确认后才索引。
4. 第 263 行调用 `_remember_request_context`，把 payload 的别名 JSON 保存至 request.state，以便全局异常响应带回 requestId/runId 等。
5. 第 264 行 await `fetch_public_article(payload.url)`，执行安全校验、网络读取和正文提取。
6. 第 265-270 行构建 `AgentResponse`：复制协议和身份字段，设置 code 100、COMPLETED/ACTIVE/版本 0，把标题作为 answer，把 `document.as_dict()` 作为 output，error 为 None。

### 3.2 `_remember_request_context`

文件：`python-agent/app/api/application.py:391-394`。

1. 第 392 行使用 `getattr` 取得 payload 的可选 `model_dump` 方法，避免假定所有对象都是 Pydantic 模型。
2. 第 393-394 行仅当该属性可调用时，以 alias 和 JSON mode 转储 payload 并写入 `request.state.agent_context`。

### 3.3 URL 安全函数

文件：`python-agent/app/tools/web_reader.py:190-215`。

1. `_is_public_host` 第 190-200 行调用 `socket.getaddrinfo` 解析全部 IP；DNS 失败转为不可重试 `AgentDependencyError`；逐项检查 private、loopback、link-local、multicast、reserved、unspecified，任一命中即拒绝。
2. `validate_public_url` 第 203 行 strip/parse 输入；第 205-206 行仅允许带 hostname 的 http/https；第 207-210 行将非法端口转换为业务异常；第 211-212 行拒绝 URL 凭证和非 80/443 端口；第 213-214 行调用公网主机检查；第 215 行返回标准解析结果。

### 3.4 `fetch_public_article`

文件：`python-agent/app/tools/web_reader.py:218-270`。

1. 第 219-225 行校验入口 URL，初始化最后网络异常，并创建 120 秒、禁止自动重定向、固定 User-Agent 的 `httpx.AsyncClient`。
2. 第 226-235 行最多执行三次网络尝试，每次最多处理四次响应；遇到重定向必须有 Location，经 `urljoin` 合成后再次运行 `validate_public_url`。
3. 第 236-242 行要求成功 HTTP 状态和 HTML/XHTML Content-Type，并拒绝超过 5MB 的响应体。
4. 第 243-245 行创建解析器、以服务端编码或 UTF-8 解码 HTML、feed 后 close。
5. 第 246-251 行用 title/blocks 组成 Markdown、截断为 180000 字符、拒绝少于 80 字符的正文。
6. 第 252-261 行返回 WebDocument：最终 URL、最多 500 字符标题、UTC 抓取时刻、SHA-256、Markdown、类型、原始链接和字节数。
7. 第 262-270 行对安全/内容依赖异常立即抛出；仅网络超时、网络错误、HTTP 状态错误按 0.5/1 秒退避重试，最终抛可重试失败。

### 3.5 `_ArticleParser` 与 `WebDocument.as_dict`

文件：`python-agent/app/tools/web_reader.py:99-187、48-59`。

1. `__init__`（113-122）初始化标题、块、深度、标签栈、链接和跳过栈。
2. `handle_starttag`（124-147）小写化标签、在可读区域收集链接；对脚本、样式、导航、广告等设置跳过深度；块标签先 flush，标题/列表写入 Markdown 前缀。
3. `handle_endtag`（149-166）弹出跳过状态、维护标题深度和标签栈，并在块结束时 flush。
4. `handle_data`（168-176）跳过非正文区域、折叠空白、标题写入 title_parts、文本写入当前块。
5. `close`（178-180）调用父类 close 后 flush；`_flush`（182-187）拼接非空块、追加到 blocks 并清空缓冲。
6. `WebDocument.as_dict`（48-59）把字段转换为 Java/前端使用的 camelCase，计算字符数、将 links tuple 转 list，并返回原始字节数。

## 4. 主流构建分析

更主流的网页读取架构会把下载放到隔离的 fetch worker（容器级网络策略、域名限速、对象存储）并用异步任务返回 taskId。优点是隔离风险、避免长请求占用 API Worker，并能扩展大页面处理；缺点是要维护任务状态、队列、存储和轮询。

本项目的单页预览有严格 URL、大小、超时和重试限制，需要立即反馈给用户，当前同步实现适合。若业务扩展，可保留 `fetch_public_article` 作为 Worker 核心，Java 创建任务并由队列驱动 Python Worker，结果写入对象存储，以 taskId 状态接口或 SSE 回传预览。
