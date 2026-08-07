# Java 后端构建笔记

本文件持续记录 Java 业务后端从零构建的过程。每个完成的功能都应同步补充，便于复盘、面试表达和后续维护。

## 1. 服务定位与职责边界

- 管理用户、简历、JD、面试会话、题目、回答和报告等业务领域数据。
- 负责业务事务、幂等、并发控制、异步任务、失败处理和对 Python Agent 服务的调用协作。
- 不直接管理 Agent 记忆、RAG 索引、Agent Run 或工具执行记录。

## 2. 模块构建记录

后续按模块追加：功能目标、实现方案、关键流程、数据与接口边界、工程化考虑、验证方式、面试表达要点和可改进项。

## 3. 已实现：上层骨架

### 业务与持久化

- 新建 Maven/Spring Boot 项目，包含候选人、简历、JD、面试会话、面试轮次和异步任务实体与仓库。
- `InterviewService` 在 Java 校验用户、候选人、简历与 JD 的归属关系；仅初始化时向 Python 发送资料快照，普通问答不转发上下文。
- `InterviewSessionEntity` 使用 `@Version`，会话更新前额外校验版本，发生竞争时明确返回并发修改错误，不静默覆盖。
- `LegacyInterviewFacade` 将原 React 的文字面试接口适配为 Java 业务操作：会话列表、未完成会话恢复、答案草稿、提前结束、删除、问答历史和报告查看均由 Java 持久化状态驱动；只有正式提交回答才进入 Python Agent。
- 草稿答案保存在 Java 会话中，正式回答成功后清除；提前结束先通过 `agent.session.complete` 关闭下层 Agent 会话，再提交 Java 业务完成状态。删除上层历史不会删除用户长期记忆；下层会话保留为已完成记录，后续可补充归档清理策略。

### Python Agent Gateway

- `PythonAgentGateway` 只调用 Python 的初始化与问答接口，复用约定的 `AgentResponse`；Java 中没有 Agent Prompt、Skill 或 RAG 决策代码。
- `AgentCallExecutor` 只对网络异常和下层可重试的 5xx 业务码做有限重试；参数错误、数据一致性错误不会被重试。
- `AgentResponse.output` 只接收可展示的 `evaluationSummary`、`action`、`stage`；Java 将评价摘要写入业务轮次，报告读模型从这些持久化轮次组装，不解析模型自由文本或保存思维链。

### 异步任务

- `InterviewTaskEntity` 先持久化面试初始化任务状态，再由 `InterviewAsyncWorker` 投递 RabbitMQ durable queue；可展示 `PENDING/RUNNING/COMPLETED/FAILED` 生命周期。
- 简历分析与知识库向量化也使用 RabbitMQ。消息仅保存任务类型、资源 ID 和用户 ID，消费者重新读取数据库记录，避免把 JPA 实体或大文件文本序列化进队列；失败采用有限重试与死信队列。
- `ResumeAnalysisEntity`、`ResumeAnalysisService` 和 `ResumeAnalysisWorker` 将简历评价作为独立异步任务；任务状态和结构化结果由 Java 持久化，模型调用和 RAG 证据仍由 Python 完成。投递失败会同步标记失败，不会留下无状态的 PENDING 记录。

### 知识库与排期兼容模块

- `KnowledgeBaseService` 保存用户归属、元数据、原文件和解析原文，`KnowledgeBaseIndexWorker` 通过 RabbitMQ 异步调用 Python 的 `rag.index`；Java 不实现切片或向量相似度。
- 知识库查询由 Python 在检索证据基础上生成回答，Java 不接触或拼接命中 chunk；`/query` 和 `/query/stream` 复用同一服务，流式接口当前发送一次完整回答而非伪造 Token 流。
- `RagChatService` 只管理知识库问答会话、消息、置顶和删除；`RagChatController` 用 `SseEmitter` 返回一次检索结果。它使用独立的 `KNOWLEDGE_BASE_QUERY` 用途，不把页面查询伪装成面试 Agent 的出题或简历评价。
- `InterviewScheduleService` 负责排期 CRUD、用户校验和时间范围校验；自然语言解析委托 Python 下层的结构化抽取 Agent，Prompt 位于配置目录。定时任务将超过结束时间的未取消日程推进为 COMPLETED。

## 4. 工程化专题

后续沉淀事务与一致性、并发与幂等、异步任务、服务失败处理、可观测性、配置与部署等实践。

## 5. 踩坑与解决记录

暂无。发现真实问题后按“现象 → 根因 → 解决措施 → 预防约束”补充。

## 6. 当前 Java 环境

- 用户提供的 `D:\\Maven\\apache-maven-3.9.16` 当前是 Maven 源码目录，不是包含可执行 `bin\\mvn.cmd` 的 Maven 发布版。
- 已使用 IntelliJ 自带 JBR 21（`D:\IdeaLij\IntelliJ IDEA 2025.3.3\jbr`）和 IDEA 内置 Maven 执行 `mvn test`，构建通过；当前还没有 Java 测试类，因此未运行任何测试用例。
- 虚拟机部署仍应安装独立的 JDK 21 与 Maven 3.9.x 二进制发行版，不依赖 IDEA 的内置运行时。
- PostgreSQL 初始化脚本使用 `TEXT` 保存简历、JD、问答、评价、知识库原文和排期文本；对应实体显式使用 `@Column(columnDefinition = "TEXT")`，避免 PostgreSQL 下 `@Lob` 的 OID/CLOB 校验差异。

### Docker 部署边界

- Java 运行镜像基于 JRE 21，额外安装 `curl` 用于容器健康检查；Spring Actuator 仅暴露 `health` 和 `info`，Compose 用 `/actuator/health` 判断 Java 是否可接收前端流量。
- Java 的 `8080` 端口只通过 Docker 内部网络暴露，React/Nginx 使用服务名 `java-backend` 反向代理 `/api/`；虚拟机对外仅开放前端 `80` 端口。
- Compose 等待 PostgreSQL、Redis、RabbitMQ 及 Python Agent 健康后才启动 Java，避免应用在依赖未就绪时产生启动失败或连接风暴。
- 一键脚本位于 `scripts/start.ps1` 和 `scripts/start.sh`；真实密钥、数据库密码与字体文件均由虚拟机本地提供，不进入镜像或 Git。
