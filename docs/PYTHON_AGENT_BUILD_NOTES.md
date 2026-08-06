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

`app/core/config.py` 中的 `Settings` 统一定义配置字段，通过环境变量或 `python-agent/.env` 读取实际值；仓库只保留 `.env.example`，不把密钥和环境差异写入代码。`get_settings()` 提供进程内缓存的配置快照。

### 交互契约类

`app/core/contracts.py` 定义 `AgentRequest`、`AgentResponse` 和 `ErrorInfo`。请求只包含身份、运行标识和用户问题，下层根据 `userId + sessionId` 自己维护上下文；响应成功和失败使用相同字段集合，业务码遵循三位首位分类规范。

### 异常处理类

`app/core/exceptions.py` 定义可预期的下层异常和 `ExceptionHandler`。自定义异常直接携带错误类型与可重试属性，Python 内置的参数、查找、超时和网络异常统一映射为标准错误信息，避免各接口重复处理。

### 大模型客户端工厂

`app/core/llm.py` 中的 `LLMFactory` 只根据 `Settings` 创建 `ChatOpenAI` 客户端，不在工厂中发起模型请求。模型名称、密钥、兼容接口地址、温度、超时等从 `.env` 读取；SDK 内置重试关闭，后续统一由 `engineering/` 层处理，避免重复重试。

当前测试使用假的模型配置，只验证客户端对象创建和配置校验，不访问真实模型网络。

## 5. 工程化考虑

- 配置通过外部 `.env` 注入，敏感值不进入版本库；新增配置必须先扩展 `Settings`，再补充 `.env.example`。
- 交互模型禁止额外字段，避免上层业务字段渗入下层契约；需要演进时通过 `apiVersion` 管理。
- 异常映射不能吞掉原始日志；统一返回给上层的同时，后续应在日志中保留完整异常上下文。
- `get_settings()` 的缓存适合进程启动后不变的配置；测试修改环境变量时必须清理缓存。

## 6. 踩坑与解决记录

### Python 运行环境约束

- 固定解释器：`D:\\Anaconda\\envs\\inter-guide\\python.exe`。
- 当前版本：Python 3.12.13、Pydantic 2.10.6、pydantic-settings 2.7.1。
- 后续测试必须直接使用该虚拟环境，不随意创建、删除、升级或安装依赖。
- 运行测试前先确认解释器路径，避免误用系统 Python 或其他 Conda 环境。

## 7. 工程化专题

后续沉淀模型调用重试、超时、结构化输出、运行状态、持久化一致性、可观测性、配置与部署等实践。

## 8. 后续踩坑记录

### pytest-asyncio 事件循环作用域警告

- 现象：测试通过，但 pytest 提示 `asyncio_default_fixture_loop_scope` 未配置。
- 根因：当前 pytest-asyncio 版本未来会改变异步 fixture 的默认作用域。
- 解决措施：当前测试均为同步测试，不影响结果；开始异步接口测试时增加 pytest 配置并显式设为 `function`。
- 预防约束：引入第一条异步测试时同时创建统一 pytest 配置，避免测试行为随依赖版本变化。
