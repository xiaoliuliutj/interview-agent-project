# GET /v1/agent/sessions/{session_id}/progress：查询面试处理进度

## 1. 接口定义

该接口为上层 Java 或前端查询某个面试会话当前处理阶段。阶段值来自 `InterviewAgentService` 的内存进度表，典型值包括 `PLANNING`、`EVALUATING`、`ROUTING`、`GENERATING_QUESTION`、`COMPLETED`、`FAILED` 和初始 `IDLE`。它不读取会话持久化记录。

| 项目 | 内容 |
|---|---|
| HTTP 方法 | GET |
| 路径 | `/v1/agent/sessions/{session_id}/progress` |
| 路由函数 | `session_progress` |
| 文件 | `python-agent/app/api/application.py:64-68` |
| 下游函数 | `_resolve_service`、`InterviewAgentService.progress_for` |

## 2. 函数调用链

```text
FastAPI 路由分发
 -> session_progress
 -> _resolve_service
    -> 已注入 service，或 build_interview_agent_service（冷启动分支）
 -> InterviewAgentService.progress_for
 -> 返回 {"stage": stage}
```

## 3. 函数解析

### 3.1 `session_progress`

文件：`python-agent/app/api/application.py:64-68`

```python
    @app.get("/v1/agent/sessions/{session_id}/progress")
    async def session_progress(session_id: str, request: Request) -> dict[str, str]:
        service = _resolve_service(request)
        progress = getattr(service, "progress_for", None)
        return {"stage": progress(session_id) if callable(progress) else "IDLE"}
```

逐行解释：

1. 第 64 行：注册带路径参数 `session_id` 的 GET 路由；花括号参数由 FastAPI 提取并传给函数。
2. 第 65 行：声明异步路由函数，`session_id` 是字符串，会话请求对象提供应用状态。
3. 第 66 行：调用 `_resolve_service` 获取当前应用缓存的面试服务；服务为空时该辅助函数负责懒加载并写回 `request.app.state`。
4. 第 67 行：通过 `getattr` 读取 `progress_for`，缺失时使用 `None`，兼容测试替身或不完整实现。
5. 第 68 行：仅当属性可调用时用会话 ID 查询阶段，否则返回 `IDLE`；最终字典由 FastAPI 序列化。

### 3.2 `_resolve_service`

文件：`python-agent/app/api/application.py:312-318`

```python
def _resolve_service(request: Request) -> InterviewAgentService:
    service = request.app.state.interview_agent_service
    if service is None:
        service = build_interview_agent_service()
        request.app.state.interview_agent_service = service
    return service
```

逐行解释：

1. 第 312 行：定义从 FastAPI 请求状态解析面试服务的同步辅助函数。
2. 第 313 行：读取应用启动时保存的 `interview_agent_service`。
3. 第 314 行：判断状态中没有实例的冷启动条件。
4. 第 315 行：调用项目装配函数 `build_interview_agent_service` 创建真实服务及其依赖。
5. 第 316 行：把实例写回应用状态，使后续请求复用而非重复构建。
6. 第 317 行：返回已存在或刚创建的服务对象。

### 3.3 `InterviewAgentService.progress_for`

文件：`python-agent/app/agents/interview/service.py:99-100`

```python
    def progress_for(self, session_id: str) -> str:
        return self._progress.get(session_id, "IDLE")
```

逐行解释：

1. 第 99 行：定义同步查询函数，参数是会话标识，返回阶段字符串。
2. 第 100 行：从进度字典读取会话阶段；没有记录时返回 `IDLE`，因此不会把不存在的会话误报为完成或失败。

## 4. 审核结论

该接口的正常热路径只包含三个项目函数；`build_interview_agent_service` 仅在应用未注入服务的冷启动分支调用，实际装配链记录在 Agent 模块文档中。
