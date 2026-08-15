# GET /api/knowledgebase/categories：查询知识库分类列表的完整函数调用链

## 1. 接口定义

接口读取当前用户的全部知识库，提取非空 category 并去重。由于 Repository 先按创建时间倒序，`distinct()` 保留每个分类第一次出现的位置，即按该分类最近知识库的相对顺序返回；源码没有额外字母排序。接口不调用 Python。

| 项目 | 内容 |
| --- | --- |
| 方法/路径 | GET `/api/knowledgebase/categories` |
| 返回 | `ApiResult<List<String>>` |
| 空分类 | null/blank 被过滤 |
| 去重 | Stream.distinct，保留首次遇到顺序 |
| Python 调用 | 无 |

## 2. 函数调用链

~~~text
KnowledgeBaseManagePage.loadData/loadDataSilent
 -> knowledgeBaseApi.getAllCategories → request.get → Axios 拦截器
 -> RequestIdFilter → SimpleRateLimitFilter
 -> KnowledgeBaseController.categories
 -> KnowledgeBaseService.categories
    -> UserIdentityResolver.require
    -> Repository.findByOwnerIdOrderByCreatedAtDesc
    -> KnowledgeBaseEntity.getCategory → filter → distinct → toList
 -> ApiResult.success → setCategories
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `loadData`、`loadDataSilent` 中的分类调用

文件：`frontend/src/pages/KnowledgeBaseManagePage.tsx:141-180`。

1. 两个函数都使用 `Promise.all` 同时加载知识库数据和 `knowledgeBaseApi.getAllCategories()`：静默函数调用位于第 150 行，显式函数位于第 171 行。
2. 第 154/175 行把 categoryList 写入 `setCategories`；失败分支分别保留旧数据显示错误或设置 error。
3. 分类查询与列表查询并行，任一 Promise 失败都会使对应 `Promise.all` 进入 catch。

#### 3.1.2 `knowledgeBaseApi.getAllCategories` 与 `request.get`

文件：`frontend/src/api/knowledgebase.ts:133-135`；`api/request.ts:47-73、123-160`。

1. 第 133 行声明 string[] 返回；第 134 行 GET 固定 categories 路径；第 135 行结束。
2. request.get 第 158-160 行调用 Axios 并取 data；createClientId/currentUserId 第 47-58 行提供请求 ID/用户。
3. 请求拦截器第 64-73 行写两个头；响应拦截器第 123-155 行解包 ApiResult 或解析失败。

### 3.2 Java 函数

#### 3.2.1 `KnowledgeBaseController.categories`

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/controller/KnowledgeBaseController.java:83-87`。

1. 第 83 行映射 GET `/categories`；第 84-85 行声明列表返回并读取可缺省 X-User-Id。
2. 第 86 行调用 service.categories 并 ApiResult.success；第 87 行结束。

#### 3.2.2 `KnowledgeBaseService.categories`

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/service/KnowledgeBaseService.java:183-187`。

1. 第 183 行接收 userId。
2. 第 184 行先 identity.require，再调用按 owner、createdAt 倒序的 Repository 查询。
3. 第 185 行对每条实体调用 getCategory；第 186 行过滤 null/blank、distinct 去重、toList 返回；第 187 行结束。

#### 3.2.3 `UserIdentityResolver.require`、Repository 与 getter

文件：`common/security/UserIdentityResolver.java:14-19`；`KnowledgeBaseRepository.java:10-26`；`KnowledgeBaseEntity.java:91-111`。

1. require 第 15-17 行拒绝 null/blank，第 18 行 strip，第 19 行返回 owner。
2. Repository 的 `findByOwnerIdOrderByCreatedAtDesc` 是项目声明的派生查询，限制用户并倒序返回。
3. `KnowledgeBaseEntity.getCategory` 是单句 return，不规范化、strip 或修改 category；因此只含空白的值由 Service 的 isBlank 过滤，带首尾空白的非空分类不会在此自动清理。

#### 3.2.4 `ApiResult.success` 与 Python 边界

文件：`common/web/dto/ApiResult.java:3-6`。

1. 第 4-5 行构造 code=200、message=success、data=分类列表的 record。
2. 调用链只有 Java Repository/Stream，不含 PythonAgentClient、indexWorker、RabbitTemplate 或 `/v1/**`，Java→Python 次数为零。

## 4. 审核结论

1. 已覆盖前端并行加载、Java 用户过滤、空值过滤、稳定去重和响应封装。
2. 已纠正“排序”边界：源码不显式排序分类，只继承知识库创建时间倒序后的首次出现顺序。
3. 每个可达项目函数均标注文件、行号并解释；确认不调用 Python。
