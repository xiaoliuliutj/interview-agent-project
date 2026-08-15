# Interview Agent 虚拟机部署指南

本文用于将 Interview Agent 部署到一台 Linux 虚拟机中，并通过浏览器访问完整系统。部署后，浏览器只访问 Nginx 前端；Java 上层、Python 下层、PostgreSQL、Redis 与 RabbitMQ 均运行在 Docker 内部网络中。

> 适用环境：Ubuntu 22.04/24.04 或 Debian 系虚拟机。若使用 CentOS、Rocky Linux 或其他发行版，请按其官方文档安装 Docker Engine 与 Docker Compose Plugin，后续项目操作不变。

## 1. 部署目标与服务关系

部署完成后，虚拟机中会运行以下容器：

| 服务 | 容器职责 | 是否对宿主机开放 |
| --- | --- | --- |
| `frontend` | React 静态页面与 Nginx 反向代理 | 是，TCP 80 |
| `java-backend` | 上层业务、持久化、并发、异步任务 | 否，仅容器网络 |
| `python-agent` | 下层 Agent、Skill、记忆、RAG、模型调用 | 否，仅容器网络 |
| `postgres` | 业务数据、长期记忆与 pgvector 向量数据 | 否，仅容器网络 |
| `redis` | 缓存与简单限流辅助 | 否，仅容器网络 |
| `rabbitmq` | 简历分析、知识库向量化等异步任务 | 否，仅容器网络 |

访问链路如下：

```text
浏览器
  -> http://<虚拟机IP>/
  -> frontend / Nginx
  -> /api/ 反向代理到 java-backend
  -> java-backend 调用 python-agent
  -> PostgreSQL / Redis / RabbitMQ
  -> 外部 OpenAI-compatible 模型服务
```

## 2. 虚拟机规格与网络准备

建议使用以下资源：

| 场景 | CPU | 内存 | 磁盘 |
| --- | ---: | ---: | ---: |
| 最低演示环境 | 2 核 | 4 GB | 30 GB |
| 推荐开发/答辩环境 | 4 核 | 8 GB | 50 GB |

首次构建 Java、Python、前端镜像时会消耗较多内存和网络带宽。若虚拟机只有 4 GB 内存，建议额外配置 2～4 GB Swap，并在镜像构建期间不要同时运行其他大型任务。

安全组或防火墙只需要开放：

- TCP 22：SSH 远程管理。
- TCP 80：浏览器访问系统。

不要对公网开放 `5432`、`6379`、`5672`、`15672`、`8000`、`8080`。这些端口由 Docker 内部网络使用；暴露它们会增加数据库、消息队列和下层 Agent 被直接访问的风险。

Ubuntu 使用 UFW 时，可以执行：

```bash
sudo ufw allow OpenSSH
sudo ufw allow 80/tcp
sudo ufw enable
sudo ufw status
```

## 3. 安装系统依赖

### 3.1 安装 Git 和基础工具

```bash
sudo apt update
sudo apt install -y git curl ca-certificates gnupg
```

### 3.2 安装 Docker Engine 与 Compose Plugin

以下命令使用 Docker 官方 Ubuntu 软件源。执行前请确认虚拟机可以访问 Docker 软件源。

```bash
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg \
  | sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
sudo chmod a+r /etc/apt/keyrings/docker.gpg

echo \
  "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] https://download.docker.com/linux/ubuntu \
  $(. /etc/os-release && echo \"$VERSION_CODENAME\") stable" \
  | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null

sudo apt update
sudo apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

将当前登录用户加入 Docker 用户组，随后重新登录 SSH：

```bash
sudo usermod -aG docker $USER
exit
```

重新登录后验证：

```bash
docker --version
docker compose version
docker run --rm hello-world
```

如果 `docker` 报权限错误，说明当前终端尚未重新登录，或尚未应用新的用户组。

## 4. 获取项目代码

先确保本机修改已经推送到 GitHub；虚拟机只能拉取远程仓库中已有的提交。

```bash
git clone https://github.com/xiaoliuliutj/interview-agent-project.git interview-agent-project
cd interview-agent-project
git branch --show-current
git log -1 --oneline
```

预期当前分支为 `main`。更新已有部署时使用：

```bash
cd ~/interview-agent-project
git pull --ff-only origin main
```

## 5. 准备运行配置

### 5.1 创建 `.env`

所有密钥和环境配置都放在 `infrastructure/.env`，该文件被 `.gitignore` 忽略，不会提交到 GitHub。

```bash
cp infrastructure/.env.example infrastructure/.env
chmod 600 infrastructure/.env
nano infrastructure/.env
```

至少填写以下字段：

```dotenv
# PostgreSQL 与 RabbitMQ 密码：建议使用 20 位以上的字母和数字。
POSTGRES_PASSWORD=请替换为强密码
RABBITMQ_PASSWORD=请替换为强密码

