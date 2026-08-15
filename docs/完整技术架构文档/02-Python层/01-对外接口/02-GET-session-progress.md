# GET /v1/agent/sessions/{session_id}/progress：查询面试处理进度

## 1. 接口定义

该接口供 Java 状态代理查询 Python Agent 对某个面试会话的处理阶段。当前实现优先读取 Python 专属 Redis 中的跨实例快照，缓存不可用或没有该键时回退到当前进程的 `_progress` 字典；两处均无记录时返回 `IDLE`。它是运行状态快照，不读取 PostgreSQL 中的面试业务状态。

| 项目 | 内容 |
| --- | --- |
| HTTP 方法/路径 | `GET /v1/agent/sessions/{session_id}/progress` |
| 路由函数 | `session_progress`，`python-agent/app/api/application.py:64-71` |
| 热路径 | `_resolve_service → progress_for_async → RedisCache.get_json → progress_for` |
| 冷启动路径 | `_resolve_service → build_interview_agent_service` |
| 响应 | `{"stage":"IDLE|PLANNING|EVALUATING|...|FAILED|COMPLETED"}` |

## 2. 函数调用链

```text
FastAPI → session_progress → _resolve_service
 ├─ 已有 InterviewAgentService：直接复用
 └─ service is None：build_interview_agent_service
      → get_settings → create_session_factory → InterviewWorkflow.load
      → LLMFactory.create_chat_model → RetryPolicy.load
      → build_rag_service（配置 embedding_model 时）
      → build_cache/build_memory_service → InterviewAgentService.__init__
 → InterviewAgentService.progress_for_async
    → RedisCache.get_json
    ├─ 命中合法 {stage: str}：返回 Redis stage
    └─ 未命中/Redis 异常：InterviewAgentService.progress_for
 → {"stage": stage}
```

## 3. 函数解析

### 3.1 `session_progress`

文件：`python-agent/app/api/application.py:64-71`。

1. 第 64 行注册带 `session_id` 路径参数的 GET 路由。
2. 第 65 行声明异步函数，FastAPI 将路径参数和 Request 注入。
3. 第 66 行调用 `_resolve_service` 获取应用级面试服务。
4. 第 67 行通过 `getattr` 读取可选 `progress_for_async`，允许测试替身没有异步方法。
5. 第 68-69 行若异步方法可调用，等待它返回阶段并立即包装为字典；真实生产服务会进入此分支。
6. 第 70 行仅在异步方法不存在时读取同步 `progress_for`。
7. 第 71 行若同步方法可调用则传入 session_id，否则返回 `IDLE`；这是兼容测试替身的最终回退。

### 3.2 `_resolve_service`

文件：`python-agent/app/api/application.py:315-320`。

1. 第 315 行声明从 Request 解析 `InterviewAgentService`。
2. 第 316 行读取 `request.app.state.interview_agent_service`；该字段由 `create_app` 第 48-58 行创建 FastAPI 时初始化。
3. 第 317 行判断是否尚未装配服务。
4. 第 318 行冷启动时调用 `build_interview_agent_service`。
5. 第 319 行把新实例写回 app.state，使后续请求共享该实例、进程内进度和连接池。
6. 第 320 行返回已有或新建服务。

### 3.3 `build_interview_agent_service` 冷启动分支

文件：`python-agent/app/bootstrap.py:45-79`。

1. 第 50 行使用传入配置或调用 `get_settings`。`get_settings`（`common/config.py:47-51`）由 `lru_cache(maxsize=1)` 维护进程级 Settings 快照。
2. 第 51 行 `create_session_factory` 创建 SQLAlchemy 异步会话工厂；第 52-53 行创建 PromptLoader 和 SkillRegistry；第 54 行 `InterviewWorkflow.load` 从配置加载阶段工作流。
3. 第 55 行 `LLMFactory.create_chat_model` 校验供应商、模型名和密钥并创建统一模型客户端；第 56 行用 `RetryPolicy.load` 的 JSON 策略创建 AsyncRetryExecutor。
4. 第 57 行只有配置了 embedding model 才调用 `build_rag_service` 创建 RagSearchTool，否则进度查询不会强制要求向量能力。
5. 第 59-78 行构造所有面试 Agent、PostgreSQL 仓库、记忆服务、幂等策略、网页证据工具和 RedisCache，最后创建 InterviewAgentService。
6. `build_cache`（`bootstrap.py:41-43`）读取 Settings 并构造 `RedisCache(redis_url)`；`build_memory_service`（33-38）创建数据库工厂、长期记忆仓库并加载 MemoryPolicy；`build_rag_service`（82-91）创建向量仓库、Embedding Provider、RagPolicy 和缓存。以上只发生在 app.state 未注入服务的分支。

### 3.4 `InterviewAgentService.progress_for_async` 与 `progress_for`

文件：`python-agent/app/agents/interview/service.py:103-112`。

1. `progress_for_async` 第 106 行声明异步读取；第 108 行仅在构造时注入了 cache 才读 Redis。
2. 第 109 行用键 `python:agent-progress:{session_id}` 调用 `RedisCache.get_json`。
3. 第 110 行同时要求返回值是 dict、stage 是 str，避免错误缓存结构污染接口；第 111 行命中时返回 stage。
4. 第 112 行未命中时调用同步 `progress_for`。
5. `progress_for` 第 103-104 行从 `_progress` 字典读取 session_id，没有记录返回 `IDLE`。

### 3.5 `RedisCache.__init__` 与 `get_json`

文件：`python-agent/app/infrastructure/cache/redis_cache.py:18-39`。

1. 构造函数第 19-25 行：有 redis_url 时通过 `Redis.from_url` 创建解码字符串的异步客户端，并把连接/命令超时限制为 0.2 秒；无 URL 时客户端为 None。
2. `get_json` 第 31 行声明异步读取；第 32-33 行未配置 Redis 时立即返回 None。
3. 第 34-36 行 await Redis GET；有字符串时 `json.loads`，空值返回 None。
4. 第 37-39 行捕获 RedisError、JSONDecodeError、TypeError，记录警告并返回 None。因此缓存故障不会让进度 HTTP 接口失败，而是回退进程内状态。

## 4. 主流构建分析

主流多实例 Agent 系统通常把进度建模为持久化任务状态机或事件流：每次阶段迁移写数据库/消息事件，Redis 只作加速，客户端通过 SSE/WebSocket 订阅。优点是跨进程、重启和审计均可靠，能展示阶段时间和失败原因；缺点是写放大、状态迁移约束和推送连接管理更复杂。

本项目已经采用“Python Redis 优先、进程内回退”，对短时 UI 轮询和单机/小规模多实例足够，但 Redis 重启与服务重启同时发生时进度会回到 IDLE。若需要生产级可恢复进度，可在 Python 自有 schema 增加 `agent_run_progress` 表，以 runId/sessionId、stage、version、updatedAt 为字段；`_report_progress` 在事务中写表并更新 Redis，查询接口依次读 Redis、数据库、进程内字典，同时可新增 SSE 端点发布同一状态迁移。
