# POST /api/tools/web/crawl：受限网站抓取与预览的完整函数调用链

## 1. 接口定义

接口从入口 URL 开始，在同域、深度、页数、字节数、Markdown 总量和十分钟总时限内抓取技术页面。Python 的 WebCrawlPlanningAgent 对每页分类、决定是否纳入知识和扩展哪些真实链接；Java 校验结果并保存在内存预览缓存，返回有 TTL 的 previewToken。该接口本身不导入知识库。

| 项目 | 内容 |
| --- | --- |
| 方法/路径 | POST `/api/tools/web/crawl` |
| 请求 | `{url, topic?}` |
| Java→Python | POST `/v1/tools/web/crawl` |
| 最大范围 | 深度2、有效20页、尝试100页、50MB、约10分钟 |
| Java 返回 | 页面/拒绝列表、归档、previewToken、expiresAt |

## 2. 函数调用链

~~~text
KnowledgeBaseUploadPage.handleCrawl → knowledgeBaseApi.crawlWebSite → request.post
 -> Axios/Filter → WebToolController.crawl → WebToolService.crawl
 -> HttpPythonAgentClient.crawlWeb → Python crawl_web
    -> LLMFactory/RetryPolicy/WebCrawlPlanningAgent 构造
    -> crawl_public_site
       -> validate_public_url/normalize_crawl_url/fetch_public_article
       -> WebCrawlPlanningAgent.assess → StructuredOutputInvoker.invoke
       -> _page_is_rich/_page_is_relevant/_archive_markdown
       -> CrawlResult.as_dict
 -> WebToolService.output → WebCrawlPreviewService.save
    -> removeExpired/parsePages/stringValue/parseRejected/pageMap
 -> ApiResult.success → setCrawlPreview/setSelectedPages
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `KnowledgeBaseUploadPage.handleCrawl`

文件：`frontend/src/pages/KnowledgeBaseUploadPage.tsx:66-76`。

1. 第 67 行 URL 空白 return；第 68 行设置加载、清错误/旧预览。
2. 第 70 行传 trim URL 和非空 topic；第 71 行 setCrawlPreview；第 72 行默认选中所有返回 page.id。
3. 第 73-75 行错误写 UI，finally 清加载。

#### 3.1.2 `knowledgeBaseApi.crawlWebSite`

文件：`frontend/src/api/knowledgebase.ts:95-97`。

1. 第 95 行定义函数；第 96 行 POST url/topic，并给前端 660000ms 超时，略高于 Python 十分钟；第 97 行结束。
2. request.post 和拦截器位于 `api/request.ts:47-73、123-163`，写身份/请求 ID、解包或抛项目异常。

### 3.2 Java 函数

#### 3.2.1 `WebToolController.crawl`、`WebToolService.crawl/output`

文件：`WebToolController.java:62-72`；`WebToolService.java:40-45、56-62`。

1. Controller 第 62-66 行 @Valid 绑定 URL≤2048/topic≤500，调用 service 并 success。
2. Service 第 41-43 行构造 AgentWebCrawlRequest，identity.require owner、两个 UUID、固定 session/operation。
3. 第 44 行先 output 校验 response，再 previews.save；output 第 57-61 行要求成功 code 和非空 output，否则抛 WEB_CRAWL_FAILED。

#### 3.2.2 `HttpPythonAgentClient.crawlWeb`

文件：`HttpPythonAgentClient.java:51、65-96`；`AgentCallExecutor.java:22-43`。

1. crawlWeb 第 51 行执行 `/v1/tools/web/crawl` post lambda。
2. post 先 Bean Validation，再 RestClient POST/反序列化并区分结构化 HTTP 与网络异常；execute 对可重试异常有限重试。

### 3.3 Python 规划和抓取函数

#### 3.3.1 `crawl_web`

文件：`python-agent/app/api/application.py:270-287`。

1. 第 272 行保存上下文；第 273-279 行创建模型、PromptLoader、RetryPolicy/Executor 和 WebCrawlPlanningAgent。
2. 第 280 行 await crawl_public_site(url,topic,assessor)；第 281-287 行返回 code=100、有效页数量和 result.as_dict。

#### 3.3.2 `WebCrawlPlanningAgent.assess`

文件：`python-agent/app/tools/web_crawl_agent.py:26-45`。

