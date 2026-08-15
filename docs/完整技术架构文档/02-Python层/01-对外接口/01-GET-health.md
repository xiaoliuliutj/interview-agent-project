# GET /health：Python Agent 健康检查

## 1. 接口定义

该接口由 FastAPI 在 `create_app` 注册，用于确认 Python Agent 进程能够接收请求。它不访问数据库、模型、RAG 或记忆服务，只返回固定的 `UP` 状态，因此只能证明应用层存活，不能替代下游依赖检查。

| 项目 | 内容 |
|---|---|
| HTTP 方法 | GET |
| 路径 | `/health` |
| 路由函数 | `health` |
| 文件 | `python-agent/app/api/application.py:60-62` |
| 成功响应 | `{"status":"UP"}` |
| Python/Java 边界 | Java 或负载均衡器发起 HTTP GET，Python FastAPI 直接返回 |

## 2. 函数调用链

```text
FastAPI 路由分发
 -> health
 -> 返回字典并由 FastAPI 序列化为 JSON
```

## 3. 函数解析

### 3.1 `health`

文件：`python-agent/app/api/application.py:60-62`

```python
    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "UP"}
```

逐行解释：

1. 第 60 行：`@app.get` 把下一 个异步函数注册为 GET `/health` 路由；装饰器本身由 FastAPI 执行，项目代码提供路径和函数绑定。
2. 第 61 行：定义无参数异步函数 `health`，返回类型标注为字符串键和值组成的字典；请求体、鉴权信息和会话信息均不参与该函数。
3. 第 62 行：构造并返回状态字典，值固定为 `UP`；FastAPI 随后把该 Python 字典编码为 HTTP 200 JSON 响应。

## 4. 主流构建分析

主流生产系统通常把健康检查拆成存活探针（liveness）和就绪探针（readiness）：存活探针只证明进程事件循环可响应；就绪探针还检查数据库、Redis、向量库或模型网关是否满足接流量条件。优点是 Kubernetes、负载均衡器能区分“需要重启进程”和“暂时停止转发流量”，避免依赖短暂故障引发无意义重启；缺点是依赖检查会增加请求开销，并可能因级联依赖抖动把所有实例同时判为不可用。

本项目当前 `/health` 作为 Python 容器的轻量存活探针是合适的，因为 Compose 只需要确认 Uvicorn 已启动，Java 的实际调用仍有超时和重试保护。若部署到多实例生产环境，可保留 `/health` 不变，再新增 `/ready`：通过项目装配层分别执行数据库 `SELECT 1`、Python 专属 Redis `PING` 和向量仓库轻量检查，设置单项短超时并返回各依赖状态；模型供应商不宜在每次就绪检查中实际调用，以免产生成本和外部故障级联。