# 模型配置：当前项目使用 OpenAI-compatible 接口。
MODEL_PROVIDER=openai-compatible
MODEL_NAME=你的聊天模型名称
MODEL_API_KEY=你的模型密钥
MODEL_BASE_URL=https://你的模型服务地址/v1

# 不配置 Embedding 时，简历评价可运行；由于系统知识库不能完成索引，文本面试不能启动。
EMBEDDING_MODEL=
EMBEDDING_API_KEY=
EMBEDDING_BASE_URL=
```

`POSTGRES_PASSWORD` 会被拼接到容器内部数据库连接 URL 中。为了避免 `@`、`:`、`/` 等特殊字符导致 URL 解析失败，当前版本建议密码仅使用大小写字母和数字。

如果聊天模型与 Embedding 模型来自同一兼容服务，可以填写：

```dotenv
EMBEDDING_MODEL=你的向量模型名称
EMBEDDING_API_KEY=你的模型密钥
EMBEDDING_BASE_URL=https://你的模型服务地址/v1
```

不要将 `.env` 内容复制到聊天记录、提交信息或 GitHub Issue 中。

### 5.2 准备中文 PDF 字体（可选但建议）

项目的简历和面试报告支持真实 PDF 导出。中文 PDF 需要 CJK 字体。将合法来源的 `NotoSansCJKsc-Regular.otf` 放到以下位置：

```text
infrastructure/fonts/NotoSansCJKsc-Regular.otf
```

字体文件不进入 Git。缺少字体不会阻止服务启动，但用户下载中文 PDF 时会收到字体缺失错误。

## 6. 一键启动

在项目根目录运行：

```bash
sh scripts/start.sh
```

该脚本会依次执行：

1. 检查 Docker 与 Docker Compose 是否可用。
2. 检查 `infrastructure/.env` 是否存在、模型配置和密码是否已填写。
3. 执行 Compose 配置校验。
4. 构建前端、Java 和 Python 镜像。
5. 以后台方式启动所有容器。
6. 输出容器运行状态。

启动顺序由健康检查保证：

```text
PostgreSQL / Redis / RabbitMQ 健康
  -> Python Agent 健康
  -> Java 上层健康
  -> 前端 Nginx 启动
```

服务镜像已经构建完成、仅需重启时可跳过构建：

```bash
sh scripts/start.sh --no-build
```

Windows 本机上如需运行同一套部署流程，使用：

```powershell
.\scripts\start.ps1
```

## 7. 启动验收

### 7.1 查看容器状态

```bash
cd infrastructure
docker compose --env-file .env ps
```

预期 `postgres`、`redis`、`rabbitmq`、`python-agent`、`java-backend` 和 `frontend` 均处于运行状态；`python-agent`、`java-backend` 和 `frontend` 最终应显示 `healthy`。

### 7.2 查看日志

```bash
docker compose --env-file .env logs -f python-agent java-backend
```

首次启动需要下载依赖和构建镜像，耗时取决于网络。出现模型调用失败时，优先检查 `MODEL_NAME`、`MODEL_API_KEY`、`MODEL_BASE_URL` 和虚拟机是否能访问模型服务。

### 7.3 检查内部健康接口

Java 与 Python 不映射宿主机端口，使用容器内部命令检查：

```bash
docker compose --env-file .env exec python-agent \
  python -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8000/health').read())"

docker compose --env-file .env exec java-backend \
  curl -fsS http://127.0.0.1:8080/actuator/health
```

### 7.4 浏览器验收

在本机浏览器访问：

```text
http://<虚拟机公网IP或内网IP>/
```

建议至少验证以下业务链路：

1. 打开文字面试页面，创建面试会话。
2. 提交一轮回答，确认下一题与评价能够返回。
3. 上传一份简历，观察异步分析任务状态。
4. 上传知识库资料；已配置 Embedding 时，等待向量化完成后，文本面试会在确定题目方向后使用这些资料。
5. 导出 PDF，确认 CJK 字体配置正确。

## 8. 常用运维命令

以下命令均在 `infrastructure/` 目录执行。

| 目的 | 命令 |
| --- | --- |
| 查看运行状态 | `docker compose --env-file .env ps` |
| 查看全部日志 | `docker compose --env-file .env logs -f` |
| 查看 Java/Python 日志 | `docker compose --env-file .env logs -f java-backend python-agent` |
| 重启全部服务 | `docker compose --env-file .env restart` |
| 停止服务但保留数据 | `docker compose --env-file .env down` |
| 重新构建并启动 | `docker compose --env-file .env up -d --build --remove-orphans` |
| 查看资源占用 | `docker stats` |

也可以在根目录执行：

```bash
sh scripts/stop.sh
```

`docker compose down` 不会删除 PostgreSQL、Redis 和文件存储卷。不要在有数据的环境执行 `docker compose down -v`；该命令会删除命名卷，造成数据库、记忆、向量数据和上传文件丢失。

## 9. 更新部署与回滚

### 9.1 正常更新

```bash
cd ~/interview-agent-project
git pull --ff-only origin main
sh scripts/start.sh
```

更新会重新构建发生变化的应用镜像；Compose 命名卷仍会保留业务数据。

### 9.2 更新前备份 PostgreSQL

在项目根目录创建备份目录：

```bash
mkdir -p backups
cd infrastructure
docker compose --env-file .env exec -T postgres sh -c \
  'pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB"' \
  > ../backups/interview-agent-$(date +%F-%H%M%S).sql
