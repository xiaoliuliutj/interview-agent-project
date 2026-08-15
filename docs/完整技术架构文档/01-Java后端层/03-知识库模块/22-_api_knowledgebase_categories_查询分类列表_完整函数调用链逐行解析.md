# GET /api/knowledgebase/categories：查询分类列表完整函数调用链逐行解析

> 当前接口按用户读取其知识库后，在 Java 流中提取非空 category 并去重；不调用 Python、RabbitMQ 或 Redis。

## 1. 接口定义

### 1.1 功能与作用

`GET /api/knowledgebase/categories` 返回当前用户已使用的知识库分类字符串列表，用于前端管理页筛选和分类选择。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 路径 | `GET /api/knowledgebase/categories` |
| Controller | `KnowledgeBaseController.categories`，`KnowledgeBaseController.java:83-87` |
| 数据 | knowledge_bases，按 owner_id 查询 |
| Python/MQ/Redis | 无调用。|

### 1.3 前端入口

`knowledgeBaseApi.categories` 位于 `frontend/src/api/knowledgebase.ts:133-135`，调用该路径获得字符串数组。

## 2. 函数调用链

```text
knowledgeBaseApi.categories -> request.get -> Axios interceptor
  -> RequestIdFilter -> SimpleRateLimitFilter -> IdempotencyFilter(GET skip)
  -> KnowledgeBaseController.categories -> KnowledgeBaseService.categories
     -> UserIdentityResolver.require -> KnowledgeBaseRepository.findByOwnerIdOrderByCreatedAtDesc
     -> stream.map/filter/distinct/toList
  -> ApiResult.success
```

## 3. 函数解析

### 3.1 前端、过滤器和 Controller 函数

#### 3.1.1 `knowledgeBaseApi.categories` 与请求函数

**文件与行号：** `frontend/src/api/knowledgebase.ts:133-135`，`frontend/src/api/request.ts:47-72、123-164`。

1. API 函数调用 `request.get<string[]>('/api/knowledgebase/categories')` 并返回数组 Promise。
2. request.ts 的 client ID/用户 ID、请求拦截器写 X-User-Id 和 X-Request-Id；成功拦截器解包 code 200，错误拦截器处理失败。
3. Java RequestId、Redis 限流/本机回退、GET 幂等跳过位于 infrastructure 的三个 filter 文件，执行顺序与所有公开接口一致。

#### 3.1.2 `KnowledgeBaseController.categories` 与 `ApiResult.success`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/knowledgebase/controller/KnowledgeBaseController.java:83-87`，`common/web/dto/ApiResult.java:3-6`。

1. 第 83 行映射 categories；第 84-85 行绑定用户头；第 86 行调用 service 并 success 包装；第 87 行结束。
2. `success` 第 4-6 行创建 code=200、message=success、data 的 record。

### 3.2 Java 查询与分类流函数

#### 3.2.1 `KnowledgeBaseService.categories`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/knowledgebase/service/KnowledgeBaseService.java:193-197`。

1. 第 194 行先 `identity.require(userId)`，再调用 Mapper 按 owner 获取记录。
2. 第 195 行 map 到 entity.category；第 196 行过滤 null/空白、调用 distinct 保留首次出现顺序、toList 收集；第 197 行结束。
3. 分类未额外排序，顺序继承 Mapper 的 created_at DESC 首次出现顺序。

#### 3.2.2 Mapper 与身份函数

**文件与行号：** `KnowledgeBaseRepository.java:15`，`resources/mapper/knowledgebase/KnowledgeBaseRepository.xml:5`，`common/security/UserIdentityResolver.java:14-19`。

1. require 拒绝空身份并 strip。
2. Mapper XML 以 `WHERE owner_id=#{ownerId} ORDER BY created_at DESC` 查询，保证只读取当前用户数据。

## 4. 主流构建分析

当前“读全部后 distinct”实现简单，但分类数量/文档量增大时会传输不必要实体。

主流实现是 MyBatis `SELECT DISTINCT category ... WHERE owner_id=? AND category IS NOT NULL AND trim(category)<>'' ORDER BY category`。优点是数据库去重、数据更少；缺点是分类显示顺序从最近使用变为字母序，需明确产品语义。

本项目可在分类量增加时添加专用 Mapper；若要保持当前最近使用顺序，可用 PostgreSQL `DISTINCT ON(category) ORDER BY category, created_at DESC` 再按 created_at 排序。
