# GET /api/knowledgebase/category/{category}：按分类查询知识库的完整函数调用链

## 1. 接口定义

接口按路径中的分类精确查询当前用户的知识库，返回 KnowledgeBaseView 列表。前端对分类进行 URL 编码，Java Repository 同时限定 ownerId/category。接口不调用 Python。

| 项目 | 内容 |
| --- | --- |
| 方法/路径 | GET `/api/knowledgebase/category/{category}` |
| 分类匹配 | Repository 精确值匹配 |
| 返回 | `ApiResult<List<KnowledgeBaseView>>` |
| Python 调用 | 无 |

## 2. 函数调用链

~~~text
KnowledgeBaseManagePage.loadData → knowledgeBaseApi.getByCategory
 -> encodeURIComponent → request.get → Axios/Filter
 -> KnowledgeBaseController.byCategory
 -> KnowledgeBaseService.byCategory
    -> UserIdentityResolver.require
    -> Repository.findByOwnerIdAndCategory
    -> KnowledgeBaseService.toView
 -> ApiResult.success → setKnowledgeBases
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `knowledgeBaseApi.getByCategory`

文件：`frontend/src/api/knowledgebase.ts:137-139`。

1. 第 137 行接收 category 并声明数组返回。
2. 第 138 行 encodeURIComponent 防止中文、斜杠、空格等破坏路径，再调用 request.get；第 139 行结束。
3. ManagePage 的 loadData/loadDataSilent（141-180 行）在 category 非 `all` 时选择此函数，成功后 setKnowledgeBases。

#### 3.1.2 `request.get` 与拦截器

文件：`frontend/src/api/request.ts:47-73、123-160`。

1. request.get 第 158-160 行执行 Axios GET 并取 data。
2. createClientId/currentUserId 第 47-58 行生成请求 ID/读取 owner；请求拦截器第 64-73 行写用户和请求头。
3. 响应拦截器第 123-155 行解包 code=200 或把服务/网络错误转项目异常。

### 3.2 Java 函数

#### 3.2.1 `KnowledgeBaseController.byCategory`

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/controller/KnowledgeBaseController.java:89-93`。

1. 第 89 行映射 `/category/{category}`；第 90-91 行绑定解码后的 category 和身份头。
2. 第 92 行调用 service.byCategory 并 ApiResult.success；第 93 行结束。

#### 3.2.2 `KnowledgeBaseService.byCategory`

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/service/KnowledgeBaseService.java:189-191`。

1. 第 189 行定义函数。
2. 第 190 行 identity.require 后调用 `findByOwnerIdAndCategory(owner,category)`；随后 stream.map(this::toView).toList。
3. 第 191 行结束。Service 不 trim/忽略大小写，匹配语义由 Repository/数据库等值决定。

#### 3.2.3 `require`、Repository、`toView`

文件：`common/security/UserIdentityResolver.java:14-19`；`KnowledgeBaseRepository.java:10-26`；`KnowledgeBaseService.java:225-233`。

1. require 第 15-19 行拒绝空用户、strip 并返回 owner。
2. Repository 的 `findByOwnerIdAndCategory` 是项目声明的 Spring Data 派生查询，避免跨用户读取。
3. toView 第 225-233 行逐项调用实体 getter，构造 ID、名称、分类、文件、向量状态、来源和时间字段。

### 3.3 Python 边界

1. 调用链不含 indexRag/deleteRag、PythonAgentClient、RabbitTemplate 或 `/v1/**`。
2. 即使列表中的 vectorStatus 为 PROCESSING，本查询也只读 Java 状态，不向 Python 询问实时进度；Java→Python 次数为零。

## 4. 审核结论

1. 已覆盖 URL 编码、用户/分类复合查询和 DTO 投影。
2. 每个可达项目函数均标注文件、行号并逐句说明；确认不调用 Python。
