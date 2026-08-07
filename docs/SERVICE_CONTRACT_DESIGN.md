# 上层与下层服务契约设计

## 1. 术语与职责

- 上层：Java 业务服务，负责用户、简历、JD、面试会话、业务事务和工程治理。
- 下层：Python Agent 服务，负责 Agent 执行、Tools、Skills、MCP、记忆、RAG 和 Agent 运行记录。
- 前端只调用上层，不直接依赖下层的内部实现。

## 2. 解耦原则

下层不应该知道上层的具体业务模型。它不接收 Java 的实体类，也不直接理解“简历表”“面试题表”等概念；下层只接收通用的 Agent 请求、上下文和业务负载。

但“完全不带业务语义”也不现实。下层仍需要知道本次执行的任务类型和输入目标，否则无法选择合适的 Agent 能力。因此采用“通用协议 + 可配置任务类型”的方式解耦，而不是让下层只接收一段无意义的字符串。

## 3. Agent 身份与执行上下文

`agentId` 只表示使用哪一种 Agent 配置，例如系统提示词、可用工具、技能集合、模型参数和 RAG 范围。它不是记忆的唯一键，也不是用户身份。

如果系统只有一个固定 Agent，`agentId` 对外就是冗余字段，可以由下层配置默认值；用户和会话足以完成当前调用。本项目首期不实现多智能体编排，因此不把 `agentId` 放入必需的对外请求契约。未来出现多种 Agent 配置时，再在创建会话时增加可选的 Agent 配置标识，后续轮次不必重复传递。

在支持多 Agent 配置时，需要以下标识；本项目首期对外请求不要求 `agentId`：

| 标识 | 作用 |
|---|---|
| `agentId` | 选择 Agent 配置和能力边界 |
| `userId` | 隔离用户数据和长期记忆 |
| `sessionId` | 隔离一次会话上下文和短期记忆 |
| `runId` | 标识一次 Agent 执行，支持状态追踪和幂等 |
| `requestId` | 标识一次服务请求，支持链路追踪 |

因此，“上层只传 `agentId`，下层自行检索记忆”是不完整的。下层至少还需要用户和会话上下文；否则会发生记忆串用、无法重试幂等、并发执行相互覆盖等问题。

### 3.1 推荐的身份关系

`agentId`、`userId`、`sessionId` 和 `runId` 都是独立字段，不应将一个标识编码进另一个标识中。未来支持多 Agent 配置时，一个用户可以使用多个 Agent；一个 Agent 可以服务多个用户；一个会话只绑定一个 Agent 和一个用户；一个会话可以产生多次运行。

```text
用户 userId
  └─ 会话 sessionId
       ├─ 绑定 Agent 定义 agentId
       └─ 多次执行 runId
```

未来的 `agentId` 应表示可复用的 Agent 定义或配置，例如 `interview-coach-v1`，包含系统提示词、模型参数、可用 Tools / Skills、MCP 配置和可检索知识范围。它不创建“每个用户、每个会话一个 Agent”的实体。只有在存在多个 Agent 类型、配置版本或权限范围时，才需要由上层显式选择它。

### 3.2 会话与 Agent 数量

用户与会话是典型的一对多关系：一个 `userId` 可以拥有多个 `sessionId`，每个会话代表一次相对独立的面试过程。

如果未来支持多个 Agent，建议一个会话绑定一个主 Agent。这个 Agent 内部可以使用多个 Tools、Skills 和 MCP 能力，但这不等于多个 Agent。这样可以保持记忆边界清晰、运行链路容易追踪，也更适合本项目的面试展示目标。

多个 Agent 协作是可扩展方案，例如由编排 Agent 调度出题 Agent、评价 Agent 和报告 Agent。若将来采用该方案，应保留一个会话级 `orchestratorAgentId`，并为每次子 Agent 执行记录独立的 `runId`、`agentId` 和父运行标识；不应让多个 Agent 无边界地共享会话状态。

如果同一会话需要切换 Agent 配置，应记录切换事件，或在每次运行中显式传递 `agentId`，保证历史结果可以还原当时使用的 Agent 版本。

### 3.3 首期接口建议

- 创建会话：上层只提交用户和业务会话信息，下层使用唯一的默认 Agent。
- 会话轮次：上层传入 `userId`、`sessionId`、`runId` 和通用 `payload`，不传 `agentId`。
- 下层在内部使用固定的 Agent 配置，不让上层依赖下层的 Agent 实现细节。
- 未来扩展多个 Agent 时，再引入独立的 Agent 配置标识，并通过版本化协议演进。

