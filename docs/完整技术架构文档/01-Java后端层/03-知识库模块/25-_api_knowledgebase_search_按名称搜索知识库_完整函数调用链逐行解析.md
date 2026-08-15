# GET /api/knowledgebase/search：按名称搜索知识库的完整函数调用链

## 1. 接口定义

接口按 `keyword` 对当前用户的知识库名称执行不区分大小写的包含查询，返回 DTO 列表。它不搜索正文或向量，不调用 Python。

| 项目 | 内容 |
| --- | --- |
| 方法/路径 | GET `/api/knowledgebase/search?keyword=...` |
| 匹配字段 | name containing ignore case |
| 返回 | `ApiResult<List<KnowledgeBaseView>>` |
| Python 调用 | 无 |

## 2. 函数调用链

~~~text
KnowledgeBaseManagePage.loadData → knowledgeBaseApi.search
 -> encodeURIComponent → request.get → Axios/Filter
 -> KnowledgeBaseController.search
 -> KnowledgeBaseService.search → UserIdentityResolver.require
 -> Repository.findByOwnerIdAndNameContainingIgnoreCase → toView
 -> ApiResult.success → setKnowledgeBases
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `knowledgeBaseApi.search`

文件：`frontend/src/api/knowledgebase.ts:145-147`。

1. 第 145 行接收 keyword 并声明数组返回。
2. 第 146 行 encodeURIComponent 后拼入 query string，再调用 request.get；第 147 行结束。
3. ManagePage.loadData/loadDataSilent 在 searchKeyword.trim() 非空时选择此函数，成功后 setKnowledgeBases。

#### 3.1.2 `request.get` 与拦截器

文件：`frontend/src/api/request.ts:47-73、123-160`。

1. request.get 第 158-160 行调用 Axios GET 并取 data。
2. createClientId/currentUserId 第 47-58 行提供 requestId/owner；请求拦截器第 64-73 行写请求头。
3. 响应拦截器第 123-155 行解包 code=200 或生成服务/网络异常。

### 3.2 Java 函数

#### 3.2.1 `KnowledgeBaseController.search`

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/controller/KnowledgeBaseController.java:102-106`。

1. 第 102 行映射 GET `/search`；第 103-104 行绑定必填 keyword 与用户头。
2. 第 105 行调用 service.search 并 success；第 106 行结束。缺 keyword 由 Spring 参数绑定拒绝。

#### 3.2.2 `KnowledgeBaseService.search`

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/service/KnowledgeBaseService.java:198-200`。

1. 第 198 行定义函数。
2. 第 199 行 identity.require 后调用 `findByOwnerIdAndNameContainingIgnoreCase(owner,keyword)`，再 stream.map(toView).toList。
3. 第 200 行结束。Service 不 trim/拒绝空 keyword；传空串时数据库包含匹配通常返回当前用户全部名称。

#### 3.2.3 `require`、Repository 与 `toView`

文件：`common/security/UserIdentityResolver.java:14-19`；`KnowledgeBaseRepository.java:10-26`；`KnowledgeBaseService.java:225-233`。

1. require 第 15-19 行拒绝空用户、strip 并返回 owner。
2. Repository 方法名声明 owner 限定、name 包含和 ignore-case 语义；实际 SQL 由 Spring Data 生成。
3. toView 第 225-233 行逐项读取实体字段构造 KnowledgeBaseView。

### 3.3 Python 边界

1. 此处“search”是 Java 数据库名称查询，不是 `RagService.search`。
2. 调用链不含 PythonAgentClient、RAG embedding/vector repository 或 `/v1/**`，Java→Python 次数为零。

## 4. 审核结论

1. 已覆盖前端关键字编码、Java 用户限定、不区分大小写包含查询和 DTO 投影。
2. 已明确它不是向量检索；所有项目函数均注明文件/行号，确认不调用 Python。
