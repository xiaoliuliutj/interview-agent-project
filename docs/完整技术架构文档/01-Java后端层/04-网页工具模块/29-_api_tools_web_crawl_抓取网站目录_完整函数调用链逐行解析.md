# POST /api/tools/web/crawl：抓取网站目录的完整函数调用链逐行解析

## 1. 接口定义

接口根据入口 URL 和可选知识主题，在同一公网域名内按深度优先级受限抓取页面。Java 将 Python 返回的页面列表保存为 30 分钟、按用户隔离的预览；响应只包含预览 token、页面 Markdown 和归档信息，后续由导入接口决定哪些页面进入知识库。

| 项目 | 实现 |
| --- | --- |
| 前端入口 | `KnowledgeBaseUploadPage.handleCrawl`（`frontend/src/pages/KnowledgeBaseUploadPage.tsx:66-76`） |
| Java 路径 | `POST /api/tools/web/crawl`（`WebToolController.java:62-67`） |
| 请求体 | `{url, topic}`；URL 最长 2048，topic 最长 500 |
| Python 路径 | `POST /v1/tools/web/crawl`（`application.py:273-291`） |
| 资源边界 | 深度 2、有效页面 20、候选访问 100、总响应 50MB、Markdown 1500000 字符、总时长 10 分钟 |

## 2. 函数调用链

```text
KnowledgeBaseUploadPage.handleCrawl
 → knowledgeBaseApi.crawlWebSite
 → request.post → Axios 拦截器
 → WebToolController.crawl
 → WebToolService.crawl → UserIdentityResolver.require
 → HttpPythonAgentClient.crawlWeb → AgentCallExecutor.execute
 → HttpPythonAgentClient.post → validateRequest
 → Python crawl_web → _remember_request_context
 → LLMFactory.create_chat_model → get_settings
 → PromptLoader.__init__
 → RetryPolicy.load → AsyncRetryExecutor.__init__
 → WebCrawlPlanningAgent.__init__/assess
 → StructuredOutputInvoker.invoke/_invoke_model/_validate
 → crawl_public_site
 → validate_public_url/_is_public_host
 → normalize_crawl_url → fetch_public_article
 → _ArticleParser 全部解析函数
 → _page_is_rich/_page_is_relevant
 → _archive_markdown → CrawlResult.as_dict
 → WebToolService.output → WebCrawlPreviewService.save
 → 前端 setCrawlPreview/setSelectedPages
```

## 3. 函数解析

### 3.1 前端与 Java 入口

#### 3.1.1 `KnowledgeBaseUploadPage.handleCrawl`

文件：`frontend/src/pages/KnowledgeBaseUploadPage.tsx:66-76`。

1. 第 66 行声明异步抓取函数；第 67 行 trim URL，空值直接返回。
2. 第 68 行同时开启加载、清空错误和旧的 crawl 预览。
3. 第 69-70 行进入 try，传入 trim 后 URL 和 `crawlTopic.trim() || undefined`；调用 API 时保留主题为空的语义。
4. 第 71 行保存返回的 `WebCrawlResult`；第 72 行默认选中所有有效页面。
5. 第 73-75 行把异常转换为用户可读错误，并在 finally 关闭加载状态。

#### 3.1.2 `knowledgeBaseApi.crawlWebSite` 与 `request.post`

文件：`frontend/src/api/knowledgebase.ts:95-97`、`frontend/src/api/request.ts:47-73、123-163`。

1. `crawlWebSite` 第 95 行声明可选 topic；第 96 行 POST `/api/tools/web/crawl`，发送 `{url, topic}`，为长任务把 Axios 超时设为 660000ms；第 97 行返回 Promise。
2. 请求拦截器用 `currentUserId`（`request.ts:52-58`）读取/生成用户 ID，用 `createClientId`（47-50）生成请求 ID，并在 64-73 行设置两个请求头。
3. `request.post` 第 161-163 行调用 Axios 并提取 `response.data`。响应拦截器 123-155 行剥离成功 `ApiResult`，失败时由 `parseApiError`、`decodeErrorData`、`transportError` 生成结构化错误。

#### 3.1.3 `WebToolController.crawl` 与 `WebToolService.crawl`

文件：`WebToolController.java:62-67`、`WebToolService.java:26-44`。

1. 控制器第 62 行绑定 `/crawl`；第 63-65 行接收并校验 `CrawlRequest` 与用户头；第 66 行传递 `body.url()`、`body.topic()` 给服务并包装 `ApiResult.success`。
2. 服务构造函数第 26-31 行保存 Python 客户端、身份解析器、预览服务。
3. `crawl` 第 40 行声明方法；第 41-43 行生成 `AgentWebCrawlRequest`：API 版本、两个 UUID、规范化 owner、sessionId `web-crawl`、operation `tool.web.crawl`、URL、topic、当前时间。
4. 第 43 行同步调用 Python `crawlWeb`；第 44 行先用 `output` 校验 AgentResponse，再调用 `previews.save` 保存有 owner/TTL 的预览并返回。

