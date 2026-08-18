# POST /v1/agent/resume/activate：激活简历长期记忆

## 1. 接口定义

该接口不调用评价模型，只把候选人的简历文本、目标岗位和候选人标识合并到用户长期记忆的 active resume 快照中。Java 简历分析异步任务通常先调用该接口，再调用评价接口。

| 项目 | 内容 |
|---|---|
| HTTP 方法 | POST |
| 路径 | `/v1/agent/resume/activate` |
| 路由函数 | `activate_resume_memory` |
| 文件 | `python-agent/app/api/application.py:208-224` |

## 2. 函数调用链

```text
activate_resume_memory
 -> _remember_request_context
 -> _resolve_memory_service
    -> （冷启动）build_memory_service
       -> get_settings -> create_session_factory -> create_engine
       -> MemoryPolicy.load
 -> MemoryService.activate_resume
    -> _resume_activation_fingerprint
    -> PostgresLongTermMemoryRepository.get
    -> （用户记忆不存在）PostgresLongTermMemoryRepository.create -> _to_entity
    -> （已有 runId）输入一致直接返回 / 输入不一致抛 ConsistencyError
    -> _merge_resume_snapshot
    -> PostgresLongTermMemoryRepository.save
 -> AgentResponse -> AgentResponse.validate_code_category
```

异常分支：

```text
请求模型校验失败
 -> request_validation_error -> _error_json_response
 -> _error_response -> _request_context / _session_status_or_failed / _string_or_none
 -> ExceptionHandler.to_code / ExceptionHandler.to_error_info
 -> AgentResponse.validate_code_category -> AgentResponse.to_json_dict

ApplicationException
 -> application_error -> _mark_failed_interview_progress（本路径立即返回）
 -> _error_json_response -> 其余统一错误链

其他 Exception
 -> unexpected_error -> 记录异常日志
 -> _mark_failed_interview_progress（本路径立即返回）
 -> _error_json_response -> 其余统一错误链
```

## 3. 函数解析

### 3.1 `activate_resume_memory`

文件：`python-agent/app/api/application.py:208-224`

```python
    @app.post("/v1/agent/resume/activate", response_model=AgentResponse)
    async def activate_resume_memory(
        payload: AgentResumeMemoryActivationRequest, request: Request
    ) -> AgentResponse:
        _remember_request_context(request, payload)
        await _resolve_memory_service(request).activate_resume(
            user_id=payload.user_id, resume_id=payload.subject_id,
            candidate_id=payload.candidate_id, resume_text=payload.input_text,
            target_role=payload.target_role, run_id=payload.run_id,
        )
        return AgentResponse(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, code=100, status=RunStatus.COMPLETED,
            user_id=payload.user_id, session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE, state_version=0,
            answer=None, output=None, error=None,
        )
```

逐行解释：

1. 第 208 行：注册 HTTP `POST /v1/agent/resume/activate`，响应模型为 AgentResponse。
2. 第 209 行：定义异步路由函数。
3. 第 210 行：接收已校验 AgentResumeMemoryActivationRequest 与 Request。
4. 第 211 行：结束函数签名。
5. 第 212 行：调用 _remember_request_context 保存错误响应上下文。
6. 第 213 行：调用 _resolve_memory_service，并继续等待 MemoryService.activate_resume。
7. 第 214 行：传入 userId 与作为 resumeId 的 subjectId。
8. 第 215 行：传入 candidateId 与完整简历原文。
9. 第 216 行：传入 targetRole 与 runId。
10. 第 217 行：结束调用；返回 LongTermMemory 不向接口暴露。
11. 第 218 行：开始构造 AgentResponse。
12. 第 219 行：复制 apiVersion 与 requestId。
13. 第 220 行：复制 runId，code 100，运行状态 COMPLETED。
14. 第 221 行：复制 userId 与 sessionId。
15. 第 222 行：激活不推进面试会话，协议状态固定 ACTIVE、版本 0。
16. 第 223 行：没有问题或评价结果，answer/output/error 均为 None。
17. 第 224 行：返回响应。

