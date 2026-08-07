# Interview Agent Project：上下层重构项目说明书

> 本文档是重构后的权威设计说明。它定义目标架构、模块边界、数据归属、上下层契约和验收标准；其他专题文档记录具体实现过程和踩坑，不得与本文档冲突。

## 1. 项目目标与范围

### 1.1 项目目标

在保留原始 InterviewGuide 前端交互的基础上，将原本“Java 直接编排多个大模型调用”的实现改造成：

```text
React 前端
    ↓
Java 上层业务服务
    ↓ 标准 JSON / HTTP
Python 下层 Agent 服务
    ↓
模型、Tools、Skills、MCP、RAG 和 Agent 状态
```

目标不是堆叠模型调用，而是展示一个可以自主规划、根据回答决定追问或切换阶段、能够使用工具并维护会话状态的 Agent。

### 1.2 首期范围

- 保留原始 React 前端，必要时只做接口适配。
- Java 从零构建上层业务服务。
- Python 从零构建下层 Agent 服务。
- 完成简历/JD 资料、面试计划、六阶段问答、项目追问、基础题、场景题、算法题和总结报告的主链路。
- RAG 首期复用原项目的知识库处理思路，但用途改为题目构造和简历评价。
- 记忆、Tools、Skills、MCP、Agent Run 和基础可靠性逐步接入。
- 支持在虚拟机中部署和演示。

### 1.3 明确不做

- 首期不实现复杂多 Agent 编排。
- 不实现生产级权限中心、计费、租户治理和高可用集群。
- 算法题首期不执行候选人代码，不建立不必要的代码沙箱。
- 不将原始 Java 后端直接复制为新后端；原始代码只作为业务和工程参考。

## 2. 核心设计原则

1. 上层负责业务事实和业务持久化；下层负责 Agent 事实和 Agent 状态。
2. 上层不把 Java 实体、数据库表结构和业务内部字段泄漏给下层。
3. 下层不直接修改上层业务表，也不依赖上层数据库表。
4. 上层和下层使用版本化、稳定、最小化的 JSON 契约。
5. 普通问答请求不重复携带上下文；下层根据 `userId + sessionId` 恢复自己的 Agent 会话状态。
6. 面试整体阶段由状态机约束，阶段内部由受约束 Agent 决策。
7. 所有可修改的配置、Prompt、Skill 和知识资料使用外部文件；Python/Java 代码只加载和校验，不硬编码可变内容。
8. 先保证完整链路，再在 Agent 决策、RAG 或一致性中的一个方向深入。

## 3. 术语和身份

| 名称 | 说明 | 归属 |
|---|---|---|
| `userId` | 使用系统的用户身份 | 上层主数据，下层用于隔离 |
| `candidateId` | 候选人档案身份，可与用户一对一或一对多 | 上层 |
| `resumeId` | 简历版本身份 | 上层 |
| `jdId` | 目标岗位描述身份 | 上层 |
| `businessSessionId` | Java 业务面试会话 | 上层 |
| `sessionId` | 下层 Agent 会话身份；首期可与业务会话使用同一关联 ID，但数据归属不同 | 上下层关联 |
| `runId` | 一次 Agent 执行身份 | 下层 |
| `requestId` | 一次服务请求身份，用于追踪和幂等 | 跨服务 |
| `agentId` | 当前只有一个默认 Agent 时为内部配置，不作为首期必填外部字段 | 下层 |

## 4. 目标目录结构

```text
interviewGuide/
├─ frontend/                         原始 React 前端
├─ java-backend/                     新建 Java 上层
│  ├─ src/main/java/.../api/          REST/WebSocket 入口
│  ├─ src/main/java/.../application/  用例编排与异步任务
│  ├─ src/main/java/.../domain/       业务实体、状态和规则
│  ├─ src/main/java/.../infrastructure/数据库、HTTP、消息、文件
│  ├─ src/main/resources/config/      可修改配置
│  ├─ src/main/resources/prompts/     仅上层需要的模板
│  └─ src/test/                       未来 Java 测试
├─ python-agent/                     新建 Python 下层
│  ├─ app/api/                        下层 HTTP 适配层
│  ├─ app/core/                       配置、契约、异常、状态码
│  ├─ app/agent/                      Agent 主业务与运行时
│  │  ├─ interview/                   规划、阶段、决策
│  │  ├─ llm/                         模型客户端适配
│  │  ├─ tools/                       工具注册和执行
│  │  ├─ skills/                      Skill 加载和选择
│  │  ├─ memory/                      会话/长期记忆
│  │  └─ rag/                         文档、切片、向量、检索
│  ├─ app/engineering/                Python Agent 工程能力
│  │  ├─ persistence/                 下层状态持久化
│  │  ├─ reliability/                 重试、超时、限流、熔断
│  │  └─ observability/               运行记录和日志
│  ├─ app/utils/                      无业务副作用的工具
│  ├─ config/prompts/                 可修改 Prompt 文件
│  ├─ config/skills/                  可修改 Skill 文件
│  ├─ config/rag/                     预置知识资料和索引配置
│  └─ tests/
├─ infrastructure/                   Docker、PostgreSQL、部署脚本
├─ docs/
└─ reference/interview-guide-original/原始参考项目，只读参考
```