```

确认备份文件非空：

```bash
ls -lh ../backups
```

恢复会覆盖或合并现有数据，必须先在测试环境验证。恢复前先停止写入服务，并明确目标数据库：

```bash
# 示例：恢复前请自行确认备份文件和目标环境无误。
cat ../backups/你的备份文件.sql | docker compose --env-file .env exec -T postgres \
  sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
```

### 9.3 代码回滚

不要通过删除 Docker 卷回滚代码。应使用 Git 找到上一稳定提交，然后重新构建：

```bash
git log --oneline -10
git checkout <稳定提交哈希>
sh scripts/start.sh
```

回滚完成后，如需继续开发，应切回 `main`。数据库结构变更应配合备份和迁移策略处理，不能假设旧代码必然兼容新数据。

## 10. 常见问题排查

### 10.1 80 端口被占用

现象：`frontend` 无法启动或日志出现 `bind: address already in use`。

```bash
sudo ss -lntp | grep ':80'
```

停止占用 80 端口的服务，或修改 Compose 中前端的端口映射。修改后浏览器访问端口也要同步变化。

### 10.2 Java 一直不健康

```bash
cd infrastructure
docker compose --env-file .env logs java-backend
```

优先检查：PostgreSQL 密码是否与首次初始化时一致、RabbitMQ 是否健康、`PYTHON_AGENT_BASE_URL` 是否仍指向 `http://python-agent:8000`、数据库表结构是否与当前代码匹配。

注意：首次创建 PostgreSQL 数据卷时才会执行 `postgres/init/` 下的 SQL。已有数据卷不会自动重新执行这些脚本；数据库升级应先备份，再使用正式迁移策略处理。

### 10.3 Python Agent 启动失败或模型调用失败

```bash
docker compose --env-file .env logs python-agent
```

检查 `.env` 中的模型名、密钥和 Base URL。对于 OpenAI-compatible 服务，Base URL 通常需要包含 `/v1`，但应以供应商文档为准。模型还需要支持本项目使用的结构化 JSON 输出能力。

### 10.4 RAG 没有检索结果

检查：

1. 是否填写 `EMBEDDING_MODEL`、`EMBEDDING_API_KEY` 和 `EMBEDDING_BASE_URL`。
2. 知识库向量化异步任务是否已完成。
3. RabbitMQ 与 Python Agent 日志是否有异常。
4. 文档是否属于当前用户，且在查询时被选择。

未配置 Embedding 或系统知识库尚未完成索引时，Java 会拒绝创建文本面试，避免在缺少规定知识库范围时静默降级；先完成系统知识库导入和索引，再启动面试。

### 10.5 中文 PDF 导出失败

确认下列文件存在且可读：

```bash
ls -lh infrastructure/fonts/NotoSansCJKsc-Regular.otf
```

同时确认 `.env` 中的 `AGENT_PDF_FONT_PATH` 保持为：

```dotenv
AGENT_PDF_FONT_PATH=/app/fonts/NotoSansCJKsc-Regular.otf
```

### 10.6 磁盘空间不足

```bash
docker system df
df -h
```

只在确认无用镜像、停止容器和构建缓存可以删除时，再执行：

```bash
docker system prune
```

不要使用带 `-a --volumes` 的清理命令，除非已完成备份并确认要删除所有未使用镜像与卷。

## 11. 当前部署范围

当前方案适合单台虚拟机上的课程项目、实习面试演示和开发验收。它已覆盖容器化、一键启动、健康检查、服务依赖、数据卷、内部网络边界和敏感配置外置。

下列能力属于后续生产化演进，不在当前一期部署中实现：HTTPS 证书、登录鉴权、外部对象存储、数据库高可用、容器多副本扩缩容、集中日志、监控告警、自动备份和 CI/CD 发布流水线。
