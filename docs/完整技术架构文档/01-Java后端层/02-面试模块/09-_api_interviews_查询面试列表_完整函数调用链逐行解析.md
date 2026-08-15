# GET /api/interviews：查询文字面试列表完整函数调用链逐行解析

> 当前实现的列表接口只查询 Java PostgreSQL，并不调用 Python、RabbitMQ 或面试 Redis 缓存。它按照 `user_id` 和创建时间降序读取会话，再转换为前端视图。

## 1. 接口定义

### 1.1 功能与作用

`GET /api/interviews` 返回当前用户的所有文字面试会话，包含会话状态、简历 ID、方向、难度、当前题、题目计数、最终评价和时间。面试历史页以它展示、继续、删除或导出面试；面试入口页也取其最近五条。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 方法与路径 | `GET /api/interviews` |
| Controller | `InterviewController.list`，`java-backend/src/main/java/com/interviewguide/interview/controller/InterviewController.java:47-51` |
| 身份 | `X-User-Id`，Controller 先 `identity.require` |
| 数据 | `interview_sessions`，按 `user_id`、`created_at DESC` |
| Python/MQ | 无调用；列表查询不恢复或推进 Agent。|

### 1.3 前端入口

历史页 `frontend/src/pages/InterviewHistoryPage.tsx:34-48` 的 `load` 在挂载时调用 `interviewApi.listSessions`；面试中心也在 `InterviewHubPage.tsx:34` 调用并取前五项。

## 2. 函数调用链

```text
InterviewHistoryPage.load -> interviewApi.listSessions -> request.get
  -> Axios request interceptor -> currentUserId / createClientId
  -> RequestIdFilter.doFilterInternal -> normalize
  -> SimpleRateLimitFilter.doFilterInternal -> JavaRedisStore.incrementInFixedWindow
     ->（Redis 故障）ConcurrentHashMap 回退
  -> IdempotencyFilter.shouldNotFilter（GET，跳过）
  -> InterviewController.list -> UserIdentityResolver.require -> InterviewService.list
     -> InterviewSessionPersistenceService.list
        -> InterviewSessionRepository.findByUserIdOrderByCreatedAtDesc -> MyBatis XML SQL
     -> InterviewService.toView -> parseFinalEvaluation
  -> ApiResult.success -> Axios response interceptor -> InterviewHistoryPage.setSessions
```

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `InterviewHistoryPage.load` 与 Effect

**文件与行号：** `frontend/src/pages/InterviewHistoryPage.tsx:34-48`。

1. 第 34 行通过 `useCallback` 创建异步 load。第 35 行先设置加载状态。
2. 第 36-38 行调用 `interviewApi.listSessions`，成功后写 `sessions` 并清空旧错误。
3. 第 39-40 行捕获请求错误，优先展示 Error.message，否则使用固定兜底文本。第 41-43 行 finally 关闭 loading。
4. 第 46-48 行的 Effect 在挂载/函数变化时 `void load()`，不让 React 等待 Promise。

#### 3.1.2 `interviewApi.listSessions` 与 `request` 函数

**文件与行号：** `frontend/src/api/interview.ts:55-58`，`frontend/src/api/request.ts:47-72、123-164`。

1. `listSessions` 第 56-57 行调用泛型 `request.get<InterviewView[]>('/api/interviews')` 并返回 Promise。
2. `createClientId` 第 47-49 行生成 UUID/兼容回退；`currentUserId` 第 52-57 行读写 localStorage。
3. 请求拦截器第 64-72 行确保 headers、写入用户 ID 与 request ID。`request.get` 第 158-160 行调用 Axios 并返回 data。
4. 成功拦截器第 123-135 行对 `code=200` 解包；错误拦截器第 136-154 行将网络、统一业务体和 HTTP 失败转换为前端错误。

### 3.2 Java Web 保护和入口函数

#### 3.2.1 `RequestIdFilter`、限流与幂等跳过

**文件与行号：** `RequestIdFilter.java:23-41`、`SimpleRateLimitFilter.java:48-82`、`IdempotencyFilter.java:41-44`，均位于 `java-backend/src/main/java/com/interviewguide/infrastructure/`。

