# GET /api/resumes/{id}/export：导出简历分析 PDF 完整函数调用链

> 本文对应 Java 接口清单第 4 个接口。源码结论：请求只在 Java 后端完成授权、分析投影和 PDFBox 渲染，不调用 RabbitMQ、PythonAgentClient 或 Python Agent。

## 1. 接口定义

### 1.1 功能和作用

该接口读取当前用户拥有的指定简历及其最近一次分析结果，把简历正文、评分、摘要、优势和建议拼接为文本，使用配置的 CJK 字体生成 PDF 二进制并作为下载响应返回。它不会重新分析简历，也不会调用 Python。

### 1.2 基本信息

| 项目 | 内容 |
| --- | --- |
| HTTP 方法 | GET |
| 路径 | /api/resumes/{id}/export |
| 路径变量 | id，String 类型简历主键 |
| Controller | ResumeController.export |
| Service | ResumeService.export |
| 响应类型 | ResponseEntity<byte[]> |
| Content-Type | application/pdf |
| 下载文件名 | resume-{id}.pdf |
| Python 调用 | 无 |

### 1.3 前端入口

ResumeDetailPage.handleExportAnalysisPdf 在点击“导出分析报告”后调用 historyApi.exportAnalysisPdf。前端以 responseType=blob 和 skipResultTransform=true 请求二进制，收到 Blob 后创建临时 URL，触发浏览器下载，再释放 URL。

## 2. 函数调用链

~~~text
ResumeDetailPage.handleExportAnalysisPdf
 -> historyApi.exportAnalysisPdf
 -> request.getInstance
 -> Axios instance.get（responseType=blob）
 -> Axios request interceptor
    -> currentUserId -> createClientId
 -> GET /api/resumes/{id}/export
 -> RequestIdFilter.doFilterInternal -> normalize
 -> SimpleRateLimitFilter.doFilterInternal
 -> ResumeController.export
 -> ResumeService.export
    -> ResumeService.owned
       -> ResumeRepository.findById
       -> UserIdentityResolver.require
       -> ResumeService.owns
          -> CandidateRepository.findById
          -> CandidateEntity.getUserId
    -> ResumeAnalysisService.latest
       -> ResumeAnalysisPersistenceService.latest
       -> ResumeAnalysisRepository.findFirstByResumeIdOrderByCreatedAtDesc
       -> ResumeAnalysisService.toView
          -> ResumeAnalysisEntity getter
          -> stringList -> ObjectMapper.readValue 或 List.of
          -> mapList -> ObjectMapper.readValue 或 List.of
    -> ResumeEntity.getContent
    -> Files.isRegularFile
    -> PDDocument 构造
    -> PDType0Font.load
    -> ResumeService.addTextPages
       -> split / substring / PDPage / PDPageContentStream
    -> PDDocument.save
    -> ResponseEntity.ok/contentType/header/body
 -> 浏览器接收 byte[]，创建 Blob URL 并下载
~~~

链路在 Java 返回 ResponseEntity<byte[]> 后结束。若字体、PDFBox、数据库或授权失败，异常由 ApiExceptionHandler 处理；不存在 Python /v1 请求。

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 ResumeDetailPage.handleExportAnalysisPdf

文件：frontend/src/pages/ResumeDetailPage.tsx:114-131。

1. 第114行定义异步导出函数。
2. 第115行 setExporting('analysis')，显示导出中的状态。
3. 第117行 await historyApi.exportAnalysisPdf(resumeId)，等待 Blob。
4. 第118行 createObjectURL 把 Blob 映射为临时 URL。
5. 第119-121行创建 a、设置 href 和 download 文件名（优先 filename，否则 resumeId）。
6. 第122-125行插入 body、click 触发下载、移除元素并 revokeObjectURL。
7. 第126-127行捕获错误并 alert；第128-130行 finally 清空 exporting。

#### 3.1.2 historyApi.exportAnalysisPdf

文件：frontend/src/api/history.ts:88-91。

1. 第88行定义异步函数，参数 resumeId，返回 Blob。
2. 第89行调用 request.getInstance().get，路径为 /api/resumes/{id}/export；responseType='blob' 防止 PDF 按 JSON 解码，skipResultTransform=true 保留二进制。
3. 第90行返回 response.data；第91行结束。

