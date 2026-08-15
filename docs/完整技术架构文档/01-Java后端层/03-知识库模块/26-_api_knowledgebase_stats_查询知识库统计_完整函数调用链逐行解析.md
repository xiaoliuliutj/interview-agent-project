# GET /api/knowledgebase/stats：查询知识库统计的完整函数调用链

## 1. 接口定义

接口读取当前用户全部知识库视图，在 Java 内存中统计总数、完成数、处理中数和失败数。PROCESSING/PENDING 合并为 processingCount，FAILED/DELETE_FAILED 合并为 failedCount。它不查询 Python。

| 项目 | 内容 |
| --- | --- |
| 方法/路径 | GET `/api/knowledgebase/stats` |
| 返回字段 | totalCount、completedCount、processingCount、failedCount |
| Python 调用 | 无 |

## 2. 函数调用链

~~~text
KnowledgeBaseManagePage.loadData/loadDataSilent → knowledgeBaseApi.getStatistics
 -> request.get → Axios/Filter → KnowledgeBaseController.stats
 -> KnowledgeBaseService.list(userId) → list(userId,"time",null)
 -> Repository.findByOwnerIdOrderByCreatedAtDesc → toView
 -> Controller stream.filter/count（四项）→ ApiResult.success → setStats
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `knowledgeBaseApi.getStatistics`

文件：`frontend/src/api/knowledgebase.ts:149-151`。

1. 第 149 行声明 KnowledgeBaseStats 返回；第 150 行 GET 固定 stats 路径；第 151 行结束。
2. ManagePage.loadDataSilent 第 144 行、loadData 第 165 行在 Promise.all 中调用它，成功后把 statsResult 交 setStats。

#### 3.1.2 `request.get` 与拦截器

文件：`frontend/src/api/request.ts:47-73、123-160`。

1. request.get 第 158-160 行调用 Axios GET 并取 data。
2. createClientId/currentUserId 第 47-58 行提供请求 ID/owner；请求拦截器第 64-73 行写两个头。
3. 成功响应第 123-135 行解包 ApiResult；失败回调第 136-155 行转项目异常。

### 3.2 Java 函数

#### 3.2.1 `KnowledgeBaseController.stats`

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/controller/KnowledgeBaseController.java:108-119`。

1. 第 108 行映射 `/stats`；第 109-110 行读取用户头。
2. 第 111 行调用 service.list(userId)，得到当前用户全部 View。
3. 第 112-118 行构造 Map：size 为 total；过滤 COMPLETED 为 completed；PROCESSING 或 PENDING 为 processing；FAILED 或 DELETE_FAILED 为 failed。
4. 所有 stream.count 返回 long；第 119 行结束。

#### 3.2.2 `KnowledgeBaseService.list` 两个重载

文件：`KnowledgeBaseService.java:128-153`。

1. 单参数重载第 128-130 行委托 `list(userId,"time",null)`。
2. 三参数重载第 133-138 行验证状态；第 139-146 行建立 time comparator。
3. 第 147 行 identity.require 后按 owner 查询；null vectorStatus 使第 148-149 行保留全部；第 150-152 行排序、toView、toList。

#### 3.2.3 `toView` 与 View 访问器

文件：`KnowledgeBaseService.java:225-233`；`dto/KnowledgeBaseView.java:7-22`。

1. toView 调实体 getter 构造 KnowledgeBaseView。
2. Controller 的 `item.vectorStatus()` 是 Java record 访问器，逐条返回对应状态字符串；没有数据库或 Python 调用。

### 3.3 Python 边界

1. stats 复用 Java list，不调用 Python 实时统计；向量状态以 Java 数据库最后写入值为准。
2. 调用链不含 PythonAgentClient、RabbitTemplate、indexRag 或 `/v1/**`，Java→Python 次数为零。

## 4. 审核结论

1. 已覆盖前端并行加载、Java 全列表投影和四项内存统计。
2. 所有可达项目函数均注明文件/行号；确认不调用 Python。
