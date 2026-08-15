# GET /api/interviews/{sessionId}/agent-status：查询 Agent 进度的完整函数调用链

## 1. 接口定义

该接口查询某个已授权面试会话在 Python Agent 进程内记录的当前处理阶段，例如 `IDLE`、`PLANNING`、`EVALUATING` 或 `FAILED`。Java 先确认 session 属于当前用户，再 GET Python 的进度端点；它不提交答案、不调用模型、不更新 Java 或 Python 的会话数据。

| 项目 | 内容 |
| --- | --- |
| HTTP 方法 | GET |
| 路径 | `/api/interviews/{sessionId}/agent-status` |
| Java 入口 | `InterviewController.agentStatus` |
| Python 终点 | GET `/v1/agent/sessions/{sessionId}/progress` |
| 返回 | `ApiResult<Map<String,Object>>`，主要字段为 `stage` |

## 2. 函数调用链

~~~text
InterviewPage 的轮询 poll
 -> interviewApi.getAgentStatus → request.get → Axios 拦截器
 -> RequestIdFilter.doFilterInternal → SimpleRateLimitFilter.doFilterInternal
 -> InterviewController.agentStatus → UserIdentityResolver.require
 -> InterviewService.sessionProgress
    -> InterviewService.ownedSession → InterviewSessionPersistenceService.load
       -> InterviewSessionRepository.findById → InterviewSessionEntity.getUserId
    -> HttpPythonAgentClient.sessionProgress
       -> RestClient GET /v1/agent/sessions/{sessionId}/progress
 -> Python session_progress
    -> _resolve_service → InterviewAgentService.progress_for
 -> ApiResult.success → Axios 解包 → poll.setAgentStatus
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `InterviewPage` 的 `poll`

文件：`frontend/src/pages/InterviewPage.tsx:89-106`。

1. useEffect 第 90 行只在正在提交答案且 session 存在时启动轮询；否则 return。
2. 第 91 行保存 active 标志；第 92 行定义异步 poll。
3. 第 94 行 await `interviewApi.getAgentStatus(session.sessionId)`；第 95-98 行只在组件仍 active 且阶段不是 IDLE 时更新显示，避免旧 IDLE 覆盖已展示处理状态。
4. 第 99-101 行请求失败时显示 STATUS_UNAVAILABLE；第 103 行立即执行一次，第 104 行每秒执行一次。
5. 第 105 行 cleanup 把 active 置 false 并清除 interval；第 106 行结束。

#### 3.1.2 `interviewApi.getAgentStatus` 与 `request.get`

文件：`frontend/src/api/interview.ts:89-92`；`api/request.ts:157-160`。

1. 第 89 行声明返回 string；第 90 行 GET Java 进度路径并把 timeout 设为 5000ms；第 91 行返回 result.stage 或 IDLE；第 92 行结束。
2. request.get 第 158 行声明泛型 GET；第 159 行 instance.get 后取 response.data；第 160 行结束。
3. request.ts:47-73 的 createClientId、currentUserId 和请求拦截器依次生成/读取用户 ID，写入 X-User-Id、X-Request-Id。

### 3.2 Java 函数

#### 3.2.1 `InterviewController.agentStatus` 与身份校验

文件：`InterviewController.java:59-64`；`common/security/UserIdentityResolver.java:14-19`。

1. 第 59 行映射 `/{sessionId}/agent-status`；第 60-62 行绑定 sessionId 和请求头。
2. 第 63 行先 require(userId)，再调用 service.sessionProgress，最后 ApiResult.success 包装。
3. require 第 15-17 行拒绝 null/blank，第 18 行 strip，第 19 行返回 owner。

#### 3.2.2 `InterviewService.sessionProgress`、`ownedSession` 与 `load`

文件：`InterviewService.java:125-128、185-191`；`InterviewSessionPersistenceService.java:183-186`。

1. sessionProgress 第 126 行先 `ownedSession(sessionId,userId)`；第 127 行才调用 Python client；第 128 行结束。无权用户不能借此探测 Python 会话。
2. ownedSession 第 186 行调用 persistence.load；第 187-189 行比较 getUserId，不符抛 SESSION_ACCESS_DENIED；第 190 行返回。
3. load 第 183-186 行按 ID 调 Repository.findById，缺失时抛 SESSION_NOT_FOUND。

#### 3.2.3 `HttpPythonAgentClient.sessionProgress`

文件：`java-backend/src/main/java/com/interviewguide/pythonagent/mapper/HttpPythonAgentClient.java:55-63`。

1. 第 55 行定义返回 Map 的方法。
2. 第 56-58 行用 RestClient GET，将 sessionId 代入 `/v1/agent/sessions/{sessionId}/progress`，retrieve 后转换 Map。
3. 第 59-60 行 response body 为 null 时返回 `Map.of("stage","IDLE")`，避免 Java 返回 null。
4. 第 61-63 行把 HTTP/网络异常包装为 PythonAgentException；调用方的异常处理链会返回失败 HTTP。

### 3.3 Python 函数

#### 3.3.1 `session_progress` 与 `_resolve_service`

文件：`python-agent/app/api/application.py:64-68、312-317`。

1. 第 64-65 行注册 Python GET 路由并接收路径 session_id 与 Request。
2. 第 66 行调用 _resolve_service；第 67 行动态取得 progress_for 属性；第 68 行若可调用则返回它的结果，否则返回 IDLE。
3. _resolve_service 第 313 行从 app.state 读取实例；第 314-316 行为空时 build_interview_agent_service 并缓存；第 317 行返回。

#### 3.3.2 `InterviewAgentService.progress_for`

文件：`python-agent/app/agents/interview/service.py:99-100`。

1. 第 99 行定义纯读取函数，参数为 session_id。
2. 第 100 行从 `_progress` 字典取值，缺失时返回 IDLE；不访问数据库、模型或记忆服务。

### 3.4 审核边界

1. `_progress` 只在同一 Python 进程内保存，服务重启或多实例路由到其他实例时可能回到 IDLE；它不是持久化真相。
2. 代码路径不调用 `InterviewAgentService.initialize_session`、respond、complete、Planner 或 `model.ainvoke`，故本接口的 Python 调用终点是 `progress_for`。

## 4. 审核结论

1. 已覆盖前端轮询、Java 授权、Java-Python GET、Python 路由和内存进度读取。
2. 每个项目定义的可达函数均提供文件、行号和逐句说明。
