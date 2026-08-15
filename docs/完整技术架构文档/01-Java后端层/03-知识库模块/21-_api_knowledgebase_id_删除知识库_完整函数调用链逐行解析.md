# DELETE /api/knowledgebase/{id}：删除知识库完整函数调用链逐行解析

> 删除流程先将数据库记录标记为 DELETING，再同步调用 Python 删除向量；只有 Python 成功后才物理删除数据库记录与 Java Redis 索引快照。Python 失败会保留记录并标记 DELETE_FAILED，支持用户识别和后续处理。

## 1. 接口定义

### 1.1 功能与作用

`DELETE /api/knowledgebase/{id}` 删除当前用户知识库及其 Python RAG 向量。该接口不会发 RabbitMQ；为了在向量删除失败时不遗失重试上下文，它采用“先标记、再远程删除、最后物理删除”的同步补偿流程。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 路径 | `DELETE /api/knowledgebase/{id}` |
| Controller | `KnowledgeBaseController.delete`，`KnowledgeBaseController.java:76-81` |
| Python | `POST /v1/agent/rag/delete` |
| 状态 | DELETING → 删除成功物理移除；失败 → DELETE_FAILED |
| Redis | 每次状态变更在数据库提交后刷新；物理删后提交后移除键。|

### 1.3 前端入口

管理页调用 `knowledgeBaseApi.deleteKnowledgeBase`，定义于 `frontend/src/api/knowledgebase.ts:129-131`，它使用 `request.delete`。

## 2. 函数调用链

```text
knowledgeBaseApi.deleteKnowledgeBase -> request.delete -> Axios interceptor
  -> RequestIdFilter -> SimpleRateLimitFilter -> IdempotencyFilter(optional)
  -> KnowledgeBaseController.delete -> KnowledgeBaseService.delete
     -> required -> KnowledgeBaseRepository.findById -> UserIdentityResolver.require
     -> KnowledgeBasePersistenceService.markDeleting -> cacheAfterCommit
     -> HttpPythonAgentClient.deleteRag -> AgentCallExecutor -> Python rag_delete
     ->（失败）markDeleteFailed -> cacheAfterCommit -> BusinessException
     ->（成功）KnowledgeBasePersistenceService.deleteMarked
        -> Repository.delete -> afterCommit(removeKnowledgeBaseIndex)
  -> ApiResult.success(null)
```

## 3. 函数解析

### 3.1 前端、过滤器和 Controller

#### 3.1.1 `deleteKnowledgeBase`、请求和过滤器

**文件与行号：** `frontend/src/api/knowledgebase.ts:129-131`，`frontend/src/api/request.ts:47-72、123-154`。

1. API 第 129 行声明删除函数；第 130 行拼接 ID 并调用 request.delete；第 131 行结束。
2. request.ts 生成/读取客户端用户 ID、写用户/追踪头；成功时解包 null，失败时转换网络/业务错误。
3. Java RequestId、Redis 限流、本机回退、可选幂等键逻辑分别在 `RequestIdFilter.java:23-41`、`SimpleRateLimitFilter.java:48-82`、`IdempotencyFilter.java:41-96`。

#### 3.1.2 `KnowledgeBaseController.delete`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/knowledgebase/controller/KnowledgeBaseController.java:76-81`。

1. 第 76 行映射 DELETE；第 77-78 行绑定 ID/用户头；第 79 行调用服务；第 80 行包装 success(null)；第 81 行结束。

### 3.2 Java 状态机、Python 调用与提交后缓存函数

#### 3.2.1 `KnowledgeBaseService.delete`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/knowledgebase/service/KnowledgeBaseService.java:171-191`。

1. 第 172 行 `required` 校验存在/owner。第 173 行 markDeleting，使正在运行的索引 worker 可在处理前检测删除请求。
2. 第 180-183 行构造 RAG delete 请求，含 requestId、runId、user、会话标识和知识库 ID。
3. 第 184-189 行非成功响应时提取远端错误、`markDeleteFailed`、抛 `KNOWLEDGE_BASE_VECTOR_DELETE_FAILED`。第 190 行成功时 deleteMarked；第 191 行结束。

#### 3.2.2 `required`、`markDeleting`、`markDeleteFailed` 与 `deleteMarked`

**文件与行号：** `KnowledgeBaseService.java:228-235`，`KnowledgeBasePersistenceService.java:58-102`。

1. `required` 第 229-234 行按主键查询，空抛 NOT_FOUND，identity.require 后 owner 不同抛 ACCESS_DENIED。
2. `markDeleting` 第 58-64 行事务读实体、调用实体 markDeleting、Mapper save、登记 cacheAfterCommit。
3. `markDeleteFailed` 第 76-82 行事务写失败状态/信息、保存并提交后刷新缓存。
4. `deleteMarked` 第 66-74 行事务检查实体确为 DELETING；否则抛状态错误；第 72 行物理 delete；第 73 行提交后清除 Java task cache。
5. `afterCommit` 第 94-102 行无事务直接运行，有事务则注册同步回调；它保证 Redis 不领先 PostgreSQL。

#### 3.2.3 Python 客户端与 RAG 删除

**文件与行号：** `HttpPythonAgentClient.java:49、65-96`，`AgentCallExecutor.java:22-43`，Python `/v1/agent/rag/delete` 路由。

1. Java client `deleteRag` 用 bounded retry executor POST Python。post 先 validate、调用 RestClient、映射结构化/HTTP/网络错误。
2. executor 仅重试 retryable Python 错误，中断转不可重试异常。
3. Python RAG 删除服务按知识库 ID 清理向量/文档索引，返回成功协议或可重试标记的错误；Java 根据 code 决定 DELETE_FAILED 或物理删除。

## 4. 主流构建分析

当前同步补偿易于理解且失败保留状态，但 Python 慢/不可用会阻塞删除请求，用户必须重试。

主流模式是 Saga/异步删除任务：事务标记 DELETING 并写 outbox，Worker 删除向量，成功物理删，失败按退避重试。优点是请求快、可审计；缺点是最终一致性、任务监控和已删除记录过滤更复杂。

本项目适合在向量删除延迟增加时迁移。保留现有 DELETING/DELETE_FAILED 状态，新增 outbox 和重试次数/下次执行时间；列表默认隐藏已物理删除、但显示删除失败供用户/管理员重试。不要在远程删除成功前直接物理删数据库记录。
