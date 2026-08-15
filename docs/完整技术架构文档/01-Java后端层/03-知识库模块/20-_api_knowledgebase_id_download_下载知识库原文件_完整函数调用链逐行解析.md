# GET /api/knowledgebase/{id}/download：下载知识库原文件完整函数调用链逐行解析

> 当前实现从 knowledge_bases 行的 `original_bytes` 返回原文件；早期/缺失字节时以解析文本 UTF-8 回退。接口不调用 Python、RabbitMQ 或业务 Redis。

## 1. 接口定义

### 1.1 功能与作用

`GET /api/knowledgebase/{id}/download` 验证当前用户所有权后，以数据库中保存的 MIME 与文件名发送附件。原始 bytes 缺失时返回文本回退，保证历史记录仍可下载。

### 1.2 基本信息

| 项目 | 当前实现 |
| --- | --- |
| 路径 | `GET /api/knowledgebase/{id}/download` |
| Controller | `KnowledgeBaseController.download`，`KnowledgeBaseController.java:66-74` |
| 响应 | `ResponseEntity<byte[]>`，attachment |
| 回退 | MIME→text/plain；文件名→knowledge-base-{id}.txt；bytes→content UTF-8/空数组 |
| Python/MQ | 无调用。|

### 1.3 前端入口

`frontend/src/api/knowledgebase.ts:113-119` 以 Axios Blob 请求下载路径，返回原始 Blob。

## 2. 函数调用链

```text
knowledgeBaseApi.downloadKnowledgeBase -> Axios blob get -> request interceptor
  -> RequestIdFilter -> SimpleRateLimitFilter -> IdempotencyFilter(GET skip)
  -> KnowledgeBaseController.download -> KnowledgeBaseService.download
     -> required -> KnowledgeBaseRepository.findById -> UserIdentityResolver.require
  -> DownloadedDocument -> ResponseEntity<byte[]> -> browser download
```

## 3. 函数解析

### 3.1 前端与 Java Web 函数

#### 3.1.1 `downloadKnowledgeBase`、请求和过滤器

**文件与行号：** `frontend/src/api/knowledgebase.ts:113-119`，`frontend/src/api/request.ts:47-72、123-154`。

1. 第 113 行声明 Blob 函数；第 114 行调用 Axios URL；第 115-117 行指定 blob/跳过 JSON 解包；第 118 行返回 Blob。
2. request.ts 第 47-72 行生成/读取用户 ID、写 requestId；Blob 无 code 时成功拦截器原样返回，错误拦截器尝试解码错误体。
3. Java RequestId/限流分别在 `infrastructure/web/RequestIdFilter.java:23-41`、`ratelimit/SimpleRateLimitFilter.java:48-82`；GET 由 `IdempotencyFilter.java:41-44` 跳过。

#### 3.1.2 `KnowledgeBaseController.download`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/knowledgebase/controller/KnowledgeBaseController.java:66-74`。

1. 第 66 行映射路径；第 67-68 行绑定 long id/用户头。
2. 第 69 行调用服务。第 70-73 行按 DownloadedDocument 的 contentType、filename、content 构造 200 PDF/文本等二进制附件；第 74 行结束。

### 3.2 Java 授权与字节回退函数

#### 3.2.1 `KnowledgeBaseService.download`

**文件与行号：** `java-backend/src/main/java/com/interviewguide/knowledgebase/service/KnowledgeBaseService.java:159-169`。

1. 第 160 行调用 `required(Long.toString(id), userId)`，先完成存在与所有权校验。
2. 第 161-162 行 contentType 空时回退 text/plain。第 163-164 行原文件名空时回退稳定的 `.txt` 名称。
3. 第 165 行读取 originalBytes。第 166-168 行构造 record：bytes 存在则原样用；bytes 缺失且 content 存在则 UTF-8 编码；两者皆无返回空数组。第 169 行结束。

#### 3.2.2 `required` 与 MyBatis Mapper

**文件与行号：** `KnowledgeBaseService.java:228-235`，`KnowledgeBaseRepository.java` 的 `findById`，`resources/mapper/knowledgebase/KnowledgeBaseRepository.xml`。

1. 第 229 行按 ID 查询，空 Optional 第 230 行抛 `KNOWLEDGE_BASE_NOT_FOUND`。
2. 第 231 行 `identity.require` 后比较 ownerId；第 232 行越权抛 `KNOWLEDGE_BASE_ACCESS_DENIED`；第 234 行返回实体。
3. Mapper 负责按主键读取 bytes/content；它是 MyBatis SQL，不是 JPA 懒加载。

## 4. 主流构建分析

将原始字节存数据库便于事务一致性和小文件实现，但大文件会膨胀表、备份和内存响应。

主流生产方案是对象存储保存原文件，数据库只存 key/校验和，服务授权后签发短期预签名 URL 或流式代理。优点是扩容和大文件更好；缺点是多资源一致性、凭据和清理更复杂。

本项目小型文档库可保留字节列。文件规模增长时应抽象存储接口，保留当前 DB 方案为开发 profile，生产换 MinIO/S3；下载前仍复用 `required`，并在对象 key 缺失时返回明确错误而非泄露其他用户资源。