### 3.2 `MemoryService.activate_resume`

文件：`python-agent/app/memory/service.py:49-97`

```python
    async def activate_resume(
        self, *, user_id: str, resume_id: str, candidate_id: str, resume_text: str,
        target_role: str, run_id: str | None = None,
    ) -> LongTermMemory:
        """Make a newly uploaded resume the only version allowed to write profile data.

        This is called before asynchronous evaluation.  If an older evaluation returns
        afterwards, ``record_resume_analysis`` rejects it rather than overwriting the
        latest user profile.
        """
        fingerprint = self._resume_activation_fingerprint(
            resume_id=resume_id, candidate_id=candidate_id,
            resume_text=resume_text, target_role=target_role,
        )
        snapshot = ResumeMemory(
            resume_id=resume_id, candidate_id=candidate_id, target_role=target_role,
            resume_text=resume_text,
        )
        existing = await self._repository.get(user_id)
        if existing is None:
            memory = LongTermMemory(
                user_id=user_id, active_resume_id=resume_id, resume_snapshots=[snapshot]
            )
            if run_id:
                memory.resume_activation_runs[run_id] = ResumeActivationRun(
                    run_id=run_id, resume_id=resume_id, fingerprint=fingerprint
                )
            return await self._repository.create(memory)
        existing_run = existing.resume_activation_runs.get(run_id) if run_id else None
        if existing_run is not None:
            if existing_run.resume_id != resume_id or existing_run.fingerprint != fingerprint:
                raise ConsistencyError("同一 resume activation runId 不能提交不同的输入")
            return existing
        expected_version = existing.state_version
        existing.active_resume_id = resume_id
        existing.resume_snapshots = self._merge_resume_snapshot(existing.resume_snapshots, snapshot)
        # These three fields are derived from a resume evaluation.  Clearing them here
        # removes an already-finished old evaluation before the new evaluation arrives.
        existing.technical_stack = []
        existing.technical_depth = []
        existing.preferences = []
        if run_id:
            existing.resume_activation_runs[run_id] = ResumeActivationRun(
                run_id=run_id, resume_id=resume_id, fingerprint=fingerprint
            )
            while len(existing.resume_activation_runs) > self._policy.max_resume_evaluation_runs:
                existing.resume_activation_runs.pop(next(iter(existing.resume_activation_runs)))
        existing.updated_at = datetime.now(timezone.utc)
        return await self._repository.save(existing, expected_version=expected_version)
```

逐行解释：

1. 第 49 行：定义异步激活函数。
2. 第 50 行：接收实例、userId、resumeId、candidateId、resumeText。
3. 第 51 行：接收 targetRole 和可选 runId。
4. 第 52 行：声明返回 LongTermMemory。
5. 第 53 行：文档字符串开始说明新简历成为唯一允许写画像的版本。
6. 第 55-57 行：说明异步旧评价晚到时会被 record_resume_analysis 拒绝。
7. 第 58 行：结束文档字符串。
8. 第 59 行：调用 _resume_activation_fingerprint。
9. 第 60 行：传入 resumeId 与 candidateId。
10. 第 61 行：传入 resumeText 与 targetRole。
11. 第 62 行：结束指纹调用。
12. 第 63 行：开始构造 ResumeMemory 快照。
13. 第 64 行：写 resumeId、candidateId 和 targetRole。
14. 第 65 行：写 resumeText。
15. 第 66 行：结束快照。
16. 第 67 行：调用 PostgresLongTermMemoryRepository.get(userId)。
17. 第 68 行：检查用户记忆不存在。
18. 第 69 行：开始创建 LongTermMemory。
19. 第 70 行：写 userId、activeResumeId 和唯一简历快照。
20. 第 71 行：结束对象。
21. 第 72 行：检查 runId 非空。
22. 第 73 行：以 runId 为键写 ResumeActivationRun。
23. 第 74 行：快照写 runId、resumeId 与 fingerprint。
24. 第 75 行：结束运行快照。
25. 第 76 行：调用 PostgresLongTermMemoryRepository.create 并返回。
26. 第 77 行：已有记忆时按 runId 查询激活运行；runId 为空则 None。
27. 第 78 行：检查已有运行。
28. 第 79 行：比较已有 resumeId 或 fingerprint。
29. 第 80 行：任一不同抛 ConsistencyError。
30. 第 81 行：输入一致时直接返回 existing，实现幂等。
31. 第 82 行：保存当前 stateVersion。
32. 第 83 行：切换 activeResumeId。
33. 第 84 行：调用 _merge_resume_snapshot 合并新快照。
34. 第 85 行：注释说明以下字段来自简历评价。
35. 第 86 行：注释说明新评价到达前要清除旧评价结果。
36. 第 87 行：清空技术栈。
37. 第 88 行：清空技术深度。
38. 第 89 行：清空职业偏好。
39. 第 90 行：检查 runId。
40. 第 91 行：写入新的 ResumeActivationRun。
41. 第 92 行：写 runId、resumeId 和 fingerprint。
42. 第 93 行：结束快照。
43. 第 94 行：当激活运行数超过策略上限时循环清理。
44. 第 95 行：删除插入顺序最旧记录。
45. 第 96 行：更新 UTC 时间。
46. 第 97 行：调用长期记忆仓储 save(existing, expectedVersion) 并返回。

