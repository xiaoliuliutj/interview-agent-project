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

## 4. 审核结论

源码中该路由只有 `health` 一个项目定义函数，没有隐藏的 Python 业务调用；数据库和模型初始化不会在此请求中发生。
