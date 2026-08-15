# GET /api/interviews/unfinished/{resumeId}：查询未结束面试完整函数调用链逐行解析

> 当前接口只查询 MyBatis 会话记录。它按当前用户、指定简历和允许继续的状态筛选最新一条，不调用 Python、RabbitMQ 或 Redis。

## 1. 接口定义

### 1.1 功能与作用

`GET /api/interviews/unfinished/{resumeId}` 查找当前用户针对该简历最近创建、尚可继续的面试。允许状态严格是 `INITIALIZING`、`ACTIVE`、`PAUSED`；没有匹配会话时返回成功包装的 `null`，不是 404。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 路径 | `GET /api/interviews/unfinished/{resumeId}` |
| Controller | `InterviewController.unfinished`，`InterviewController.java:66-70` |
| 输入 | resumeId、X-User-Id |
| SQL | user_id + resume_id + status IN (...)，created_at DESC，LIMIT 1 |
| Python/MQ | 无调用；这是恢复入口的查询，不会改变 Agent 状态。|

### 1.3 前端入口

`frontend/src/api/interview.ts:102-105` 的 `findUnfinishedSession` 调用接口，非 null 时用 `toSession` 转换，用于决定创建新面试还是恢复既有会话。

## 2. 函数调用链

```text
interviewApi.findUnfinishedSession -> request.get -> Axios interceptor
  -> RequestIdFilter.doFilterInternal -> normalize
  -> SimpleRateLimitFilter.doFilterInternal -> JavaRedisStore.incrementInFixedWindow
  -> IdempotencyFilter.shouldNotFilter（GET）
  -> InterviewController.unfinished -> UserIdentityResolver.require
  -> InterviewService.findUnfinished -> InterviewSessionPersistenceService.findUnfinished
     -> InterviewSessionRepository.findFirstByUserIdAndResumeIdAndStatusInOrderByCreatedAtDesc
        -> InterviewSessionRepository.xml SELECT
     -> InterviewService.toView -> parseFinalEvaluation
  -> ApiResult.success -> Axios response interceptor -> toSession 或 null
```

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `interviewApi.findUnfinishedSession`

**文件与行号：** `frontend/src/api/interview.ts:102-105`。

1. 第 102 行声明返回 `InterviewSession | null` 的异步函数。第 103 行对带 resumeId 的未结束路径调用 `request.get<InterviewView | null>`。
2. 第 104 行有 view 时调用 `toSession`，没有时返回 null；第 105 行结束。它不把 null 当异常，因此前端可以继续创建新会话。
3. `request.ts:47-72` 的 client ID、用户 ID 和请求拦截器写入追踪/身份头；`request.ts:123-154` 解包 `ApiResult` 并将真正错误转为 rejected Promise。

### 3.2 Java 保护和入口函数

#### 3.2.1 `RequestIdFilter`、限流与幂等跳过

**文件与行号：** `RequestIdFilter.java:23-41`、`SimpleRateLimitFilter.java:48-82`、`IdempotencyFilter.java:41-44`，目录均为 `java-backend/src/main/java/com/interviewguide/infrastructure/`。

1. RequestId filter 第 25-33 行规范、保存、回传并清理 request ID；`normalize` 第 36-41 行非法时生成 UUID。
2. 限流第 54-67 行通过 Redis 固定窗口或 ConcurrentHashMap 回退计数；第 69-79 行超限返回 429。
3. 幂等判断第 42-44 行仅处理带键写请求，本 GET 不进入占位分支。

#### 3.2.2 `InterviewController.unfinished`、身份和包装

**文件与行号：** `InterviewController.java:66-70`，`common/security/UserIdentityResolver.java:14-19`，`common/web/dto/ApiResult.java:3-6`。

1. 第 66 行映射固定 `unfinished` 前缀，避免与 `/{sessionId}` 路由混淆；第 67 行绑定 resumeId；第 68 行绑定用户头。
2. 第 69 行先 require 用户、调用服务、以 success 包装 null 或 view；第 70 行结束。
3. `require` 第 15-19 行拒绝空值并 strip；`success` 第 4-6 行构造统一 200 响应。

### 3.3 Java 查询和视图函数

#### 3.3.1 `InterviewService.findUnfinished`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/interview/service/InterviewService.java:134-136`。

1. 第 134 行声明 userId、resumeId 输入。第 135 行调用持久化查询，Optional 有值则 map 到 `toView`，无值 `orElse(null)`。
2. 第 136 行结束。它不额外验证 resume 所有权，因为 SQL 使用 user_id；不会请求 Python。

#### 3.3.2 `InterviewSessionPersistenceService.findUnfinished` 与 MyBatis SQL

**文件与行号：** `InterviewSessionPersistenceService.java:176-183`，`InterviewSessionRepository.java` 同名 Mapper 方法，`resources/mapper/interview/InterviewSessionRepository.xml:7`。

1. 第 176 行声明查询。第 177 行调用 Mapper；第 178-179 行传递用户和简历；第 180-182 行构造允许状态列表 INITIALIZING、ACTIVE、PAUSED；第 183 行结束。
2. XML 第 7 行以 user_id、resume_id 和 foreach 展开的 status IN 过滤，按 created_at 倒序 LIMIT 1。数据库在一条 SQL 中保证用户隔离与“最新”选择。

#### 3.3.3 `InterviewService.toView` 与 `parseFinalEvaluation`

**文件与行号：** `InterviewService.java:238-257`。

1. `toView` 第 239-248 行复制会话主要字段、状态、当前题和计数，并调用 `parseFinalEvaluation`。
2. `parseFinalEvaluation` 第 250 行空值回退空 Map；第 251-252 行 JSON 反序列化；第 253-255 行异常同样回退空 Map，避免可选报告格式影响恢复入口。

## 4. 主流构建分析

当前在数据库中直接按状态查询，优点是恢复决定基于持久化事实、没有缓存过期造成误恢复；缺点是同一用户对同一简历在并发创建窗口中可能有多条可恢复会话，SQL 只取最新条而不强制唯一。

主流改进是用部分唯一索引或显式会话锁保证“一个用户/简历最多一个活动会话”，并在创建逻辑中捕获唯一冲突后返回既有会话。优点是业务约束由数据库兜底；缺点是不同数据库对部分索引支持不同，历史迁移需要先清理重复活动数据。

本项目适合引入 PostgreSQL 部分唯一索引：`UNIQUE(user_id,resume_id) WHERE status IN ('INITIALIZING','ACTIVE','PAUSED')`，并让 `InterviewService.start` 在冲突时读取/返回未结束会话。这样与现有 findUnfinished 查询完全适配。
