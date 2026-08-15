# POST /api/interviews/{sessionId}/pause：暂停面试完整函数调用链逐行解析

> 当前暂停接口不是只修改 Java 状态：它会同步调用 Python `/v1/agent/sessions/complete`，但请求 operation 明确为 `agent.session.pause`。Python 路由据此调用暂停分支，Java 仅在返回身份/状态校验通过后保存会话。

## 1. 接口定义

### 1.1 功能与作用

`POST /api/interviews/{sessionId}/pause` 暂停当前用户处于 ACTIVE 状态的面试，以便稍后通过未结束会话查询和详情恢复。会话并非删除，也不生成最终报告。已经 PAUSED、COMPLETED 或 FAILED 时，Java 不调用 Python，直接成功返回。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 路径 | `POST /api/interviews/{sessionId}/pause` |
| Controller | `InterviewController.pause`，`InterviewController.java:88-93` |
| Python 路径 | `POST /v1/agent/sessions/complete` |
| 区分字段 | `operation = agent.session.pause` |
| 可暂停状态 | 仅 ACTIVE；其他状态直接 return |
| 结果 | Java 会话保留现有问题/计数，持久化 Python 回显状态与新版本。|

### 1.3 前端入口

`frontend/src/api/interview.ts:98-100` 的 `pauseInterview` 对该路径 POST。面试页/导航离开逻辑调用它后，可通过 `findUnfinishedSession` 继续恢复。

## 2. 函数调用链

```text
interviewApi.pauseInterview -> request.post -> Axios interceptor
  -> RequestIdFilter.doFilterInternal -> SimpleRateLimitFilter.doFilterInternal
  -> IdempotencyFilter.shouldNotFilter（可选 doFilterInternal）
  -> InterviewController.pause -> UserIdentityResolver.require -> InterviewService.pause
     -> ownedSession -> InterviewSessionPersistenceService.load / assertOwner
     -> HttpPythonAgentClient.complete -> AgentCallExecutor.execute -> post
        -> Python complete_session (operation=pause) -> InterviewAgentService.pause_session
     -> requireMatchingResponse -> requireSuccess / firstNonBlank
     -> InterviewSessionPersistenceService.pauseFromAgent
        -> requiredForUpdate -> assertOwner -> applyAgentResponse -> advanceStateVersion
        -> InterviewSessionRepository.save
  -> ApiResult.success(null)
```

## 3. 函数解析

### 3.1 前端、过滤器与 Controller 函数

#### 3.1.1 `interviewApi.pauseInterview` 与请求处理

**文件与行号：** `frontend/src/api/interview.ts:98-100`，`frontend/src/api/request.ts:47-72、123-154`。

1. 第 98 行声明异步暂停函数；第 99 行对 sessionId 路径调用 `request.post<void>`；第 100 行结束。
2. `createClientId` 第 47-49 行生成临时客户端 ID；`currentUserId` 第 52-57 行读写 localStorage；请求拦截器第 64-72 行写 X-User-Id 与 X-Request-Id。
3. 成功拦截器第 123-135 行从 ApiResult 取 null data；错误拦截器第 136-154 行转换网络、HTTP 或统一业务错误。

#### 3.1.2 RequestId、限流、幂等和 Controller

**文件与行号：** `RequestIdFilter.java:23-41`、`SimpleRateLimitFilter.java:48-82`、`IdempotencyFilter.java:41-96`，位于 `java-backend/src/main/java/com/interviewguide/infrastructure/`；`InterviewController.java:88-93`。

1. RequestId 第 25-33 行规范/回传 requestId、MDC 放行和清理，`normalize` 第 36-41 行无效值生成 UUID。
2. 限流第 54-67 行 Redis 计数失败时回退本机窗口，第 69-79 行超限 429。POST 若没有幂等头，`shouldNotFilter` 第 42-44 行跳过；若有头，`doFilterInternal` 第 50-84 行占位并由 `writeConflict` 第 88-95 行拒绝重复。
3. Controller 第 88 行映射 pause；第 89-90 行绑定参数；第 91 行 require 后调用服务；第 92 行 success(null)；第 93 行结束。

