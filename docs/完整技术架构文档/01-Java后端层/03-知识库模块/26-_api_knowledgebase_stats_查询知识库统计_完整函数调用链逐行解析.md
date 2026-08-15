# GET /api/knowledgebase/stats：查询知识库统计完整函数调用链逐行解析

> 当前统计接口复用 `KnowledgeBaseService.list(userId)` 的用户过滤、实体转换和 Redis 状态覆盖，然后在 Controller 流中计数；不调用 Python 或 RabbitMQ。

## 1. 接口定义

### 1.1 功能与作用

`GET /api/knowledgebase/stats` 返回当前用户知识库总数、向量化完成数、处理中数和失败数。`FAILED` 与 `DELETE_FAILED` 都计入失败，`PENDING` 与 `PROCESSING` 都计入处理中。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 路径 | `GET /api/knowledgebase/stats` |
| Controller | `KnowledgeBaseController.stats`，`KnowledgeBaseController.java:108-119` |
| 数据来源 | `service.list(userId)`，状态可能来自 Java Redis 快照 |
| Python/MQ | 无调用 |

### 1.3 前端入口

`knowledgeBaseApi.stats` 定义于 `frontend/src/api/knowledgebase.ts:150-152`，管理页用于顶部统计卡片。

## 2. 函数调用链

```text
knowledgeBaseApi.stats -> request.get -> Axios interceptor
  -> RequestIdFilter -> SimpleRateLimitFilter -> IdempotencyFilter(GET skip)
  -> KnowledgeBaseController.stats -> KnowledgeBaseService.list
     -> identity.require -> Repository.findByOwnerIdOrderByCreatedAtDesc
     -> filter/sort/toView -> JavaTaskStatusCache.knowledgeBaseIndex
  -> List.size / stream.filter.count -> ApiResult.success
```

## 3. 函数解析

### 3.1 前端、过滤器和 Controller 函数

#### 3.1.1 `knowledgeBaseApi.stats` 与请求处理

**文件与行号：** `frontend/src/api/knowledgebase.ts:150-152`，`frontend/src/api/request.ts:47-72、123-164`。

1. 第 151 行调用 `request.get<KnowledgeBaseStats>('/api/knowledgebase/stats')`。
2. request 拦截器生成/读取用户身份与 requestId；响应成功拦截器解包 stats；错误拦截器转换错误。Java RequestId、限流和 GET 幂等跳过按通用过滤器执行。

#### 3.1.2 `KnowledgeBaseController.stats`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/knowledgebase/controller/KnowledgeBaseController.java:108-119`。

1. 第 108 行映射 stats；第 109-110 行绑定用户头。
2. 第 111 行调用 `service.list(userId)`，因此统计只基于当前用户、且复用 Redis 状态覆盖。
3. 第 112-118 行构造 Map：第 113 行 `items.size()` 为总数；第 114 行过滤 COMPLETED 并 count；第 115-116 行过滤 PROCESSING/PENDING；第 117-118 行过滤 FAILED/DELETE_FAILED。
4. 第 119 行结束并返回 success。

### 3.2 Java 列表和计数函数

#### 3.2.1 `KnowledgeBaseService.list` 与 `toView`

**文件与行号：** `KnowledgeBaseService.java:132-157、237-250`。

1. 简化 list 第 132-134 行调用主 list，默认按时间排序、无状态过滤。主 list 第 137-150 行校验状态并选择 comparator；第 151-156 行按 owner 查询、排序并转换。
2. `toView` 第 238-242 行从 Java task cache 获取 status/error，Redis 缺失时回退实体；第 243-249 行复制其他字段；第 250 行结束。
3. Controller 的计数因此统计的是展示状态，而不是未刷新缓存的旧数据库状态；数据库仍是最终来源。

## 4. 主流构建分析

当前实现复用列表后在内存计数，优点是口径与列表完全一致、代码少；缺点是为四个数字读取并转换全部知识库，数据量增大时成本高，且缓存状态逐项读取。

主流方案是单条 SQL 条件聚合或物化统计：`COUNT(*) FILTER (WHERE vector_status=...)`，必要时按 owner 建索引。优点是数据库只返回四个数字；缺点是若 Redis 状态领先数据库，统计需重新设计缓存/事件口径。

本项目可在规模增长时增加 `statsByOwner` Mapper SQL，并明确统计只基于 PostgreSQL 持久化状态；若必须反映 Redis 即时状态，则由状态变更事件维护独立计数缓存，并设置数据库校准任务。