### 3.4 多 Agent 并行处理

当下层需要多个 Agent 并行处理时，`agentId` 仍然有效，但它只标识每个 Agent 的配置或角色。例如：

```text
orchestrator-agent
├─ question-agent
├─ evaluation-agent
└─ knowledge-agent
```

上层不需要感知这些子 Agent 的内部编排。上层只调用一个编排入口；下层根据编排 Agent 的配置决定是否并行调用子 Agent。

并行执行时必须额外区分：

- `parentRunId`：本次编排任务的运行标识。
- `subRunId`：每个子 Agent 的独立运行标识。
- `agentId`：本次子运行使用的 Agent 配置。
- `taskKey`：子任务类型，用于聚合结果和幂等。

子 Agent 不应直接共享可变状态。每个子任务接收只读输入，独立生成结果，最后由编排 Agent 进行聚合；记忆写入应由统一的结果处理阶段完成，避免并行写入互相覆盖。

本项目首期不实现复杂的多 Agent 自主协作，只保留该架构思想。若需要展示并行能力，优先实现有限的固定并行分支，例如同时进行“回答评分”和“知识点检索”，再由编排层合并结果。

实现上可采用 Python `asyncio.gather` 处理短耗时独立任务，或使用 LangGraph 的并行分支与状态合并；长耗时任务再引入队列。无论采用哪种方式，都必须处理超时、部分失败、取消、并发上限、幂等和模型调用成本。

### 3.5 多用户同时使用同一个 Agent

“两个用户同时使用 Agent”和“一个请求内部启动多个 Agent”是两种不同的问题。前者是服务并发，后者是 Agent 编排。

两个用户可以共享同一个 `agentId`，下层为每个请求创建独立的执行上下文：

```text
user-A + session-A + run-A ─┐
                             ├─ interview-coach-v1
user-B + session-B + run-B ─┘
```

逻辑上不需要为每个用户创建一个新的 Agent 定义或固定线程。下层可以复用只读的 Agent 配置和模型客户端，但每个请求必须独立保存消息、工具调用、中间状态和错误信息，不能使用进程级可变会话变量。

FastAPI 的异步请求处理、数据库连接池和模型客户端的并发请求能力可以支持多个用户同时调用。真实部署中还需要通过并发上限、超时、限流、连接池大小和多进程/多副本扩展控制资源。

只有在 Agent 持有独占资源（例如本地模型显存、浏览器实例或特定工具会话）时，才需要额外设计 Agent 实例池或 Worker 池；这属于运行资源管理，不应改变 `agentId`、`userId` 和 `sessionId` 的业务身份关系。

## 6. 首版上下层 JSON 交互格式

### 6.1 上层请求下层

```json
{
  "apiVersion": "v1",
  "requestId": "req-20260806-0001",
  "runId": "run-20260806-0001",
  "userId": "user-001",
  "sessionId": "session-001",
  "operation": "agent.respond",
  "question": "Redis 的 RDB 和 AOF 有什么区别？",
  "timestamp": "2026-08-06T12:00:00Z"
}
```

必填字段：`apiVersion`、`requestId`、`runId`、`userId`、`sessionId`、`operation`、`question` 和 `timestamp`。请求不携带会话上下文、历史消息或上层业务字段；下层根据 `userId + sessionId` 读取和维护自己的会话状态。

会话提前结束使用独立的 `agent.session.complete` 请求，不复用问答的 `question` 字段：它只携带 `apiVersion`、`requestId`、`runId`、`userId`、`sessionId`、`operation` 和 `timestamp`。下层将对应 Agent 会话置为 `COMPLETED` 并返回同一响应结构；用户级长期记忆不删除。Java 只有在下层成功关闭后才提交自己的业务完成状态，重试同一关闭操作应得到稳定的已完成结果。

### 6.2 下层返回上层

```json
{
  "apiVersion": "v1",
  "requestId": "req-20260806-0001",
  "runId": "run-20260806-0001",
  "code": 100,
  "status": "COMPLETED",
  "userId": "user-001",
  "sessionId": "session-001",
  "sessionStatus": "ACTIVE",
  "stateVersion": 4,
  "answer": "RDB 是定期生成内存快照，恢复速度较快但可能丢失最近数据；AOF 记录写命令，数据可靠性更高，但文件通常更大。",
  "error": null,
  "timestamp": "2026-08-06T12:00:04Z"
}
```

