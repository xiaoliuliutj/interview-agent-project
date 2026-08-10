# Java 上层构建笔记

本文记录当前实际运行的 Java 上层实现。已删除的旧文本面试接口、`Legacy*` DTO、
`InterviewTask*` 和 `LegacyInterviewFacade` 不再作为设计依据。

知识库删除采用显式状态：先标记 `DELETING`，再无条件调用 Python 删除该知识库向量，成功后删除上层记录；向量删除是幂等的，不能依据上层 `chunkCount` 跳过清理。失败则保留 `DELETE_FAILED` 和错误原因，便于用户重试。`DELETING` 与 `DELETE_FAILED` 都表示删除意图仍在，索引消费者在开始和下层索引返回后都会拒绝这两种状态，避免已删除资料被迟到任务重新写回。

## 1. 职责边界

- Java 管理身份、简历文件、候选人、文本面试会话、前端可见问答、用户知识库和异步任务状态。
- Python 管理 Agent 计划、评分、短长期记忆、RAG 检索、检索缓存和模型调用。
- 文本面试中，评分、记忆、检索证据和最终评分结构都不从 Python 传给 Java；Java 仅保存问题、候选人回答、会话状态和版本号。

## 2. 简历主链路

`POST /api/resumes/upload` 接收文件和必填 `targetRole`。Java 用 Tika 提取文本并持久化原文件；
解析成功后创建 `ResumeAnalysisEntity`，再经 RabbitMQ 异步调用 Python 简历评价接口。

- 每个用户复用同一 `CandidateEntity`，`currentResumeId` 指向最新简历。
- 新简历上传会取消该候选人旧版本尚未完成的分析任务；消费者在执行前、下层画像激活后和模型返回后都会检查当前简历指针。
- 对同一当前简历重新分析时，也会先取消该简历仍在运行的旧分析任务，确保一个简历版本同时只有一个可写分析任务。
- 消费者在调用简历评估前，先调用下层 `agent.resume.activate`，把最新版设为唯一可写画像版本；旧模型调用即使迟到，也会被下层拒绝写入画像。
- 相同文件可复用已存储文件与文本，但仍按本次 `targetRole` 取消旧分析并新建分析任务。
- 上传会先解析并计算文件哈希，再判断当前用户是否重复；只有新版本才以真实 `resumeId` 建立独立文件路径。不同简历版本不共享可删除的物理文件，因此删除一个版本不会误删其他版本文件。
- 简历分析记录 `retryCount`、`lastAttemptAt` 和错误信息。网络、超时、下层 5xx 在 Rabbit 上限内重试；不可重试错误或达到上限后才标记 `FAILED`。
- 原文件可下载，提取文本和结构化分析结果可浏览；旧版本可以审计，但不能重新分析或用于创建新面试。
- 删除当前简历时，Java 会先取消其未完成分析任务、删除关联会话与原文件，再把
  `CandidateEntity.currentResumeId` 切换到该候选人仍存在的最高版本简历；若已无简历则清空
  指针。已经进入 RabbitMQ 的删除/取消任务消息属于过期消息，消费者直接忽略，不作为
  失败任务重试。

## 3. 文本面试主链路

唯一公开接口前缀是 `/api/interviews`。

1. Java 校验当前简历、目标岗位、题量、时长、难度和系统知识库状态；空解析文本直接拒绝，
   不把空字符串、固定时长或固定难度交给下层兜底。
2. 通过同一组真实参数调用 Python 初始化 Agent。`questionCount`、时长、难度、简历文本、
   自定义分类和两类知识库 ID 都是显式契约字段；自定义 JD 原文会原样传递，未填写时明确
   传递 `null`，不再用空字符串伪造输入。下层缺少任一必需字段时返回参数错误。
3. 每次回答必须携带 `runId`。Java 先调用下层，再以 `stateVersion` 和 `runId` 保存可见轮次。
4. 同一 `runId` 只能复用相同会话和相同回答；前端网络失败、刷新后会复用该 ID，避免 Python 已推进、Java 未落库时重复推进。
   Java 在比较 JPA 会话版本之前先检查已保存的 `runId`，因此“Java 已成功落库、但响应在返回前丢失”的重放会直接返回已有轮次，而不是误报并发修改。
5. Java 接收下层成功结果时，除检查业务码外，还校验回显的 `userId`、`sessionId` 与 `runId`。身份不一致的下层响应不得写入会话、简历分析或用户画像相关状态。
6. Java 网关在发起下层调用前执行 Bean Validation；内部 DTO 的必填字段、知识库 ID 数量、时间戳和文本输入与 Python 契约同时校验，避免参数遗漏被延迟到远程服务后才发现。
5. 提前结束、删除、详情、未完成会话恢复和只含问答记录的 PDF 导出均由当前接口实现。

