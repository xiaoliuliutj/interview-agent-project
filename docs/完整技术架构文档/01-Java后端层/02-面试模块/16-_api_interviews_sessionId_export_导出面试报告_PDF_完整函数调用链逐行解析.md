# GET /api/interviews/{sessionId}/export：导出面试报告 PDF 完整函数调用链逐行解析

> 当前导出在 Java 线程内使用 PDFBox 生成字节流；它先读/授权面试详情，再调用报告服务。不会调用 Python 或 RabbitMQ。

## 1. 接口定义

### 1.1 功能与作用

接口将面试状态、题数、最终评价和每轮问答导出为 PDF 附件。报告服务尝试配置的 CJK 字体及候选字体路径；没有可用字体或 PDF 写入失败时返回业务错误，而不是输出不可读文件。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 路径 | `GET /api/interviews/{sessionId}/export` |
| Controller | `InterviewController.export`，`InterviewController.java:95-104` |
| 响应 | `application/pdf` + attachment `interview-{sessionId}.pdf` |
| 输入数据 | 详情 session/turns，PDF 字体配置 `agent.pdf-font-path` |
| Python/MQ | 无调用；使用已经持久化的报告数据。|

### 1.3 前端入口

`InterviewHistoryPage` 调用 `historyApi.exportInterviewPdf`；API 位于 `frontend/src/api/history.ts:96-99`，以 Blob 读取文件。

## 2. 函数调用链

```text
historyApi.exportInterviewPdf -> Axios get(blob) -> request interceptor
  -> RequestIdFilter -> SimpleRateLimitFilter -> IdempotencyFilter(GET skip)
  -> InterviewController.export -> UserIdentityResolver.require -> InterviewService.detail
     -> ownedSession/load/turns -> MyBatis session/turn SQL -> toView/parseFinalEvaluation
  -> InterviewReportPdfService.render
     -> fontCandidates -> renderWithFont -> reportLines -> addPages -> wrap
  -> ResponseEntity<byte[]> -> browser Blob download
```

## 3. 函数解析

### 3.1 前端与 HTTP 入口函数

#### 3.1.1 `historyApi.exportInterviewPdf` 和 Axios Blob 请求

**文件与行号：** `frontend/src/api/history.ts:96-99`，`frontend/src/api/request.ts:47-72、123-154`。

1. API 第 96 行声明 Blob 导出；第 97 行底层 Axios GET 设 `responseType: blob` 和 `skipResultTransform`；第 98 行返回 byte Blob。
2. 请求拦截器第 47-72 行生成/读取客户端用户 ID、写 X-User-Id 和 X-Request-Id。PDF Blob 不含 code，成功拦截器第 125-134 行原样放行；失败拦截器第 136-154 行可解码 Blob 错误体。

#### 3.1.2 RequestId、限流和 `InterviewController.export`

**文件与行号：** `RequestIdFilter.java:23-41`、`SimpleRateLimitFilter.java:48-82`、`IdempotencyFilter.java:41-44`，目录 `java-backend/src/main/java/com/interviewguide/infrastructure/`；`InterviewController.java:95-104`。

1. RequestId filter 规范/回传 ID、MDC 清理；限流用 Redis INCR 或本机回退并可返回 429；GET 被幂等过滤器跳过。
2. Controller 第 95-97 行绑定 sessionId/用户头；第 98 行调用 `detail` 取得已授权详情；第 99-100 行把报告服务需要的 ID、状态、题数、turns、最终评价传给 `render`。
3. 第 101-103 行创建 PDF content-type、附件头和 body；第 104 行结束。不存在 `ApiResult` 包装。

### 3.2 详情读取与 PDF 构建函数

#### 3.2.1 `InterviewService.detail`、`ownedSession`、`turns` 与 `toView`

**文件与行号：** `InterviewService.java:112-123、185-191、238-257`，`InterviewSessionPersistenceService.java:185-191`。

1. `detail` 第 113 行先 `ownedSession`；第 114-115 行读取 turns；第 116-121 行按时间顺序转为 indexed turn；第 122 行返回详情。
2. `ownedSession` 调用 load/owner 校验。`load` 第 189-191 行 MyBatis 查 session，turns 第 185-187 行 MyBatis 按 session/time 查 turn。
3. `toView` 第 238-248 行复制会话字段并调用 `parseFinalEvaluation`；后者第 249-257 行空/非法 JSON 返回空 Map。所有读取不调用 Python。

#### 3.2.2 `InterviewReportPdfService.render` 与字体函数

**文件与行号：** `java-backend/src/main/java/com/interviewguide/interview/service/InterviewReportPdfService.java:35-87`。

1. `render` 第 35 行接收报告输入；它调用 `fontCandidates` 获得配置字体及候选路径，逐一尝试 `renderWithFont`，成功即返回 bytes，失败记录/继续尝试，全部失败抛出报告导出错误。
2. `fontCandidates` 第 52-63 行收集配置路径和运行环境候选 CJK 字体，过滤不存在/重复路径，保证 Unicode 字符有字体支持。
3. `renderWithFont` 第 64-87 行创建 PDF/输出流、加载 Type0 字体、调用 `reportLines` 和 `addPages`，保存后返回 bytes；IOException 被上层转换为业务错误。

#### 3.2.3 `reportLines`、`addPages` 与 `wrap`

**文件与行号：** `InterviewReportPdfService.java:88-`。

1. `reportLines` 把 session ID、状态、题目数量、最终评价和每个 turn 格式化为报告文本行；null 评价/回答以安全空文本处理。
2. `addPages` 根据页面行容量创建/关闭 PDF content stream，设置字体、字号、行距和起点，再逐行写出；满页后新建页。
3. `wrap` 将超长文本分段，避免 PDF 单行溢出。三者均只处理内存文本，不读数据库或网络。

## 4. 主流构建分析

同步 PDFBox 导出优点是无额外基础设施、报告与当前详情一致；缺点是长报告在 Web 线程中占内存，字体探测依赖容器镜像，固定文本换行不具备专业排版。

主流替代是异步导出到对象存储或使用模板/HTML-to-PDF 渲染服务。优点是可扩容、易做复杂样式和下载链接；缺点是引入队列、对象存储、模板安全和任务清理。

本项目小规模时可保持当前实现。部署稳定后应在镜像中明确安装/挂载 CJK 字体；大报告场景可复用 Outbox 任务与 MinIO，报告生成后返回短期下载 URL，并保留当前 detail 授权校验。
