# GET /api/interviews/{sessionId}：查询面试详情完整函数调用链逐行解析

> 当前详情接口从 Java 的 MyBatis 持久化记录读取会话与 turn；它不会调用 Python、RabbitMQ 或 Redis。先校验用户对 session 的所有权，才读取候选人回答和评价摘要。

## 1. 接口定义

### 1.1 功能与作用

`GET /api/interviews/{sessionId}` 返回一场面试的会话视图和按时间顺序排列的题目/回答 turn。每个 turn 被加上从 0 开始的 `index`，供前端恢复对话进度。该接口用于继续既有面试、历史面试详情和简历详情页的面试抽屉。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 方法与路径 | `GET /api/interviews/{sessionId}` |
| Controller | `InterviewController.get`，`java-backend/src/main/java/com/interviewguide/interview/controller/InterviewController.java:53-57` |
| 输入 | sessionId、X-User-Id |
| 响应 | `ApiResult<InterviewDetailView>`，包含 session 与 turns |
| 数据 | interview_sessions、interview_turns；MyBatis 查询 |
| Python/MQ | 无调用。详情读取不会补发问题、提交回答或恢复 Agent。|

### 1.3 前端入口

`frontend/src/api/interview.ts:73-76` 的 `getSession` 用于面试页恢复会话；`frontend/src/api/history.ts:87` 的 `getInterviewDetail` 用于历史/简历详情展示。两者请求同一个 Java 路径。

## 2. 函数调用链

```text
interviewApi.getSession 或 historyApi.getInterviewDetail -> request.get
  -> Axios interceptor -> currentUserId / createClientId
  -> RequestIdFilter.doFilterInternal -> normalize
  -> SimpleRateLimitFilter.doFilterInternal -> JavaRedisStore.incrementInFixedWindow
  -> IdempotencyFilter.shouldNotFilter（GET 跳过）
  -> InterviewController.get -> UserIdentityResolver.require -> InterviewService.detail
     -> ownedSession -> InterviewSessionPersistenceService.load
        -> InterviewSessionRepository.findById -> MyBatis XML
        -> assert userId
     -> InterviewSessionPersistenceService.turns
        -> InterviewTurnRepository.findBySessionIdOrderByCreatedAt -> MyBatis XML
     -> IntStream.mapToObj Lambda -> InterviewTurnView
     -> InterviewService.toView -> parseFinalEvaluation
  -> ApiResult.success -> Axios response interceptor -> 前端恢复/展示
```

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `interviewApi.getSession` 与 `historyApi.getInterviewDetail`

**文件与行号：** `frontend/src/api/interview.ts:73-76`，`frontend/src/api/history.ts:87`。

1. `getSession` 第 73 行接收 sessionId；第 74 行调用 `request.get<InterviewDetailView>`；第 75 行将详情转换为面试页 session 形状；第 76 行结束。
2. `getInterviewDetail` 第 87 行直接对相同模板 URL 调用 `request.get<InterviewDetail>`，用于历史页而不做面试页转换。
3. 两个调用均先经过 `request.ts:47-72`：`createClientId` 第 47-49 行创建 ID，`currentUserId` 第 52-57 行读写 localStorage，请求拦截器第 64-72 行写用户与 requestId。
4. `request.get` 第 158-160 行返回 Axios data；响应拦截器第 123-135 行解包 Java 200 包装，错误拦截器第 136-154 行处理传输、业务和 HTTP 错误。

### 3.2 Java 入口与保护函数

#### 3.2.1 `RequestIdFilter`、限流和幂等跳过

**文件与行号：** `RequestIdFilter.java:23-41`、`SimpleRateLimitFilter.java:48-82`、`IdempotencyFilter.java:41-44`，均位于 `java-backend/src/main/java/com/interviewguide/infrastructure/`。

1. RequestId filter 第 25-33 行调用 `normalize`、保存/回传 ID、写 MDC、放行并 finally 清理；第 36-41 行对不安全请求 ID 生成 UUID。
2. 限流第 54-67 行通过 `JavaRedisStore.incrementInFixedWindow` 做 Redis INCR/TTL，失败回退 ConcurrentHashMap；第 69-79 行超限返回 429，第 81 行继续。
3. `shouldNotFilter` 第 42-44 行只允许有幂等键的写操作处理；本 GET 没有 `doFilterInternal` 分支。

#### 3.2.2 `InterviewController.get`、身份与成功包装

