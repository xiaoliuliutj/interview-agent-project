# 当前重构落地状态

本文补充 `PROJECT_SPECIFICATION.md`，记录已经落地的代码边界和可验证证据。代码修改仍需用户明确同意后才能提交或推送。

## 1. 目录与职责

```text
frontend/                 原 React 前端，保持页面与交互
java-backend/             Java 上层：业务、持久化、并发、异步、Gateway
python-agent/             Python 下层：Agent、Prompt、Skill、记忆、RAG、模型
infrastructure/           虚拟机/容器部署与数据库初始化
reference/                原始项目，只作迁移参考
```

Java 不保存 Python Agent 的 Prompt、Skill、RAG 决策或记忆上下文。Python 普通问答不接收上层历史消息；它按 `userId + sessionId` 恢复短期记忆，并按 `userId`恢复长期记忆。

## 2. Python 下层已落地能力

| 能力 | 代码位置 | 说明 |
| --- | --- | --- |
| 配置、契约、异常 | `app/core/` | 配置来自 `.env`/JSON；响应统一为 `AgentResponse`；业务码按首位分类 |
| 面试 Agent | `app/agent/interview/` | InterviewPlan、六阶段状态机、受约束决策和状态版本 |
| 双层记忆 | `app/agent/memory/` | 最近 5 轮短期窗口；用户级长期摘要、简历快照、偏好和薄弱点 |
| RAG | `app/agent/rag/` | 默认 800 Token 无重叠切片、批量 10、pgvector 检索、KB 过滤与本地回退 |
| HTTP 入口 | `app/api/application.py` | 健康检查、初始化、问答和统一异常响应 |
| 持久化 | `app/engineering/persistence/` | PostgreSQL 会话、长期记忆和 pgvector 仓库；无数据库时不伪造持久化 |
| 可靠性与幂等 | `app/engineering/reliability/`、`app/engineering/idempotency/` | LLM 有限异步重试；`runId` 保存稳定响应快照并防止重复推进 |

面试 Agent 的 RAG 只使用 `QUESTION_GENERATION` 和 `RESUME_EVALUATION`；另为原 React 知识库页面保留独立的 `KNOWLEDGE_BASE_QUERY` 检索验证用途。Embedding 未配置时 Agent 仍能运行基础流程，但不会伪造检索依据；配置后 Planner 和 Decision Agent 自动通过 Tool 获取检索证据。

## 3. Java 上层已落地能力

- `InterviewService` 负责用户、候选人、简历、JD 的业务校验和面试业务会话。
- `PythonAgentGateway` 负责固定 JSON 调用，不在 Java 重复实现 Agent。
- 下层问答响应的 `output.evaluationSummary`、`action` 和 `stage` 由 Java 持久化到面试轮次；它们是可展示 Agent 结果，不包含模型思维链。
- `AgentCallExecutor` 负责有限重试；仅网络异常和下层可重试 5xx 重试。
- `InterviewSessionEntity` 使用 JPA `@Version`，`InterviewSessionPersistenceService` 负责事务边界和并发版本校验。
- 文字面试兼容层已持久化会话列表、未完成会话恢复、答案草稿、提前结束、删除和问答历史；草稿不调用下层，正式答案才调用 Python Agent。
- 提前结束通过 `agent.session.complete` 同步关闭下层会话；关闭请求不带问答上下文，且不会删除按用户保留的长期记忆。
- `InterviewTaskEntity`、`InterviewAsyncWorker` 和线程池展示持久化异步任务生命周期。
- `LegacyInterviewController` 保留 React 原有的核心面试/简历上传路径，并通过 Facade 转换为新领域 DTO；`KnowledgeBaseController`、`RagChatController` 和 `InterviewScheduleController` 已提供旧路径适配。语音面试仍是说明书中定义的后续扩展，不纳入首期核心链路。

Java 源码尚未运行 Maven 编译/测试：当前 `D:\Maven\apache-maven-3.9.16` 是源码目录，缺少 `bin/mvn.cmd`，系统也未提供可用 JDK。环境补齐后必须执行编译、Gateway 契约测试、并发测试和异步任务测试。

部署资产已静态核对：Java 长文本实体显式映射为 PostgreSQL `TEXT`，避免 `@Lob` 与初始化脚本类型不一致；Compose 会等待 Python `/health` 通过后启动 Java，Nginx 对 `/api/` 关闭响应缓冲以支持 SSE。当前本机没有 Docker，未执行容器构建或启动验证。

## 4. 上下层关键链路

```text
React
  → Java POST /api/interviews
  → Java 校验并持久化业务会话
  → Java 调 Python /v1/agent/sessions/initialize（携带一次性资料快照）
  → Python 规划、写入 AgentSession 与长期简历记忆
  → Java 返回开场问题

React
  → Java POST /api/interviews/{sessionId}/answers
  → Java 校验用户和业务会话
  → Python /v1/agent/respond（只携带 userId/sessionId/runId/answer）
  → Python 读取短期/长期记忆、可选 RAG、调用模型并持久化
  → Java 以乐观锁保存业务轮次和状态
```

## 5. 验证记录

使用 `D:\Anaconda\envs\inter-guide\python.exe` 执行：

```text
python -m pytest tests -q       21 passed
python -m compileall -q app     通过
```

测试覆盖：面试阶段推进、重复会话、短期/长期记忆、统一 API 响应、RAG 切片、Embedding 批次、知识库过滤回退。PostgreSQL、真实 Embedding、Java 和完整容器启动仍属于待环境补齐后的集成验证。

真实模型验证：已使用当前本地 OpenAI-compatible 配置完成聊天连通、六阶段面试规划和单轮受约束 Decision Agent 决策验证；测试过程未输出敏感配置。数据库和 Embedding 未配置，因此上述验证不替代持久化/RAG 集成测试。

## 6. 虚拟机部署

`infrastructure/docker-compose.yml` 提供 PostgreSQL/pgvector、Redis、Python Agent、Java 上层和 React/Nginx 五个服务；`infrastructure/postgres/init/001-schema.sql` 初始化双方所需表和向量扩展。模型密钥只从虚拟机环境变量或未提交的 `.env` 注入，不进入镜像和 Git。