### 3.2 Java 暂停与 Python 调用函数

#### 3.2.1 `InterviewService.pause`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/interview/service/InterviewService.java:150-160`。

1. 第 151 行以 `ownedSession` 读取并校验会话。它调用 `sessionPersistence.load`，再比较 userId；不存在/越权分别转换为 SESSION_NOT_FOUND/SESSION_ACCESS_DENIED。
2. 第 152 行只在状态不是 ACTIVE 时 return，因此重复暂停和已完成暂停没有 Python 副作用。
3. 第 153 行创建 runId。第 154-157 行构造 `AgentCompleteRequest`，注意 endpoint 名称是 complete，但第 156 行 operation 明确写 `agent.session.pause`，并携带当前状态和 stateVersion。
4. 第 158 行校验 Python 响应；第 159 行调用 `pauseFromAgent` 持久化；第 160 行结束。

#### 3.2.2 `HttpPythonAgentClient.complete`、重试与 Python 路由分支

**文件与行号：** `HttpPythonAgentClient.java:45、65-96`，`AgentCallExecutor.java:22-43`，`python-agent/app/api/application.py:135-`。

1. Java client 第 45 行将请求交给 bounded retry executor，再 POST `/v1/agent/sessions/complete`。`post` 第 65-79 行校验和执行请求，并映射错误；`execute` 第 22-34 行仅 retryable 异常重试。
2. Python `complete_session` 接收完成请求并保存上下文。路由第 143 行检查 `payload.operation == "agent.session.pause"`；真时 await `service.pause_session`，否则 await `service.complete_session`。因此名称复用不是文档推测，而是明确的条件分支。
3. Python 服务暂停函数保存可恢复 session/状态，并由进度上报函数写 Python 专属 Redis。路由把结果打包回 `AgentResponse`；依赖失败按全局异常处理返回协议错误。

#### 3.2.3 响应校验与 `pauseFromAgent`

**文件与行号：** `InterviewService.java:202-237`，`InterviewSessionPersistenceService.java:154-161`。

1. `requireSuccess` 第 202-217 行只接受 100–199 code，失败以 `firstNonBlank` 第 219-224 行选择错误；`requireMatchingResponse` 第 226-237 行比较 user/session/run，避免暂停结果串会话。
2. `pauseFromAgent` 第 154 行启动事务。第 156 行锁定会话，`requiredForUpdate` 使用 Mapper 的 `findByIdForUpdate`；第 157 行重复校验 owner。
3. 第 158 行 `applyAgentResponse` 写 Python 返回的状态、answer、stateVersion、stage；第 159 行 `advanceStateVersion`；第 160 行 MyBatis 保存；第 161 行结束。它不调用 `storeFinalEvaluation` 或 `session.complete`，与完成接口的实现不同。

## 4. 主流构建分析

当前复用 completion endpoint、以 operation 区分暂停，优点是协议和 HTTP 客户端较少、会话状态机集中在 Python；缺点是 endpoint 名称语义不直观，调用方若忽略 operation 容易误用，OpenAPI 文档也难以清晰表达两种业务。

主流改进是拆分为 `POST /sessions/{id}/pause` 与 `POST /sessions/{id}/complete`，使用判别联合请求/响应模型并明确状态转移。优点是 REST 语义、权限和监控指标更清晰；缺点是新增路由、客户端方法与兼容迁移工作。

本项目适合渐进拆分：先保留现有 endpoint 兼容，Python 新增 pause 路由，Java `PythonAgentClient` 新增 `pause` 方法，前端无需直连 Python。完成迁移后删除 operation 分支。无论拆分与否，保持当前 ACTIVE 前置条件、runId 回显和 Java 行锁检查。