上层通过 JPA `@Version` 和显式版本比对检测并发修改，不静默覆盖会话。

### 3.1 问答序列与前端恢复

回答接口现在返回 `InterviewDetailView`，其中同时包含更新后的会话视图和已经落库的全部可见轮次。前端不再用单独的当前题目重建历史，而是以该详情作为唯一序列来源，因此题号、已回答数量和当前阶段不会在提交后重置。进度只统计主问题，追问不会把进度条推进到超过总题量；页面顶部只展示当前阶段。

## 4. RAG 与知识库

- 用户知识库仍支持上传、下载、分类、查询、删除和重新索引。
- 系统知识库使用 `scripts/import-system-knowledge-base.sh` 走真实上传和异步索引链路；脚本只在索引完成后输出 `AGENT_SYSTEM_KNOWLEDGE_BASE_IDS`。
- 创建文本面试前，`InterviewKnowledgeBaseSelectionService` 要求所有配置的系统知识库已经 `COMPLETED`，并收集当前用户已索引知识库 ID。两类 ID 作为初始化快照传给 Python。
- Java 不根据知识库内容决定评分、追问或题目方向。
- 用户知识库索引状态真实经历 `PENDING → PROCESSING → COMPLETED | FAILED`；重新索引会重置为 `PENDING`，删除意图一旦进入 `DELETING/DELETE_FAILED` 则不能被迟到索引覆盖。前端只展示真实索引数量和状态，不再展示没有写入来源的访问/提问次数。

## 5. 异步与失败处理

- Rabbit 队列的消息只携带任务类型、资源 ID 和用户 ID；消费时重新读取数据库，避免传递实体或大文本。
- `AgentCallExecutor` 对网络异常和下层可重试错误做有限同步重试；Rabbit 为简历分析与知识库索引提供异步重试。
- 任务投递失败会同步写入失败状态；取消任务、无效消息、权限错误和结构化输出错误不会被无意义重试。
- 队列消费者会记录并确认格式不完整、资源 ID 非法或任务类型不支持的消息；知识库索引只将可重试的下层网络/模型故障重新抛给 Rabbit，参数和契约错误已标记失败后直接确认。

## 6. 验证与部署

- 新增 Java 单元测试覆盖简历任务的“重试仍可执行”与“取消后不可再执行”状态规则。
- 本机未配置可执行 Maven；Java 的完整编译和容器验证需在虚拟机执行：

  ```bash
  cd ~/interviewGuide/infrastructure
  docker compose build java-backend
  docker compose up -d
  ```

- 部署前必须在 `infrastructure/.env` 配置 `AGENT_SYSTEM_KNOWLEDGE_BASE_IDS`，并保证其中每个系统知识库已完成向量索引。
- 已存在 PostgreSQL 数据卷时，初始化目录不会自动重放。先执行
  `bash scripts/apply-db-upgrade.sh`，再重建 Java/Python 服务；该脚本只补齐缺失结构，
  并为旧候选人回填最高版本简历指针，不删除历史数据。

## 7. 已记录的坑

- Python 下层契约使用 `operation`、`subjectType` 等字段区分资源操作。Java 的
  `record` 是位置构造器，新增字段后必须同步检查每一个构造调用；简历异步消费者曾
  少传这两个字段，修复后显式发送 `agent.resume.evaluate` 和 `RESUME`，避免字段错位
  或下层参数校验失败。
- Python 的参数校验响应是统一 JSON 搭配 HTTP 400；Java 网关不能把所有
  `RestClientException` 都当作可重试故障。现在 HTTP 4xx 不重试，HTTP 5xx 和无响应的
  网络异常才进入重试器，避免无效请求放大下层压力。
## 8. Java 调用 Python 的 HTTP 协议

- 容器联调中，Java 默认 HTTP 客户端会尝试 `h2c`（HTTP/2 明文升级），而 Uvicorn 下层服务只按 HTTP/1.1 接收请求。日志中出现 `Unsupported upgrade request` 或 `Invalid HTTP request received` 时，FastAPI 会拿不到可校验的 JSON 请求体，并以 HTTP 400 返回空的请求标识字段。
- `AgentHttpConfiguration` 显式使用 JDK `HttpClient.Version.HTTP_1_1`。这是上下层服务之间的协议适配，不改变业务 JSON 契约；之后排查下层 400 时，应先检查 Python 日志是否仍出现上述协议告警。
