# Python 层功能与接口顺序汇总

## 对外接口（按 `python-agent/app/api/application.py` 出现顺序）

| 序号 | 方法 | 路径 | 路由函数 | 文档 |
|---:|---|---|---|---|
| 1 | GET | `/health` | `health` | [01-GET-health.md](01-对外接口/01-GET-health.md) |
| 2 | GET | `/v1/agent/sessions/{session_id}/progress` | `session_progress` | [02-GET-session-progress.md](01-对外接口/02-GET-session-progress.md) |
| 3 | POST | `/v1/agent/sessions/initialize` | `initialize_session` | [03-POST-sessions-initialize.md](01-对外接口/03-POST-sessions-initialize.md) |
| 4 | POST | `/v1/agent/respond` | `respond` | [04-POST-agent-respond.md](01-对外接口/04-POST-agent-respond.md) |
| 5 | POST | `/v1/agent/sessions/complete` | `complete_session` | [05-POST-sessions-complete.md](01-对外接口/05-POST-sessions-complete.md) |
| 6 | POST | `/v1/agent/evaluate/resume` | `evaluate_resume` | [06-POST-evaluate-resume.md](01-对外接口/06-POST-evaluate-resume.md) |
| 7 | POST | `/v1/agent/resume/activate` | `activate_resume_memory` | [07-POST-resume-activate.md](01-对外接口/07-POST-resume-activate.md) |
| 8 | POST | `/v1/agent/rag/index` | `index_rag` | [08-POST-rag-index.md](01-对外接口/08-POST-rag-index.md) |
| 9 | POST | `/v1/agent/rag/delete` | `delete_rag` | [09-POST-rag-delete.md](01-对外接口/09-POST-rag-delete.md) |
| 10 | POST | `/v1/tools/web/fetch` | `fetch_web` | [10-POST-web-fetch.md](01-对外接口/10-POST-web-fetch.md) |
| 11 | POST | `/v1/tools/web/crawl` | `crawl_web` | [11-POST-web-crawl.md](01-对外接口/11-POST-web-crawl.md) |

说明：源码中没有 `/v1/tools/web/crawl/import` Python 路由；该路径属于 Java 上层预览/导入业务，不能计入 Python 路由数量。

## 功能目录

| 目录 | 覆盖范围 |
|---|---|
| `01-对外接口` | FastAPI 11 个真实路由及 Python 入口链路 |
| `02-Agent` | 面试服务、规划/评价/路由/出题/总结 Agent、工作流、结构化输出和重试 |
| `03-RAG` | 文档解析、切片、embedding、向量检索、缓存、索引删除 |
| `04-记忆工具` | 用户长期记忆、简历激活/评价快照、轮次和会话归档 |
| `05-Skills` | Skill 元数据、指令加载、工具白名单和面试选择 |
| `06-网页抓取工具` | URL 安全、HTML 抽取、单页抓取、受限站点爬取和规划 Agent |
