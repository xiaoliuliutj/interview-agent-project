# GET /api/interviews/{sessionId}：查询面试详情的完整函数调用链

> 对应接口汇总第 10 项。详情由 Java 已持久化的会话和回合数据组成，不访问 Python 实时状态，也不调用 Python Agent。

## 1. 接口定义

### 1.1 功能与作用

接口按会话 ID 返回当前用户的一场文字面试详情：一份 InterviewView 会话快照，以及按创建时间排序的 InterviewTurnView 回合数组。回合中的问题、回答、评分和评语都是 Java 在提交答案流程中已保存的数据。该接口只读，不会继续出题、评价、暂停或完成会话。

### 1.2 基本信息

| 项目 | 内容 |
| --- | --- |
| HTTP 方法 | GET |
| 路径 | `/api/interviews/{sessionId}` |
| Controller | `InterviewController.get` |
| 返回 | `ApiResult<InterviewDetailView>` |
| 身份头 | `X-User-Id` |
| Repository | 会话按主键查询；回合按 createdAt 升序查询 |
| Python 调用 | 无 |

## 2. 函数调用链

~~~text
InterviewPage.resumeExistingSession（或完成后刷新）
 -> interviewApi.getSession
 -> request.get → Axios 拦截器 → currentUserId/createClientId
 -> RequestIdFilter.doFilterInternal → normalize
 -> SimpleRateLimitFilter.doFilterInternal
 -> InterviewController.get
    -> UserIdentityResolver.require
    -> InterviewService.detail
       -> InterviewService.ownedSession
          -> InterviewSessionPersistenceService.load
             -> InterviewSessionRepository.findById
          -> InterviewSessionEntity.getUserId
       -> InterviewSessionPersistenceService.turns
          -> InterviewTurnRepository.findBySessionIdOrderByCreatedAt
       -> InterviewTurnEntity getter（每条回合）
       -> InterviewService.toView
          -> parseFinalEvaluation → InterviewSessionEntity getter
 -> ApiResult.success → Axios 响应拦截器
 -> interviewApi.toSession → InterviewPage.initSession
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `InterviewPage.resumeExistingSession`

文件：`frontend/src/pages/InterviewPage.tsx:148-167`。

1. 第 148 行定义恢复函数，参数是已有 sessionId；第 149-150 行进入创建中状态并清空错误。
2. 第 153 行 await `interviewApi.getSession(sessionId)`；第 154 行将结果传给 initSession。
3. 第 157-160 行读取当前问题；若当前题已存在用户答案则回填输入框。
4. 第 161-163 行把失败转换为可显示文本；第 164-166 行 finally 恢复创建状态。

#### 3.1.2 `interviewApi.getSession` 与 `toSession`

文件：`frontend/src/api/interview.ts:41-51、73-76`。

1. getSession 第 73 行声明返回 InterviewSession；第 74 行 GET `/api/interviews/${sessionId}`；第 75 行把 detail.session 和 detail.turns 传入 toSession；第 76 行结束。
2. toSession 第 42 行把回合逐条映射为 `toQuestion`；第 43-45 行仅在 ACTIVE/PAUSED 时追加 currentQuestion。
3. 第 46-50 行展开会话字段、计算 currentQuestionIndex 并返回前端状态对象。
4. toQuestion 位于第 34-39 行：第 35 行构造对象，第 36-38 行把 index、问题、stage、回答、评语和分数逐项映射。

#### 3.1.3 通用请求、响应函数

文件：`frontend/src/api/request.ts:47-73、75-155、157-160`。

1. request.get 第 158-160 行调用 Axios GET 并取 data。
2. createClientId 第 47-50 行生成 UUID 或兼容 ID；currentUserId 第 52-58 行从 localStorage 读取或首次生成用户 ID。
3. 请求拦截器第 64-73 行创建 header setter、写入 X-User-Id 和 X-Request-Id。
4. 成功响应拦截器第 123-135 行识别 ApiResult code=200 并在第 128 行解包 data；失败回调第 136-155 行使用 decodeErrorData、parseApiError、transportError 生成 rejected Promise。

### 3.2 Java HTTP 与授权函数

#### 3.2.1 `RequestIdFilter` 与 `SimpleRateLimitFilter`

文件：`infrastructure/web/RequestIdFilter.java:23-41`；`infrastructure/ratelimit/SimpleRateLimitFilter.java:38-61`。

