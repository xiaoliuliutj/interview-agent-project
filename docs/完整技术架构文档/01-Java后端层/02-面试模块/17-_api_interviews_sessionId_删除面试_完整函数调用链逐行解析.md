# DELETE /api/interviews/{sessionId}：删除面试会话的完整函数调用链

## 1. 接口定义

接口删除当前用户的一场面试及其 Java 回合记录。若状态为 ACTIVE 或 PAUSED，Java 会先调用 `InterviewService.complete`，同步请求 Python 完成/归档会话，再删除 Java 数据；已经 COMPLETED/FAILED 等状态则不调用 Python，直接删除。Python 的 Agent 会话和用户长期记忆不会被此接口物理删除。

| 项目 | 内容 |
| --- | --- |
| 方法/路径 | DELETE `/api/interviews/{sessionId}` |
| Controller | `InterviewController.delete` |
| Java 删除 | InterviewTurnEntity 后 InterviewSessionEntity |
| 条件 Python | 活动/暂停会话先 POST `/v1/agent/sessions/complete` |
| 返回 | `ApiResult<Void>` |

## 2. 函数调用链

~~~text
InterviewHistoryPage.remove → historyApi.deleteInterview → request.delete
 -> Axios/Filter → InterviewController.delete → UserIdentityResolver.require
 -> InterviewService.delete → ownedSession → Persistence.load
    -> [ACTIVE/PAUSED] InterviewService.complete
       -> HttpPythonAgentClient.complete → Python complete_session
       -> InterviewAgentService.complete_session → Repository.save/MemoryService.finalize_session
       -> Persistence.completeFromAgent
    -> InterviewSessionPersistenceService.delete
       -> requiredForUpdate → assertOwner
       -> TurnRepository.findBySessionIdOrderByCreatedAt → deleteAll
       -> SessionRepository.delete
 -> ApiResult.success → 前端移除列表项
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `InterviewHistoryPage.remove`

文件：`frontend/src/pages/InterviewHistoryPage.tsx:50-60`。

1. 第 50 行定义异步函数；第 51 行无 pendingDelete 时 return。
2. 第 52 行设置 deleting；第 54 行 await historyApi.deleteInterview。
3. 第 55 行成功后从 sessions 中过滤被删 sessionId；第 56 行清 pendingDelete。
4. 第 57-59 行 finally 恢复 deleting；第 60 行结束。

#### 3.1.2 `historyApi.deleteInterview`、`request.delete`

文件：`frontend/src/api/history.ts:101`；`api/request.ts:47-73、123-155、170-172`。

1. historyApi 第 101 行将 sessionId 插入 DELETE 路径并调用泛型 `request.delete<void>`。
2. request.delete 第 170-172 行调用 Axios delete 并返回 response.data。
3. createClientId/currentUserId 第 47-58 行生成请求 ID和读取 owner；请求拦截器第 64-73 行写两个头；响应拦截器第 123-155 行解包成功或返回项目异常。

### 3.2 Java 删除和条件完成函数

#### 3.2.1 `InterviewController.delete`

文件：`java-backend/src/main/java/com/interviewguide/interview/controller/InterviewController.java:106-111`。

1. 第 106 行映射 DELETE `/{sessionId}`；第 107-108 行绑定路径和身份头。
2. 第 109 行 require 用户并调用 service.delete；第 110 行返回 success(null)；第 111 行结束。

#### 3.2.2 `InterviewService.delete`

文件：`java-backend/src/main/java/com/interviewguide/interview/service/InterviewService.java:162-168`。

1. 第 163 行 ownedSession 验证存在和归属。
2. 第 164 行检查 ACTIVE 或 PAUSED；满足时第 165 行调用同类 complete。该调用会同步访问 Python，并把最终评价写回 Java后才继续。
3. 第 167 行调用 persistence.delete 删除 Java 数据；第 168 行结束。

#### 3.2.3 `ownedSession` 与条件 `complete`

文件：`InterviewService.java:138-148、185-191`。

1. ownedSession 第 186 行 load；第 187-189 行比较 getUserId，越权抛 SESSION_ACCESS_DENIED；第 190 行返回。
2. complete 第 139-140 行再次授权并对已完成状态短路；第 141-145 行构造 operation=`agent.session.complete` 的请求；第 146 行校验 Python 响应；第 147 行 completeFromAgent。
3. `complete` 继续调用 `HttpPythonAgentClient.complete`（HttpPythonAgentClient.java:45），后者经 `AgentCallExecutor.execute` 与 `post` 请求 `/v1/agent/sessions/complete`；Python 路由 `complete_session`（application.py:132-153）进入 `InterviewAgentService.complete_session`（service.py:191-233），依次校验会话、生成总结或回退评价、保存 Agent 会话并调用 `MemoryService.finalize_session`。Java 收到响应后执行 `requireMatchingResponse` 和 `InterviewSessionPersistenceService.completeFromAgent`，最后才回到 `delete` 第 167 行删除 Java 数据。这里把条件分支的全部函数明确列出，不依赖其他接口文档。

#### 3.2.4 `InterviewSessionPersistenceService.delete`

文件：`java-backend/src/main/java/com/interviewguide/interview/service/InterviewSessionPersistenceService.java:158-164、188-197`。

1. 第 158 行声明事务；第 159 行定义删除函数。
2. 第 160 行 requiredForUpdate 以悲观锁读取；第 161 行 assertOwner 再次校验用户。
3. 第 162 行先按 sessionId 查询全部 turns 并调用 deleteAll；第 163 行再删除 session，避免留下回合。
4. requiredForUpdate 第 188-191 行找不到抛 SESSION_NOT_FOUND；assertOwner 第 193-196 行对 null/不匹配用户抛 SESSION_ACCESS_DENIED。

### 3.3 条件 Python 完成链

#### 3.3.1 `HttpPythonAgentClient.complete` 与 Python 路由

文件：`HttpPythonAgentClient.java:45、65-96`；`python-agent/app/api/application.py:132-153`。

1. Java complete 第 45 行用 callExecutor 执行 `/v1/agent/sessions/complete`；post 第 65-79 行校验/发送/解析或包装异常。
2. Python 路由第 134 行保存上下文；第 136-146 行 operation 不是 pause，故调用 service.complete_session；第 147-153 行返回 finalEvaluation 和状态。

#### 3.3.2 `InterviewAgentService.complete_session` 与 `MemoryService.finalize_session`

文件：`python-agent/app/agents/interview/service.py:191-233`；`memory/service.py:146-175`。

1. complete_session 第 197-211 行查会话、验证 user、处理终态并校验 Java 状态版本。
2. 第 214-230 行设 COMPLETED、生成模型总结或回退报告、保存 Python 会话；第 231-233 行归档记忆、报 COMPLETED 并返回。
3. finalize_session 第 147-168 行按 sessionId 幂等，汇总分数/优缺点/问题目录并登记 finalized_session_ids；第 169-175 行乐观锁保存或在冲突后确认已写入。

### 3.4 删除边界

1. `InterviewSessionPersistenceService.delete` 只删除 Java `interview_turns` 与 `interview_sessions`。
2. Python complete_session 保存 Agent 终态与长期记忆，但没有 Python delete repository 调用；因此“删除面试”不等于擦除 Python 用户记忆。
3. 非 ACTIVE/PAUSED 分支完全不调用 Python。

## 4. 审核结论

1. 已覆盖前端删除、Java 条件完成、Python 终态归档以及 Java 回合/会话删除。
2. 已明确两条真实分支：ACTIVE/PAUSED 会访问 Python，其他状态直接删除 Java 数据。
