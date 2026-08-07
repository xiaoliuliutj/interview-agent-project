# Python Agent 构建笔记

本文件持续记录 Python Agent 服务从零构建的过程。每个完成的功能都应同步补充，便于复盘、面试表达和后续维护。

## 1. 服务定位与职责边界

- 负责 Agent Loop、模型调用、Tools、Skills、MCP、RAG、记忆、Agent Run 和工具调用记录。
- 接收 Java 后端提供的 `userId`、`sessionId`、`runId` 与最小业务上下文。
- 管理自己的 Agent 领域数据，不直接写入 Java 业务领域数据表。

## 2. 模块构建记录

后续按模块追加：功能目标、实现方案、关键流程、数据与接口边界、工程化考虑、验证方式、面试表达要点和可改进项。

## 3. 代码目录职责

```text
app/
├─ agent/          主业务代码，负责 Agent 相关逻辑和领域编排
├─ core/           全局配置与跨模块基础能力
├─ utils/          无业务状态的轻量工具函数
└─ engineering/    可靠性、数据一致性、并发和故障处理等工程能力
```

目录边界约束：

- `agent/` 可以依赖 `core/`、`utils/` 和 `engineering/`，但不应把全局协议或通用重试逻辑复制进业务文件。
- `core/` 只放跨模块共享的配置、异常、JSON 契约和基础模型，不放具体 Agent 流程。
- `utils/` 保持轻量、无持久化和无业务副作用，避免演变成无法管理的杂物目录。
- `engineering/` 提供可复用的可靠性组件，不直接决定面试业务规则。

## 4. 已完成：全局基础类

### 配置类

`app/core/config.py` 中的 `Settings` 统一定义配置字段，通过环境变量或 `python-agent/.env` 读取实际值；仓库只保留 `.env`，不把密钥和环境差异写入代码。`get_settings()` 提供进程内缓存的配置快照。

### 交互契约类

`app/core/contracts.py` 定义 `AgentRequest`、`AgentResponse` 和 `ErrorInfo`。请求只包含身份、运行标识和用户问题，下层根据 `userId + sessionId` 自己维护上下文；响应成功和失败使用相同字段集合，业务码遵循三位首位分类规范。问答响应的 `output` 仅返回受控的评价摘要、动作和阶段，禁止返回思维链。

### 异常处理类

`app/core/exceptions.py` 定义可预期的下层异常和 `ExceptionHandler`。自定义异常直接携带错误类型与可重试属性，Python 内置的参数、查找、超时和网络异常统一映射为标准错误信息，避免各接口重复处理。

### 大模型客户端工厂

`app/agent/llm/factory.py` 中的 `LLMFactory` 只根据 `Settings` 创建 `ChatOpenAI` 客户端，不在工厂中发起模型请求。模型名称、密钥、兼容接口地址、温度、超时等从 `.env` 读取；SDK 内置重试关闭，后续统一由 `engineering/` 层处理，避免重复重试。

模型客户端直接服务于 Agent 构建并依赖具体模型框架，因此属于 `agent/`，不属于 `core/`。`core/` 只保留模型配置字段和通用异常；依赖方向为 `agent/llm → core`，禁止 `core` 反向依赖 Agent 实现。

当前测试使用假的模型配置，只验证客户端对象创建和配置校验，不访问真实模型网络。

模型配置文件固定为 `python-agent/.env`，不依赖启动时的当前工作目录。支持 `openai`、`openai-compatible` 与 `custom` 三种提供方标识；其中后两者仍要求服务提供 OpenAI Chat Completions 兼容接口。

### Agent 行为模式决策

本项目采用“受约束的单 Agent ReAct + 会话状态机”。固定节点负责加载会话、恢复记忆、执行工具、保存结果和失败收口；Agent 在中间循环中根据当前问题和上下文自主决定直接回答，或调用 RAG、记忆、Skills、MCP 等工具。

```text
加载会话状态
  → 恢复消息与记忆
  → Agent 判断
     ├─ 直接回答
     └─ 调用工具 → 观察结果 → 再次判断
  → 生成最终回答
  → 保存运行记录和记忆
```

