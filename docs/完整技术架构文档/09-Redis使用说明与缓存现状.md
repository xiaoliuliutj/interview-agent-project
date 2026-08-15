# Redis 使用说明与缓存现状

## 1. 结论先行

当前工作区没有项目自定义的 Redis 读写调用：没有 `RedisTemplate`、`StringRedisTemplate`、Redisson、Jedis、Lettuce client、`@Cacheable` 或 Python Redis 客户端。Redis 只出现在依赖、配置和 Docker 编排中，业务代码尚未把任何信息写入 Redis。

当前实际存在的缓存是 Python 进程内字典、会话 JSON 中的证据缓存和 `lru_cache` 配置缓存，它们都不是 Redis。

## 2. Redis 出现位置

### 2.1 Java Maven 依赖

文件：`java-backend/pom.xml:36-39`

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

逐行解释：第 36 行开始声明 Redis Starter；第 37 行指定组织；第 38 行把 Spring Data Redis 客户端和自动配置加入 classpath；第 39 行结束依赖。依赖存在不代表业务已经执行 Redis 命令。

### 2.2 Java 连接配置

文件：`java-backend/src/main/resources/application.yml:17-18`

```yaml
  data:
    redis:
      url: ${REDIS_URL:redis://localhost:6379}
```

逐行解释：第 17 行进入 Spring Data 配置；第 18 行读取 `REDIS_URL` 或使用 localhost:6379。当前没有 Java 类读取该连接工厂执行 get/set。

### 2.3 Docker 服务

文件：`infrastructure/docker-compose.yml:18-27,126`

```yaml
  redis:
    image: redis:7.4-alpine
    command: ["redis-server", "--appendonly", "yes"]
    volumes:
      - redis-data:/data
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 20
    restart: unless-stopped
```

逐行解释：服务名为 redis，使用 Redis 7.4，开启 AOF 和持久卷；健康检查只验证进程可 ping，不能证明有业务 key；当前没有缓存写入方。

## 3. 当前实际缓存（均不是 Redis）

### 3.1 Python RAG 进程内缓存

文件：`python-agent/app/rag/service.py:32,93-102,121-129`

```python
self._search_cache: dict[str, tuple[float, list[RagSearchResult]]] = {}
cache_key = "|".join([
    str(use_case), ",".join(sorted(selected_kbs)), normalized_query.lower(),
    str(selected_top_k), str(selected_min_score),
])
cached = self._search_cache.get(cache_key)
if cached and (
    self._policy.cache_ttl_seconds == 0
    or monotonic() - cached[0] < self._policy.cache_ttl_seconds
):
    return [item.model_copy(deep=True) for item in cached[1]]
self._search_cache[cache_key] = (
    monotonic(), [item.model_copy(deep=True) for item in results]
)
while len(self._search_cache) > self._policy.cache_max_entries:
    self._search_cache.pop(next(iter(self._search_cache)))

def invalidate_cache(self) -> None:
    self._search_cache.clear()
```

逐行解释：缓存键包含用途、知识库集合、查询和阈值；命中按 TTL 返回深拷贝；未命中后保存结果；超过最大条目淘汰最早项；索引/删除完成时清空。它只存在当前 Python 进程，重启和多实例不会共享。

### 3.2 会话证据缓存

文件：`python-agent/app/agents/interview/service.py:749-818`，字段：`python-agent/app/agents/interview/models.py:212`

```python
cache_key = self._evidence_cache_key(session, route.next_topic, knowledge_base_ids)
await self._report_progress(session.session_id, "CACHE_LOOKUP")
cached = session.rag_evidence_cache.get(cache_key)
if cached is not None:
    return [dict(item) for item in cached]
...
session.rag_evidence_cache[cache_key] = evidence
```

逐行解释：按阶段、主题和知识库集合生成键；命中复制证据字典返回；未命中完成 RAG/网页检索后写入会话字段。完成会话时清空该字段，避免临时证据进入终态。该字段最终随 Python 会话 JSON 保存到 PostgreSQL，而不是 Redis。

### 3.3 配置缓存

文件：`python-agent/app/common/config.py:43-46`

```python
@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings()
```

