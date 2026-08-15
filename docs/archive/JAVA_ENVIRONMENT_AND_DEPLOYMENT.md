# 环境配置与部署说明

本文以“配置真实依赖后可运行”为目标，描述 Java 上层、Python 下层及其依赖服务的配置边界。密钥、数据库密码、模型名称和地址只能写入未提交的 `.env` 或部署平台 Secret，不要写入源码、镜像或 Git。

## 1. 运行环境

| 组件 | 建议版本 | 用途 |
| --- | --- | --- |
| JDK | 21 | Java 上层编译和运行 |
| Maven | 3.9.x 二进制发行版 | Java 构建；`D:\Maven\apache-maven-3.9.x\bin\mvn.cmd` 应存在 |
| Node.js | 20 LTS 或更高 LTS | React 构建 |
| pnpm | 10.26.2 | 前端依赖安装；项目以 `pnpm-lock.yaml` 为唯一锁文件 |
| Python | Conda 环境 `D:\Anaconda\envs\inter-guide` | Python Agent |
| PostgreSQL | 16 + pgvector | Java 业务数据、Agent 会话、长期记忆和向量 |
| Redis | 7.x | RAG 答案缓存和短期缓存，不承担业务消息可靠投递 |
| RabbitMQ | 3.13+ | 简历分析和 RAG 向量化异步任务、重试和死信队列 |
| 中文字体 | 可商用 TTF/OTF | Java PDF 报告中文渲染 |

`D:\Maven\apache-maven-3.9.16` 当前是 Maven 源码树，不是可执行发行版；请安装二进制包后再使用其 `bin\mvn.cmd`。
如果使用 IntelliJ 自带的 JBR 21，当前机器可用路径为 `D:\IdeaLij\IntelliJ IDEA 2025.3.3\jbr`；命令行构建前应将 `JAVA_HOME` 指向一个 JDK 21（JRE 不够）。

## 2. 配置文件

### 本机运行

1. 复制 `infrastructure/.env.example` 为 `infrastructure/.env`，填写 PostgreSQL、RabbitMQ 和模型配置。
2. 复制 `python-agent/.env.example` 为 `python-agent/.env`，填写模型 API、Base URL、Embedding（启用 RAG 时）和 `DATABASE_URL`。
3. Java 使用环境变量或 IDEA 的 Run Configuration 注入 `application.yml` 中引用的变量：
   - `DATABASE_URL`、`DATABASE_USERNAME`、`DATABASE_PASSWORD`
   - `REDIS_URL`
   - `RABBITMQ_HOST`、`RABBITMQ_PORT`、`RABBITMQ_USER`、`RABBITMQ_PASSWORD`
   - `PYTHON_AGENT_BASE_URL`
   - `AGENT_FILE_STORAGE_ROOT`
   - `AGENT_PDF_FONT_PATH`
4. `AGENT_PDF_FONT_PATH` 必须指向运行 Java 进程可读的中文字体文件；未配置时，PDF 导出接口会明确返回配置错误，不生成乱码报告。
   Compose 部署时请将可商用字体放到 `infrastructure/fonts/NotoSansCJKsc-Regular.otf`，或同步修改挂载路径和变量。

### 模型配置

Python 使用 OpenAI-compatible Chat Completions 接口，因此官方 OpenAI 或兼容其协议的供应商均可使用。必须同时确认模型名称、Base URL 的路径约定、API Key 权限和结构化输出能力；若供应商不支持结构化输出，需要换用支持 JSON Schema 的模型或适配器。

## 3. Docker Compose 部署（推荐）

推荐在项目根目录使用一键启动脚本（完整说明见 [DOCKER_DEPLOYMENT.md](DOCKER_DEPLOYMENT.md)）：

```powershell
.\scripts\start.ps1
```

Linux 虚拟机使用：

```bash
sh scripts/start.sh
```

首次执行仅会生成 `infrastructure/.env`，必须填写模型配置和高强度密码后再次运行。也可以手工执行：

```powershell
docker compose --env-file infrastructure\.env -f infrastructure\docker-compose.yml up -d --build
```

启动顺序由健康检查控制：PostgreSQL → Python Agent → Java 上层；RabbitMQ、Redis 作为 Java 的依赖。首次启动 PostgreSQL 时会执行 `infrastructure/postgres/init/001-schema.sql` 和 `002-feature-upgrade.sql`。已有数据库环境请使用正式迁移工具执行 SQL，不要依赖容器重复初始化。