## 5. 上层 Java 业务模块

### 5.1 用户与候选人模块

职责：用户身份、候选人档案、简历版本、JD 关联和权限校验。

Java 持久化：`user`、`candidate_profile`、`resume`、`job_description`。

下层只接收初始化 Agent 所需的最小快照或受控资源引用，不读取 Java 表。

### 5.2 简历与 JD 业务模块

职责：文件上传、版本、解析任务状态、删除、查询和业务关联。

不需要模型的文件类型识别、大小校验、Hash、存储和元数据由 Java 或文件基础设施完成；需要 Agent 语义解析、评价和问题构造时调用下层。

### 5.3 面试业务会话模块

职责：创建、暂停、恢复、结束业务面试，保存业务侧问答索引和最终报告。

Java 负责业务状态和事务；Python 负责对应的 Agent 计划、阶段、当前问题、Agent 运行记录。两边通过 `sessionId` 关联，但不共同写同一张表。

### 5.4 Agent Gateway 模块

职责：统一调用下层，不在 Java 中写 Prompt 或大模型业务逻辑。

包含：请求 DTO、HTTP 客户端、超时、重试、幂等键、错误映射、下层健康检查和结果落库。

### 5.5 异步任务模块

职责：简历解析、简历评价、RAG 文档向量化、报告生成等耗时任务。

Java 负责任务状态、提交、重试策略和用户查询；Python 执行 Agent 或内容处理任务并返回结果。

### 5.6 报告与历史模块

职责：保存用户可见的最终报告、阶段评分、问答历史摘要和导出。

报告的业务版本由 Java 管理；评分依据和 Agent 运行细节可从下层通过结果快照或 `runId` 关联获取。

### 5.7 其他原始模块迁移

| 原始模块 | 新归属 |
|---|---|
| `modules/interview` | Java 面试业务 + Python 面试 Agent |
| `modules/resume` | Java 文件/版本/业务关系 + Python 解析/评价 |
| `modules/knowledgebase` | Java 上传与业务元数据 + Python RAG 处理/检索 |
| `modules/interviewschedule` | Java 业务模块；解析结果需要 Agent 时调用 Python |
| `modules/voiceinterview` | 后续扩展；Java 管理 WebSocket 会话，Python 提供语音/Agent 能力 |
| 原始 `common/ai` | Python `agent/llm` 和 Agent 运行时；Java 保留调用适配，不保留业务 Prompt |
| 原始 `common/async`、Redis 限流 | Java 工程化层；Python 只保留 Agent 调用级可靠性 |

## 6. 下层 Python Agent 模块

### 6.1 API 适配层

职责：接收上层 JSON、校验身份和协议版本、调用 Agent 应用服务、返回统一响应。

API 层不写业务流程、不写 Prompt、不直接访问数据库。

### 6.2 Agent 规划模块

输入：初始化时提供的候选人简历快照、目标岗位、面试时长和难度。

输出：结构化 `InterviewPlan`，包含六阶段、题量上限、追问上限、难度、主题和时间预算。

计划初始化后持久化，后续轮次不重新规划。

### 6.3 Agent 流程与决策模块

固定外壳：

```text
加载会话 → 恢复计划和运行状态 → Agent 判断 → 更新状态 → 保存运行记录
```

Agent 动作：

```text
FOLLOW_UP / NEXT_QUESTION / NEXT_STAGE / END_INTERVIEW
```

服务层校验动作白名单、阶段配额、追问上限和会话状态，防止模型跳过流程约束。

### 6.4 Tools / Skills / MCP

- Tools：可以被模型调用的具体函数，例如读取会话资料、搜索题库、生成评价指标。
- Skills：可配置的任务能力包，由 Prompt、规则和参考资料组成。
- MCP：对外部资源的标准化工具接入；首期保留接口和示例，不做复杂生态。

三者都由 Python Agent 运行时选择和执行；Java 不实现 Agent 工具调用。

### 6.5 RAG 模块