### 3.3 `_resume_activation_fingerprint`

文件：`python-agent/app/memory/service.py:257-267`

逐行解释：

1. 第 257 行：声明静态方法。
2. 第 258 行：定义激活输入指纹函数。
3. 第 259 行：强制 resumeId、candidateId、resumeText、targetRole 具名传入。
4. 第 260 行：声明返回字符串。
5. 第 261 行：开始构造确定性 JSON。
6. 第 262 行：写 resumeId。
7. 第 263 行：写 candidateId。
8. 第 264 行：写完整 resumeText。
9. 第 265 行：写 targetRole。
10. 第 266 行：以中文安全、键排序和紧凑分隔符序列化。
11. 第 267 行：UTF-8 编码后计算 SHA-256 十六进制摘要。

### 3.4 `_merge_resume_snapshot`

文件：`python-agent/app/memory/service.py:250-252`

逐行解释：

1. 第 250 行：定义快照合并函数。
2. 第 251 行：过滤掉与 incoming.resumeId 相同的旧快照。
3. 第 252 行：把 incoming 放在首位，追加其余快照，并按策略 maxResumeSnapshots 截断。

### 3.5 `PostgresLongTermMemoryRepository.get`、`create` 与 `save`

文件：`python-agent/app/infrastructure/persistence/long_term_memory_repository.py:31-86`

`get` 逐行解释：

1. 第 31 行：定义按 userId 读取长期记忆函数。
2. 第 32 行：打开异步数据库会话。
3. 第 33 行：执行单标量查询。
4. 第 34 行：选择 LongTermMemoryEntity。
5. 第 35 行：WHERE 条件为 userId。
6. 第 36-37 行：结束查询。
7. 第 38 行：实体存在时校验恢复 LongTermMemory，否则返回 None。

`create` 逐行解释：

1. 第 40 行：定义首次创建函数。
2. 第 41 行：进入唯一键冲突保护。
3. 第 42 行：打开数据库会话。
4. 第 43 行：调用项目函数 _to_entity(memory) 并加入会话。
5. 第 44 行：提交事务。
6. 第 45 行：捕获 IntegrityError。
7. 第 46 行：转换为 ConsistencyError("用户长期记忆已存在")。
8. 第 47 行：返回原领域对象。

`save` 逐行解释：