#### 3.1.3 request.getInstance、createClientId、currentUserId 与拦截器

文件：frontend/src/api/request.ts:47-73、180-182。

1. getInstance 第180-182行返回共享 Axios instance。
2. createClientId 第47-50行优先 crypto.randomUUID，不支持时拼接 prefix、时间和随机十六进制值。
3. currentUserId 第52-58行读取 localStorage；缺失时生成、保存并返回用户 ID。
4. 请求拦截器第64-69行确保 headers 并定义兼容 AxiosHeaders 的 setHeader；第70-71行写 X-User-Id 和 X-Request-Id；第72行返回 config。

#### 3.1.4 二进制响应和错误函数

文件：frontend/src/api/request.ts:75-155。

1. 成功拦截器第124-134行读取 response；PDF 是 Blob，不满足 isRecord(result) 且有 code 的 JSON 条件，因此第134行原样返回。
2. isRecord 第75-77行拒绝 null、数组和非对象；stringValue 第79-81行只返回非空字符串。
3. parseApiError 第83-99行提取嵌套 error/code 并构造 ApiRequestError。
4. 失败拦截器第136-153行处理 AxiosError；无 response 调 transportError，有 response 调 decodeErrorData 和 parseApiError。导出失败回到 handleExportAnalysisPdf 的 catch。

### 3.2 Java Web 入口

#### 3.2.1 RequestIdFilter.doFilterInternal 与 normalize

文件：java-backend/src/main/java/com/interviewguide/infrastructure/web/RequestIdFilter.java:23-41。

1. 第25行读取 X-Request-Id 并调用 normalize。
2. normalize 第36-41行仅接受非空、长度不超过128且匹配正则的值；合法值第38行返回，非法值第40行生成 UUID。
3. 第26-28行把 ID 写入 request attribute、响应头和 MDC；第30行继续 filterChain；第31-33行 finally 清理 MDC。

#### 3.2.2 SimpleRateLimitFilter.doFilterInternal

文件：java-backend/src/main/java/com/interviewguide/infrastructure/ratelimit/SimpleRateLimitFilter.java:38-61。

1. 第40-43行只放行 health/actuator，export 路径继续限流。
2. 第44-47行按远端地址、URI 和当前分钟计算 Window。
3. 第48行原子计数并比较 limit；超限第49-58行返回429和结构化错误，正常第60行继续。

#### 3.2.3 ResumeController.export 与 ResponseEntity

文件：java-backend/src/main/java/com/interviewguide/resume/controller/ResumeController.java:51-55。

1. 第51行映射与类级路径组成 /api/resumes/{id}/export。
2. 第52行绑定 id 并声明 ResponseEntity<byte[]>。
3. 第53行读取 X-User-Id；第54行调用 resumeService.export；第55行结束。
4. Controller 不生成 PDF，ResponseEntity.ok/contentType/header/body 是 Spring 函数，项目传入 PDF MIME、附件名和 byte[]。

### 3.3 ResumeService.export

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:178-202。

1. 第178-179行声明导出函数。
2. 第180行调用 owned 完成存在性和归属校验。
3. 第181行调用 analysisService.latest 获取最近分析。
4. 第182行创建 StringBuilder，追加标题、换行和 resume.getContent；正文 null 转为空串。
5. 第183-187行在 analysis 非 null 时追加标题、overallScore、summary、String.join 优势和建议。
6. 第189-190行检查 pdfFontPath 非 null 且 Files.isRegularFile；失败抛 RESUME_PDF_FONT_REQUIRED。
7. 第192行 try-with-resources 创建 PDDocument 与 ByteArrayOutputStream。
8. 第193行 PDType0Font.load 加载 CJK 字体；第194行调用 addTextPages；第195行 document.save 写入内存流。
9. 第196-198行构造200、application/pdf、Content-Disposition和 byte[] 响应。
10. 第199-201行把 IOException 转成 RESUME_PDF_EXPORT_FAILED；第202行结束。

#### 3.3.1 owned、owns、require 与 Repository

文件：ResumeService.java:276-288；java-backend/src/main/java/com/interviewguide/common/security/UserIdentityResolver.java:14-19；java-backend/src/main/java/com/interviewguide/resume/mapper/ResumeRepository.java:8-12。

