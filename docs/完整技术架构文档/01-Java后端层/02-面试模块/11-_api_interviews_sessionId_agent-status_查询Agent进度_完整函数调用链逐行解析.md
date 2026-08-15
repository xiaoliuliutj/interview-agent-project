# GET /api/interviews/{sessionId}/agent-status：查询 Agent 进度完整函数调用链逐行解析

> 此接口是 Java 受权代理：它先确认会话属于当前用户，再同步查询 Python 的 progress 路由。Python 优先读取 Python 专属 Redis 进度键，缓存不可用时回退本进程内存；Java 网络异常不会抛给页面，而是返回 `STATUS_UNAVAILABLE`。

## 1. 接口定义

### 1.1 功能与作用

`GET /api/interviews/{sessionId}/agent-status` 返回该面试 Agent 当前阶段，例如 IDLE、CACHE_LOOKUP、RAG_RETRIEVING、WEB_RETRIEVING、SUMMARIZING、COMPLETED 或 FAILED。前端可用它显示处理状态，但该接口不驱动 Agent 执行，只读取状态。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 方法与路径 | `GET /api/interviews/{sessionId}/agent-status` |
| Java 入口 | `InterviewController.agentStatus`，`InterviewController.java:59-64` |
| Python 路径 | `GET /v1/agent/sessions/{session_id}/progress` |
| 授权 | Java 先按 sessionId 校验 X-User-Id |
| 缓存 | Python：`python:agent-progress:<sessionId>`，TTL 86400 秒；Java 不缓存该结果 |
| 异常 | Python HTTP 连接异常被 Java 映射为 `{stage: STATUS_UNAVAILABLE}`。|

### 1.3 前端入口

`frontend/src/api/interview.ts:89-92` 的进度 API 对 Java 路径做 5 秒超时请求，并返回 `stage`。页面将该值传给 `InterviewChatPanel` 显示标签。

## 2. 函数调用链

```text
interviewApi.getAgentStatus -> request.get
  -> Axios interceptor -> currentUserId / createClientId
  -> RequestIdFilter.doFilterInternal -> normalize
  -> SimpleRateLimitFilter.doFilterInternal -> JavaRedisStore.incrementInFixedWindow
  -> IdempotencyFilter.shouldNotFilter（GET 跳过）
  -> InterviewController.agentStatus -> UserIdentityResolver.require
  -> InterviewService.sessionProgress -> ownedSession -> sessionPersistence.load
     -> InterviewSessionRepository.findById / assertOwner
  -> HttpPythonAgentClient.sessionProgress
  -> Python session_progress -> InterviewAgentService.progress_for_async
     -> RedisCache.get_json（python:agent-progress:*）
     ->（未命中/失败）InterviewAgentService.progress_for
  -> ApiResult.success -> Axios response interceptor
```

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `interviewApi` 的进度查询函数

**文件与行号：** `frontend/src/api/interview.ts:89-92`。

1. 第 90 行调用 `request.get<{stage?: string}>`，将 sessionId 写入 Java URL，并显式设置 5000 毫秒 timeout。
2. 第 91 行从返回对象读取 stage；缺失时可由调用方按空值处理。该函数不轮询、不重试，也不写会话状态。
3. `request.ts:47-72` 的 `createClientId`、`currentUserId`、请求拦截器分别生成/复用临时用户 ID、写入 X-User-Id 和 X-Request-Id。成功拦截器 `123-135` 解包 Java result；错误拦截器 `136-154` 转换超时/HTTP 错误。

### 3.2 Java 入口、授权和 HTTP 函数

#### 3.2.1 `RequestIdFilter`、限流和幂等跳过

**文件与行号：** `RequestIdFilter.java:23-41`、`SimpleRateLimitFilter.java:48-82`、`IdempotencyFilter.java:41-44`，均在 `java-backend/src/main/java/com/interviewguide/infrastructure/`。

1. RequestId filter 第 25-33 行规范、保存、回传并在 finally 清理 requestId；`normalize` 第 36-41 行非法时生成 UUID。
2. 限流第 54-67 行用 Redis INCR 固定窗口或 ConcurrentHashMap 回退计数；第 69-79 行限流响应 429。
3. `shouldNotFilter` 第 42-44 行使 GET 不进入幂等键处理。

#### 3.2.2 `InterviewController.agentStatus`、身份与成功包装

**文件与行号：** `java-backend/src/main/java/com/interviewguide/interview/controller/InterviewController.java:59-64`，`common/security/UserIdentityResolver.java:14-19`，`common/web/dto/ApiResult.java:3-6`。

