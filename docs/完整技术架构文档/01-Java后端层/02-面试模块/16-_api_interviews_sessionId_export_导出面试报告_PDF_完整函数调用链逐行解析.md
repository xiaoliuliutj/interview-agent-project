# GET /api/interviews/{sessionId}/export：导出面试报告 PDF 的完整函数调用链

## 1. 接口定义

接口从 Java 已保存的面试详情生成候选人可见的 PDF 报告：会话基本信息、每轮问题/回答/评价和最终评价。PDFBox 在 Java 内存中生成 `byte[]`；不调用 Python、RAG 或模型。

| 项目 | 内容 |
| --- | --- |
| 方法/路径 | GET `/api/interviews/{sessionId}/export` |
| Controller | `InterviewController.export` |
| 输出 | `ResponseEntity<byte[]>`，application/pdf |
| Service | `InterviewService.detail`、`InterviewReportPdfService.render` |
| Python 调用 | 无 |

## 2. 函数调用链

~~~text
InterviewHistoryPage.exportPdf → historyApi.exportInterviewPdf
 -> request.getInstance().get(blob) → Axios 请求拦截器
 -> RequestIdFilter → SimpleRateLimitFilter
 -> InterviewController.export → UserIdentityResolver.require
 -> InterviewService.detail → ownedSession/load → turns → toView/parseFinalEvaluation
 -> InterviewReportPdfService.render
    -> fontCandidates → renderWithFont
       -> reportLines → addPages → wrap
 -> ResponseEntity.ok/contentType/header/body
 -> Blob URL 下载
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `InterviewHistoryPage.exportPdf`

文件：`frontend/src/pages/InterviewHistoryPage.tsx:62-75`。

1. 第 62 行定义异步导出函数；第 63 行记录正在导出的 sessionId。
2. 第 65 行 await `historyApi.exportInterviewPdf`；第 66 行创建 Blob URL；第 67-70 行创建锚点、赋 href/download、click 触发下载。
3. 第 71 行 revoke 临时 URL；第 72-74 行 finally 清空 exporting，无论成功失败均恢复按钮。

#### 3.1.2 `historyApi.exportInterviewPdf` 和 Axios

文件：`frontend/src/api/history.ts:96-99`；`api/request.ts:47-73、123-155、180-182`。

1. 第 96 行声明 Blob 返回；第 97 行从 getInstance 取得共享 Axios，GET export 路径并要求 `responseType:'blob'`、`skipResultTransform:true`；第 98 行返回 response.data。
2. getInstance 第 180-182 行返回 instance；请求拦截器第 64-73 行写 X-User-Id/X-Request-Id。
3. 成功响应拦截器第 124-134 行发现 Blob 不含 code，原样返回；失败回调第 136-153 行把 JSON Blob/HTTP 错误转成 ApiRequestError。

### 3.2 Java 详情读取函数

#### 3.2.1 `InterviewController.export`

文件：`java-backend/src/main/java/com/interviewguide/interview/controller/InterviewController.java:95-104`。

1. 第 95 行映射 export；第 96-97 行绑定 sessionId/用户头。
2. 第 98 行 require 后调用 detail，保证报告经过相同归属校验。
3. 第 99-100 行调用 reportPdfService.render，传 status、总题数、turns 和 finalEvaluation。
4. 第 101-103 行创建 200、PDF MIME、附件文件名 `interview-{id}.pdf` 和内容；第 104 行结束。

#### 3.2.2 `InterviewService.detail` 及其下游

文件：`InterviewService.java:112-123、185-191、238-255`；`InterviewSessionPersistenceService.java:179-186`。

1. detail 第 113 行 ownedSession；第 114-115 行读取 turns；第 116-121 行按顺序映射 InterviewTurnView；第 122 行构造 InterviewDetailView。
2. ownedSession 第 186-190 行 load 后比较 getUserId；load 第 183-186 行按主键查会话或抛 SESSION_NOT_FOUND。
3. turns 第 179-181 行按 createdAt 查回合；toView 第 238-247 行读取会话字段；parseFinalEvaluation 第 249-255 行空值为 null、JSON 失败也返回 null。

### 3.3 PDF 生成函数

#### 3.3.1 `InterviewReportPdfService.render` 与 `fontCandidates`

文件：`java-backend/src/main/java/com/interviewguide/interview/service/InterviewReportPdfService.java:36-63`。

1. render 第 38 行取 fontCandidates；第 39-42 行无可用 CJK 字体则抛 INTERVIEW_PDF_FONT_REQUIRED。
2. 第 43-49 行逐个尝试 renderWithFont，IOException 时记录警告并继续；第 50 行全部失败抛 INTERVIEW_PDF_EXPORT_FAILED。
3. fontCandidates 第 54 行创建列表；第 55 行加入配置字体；第 56-61 行检查三个系统 Noto 路径、去重后加入；第 62 行返回。

#### 3.3.2 `renderWithFont`

文件：`InterviewReportPdfService.java:65-88`。

1. 第 67 行声明 TTC collection；第 68 行 try-with-resources 创建 PDDocument 与内存输出流。
2. 第 70-80 行按扩展名加载 TTC 首字体或普通字体；TTC 为空第 74-76 行抛 IOException。
3. 第 82 行调用 reportLines/addPages；第 83-84 行保存并返回 byte[]。
4. 第 85-87 行 finally 关闭 collection，避免 TTC 文件句柄泄露。

#### 3.3.3 `reportLines`、`addPages`、`wrap`

文件：`InterviewReportPdfService.java:90-160`。

1. reportLines 第 92-98 行加入报告标题/会话信息；第 99-108 行对每个 turn 加阶段、问题、回答和非空评价；第 109-116 行有最终评价时加入分数、摘要、优缺点、建议；第 117 行返回。
2. addPages 第 121-143 行逐条 wrap；每页达到 48 行时第 126-137 行关闭旧 stream、新建页、设置字体/行距/坐标；第 139-141 行写文本和换行；第 144-149 行 finally 关闭最后 stream。
3. wrap 第 153-160 行将 null 变空串、替换 CR/LF，循环按 55 字符切行，并加入最后剩余行。

### 3.4 Python 边界

1. export 的 Controller 下游只有 detail 和 render；detail 查询 Java Repository，render 使用 PDFBox。
2. 源码没有 PythonAgentClient、HttpPythonAgentClient 或 `/v1/**` 调用，因此 Java→Python 次数为零。

## 4. 审核结论

1. 已覆盖 Blob 下载、授权详情读取、字体选择、PDF 文本分页和二进制响应。
2. 每个项目定义的可达函数均标明文件、行号及逐句作用；接口不调用 Python。
