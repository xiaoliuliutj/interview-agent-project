# POST /api/interviews/{sessionId}/answers：提交回答、评价并生成下一题的完整函数调用链

> 对应接口汇总第 13 项。该接口是同步 Java→Python 工作流：浏览器等待 Python 评价、路由和下一题生成完成后，Java 才持久化本回合并返回完整详情。`runId` 同时承担浏览器、Java、Python 三层的幂等键。

## 1. 接口定义

### 1.1 功能与作用

接口提交当前题回答。Java 验证 `runId`、会话归属和状态，向 Python `/v1/agent/respond` 传递上层会话状态版本。Python 对回答评分、按约束路由、必要时检索出题资料并生成下一题，随后以乐观锁保存 Agent 会话与长期记忆。Java 校验 Python 响应、按 runId 去重保存 InterviewTurn、更新会话状态和计数，最后重新读取完整详情。

### 1.2 基本信息

| 项目 | 内容 |
| --- | --- |
| HTTP 方法 | POST |
| 路径 | `/api/interviews/{sessionId}/answers` |
| 请求体 | `SubmitInterviewAnswerRequest { answer, runId }` |
| Java→Python | POST `/v1/agent/respond` |
| 成功响应 | `ApiResult<InterviewDetailView>` |
| 幂等规则 | 同一 runId 只能对应同一会话、同一回答；重试返回已保存快照 |
| 可处理状态 | ACTIVE、PAUSED（PAUSED 会在 Python 恢复 ACTIVE） |

## 2. 函数调用链

~~~text
InterviewPage.handleSubmitAnswer
 -> createClientId("answer-run") / pendingAnswerStorageKey
 -> interviewApi.submitAnswer → request.post → Axios 拦截器
 -> RequestIdFilter → SimpleRateLimitFilter
 -> InterviewController.submitAnswer → UserIdentityResolver.require
 -> InterviewService.submitAnswer
    -> ownedSession → Persistence.load → Session.getUserId
    -> HttpPythonAgentClient.respond → AgentCallExecutor.execute → post/validateRequest
    -> Python respond → _remember_request_context → _resolve_service
       -> InterviewAgentService.submit_answer_for_run → _submit_answer
          -> Repository.get → 幂等 run_snapshots / _synchronize_turn_memory
          -> _validate_expected_state → MemoryService.build_context
          -> _run_interview_node → InterviewEvaluationAgent.evaluate
          ->（OPENING）_replan_after_opening → InterviewPlanner.create_plan
          -> _allowed_actions / _next_stage → InterviewRoutingAgent.route → _enforce_route_limits
          -> _record_turn → _compact_session_history → _apply_route
          -> MemoryService.build_context → _question_evidence → InterviewQuestionAgent.generate
          -> _register_question → Repository.save → MemoryService.record_turn
          -> _candidate_visible_output → _success_response
    -> requireMatchingResponse
    -> InterviewSessionPersistenceService.applyAnswer
       -> requiredForUpdate → TurnRepository.findByRunId
       -> InterviewTurnEntity 构造/setter → Repository.save
       -> Session.applyCounters/applyAgentResponse → SessionRepository.save
    -> load → toView
 -> InterviewService.detail（Controller 第二次调用）→ turns → toView
 -> ApiResult.success → Axios 解包 → toSession → InterviewPage.initSession
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `InterviewPage.handleSubmitAnswer`

文件：`frontend/src/pages/InterviewPage.tsx:226-280`。

1. 第 227 行拒绝空白 answer、无 session 或无 currentQuestion；不会发请求。
2. 第 230-232 行设置提交中、显示 EVALUATING 并清空旧错误；第 233 行 trim 得到真正提交文本。
3. 第 234-246 行读取 pending ref，若 session、问题和文本相同则复用旧 runId；否则第 241-246 行构造新对象，调用 `createClientId("answer-run")`。
4. 第 247-248 行把 submission 同时写入 ref 和 sessionStorage，使网络失败后的重试保持同一幂等键。
5. 第 250-258 行仅在非重试时把用户消息加入 UI，并删除可能存在的旧 runId 消息。
6. 第 260-264 行 await `interviewApi.submitAnswer`；成功后第 266-268 行清 ref、删除 sessionStorage、清输入框；第 272 行调用 initSession 重建完整对话。
7. 第 273-279 行失败时显示错误，finally 恢复提交状态和 IDLE。

#### 3.1.2 `pendingAnswerStorageKey`、`loadPendingAnswerSubmission`

文件：`InterviewPage.tsx:29-46`。

1. pendingAnswerStorageKey 第 29-31 行以 sessionId 生成 sessionStorage 键，隔离不同会话未完成提交。
2. loadPendingAnswerSubmission 第 33 行定义读取函数；第 35 行读 storage；第 36 行无值返回 null。
3. 第 37 行 JSON.parse；第 38-41 行逐项验证 sessionId、question、answer、runId 类型和值；无效返回 null。
4. 第 42 行返回类型收窄后的 submission；第 43-45 行捕获解析/存储异常返回 null。