沿用原始知识库的处理逻辑，但重新定义用途：

1. 文档导入、解析、清洗、切片。
2. 生成 Embedding 并写入向量索引。
3. 根据任务类型检索相关片段。
4. 将检索结果交给题目构造 Agent 或简历评价 Agent。

首期 RAG 用途：

- 从预置面经、技能资料和知识库构造八股/场景题。
- 根据候选人简历和岗位要求检索评价标准。
- 为回答评价提供知识依据和参考答案。

RAG 不负责业务会话，也不直接返回前端页面。

### 6.6 记忆模块

记忆由 Python 下层统一管理，采用明确的双层模型；RAG 是外部资料检索，不能替代记忆。

| 层级 | 作用域 | 保存内容 | 读取规则 |
| --- | --- | --- | --- |
| 短期记忆 | `(userId, sessionId)` | 最近 3–5 轮完整问答、当前阶段、当前问题与本轮运行状态 | 每次问答必读，只向模型提供固定窗口内的原始对话 |
| 长期记忆 | `userId` | 用户历史面试摘要、已确认的简历信息、岗位偏好、薄弱点、表现趋势以及经用户或系统确认的其他长期信息 | 初始化和每次问答按需检索，以结构化摘要形式注入，不直接拼接所有历史原文 |

简历、JD 的权威业务版本仍由 Java 管理；Python 在初始化时保存本次面试所需的简历/JD 快照，并将已确认的简历关键信息作为长期记忆的受版本约束内容。简历更新或删除时，上层必须通过内部事件或接口通知下层失效或重建对应记忆，避免使用旧资料。

每次运行的顺序固定为：读取短期记忆和相关长期记忆 → 组装 Agent 上下文 → 完成决策 → 先持久化问答与会话状态 → 再更新长期摘要。长期记忆更新必须可追溯、幂等且不得覆盖未经确认的原始简历事实。首期可以先采用关系型持久化和摘要更新，不急于引入独立向量记忆框架。

### 6.7 Python 工程化模块

Python 只处理 Agent 领域内的工程问题：模型调用重试、超时、结构化输出校验、工具失败、Agent Run 记录、下层状态一致性和资源限制。

Java 负责跨业务的事务、异步任务、业务幂等、并发和最终结果持久化。

## 7. 上下层数据归属

| 数据 | Java 上层 | Python 下层 |
|---|---:|---:|
| 用户/候选人主数据 | 主责 | 只读快照 |
| 简历/JD 文件与版本 | 主责 | 处理副本或快照 |
| 业务面试会话 | 主责 | 关联使用 |
| Agent 面试计划 | 引用/摘要 | 主责 |
| 当前 Agent 阶段和问题 | 展示快照 | 主责 |
| 问答业务记录 | 主责 | 运行记录 |
| Agent Tool 调用轨迹 | 可按需引用 | 主责 |
| RAG 文档、切片、向量 | 业务元数据 | 主责 |
| 长期记忆 | 不负责 | 主责 |
| 最终报告 | 主责 | 生成依据 |
| 任务状态 | 主责 | 返回执行状态 |

## 8. 标准数据交互

### 8.1 初始化 Agent 会话

初始化是特殊操作，可以携带一次资料快照；普通问答不携带上下文。

```json
{
  "apiVersion": "v1",
  "requestId": "req-001",
  "runId": "run-init-001",
  "userId": "user-001",
  "sessionId": "session-001",
  "operation": "agent.session.initialize",
  "payload": {
    "candidate": {
      "candidateId": "candidate-001",
      "resumeId": "resume-001",
      "resumeSnapshot": "候选人简历文本"
    },
    "job": {
      "jdId": "jd-001",
      "targetRole": "Java 后端开发",
      "jdSnapshot": "岗位要求文本"
    },
    "interview": {
      "durationMinutes": 40,
      "difficulty": "MEDIUM"
    }
  },
  "timestamp": "2026-08-07T10:00:00Z"
}
```

### 8.2 普通问答

普通轮次只携带身份、运行标识和用户输入，下层从自己的会话持久化中恢复计划与上下文。

```json
{
  "apiVersion": "v1",
  "requestId": "req-002",
  "runId": "run-002",
  "userId": "user-001",
  "sessionId": "session-001",
  "operation": "agent.respond",
  "question": "我使用 Redis 做缓存。",
  "timestamp": "2026-08-07T10:05:00Z"
}
```

### 8.3 统一响应

成功、处理中、部分结果和失败必须使用完全相同的字段集合：