### 3.2 Java HTTP 可靠性函数

文件：`HttpPythonAgentClient.java:35-96`、`AgentCallExecutor.java:16-43`。

1. `crawlWeb` 第 51 行把 `/v1/tools/web/crawl` POST 操作交给 `AgentCallExecutor.execute`。
2. `execute` 第 22-34 行按 `maxAttempts` 循环；仅 `PythonAgentException.retryable()` 为真时重试，最终抛出最后一次异常。
3. `sleepBeforeRetry` 第 36-43 行按配置休眠；线程中断时恢复标志并抛出不可重试异常。
4. `post` 第 65-80 行先 Bean 校验，再通过 RestClient 发送请求；空响应、HTTP 响应异常、网络异常分别转换为 PythonAgentException。
5. `validateRequest` 第 89-96 行收集并排序违反约束的字段，阻止非法请求进入 Python。
6. `parseStructuredError` 第 82-87 行尝试解析 Python 返回的 AgentResponse 错误体；解析失败返回 null，交由上层生成 HTTP 错误。

### 3.3 Python 路由和规划 Agent

#### 3.3.1 `crawl_web`

文件：`python-agent/app/api/application.py:273-291`。

1. 第 273-274 行注册路由并接收强类型 payload/request。
2. 第 275 行保存追踪上下文。
3. 第 276-279 行在函数内导入 LLM 工厂、规划 Agent、重试策略和异步执行器，减少应用启动时的可选依赖初始化。
4. 第 280-282 行创建聊天模型、PromptLoader 和 `AsyncRetryExecutor(RetryPolicy.load())`，再注入 `WebCrawlPlanningAgent`。
5. 第 283 行调用 `crawl_public_site`，把入口 URL、主题和 assessor 传入。
6. 第 284-291 行返回完成的 AgentResponse；answer 是有效页面数，output 是 `CrawlResult.as_dict()`。

#### 3.3.2 `LLMFactory.create_chat_model`、`PromptLoader`、`RetryPolicy.load`

文件：`agents/llm/factory.py:9-35`、`common/prompt_loader.py:11-48`、`infrastructure/reliability/policy.py:12-55`。

1. `create_chat_model` 先取传入或 `get_settings()` 的配置；检查 provider、model name、API key，不满足时抛配置异常；组装模型名、密钥、温度、超时和 `max_retries=0`，可选写入 base URL/max tokens，最后构造 ChatOpenAI。
2. `PromptLoader.__init__` 设置 prompts 根目录。`render` 先 `load` 文件，再用正则替换变量；缺失变量或替换后仍有占位符时抛配置异常。`_resolve` 解析路径并确保没有越出 prompts 根目录。
3. `RetryPolicy.load` 读取 reliability.json，转换最大尝试、退避、可重试异常、超时和结构化输出修复次数；捕获文件/JSON/类型错误后抛配置异常，并逐项校验 1-5 次尝试、退避关系、0-120 秒超时、修复次数 0-2 和非空异常集合。
4. `AsyncRetryExecutor.__init__` 保存策略；`execute` 第 23-34 行用 `asyncio.wait_for` 限制每次调用时长，按 `_is_retryable` 和 `_backoff_seconds` 重试；耗尽后包装为 `AgentDependencyError`。这两个辅助函数分别按异常类名匹配策略、计算指数退避并封顶。

#### 3.3.3 `WebCrawlPlanningAgent.assess` 与结构化输出

文件：`agents/web_crawl/agent.py:26-43`、`infrastructure/reliability/structured_output.py:18-104`。

1. Agent 构造函数保存模型和 PromptLoader，并创建 `StructuredOutputInvoker`。
2. `assess` 将业务 Prompt `web-crawl/planner.md`、主题、页面 URL/标题、截断到 12000 字符的正文和最多 100 条候选链接传给 invoker。
3. `invoke` 生成共享结构化输出 Prompt 和 JSON schema，构造 system/human 消息；按允许的修复次数调用 `_invoke_model`，再用 `_validate` 解析 JSON、去除代码围栏并用 Pydantic 校验 `CrawlPageDecision`。校验失败会把原输出和修复指令追加到消息，耗尽后抛 `ModelOutputError`。
4. `_invoke_model` 无执行器时直接 `model.ainvoke`，否则通过异步重试执行器调用；`_content_as_text`、`_strip_json_fence`、`_readable_validation_error` 分别抽取文本、去除 Markdown 围栏、生成字段错误摘要。

### 3.4 Python 抓取核心

文件：`python-agent/app/tools/web_reader.py:190-399`。