该模式不采用纯固定工作流，因为纯工作流无法突出 Agent 的自主决策；不采用完全开放的 ReAct，因为无限循环、重复工具调用和不可控成本不适合本项目；不采用 Plan-and-Execute 和多 Agent，因为当前任务以多轮对话和短任务为主，额外规划与编排成本过高。

首期约束包括：单次运行最大步骤数、工具白名单、统一超时、禁止工具无限递归、工具参数校验、运行状态持久化以及统一失败响应。业务类型不写成 Python 分支；下层通过系统提示词、会话记忆和通用工具理解当前任务。

## 5. 已完成：基础面试 Agent 流程

### 规划与初始化

`app/agent/interview/agent.py` 中的 `InterviewPlanner` 通过结构化输出生成 `InterviewPlan`。计划固定包含六个阶段，OPENING 与 SUMMARY 各固定一次；中间阶段保存主问题上限、单题追问上限、难度、主题和时间预算。

`InterviewAgentService.initialize_session()` 在创建会话时完成：检查重复会话、生成并固定面试计划、生成固定开场问题，并将下层会话保存到仓库。候选人资料仅在此时用于规划，正式问答不再重复传递。

### 受约束 ReAct 推进

`InterviewDecisionAgent` 根据当前问题、候选人回答、阶段计划和近期问答记录返回结构化决策。它只能返回 `FOLLOW_UP`、`NEXT_QUESTION`、`NEXT_STAGE` 或 `END_INTERVIEW`；`InterviewAgentService` 根据轮次上限校验动作后再变更状态，模型不能绕过流程约束。

当前版本已为 Planner 和 Decision Agent 接入受策略约束的 RAG Tool；“观察”同时来自候选人回答、短期会话记忆、长期用户记忆和可选检索证据。长期记忆首期使用结构化摘要与版本化简历快照，暂不使用向量记忆或自动事实抽取。

### 双层记忆边界

- 短期记忆：以 `(userId, sessionId)` 为键持久化最近 3–5 轮原始问答和当前工作状态。每轮读取固定窗口，避免上下文无限膨胀。
- 长期记忆：以 `userId` 为键持久化历史面试摘要、经确认的简历要点、偏好、薄弱点与其他需跨会话保留的信息；以摘要和结构化字段供 Agent 按需使用。
- 简历/JD 的权威数据仍在 Java。Python 只保存初始化时的版本化快照和供 Agent 使用的提炼信息；上层资料变更时必须触发下层失效或刷新。
- 写入顺序：先使用旧记忆完成本轮决策并保存会话，再以 `runId` 幂等地更新长期摘要，防止重试造成重复归纳或并发覆盖。

### 持久化

`app/engineering/persistence/` 提供 PostgreSQL 异步仓库实现。会话以 `sessionId` 为主键保存面试计划、当前阶段、当前问题、问答记录和 `stateVersion`；更新使用乐观锁，避免同一会话并发提交造成覆盖。开发期提供 `create_schema()` 建表入口，正式部署时应改用 Alembic 迁移。

当前本地 `.env` 未配置 `DATABASE_URL`，因此 PostgreSQL 真实连接测试尚未进行。流程单元测试使用内存仓库验证状态变化，不替代数据库集成测试。

## 6. 工程化考虑

- 配置通过外部 `.env` 注入，敏感值不进入版本库；新增配置必须先扩展 `Settings`，再补充 `.env`。
- 交互模型禁止额外字段，避免上层业务字段渗入下层契约；需要演进时通过 `apiVersion` 管理。
- 异常映射不能吞掉原始日志；统一返回给上层的同时，后续应在日志中保留完整异常上下文。
- `get_settings()` 的缓存适合进程启动后不变的配置；测试修改环境变量时必须清理缓存。
- 会话更新必须带 `stateVersion` 执行乐观锁校验；发生冲突时返回 3xx 数据一致性错误，而不是静默覆盖。
- LLM 规划和流程决策使用结构化输出；模型返回不在允许动作集合内时立即失败收口，不能直接修改会话状态。

## 7. 踩坑与解决记录

