# 虚拟机部署说明

在已安装 Docker Engine 和 Docker Compose Plugin 的 Linux 虚拟机上，推荐从项目根目录使用一键脚本：

```sh
cp infrastructure/.env.example infrastructure/.env
# 编辑 infrastructure/.env，至少填写密码、模型配置和 AGENT_SYSTEM_KNOWLEDGE_BASE_IDS
sh scripts/start.sh
```

`start.sh` 会校验环境变量、停止旧容器但保留命名卷、构建三个业务镜像、启动基础设施并等待健康检查。常用选项：

- `sh scripts/start.sh --no-build`：只替换并启动已有镜像。
- `sh scripts/start.sh --pull`：构建前拉取基础镜像；网络受限时可在 `.env` 配置 `MAVEN_MIRROR_URL`、`PIP_INDEX_URL` 或 `NPM_REGISTRY`。
- `sh scripts/start.sh --rebuild`：禁用 Dockerfile 层缓存重新构建。

停止服务但保留数据：

```sh
sh scripts/stop.sh
```

只有确认要删除 PostgreSQL、Redis、RabbitMQ 和文件数据时才执行 `sh scripts/stop.sh --volumes`。

通过 `http://虚拟机地址/` 访问前端；Java 上层使用 8080，Python 下层仅作为内部服务使用。使用 `docker compose -p interview-guide --env-file infrastructure/.env -f infrastructure/docker-compose.yml ps` 和 `logs java-backend python-agent` 检查服务；`python-agent` 健康检查通过后 Java 才会启动。

PostgreSQL 初始化脚本会创建 pgvector 扩展和双方首期表结构，后续结构升级使用同目录下按序号管理的 SQL 脚本。Nginx 已关闭 `/api/` 的响应缓冲，以支持知识库和 Agent 的 SSE 响应。生产环境还应使用反向代理、TLS、密钥管理和备份策略。不要把真实 `.env`、模型密钥或数据库密码写入镜像。