1. `crawl_public_site` 第 325 行起先校验入口并记录域名、计时器、队列、去重集合、页面/拒绝列表和字节/尝试计数。
2. while 循环在 10 分钟、50MB、100 次尝试和 20 页任一边界达到时设置 stopReason；取出 URL 后 `normalize_crawl_url` 第 273-281 行解析相对链接、过滤跟踪参数、统一大小写和路径；跨域链接加入 rejected。
3. 对同域未访问 URL 调用 `fetch_public_article`，用 `asyncio.wait_for` 绑定剩余总时长；超时停止，其他异常记录拒绝原因。再次检查重定向后的域名。
4. 根据 `document.links` 规范化候选链接；若有 assessor，调用 `assess`，失败则降级为规则判断。`_page_is_rich` 去除 Markdown 标点后要求至少 160 字符/35 个词；`_page_is_relevant` 在有主题时按中文或单词 token 检查标题及前 5000 字符。
5. 内容哈希重复则拒绝；预计 Markdown 超过 1500000 字符则停止。纳入页面时生成 slug/文件名和 RAG front matter（`rag_index_enabled: true`），用 `dataclasses.replace` 写入页面并记录深度/父 URL；达到长度上限或深度允许且 Agent 要求扩展时，将候选加入队列。
6. 循环结束后根据剩余队列设置部分完成状态，调用 `_archive_markdown` 生成带 `rag_index_enabled: false` 的溯源归档，最后构造 `CrawlResult`。
7. `_archive_markdown` 第 302-323 行依次写入入口、状态、有效/无效数量、来源目录、文件名、深度、SHA-256、页面内容和未纳入原因，并以换行结尾。
8. `CrawlResult.as_dict` 第 79-91 行将结果转换为 camelCase 字典；每页展开 `WebDocument.as_dict` 并补充 depth、parentUrl、filename。

`fetch_public_article`（`web_reader.py:218-270`）是循环实际调用的单页读取函数：第 219 行校验 URL；221-225 行创建不自动跳转、120 秒超时的 AsyncClient；226-235 行处理最多三次网络尝试及三次重定向且每个跳转重新校验；236-242 行限制成功 HTML/XHTML 与 5MB；243-261 行调用 `_ArticleParser`、生成 Markdown、检查至少 80 字符并构造含 SHA-256/链接/字节数的 `WebDocument`；262-270 行对网络/超时/状态码按 0.5、1 秒退避重试，而安全和内容错误立即失败。

`validate_public_url`（203-215）和 `_is_public_host`（190-200）逐行完成协议/hostname、非法端口、凭证、标准端口和所有 DNS 解析 IP 的私网、回环、链路本地、多播、保留、未指定地址检查。`_ArticleParser.__init__`（113-122）初始化解析状态；`handle_starttag`（124-147）收集非跳过区链接、识别脚本/广告/导航区、追加标题或列表 Markdown 前缀；`handle_endtag`（149-166）维护跳过/标题深度并刷新块；`handle_data`（168-176）压缩可读文本；`close`（178-180）结束父解析器后刷新；`_flush`（182-187）将非空当前块加入正文列表。`WebDocument.as_dict`（48-59）把 snake_case 文档和 links tuple 转为 Java 所需 camelCase JSON。

`_remember_request_context`（`application.py:391-394`）通过 `getattr` 取得 Pydantic `model_dump`，只有它可调用时才以 alias/JSON 模式把 payload 写到 `request.state.agent_context`；统一异常处理据此能返回关联的 requestId/runId/userId。

### 3.5 Java 预览保存与前端回写

`WebToolService.output`（`WebToolService.java:56-62`）检查 response 非空、code 在 100-199 且 output 非空；失败时优先使用 Python error.message，否则使用 fallback 并抛 `BusinessException`。

`WebCrawlPreviewService.save`（`WebCrawlPreviewService.java:52-83`）先校验 owner 并删除过期项；限制全局 20 个、单用户 3 个预览；`parsePages` 将 Python page 映射为 Page 并用 `safeFilename` 清理文件名；检查页面数量、正文标记、单页/总长度及归档标记；生成 token 和 30 分钟过期时间，保存 ConcurrentHashMap，并把 token、expiresAt 和 `pageMap` 结果返回。

## 4. 主流构建分析

更主流的目录抓取方案是“任务编排器 + 队列 Worker + 持久化结果”：API 立即返回任务 ID，Worker 按域名限速、robots.txt、分布式去重和断点续抓执行，LLM 规划结果写入任务事件流。优点是长任务可横向扩展、可暂停恢复并避免请求超时；缺点是架构复杂，需要任务状态和幂等设计，预览不能立即返回。

本项目当前用单次同步调用，且已有 10 分钟/50MB/100 次/20 页硬上限和短期内存预览，适合实习项目的小规模交互。若规模增大，可由 Java 发布 RabbitMQ crawl 任务，Python Worker 复用 `crawl_public_site`，将 `CrawlResult` 存对象存储/数据库，Java 只保存 taskId 与 owner；新增进度查询接口并将 `WebCrawlPreviewService` 改为 Redis/数据库存储，以支持多实例和重启恢复。