1. RequestIdFilter 第 25 行 normalize 请求 ID；第 26-28 行写 attribute/header/MDC；第 30 行放行；第 31-33 行 finally 清理。normalize 第 36-41 行校验字符/长度或生成 UUID。
2. RateLimitFilter 第 44-48 行按 IP、URI、分钟计算并增加计数；超过限制时第 49-58 行写 429，正常时第 60 行进入 Controller。

#### 3.2.2 `InterviewController.get` 与 `UserIdentityResolver.require`

文件：`InterviewController.java:53-57`；`common/security/UserIdentityResolver.java:14-19`。

1. 第 53 行映射 `/{sessionId}`；第 54-55 行绑定路径和身份头。
2. 第 56 行先 require，再调用 `interviewService.detail(sessionId,owner)`，并用 ApiResult.success 包装。
3. require 第 15-17 行拒绝空用户 ID，第 18 行 strip，第 19 行返回 owner。

### 3.3 Java 会话与回合读取函数

#### 3.3.1 `InterviewService.detail`

文件：`java-backend/src/main/java/com/interviewguide/interview/service/InterviewService.java:112-123`。

1. 第 113 行调用 ownedSession 完成存在和所有权校验。
2. 第 114-115 行调用 persistence.turns 查询已保存回合。
3. 第 116 行用 IntStream 产生从 0 起的展示 index；第 117-121 行对每个 turn 调 getter 并构造 InterviewTurnView。
4. 第 122 行把 toView(session) 和 indexedTurns 构造成 InterviewDetailView；第 123 行结束。

#### 3.3.2 `ownedSession`、`load` 与 `turns`

文件：`InterviewService.java:185-191`；`InterviewSessionPersistenceService.java:179-186`。

1. ownedSession 第 186 行调用 persistence.load；第 187-189 行把 session.getUserId 与请求 owner 比较，越权抛 SESSION_ACCESS_DENIED；第 190 行返回。
2. load 第 183-186 行调用 sessionRepository.findById，Optional 为空时抛 SESSION_NOT_FOUND。
3. turns 第 179-181 行调用 `turnRepository.findBySessionIdOrderByCreatedAt(sessionId)`；该项目 Repository 声明按创建时间升序读取回合。

#### 3.3.3 `InterviewTurnEntity` getter

文件：`java-backend/src/main/java/com/interviewguide/interview/domain/InterviewTurnEntity.java:54-63`。

1. getStage 第 54 行返回阶段；getSessionId 第 57 行返回所属会话。
2. getQuestion 第 59 行返回被回答的问题；getCandidateAnswer 第 60 行返回候选人回答；getCreatedAt 第 61 行返回创建时间。
3. getEvaluationSummary 第 62 行和 getScore 第 63 行返回 Java 保存的评价信息。各函数都是单句 return，无 Python、数据库或状态副作用。

#### 3.3.4 `toView` 与 `parseFinalEvaluation`

文件：`InterviewService.java:238-255`。

1. toView 第 239 行先 parseFinalEvaluation；第 240-246 行以各 `InterviewSessionEntity` getter 构造 InterviewView。
2. parseFinalEvaluation 第 250 行对 null/blank 返回 null；第 251 行 ObjectMapper 解析 JSON Map；第 252-254 行解析失败也返回 null，防止损坏报告阻塞详情读取。
3. Session getter 位于 `InterviewSessionEntity.java:118-137`，每一行仅返回对应 id、配置、状态、题目、计数、时间或 JSON 字段。

#### 3.3.5 `ApiResult.success` 与 Python 边界

文件：`common/web/dto/ApiResult.java:3-6`。

1. 第 4-5 行构造 code=200、message=success、data=InterviewDetailView 的 record。
2. `detail` 的下游仅有 persistence.load、persistence.turns、toView；源码未出现 pythonAgentClient、HttpPythonAgentClient 或 `/v1/agent` 调用。
3. 因此本接口的 Java→Python 调用次数为零；实时 Python 进度仅由另一个 `/agent-status` 接口读取。

## 4. 审核结论

1. 已覆盖前端恢复入口、请求封装、Java 身份校验、会话/回合查询和详情 DTO 构造。
2. 每个可达项目函数均标注源码文件和行号，并按语句说明。
3. 已确认接口只读 Java 历史数据，不会调用 Python。