### Python 运行环境约束

- 固定解释器：`D:\\Anaconda\\envs\\inter-guide\\python.exe`。
- 当前版本：Python 3.12.13、Pydantic 2.10.6、pydantic-settings 2.7.1。
- 后续测试必须直接使用该虚拟环境，不随意创建、删除、升级或安装依赖。
- 运行测试前先确认解释器路径，避免误用系统 Python 或其他 Conda 环境。

## 8. 工程化专题

后续沉淀模型调用重试、超时、结构化输出、运行状态、持久化一致性、可观测性、配置与部署等实践。

## 9. 后续踩坑记录

### pytest-asyncio 事件循环作用域警告

- 现象：测试通过，但 pytest 提示 `asyncio_default_fixture_loop_scope` 未配置。
- 根因：当前 pytest-asyncio 版本未来会改变异步 fixture 的默认作用域。
- 解决措施：已添加 `pytest.ini`，将默认作用域显式设为 `function`。
- 预防约束：后续异步测试沿用统一 pytest 配置，避免测试行为随依赖版本变化。

### HTTP API 与依赖组装

- 新增 `app/api/application.py`：提供 `/health`、`/v1/agent/sessions/initialize`、`/v1/agent/respond` 和 `/v1/agent/sessions/complete`。
- 初始化请求才允许传候选人、简历和 JD 快照；普通问答只传 `userId`、`sessionId`、`runId` 和用户回答。
- `agent.session.complete` 不调用模型，不携带回答或上层历史；它以 `userId + sessionId` 校验并幂等关闭 Agent 会话，长期记忆仍按 `userId` 保留。
- FastAPI 校验错误、下层业务异常和未预期异常都转换为同一 `AgentResponse` 字段集合；HTTP 状态码不替代三位业务码。
- `app/bootstrap.py` 只在配置了 PostgreSQL 时组装生产服务，不回退为临时文件或内存数据。

### 下层可靠性与幂等

- `app/engineering/reliability/` 使用外部 JSON 策略统一包裹 Planner/Decision 的模型调用；SDK 自身不重试，避免两层叠加造成重试风暴。
- `runId` 在 AgentSession 中保存稳定响应快照；同一 `runId` 重放时直接返回原快照，不再次调用模型、推进阶段或更新记忆。快照窗口由 `config/agent/idempotency.json` 控制。

### RAG：原项目逻辑的下层迁移

- `app/agent/rag/` 负责文档解析、800 Token 无重叠切片、每批最多 10 个分片的向量化、知识库过滤和相似度检索。
- 优先让向量仓库执行 `knowledgeBaseId` 过滤；底层不支持时，按原项目思路扩大候选集并在服务层做本地过滤和 `topK/minScore` 收口。
- 面试 Agent 的 RAG 仅使用 `QUESTION_GENERATION` 与 `RESUME_EVALUATION`；原 React 知识库页面使用单独的 `KNOWLEDGE_BASE_QUERY` 检索验证用途。三者均由外部 `config/rag/rag-policy.json` 控制，知识库页面不参与面试 Agent 决策，也不取代长期记忆。
- 已提供 PostgreSQL/pgvector 仓库；真实索引和检索需要配置 `DATABASE_URL`、`EMBEDDING_MODEL` 及可用的 Embedding 服务。

### MCP：只读外部参考工具

- `app/agent/mcp/server.py` 提供一个最小的 MCP Server，通过 `FastMCP` 暴露
  `lookup_interview_reference(query)` 工具。
- 工具只读取 `config/rag/sources/interview-basics.md`，不修改会话、记忆或业务数据；
  查询长度限制为 200 个字符，结果限制为最多 5 个段落。
- MCP 是下层 Agent 的可选能力，不改变上层与下层的 JSON 契约；需要启用时由 Agent
  配置选择工具，不能让 MCP 工具反向承担 Java 业务编排。
- 本地可使用 `python -m app.agent.mcp.server` 通过 stdio 启动，测试覆盖只读行为、空
  查询和长度边界。后续若接入远端 MCP Server，必须补充超时、失败映射、来源审计和
  工具白名单。

