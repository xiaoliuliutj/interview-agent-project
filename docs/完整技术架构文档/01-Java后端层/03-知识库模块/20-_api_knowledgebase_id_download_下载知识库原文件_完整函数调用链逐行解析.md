# GET /api/knowledgebase/{id}/download：下载知识库原文件的完整函数调用链

## 1. 接口定义

接口在验证知识库属于当前用户后返回原始上传字节、内容类型和原始文件名。旧记录没有 originalBytes 时退化为把解析正文以 UTF-8 输出。它不访问 Python 向量库。

| 项目 | 内容 |
| --- | --- |
| 方法/路径 | GET `/api/knowledgebase/{id}/download` |
| 输出 | `ResponseEntity<byte[]>` |
| 下载名 | originalFilename，缺失时 `knowledge-base-{id}.txt` |
| MIME | contentType，缺失时 text/plain |
| Python 调用 | 无 |

## 2. 函数调用链

~~~text
KnowledgeBaseManagePage.handleDownload → knowledgeBaseApi.downloadKnowledgeBase
 -> request.getInstance().get(blob) → Axios 拦截器
 -> RequestIdFilter → SimpleRateLimitFilter
 -> KnowledgeBaseController.download
 -> KnowledgeBaseService.download → required
    -> Repository.findById → UserIdentityResolver.require → Entity.getOwnerId
    -> Entity.getContentType/getOriginalFilename/getOriginalBytes/getContent
 -> new DownloadedDocument → ResponseEntity.ok/contentType/header/body
 -> 浏览器 Blob URL 下载
~~~

## 3. 函数解析

### 3.1 前端函数

#### 3.1.1 `KnowledgeBaseManagePage.handleDownload`

文件：`frontend/src/pages/KnowledgeBaseManagePage.tsx:231-245`。

1. 第 231 行接收知识库行；第 233 行 await downloadKnowledgeBase(kb.id)。
2. 第 234 行创建 Blob URL；第 235-237 行创建 a、设置 href 和 originalFilename。
3. 第 238-241 行插入 DOM、click、移除并 revoke URL。
4. 第 242-244 行捕获并记录错误；第 245 行结束。

#### 3.1.2 `knowledgeBaseApi.downloadKnowledgeBase` 与 Axios

文件：`frontend/src/api/knowledgebase.ts:113-119`；`api/request.ts:47-73、123-155、180-182`。

1. 第 113 行声明 Blob 返回；第 114-117 行 GET 下载路径，responseType=blob 且 skipResultTransform；第 118 行返回 data。
2. getInstance 第 180-182 行返回共享 instance；请求拦截器第 64-73 行写 X-User-Id/X-Request-Id。
3. Blob 不满足 ApiResult 对象条件，成功回调第 134 行原样返回；失败回调可解析 JSON Blob 错误。

### 3.2 Java 函数

#### 3.2.1 `KnowledgeBaseController.download`

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/controller/KnowledgeBaseController.java:66-74`。

1. 第 66 行映射 `/{id}/download`；第 67-68 行绑定 long id 和身份头。
2. 第 69 行 service.download；第 70-73 行以 document.contentType、filename、content 构造附件响应；第 74 行结束。

#### 3.2.2 `KnowledgeBaseService.download`

文件：`java-backend/src/main/java/com/interviewguide/knowledgebase/service/KnowledgeBaseService.java:155-165`。

1. 第 156 行 required(Long.toString(id),userId) 验证实体。
2. 第 157-158 行 contentType 为 null/blank 时选 text/plain，否则保留实体值。
3. 第 159-160 行 filename 空时生成回退名，否则使用 originalFilename。
4. 第 161 行读取 originalBytes；第 162-164 行构造 DownloadedDocument。original 为 null 时，content 也 null 则空 byte[]，否则 content UTF-8；原始字节存在则原样返回。
5. 第 165 行结束。

#### 3.2.3 `KnowledgeBaseService.required` 与 `UserIdentityResolver.require`

文件：`KnowledgeBaseService.java:216-223`；`common/security/UserIdentityResolver.java:14-19`。

1. required 第 217-218 行 Repository.findById，缺失抛 KNOWLEDGE_BASE_NOT_FOUND。
2. 第 219-221 行 require 用户 ID 并与 entity.getOwnerId 比较，不符抛 KNOWLEDGE_BASE_ACCESS_DENIED；第 222 行返回实体。
3. identity.require 第 15-17 行拒绝 null/blank，第 18 行 strip，第 19 行返回 owner。

#### 3.2.4 实体 getter 与 `DownloadedDocument`

文件：`KnowledgeBaseEntity.java:91-112`；`KnowledgeBaseService.java:28`。

1. getOwnerId、getContentType、getOriginalFilename、getOriginalBytes、getContent 均单句返回对应字段，无副作用。
2. DownloadedDocument 是 Java record，编译器生成 filename/contentType/content 访问器；Controller 使用三个访问器组装响应。

### 3.3 Python 边界

1. download 下游仅有 required、实体 getter 和 ResponseEntity。
2. 没有 PythonAgentClient、deleteRag/indexRag、RabbitTemplate 或 `/v1/**` 调用，Java→Python 次数为零。

## 4. 审核结论

1. 已覆盖 Blob 下载、所有权校验、原始字节/正文回退和附件响应。
2. 所有可达项目函数均给出文件、行号和逐句说明；已确认不调用 Python。
