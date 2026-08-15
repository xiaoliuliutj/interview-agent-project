# DELETE /api/knowledgebase/{id}：删除知识库及向量的完整函数调用链

## 1. 接口定义

接口删除当前用户的一条知识库及 Python 向量数据。Java 先把状态写为 DELETING，再同步调用 Python `/v1/agent/rag/delete`；仅 Python 成功后删除 Java 实体。失败时保留实体并写 DELETE_FAILED/error，便于重试和诊断。

| 项目 | 内容 |
| --- | --- |
| 方法/路径 | DELETE `/api/knowledgebase/{id}` |
| Java 入口 | `KnowledgeBaseController.delete` |
| Python | POST `/v1/agent/rag/delete` |
| 成功 | 向量删除后删除 Java 记录 |
| 失败 | Java 记录保留为 DELETE_FAILED |

## 2. 函数调用链

~~~text
KnowledgeBaseManagePage.handleDelete → knowledgeBaseApi.deleteKnowledgeBase → request.delete
 -> Axios/Filter → KnowledgeBaseController.delete
 -> KnowledgeBaseService.delete → required → UserIdentityResolver.require
    -> Persistence.markDeleting → Entity.markDeleting
    -> HttpPythonAgentClient.deleteRag → AgentCallExecutor.execute → post
    -> Python delete_rag → _resolve_rag_service
       -> RagService.delete_knowledge_base → _lock_for
       -> VectorRepository.delete_by_knowledge_base → invalidate_cache
    -> [成功] Persistence.deleteMarked → Repository.delete
    -> [失败] Persistence.markDeleteFailed → Entity.markDeleteFailed
 -> ApiResult.success → 前端刷新
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `KnowledgeBaseManagePage.handleDelete`

文件：`frontend/src/pages/KnowledgeBaseManagePage.tsx:216-228`。

1. 第 217 行无 deleteItem 直接 return；第 218 行设置 deleting=true。
2. 第 220 行 await deleteKnowledgeBase(id)；第 221 行成功后 await loadData 刷新服务端状态；第 222 行关闭确认框。
3. 第 223-225 行捕获错误并写 error；第 226-227 行 finally 恢复 deleting；第 228 行结束。

#### 3.1.2 `knowledgeBaseApi.deleteKnowledgeBase` 与 `request.delete`

文件：`frontend/src/api/knowledgebase.ts:129-131`；`api/request.ts:47-73、123-155、170-172`。

1. 第 129 行定义函数；第 130 行 DELETE 动态 ID 路径；第 131 行结束。
2. request.delete 第 170-172 行调用 Axios 并取 data；请求拦截器写 X-User-Id/X-Request-Id；响应拦截器解包成功或产生 ApiRequestError。

### 3.2 Java 函数

#### 3.2.1 `KnowledgeBaseController.delete`

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/controller/KnowledgeBaseController.java:76-81`。

1. 第 76 行映射 DELETE `/{id}`；第 77-78 行绑定 long id 和用户头。
2. 第 79 行 service.delete；第 80 行 success(null)；第 81 行结束。

#### 3.2.2 `KnowledgeBaseService.delete`

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/service/KnowledgeBaseService.java:167-181`。

1. 第 168 行 required 校验存在/归属；第 169 行 markDeleting。
2. 第 170-173 行构造 AgentRagDeleteRequest：独立 requestId、稳定 run/session 前缀、operation、KB ID 和时间；再次 require 用户 ID。
3. 第 174-178 行 Python 响应为空或非 1xx 时取消息、markDeleteFailed 并抛 KNOWLEDGE_BASE_VECTOR_DELETE_FAILED。
4. 第 180 行 Python 成功后 deleteMarked 删除实体；第 181 行结束。

#### 3.2.3 `required` 与身份校验

文件：`KnowledgeBaseService.java:216-223`；`common/security/UserIdentityResolver.java:14-19`。

1. required 第 217-218 行 Repository.findById，缺失抛 KNOWLEDGE_BASE_NOT_FOUND。
2. 第 219-221 行 identity.require 后与 ownerId 比较，越权抛 ACCESS_DENIED；第 222 行返回。
3. require 第 15-19 行拒绝 null/blank、strip 并返回 owner。

#### 3.2.4 `KnowledgeBasePersistenceService` 与实体状态函数

文件：`KnowledgeBasePersistenceService.java:39-57`；`KnowledgeBaseEntity.java:91-102`。

1. markDeleting 第 39-41 行事务中 required 后调用实体；实体第 91-95 行写 DELETING、清 error、更新时间。
2. deleteMarked 第 44-51 行 required 后确认 hasDeletionRequest，状态不允许则抛业务异常，允许时 repository.delete。
3. markDeleteFailed 第 54-56 行 required 后调用实体；实体第 97-102 行写 DELETE_FAILED、截断错误并更新时间。

#### 3.2.5 `HttpPythonAgentClient.deleteRag`

文件：`HttpPythonAgentClient.java:49、65-96`；`AgentCallExecutor.java:22-43`。

1. deleteRag 第 49 行以 callExecutor 执行 `/v1/agent/rag/delete`。
2. post 第 66-79 行 validateRequest、POST、反序列化，并处理空 body、结构化错误、HTTP/网络异常。
3. execute 对可重试网关错误按 maxAttempts/backoff 重试；最终异常使 Service 退出，Java 状态已经是 DELETING，非 AgentResponse 失败不会进入第 174 行的 markDeleteFailed，这是源码实际边界。

### 3.3 Python 函数

#### 3.3.1 `delete_rag` 与 `_resolve_rag_service`

文件：`python-agent/app/api/application.py:240-252、331-337`。

1. 路由第 241 行定义；第 242 行保存请求上下文；第 243 行取 RagService 并 await delete_knowledge_base。
2. 第 244-252 行构造 code=100 AgentResponse，answer=`deleted`。
3. _resolve_rag_service 第 332-337 行从 app.state 取实例；为空时 build_rag_service 并缓存。

#### 3.3.2 `RagService.delete_knowledge_base`

文件：`python-agent/app/rag/service.py:56-61、128-136`。

1. 第 57-58 行拒绝空白 ID；第 59 行取得该 KB 专属 asyncio.Lock。
2. 第 60 行 repository.delete_by_knowledge_base；第 61 行 invalidate_cache 清所有搜索缓存。
3. _lock_for 第 131-136 行按 ID 复用或创建锁，防止删除与同一 KB 索引并发。

#### 3.3.3 向量 Repository 删除

文件：`python-agent/app/rag/repository.py:79-84`。

1. delete_by_knowledge_base 打开异步数据库会话，执行按 knowledge_base_id 的 DELETE 并 commit。
2. 删除零行仍视为成功，使接口幂等；同一 Java 请求重试不会因向量已不存在而失败。

## 4. 审核结论

1. 已覆盖前端确认、Java 删除状态机、同步 Python 向量删除、成功物理删除及失败保留分支。
2. 每个可达项目函数均标注文件/行号并逐句解释；删除顺序严格依据源码。