`code` 固定为三位数字，不只有成功和失败两种含义。首位表示类别，后两位表示具体原因：

| 分类 | 含义 | 首版示例 |
|---|---|---|
| `1xx` | 正常处理、处理中或部分结果 | `100` 已完成、`101` 处理中、`102` 部分结果 |
| `2xx` | 请求端错误 | `200` 参数不合法、`201` 不支持的操作 |
| `3xx` | 数据一致性错误 | `300` Agent 会话不存在、`301` 会话状态冲突、`302` 幂等运行冲突 |
| `4xx` | 流量或资源保护错误 | `400` 已限流、`401` 并发已达上限 |
| `5xx` | 大模型、网络、工具等下层依赖错误 | `500` 模型不可用、`501` 模型超时、`502` 工具执行失败、`503` 网络错误 |

`status` 表示本次运行状态，`sessionStatus` 表示下层维护的会话状态。

失败或处理中仍返回同一结构，只改变 `code`、`status`、`sessionStatus` 和 `error`：

```json
{
  "apiVersion": "v1",
  "requestId": "req-20260806-0001",
  "runId": "run-20260806-0001",
  "code": 500,
  "status": "FAILED",
  "userId": "user-001",
  "sessionId": "session-001",
  "sessionStatus": "ACTIVE",
  "stateVersion": 4,
  "answer": null,
  "error": {
    "type": "AGENT_EXECUTION_FAILED",
    "message": "Agent 暂时无法完成本次处理",
    "retryable": true
  },
  "timestamp": "2026-08-06T12:00:04Z"
}
```

`code` 是业务结果码，不替代 HTTP 状态码；例如 HTTP 200 也可以携带处理中或业务失败结果，便于上层稳定解析 Agent 执行结果。正式实现时，Java DTO、Python Pydantic 模型和契约测试必须以本节为唯一来源。

成功、处理中、部分结果和失败必须使用完全相同的响应字段集合，不能通过删字段或更换 JSON 层级表达状态差异。上层只根据 `code`、`status`、`sessionStatus`、`answer` 和 `error` 的值解析结果；下层不返回 `nextQuestion` 等面试业务字段。

记忆的检索范围使用组合条件，而不是拼接 ID：短期记忆按 `(userId, sessionId)` 隔离，只保留最近 3–5 轮完整问答与当前会话状态；长期记忆按 `userId` 隔离，保存用户历史摘要、已确认的简历信息及其他长期有效信息。一次运行的状态和事件按 `runId` 管理。`agentId` 仅用于选择 Agent 配置，不作为记忆主键。

普通问答请求仍禁止携带 `history`、`memory`、简历全文或 JD 全文。下层使用请求中的 `userId + sessionId` 自行读取这两层记忆；初始化请求才可携带资料快照，供下层建立本次会话与长期记忆索引。

当后续支持用户自定义 Agent 配置时，再增加独立的 `agentInstanceId` 或 `agentProfileId`。该实例仍然不应替代用户和会话标识。

## 4. JSON 契约方向

上下层使用统一的版本化请求信封：

```json
{
  "apiVersion": "v1",
  "requestId": "req-001",
  "agentId": "interview-coach",
  "userId": "user-001",
  "sessionId": "session-001",
  "runId": "run-001",
  "operation": "agent.run",
  "payload": {}
}
```

响应统一包含请求追踪信息、成功标识、结果和错误结构。具体字段在实现第一个跨服务接口时固化为 Java DTO、Python Pydantic 模型和契约测试。

## 5. 真实场景下的工程化约束

- 上层负责鉴权、业务合法性和调用权限，下层不应自行信任客户端传入的用户身份。
- 下层必须校验 `agentId` 是否存在、是否启用以及当前调用方是否有权使用。
- `requestId` 和 `runId` 需要支持幂等，避免上层重试导致重复执行或重复写入记忆。
- 协议必须版本化，字段新增应兼容旧客户端，语义变更应升级版本。
- 超时、重试、错误码和可重试属性必须显式表达，不能只返回一段错误文本。
- 上层不能依赖下层的数据库表；下层也不能直接修改上层业务数据。