1. 第 49-51 行：定义带 expectedVersion 的乐观保存函数。
2. 第 52 行：复制记忆对象。
3. 第 53 行：开始更新字典。
4. 第 54 行：版本加一。
5. 第 55 行：更新时间取 UTC。
6. 第 56-57 行：结束复制。
7. 第 58 行：开始 UPDATE。
8. 第 59 行：目标为长期记忆表。
9. 第 60 行：开始 WHERE。
10. 第 61 行：userId 必须匹配。
11. 第 62 行：数据库版本必须等于 expectedVersion。
12. 第 63 行：结束 WHERE。
13. 第 64 行：开始 values。
14. 第 65 行：写新版本。
15. 第 66 行：写完整 memory JSON。
16. 第 67 行：写更新时间。
17. 第 68-69 行：结束语句。
18. 第 70 行：打开数据库会话。
19. 第 71 行：执行 UPDATE。
20. 第 72 行：检查受影响行数不是 1。
21. 第 73 行：冲突时回滚。
22. 第 74 行：抛并发修改 ConsistencyError。
23. 第 75 行：成功时提交。
24. 第 76 行：返回新版本记忆。

`_to_entity` 文件：`python-agent/app/infrastructure/persistence/long_term_memory_repository.py:78-86`

1. 第 78 行：声明静态方法。
2. 第 79 行：定义领域到实体转换。
3. 第 80 行：开始构造 LongTermMemoryEntity。
4. 第 81 行：复制 userId。
5. 第 82 行：复制 stateVersion。
6. 第 83 行：序列化完整 memoryData。
7. 第 84 行：复制 createdAt。
8. 第 85 行：复制 updatedAt。
9. 第 86 行：返回实体。

### 3.6 `_remember_request_context`

文件：`python-agent/app/api/application.py:391-394`

1. 第 391 行：定义请求上下文记录函数。
2. 第 392 行：兼容读取 model_dump。
3. 第 393 行：确认可调用。
4. 第 394 行：按字段别名和 JSON 模式导出，保存到 request.state.agent_context。

### 3.7 `_resolve_memory_service` 与 `build_memory_service`

`_resolve_memory_service` 文件：`python-agent/app/api/application.py:351-357`

1. 第 351 行：定义记忆服务解析函数。
2. 第 352 行：从应用状态读取服务。
3. 第 353 行：检查未构造。
4. 第 354 行：函数内导入 build_memory_service。
5. 第 355 行：调用工厂。
6. 第 356 行：写回应用状态。
7. 第 357 行：返回服务。

`build_memory_service` 文件：`python-agent/app/bootstrap.py:33-38`

1. 第 33 行：定义工厂。
2. 第 34 行：选择显式配置或 get_settings。
3. 第 35 行：调用 create_session_factory。
4. 第 36 行：开始构造 MemoryService。
5. 第 37 行：注入 PostgreSQL 仓储并调用 MemoryPolicy.load。
6. 第 38 行：返回服务。

### 3.8 `get_settings`、数据库工厂与 `MemoryPolicy.load`

`get_settings` 文件：`python-agent/app/common/config.py:47-51`

1. 第 47 行：以 LRU 最大 1 缓存。
2. 第 48 行：定义读取函数。
3. 第 49 行：说明返回进程配置快照。
4. 第 51 行：实例化 Settings，从环境和 .env 读取并校验。

`create_engine` 文件：`python-agent/app/infrastructure/persistence/database.py:9-13`

1. 第 9 行：定义引擎工厂。
2. 第 10 行：选择配置。
3. 第 11 行：检查 databaseUrl。
4. 第 12 行：缺失时抛 PersistenceConfigurationError。
5. 第 13 行：创建异步引擎并启用 poolPrePing。

`create_session_factory` 文件：`python-agent/app/infrastructure/persistence/database.py:16-19`

1. 第 16-18 行：定义会话工厂函数。
2. 第 19 行：调用 create_engine，并创建 expireOnCommit=False 的 asyncSessionMaker。

`MemoryPolicy.load` 文件：`python-agent/app/memory/policy.py:18-40`

逐行解释：

1. 第 18 行：声明类方法。
2. 第 19 行：定义策略加载。
3. 第 20 行：选择路径或 memory-policy.json。
4. 第 21 行：进入读取保护。
5. 第 22 行：解析 JSON。
6. 第 23 行：开始构造。
7. 第 24 行：读取短期轮次上限。
8. 第 25 行：读取摘要字符上限。
9. 第 26 行：读取简历快照上限。
10. 第 27 行：读取评价运行保留数。
11. 第 28 行：结束构造。
12. 第 29 行：捕获配置读取错误。
13. 第 30 行：统一抛 WorkflowConfigurationError。
14. 第 32 行：短期轮次必须是 3、4、5。
15. 第 33 行：失败抛错。
16. 第 34 行：摘要容量至少 200。
17. 第 35 行：失败抛错。
18. 第 36 行：简历快照至少 1。
19. 第 37 行：失败抛错。
20. 第 38 行：评价运行保留数为正。
21. 第 39 行：失败抛错。
22. 第 40 行：返回策略。