1. owned 第277行 findById；第278行不存在抛 RESUME_NOT_FOUND；第279行 require 后调用 owns；第280行不归属抛 RESUME_ACCESS_DENIED；第282行返回实体。
2. owns 第286行取 candidateId 查询 CandidateRepository.findById；第287行比较 candidate.userId，候选人缺失时 orElse(false)；第288行结束。
3. require 第15-17行拒绝 null/blank；第18行 strip；第19行返回规范化 ID。
4. ResumeRepository.findById 与 CandidateRepository.findById 是 JpaRepository 继承的只读主键查询；导出链不调用 save/delete。

### 3.4 最近分析投影

#### 3.4.1 latest 与 Persistence.latest

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:62-65；java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisPersistenceService.java:50-52；java-backend/src/main/java/com/interviewguide/resume/mapper/ResumeAnalysisRepository.java:10-14。

1. latest 第62行声明；第63行 persistence.latest；第64行无记录返回 null，有记录 toView；第65行结束。
2. Persistence.latest 第50行声明；第51行按 resumeId 和 createdAt 倒序查询第一条并 orElse(null)；第52行结束。
3. Repository 第12行的派生查询实现“按简历过滤、按创建时间倒序取第一条”。

#### 3.4.2 toView、stringList、mapList

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeAnalysisService.java:82-100。

1. toView 第83-85行读取 ID、状态、六项评分、summary、updatedAt；第86行两次调用 stringList；第87行调用 mapList、读取 error并构造 DTO。
2. stringList 第90-94行：第91行 null/blank 返回 List.of；第92行 ObjectMapper.readValue 解析 List<String>；第93行异常返回空列表。
3. mapList 第96-100行：第97行空值短路；第98行解析 List<Map<String,Object>>；第99行异常返回空列表。
4. ResumeAnalysisEntity getter（java-backend/src/main/java/com/interviewguide/resume/domain/ResumeAnalysisEntity.java:111-129）逐行返回分析字段；ResumeAnalysisView record（dto/ResumeAnalysisView.java:7-21）生成 overallScore、summary、strengths、suggestions 等访问器，export 第184-187行使用它们。

### 3.5 ResumeService.addTextPages

文件：java-backend/src/main/java/com/interviewguide/resume/service/ResumeService.java:204-224。

1. 第204行声明输入 document、font、text，可抛 IOException。
2. 第205-209行创建 lines，按换行 split；每个 source 超过55字符时循环 substring 切分，再加入剩余行。
3. 第211-213行初始化 page、stream、lineCount。
4. 第214-220行遍历 lines；无流或行数达到48时关闭旧流、创建 PDPage 和 PDPageContentStream，beginText，设置字体10、行距14和起点(40,750)。
5. 第221行把制表符换成两个空格，showText 写入、newLine 换行并递增计数。
6. 第223行关闭最后一个流；第224行结束。该函数只排版，不访问数据库或 Python。

### 3.6 异常边界

文件：java-backend/src/main/java/com/interviewguide/common/web/ApiExceptionHandler.java:31-35、86-92、115-140。

1. handleBusiness 第32-35行保留业务 code、HTTP status、retryable 和 requestId，字体缺失会走这里。
2. handleDataAccess 第87-92行记录数据库异常并返回503。
3. handleUnexpected 第116-120行记录未预期异常并返回500。
4. response 第123-129行构造 ApiErrorDetail 和 ResponseEntity；requestId 第131-136行优先读过滤器 attribute，再读请求头或生成 UUID；firstNonBlank 第138-140行选择非空值。

## 4. 审核结论

1. 接口定义写明路径、变量、PDF 响应、授权和 Python 边界。
2. 调用链覆盖前端 Blob、请求 ID、限流、Controller、授权、分析投影、PDF 排版和二进制响应。
3. 每个本接口实际可达的项目函数均标出文件和行号，并逐项解释语句与失败分支。
4. ResumeController.java:51-55 与 ResumeService.java:178-224 证明该接口本地生成 PDF，没有 Python /v1 调用。
5. 本文审核通过后，下一接口按顺序为 GET /api/resumes/{id}/download。