**文件与行号：** `InterviewController.java:53-57`，`common/security/UserIdentityResolver.java:14-19`，`common/web/dto/ApiResult.java:3-6`。

1. Controller 第 53 行映射 `/{sessionId}`；第 54 行绑定路径；第 55 行绑定用户头；第 56 行先 require 再调用 detail 并包装；第 57 行结束。
2. `require` 第 15-17 行拒绝空身份，第 18 行去空白，第 19 行返回；`success` 第 4-5 行构造 code 200、message success 和 data。

### 3.3 Java 详情、Mapper 与视图函数

#### 3.3.1 `InterviewService.detail`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/interview/service/InterviewService.java:111-123`。

1. 第 112 行声明 Controller 专用详情函数。第 113 行先调用 `ownedSession`，因此后续不会在未授权 session 上读取 turns。
2. 第 114-115 行调用 `sessionPersistence.turns(sessionId)` 得到持久化 turn 列表。
3. 第 116 行创建从 0 到 turns.size 的 IntStream；第 117-121 行 Lambda 按索引读取 turn，构造 `InterviewTurnView(index, stage, question, candidateAnswer, evaluationSummary, score, createdAt)`，最后收集列表。
4. 第 122 行用 `toView(session)` 和 indexedTurns 构造 `InterviewDetailView`；第 123 行结束。

#### 3.3.2 `ownedSession`、`load` 与所有权检查

**文件与行号：** `InterviewService.java:185-191`，`InterviewSessionPersistenceService.java:189-203`。

1. `ownedSession` 调用 `sessionPersistence.load`，再比较 session.userId 与请求 userId；不一致抛 `SESSION_ACCESS_DENIED`。
2. `load` 第 189 行声明函数；第 190 行调用 `InterviewSessionRepository.findById`；第 191 行把空 Optional 转为 `SESSION_NOT_FOUND`；第 192 行结束。
3. `assertOwner` 第 199 行声明检查；第 200-202 行拒绝 null 或不等用户；第 203 行结束。它保证详情与 Python 进度接口共享同一授权规则。
4. Repository 的 findById 是 MyBatis Mapper，对应 `resources/mapper/interview/InterviewSessionRepository.xml` 的按主键 select；不存在 JPA Repository。

#### 3.3.3 `turns`、Turn Mapper 与转换 Lambda

**文件与行号：** `InterviewSessionPersistenceService.java:185-187`，`InterviewTurnRepository.java:14`，`InterviewTurnRepository.xml:5`。

1. `turns` 第 185 行声明函数；第 186 行调用 Mapper 的 `findBySessionIdOrderByCreatedAt`；第 187 行结束。
2. Mapper 接口第 14 行声明查询；XML 第 5 行以 `WHERE session_id=#{sessionId} ORDER BY created_at` 查询，保证第 116 行索引的顺序稳定。
3. 详情函数的 map Lambda 第 117-121 行不访问数据库；它只是把实体字段变为对前端友好的不可变视图并显式附加序号。

#### 3.3.4 `toView` 与 `parseFinalEvaluation`

**文件与行号：** `InterviewService.java:238-257`。

1. `toView` 第 238 行声明转换。第 239-247 行按构造器顺序复制会话身份、简历、方向、难度、题数、状态、Python/Java 状态版本、当前题、stage 和各类计数。
2. 第 247 行调用 `parseFinalEvaluation`，第 248 行补充时间字段并返回。
3. `parseFinalEvaluation` 第 249 行声明；第 250 行空 JSON 返回空 Map；第 251-252 行反序列化；第 253-255 行捕获异常也回退空 Map；第 257 行结束。可选报告 JSON 损坏不会阻止基本面试详情展示。

## 4. 主流构建分析

当前实现使用两个 MyBatis 查询和 Java 组装，优点是授权发生在读取 turns 前、SQL 简单且顺序稳定；缺点是会话与 turns 非同一快照读，回答提交并发时可能短暂看到新会话状态配旧 turn 列表。

主流改进是为详情读采用只读事务（合适隔离级别）并返回强类型 DTO，或将 session/turns 用 MyBatis resultMap 的嵌套查询批量装配。优点是契约稳定、可更明确控制一致性；缺点是 resultMap 容易产生笛卡尔展开/复杂映射，事务会增加数据库资源占用。

本项目适合先保留两查询方式，并在详情读取与提交回答并发问题出现时引入 `@Transactional(readOnly=true)` 和版本号回传。前端可基于 session.stateVersion 检测变化后刷新；数据库为 `interview_turns(session_id, created_at)` 保持复合索引。