### 3.9 `AgentResponse.validate_code_category`

文件：`python-agent/app/common/contracts.py:177-182`

1. 第 177 行：注册 code 字段校验器。
2. 第 178 行：声明类方法。
3. 第 179 行：定义校验函数。
4. 第 180 行：要求 code 首位属于 1~5。
5. 第 181 行：不满足抛 ValueError。
6. 第 182 行：返回合法值。

### 3.10 FastAPI 异常入口与统一错误响应

`request_validation_error` 文件：`python-agent/app/api/application.py:292-299`

1. 第 292 行：注册请求校验错误处理器。
2. 第 293 行：定义异步函数。
3. 第 294 行：读取 error.body。
4. 第 295 行：body 是映射时作为上下文。
5. 第 296 行：调用 _error_json_response。
6. 第 297 行：转换 RequestError 并设置 HTTP 400。
7. 第 298 行：传入上下文。
8. 第 299 行：返回。

`application_error` 文件：`python-agent/app/api/application.py:301-304`

1. 第 301 行：注册 ApplicationException 处理器。
2. 第 302 行：定义异步函数。
3. 第 303 行：调用 _mark_failed_interview_progress；本路径立即返回。
4. 第 304 行：调用 _error_json_response，HTTP 200。

`unexpected_error` 文件：`python-agent/app/api/application.py:306-310`

1. 第 306 行：注册其他 Exception 处理器。
2. 第 307 行：定义异步函数。
3. 第 308 行：记录异常堆栈。
4. 第 309 行：调用 _mark_failed_interview_progress。
5. 第 310 行：调用 _error_json_response，HTTP 500。

`_mark_failed_interview_progress` 文件：`python-agent/app/api/application.py:323-331`

1. 第 323 行：定义失败进度补偿。
2. 第 324 行：检查路径不是 respond。
3. 第 325 行：本激活接口立即返回。
4. 第 326 行：仅 respond 路径才调用 `_request_context` 恢复请求字段；本接口因第 325 行已返回，不执行该行。
5. 第 327 行：仅 respond 路径才调用 `_string_or_none` 清洗 sessionId。
6. 第 328 行：仅 respond 路径才从应用状态读取面试服务，异常路径不会冷启动新服务。
7. 第 329 行：通过 `getattr` 兼容读取 `mark_progress_failed`。
8. 第 330 行：只有 sessionId 非空且标记方法可调用时进入补偿。
9. 第 331 行：调用 `mark_progress_failed(sessionId)`；本激活接口不执行该行。

`_request_context` 文件：`python-agent/app/api/application.py:379-388`

1. 第 379 行：定义上下文恢复。
2. 第 380 行：读取已记住上下文。
3. 第 381 行：检查映射。
4. 第 382 行：直接返回映射。
5. 第 383 行：进入 body 解析保护。
6. 第 384 行：读取 body。
7. 第 385 行：非空解析 JSON。
8. 第 386 行：根节点字典才返回。
9. 第 387 行：捕获 JSON、Unicode 和运行时错误。
10. 第 388 行：失败返回空字典。

`_error_response` 文件：`python-agent/app/api/application.py:397-411`

1. 第 397-399 行：定义异常到 AgentResponse 转换。
2. 第 400 行：显式上下文优先，否则调用 _request_context。
3. 第 401 行：调用 _session_status_or_failed。
4. 第 402 行：读取 stateVersion。
5. 第 403 行：开始构造响应。
6. 第 404 行：调用 _string_or_none 转换 apiVersion。
7. 第 405 行：转换 requestId。
8. 第 406 行：转换 runId 并调用 ExceptionHandler.to_code。
9. 第 407 行：状态 FAILED 并转换 userId。
10. 第 408 行：转换 sessionId 并写会话状态。
11. 第 409 行：版本必须非负整数，否则 0。
12. 第 410 行：answer None、currentStage FAILED，并调用 to_error_info。
13. 第 411 行：返回。

