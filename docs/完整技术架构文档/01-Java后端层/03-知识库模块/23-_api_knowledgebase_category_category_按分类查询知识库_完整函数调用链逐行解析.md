# GET /api/knowledgebase/category/{category}：按分类查询知识库完整函数调用链逐行解析

> 当前实现把 owner 与分类条件一并下推到 MyBatis SQL，然后将实体转换为带 Java Redis 索引状态快照的视图。它不调用 Python 或 RabbitMQ。

## 1. 接口定义

### 1.1 功能与作用

`GET /api/knowledgebase/category/{category}` 返回当前用户某一个精确分类下的知识库。分类作为路径变量直接传递给 Mapper 参数；不存在分类返回空数组。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 路径 | `GET /api/knowledgebase/category/{category}` |
| Controller | `KnowledgeBaseController.byCategory`，`KnowledgeBaseController.java:89-93` |
| SQL | `WHERE owner_id=#{ownerId} AND category=#{category}` |
| 状态显示 | `toView` 优先 Java Redis 索引快照 |
| Python/MQ | 无调用。|

### 1.3 前端入口

`knowledgeBaseApi.byCategory` 在 `frontend/src/api/knowledgebase.ts:137-139`，以 `encodeURIComponent(category)` 保护路径段。

## 2. 函数调用链

```text
knowledgeBaseApi.byCategory -> request.get -> Axios interceptor
  -> RequestIdFilter -> SimpleRateLimitFilter -> IdempotencyFilter(GET skip)
  -> KnowledgeBaseController.byCategory -> KnowledgeBaseService.byCategory
     -> UserIdentityResolver.require -> KnowledgeBaseRepository.findByOwnerIdAndCategory
        -> MyBatis XML SELECT -> KnowledgeBaseService.toView
        -> JavaTaskStatusCache.knowledgeBaseIndex / JavaRedisStore.getJson
  -> ApiResult.success
```

## 3. 函数解析

### 3.1 前端、过滤器与 Controller 函数

#### 3.1.1 `knowledgeBaseApi.byCategory` 与 request 函数

**文件与行号：** `frontend/src/api/knowledgebase.ts:137-139`，`frontend/src/api/request.ts:47-72、123-164`。

1. 第 138 行对 category 做 `encodeURIComponent`，防止空格、斜杠和 `?` 改变 URL 结构，然后调用 request.get。
2. request.ts 创建/读取客户端用户 ID、拦截器写 X-User-Id/X-Request-Id、成功时解包 code=200、失败时返回 ApiRequestError。

#### 3.1.2 Java 通用过滤器与 `byCategory` Controller

**文件与行号：** `infrastructure/web/RequestIdFilter.java:23-41`、`ratelimit/SimpleRateLimitFilter.java:48-82`、`idempotency/IdempotencyFilter.java:41-44`；`KnowledgeBaseController.java:89-93`。

1. RequestId 规范并回传追踪 ID；限流通过 Redis INCR 或本机窗口；GET 跳过幂等键逻辑。
2. Controller 第 89 行映射路径；第 90-91 行绑定 category 和用户头；第 92 行调用 service 后 success 包装；第 93 行结束。

### 3.2 Java Mapper、缓存与视图函数

#### 3.2.1 `KnowledgeBaseService.byCategory`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/knowledgebase/service/KnowledgeBaseService.java:199-201`。

1. 第 200 行先 `identity.require(userId)`，再调用 `findByOwnerIdAndCategory`。查询结果流逐个调用 `toView` 并收集列表。
2. 第 201 行结束。没有额外 Java 内存分类过滤，权限和分类均在 SQL 条件中。

#### 3.2.2 Mapper 与 `toView`

**文件与行号：** `KnowledgeBaseRepository.java:16`，`resources/mapper/knowledgebase/KnowledgeBaseRepository.xml:6`，`KnowledgeBaseService.java:237-250`。

1. Mapper 第 16 行以 @Param 命名 owner/category；XML 第 6 行执行两个等值条件的 select。
2. `toView` 第 238 行读取 task cache；第 239-242 行 Redis 状态/error 存在且为 String 时覆盖实体值，缺失/异常回退数据库值；第 243-249 行构造完整视图。

## 4. 主流构建分析

精确分类 SQL 简单且权限正确，但 category 区分大小写、空白和别名，容易形成近似重复分类。

主流方式是独立分类表或规范化分类键（slug），知识库保存 category_id，并用唯一约束管理名称。优点是避免脏分类、可扩展层级/颜色/排序；缺点是上传、迁移与查询多一张表。

本项目当前可先在 `updateCategory`/上传时 trim 并统一大小写；若分类运营需求增加，再建 categories 表，Mapper 改 join/category_id 查询，并迁移现有字符串。
