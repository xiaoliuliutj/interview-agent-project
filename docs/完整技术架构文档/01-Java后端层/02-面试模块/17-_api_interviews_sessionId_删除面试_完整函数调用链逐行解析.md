# DELETE /api/interviews/{sessionId}：删除面试完整函数调用链逐行解析

> 删除面试会先结束 ACTIVE/PAUSED 会话，随后在 Java MyBatis 事务内删除 turn 和 session。前置结束会同步调用 Python；已结束会话则仅删除 Java 数据。

## 1. 接口定义

### 1.1 功能与作用

`DELETE /api/interviews/{sessionId}` 删除当前用户的一场面试及其所有 turn。若会话仍 ACTIVE 或 PAUSED，服务先调用完整的 `complete` 流程生成最终状态，再删除；这避免 Python 仍持有活动会话而 Java 已无记录。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 路径 | `DELETE /api/interviews/{sessionId}` |
| Controller | `InterviewController.delete`，`InterviewController.java:106-111` |
| 条件调用 Python | 仅 ACTIVE、PAUSED 时先 POST Python complete |
| 数据删除 | 先 `interview_turns`，后 `interview_sessions`，MyBatis 事务 |
| 响应 | `ApiResult<Void>` |

### 1.3 前端入口

面试历史页确认删除后调用 `historyApi.deleteInterview`；其定义在 `frontend/src/api/history.ts:101`。

## 2. 函数调用链

```text
historyApi.deleteInterview -> request.delete -> Axios interceptor
  -> RequestIdFilter -> SimpleRateLimitFilter -> IdempotencyFilter（可选）
  -> InterviewController.delete -> UserIdentityResolver.require -> InterviewService.delete
     -> ownedSession -> load/owner check
     ->（ACTIVE/PAUSED）InterviewService.complete -> Python complete_session
        -> requireMatchingResponse -> completeFromAgent
     -> InterviewSessionPersistenceService.delete
        -> requiredForUpdate -> assertOwner
        -> InterviewTurnRepository.findBySessionIdOrderByCreatedAt -> deleteAll
        -> InterviewSessionRepository.delete
  -> ApiResult.success(null)
```

## 3. 函数解析

### 3.1 前端、过滤器与 Controller 函数

#### 3.1.1 `historyApi.deleteInterview` 与请求函数

**文件与行号：** `frontend/src/api/history.ts:101`，`frontend/src/api/request.ts:47-72、123-154`。

1. 第 101 行把 sessionId 拼到 `/api/interviews/${sessionId}` 并调用 `request.delete<void>`。
2. `createClientId` 第 47-49 行和 `currentUserId` 第 52-57 行提供客户端/用户 ID；拦截器第 64-72 行写追踪头；响应拦截器第 123-154 行解包或抛错误。

#### 3.1.2 RequestId、限流、幂等与 `InterviewController.delete`

**文件与行号：** `RequestIdFilter.java:23-41`、`SimpleRateLimitFilter.java:48-82`、`IdempotencyFilter.java:41-96`，目录为 `java-backend/src/main/java/com/interviewguide/infrastructure/`；`InterviewController.java:106-111`。

1. RequestId filter 保存/回传安全 ID 并 finally 清理 MDC；限流使用 Redis 固定窗口，失败本机回退，超限返回 429。
2. DELETE 没有幂等头时跳过；有头时 Idempotency filter 占位并拒绝重复键 409。
3. Controller 第 106 行映射 delete，第 107-108 行绑定参数，第 109 行 require 后调用服务，第 110 行 success(null)，第 111 行结束。

### 3.2 Java 删除和条件完成函数

#### 3.2.1 `InterviewService.delete`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/interview/service/InterviewService.java:162-168`。

1. 第 163 行调用 `ownedSession`，确保不存在或越权不会进入删除。
2. 第 164 行检查状态；第 165 行仅 ACTIVE/PAUSED 时调用 `complete(sessionId,userId)`，该函数会生成 runId、调用 Python、验证回显并事务写 COMPLETED。
3. 第 167 行无论原先已结束或刚完成，都调用持久化 delete；第 168 行结束。

#### 3.2.2 `complete` 与 Python 分支

**文件与行号：** `InterviewService.java:138-148`，`InterviewSessionPersistenceService.java:143-152`，`python-agent/app/api/application.py:135-`。

1. `complete` 读取授权会话，已 COMPLETED return，否则构造 complete request、调用 Python、验证 user/session/run，并调用 `completeFromAgent`。
2. `completeFromAgent` 以事务锁定会话、再次校验 owner、应用响应、保存 finalEvaluation、标记 COMPLETED、推进版本并 Mapper 保存。
3. Python complete 路由处理 operation 非 pause 的分支，汇总/保存结果再回显协议响应。删除只有在此链成功后才物理删除 Java 记录。

#### 3.2.3 `InterviewSessionPersistenceService.delete` 与 Mapper 函数

**文件与行号：** `java-backend/src/main/java/com/interviewguide/interview/service/InterviewSessionPersistenceService.java:163-170`。

1. 第 163 行声明事务。第 165 行通过 `requiredForUpdate` 以数据库锁读取 session；第 166 行 `assertOwner`。
2. 第 167 行按 sessionId 查询全部 turns。第 168 行非空时调用 `turnRepository.deleteAll(turns)`，先删子表。
3. 第 169 行调用 `sessionRepository.delete(session)` 删除父会话；第 170 行结束。Turn 查询对应 `InterviewTurnRepository.xml:5`，删除对应 Mapper XML；都是 MyBatis。

## 4. 主流构建分析

当前“活动会话先完成再删除”的做法避免 Python 留下活动状态，优点是状态机更完整；缺点是用户删除等待模型汇总，Python 故障会阻止删除，删除语义不够即时。

主流替代是“先逻辑删除/取消、异步清理”：事务中将 session 标为 DELETING/DELETED，发送取消或清理事件，后台撤销 Python 状态并物理删除。优点是 API 快、可重试；缺点是需要状态机、任务监控和查询过滤。

本项目若删除体验优先可采用逻辑删除。短期仍适合保留当前完整性优先实现；至少应在前端提示删除可能等待完成。若迁移，数据库查询必须默认过滤 DELETED，Worker 需按 sessionId/runId 幂等清理。
