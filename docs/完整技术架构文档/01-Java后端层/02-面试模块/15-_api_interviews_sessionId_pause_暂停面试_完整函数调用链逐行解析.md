# POST /api/interviews/{sessionId}/pause：暂停面试的完整函数调用链

## 1. 接口定义

该接口将当前用户处于 ACTIVE 状态的面试暂停。Java 校验会话后同步调用 Python `/v1/agent/sessions/complete`，但请求 operation 为 `agent.session.pause`；Python 路由据此执行 `pause_session`，以乐观锁保存 PAUSED。Java 再写回状态。会话不是完成状态时才访问 Python；已 PAUSED/COMPLETED 的处理由状态分支决定。

| 项目 | 内容 |
| --- | --- |
| 方法/路径 | POST `/api/interviews/{sessionId}/pause` |
| Controller | `InterviewController.pause` |
| Python 路径 | POST `/v1/agent/sessions/complete` |
| Python operation | `agent.session.pause` |
| 返回 | `ApiResult<Void>` |

## 2. 函数调用链

~~~text
interviewApi.pauseInterview → request.post → Axios/Filter
 -> InterviewController.pause → UserIdentityResolver.require
 -> InterviewService.pause → ownedSession → Persistence.load
    ->（非 ACTIVE：return）
    -> HttpPythonAgentClient.complete → post
    -> Python complete_session → _resolve_service → InterviewAgentService.pause_session
       -> Repository.get → _validate_expected_state → Repository.save
    -> _success_response → requireMatchingResponse
    -> Persistence.pauseFromAgent → requiredForUpdate → assertOwner
       -> Session.applyAgentResponse → Repository.save
 -> ApiResult.success
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `interviewApi.pauseInterview`

文件：`frontend/src/api/interview.ts:98-100`。

1. 第 98 行定义异步函数并接收 sessionId。
2. 第 99 行 await `request.post<void>(/api/interviews/${sessionId}/pause)`；没有请求体。
3. 第 100 行结束。request.post、createClientId、currentUserId、Axios 请求/响应拦截器分别位于 `api/request.ts:47-73、123-163`：写入用户/请求 ID、解包 ApiResult 或抛 ApiRequestError。

### 3.2 Java 函数

#### 3.2.1 `InterviewController.pause`

文件：`java-backend/src/main/java/com/interviewguide/interview/controller/InterviewController.java:88-93`。

1. 第 88 行映射 POST `/{sessionId}/pause`；第 89-90 行绑定路径和身份头。
2. 第 91 行 require 用户后调用 service.pause；第 92 行 ApiResult.success(null)；第 93 行结束。

#### 3.2.2 `InterviewService.pause`

文件：`java-backend/src/main/java/com/interviewguide/interview/service/InterviewService.java:150-160`。

1. 第 151 行 ownedSession 验证存在和归属。
2. 第 152 行状态不为 ACTIVE 直接 return：已暂停、完成或失败时不重复调用 Python。
3. 第 153 行生成 runId；第 154-157 行构造 AgentCompleteRequest，operation 明确写 `agent.session.pause`，同时传上层状态和 Agent stateVersion。
4. 第 158 行 requireMatchingResponse 校验 Python 返回；第 159 行 pauseFromAgent 写回 Java；第 160 行结束。

#### 3.2.3 `ownedSession`、HTTP 客户端与写回

文件：`InterviewService.java:185-191、202-236`；`HttpPythonAgentClient.java:45、65-96`；`InterviewSessionPersistenceService.java:151-156`。

1. ownedSession 第 186 行 load；第 187-189 行比较 getUserId，越权抛 SESSION_ACCESS_DENIED。
2. HttpPythonAgentClient.complete 第 45 行以固定 complete 路径调用 post；post 第 66-79 行校验 DTO、发送 JSON、处理空响应、结构化 HTTP 错误与网络错误。
3. requireSuccess 第 202-215 行拒绝非成功 AgentResponse；requireMatchingResponse 第 226-236 行再比对用户、会话和 runId。
4. pauseFromAgent 第 152 行锁定读取，153 行 assertOwner，154 行 applyAgentResponse，155 行 Repository.save，156 行结束。

### 3.3 Python 函数

#### 3.3.1 `complete_session` 路由分支

文件：`python-agent/app/api/application.py:132-153`。

1. 第 133 行定义共同 complete/pause 路由；第 134 行记录请求上下文。
2. 第 136-146 行三元分支检查 payload.operation。`agent.session.pause` 时第 136-140 行 await service.pause_session；其他 operation 才 complete_session。
3. 第 147-153 行用 _success_response 返回会话状态；暂停通常没有 finalEvaluation output。

#### 3.3.2 `InterviewAgentService.pause_session`

文件：`python-agent/app/agents/interview/service.py:235-254`。

1. 第 240 行 Repository.get；第 241-242 行会话缺失或 user 不匹配时抛 ConsistencyError。
2. 第 243-244 行已 COMPLETED/FAILED 时直接返回，不把终态改回 PAUSED。
3. 第 245-249 行调用 _validate_expected_state，要求 Java 和 Python 的状态、版本一致。
4. 第 250 行保存 expected_version；第 251 行设置 PAUSED；第 252 行标记 interrupted；第 253 行以 expected_version 调 Repository.save；第 254 行返回。

#### 3.3.3 `_validate_expected_state` 与仓储 `save`

文件：`service.py:405-417`；`infrastructure/persistence/interview_session_repository.py:55-86`。

1. _validate_expected_state 第 411-417 行同时比较 status/state_version，不一致抛 ConsistencyError，防止旧 Java 请求覆盖新 Agent 状态。
2. Repository.save 第 58-64 行生成 next_version 和更新时间；第 65-79 行构造带 session_id 与旧 state_version 条件的 UPDATE。
3. 第 80-85 行执行更新，rowcount 非 1 时 rollback 并抛并发错误；第 86 行返回保存后 session。

## 4. 审核结论

1. 已覆盖前端请求、Java 状态短路、Python pause 分支、乐观锁保存和 Java 状态写回。
2. 暂停不调用总结 Agent、MemoryService.finalize_session 或模型；Python 终点为 pause_session。
