# GET /api/interviews/unfinished/{resumeId}：查询未结束面试的完整函数调用链

## 1. 接口定义

该接口按当前用户和简历 ID 查找最近一条状态为 `INITIALIZING`、`ACTIVE` 或 `PAUSED` 的文字面试；没有匹配记录时成功返回 `data: null`。它用于前端决定继续已有会话还是创建新会话，只读取 Java 数据库，不调用 Python。

| 项目 | 内容 |
| --- | --- |
| 方法/路径 | GET `/api/interviews/unfinished/{resumeId}` |
| Controller | `InterviewController.unfinished` |
| Service | `InterviewService.findUnfinished` |
| 查询状态 | INITIALIZING、ACTIVE、PAUSED |
| 返回 | `ApiResult<InterviewView>` 或 `data:null` |
| Python 调用 | 无 |

## 2. 函数调用链

~~~text
interviewApi.findUnfinishedSession
 -> request.get → Axios 身份/响应拦截器
 -> RequestIdFilter.doFilterInternal → SimpleRateLimitFilter.doFilterInternal
 -> InterviewController.unfinished → UserIdentityResolver.require
 -> InterviewService.findUnfinished
    -> InterviewSessionPersistenceService.findUnfinished
       -> InterviewSessionRepository.findFirstByUserIdAndResumeIdAndStatusInOrderByCreatedAtDesc
    -> InterviewService.toView（存在时）→ parseFinalEvaluation → Session getter
 -> ApiResult.success
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `interviewApi.findUnfinishedSession`

文件：`frontend/src/api/interview.ts:102-105`。

1. 第 102 行接收 resumeId 并声明返回 InterviewSession 或 null。
2. 第 103 行调用 `request.get<InterviewView | null>`，将 resumeId 插入路径变量。
3. 第 104 行使用三元表达式：view 非空时调用 `toSession(view)`，空值直接返回 null；第 105 行结束。
4. `toSession` 位于同文件 41-51 行：把已有 turns 映射为问题，ACTIVE/PAUSED 时追加 currentQuestion，计算 currentQuestionIndex 后返回前端对象。本接口只有 InterviewView，没有 turns，因此不产生历史题目数组。

#### 3.1.2 `request.get`、ID 函数和拦截器

文件：`frontend/src/api/request.ts:47-73、123-160`。

1. request.get 第 158-160 行调用 Axios GET 并返回 response.data。
2. createClientId 第 47-50 行生成请求 ID；currentUserId 第 52-58 行读取或首次保存用户 ID。
3. 请求拦截器第 64-73 行写 X-User-Id、X-Request-Id；成功响应拦截器第 123-135 行对 code=200 解包 data；失败回调第 136-155 行生成 ApiRequestError。

### 3.2 Java 函数

#### 3.2.1 `InterviewController.unfinished` 与 `require`

文件：`java-backend/src/main/java/com/interviewguide/interview/controller/InterviewController.java:66-70`；`common/security/UserIdentityResolver.java:14-19`。

1. 第 66 行映射 `/unfinished/{resumeId}`；第 67-68 行绑定 resumeId 和用户头。
2. 第 69 行先 `identity.require(userId)`，再传入 service.findUnfinished，最后用 ApiResult.success 包装可空结果。
3. require 第 15-17 行拒绝 null/blank，第 18 行 strip，第 19 行返回 owner。

#### 3.2.2 `InterviewService.findUnfinished`

文件：`java-backend/src/main/java/com/interviewguide/interview/service/InterviewService.java:134-136`。

1. 第 134 行定义输入 owner 和 resumeId 的查询函数。
2. 第 135 行调用 persistence.findUnfinished，得到 Optional；有值时 map(this::toView)，无值时 orElse(null)。
3. 第 136 行结束。此函数不会验证 ResumeEntity 是否存在；查询条件按 resumeId 直接匹配会话历史。

#### 3.2.3 `InterviewSessionPersistenceService.findUnfinished`

文件：`java-backend/src/main/java/com/interviewguide/interview/service/InterviewSessionPersistenceService.java:170-177`。

1. 第 170 行定义返回 Optional 的只读函数。
2. 第 171-176 行调用项目声明的派生 Repository 查询，传入 userId、resumeId 和三个允许状态。
3. 方法名的 `findFirst...OrderByCreatedAtDesc` 使 Spring Data 只取最新一条；第 177 行结束。

#### 3.2.4 `InterviewService.toView` 与 `parseFinalEvaluation`

文件：`InterviewService.java:238-255`。

1. toView 第 239 行先解析 finalEvaluationJson；第 240-246 行调用 InterviewSessionEntity getter 构造完整 InterviewView；第 247 行结束。
2. parseFinalEvaluation 第 250 行对 null/blank 返回 null；第 251 行 ObjectMapper 解析 Map；第 252-254 行解析异常时返回 null，避免坏 JSON 影响恢复入口。
3. Session getter 位于 `InterviewSessionEntity.java:118-137`，每一行仅返回会话字段，无副作用。

#### 3.2.5 `ApiResult.success` 与 Python 边界

文件：`common/web/dto/ApiResult.java:3-6`。

1. 第 4-5 行构造 code=200、message=success、data=InterviewView 或 null 的结果。
2. Controller、Service、Persistence 的调用链不含 PythonAgentClient、HttpPythonAgentClient、RabbitTemplate 或 `/v1/**`，所以本接口 Java→Python 调用次数为零。

## 4. 审核结论

1. 已覆盖前端可空处理、身份校验、三状态 Repository 查询和 DTO 投影。
2. 所有可达项目函数均注明文件和行号，按源码语句解释。
3. 已确认该接口不调用 Python。