1. 第 59 行映射 status 子路径；第 60-62 行绑定 sessionId 和用户头。
2. 第 63 行先调用 `identity.require`，再调用 `sessionProgress` 并以 `success` 包装；第 64 行结束。
3. `require` 第 15-19 行拒绝空身份并 strip；`success` 第 4-6 行构造 code 200 的统一响应。

#### 3.2.3 `InterviewService.sessionProgress`、`ownedSession` 与持久化 load

**文件与行号：** `InterviewService.java:125-128、185-191`，`InterviewSessionPersistenceService.java:189-203`。

1. `sessionProgress` 第 125 行声明函数；第 126 行调用 `ownedSession`；第 127 行才委托 `pythonAgentClient.sessionProgress`；第 128 行结束。
2. `ownedSession` 调用 `sessionPersistence.load` 并检查 owner。`load` 第 190 行通过 `InterviewSessionRepository.findById` 读取，空时第 191 行抛 `SESSION_NOT_FOUND`。
3. `assertOwner` 第 199-203 行在 null 或 userId 不等时抛 `SESSION_ACCESS_DENIED`。因此 Python progress 不能被任意 sessionId 探测。

#### 3.2.4 `HttpPythonAgentClient.sessionProgress`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/pythonagent/mapper/HttpPythonAgentClient.java:53-63`。

1. 第 55 行声明返回 Map 的方法。第 56 行进入 try；第 57 行用 RestClient GET Python progress URL 并替换路径参数；第 58 行反序列化为 Map。
2. 第 59 行把空响应规范为 `{stage: IDLE}`。第 60 行捕获 `RestClientException`；第 61 行返回 `{stage: STATUS_UNAVAILABLE}`；第 62-63 行结束。
3. 该函数故意不使用 `AgentCallExecutor` 重试，因为进度展示是低价值读操作，快速降级比占用页面请求更合适。

### 3.3 Python 路由与 Redis 进度函数

#### 3.3.1 FastAPI `session_progress`

**文件与行号：** `python-agent/app/api/application.py:64-71`。

1. 第 64 行注册 GET 路由。第 65 行接收 session_id 与 Request。
2. 第 66 行解析 InterviewAgentService。第 67 行以 `getattr` 读取兼容的异步进度函数。
3. 第 68-69 行若函数可调用则 await 并返回 `{stage: ...}`。第 70 行回退查找同步 `progress_for`；第 71 行有同步函数就调用，否则返回 IDLE。

#### 3.3.2 `InterviewAgentService.progress_for_async` 与 `progress_for`

**文件与行号：** `python-agent/app/agents/interview/service.py:103-113`。

1. `progress_for` 第 103-104 行从进程内 `_progress` 字典取值，缺失返回 IDLE。
2. 异步函数第 106 行声明跨实例读取。第 109 行调用 Python RedisCache 的 `get_json`，键为 `python:agent-progress:<session_id>`。
3. 第 110-111 行若缓存值是字典且 stage 为字符串则返回它。缓存未命中或 Redis 异常由缓存实现返回空值，函数第 112 行调用 `progress_for` 回退；第 113 行结束。

#### 3.3.3 `_report_progress` 与 `mark_progress_failed`

**文件与行号：** `python-agent/app/agents/interview/service.py:114-133`。

1. `mark_progress_failed` 第 114 行声明失败标记；第 117 行更新本机字典为 FAILED；第 121 行异步/调度写 Redis 键和 86400 秒 TTL，缓存失败不会覆盖本机状态。
2. `_report_progress` 第 126 行声明阶段上报；第 127 行先更新内存；第 129-131 行把 `{stage}` 写 Python 专属 Redis；第 132-133 行如存在 reporter 回调则 await 通知。
3. 这些函数由初始化、回答、完成等 Agent 路径调用；纯 progress 查询只读取，不会触发 `_report_progress`。

## 4. 主流构建分析

当前采用“Java 授权代理 + Python Redis 短状态”的方案，优点是前端不直连 Python、跨 Python 实例可见、Redis 故障仍有本机显示；缺点是本机回退并非跨实例一致，Java 将远端不可用压成一个状态，调用方无法区分超时/网络/5xx。

主流改进是使用事件流（SSE/WebSocket）或状态机事件表，让 Python 发布进度、Java 网关按用户订阅。优点是减少轮询、状态变化实时；缺点是长连接、断线补偿、授权和消息积压管理更复杂。

本项目目前较适合保留轮询。若引入推送，应把 progress key 作为加速层而非事实来源，事件中包含 sessionId/stateVersion/stage，并要求 Java 在订阅前执行当前的 `ownedSession` 校验；断线后仍可回退本接口查询。