1. 构造第 27-30 行保存模型并创建 StructuredOutputInvoker。
2. assess 第 34-45 行要求 CrawlPageDecision schema，业务提示把网页正文声明为不可信数据、只可选择 candidateLinks 中真实 URL。
3. input 只给 topic、URL、标题、前12000字符和最多100链接；Pydantic 约束 pageType、布尔决策、0-100相关度、理由和链接优先级。

#### 3.3.3 `normalize_crawl_url`、`_page_is_relevant`、`_page_is_rich`

文件：`web_reader.py:273-300`。

1. normalize 第 274-281 行 urljoin、验证 http(s)/hostname、删除 UTM/fbclid/gclid/spm 参数、统一 scheme/host/path并去 fragment。
2. relevant 第 285-293 行先要求 Markdown≥160；无 topic 直接 true，有 topic 时提取两字符以上 token并在标题+前5000字符命中任一。
3. rich 第 298-300 行去 Markdown 标记并按英文 token/中文字计数，要求文本≥160且词≥35。

#### 3.3.4 `crawl_public_site`

文件：`web_reader.py:325-440`。

1. 第 327-337 行验证入口，初始化同域 host、计时、BFS queue、seen/hash/pages/rejected、字节/尝试计数。
2. 第 338-347 行循环受 20有效页、10分钟、50MB、100尝试限制。
3. 第 348-356 行 pop/normalize/去重/同域检查并计数。
4. 第 357-374 行以剩余总时间 wait_for `fetch_public_article`；累计 body bytes，超时停止，单页错误加入 rejected 后继续。
5. 第 375-379 行拒绝重定向离域，规范化候选链接。
6. 第 380-388 行调用 assessor；模型失败时 decision=None，后续使用确定性规则降级。
7. 第 389-405 行组合 rich/relevant/Agent 决策、内容 hash 去重和总 Markdown 上限。
8. 第 406-417 行对纳入页面生成 slug 文件名和含 `rag_index_enabled:true` 的来源 front matter，append CrawlPage。
9. 第 418-424 行未纳入页面记录理由，并再次检查总长度。
10. 第 425-434 行深度允许且 expand 时，仅从真实候选链接或 Agent 选择的候选子集入队。
11. 第 435-440 行确定 stopReason/status、生成 archive，并返回不可变 CrawlResult。

#### 3.3.5 `_archive_markdown` 与 `CrawlResult.as_dict`

文件：`web_reader.py:70-91、303-322`。

1. archive 第 305-322 行写 `rag_index_enabled:false`、入口/状态/数量、来源目录、每页全文和拒绝理由，确保归档不能误作 RAG 文档。
2. CrawlResult.as_dict 第 79-91 行输出 entryUrl/status/stopReason/count、pages/rejected/archiveMarkdown；每页复用 WebDocument.as_dict并增加 depth/parentUrl/filename。

### 3.4 Java 预览缓存函数

#### 3.4.1 `WebCrawlPreviewService.save`

文件：`java-backend/src/main/java/com/interviewguide/web/service/WebCrawlPreviewService.java:52-84`。

1. 第 53-58 行 require owner、removeExpired、统计全局/用户数量，超限抛 WEB_CRAWL_PREVIEW_LIMIT。
2. 第 59-60 行 parsePages 和 stringValue archive。
3. 第 61-70 行验证最多页数、页面字段/单页/总长度、每页 true 标记、归档 false 标记与归档大小。
4. 第 71-77 行生成 UUID token/TTL Preview 并放 ConcurrentHashMap。
5. 第 79-83 行复制 output，加入 token/expiresAt并把页面转安全 pageMap后返回。

#### 3.4.2 `removeExpired`、`parsePages`、`parseRejected`、`pageMap`

文件：`WebCrawlPreviewService.java:160-244`。

1. removeExpired 遍历 previews，删除 expiresAt 早于 now 的条目。
2. parsePages 要求输入 list/map，逐项 stringValue/numberValue，生成稳定 page ID 与 Page；非法结构抛结果无效。
3. parseRejected 只保留 url/reason 字段；pageMap 将内部 Page 映射给前端并不暴露 owner/imported 状态。

## 4. 审核结论

1. 已覆盖前端、Java-Python 调用、模型规划、同域/BFS/资源硬限制、确定性降级和 Java TTL 预览缓存。
2. 每个可达项目函数均注明文件/行号并逐句解释；crawl 只生成预览，不自动索引。
