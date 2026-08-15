# Java 后端接口路径汇总

本文按 Java 后端的 Controller 模块汇总当前对前端开放的全部 HTTP 接口。路径以 Controller 类上的 `@RequestMapping` 与方法上的映射注解拼接为准；`{...}` 表示 Spring MVC 的路径变量。共 **31** 个接口：简历模块 7 个、面试模块 10 个、知识库模块 10 个、网页工具模块 4 个。

> 范围说明：本文只整理 Java 后端对前端暴露的 `/api/**` 路径，不将 Java 后端调用 Python Agent 的内部 `/v1/**` 路径计入其中。除下载、导出接口直接返回二进制 `ResponseEntity<byte[]>` 外，其余接口由 `ApiResult.success(...)` 包装 JSON 成功响应。

## 1. 简历模块（7 个）

入口类：`com.interviewguide.resume.controller.ResumeController`，类级路径：`/api/resumes`。

| 序号 | HTTP 方法 | 完整路径 | Controller 入口方法 | 用途 |
| --- | --- | --- | --- | --- |
| 1 | `POST` | `/api/resumes/upload` | `upload` | 上传简历并创建分析任务。 |
| 2 | `GET` | `/api/resumes` | `list` | 查询当前用户的简历列表。 |
| 3 | `GET` | `/api/resumes/{id}/detail` | `detail` | 查询指定简历及其分析详情。 |
| 4 | `GET` | `/api/resumes/{id}/export` | `export` | 导出指定简历的分析 PDF。 |
| 5 | `GET` | `/api/resumes/{id}/download` | `download` | 下载指定简历的原始文件。 |
| 6 | `POST` | `/api/resumes/{id}/reanalyze` | `reanalyze` | 按目标岗位重新分析指定简历。 |
| 7 | `DELETE` | `/api/resumes/{id}` | `delete` | 删除指定简历及关联数据。 |

## 2. 面试模块（10 个）

入口类：`com.interviewguide.interview.controller.InterviewController`，类级路径：`/api/interviews`。

| 序号 | HTTP 方法 | 完整路径 | Controller 入口方法 | 用途 |
| --- | --- | --- | --- | --- |
| 8 | `POST` | `/api/interviews` | `start` | 创建文字面试。 |
| 9 | `GET` | `/api/interviews` | `list` | 查询当前用户的面试列表。 |
| 10 | `GET` | `/api/interviews/{sessionId}` | `get` | 查询指定面试会话详情。 |
| 11 | `GET` | `/api/interviews/{sessionId}/agent-status` | `agentStatus` | 查询指定会话的 Agent 进度。 |
| 12 | `GET` | `/api/interviews/unfinished/{resumeId}` | `unfinished` | 按简历查找当前用户未结束的面试。 |
| 13 | `POST` | `/api/interviews/{sessionId}/answers` | `submitAnswer` | 提交指定会话的一次回答。 |
| 14 | `POST` | `/api/interviews/{sessionId}/complete` | `complete` | 完成指定面试会话。 |
| 15 | `POST` | `/api/interviews/{sessionId}/pause` | `pause` | 暂停指定面试会话。 |
| 16 | `GET` | `/api/interviews/{sessionId}/export` | `export` | 导出指定面试的报告 PDF。 |
| 17 | `DELETE` | `/api/interviews/{sessionId}` | `delete` | 删除指定面试会话。 |

## 3. 知识库模块（10 个）

入口类：`com.interviewguide.knowledgebase.controller.KnowledgeBaseController`，类级路径：`/api/knowledgebase`。

| 序号 | HTTP 方法 | 完整路径 | Controller 入口方法 | 用途 |
| --- | --- | --- | --- | --- |
| 18 | `POST` | `/api/knowledgebase/upload` | `upload` | 上传知识库文件并投递向量索引任务。 |
| 19 | `GET` | `/api/knowledgebase/list` | `list` | 按可选排序和向量状态查询知识库列表。 |
| 20 | `GET` | `/api/knowledgebase/{id}/download` | `download` | 下载指定知识库的原始文件。 |
| 21 | `DELETE` | `/api/knowledgebase/{id}` | `delete` | 删除指定知识库及其向量数据。 |
| 22 | `GET` | `/api/knowledgebase/categories` | `categories` | 查询当前用户知识库的分类列表。 |
| 23 | `GET` | `/api/knowledgebase/category/{category}` | `byCategory` | 按分类查询知识库。 |
| 24 | `PUT` | `/api/knowledgebase/{id}/category` | `updateCategory` | 修改指定知识库的分类。 |
| 25 | `GET` | `/api/knowledgebase/search` | `search` | 按文件名关键字搜索知识库。 |
| 26 | `GET` | `/api/knowledgebase/stats` | `stats` | 查询知识库总数及向量索引状态统计。 |
| 27 | `POST` | `/api/knowledgebase/{id}/revectorize` | `revectorize` | 为指定知识库重新创建向量索引。 |

## 4. 网页工具模块（4 个）

入口类：`com.interviewguide.web.controller.WebToolController`，类级路径：`/api/tools/web`。

| 序号 | HTTP 方法 | 完整路径 | Controller 入口方法 | 用途 |
| --- | --- | --- | --- | --- |
| 28 | `POST` | `/api/tools/web/fetch` | `fetch` | 抓取单个网页并返回预览结果。 |
| 29 | `POST` | `/api/tools/web/crawl` | `crawl` | 对受限网站发起抓取并返回预览结果。 |
| 30 | `POST` | `/api/tools/web/crawl/import` | `importCrawl` | 将选中的抓取预览页面导入知识库。 |
| 31 | `GET` | `/api/tools/web/crawl/{previewToken}/archive` | `downloadArchive` | 下载指定抓取预览令牌对应的归档文件。 |

## 5. 路径统计

| 模块 | Controller | 接口数 |
| --- | --- | ---: |
| 简历模块 | `ResumeController` | 7 |
| 面试模块 | `InterviewController` | 10 |
| 知识库模块 | `KnowledgeBaseController` | 10 |
| 网页工具模块 | `WebToolController` | 4 |
| **合计** | **4 个 Controller** | **31** |

## 6. 路由来源

- `java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java`
- `java-backend/src/main/java/com/interviewguide/interview/controller/InterviewController.java`
- `java-backend/src/main/java/com/interviewguide/knowledgebase/controller/KnowledgeBaseController.java`
- `java-backend/src/main/java/com/interviewguide/web/controller/WebToolController.java`