```json
{
  "apiVersion": "v1",
  "requestId": "req-002",
  "runId": "run-002",
  "code": 100,
  "status": "COMPLETED",
  "userId": "user-001",
  "sessionId": "session-001",
  "sessionStatus": "ACTIVE",
  "stateVersion": 4,
  "answer": "请进一步说明 Redis 缓存与数据库一致性如何保证。",
  "error": null,
  "timestamp": "2026-08-07T10:05:04Z"
}
```

业务码固定三位：`1xx` 正常/部分结果，`2xx` 请求错误，`3xx` 数据一致性，`4xx` 流量保护，`5xx` 模型/网络/工具依赖错误。

## 9. 关键调用时序

### 9.1 简历与 JD 初始化

```text
前端 → Java：创建业务面试
Java：校验用户、简历、JD、权限并生成 sessionId
Java → Python：发送一次初始化快照
Python：规划 Agent 面试 → 持久化 AgentSession
Python → Java：返回开场消息和状态
Java：持久化业务会话并返回前端
```

### 9.2 一次问答

```text
前端 → Java：提交候选人回答
Java：校验会话、幂等、并发和任务状态
Java → Python：发送 userId/sessionId/runId/answer
Python：读取 AgentSession
Python：恢复计划、阶段和最近问答
Python → LLM：结构化决策
Python：校验动作、更新会话、持久化 stateVersion
Python → Java：返回统一响应
Java：保存业务问答、更新业务状态
Java → 前端：返回下一条 Agent 消息
```

### 9.3 RAG 题目构造或简历评价

```text
Java：提交业务任务
Java → Python：传递任务类型、候选人快照和 runId
Python：查询 RAG 文档
Python：将检索片段放入 Agent 输入
Python：生成结构化题目或评价结果
Python：保存 Agent Run
Python → Java：返回结果
Java：异步任务落库并通知前端
```

### 9.4 提前结束面试

```text
前端 → Java：结束业务面试
Java：校验用户和会话状态
Java → Python：发送 agent.session.complete（不携带问答上下文）
Python：以 sessionId + userId 校验并关闭 Agent 会话，保留长期记忆
Python → Java：返回统一完成响应
Java：持久化业务会话完成状态并返回前端
```

## 10. 配置、Prompt、Skill 和资料规则

### 10.1 Python

```text
python-agent/.env                    密钥和环境变量，不提交
python-agent/config/prompts/          Prompt 文件
python-agent/config/skills/           Skill 定义与 metadata
python-agent/config/rag/              预置资料、索引配置和版本信息
```

Prompt 文件按用途拆分，例如：

```text
prompts/interview/planner.md
prompts/interview/decision.md
prompts/interview/evaluation.md
prompts/resume/analysis.md
prompts/rag/question-generation.md
prompts/rag/resume-evaluation.md
```

任何可调整的规则、模板、模型参数和知识内容禁止直接写入 Python 业务代码。

### 10.2 Java

```text
src/main/resources/config/             业务配置
src/main/resources/prompts/            仅保留非 Agent 业务模板
```

Java 不保存 Python Agent 的系统 Prompt，不复制下层 Agent 决策逻辑。

## 11. 测试策略

### Python

- 使用指定 Conda 环境运行单元测试和集成测试。
- 测试计划结构校验、动作白名单、阶段流转和会话乐观锁。
- 使用当前 API 配置进行受控模型集成测试，但不在测试日志输出密钥。
- PostgreSQL、RAG 和外部工具分别提供集成测试。

### Java

- 当前只完成目录、接口和配置重构，不运行测试，等待 JDK/构建环境配置完成。
- 环境就绪后测试业务服务、Agent Gateway、异步任务、幂等和并发更新。

### 前后端

- 前端保持原有页面和交互。
- 通过 Java API 适配原前端需要的 DTO。
- 使用契约测试验证 Java → Python JSON 不漂移。

## 12. 重构验收标准

1. 前端可以通过 Java 上层完成创建面试、提交回答、查看历史和报告。
2. Java 代码中不再直接编排具体大模型 Prompt。
3. Python 可以独立完成面试规划、阶段推进和 Agent 决策。
4. 不同 `userId`、`sessionId`、`resumeId`、`jdId` 的状态不会串用。
5. RAG 可以为题目构造和简历评价提供检索依据。
6. Prompt、Skill、RAG 资料和可修改配置均为外部文件。
7. Java 负责业务持久化、并发、异步和跨服务可靠性。
8. Python 负责 Agent 状态、结构化输出、模型调用和 Agent 运行可靠性。
9. Python 测试通过；Java 测试在环境准备后补齐。
10. 项目可通过虚拟机部署并演示一条完整前后端链路。
