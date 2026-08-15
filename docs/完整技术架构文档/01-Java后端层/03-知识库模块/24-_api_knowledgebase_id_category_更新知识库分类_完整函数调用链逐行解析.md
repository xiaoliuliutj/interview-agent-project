# PUT /api/knowledgebase/{id}/category：更新知识库分类的完整函数调用链

## 1. 接口定义

接口更新当前用户指定知识库的 category 字段和 updatedAt。分类可以为字符串或 null；Controller 从 JSON Map 读取。它不修改正文、原文件或向量，因此不调用 Python、RabbitMQ 或重新索引。

| 项目 | 内容 |
| --- | --- |
| 方法/路径 | PUT `/api/knowledgebase/{id}/category` |
| 请求体 | `{ "category": string|null }` |
| 返回 | `ApiResult<Void>` |
| Python 调用 | 无 |

## 2. 函数调用链

~~~text
KnowledgeBaseManagePage.saveCategory → knowledgeBaseApi.updateCategory
 -> request.put → Axios/Filter
 -> KnowledgeBaseController.updateCategory
 -> KnowledgeBaseService.updateCategory
    -> required → Repository.findById → UserIdentityResolver.require
    -> KnowledgeBaseEntity.updateCategory
 -> JPA dirty checking → ApiResult.success → 前端 loadData
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 页面保存分类函数

文件：`frontend/src/pages/KnowledgeBaseManagePage.tsx:259-276`。

1. 保存函数先确认 editingCategoryId 存在，读取输入分类并把空白值转 null。
2. 第 267 行 await `knowledgeBaseApi.updateCategory(id,categoryToSave)`。
3. 成功后清编辑状态并 await loadData；失败时写 error；finally 清 saving 状态。

#### 3.1.2 `knowledgeBaseApi.updateCategory` 与 `request.put`

文件：`frontend/src/api/knowledgebase.ts:141-143`；`api/request.ts:47-73、123-155、164-166`。

1. 第 141 行定义函数；第 142 行 PUT 动态 ID 路径，请求体明确为 `{category}`；第 143 行结束。
2. request.put 第 164-166 行调用 instance.put 并取 data。
3. createClientId/currentUserId 第 47-58 行提供请求 ID/owner；请求拦截器写两个头，响应拦截器解包 ApiResult 或抛项目异常。

### 3.2 Java 函数

#### 3.2.1 `KnowledgeBaseController.updateCategory`

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/controller/KnowledgeBaseController.java:95-101`。

1. 第 95 行映射 PUT `/{id}/category`。
2. 第 96-97 行绑定 long id、JSON `Map<String,String>` 和用户头。
3. 第 98 行 `body.get("category")`；key 缺失与显式 null 都传 null。
4. 第 99 行 success(null)；第 100-101 行结束。

#### 3.2.2 `KnowledgeBaseService.updateCategory` 与 `required`

文件：`KnowledgeBaseService.java:194-196、216-223`。

1. updateCategory 第 194 行声明函数；第 195 行把 long id 转 String，调用 required 后直接 entity.updateCategory；第 196 行结束。
2. required 第 217-218 行按 ID 查实体，缺失抛 NOT_FOUND。
3. 第 219-221 行 identity.require 与 ownerId 比较，越权抛 ACCESS_DENIED；第 222 行返回实体。
4. Service 作为 Spring 管理方法，实体修改由持久化上下文 dirty checking 提交；源码未显式 repository.save。

#### 3.2.3 `KnowledgeBaseEntity.updateCategory`

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/domain/KnowledgeBaseEntity.java:111`。

1. 第 111 行第一句把传入 category 原样赋值；没有 trim、长度或空白校验。
2. 同行第二句把 updatedAt 设为 Instant.now；向量状态、正文、来源和文件字段不变。

### 3.3 Python 边界

1. 调用链不含 indexWorker、PythonAgentClient、indexRag/deleteRag、RabbitTemplate 或 `/v1/**`。
2. 分类不进入已持久化向量 chunk 的重建流程，因此 Java→Python 次数为零。

## 4. 审核结论

1. 已覆盖前端空分类处理、JSON Map 绑定、所有权校验、实体更新及刷新。
2. 所有可达项目函数均标注文件和行号；确认不调用 Python或重建向量。
