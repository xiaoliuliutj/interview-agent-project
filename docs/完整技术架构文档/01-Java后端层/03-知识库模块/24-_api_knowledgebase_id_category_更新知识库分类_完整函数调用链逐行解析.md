# PUT /api/knowledgebase/{id}/category：更新知识库分类完整函数调用链逐行解析

> 当前接口只更新 knowledge_bases 的 category 字段；不会重新向量化、不调用 Python/MQ，也不刷新索引状态 Redis，因为分类不影响现有向量内容。

## 1. 接口定义

### 1.1 功能与作用

`PUT /api/knowledgebase/{id}/category` 将请求 JSON 中的 `category` 写入当前用户拥有的知识库。它先验证记录和所有权，随后在 MyBatis 事务中更新实体。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 路径 | `PUT /api/knowledgebase/{id}/category` |
| Controller | `KnowledgeBaseController.updateCategory`，`KnowledgeBaseController.java:95-100` |
| 请求体 | `{ "category": "..." }`；Controller 从 Map 取键，缺失时传 null |
| 数据 | knowledge_bases.category |
| Python/MQ/Redis | 无调用/无状态刷新。|

### 1.3 前端入口

`knowledgeBaseApi.updateCategory` 位于 `frontend/src/api/knowledgebase.ts:141-143`，用 request.put 发送 `{category}`。

## 2. 函数调用链

```text
knowledgeBaseApi.updateCategory -> request.put -> Axios interceptor
  -> RequestIdFilter -> SimpleRateLimitFilter -> IdempotencyFilter(optional)
  -> KnowledgeBaseController.updateCategory -> KnowledgeBaseService.updateCategory
     -> required -> KnowledgeBaseRepository.findById -> UserIdentityResolver.require
     -> KnowledgeBaseEntity.updateCategory -> KnowledgeBaseRepository.save (MyBatis update)
  -> ApiResult.success(null)
```

## 3. 函数解析

### 3.1 前端、过滤器和 Controller

#### 3.1.1 `knowledgeBaseApi.updateCategory` 与 request.put

**文件与行号：** `frontend/src/api/knowledgebase.ts:141-143`，`frontend/src/api/request.ts:47-72、123-`。

1. API 将 id 写路径、category 写 JSON body 并调用 PUT。
2. request.ts 生成/读取客户端用户 ID，拦截器写身份/追踪头，成功解包 null，失败转错误。
3. RequestId/限流保证可观测和保护；PUT 若没有 X-Idempotency-Key 跳过幂等，带键时 Redis/本机占位防止重复提交。

#### 3.1.2 `KnowledgeBaseController.updateCategory`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/knowledgebase/controller/KnowledgeBaseController.java:95-100`。

1. 第 95 行映射 PUT。第 96 行绑定 ID 与 body Map；第 97 行绑定用户头。
2. 第 98 行读取 `body.get("category")` 并委托 service；第 99 行 success(null)；第 100 行结束。body 为 null/非对象时由 Spring JSON 绑定错误处理，不会进入服务。

### 3.2 Java 授权、实体与持久化函数

#### 3.2.1 `KnowledgeBaseService.updateCategory`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/knowledgebase/service/KnowledgeBaseService.java:203-208`。

1. 第 203 行以 `@Transactional` 定义事务。第 204 行 `required` 读取/授权实体。
2. 第 206 行调用领域实体 `updateCategory(category)`；第 207 行调用 Mapper `save` 写数据库；第 208 行结束。
3. 函数没有调用 indexWorker、pythonAgentClient、persistence 状态方法，因此不改变 vectorStatus 或 chunkCount。

#### 3.2.2 `required`、实体更新与 MyBatis save

**文件与行号：** `KnowledgeBaseService.java:228-235`，`KnowledgeBaseEntity.java` 的 `updateCategory`，`KnowledgeBaseRepository.java` 的 `save` 与 XML update。

1. `required` 第 229 行按 ID 查记录，第 230 行缺失抛 NOT_FOUND；第 231-232 行 require 用户并比较 owner，越权抛 ACCESS_DENIED；第 234 行返回。
2. `updateCategory` 仅修改内存 category（及实体内部更新时间逻辑）；真正持久化发生在下一行 Mapper save。
3. Mapper save 根据实体主键执行 XML update；MyBatis 不会像 JPA 一样自动检测脏字段。

## 4. 主流构建分析

当前使用通用 Map body 和全行 save，优点是实现短；缺点是缺少分类长度/空白/字符集约束，Map 键拼写在编译期不可发现。

主流做法是定义 `UpdateKnowledgeBaseCategoryRequest` record，使用 Bean Validation，并以专用 `UPDATE knowledge_bases SET category=... WHERE id=... AND owner_id=...` Mapper 更新。优点是契约和授权更清晰、写入更少；缺点是增加 DTO/Mapper 代码。

本项目适合直接引入 DTO：`@NotBlank @Size(max=...)` 或明确允许 null 的语义；Mapper 合并 id/owner 条件并以受影响行数判断 404/403。分类规范化规则应与上传和按分类查询一致。