逐行解释：当前进程只缓存一个 Settings 实例；缓存对象是配置，不是业务数据，也不访问 Redis。

## 4. Redis 异常处理现状

因为没有 Redis client 调用，项目没有 Redis 命令超时、连接断开、序列化失败、缓存击穿、雪崩或 key 失效的业务 catch/降级代码。当前只有 Docker `redis-cli ping` 健康检查。若未来接入 Redis，必须另行设计连接失败降级、读失败旁路数据库、写失败不阻断主流程、TTL 和序列化异常处理；这些不属于当前实现，本文不虚构。

## 5. 审核结论

Redis 是已部署但未被业务读写的预留基础设施。当前没有任何信息实际缓存到 Redis；RAG 查询、会话证据和配置缓存分别位于 Python 内存/会话 JSON/进程缓存中。

## 6. 当前工作区 Redis 相关位置逐项审计

以下清单按当前有效源码目录（`java-backend`、`python-agent`、`infrastructure`）的实际引用整理；`reference/interview-guide-original` 是历史参考工程，不属于当前运行链路，不能把其中的 Redisson/Redis Stream 实现当作本项目已实现功能。

| 文件与行号 | 代码 | 实际作用 | 是否执行 Redis 命令 |
|---|---|---|---|
| `java-backend/pom.xml:37-40` | `spring-boot-starter-data-redis` 依赖 | 让 Spring Boot 具备 Redis 自动配置能力 | 否 |
| `java-backend/src/main/resources/application.yml:16-18` | `spring.data.redis.url` | 声明连接地址，默认 `redis://localhost:6379` | 否，当前无业务 Bean 注入连接工厂 |
| `infrastructure/docker-compose.yml:18-28` | Redis 服务、AOF、数据卷、健康检查 | 启动并持久化 Redis 容器 | 只有健康检查执行 `redis-cli ping` |
| `infrastructure/docker-compose.yml:76` | `REDIS_URL: redis://redis:6379` | 将地址传给 Java 容器 | 否 |
| `infrastructure/docker-compose.yml:95-96` | `depends_on.redis.condition: service_healthy` | Java 容器等待 Redis 健康 | 否 |
| `infrastructure/docker-compose.yml:126` | `redis-data` 卷 | 保存 Redis AOF 数据 | 否 |
| `python-agent/app/rag/service.py:32,93-129` | `_search_cache` 字典 | 进程内 RAG 搜索缓存 | 否 |
| `python-agent/app/agents/interview/models.py:210-212`、`service.py:748-844` | `rag_evidence_cache` | 会话 JSON 内的题目证据快照 | 否 |
| `python-agent/app/common/config.py:43-47` | `@lru_cache(maxsize=1)` | 进程内配置对象缓存 | 否 |

## 7. 非 Redis 缓存的逐行解析与异常行为

### 7.1 RAG 搜索缓存

文件：`python-agent/app/rag/service.py:32,93-102,121-129`；策略字段和校验位于 `python-agent/app/rag/policy.py:22-23,40-41,62-63`。

1. 第 32 行创建空字典，键是字符串，值是“写入时间 + 搜索结果列表”；进程重启后全部丢失。
2. 第 93-96 行把用途、排序后的知识库 ID、标准化 query、topK 和最小分数拼成唯一键，避免不同检索条件互相污染。
3. 第 97 行读取字典；第 98-101 行检查条目存在且未超过 `cache_ttl_seconds`，TTL 为 0 时按代码语义表示永不过期。
4. 第 102 行对缓存结果做深拷贝后返回，调用方修改返回值不会反向修改缓存。
5. 第 121-123 行保存单调时钟时间和结果深拷贝；第 124-125 行在超过 `cache_max_entries` 时删除最早插入的键，限制进程内存。
6. 第 128-129 行的 `invalidate_cache` 清空全部条目；`index_document` 第 53 行和 `delete_knowledge_base` 第 61 行在向量写入/删除成功后调用，防止旧检索结果继续被使用。
7. `RagPolicy.load` 第 40-41 行读取 TTL 和最大条目数；第 62-63 行拒绝负 TTL 或小于 1 的容量，配置错误抛出 `RagConfigurationError`，不会启动一个不受约束的缓存。

