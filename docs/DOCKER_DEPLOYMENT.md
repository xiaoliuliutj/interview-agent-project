# Docker 一键部署

完整的虚拟机安装、配置、启动、验收、升级、备份与排障步骤见 [VM_DEPLOYMENT_GUIDE.md](VM_DEPLOYMENT_GUIDE.md)。本文件保留快速启动摘要。

本项目按最终架构部署为 6 个容器服务：React/Nginx 前端、Java 上层、Python Agent 下层、PostgreSQL/pgvector、Redis 和 RabbitMQ。浏览器只访问前端；Java、Python、数据库和中间件均处在 Docker 内部网络中。

## 1. 前置条件

- 虚拟机安装 Docker Engine 和 Docker Compose Plugin（`docker compose version` 可用）。
- 虚拟机开放 TCP `80`；如需远程管理，再按需开放 SSH 端口。
- 为模型服务准备 OpenAI-compatible 的 `MODEL_NAME`、`MODEL_API_KEY` 和可选的 `MODEL_BASE_URL`。
- 如需导出中文简历或面试报告 PDF，将 `NotoSansCJKsc-Regular.otf` 放入 `infrastructure/fonts/`。字体文件不提交到 Git。

不要开放 PostgreSQL、Redis、RabbitMQ、Java 或 Python Agent 的宿主机端口；它们由 Compose 内部网络访问。

## 2. 首次配置

在项目根目录执行下列命令之一：

```powershell
# Windows PowerShell
.\scripts\start.ps1
```

```bash
# Linux 虚拟机
sh scripts/start.sh
```

首次执行会创建未被 Git 跟踪的 `infrastructure/.env`，随后停止。编辑该文件，至少填写：

```dotenv
POSTGRES_PASSWORD=请设置高强度且仅含字母数字的密码
RABBITMQ_PASSWORD=请设置高强度且仅含字母数字的密码
MODEL_NAME=你的模型名
MODEL_API_KEY=你的模型密钥
MODEL_BASE_URL=https://你的兼容接口地址/v1
```

密码建议使用字母和数字，避免 `@`、`:`、`/` 等字符破坏容器内数据库连接 URL。

## 3. 一键启动与检查

完成配置后再次运行同一启动脚本。脚本会先校验 Compose 配置，再构建镜像并以后台方式启动全部服务：

```bash
sh scripts/start.sh
```

无代码变更、只需重新启动时可跳过构建：

```bash
sh scripts/start.sh --no-build
```

查看状态和日志：

```bash
cd infrastructure
docker compose --env-file .env ps
docker compose --env-file .env logs -f java-backend python-agent
```

待 `frontend`、`java-backend` 和 `python-agent` 显示为 `healthy` 后，访问：

```text
http://<虚拟机 IP>/
```

启动链路为：PostgreSQL、Redis、RabbitMQ 健康 -> Python Agent 健康 -> Java 上层健康 -> 前端启动。应用容器设置了 `unless-stopped`，虚拟机重启后 Docker 服务恢复时会自动拉起。

## 4. 停止、更新与数据

普通停止不会清除数据卷：

```bash
sh scripts/stop.sh
```

更新代码后，在项目根目录执行：

```bash
git pull
sh scripts/start.sh
```

数据存储在 Compose 命名卷中：PostgreSQL（业务数据、记忆和向量）、Redis（缓存）以及 Java 文件卷（原始简历和报告）。`docker compose down` 不会删除这些卷；只有显式执行 `docker compose down -v` 才会删除数据，生产或演示数据环境不要使用该命令。

## 5. 部署边界

此部署面向单台虚拟机演示和开发验收。它不包含 HTTPS 证书、外部对象存储、数据库备份、监控告警或多副本高可用；这些属于后续生产化演进，而非本项目一期范围。
