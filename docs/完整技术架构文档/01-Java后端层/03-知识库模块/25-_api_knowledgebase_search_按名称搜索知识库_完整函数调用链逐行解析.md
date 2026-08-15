# GET /api/knowledgebase/search：按名称搜索知识库完整函数调用链逐行解析

> 当前实现通过 MyBatis 的 `LOWER(name) LIKE` 对当前用户知识库名称做包含搜索，再转换 Redis 状态快照。它不调用 Python、RabbitMQ。

## 1. 接口定义

### 1.1 功能与作用

`GET /api/knowledgebase/search?keyword=...` 返回名称包含关键词的知识库列表，忽略大小写。搜索范围严格限定当前 `X-User-Id`，结果没有匹配时为空数组。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 路径 | `GET /api/knowledgebase/search?keyword=` |
| Controller | `KnowledgeBaseController.search`，`KnowledgeBaseController.java:102-106` |
| SQL | `LOWER(name) LIKE CONCAT('%',LOWER(#{keyword}),'%')` |
| Python/MQ | 无调用 |

### 1.3 前端入口

`knowledgeBaseApi.search` 位于 `frontend/src/api/knowledgebase.ts:145-147`，对 keyword 做 URL 编码后请求。

## 2. 函数调用链

```text
knowledgeBaseApi.search -> request.get -> Axios interceptor
  -> RequestIdFilter -> SimpleRateLimitFilter -> IdempotencyFilter(GET skip)
  -> KnowledgeBaseController.search -> KnowledgeBaseService.search
     -> UserIdentityResolver.require -> KnowledgeBaseRepository.findByOwnerIdAndNameContainingIgnoreCase
        -> XML SQL -> KnowledgeBaseService.toView -> JavaTaskStatusCache.knowledgeBaseIndex
  -> ApiResult.success
```

## 3. 函数解析

### 3.1 前端、过滤器与 Controller 函数

#### 3.1.1 `knowledgeBaseApi.search` 与 request

**文件与行号：** `frontend/src/api/knowledgebase.ts:145-147`，`frontend/src/api/request.ts:47-72、123-164`。

1. 第 146 行调用 `encodeURIComponent(keyword)`，再 GET `/search?keyword=`；第 147 行结束。
2. request.ts 生成/复用用户 ID、写身份/追踪头、解包 Java success；错误拦截器处理网络和业务错误。RequestId、限流与 GET 幂等跳过继续沿用 infrastructure filter。

#### 3.1.2 `KnowledgeBaseController.search`

**文件与行号：** `KnowledgeBaseController.java:102-106`。

1. 第 102 行映射 search；第 103 行绑定必需 keyword；第 104 行绑定用户头。
2. 第 105 行调用 service 并 success 包装列表；第 106 行结束。

### 3.2 Java 搜索、Mapper 与视图函数

#### 3.2.1 `KnowledgeBaseService.search`

**文件与行号：** `KnowledgeBaseService.java:210-212`。

1. 第 211 行先 `identity.require(userId)`，再调用 Mapper，逐项用 `toView` 转换并 `toList`。
2. 第 212 行结束。关键词未在 Java 手工拼接 SQL，避免把用户输入直接作为 SQL 片段。

#### 3.2.2 Mapper SQL、`toView` 与 Redis 回退

**文件与行号：** `KnowledgeBaseRepository.java` 的同名方法，`resources/mapper/knowledgebase/KnowledgeBaseRepository.xml:7`，`KnowledgeBaseService.java:237-250`。

1. XML 第 7 行同时限制 owner_id，并用 LOWER/LIKE 做大小写不敏感包含匹配。
2. `toView` 第 238-242 行从 Java task cache 读取 status/error，缓存空或类型错误时回退实体字段；第 243-249 行复制其余元数据；第 250 行结束。

## 4. 主流构建分析

当前 `LIKE '%keyword%'` 简单易用，但前缀通配符通常无法使用普通 B-tree 索引，数据量大时会全表扫描；关键词为空还可能匹配全部名称。

主流方案是 PostgreSQL `pg_trgm` + GIN 索引，或 Elasticsearch/OpenSearch 的全文索引。trigram 改造小、支持包含匹配；搜索引擎功能强但引入同步、运维和最终一致性。

本项目数据量较小时保留当前实现，并在入口拒绝/限制空关键词长度。规模增长时优先启用 `pg_trgm`：建 `CREATE INDEX ... USING gin (name gin_trgm_ops)`，保持现有 Mapper 语义；只有需要分词、拼写和排序时再引入搜索引擎。