常用检查：

```powershell
docker compose --env-file infrastructure\.env -f infrastructure\docker-compose.yml ps
docker compose --env-file infrastructure\.env -f infrastructure\docker-compose.yml exec python-agent python -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8000/health').read())"
docker compose --env-file infrastructure\.env -f infrastructure\docker-compose.yml exec java-backend curl -fsS http://127.0.0.1:8080/actuator/health
```

前端入口为 `http://localhost`（虚拟机上则为 `http://<虚拟机IP>/`）。Java API 与 Python Agent 均仅通过 Compose 内部网络访问，不映射宿主机端口；前端 Nginx 将 `/api/` 反向代理到 Java。前端会自动生成并保存匿名 `X-User-Id`，用于未接入鉴权前的用户数据隔离；这不是生产鉴权，接入登录系统后应由网关替换该身份来源。

## 4. 本机分进程启动

先启动依赖服务：

```powershell
docker compose --env-file infrastructure\.env -f infrastructure\docker-compose.yml up -d postgres redis rabbitmq
```

Python：

```powershell
conda activate inter-guide
Set-Location python-agent
python -m uvicorn app.api.application:app --host 0.0.0.0 --port 8000
```

Java：

```powershell
Set-Location java-backend
& 'D:\Maven\apache-maven-3.9.x\bin\mvn.cmd' spring-boot:run
```

前端：

```powershell
Set-Location frontend
corepack enable
pnpm install --frozen-lockfile
pnpm dev
```

## 5. 业务链路与持久化边界

- Java 保存候选人、简历、JD、面试会话、每轮问题与回答、知识库和异步任务状态；回答评分、记忆摘要与 RAG 证据只保留在 Python 下层。
- Python 保存 Agent 会话状态、短期记忆、用户长期记忆和向量索引；恢复时使用 `userId + sessionId`，初始化后不由 Java 传递完整对话上下文。
- RabbitMQ 只承担简历分析和 RAG 向量化的异步任务传递；文本面试初始化与逐轮回答是同步调用。Redis 只承担缓存。业务最终状态必须落 PostgreSQL。
- 每次正式回答带 `runId`，Java 使用乐观锁和幂等记录避免重复提交；Python 使用同一 `runId` 返回稳定结果。
- 简历和知识库原始文件必须通过 Java 文件存储配置保存，数据库保存元数据和解析内容；删除业务记录时同步清理文件。

## 6. 常见问题

- `USER_ID_REQUIRED`：请求缺少 `X-User-Id`，前端之外的调用方也必须提供稳定用户标识。
- `DIFFICULTY_INVALID`：只接受 `junior/mid/senior` 或 `EASY/MEDIUM/HARD`，Java 会统一转换后发送给 Python。
- Python 初始化失败：检查 `MODEL_NAME`、`MODEL_API_KEY`、`MODEL_BASE_URL` 和模型是否支持结构化输出。
- RAG 无结果：检查 `EMBEDDING_MODEL`、Embedding API 配置、pgvector 扩展和知识库索引状态。
- PDF 导出失败：检查 `AGENT_PDF_FONT_PATH` 是否挂载到 Java 容器并指向真实 CJK 字体。
- RabbitMQ 任务未消费：检查队列连接、凭据以及 `interview.agent.tasks` 下的 `interview.agent.interview.create`、`interview.agent.work.execute` 和对应死信队列。
- Java 无法启动：确认 JPA schema 与迁移脚本一致，并使用 JDK 21 和 Maven 二进制发行版，而不是 Maven 源码目录。

## 7. 当前范围

首期可运行链路为文本面试、简历分析、知识库管理和面试内部 RAG。独立知识库聊天、面试日程解析以及语音面试（ASR/TTS/WebSocket）不属于当前范围。

前端不要使用 `npm ci`：仓库的 Docker 构建与锁定依赖均使用 `pnpm-lock.yaml`，而历史遗留的 `package-lock.json` 不作为部署依据。Dockerfile 固定使用 pnpm 10.26.2，并允许构建 Vite 所需的原生依赖。需要清理历史 `package-lock.json` 时，应单独确认后再删除，避免影响现有工作区。
