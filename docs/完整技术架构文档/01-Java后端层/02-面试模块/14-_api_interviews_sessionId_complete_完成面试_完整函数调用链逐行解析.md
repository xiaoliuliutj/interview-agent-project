# POST /api/interviews/{sessionId}/complete：完成面试完整函数调用链逐行解析

> 当前实现同步调用 Python 完成会话，不经 RabbitMQ。Java 只有在 Python 成功回显匹配身份后才把会话标为 COMPLETED 并保存最终评价 JSON。

## 1. 接口定义

### 1.1 功能与作用

`POST /api/interviews/{sessionId}/complete` 供用户提前结束或完成面试。它读取/授权会话，已经 COMPLETED 时立即成功返回；否则请求 Python 汇总评价并把结果写入 Java 会话。响应 data 为 null，最终报告通过详情/导出接口读取。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 路径 | `POST /api/interviews/{sessionId}/complete` |
| Controller | `InterviewController.complete`，`InterviewController.java:81-86` |
| Python | `POST /v1/agent/sessions/complete`，operation=`agent.session.complete` |
| 状态 | 已完成直接返回；成功写 COMPLETED 与 final_evaluation_json |
| MQ/Redis | 不投 RabbitMQ；Python 可更新其进度缓存。|

### 1.3 前端入口

`frontend/src/pages/InterviewPage.tsx:282-` 的 `handleCompleteEarly` 调用 `interviewApi.completeInterview`；API 位于 `frontend/src/api/interview.ts:94-96`。

## 2. 函数调用链

```text
InterviewPage.handleCompleteEarly -> interviewApi.completeInterview -> request.post
  -> Axios interceptor -> RequestIdFilter -> SimpleRateLimitFilter -> IdempotencyFilter
  -> InterviewController.complete -> UserIdentityResolver.require -> InterviewService.complete
     -> ownedSession -> sessionPersistence.load / owner check
     -> HttpPythonAgentClient.complete -> AgentCallExecutor -> post
        -> Python complete_session -> InterviewAgentService.complete_session
     -> requireMatchingResponse -> requireSuccess / firstNonBlank
     -> InterviewSessionPersistenceService.completeFromAgent
        -> requiredForUpdate -> assertOwner -> applyAgentResponse -> storeFinalEvaluation
        -> InterviewSessionEntity.complete / advanceStateVersion -> sessionRepository.save
  -> ApiResult.success(null)
```

## 3. 函数解析

### 3.1 前端与 Java Web 函数

#### 3.1.1 `handleCompleteEarly`、API 和请求拦截器

**文件与行号：** `frontend/src/pages/InterviewPage.tsx:282-`，`frontend/src/api/interview.ts:94-96`，`frontend/src/api/request.ts:47-72、123-154`。

1. 页面函数在用户确认后调用 complete API，并在成功后刷新/切换会话展示；异常经页面错误状态显示。
2. API 第 94 行声明函数，第 95 行 POST 完成路径，第 96 行结束。
3. `createClientId`/`currentUserId` 第 47-57 行提供身份，拦截器第 64-72 行写请求头；成功/错误拦截器第 123-154 行处理 Java 统一响应。

#### 3.1.2 RequestId、限流、幂等和 Controller

**文件与行号：** `RequestIdFilter.java:23-41`、`SimpleRateLimitFilter.java:48-82`、`IdempotencyFilter.java:41-96`，根目录为 `java-backend/src/main/java/com/interviewguide/infrastructure/`；`InterviewController.java:81-86`。

1. RequestId filter 第 25-33 行规范/回传 ID、写 MDC、放行并 finally 清理；`normalize` 第 36-41 行无效时生成 UUID。
2. 限流第 54-67 行使用 Redis INCR 或本机回退，第 69-79 行超限 429。POST 默认无幂等头会被 `shouldNotFilter` 跳过；提供头时 `doFilterInternal` 会占位并对重复返回 409。
3. Controller 第 81 行映射路径，第 82-83 行绑定参数，第 84 行 require 后调用服务，第 85 行返回 success(null)，第 86 行结束。

### 3.2 Java 与 Python 完成函数

#### 3.2.1 `InterviewService.complete`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/interview/service/InterviewService.java:138-148`。

1. 第 139 行用 `ownedSession` 读取并授权。第 140 行已经 COMPLETED 时立即 return，保证重复点击不重复调用 Python。
2. 第 141 行生成本次 runId。第 142-145 行构造 Python completion 请求，包含协议、身份、当前状态与 stateVersion。
3. 第 146 行调用 `requireMatchingResponse`。第 147 行调用持久化完成；第 148 行结束。

#### 3.2.2 `HttpPythonAgentClient.complete`、重试和 Python `complete_session`

**文件与行号：** `HttpPythonAgentClient.java:45、65-96`，`AgentCallExecutor.java:22-43`，`python-agent/app/api/application.py:135-`。

1. Java client 第 45 行经 `AgentCallExecutor.execute` POST Python。`post` 第 65-79 行校验/调用/转换远端异常；`execute` 第 22-34 行只对 retryable 异常重试，`sleepBeforeRetry` 第 36-43 行处理中断。
2. Python 路由第 135 行注册 complete；读取 payload、保存请求上下文、解析服务。若 operation 是 pause 则走暂停分支，否则调用 `complete_session`，将结果回显成 AgentResponse。
3. Python InterviewAgentService 的 `complete_session` 负责汇总最终评价、保存会话与进度。模型/依赖异常通过 Python 统一异常处理返回带 retryable 属性的协议错误。

#### 3.2.3 响应校验与 `completeFromAgent`

**文件与行号：** `InterviewService.java:202-237`，`InterviewSessionPersistenceService.java:143-152、126-133`。

1. `requireSuccess` 第 202-217 行只接受 100–199 code；`firstNonBlank` 第 219-224 行选择错误文本；`requireMatchingResponse` 第 226-237 行校验 user/session/run 完全相同。
2. `completeFromAgent` 第 143 行事务开始。第 145 行锁定会话；第 146 行再次 assertOwner；第 147 行应用 Python 状态/答案/版本/stage。
3. 第 148 行调用 `storeFinalEvaluation`。该函数第 126-127 行空 output 直接返回，第 128-129 行 JSON 序列化 finalEvaluation，第 130-132 行格式异常时保留已完成会话。
4. 第 149 行调用实体 `complete`，第 150 行推进 Java stateVersion，第 151 行 MyBatis 保存，第 152 行结束。

## 4. 主流构建分析

同步完成优点是用户点击后立即得到稳定最终状态与报告；缺点是总结模型调用可能较慢，用户网络中断时需要靠重试/详情确认真实完成状态。

主流改进可把完成设计为命令任务：先持久化 `COMPLETING`，异步 Worker 生成报告，前端订阅状态。优点是长汇总不阻塞 HTTP，能稳定重试；缺点是需要新状态、事件和等待体验。

本项目可保留同步完成并增加 `COMPLETING`/runId 查询作为渐进改进：若客户端超时，详情返回该状态，前端轮询；任务量高时再把同一 runId 放入 Outbox/Worker，保持当前身份回显与版本检查。
