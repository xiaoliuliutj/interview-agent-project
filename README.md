# Interview Agent Project

一个面向实习面试准备的全链路 AI 面试助手项目。项目保留原项目的 React 前端界面，并从零构建 Java 业务后端与 Python Agent 服务。

## 目录说明

```text
frontend/                 复用的 React 前端；后续只按新接口做必要适配
java-backend/             从零构建的 Java 业务后端
python-agent/             Python Agent、RAG、记忆、工具与运行记录
infrastructure/           虚拟机部署、容器与环境配置
docs/                     项目规则、模块设计和重构路线
reference/                原 InterviewGuide 项目，仅作设计与代码参考
_scratch/                 临时脚本与验证文件（不提交）
```

## 服务职责

- Java 后端负责用户、简历、JD、面试会话、问答与报告等业务数据，以及事务、异步任务、幂等、并发控制和跨服务协作。
- Python Agent 服务负责 Agent Loop、Tools / Skills / MCP、RAG、记忆、Agent 运行记录和模型调用的基础重试。
- 两端通过 `userId`、`sessionId`、`runId` 协作；可以共用 PostgreSQL 实例，但严格使用各自的数据表，不跨服务直接写对方的数据。

详细设计与实施顺序见 [docs](docs/) 中的说明文档。

## 参考来源

- 原项目：<https://github.com/Snailclimb/interview-guide>
- Agent 学习参考：<https://github.com/datawhalechina/hello-agents>
- 面试知识点参考：<https://programmercarl.com/qita/0022.llminterview.html>
