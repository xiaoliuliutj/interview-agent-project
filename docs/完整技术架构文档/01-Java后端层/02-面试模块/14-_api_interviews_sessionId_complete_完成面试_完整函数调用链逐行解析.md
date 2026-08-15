# POST /api/interviews/{sessionId}/complete：完成面试的完整函数调用链

## 1. 接口定义

该接口让当前用户提前完成一场面试。Java 验证会话归属，已完成则直接返回；否则同步调用 Python `/v1/agent/sessions/complete`。Python 生成最终总结/评价（不可用时使用回退评价）、保存 Agent 会话并把长期记忆归档；Java 校验返回结果并写入 Java 会话的完成状态和最终评价。

| 项目 | 内容 |
| --- | --- |
| 方法/路径 | POST `/api/interviews/{sessionId}/complete` |
| Controller | `InterviewController.complete` |
| Python 终点 | POST `/v1/agent/sessions/complete` |
| 成功响应 | `ApiResult<Void>` |
| 幂等 | Java/Python 均对已完成会话直接返回 |

## 2. 函数调用链

~~~text
InterviewPage.handleCompleteEarly → interviewApi.completeInterview → request.post
 -> Axios/Filter/Controller.complete → UserIdentityResolver.require
 -> InterviewService.complete → ownedSession → Persistence.load
    ->（已 COMPLETED：return）
    -> HttpPythonAgentClient.complete → AgentCallExecutor.execute → post
    -> Python complete_session → _remember_request_context → _resolve_service
       -> InterviewAgentService.complete_session
          -> Repository.get → _validate_expected_state
          -> _report_progress → InterviewSummaryAgent.summarize 或 _fallback_evaluation
          -> Repository.save → MemoryService.finalize_session → _report_progress
       -> _success_response
    -> requireMatchingResponse → Persistence.completeFromAgent
       -> requiredForUpdate → assertOwner → Session.applyAgentResponse
       -> storeFinalEvaluation → Session.complete → Repository.save
 -> ApiResult.success
 -> InterviewPage.getSession → initSession
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `InterviewPage.handleCompleteEarly`

文件：`frontend/src/pages/InterviewPage.tsx:282-299`。

1. 第 283 行无 session 直接 return；第 285-286 行设置提交中与 SUMMARIZING。
2. 第 288 行 await completeInterview；第 289 行关闭确认框；第 290 行重新 getSession；第 291 行 initSession 更新完成报告。
3. 第 292-294 行显示失败信息；第 295-298 行 finally 恢复状态。

#### 3.1.2 `interviewApi.completeInterview` 与请求封装

文件：`frontend/src/api/interview.ts:94-96`；`api/request.ts:47-73、161-163`。

1. 第 94 行定义函数；第 95 行 POST `/api/interviews/${sessionId}/complete` 并等待 void；第 96 行结束。
2. request.post 第 161-163 行调用 Axios；createClientId/currentUserId 第 47-58 行生成请求 ID/读取 owner；拦截器第 64-73 行写两个请求头。
3. 响应拦截器第 123-155 行解包成功 ApiResult 或把网络、JSON/HTTP 失败转成 ApiRequestError。

### 3.2 Java 函数

#### 3.2.1 `InterviewController.complete` 与 `InterviewService.complete`

文件：`InterviewController.java:81-86`；`InterviewService.java:138-148`。

1. Controller 第 81 行映射路径；第 82-83 行绑定参数；第 84 行 require 后调用 service.complete；第 85 行返回 success(null)。
2. Service 第 139 行 ownedSession；第 140 行若状态已 COMPLETED 直接 return。
3. 第 141 行生成 runId；第 142-145 行构造 AgentCompleteRequest，携带上层状态和 Agent stateVersion；第 146 行校验响应；第 147 行交 persistence 写回。

#### 3.2.2 `HttpPythonAgentClient.complete`、响应校验、持久化完成

文件：`HttpPythonAgentClient.java:45、65-96`；`InterviewService.java:202-236`；`InterviewSessionPersistenceService.java:141-148`。

1. complete 第 45 行以 callExecutor 执行固定 complete 路径；post 第 65-79 行校验 DTO、POST、处理空响应和 HTTP/网络异常。
2. requireSuccess 第 202-215 行拒绝非 1xx；requireMatchingResponse 第 226-236 行再核对用户、会话、runId。
3. completeFromAgent 第 142 行锁定读取，143 行 assertOwner，144 行 applyAgentResponse，145 行 storeFinalEvaluation，146 行实体 complete，147 行 save。
4. storeFinalEvaluation 第 124-131 行只在 output 有 finalEvaluation 时 JSON 序列化；失败保留可完成会话。实体 complete（InterviewSessionEntity.java:113-116）写 COMPLETED 和更新时间。

### 3.3 Python 函数

#### 3.3.1 `complete_session` 路由

文件：`python-agent/app/api/application.py:132-153`。

1. 第 133 行定义路由；第 134 行保存上下文并解析服务。
2. 第 136-146 行按 operation 调 `pause_session` 或 `complete_session`；本接口 operation 为 `agent.session.complete`，因此进入 complete_session。
3. 第 147-153 行把 finalEvaluation（存在时）写 output 并以 _success_response 返回。

#### 3.3.2 `InterviewAgentService.complete_session`

文件：`python-agent/app/agents/interview/service.py:191-233`。

1. 第 197-201 行查 Agent 会话并校验 user；第 202-206 行已 COMPLETED/FAILED 时幂等返回或归档中断记忆。
2. 第 207-211 行验证 Java 状态/版本；第 213 行保存 expected_version；第 214-218 行报 SUMMARIZING、标 COMPLETED、生成回退摘要。
3. 第 219-225 行有总结 Agent 和 turn 时调用 summarize；异常只记日志，不使会话无法完成。
4. 第 226-230 行确保 final_evaluation、清 RAG 缓存并以乐观锁保存；第 231 行 finalize_session；第 232 行报 COMPLETED；第 233 行返回。

#### 3.3.3 `InterviewSummaryAgent.summarize`、回退与记忆归档

文件：`agents/interview/agent.py:352-362`；`service.py:873-900`；`memory/service.py:146-175`。

1. summarize 第 353-357 行把难度、计划、全部 turns 序列化；第 358-362 行以 summary prompt 调 StructuredOutputInvoker，得到 InterviewSummary。
2. _fallback_summary 第 873-877 行按 interrupted 和回合数产生文本；_fallback_evaluation 第 880-900 行无回合返回零分说明，有回合计算平均分、截取优缺点并构造可用报告。
3. finalize_session 第 147-151 行读记忆并去重；第 153-168 行汇总分数/优缺点、更新长期摘要和已完成会话 ID；第 169-175 行乐观锁保存，并在冲突时确认是否已归档。

## 4. 审核结论

1. 已覆盖前端确认、Java 会话授权、同步 Python 总结、Java 最终评价写回和前端重新读取详情。
2. 每个可达项目函数均包含文件、行号与逐句说明；已完成分支不重复调用 Python。
