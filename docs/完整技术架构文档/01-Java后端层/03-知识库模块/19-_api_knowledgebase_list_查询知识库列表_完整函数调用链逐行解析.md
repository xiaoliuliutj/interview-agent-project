# GET /api/knowledgebase/list：查询知识库列表完整函数调用链逐行解析

> 列表接口是 Java 查询路径：MyBatis 从 knowledge_bases 按 owner 读取，Java 再执行状态过滤和时间/大小排序；每一项优先合并 Java Redis 中的索引状态快照。不会调用 Python 或 RabbitMQ。

## 1. 接口定义

### 1.1 功能与作用

`GET /api/knowledgebase/list` 返回当前用户知识库，可选 `sortBy=time|size` 与 `vectorStatus` 过滤。返回记录包括来源、文件元数据、向量状态、错误与 chunkCount。上传页/管理页用它轮询异步索引结果。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 路径 | `GET /api/knowledgebase/list?sortBy=&vectorStatus=` |
| Controller | `KnowledgeBaseController.list`，`KnowledgeBaseController.java:58-64` |
| 数据 | knowledge_bases；Redis 仅覆盖状态/error 展示 |
| 允许状态 | PENDING、PROCESSING、COMPLETED、FAILED、DELETING、DELETE_FAILED |
| Python/MQ | 无调用。|

### 1.3 前端入口

`frontend/src/api/knowledgebase.ts:126` 使用 query 参数调用列表 API；管理页将结果用于筛选、排序和索引状态轮询。

## 2. 函数调用链

```text
knowledgeBaseApi.list -> request.get -> Axios interceptor
  -> RequestIdFilter -> SimpleRateLimitFilter -> IdempotencyFilter(GET skip)
  -> KnowledgeBaseController.list -> KnowledgeBaseService.list(overload)
     -> UserIdentityResolver.require -> KnowledgeBaseRepository.findByOwnerIdOrderByCreatedAtDesc
        -> KnowledgeBaseRepository.xml SELECT
     -> stream.filter/sorted -> KnowledgeBaseService.toView
        -> JavaTaskStatusCache.knowledgeBaseIndex -> JavaRedisStore.getJson
  -> ApiResult.success -> Axios response interceptor
```

## 3. 函数解析

### 3.1 前端、过滤器和 Controller 函数

#### 3.1.1 `knowledgeBaseApi` 列表函数与 `request.get`

**文件与行号：** `frontend/src/api/knowledgebase.ts:126`，`frontend/src/api/request.ts:47-72、123-164`。

1. 列表 API 把已构造 query 拼接到 `/api/knowledgebase/list`，调用 `request.get`。
2. `createClientId` 第 47-49 行和 `currentUserId` 第 52-57 行提供身份；拦截器第 64-72 行写用户/request ID。
3. `request.get` 第 158-160 行返回 data；第 123-135 行解包 code 200；第 136-154 行转换错误。

#### 3.1.2 RequestId、限流、幂等和 `KnowledgeBaseController.list`

**文件与行号：** `RequestIdFilter.java:23-41`、`SimpleRateLimitFilter.java:48-82`、`IdempotencyFilter.java:41-44`，目录 `java-backend/src/main/java/com/interviewguide/infrastructure/`；`KnowledgeBaseController.java:58-64`。

1. RequestId 规范/回传 ID 并清理 MDC；限流以 Redis INCR 或 ConcurrentHashMap 回退限制请求；GET 由幂等过滤器跳过。
2. Controller 第 58 行映射路径；第 59-62 行绑定 sortBy、vectorStatus、用户头；第 63 行调用 service 并 success 包装；第 64 行结束。

### 3.2 Java 查询、缓存合并和视图函数

#### 3.2.1 `KnowledgeBaseService.list` 两个 overload

**文件与行号：** `java-backend/src/main/java/com/interviewguide/knowledgebase/service/KnowledgeBaseService.java:132-157`。

1. 简化 overload 第 132-134 行指定默认 `sortBy=time`、无状态过滤，委托主函数。
2. 主函数第 137-142 行构造允许状态集合，并拒绝未知 vectorStatus。第 143-150 行 switch：time 按 createdAt 倒序且 null 最后，size 按 fileSize 倒序，其他 sortBy 抛业务错误。
3. 第 151 行 require 用户后调用 Mapper；第 152-153 行只保留匹配状态（未提供即保留全部）；第 154 行执行 comparator；第 155 行逐项 `toView`；第 156 行收集返回。

#### 3.2.2 MyBatis Mapper 与 `toView`

**文件与行号：** `KnowledgeBaseRepository.java:15`，`resources/mapper/knowledgebase/KnowledgeBaseRepository.xml:5`，`KnowledgeBaseService.java:237-250`。

1. Mapper 接口第 15 行声明按 owner 查询；XML 第 5 行用 `WHERE owner_id=#{ownerId} ORDER BY created_at DESC`，数据库先保证隔离。
2. `toView` 第 238 行读 `taskCache.knowledgeBaseIndex`。第 239-242 行从缓存 Map 取 status/error；类型不匹配或缺失回退实体字段。
3. 第 243-249 行构造 view，复制 ID、名称、类别、原文件、大小、类型、时间、向量状态/错误、chunkCount 和来源字段；第 250 行结束。底层 Redis 失败返回空 Optional，不使列表失败。

## 4. 主流构建分析

当前实现易读且 Redis 故障可回退数据库；缺点是 SQL 已按时间取全量后，状态过滤和 size 排序在 Java 内存完成，数据量增加后无法分页且会放大内存。

主流实现应把 status、sort、cursor pagination 下推 MyBatis SQL，并使用专用列表 DTO。优点是高效、稳定分页；缺点是动态 SQL/索引和缓存合并更复杂。

本项目适合逐步引入：增加 `limit/cursor`，Mapper 用动态 `<if>` 过滤 status、`ORDER BY` 白名单分支，并为 `(owner_id, vector_status, created_at)`、`(owner_id,file_size)` 建索引。Redis 仍只覆盖短期状态，而数据库列表为最终事实。
