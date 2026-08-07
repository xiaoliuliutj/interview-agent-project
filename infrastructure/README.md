# 虚拟机部署说明

在已安装 Docker Compose 的 Linux 虚拟机上：

1. 复制 `infrastructure/.env.example` 为 `infrastructure/.env`，填写数据库密码、模型名称、API Key 和 Base URL。该文件不会进入 Git。
2. 在 `infrastructure/` 目录执行 `docker compose build`。
3. 执行 `docker compose up -d`。
4. 通过 `http://虚拟机地址/` 访问前端；Java 上层使用 8080，Python 下层仅作为内部服务使用 8000。
5. 使用 `docker compose ps` 和 `docker compose logs java-backend python-agent` 检查服务；`python-agent` 健康检查通过后 Java 才会启动。

PostgreSQL 初始化脚本会创建 pgvector 扩展和双方首期表结构。Nginx 已关闭 `/api/` 的响应缓冲，以支持知识库和 Agent 的 SSE 响应。生产环境应将脚本迁移到版本化 Flyway/Alembic 迁移，并使用反向代理、TLS、密钥管理和备份策略。不要把真实 `.env`、模型密钥或数据库密码写入镜像。