`_string_or_none` 文件：`python-agent/app/api/application.py:414-415`

1. 第 414 行：定义字符串清洗。
2. 第 415 行：非空字符串返回，否则 None。

`_session_status_or_failed` 文件：`python-agent/app/api/application.py:418-423`

1. 第 418 行：定义状态转换。
2. 第 419 行：说明运行失败不能误改已有会话。
3. 第 420 行：进入保护。
4. 第 421 行：构造 SessionStatus。
5. 第 422 行：捕获类型和值错误。
6. 第 423 行：失败回退 FAILED。

`ExceptionHandler.to_code` 文件：`python-agent/app/common/exceptions.py:139-146`

1. 第 139 行：声明类方法。
2. 第 140 行：定义 code 转换。
3. 第 141 行：识别项目 `ApplicationException`。
4. 第 142 行：返回异常类声明的 code。
5. 第 143 行：遍历内置异常映射。
6. 第 144 行：按 `isinstance` 匹配当前异常类型。
7. 第 145 行：匹配时返回映射 code。
8. 第 146 行：未知异常返回 500。

`ExceptionHandler.to_error_info` 文件：`python-agent/app/common/exceptions.py:116-137`

1. 第 116 行：声明类方法。
2. 第 117 行：定义 ErrorInfo 转换。
3. 第 118 行：识别项目 ApplicationException。
4. 第 119 行：开始构造 ErrorInfo。
5. 第 120 行：类型取项目异常 errorType。
6. 第 121 行：消息取项目异常 message。
7. 第 122 行：复制 retryable。
8. 第 123 行：返回项目错误信息。
9. 第 125 行：遍历内置异常映射。
10. 第 126 行：按异常类型匹配。
11. 第 127 行：开始构造内置映射 ErrorInfo。
12. 第 128 行：写映射错误名。
13. 第 129 行：优先使用异常文本，空文本时用错误名。
14. 第 130 行：写 retryable。
15. 第 131 行：返回映射错误。
16. 第 133 行：开始未知错误。
17. 第 134 行：类型 INTERNAL_ERROR。
18. 第 135 行：固定外部消息。
19. 第 136 行：不可重试。
20. 第 137 行：返回。

`_error_json_response` 文件：`python-agent/app/api/application.py:447-455`

1. 第 447-453 行：定义 JSON 包装函数。
2. 第 454 行：调用 _error_response。
3. 第 455 行：调用 AgentResponse.to_json_dict 并返回 JSONResponse。

`AgentResponse.to_json_dict` 文件：`python-agent/app/common/contracts.py:184-185`

1. 第 184 行：定义 JSON 导出。
2. 第 185 行：以 JSON 模式、别名并保留 null 导出。

## 4. 主流构建分析

主流候选人画像系统通常把“简历版本激活”建模为独立版本实体与原子 active pointer：先不可变保存 ResumeVersion，再用条件更新切换 UserProfile.activeResumeVersion；旧版本异步分析结果通过版本号或 generation token 拒绝写入。优点是版本历史、回滚、审计和并发边界清晰；缺点是表结构、存储量和清理策略更复杂。

本项目已经使用 activeResumeId、runId 指纹和 stateVersion 乐观锁实现核心安全性，但简历快照与运行快照嵌在长期记忆 JSON 中，版本查询和局部更新成本会随数据增长。当前实习项目规模下保持现状简单且适配。若要采用主流方式，可拆出 `resume_versions` 与 `resume_activation_runs` 表，激活事务只更新 activeResumeId 并写 Outbox；评价 Worker 按 resumeVersionId 条件更新画像。现有 `_resume_activation_fingerprint` 和旧结果拒绝逻辑可复用，代价是需要数据迁移和跨表事务设计。