#### 3.1.3 `interviewApi.submitAnswer`

文件：`frontend/src/api/interview.ts:78-87`。

1. 第 79-82 行 POST 回答和 runId，并将 timeout 设为 180 秒以容纳下层模型处理。
2. 第 83 行把 Java 返回的 session/turns 调 toSession；第 84-85 行仅在 ACTIVE 且存在题目时选择最后一题为 nextQuestion。
3. 第 86 行返回 session、hasNextQuestion 和 nextQuestion；第 87 行结束。

#### 3.1.4 `request.post` 和请求/响应拦截器

文件：`frontend/src/api/request.ts:47-73、123-163`。

1. createClientId 第 47-50 行生成 UUID 或兼容随机 ID；currentUserId 第 52-58 行读取或保存稳定用户标识。
2. 请求拦截器第 64-73 行设置 headers，写入 X-User-Id 和独立 X-Request-Id。
3. request.post 第 161-163 行调用 instance.post 后取 response.data。
4. 成功拦截器第 123-135 行把 ApiResult 的 data 解包；失败拦截器第 136-155 行使用 `decodeErrorData`、`parseApiError`、`transportError` 生成拒绝 Promise。

### 3.2 Java Controller、Service 与持久化函数

#### 3.2.1 `InterviewController.submitAnswer`

文件：`java-backend/src/main/java/com/interviewguide/interview/controller/InterviewController.java:72-79`。

1. 第 72 行映射 `/{sessionId}/answers`；第 73-75 行绑定路径、@Valid 请求体和身份头。
2. 第 76 行 require 用户 ID 并保存 owner；第 77 行调用 Service 提交答案。
3. 第 78 行再次调用 `interviewService.detail(sessionId,owner)`，确保 HTTP 响应带 Java 已持久化的回合；第 79 行结束。

#### 3.2.2 `InterviewService.submitAnswer`、`ownedSession` 与响应校验

文件：`InterviewService.java:89-105、185-191、202-236`。

1. 第 90-92 行要求 runId 非空，否则抛 RUN_ID_REQUIRED。
2. 第 93 行 ownedSession；第 94-97 行仅允许 ACTIVE/PAUSED，否则抛 SESSION_NOT_ACTIVE。
3. 第 98-101 行构造 AgentRespondRequest：UUID requestId、客户端 runId、上层状态名、Agent stateVersion、回答和时间。
4. 第 102 行 requireMatchingResponse，先 requireSuccess 检查 code 为 1xx，再比较 userId/sessionId/runId；第 103 行持久化回答；第 104 行重新 load/toView。
5. ownedSession 第 186-190 行 load 后比较 getUserId，越权抛 SESSION_ACCESS_DENIED。

#### 3.2.3 `HttpPythonAgentClient.respond`、`post` 与重试

文件：`HttpPythonAgentClient.java:44、65-96`；`AgentCallExecutor.java:22-43`。

1. respond 第 44 行把固定路径 `/v1/agent/respond` 的 post lambda 交给 execute。
2. post 第 66 行先 validateRequest；第 68 行 RestClient POST 并反序列化 AgentResponse；第 69-70 行拒绝空响应。
3. 第 71-79 行保留已有异常、尝试 parseStructuredError 或封装 HTTP/网络异常；validateRequest 第 89-95 行列出 Bean Validation 违规字段并抛不可重试异常。
4. execute 第 22-34 行只对可重试 PythonAgentException 延迟后重试；sleepBeforeRetry 第 36-43 行处理中断。

#### 3.2.4 `InterviewSessionPersistenceService.applyAnswer`

文件：`InterviewSessionPersistenceService.java:57-122`。

1. 第 63 行悲观锁读取会话；第 64 行按 runId 查旧 turn。
2. 第 65-73 行已存在时验证 sessionId 与 candidateAnswer；相同即 return（Java 幂等），不同抛 RUN_ID_PAYLOAD_MISMATCH。
3. 第 79-82 行对新 runId 比较 JPA stateVersion，旧版本抛 SESSION_CONCURRENT_MODIFICATION。
4. 第 84-92 行要求 Python 返回合法 turnStage。
5. 第 94-96 行构造 InterviewTurnEntity 并写 stage；第 97-114 行读取 candidate 可见 output，写评语/分数/JSON/计数，JSON 序列化失败仅忽略可选报告字段。
6. 第 115 行 save turn；第 116 行 applyAgentResponse；第 117 行 save session。
7. number 第 120-122 行只把 Number 转 Integer，其他类型返回 null。

### 3.3 Python 路由与答案工作流

#### 3.3.1 `respond`、`_candidate_response_output`

文件：`python-agent/app/api/application.py:93-130、423-432`。