该缓存没有 Redis 连接异常、序列化异常或网络超时分支，因为它只操作 Python 字典。RAG 检索本身失败时由 `service.py:109-120` 对过滤能力做本地回退；embedding/向量库异常则由 `RagDependencyError` 向 API 层报告，缓存不会吞掉依赖失败。

### 7.2 面试会话证据缓存

文件：`python-agent/app/agents/interview/models.py:210-212`、`python-agent/app/agents/interview/service.py:748-819,821-829,838-844`。

1. 模型第 210-212 行把“当前题证据”和按键索引的 `rag_evidence_cache` 定义为 Pydantic 字段，默认使用新建字典，避免不同会话共享同一对象。
2. `_question_evidence` 第 755-763 行校验路由题目并合并知识库 ID，再调用 `_evidence_cache_key`；第 821-829 行按阶段、规范化主题和排序后的知识库 ID生成稳定键。
3. 第 764 行把进度写为 `CACHE_LOOKUP`；第 765-768 行命中时复制证据字典并立即返回，后续不会重复调用 RAG 或网页服务。
4. 第 769-787 行未命中时执行 RAG 检索；超时或任意异常只记录 warning 并将结果降为空列表，保证面试主流程继续。
5. 第 788-817 行把 RAG/网页结果裁剪为提示词需要的字段和长度；第 818-819 行把结果写入当前会话并返回副本。
6. `_complete` 第 838-844 行在面试完成时清空证据缓存，避免临时证据进入完成态；会话最终仍由 Python 的 PostgreSQL repository 保存，不是 Redis。

### 7.3 配置缓存

文件：`python-agent/app/common/config.py:43-47`。

1. 第 43 行的 `@lru_cache(maxsize=1)` 只保留一个 `Settings` 返回值。
2. 第 44 行定义 `get_settings`，第 45 行说明它是进程级只读快照。
3. 第 47 行实际构造 `Settings()`；后续调用直接命中装饰器缓存，不重新读取环境变量或 `.env`。

配置缓存没有外部依赖，因此不存在 Redis 连接失败处理；测试需要新配置时必须显式调用 `get_settings.cache_clear()`，否则会继续使用旧快照。

## 8. Redis 部署层异常处理边界

`docker-compose.yml:23-28` 每 5 秒执行一次 `redis-cli ping`，连续 20 次失败后服务保持不健康；`java-backend` 在 95-98 行要求 Redis healthy 才启动。该机制只阻止编排层过早启动 Java，并不代表业务已经使用 Redis。当前 Java/Python 没有捕获 Redis 超时、连接断开、序列化失败、缓存击穿、雪崩或 key 过期的代码路径；这些异常处理属于未来真正接入 Redis 时需要新增的设计，而不是当前实现。

## 9. Docker 片段逐行解析

| 行号 | 逐行解释 |
|---:|---|
| 18 | 声明名为 `redis` 的 Compose 服务。 |
| 19 | 使用 `redis:7.4-alpine` 镜像。 |
| 20 | 启动 Redis 并开启 AOF（Append Only File）持久化。 |
| 21-22 | 将命名卷 `redis-data` 挂载到 `/data`，保存 AOF 文件。 |
| 23-27 | 声明健康检查：每 5 秒执行一次、单次超时 3 秒、最多失败 20 次。 |
| 24 | 实际执行的唯一 Redis 命令是 `redis-cli ping`，只验证服务存活。 |
| 28 | 容器异常退出时由 Compose 自动重启。 |
| 76 | 向 Java 容器注入 `REDIS_URL`；因为当前没有 Redis 客户端业务调用，该环境变量不会触发 get/set。 |
| 95-96 | Java 依赖 Redis healthy 条件启动；这是启动编排依赖，不是应用层缓存读写。 |

## 10. 审计结论

当前 Redis 的唯一运行时命令是容器健康检查中的 `redis-cli ping`。业务缓存的信息是：RAG 搜索结果（Python 字典）、题目证据快照（会话 JSON）和配置对象（`lru_cache`）；缓存目的分别是减少重复向量检索、保证同一题目使用同一证据快照、避免重复解析配置。它们均不跨进程、不跨实例，也不具备 Redis 的持久化和共享语义。
