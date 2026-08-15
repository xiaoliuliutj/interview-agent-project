# GET /api/knowledgebase/list：查询知识库列表的完整函数调用链

## 1. 接口定义

接口查询当前用户的知识库，可按 `time` 或 `size` 排序，并可按六种向量状态过滤。它只读取 Java 数据库并投影 DTO，不查询 Python 向量库，也不启动索引任务。

| 项目 | 内容 |
| --- | --- |
| 方法/路径 | GET `/api/knowledgebase/list` |
| 可选参数 | sortBy=time/size；vectorStatus=PENDING/PROCESSING/COMPLETED/FAILED/DELETING/DELETE_FAILED |
| 返回 | `ApiResult<List<KnowledgeBaseView>>` |
| Python 调用 | 无 |

## 2. 函数调用链

~~~text
KnowledgeBaseManagePage.loadData/loadDataSilent
 -> knowledgeBaseApi.getAllKnowledgeBases → request.get → Axios 拦截器
 -> RequestIdFilter → SimpleRateLimitFilter
 -> KnowledgeBaseController.list
 -> KnowledgeBaseService.list
    -> UserIdentityResolver.require
    -> Repository.findByOwnerIdOrderByCreatedAtDesc
    -> 状态 filter → comparator sort → KnowledgeBaseService.toView
 -> ApiResult.success → 前端 setKnowledgeBases
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `KnowledgeBaseManagePage.loadData` 与 `loadDataSilent`

文件：`frontend/src/pages/KnowledgeBaseManagePage.tsx:141-180`。

1. loadDataSilent 第 141 行用 useCallback 定义无加载动画刷新；第 143-150 行按 category/keyword/默认列表分支调用不同 API，本接口对应第 149 行 `getAllKnowledgeBases(sortBy)`。
2. 第 151 行成功写 knowledgeBases；第 152-156 行捕获错误但保持现有列表，避免轮询闪烁；依赖数组使分类、关键字、排序变化时重建函数。
3. loadData 第 161 行定义显式加载；第 162-163 行设置 loading/清 error；第 165-171 行使用同一三分支，本接口对应第 170 行。
4. 第 172 行写列表；第 173-176 行错误转 UI 文案；第 177-179 行 finally 关闭 loading。

#### 3.1.2 `knowledgeBaseApi.getAllKnowledgeBases`

文件：`frontend/src/api/knowledgebase.ts:121-127`。

1. 第 121 行接收可选 sortBy/vectorStatus。
2. 第 122 行创建 URLSearchParams；第 123-124 行只追加存在的参数。
3. 第 125 行序列化 query；第 126 行有 query 时加 `?`，调用 request.get；第 127 行结束。

#### 3.1.3 `request.get` 与 Axios 拦截器

文件：`frontend/src/api/request.ts:47-73、123-160`。

1. request.get 第 158-160 行调用 instance.get 后返回 data。
2. createClientId 第 47-50 行生成请求 ID；currentUserId 第 52-58 行读取/首次保存 owner。
3. 请求拦截器第 64-73 行设置 X-User-Id/X-Request-Id；成功拦截器第 123-135 行解包 ApiResult；失败回调第 136-155 行解析服务/网络错误。

### 3.2 Java 函数

#### 3.2.1 `KnowledgeBaseController.list`

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/controller/KnowledgeBaseController.java:58-64`。

1. 第 58 行映射 `/list`；第 59-62 行绑定两个可选查询参数和用户头。
2. 第 63 行调用 service.list 并 ApiResult.success；第 64 行结束。

#### 3.2.2 `KnowledgeBaseService.list`

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/service/KnowledgeBaseService.java:128-153`。

1. 三参数 list 第 132 行定义；第 133-134 行建立六种允许状态集合。
2. 第 135-138 行非空且不在集合的 vectorStatus 抛 KNOWLEDGE_BASE_STATUS_INVALID。
3. 第 139-146 行 switch 排序：null/time 按 createdAt 倒序且 null 最后；size 按 fileSize 倒序；其他值抛 KNOWLEDGE_BASE_SORT_INVALID。
4. 第 147 行 identity.require 后按 owner 查询；第 148-149 行无过滤值或状态相等才保留。
5. 第 150 行按 comparator 排序；第 151 行 toView；第 152 行 toList；第 153 行结束。
6. 单参数 list 第 128-130 行仅以默认 time/null 委托三参数函数，stats 接口使用；本路径 Controller 直接调用三参数重载。

#### 3.2.3 `UserIdentityResolver.require` 与 Repository

文件：`common/security/UserIdentityResolver.java:14-19`；`knowledgebase/mapper/KnowledgeBaseRepository.java:10-26`。

1. require 第 15-17 行拒绝 null/blank；第 18 行 strip；第 19 行返回 owner。
2. `findByOwnerIdOrderByCreatedAtDesc` 是项目声明的 Spring Data 派生查询，只读取当前 owner 的实体并按创建时间倒序。

#### 3.2.4 `KnowledgeBaseService.toView` 与实体 getter

文件：`KnowledgeBaseService.java:225-233`；`KnowledgeBaseEntity.java:91-111`。

1. toView 第 225-233 行按 DTO 构造顺序调用实体 getter：ID、名称、分类、文件名、大小、内容类型、向量状态/数量/错误、来源 URL/标题/时间/hash、创建时间。
2. list 的 filter 使用 getVectorStatus；time comparator 使用 getCreatedAt；size comparator 使用 getFileSize。
3. 每个 getter 均为单句 return，无数据库、Python 或状态副作用。

#### 3.2.5 `ApiResult.success` 与 Python 边界

文件：`common/web/dto/ApiResult.java:3-6`。

1. 第 4-5 行构造 code=200、message=success、data=列表的 record。
2. Controller→Service→Repository/投影链不含 indexWorker、PythonAgentClient、RabbitTemplate 或 `/v1/**`，所以 Java→Python 调用次数为零。

## 4. 审核结论

1. 已覆盖前端列表/静默刷新、参数构建、Java 参数校验、归属查询、过滤排序和 DTO 投影。
2. 每个可达项目函数均标明文件、行号及语句作用；已确认不调用 Python。