1. 第 95 行保存请求上下文；第 96 行取服务。
2. 第 98-108 行以 150 秒 wait_for 调 submit_answer_for_run，传入用户、会话、回答、runId 和 Java 状态/版本。
3. 第 109-121 行超时或异常时标记进度 FAILED 并抛 AgentDependencyError/原异常。
4. 第 122-130 行把 session、snapshot、白名单 output 传给 _success_response。
5. _candidate_response_output 第 425-432 行空 output 返回 None，否则仅保留评价、分数、优缺点、计数、预算和最终评价，阻止记忆、RAG 证据、路由理由泄露给 Java。

#### 3.3.2 `submit_answer_for_run` 与 `_submit_answer`

文件：`python-agent/app/agents/interview/service.py:256-396`。

1. submit_answer_for_run 第 266-273 行是参数不变的 public 委托，统一进入 _submit_answer。
2. _submit_answer 第 285-289 行读取 Agent 会话并校验 user；第 290-297 行命中 run_snapshots 时验证回答、调用 _synchronize_turn_memory 后直接返回旧快照。
3. 第 298-305 行校验 Java 传入的会话状态/版本，并仅允许 ACTIVE/PAUSED；第 307-310 行把 PAUSED 恢复为 ACTIVE。
4. 第 311-317 行读取记忆上下文并以 _run_interview_node 调 EvaluationAgent；OPENING 时第 318-321 行重规划。
5. 第 322-333 行计算 allowed actions/next stage，调用 RoutingAgent，并经 _enforce_route_limits 把模型决策收敛到硬规则。
6. 第 335-337 行记录回合、压缩历史、应用路由；第 342 行构建下一题上下文。
7. 第 343-356 行若已完成则生成总结/回退评价；第 357-374 行未完成时获取 evidence、生成题目、登记问题与缓存证据。
8. 第 375-388 行构造幂等 snapshot 并按策略淘汰旧 snapshot；第 389 行乐观锁保存会话；第 390-395 行写长期记忆、必要时 finalize 并更新进度；第 396 行返回。

#### 3.3.3 状态、路由与回合辅助函数

文件：`service.py:405-467、499-557、628-746、847-870`。

1. _validate_expected_state 第 411-417 行同时比较 status/state_version，不一致抛 ConsistencyError。
2. _allowed_actions 第 422-467 行根据开场、总题数、阶段上限、回答弱点、算法特殊规则，返回 FOLLOW_UP/NEXT_QUESTION/NEXT_STAGE/END_INTERVIEW 的允许集合。
3. _enforce_route_limits 第 508-557 行拒绝超预算或模型给出的非法 action，修正追问主题、每主题上限、下一题和下一阶段路由。
4. _next_stage 第 628-634 行从 workflow 当前阶段后寻找可用阶段；_record_turn 第 652-667 行构造 TurnRecord 并 append。
5. _compact_session_history 第 705-716 行把较早回合压缩到最多 2000 字符；_apply_route 第 721-746 行按 action 更新题数、追问数、阶段或完成会话。
6. _register_question 第 678-687 行去重登记问题、阶段/主题计数；_candidate_visible_output 第 855-870 行只返回候选人可见字段。

#### 3.3.4 评价、路由、出题与记忆函数

文件：`agents/interview/agent.py:188-339`；`memory/service.py:99-144`。

1. EvaluationAgent.evaluate 第 194-213 行组装当前题、回答、缓存证据和记忆；第 217-225 行取 interview-coach skill、渲染 evaluation prompt、StructuredOutputInvoker 调模型并校验 InterviewEvaluation。
2. RoutingAgent.route 第 252-288 行组装评分、阶段计划、allowedActions、计数和记忆，解析选中 skills、渲染 routing prompt，调用结构化模型返回 InterviewRoute。
3. QuestionAgent.generate 第 304-339 行要求非空 next_topic，解析技能/提示，传入题目目录、记忆和 RAG evidence，调用结构化模型并返回 question。
4. MemoryService.build_context 第 100-120 行读长期记忆；无记忆时构造空上下文，有记忆时选择当前简历快照并返回技术栈、偏好、弱点和最近回合。
5. record_turn 第 123-144 行以 turn_id 去重，更新历史摘要、问题目录、弱点、优势、偏好，保存并在并发冲突时读最新记录确认是否已经写入。

## 4. 审核结论

1. 已覆盖浏览器 runId 重试、Java 校验/事务、同步 Python 评分路由出题、Python 会话与记忆幂等、Java turn 与会话写回、详情重读。
2. 每个调用链中的项目函数均标有文件、行号和逐句解释；框架模型、JPA、RestClient 仅按项目调用边界说明。
3. 本接口的 Python 调用终点不是仅一个 HTTP 路由，而是 `respond → submit_answer_for_run → 评价/路由/出题/持久化` 的完整同步路径。