1. RequestId 过滤器第 25-33 行规范/回传 ID、写 MDC、放行并清理；`normalize` 第 36-41 行非法时新建 UUID。
2. 限流函数第 54-67 行调用 `JavaRedisStore.incrementInFixedWindow`。底层第 31-39 行使用 INCR/TTL，故障返回空 Optional；过滤器改用本机窗口。第 69-79 行超限返回 429，第 81 行放行。
3. 幂等 `shouldNotFilter` 第 42-44 行只处理有键写请求；本 GET 不执行占位或 409 分支。

#### 3.2.2 `InterviewController.list`、`UserIdentityResolver.require` 和 `ApiResult.success`

**文件与行号：** `InterviewController.java:47-51`，`common/security/UserIdentityResolver.java:14-19`，`common/web/dto/ApiResult.java:3-6`。

1. Controller 第 47 行映射 GET；第 48-49 行绑定用户头；第 50 行先 require 再调用 `interviewService.list` 并包为 success；第 51 行结束。
2. `require` 第 15-17 行拒绝空值，第 18 行 strip，第 19 行返回 owner。
3. `success` 第 4-5 行构造 code 200/message success/data，第 6 行结束。

### 3.3 Java 查询、MyBatis 和视图函数

#### 3.3.1 `InterviewService.list`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/interview/service/InterviewService.java:130-132`。

1. 第 130 行声明按 userId 查询的函数。第 131 行调用 `sessionPersistence.list(userId)`，然后在流中对每个实体调用 `toView` 并 `toList`。
2. 第 132 行结束。服务不会调用 `pythonAgentClient`，因此无 Python 会话恢复或副作用。

#### 3.3.2 `InterviewSessionPersistenceService.list` 与 Mapper SQL

**文件与行号：** `java-backend/src/main/java/com/interviewguide/interview/service/InterviewSessionPersistenceService.java:172-174`，`InterviewSessionRepository.java:17`，`resources/mapper/interview/InterviewSessionRepository.xml:6`。

1. 第 172 行声明持久化查询。第 173 行直接调用 Mapper；第 174 行结束。
2. Mapper 接口声明按 user ID 列表查询；XML 第 6 行执行 `WHERE user_id=#{userId} ORDER BY created_at DESC`。过滤条件在 SQL 中完成，不读取其他用户会话。

#### 3.3.3 `InterviewService.toView` 与 `parseFinalEvaluation`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/interview/service/InterviewService.java:238-257`。

1. `toView` 第 238 行声明转换函数。第 239-247 行按 `InterviewView` 构造器顺序复制会话 ID、用户/候选人/简历/JD、方向、难度、题数、状态、Agent 状态版本、当前题、阶段和题目计数。
2. 第 247 行调用 `parseFinalEvaluation`，第 248 行复制创建/更新时间并结束构造。
3. `parseFinalEvaluation` 第 249 行声明；第 250 行将空值返回 `Map.of()`；第 251-252 行用 ObjectMapper 反序列化；第 253-255 行任何解析异常也返回空 Map；第 257 行结束。列表不会因历史 JSON 损坏失败。

## 4. 主流构建分析

当前实现是单表按用户读取后在服务层转换视图。优点是查询简单、用户隔离明确、不会把列表流量转化为 Agent 负载；缺点是没有分页，长期面试历史会让响应体变大，最终评价 JSON 在每项转换时都要反序列化。

主流做法是 cursor 分页（`created_at,id`）并使用专用列表 DTO，只返回列表卡片需要的字段，详情页再取完整 JSON。优点是响应稳定、数据库/网络成本随页大小受控；缺点是前端需要维护 cursor，Mapper SQL 和索引设计更复杂。

本项目适合在面试记录增长后采用。可增加 `GET /api/interviews?cursor=&limit=`，MyBatis 新增 `findPageByUserId`，为 `interview_sessions(user_id, created_at, id)` 建索引；列表 DTO 仅含摘要，完整 `finalEvaluation` 留给详情接口。仍应保持查询接口不调用 Python。