### Skill 目录与 JD 预处理

- `config/skills/catalog.json` 是面向前端展示的可修改 Skill 目录；`SKILL.md` 仍只供
  Agent 内部加载，不通过接口暴露内部指令。
- `SkillRegistry.categories_for_jd()` 根据目录中的关键词确定性提取分类，供原 React
  的自定义 JD 页面使用。它不调用大模型，Java 只代理结果，避免上下层重复实现 Skill
  选择逻辑。
- 已提供 Java 后端、Python 后端、前端、算法与数据结构、系统设计和 AI Agent 开发六个可选公开 Skill；每个 Skill 的元数据和面试约束均在独立文件中，不把可变考察范围写进 Python 代码。

### 日程解析 Agent

- `app/agent/schedule/` 使用结构化输出将自然语言日程解析为标题、开始时间和结束时间；Prompt 位于 `config/prompts/schedule/parse.md`。
- Prompt 接收当前 UTC 时间和用户时区，只能抽取输入文本明确给出的信息。缺少时间时返回 `null`，由 Java 上层和前端要求用户确认，不能编造预约时间。
- Java Gateway 使用独立的 `agent.schedule.parse` 协议调用此能力；Python 不创建业务日程，也不写 Java 的排期表。
- 对模型未填充但文本明确包含“今天/明天/后天 + 时刻 + 时长”的情况，下层执行受限的确定性补全；其他不明确的字段保持 `null`，不会猜测预约时间。真实模型连通验证已覆盖该路径。

### 简历评价 Agent

- 新增 `app/agent/evaluation/` 和 `/v1/agent/evaluate/resume`，使用 `config/prompts/resume/analysis.md`、`resume-analyst` Skill 和可选 `RESUME_EVALUATION` 检索证据。
- `ResumeEvaluation` 限制分数范围、总结长度和列表数量；响应 `output` 保留数字、列表等 JSON 类型，Java 不通过字符串拆分恢复评价结果。
- 未配置 Embedding 时评价仍可运行，但 `ragEvidence` 为空；系统不会伪造检索来源。

### PostgreSQL 集成测试待补充

- 现象：基础流程单元测试通过，但本地未提供 `DATABASE_URL`，无法执行真实的 PostgreSQL 建表、读写和乐观锁集成测试。
- 根因：下层数据库尚未配置。
- 解决措施：后续提供 PostgreSQL 配置后，调用 `create_schema()` 并增加真实连接与并发更新测试。
- 预防约束：持久化功能不能只以内存仓库测试为完成标准；接入数据库后必须补充集成测试。

### 真实模型验证记录

- 当前本地 `.env` 已配置 OpenAI-compatible 聊天模型，未输出任何 API Key、Base URL 或模型密钥内容。
- 已实际发送固定连通问题并获得正确响应；随后在内存仓库中使用真实模型完成一次 `InterviewPlanner` 六阶段计划生成，以及一次 `InterviewDecisionAgent` 受约束决策。决策返回了有效动作、下一阶段和评价摘要。
- 当前 `DATABASE_URL`、Embedding 模型及 Embedding 服务未配置，因此 PostgreSQL 持久化、真实向量索引与端到端 RAG 仍不能宣称已完成集成验证。
- 真实模型简历评价验证已通过：返回合法分数、总结和优势列表；由于 Embedding 未配置，本次不包含 RAG 证据。

### Docker 部署边界

- Python 下层镜像只复制 `app/`、`config/` 和依赖清单；`.env`、测试缓存和本机代码产物由 `.dockerignore` 排除，防止模型密钥被打入镜像。
- Compose 通过下层 `/health` 检查确认服务可用，Java 仅使用 `http://python-agent:8000` 这一容器内地址调用下层；Python 的 8000 端口不映射到虚拟机。
- 模型和 Embedding 配置由 `infrastructure/.env` 注入。Embedding 留空时基础面试链路仍可启动，但 RAG 不会伪造检索结果。
- 全量部署与启动命令见 `docs/DOCKER_DEPLOYMENT.md`；本机没有 Docker，容器构建和端到端启动仍需要在虚拟机上完成验收。
