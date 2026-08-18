# POST /v1/agent/evaluate/resume：评估简历并写入记忆

## 1. 接口定义

该接口按简历文本、目标岗位和 subjectId 生成结构化简历评价。它先尝试读取同一 runId 与输入指纹的缓存，未命中才调用 `ResumeEvaluationAgent`；随后把摘要、问题、建议、技术栈和职业偏好写入长期记忆，并返回别名序列化后的评价。

| 项目 | 内容 |
|---|---|
| HTTP 方法 | POST |
| 路径 | `/v1/agent/evaluate/resume` |
| 路由函数 | `evaluate_resume` |
| 请求模型 | `AgentEvaluationRequest` |
| 文件 | `python-agent/app/api/application.py:159-206` |

## 2. 函数调用链

```text
evaluate_resume
 -> _remember_request_context
 -> _resume_evaluation_fingerprint
 -> _resolve_memory_service
    -> （冷启动）build_memory_service
       -> get_settings -> create_session_factory -> create_engine
       -> MemoryPolicy.load
 -> MemoryService.get_resume_evaluation_run
    -> PostgresLongTermMemoryRepository.get
 -> （缓存未命中）_resolve_resume_evaluator -> build_resume_evaluation_agent
    -> get_settings -> RetryPolicy.load -> LLMFactory.create_chat_model
    -> ResumeEvaluationAgent.evaluate
       -> SkillRegistry.get
       -> PromptLoader.render -> PromptLoader.load -> PromptLoader._resolve
       -> StructuredOutputInvoker.invoke
          -> _few_shot_output
          -> _invoke_model -> AsyncRetryExecutor.execute -> _is_retryable / _backoff_seconds
          -> _validate -> _content_as_text -> _strip_json_fence -> ResumeEvaluation.model_validate
          -> （格式失败）_readable_validation_error -> 有限纠错
 -> MemoryService.record_resume_analysis
    -> PostgresLongTermMemoryRepository.get
    -> _unique_items
    -> PostgresLongTermMemoryRepository.save
 -> （ConsistencyError）再次 get_resume_evaluation_run
    -> PostgresLongTermMemoryRepository.get
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

### 3.1 `evaluate_resume`

文件：`python-agent/app/api/application.py:159-206`

```python
    @app.post("/v1/agent/evaluate/resume", response_model=AgentResponse)
    async def evaluate_resume(payload: AgentEvaluationRequest, request: Request) -> AgentResponse:
        _remember_request_context(request, payload)
        fingerprint = _resume_evaluation_fingerprint(payload)
        memory_service = _resolve_memory_service(request)
        result = await memory_service.get_resume_evaluation_run(
            user_id=payload.user_id, resume_id=payload.subject_id,
            run_id=payload.run_id, evaluation_fingerprint=fingerprint,
        )
        if result is None:
            result = await _resolve_resume_evaluator(request).evaluate(
                subject_id=payload.subject_id,
                input_text=payload.input_text,
                target_role=payload.target_role,
            )
        try:
            await memory_service.record_resume_analysis(
                user_id=payload.user_id,
                resume_id=payload.subject_id,
                candidate_id=payload.candidate_id,
                resume_text=payload.input_text,
                target_role=payload.target_role,
                summary=result.summary,
                questions=[item.question for item in result.issues],
                priorities=[item.priority for item in result.issues],
                suggestions=[item.suggestion for item in result.issues] + result.suggestions,
                technical_stack=result.technical_stack,
                technical_depth=result.technical_depth,
                career_preferences=result.career_preferences,
                run_id=payload.run_id,
                evaluation_fingerprint=fingerprint,
                evaluation=result,
            )
        except ConsistencyError:
            replay = await memory_service.get_resume_evaluation_run(
                user_id=payload.user_id, resume_id=payload.subject_id,
                run_id=payload.run_id, evaluation_fingerprint=fingerprint,
            )
            if replay is None:
                raise
            result = replay
        return AgentResponse(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, code=100, status=RunStatus.COMPLETED,
            user_id=payload.user_id, session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE, state_version=0,
            answer=result.summary, output=result.model_dump(by_alias=True), error=None,
        )
```

逐行解释：

1. 第 159 行：注册 HTTP `POST /v1/agent/evaluate/resume`，响应模型为 `AgentResponse`。
2. 第 160 行：定义异步路由，接收已校验 `AgentEvaluationRequest` 和 FastAPI Request。
3. 第 161 行：调用 `_remember_request_context` 保存协议上下文。
4. 第 162 行：调用 `_resume_evaluation_fingerprint(payload)` 计算业务输入指纹。
5. 第 163 行：调用 `_resolve_memory_service` 取得或冷启动长期记忆服务。
6. 第 164 行：调用 `MemoryService.get_resume_evaluation_run` 查询 runId 快照。
7. 第 165 行：传入用户 ID 和作为 resumeId 使用的 subjectId。
8. 第 166 行：传入 runId 和当前输入指纹。
9. 第 167 行：结束查询，结果可能为 `ResumeEvaluation` 或 `None`。
10. 第 168 行：只有幂等快照未命中时才调用模型。
11. 第 169 行：调用 `_resolve_resume_evaluator(request)` 并继续调用 `ResumeEvaluationAgent.evaluate`。
12. 第 170 行：传入 subjectId。
13. 第 171 行：传入简历原文。
14. 第 172 行：传入目标岗位。
15. 第 173 行：得到结构化评价。
16. 第 174 行：进入长期记忆保存的并发保护。
17. 第 175 行：调用 `MemoryService.record_resume_analysis`。
18. 第 176 行：传入 userId。
19. 第 177 行：传入 resumeId。
20. 第 178 行：传入 candidateId。
21. 第 179 行：传入简历原文快照。
22. 第 180 行：传入目标岗位。
23. 第 181 行：传入评价摘要。
24. 第 182 行：从每个 issues 项提取 question 列表。
25. 第 183 行：提取 priority 列表。
26. 第 184 行：把每个 issue 的 suggestion 与评价总 suggestions 连接。
27. 第 185 行：传入技术栈。
28. 第 186 行：传入技术深度。
29. 第 187 行：传入职业偏好。
30. 第 188 行：传入 runId。
31. 第 189 行：传入评价指纹。
32. 第 190 行：传入完整结构化评价，供幂等快照保存。
33. 第 191 行：结束记忆写入。
34. 第 192 行：捕获长期记忆乐观锁或 runId 输入冲突。
35. 第 193 行：按完全相同条件再次调用 `get_resume_evaluation_run`。
36. 第 194 行：传入用户与简历 ID。
37. 第 195 行：传入 runId 与指纹。
38. 第 196 行：结束回读。
39. 第 197 行：检查并发请求是否没有留下可重放结果。
40. 第 198 行：没有结果时原样重新抛出 ConsistencyError。
41. 第 199 行：有结果时用持久化 replay 覆盖当前结果，保证并发响应一致。
42. 第 200 行：开始构造 `AgentResponse`。
43. 第 201 行：复制 apiVersion 与 requestId。
44. 第 202 行：复制 runId，成功 code 为 100，运行状态为 COMPLETED。
45. 第 203 行：复制 userId 和 sessionId。
46. 第 204 行：该独立评价不推进面试会话，协议状态固定 ACTIVE、版本固定 0。
47. 第 205 行：answer 返回摘要，output 按别名序列化完整评价，error 为 None。
48. 第 206 行：返回响应。

### 3.2 `_resume_evaluation_fingerprint`

文件：`python-agent/app/api/application.py:438-444`

```python
def _resume_evaluation_fingerprint(payload: AgentEvaluationRequest) -> str:
    canonical = json.dumps({
        "subjectId": payload.subject_id,
        "inputText": payload.input_text,
        "targetRole": payload.target_role,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()
```

逐行解释：

1. 第 438 行：定义评价输入指纹函数。
2. 第 439 行：开始构造确定性 JSON。
3. 第 440 行：写入 subjectId。
4. 第 441 行：写入完整 inputText。
5. 第 442 行：写入 targetRole。
6. 第 443 行：关闭字典，并以中文安全、键排序、紧凑分隔符序列化。
7. 第 444 行：UTF-8 编码后计算 SHA-256，返回十六进制指纹。

### 3.3 `ResumeEvaluationAgent.evaluate`

文件：`python-agent/app/agents/evaluation/agent.py:25-50`

```python
    async def evaluate(
        self,
        *,
        subject_id: str,
        input_text: str,
        target_role: str,
    ) -> ResumeEvaluation:
        normalized_text = input_text.strip()
        if not normalized_text:
            raise ValueError("待评价内容不能为空")

        skill = self._skill_registry.get("resume-analyst")
        system_prompt = self._prompt_loader.render(
            "resume/analysis.md", {"skill_instructions": skill.instructions}
        )
        payload = {
            "subjectId": subject_id,
            "targetRole": target_role,
            "resumeText": normalized_text,
        }
        return await self._structured_output.invoke(
            model=self._model,
            schema=ResumeEvaluation,
            business_prompt=system_prompt,
            input_payload=payload,
        )
```

逐行解释：

1. 第 25 行：定义异步评价函数。
2. 第 26 行：接收实例。
3. 第 27 行：强制业务参数具名传入。
4. 第 28 行：接收 subjectId。
5. 第 29 行：接收简历原文。
6. 第 30 行：接收目标岗位。
7. 第 31 行：声明返回 ResumeEvaluation。
8. 第 32 行：去除原文首尾空白。
9. 第 33 行：检查规范文本是否为空。
10. 第 34 行：为空时抛 ValueError。
11. 第 36 行：调用 SkillRegistry.get("resume-analyst")。
12. 第 37 行：调用 PromptLoader.render。
13. 第 38 行：加载 resume/analysis.md 并注入 Skill 指令。
14. 第 39 行：得到系统 Prompt。
15. 第 40 行：开始构造输入。
16. 第 41 行：放入 subjectId。
17. 第 42 行：放入 targetRole。
18. 第 43 行：放入清理后的 resumeText。
19. 第 44 行：结束输入。
20. 第 45 行：调用 StructuredOutputInvoker.invoke。
21. 第 46 行：传入模型。
22. 第 47 行：要求 ResumeEvaluation schema。
23. 第 48 行：传入系统 Prompt。
24. 第 49 行：传入输入字典。
25. 第 50 行：返回通过校验的评价。

### 3.4 `MemoryService.get_resume_evaluation_run`

文件：`python-agent/app/memory/service.py:236-248`

逐行解释：

1. 第 236 行：定义异步评价快照读取函数。
2. 第 237 行：强制传入 userId、resumeId、runId 和 evaluationFingerprint。
3. 第 238 行：结束签名；返回类型由运行对象决定，实际为 ResumeEvaluation 或 None。
4. 第 239 行：调用 PostgresLongTermMemoryRepository.get(userId)。
5. 第 240 行：检查用户长期记忆不存在。
6. 第 241 行：不存在时返回 None。
7. 第 242 行：按 runId 查询 resume_evaluation_runs。
8. 第 243 行：检查快照不存在。
9. 第 244 行：未命中返回 None。
10. 第 245 行：开始输入一致性判断。
11. 第 246 行：resumeId 或 fingerprint 任一不同即冲突。
12. 第 247 行：抛 ConsistencyError，禁止同一 runId 复用于不同输入。
13. 第 248 行：返回快照中的结构化 evaluation。

### 3.5 `MemoryService.record_resume_analysis`

文件：`python-agent/app/memory/service.py:177-234`

逐行解释：

1. 第 177 行：定义异步简历分析写入函数。
2. 第 178 行：接收实例以及 userId、resumeId、candidateId、resumeText。
3. 第 179 行：接收 targetRole、summary、questions、priorities。
4. 第 180 行：接收 suggestions、technicalStack、technicalDepth。
5. 第 181 行：接收 careerPreferences 和可选 runId。
6. 第 182 行：接收可选评价指纹。
7. 第 183 行：接收可选完整 ResumeEvaluation。
8. 第 184 行：声明返回 LongTermMemory 或 None。
9. 第 185 行：调用长期记忆仓储 get(userId)。
10. 第 186 行：检查用户记忆不存在。
11. 第 187 行：不存在时返回 None，不在评价接口隐式创建用户画像。
12. 第 188 行：runId 存在时读取已有评价运行，否则 None。
13. 第 189 行：检查已存在同 runId。
14. 第 190 行：开始比较已有 resumeId。
15. 第 191 行：以 or 比较指纹。
16. 第 192 行：任一不同抛 ConsistencyError。
17. 第 193 行：输入一致时直接返回当前记忆，不重复写入。
18. 第 194 行：检查待写简历是否仍是用户活动简历。
19. 第 195 行：不是活动版本时返回 None，阻止旧异步结果覆盖新简历。
20. 第 196 行：保存当前 stateVersion 作为乐观锁期望值。
21. 第 197 行：创建新的简历快照列表。
22. 第 198 行：初始化是否匹配目标简历标志。
23. 第 199 行：遍历已有简历快照。
24. 第 200 行：检查 resumeId 匹配。
25. 第 201 行：标记已找到。
26. 第 202 行：复制匹配快照并开始更新。
27. 第 203 行：写评价摘要。
28. 第 204 行：questions 最多保留前 20 项。
29. 第 205 行：priorities 最多 20 项。
30. 第 206 行：suggestions 最多 20 项。
31. 第 207 行：更新时间取 UTC。
32. 第 208 行：结束快照复制。
33. 第 209 行：把匹配后的或原样快照加入新列表。
34. 第 210 行：遍历结束后检查是否从未匹配。
35. 第 211 行：开始创建 ResumeMemory。
36. 第 212 行：写 resumeId、candidateId 和 targetRole。
37. 第 213 行：写 resumeText 和 summary。
38. 第 214 行：写前 20 个 questions 与 priorities。
39. 第 215 行：写前 20 个 suggestions。
40. 第 216 行：结束并追加新快照。
41. 第 217 行：按 MemoryPolicy.max_resume_snapshots 截断快照列表。
42. 第 218 行：注释说明同一简历重复评价采用替换而非累积。
43. 第 219 行：注释说明 activate_resume 已防止旧简历覆盖当前版本。
44. 第 220 行：调用 _unique_items 替换技术栈，最多 30 项。
45. 第 221 行：调用 _unique_items 替换技术深度。
46. 第 222 行：把建议去重后替换 notes。
47. 第 223 行：把职业偏好去重后替换 preferences。
48. 第 224 行：只有 runId、指纹和完整评价全部存在时保存运行快照。
49. 第 225 行：以 runId 为键构造 ResumeEvaluationRun。
50. 第 226 行：写 runId。
51. 第 227 行：写 resumeId。
52. 第 228 行：写 fingerprint。
53. 第 229 行：写结构化 evaluation。
54. 第 230 行：结束快照对象。
55. 第 231 行：当运行快照数量超过策略上限时循环清理。
56. 第 232 行：删除插入顺序最旧的运行快照。
57. 第 233 行：更新长期记忆 UTC 时间。
58. 第 234 行：调用 PostgresLongTermMemoryRepository.save(memory, expectedVersion) 并返回。

`MemoryService._unique_items` 文件：`python-agent/app/memory/service.py:274-277`

逐行解释：

1. 第 274 行：声明静态方法。
2. 第 275 行：定义有界字符串去重函数。
3. 第 276 行：过滤空项，并去除每项首尾空白。
4. 第 277 行：按首次出现顺序去重并保留前 limit 项。

### 3.6 `PostgresLongTermMemoryRepository.get` 与 `save`

文件：`python-agent/app/infrastructure/persistence/long_term_memory_repository.py:31-76`

`get` 逐行解释：

1. 第 31 行：定义按 userId 读取长期记忆函数。
2. 第 32 行：打开异步数据库会话。
3. 第 33 行：执行单标量查询。
4. 第 34 行：选择 LongTermMemoryEntity。
5. 第 35 行：WHERE 条件为 userId。
6. 第 36-37 行：结束查询和会话。
7. 第 38 行：实体存在时校验恢复 LongTermMemory，否则 None。

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
11. 第 62 行：stateVersion 必须等于 expectedVersion。
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
22. 第 74 行：抛 ConsistencyError。
23. 第 75 行：成功时提交。
24. 第 76 行：返回新版本记忆。

### 3.7 `_remember_request_context`

文件：`python-agent/app/api/application.py:391-394`

1. 第 391 行：定义请求上下文记录函数。
2. 第 392 行：兼容性读取 payload.model_dump。
3. 第 393 行：确认属性可调用。
4. 第 394 行：按别名和 JSON 模式导出并保存到 request.state.agent_context。

### 3.8 `_resolve_memory_service` 与 `build_memory_service`

`_resolve_memory_service` 文件：`python-agent/app/api/application.py:351-357`

逐行解释：

1. 第 351 行：定义记忆服务解析函数。
2. 第 352 行：从应用状态读取服务。
3. 第 353 行：检查未构造。
4. 第 354 行：函数内导入 build_memory_service，避免不使用该接口时提前加载依赖。
5. 第 355 行：调用 build_memory_service。
6. 第 356 行：写回应用状态。
7. 第 357 行：返回服务。

`build_memory_service` 文件：`python-agent/app/bootstrap.py:33-38`

1. 第 33 行：定义记忆服务工厂。
2. 第 34 行：选择显式配置或 get_settings。
3. 第 35 行：调用 create_session_factory。
4. 第 36 行：开始构造 MemoryService。
5. 第 37 行：注入 PostgresLongTermMemoryRepository，并调用 MemoryPolicy.load。
6. 第 38 行：返回服务。

### 3.9 `_resolve_resume_evaluator` 与 `build_resume_evaluation_agent`

`_resolve_resume_evaluator` 文件：`python-agent/app/api/application.py:343-348`

1. 第 343 行：定义评价 Agent 解析函数。
2. 第 344 行：从应用状态读取 evaluator。
3. 第 345 行：检查未构造。
4. 第 346 行：调用 build_resume_evaluation_agent。
5. 第 347 行：写回应用状态。
6. 第 348 行：返回 evaluator。

`build_resume_evaluation_agent` 文件：`python-agent/app/bootstrap.py:94-104`

逐行解释：

1. 第 94-96 行：定义接受可选 Settings 的评价 Agent 工厂。
2. 第 97 行：选择显式配置或 get_settings。
3. 第 98 行：调用 RetryPolicy.load 并构造 AsyncRetryExecutor。
4. 第 99 行：开始构造 ResumeEvaluationAgent。
5. 第 100 行：调用 LLMFactory.create_chat_model 注入模型。
6. 第 101 行：创建 PromptLoader。
7. 第 102 行：创建 SkillRegistry。
8. 第 103 行：注入统一重试器。
9. 第 104 行：返回 Agent。

### 3.10 `get_settings`、数据库工厂与 `MemoryPolicy.load`

`get_settings` 文件：`python-agent/app/common/config.py:47-51`

1. 第 47 行：以 LRU 最大 1 缓存配置。
2. 第 48 行：定义读取函数。
3. 第 49 行：说明返回进程级快照。
4. 第 51 行：实例化 Settings，从环境和 `.env` 读取并校验。

`create_engine` 文件：`python-agent/app/infrastructure/persistence/database.py:9-13`

1. 第 9 行：定义数据库引擎工厂。
2. 第 10 行：选择配置。
3. 第 11 行：检查 databaseUrl。
4. 第 12 行：缺失时抛 PersistenceConfigurationError。
5. 第 13 行：创建异步引擎并启用 poolPrePing。

`create_session_factory` 文件：`python-agent/app/infrastructure/persistence/database.py:16-19`

1. 第 16-18 行：定义异步会话工厂函数。
2. 第 19 行：调用 create_engine，并设置 expireOnCommit=False。

`MemoryPolicy.load` 文件：`python-agent/app/memory/policy.py:18-40`

逐行解释：

1. 第 18 行：声明类方法。
2. 第 19 行：定义加载函数。
3. 第 20 行：选择路径或 memory-policy.json。
4. 第 21 行：进入读取保护。
5. 第 22 行：解析 JSON。
6. 第 23 行：开始构造。
7. 第 24 行：读取短期轮次上限。
8. 第 25 行：读取摘要字符上限。
9. 第 26 行：读取简历快照上限。
10. 第 27 行：读取评价运行保留数。
11. 第 28 行：结束构造。
12. 第 29 行：捕获文件、字段、值和 JSON 错误。
13. 第 30 行：统一抛 WorkflowConfigurationError。
14. 第 32 行：短期轮次必须属于 3、4、5。
15. 第 33 行：失败抛错。
16. 第 34 行：摘要容量至少 200。
17. 第 35 行：失败抛错。
18. 第 36 行：快照上限至少 1。
19. 第 37 行：失败抛错。
20. 第 38 行：评价运行保留数必须为正。
21. 第 39 行：失败抛错。
22. 第 40 行：返回策略。

### 3.11 `LLMFactory.create_chat_model` 与 `RetryPolicy.load`

`LLMFactory.create_chat_model` 文件：`python-agent/app/agents/llm/factory.py:12-39`

逐行解释：

1. 第 12 行：声明静态方法。
2. 第 13 行：定义模型客户端工厂。
3. 第 14 行：选择显式配置或 get_settings。
4. 第 16 行：定义允许的 OpenAI-compatible provider 集合。
5. 第 17 行：检查配置 provider。
6. 第 18-20 行：不支持时抛包含名称的 ModelConfigurationError。
7. 第 21 行：检查 modelName。
8. 第 22 行：缺失时抛错。
9. 第 23 行：检查 API Key。
10. 第 24 行：缺失时抛错。
11. 第 26 行：开始参数字典。
12. 第 27 行：写模型名。
13. 第 28 行：写 API Key。
14. 第 29 行：写温度。
15. 第 30 行：写请求超时。
16. 第 31 行：注释说明重试由工程层负责。
17. 第 32 行：禁用 SDK 内重试。
18. 第 33 行：结束基础参数。
19. 第 34 行：检查 baseUrl。
20. 第 35 行：存在时写入。
21. 第 36 行：检查 maxTokens。
22. 第 37 行：存在时写入。
23. 第 39 行：构造并返回 ChatOpenAI，不在此处发请求。

`RetryPolicy.load` 文件：`python-agent/app/infrastructure/reliability/policy.py:20-45`

逐行解释：

1. 第 20 行：声明类方法。
2. 第 21 行：定义策略加载函数。
3. 第 22 行：选择路径或 reliability.json。
4. 第 23 行：进入读取保护。
5. 第 24 行：解析 JSON。
6. 第 25 行：开始构造策略。
7. 第 26 行：读取最大尝试次数。
8. 第 27 行：读取初始退避。
9. 第 28 行：读取最大退避。
10. 第 29 行：构造可重试异常冻结集合。
11. 第 30 行：读取单次超时，默认 120。
12. 第 31 行：读取输出纠错次数，默认 2。
13. 第 32 行：结束构造。
14. 第 33 行：捕获文件、字段、值、类型和 JSON 错误。
15. 第 34 行：统一抛 ReliabilityConfigurationError。
16. 第 35 行：验证尝试次数 1~5 且初始退避非负。
17. 第 36 行：失败抛错。
18. 第 37 行：验证最大退避不小于初始值。
19. 第 38 行：失败抛错。
20. 第 39 行：验证单次超时大于 0 且不超过 120。
21. 第 40 行：失败抛错。
22. 第 41 行：验证纠错次数 0~2。
23. 第 42 行：失败抛错。
24. 第 43 行：验证可重试集合非空。
25. 第 44 行：为空抛错。
26. 第 45 行：返回策略。

### 3.12 `SkillRegistry.get`

文件：`python-agent/app/tools/skills/loader.py:47-84`

逐行解释：

1. 第 47 行：定义按 ID 加载 Skill 的函数。
2. 第 48-50 行：要求 ID 为字符串且完全符合小写字母数字和单连字符格式。
3. 第 51 行：格式错误抛 SkillConfigurationError。
4. 第 52 行：构造 Skill 目录。
5. 第 53 行：构造 skill.json 路径。
6. 第 54 行：构造 SKILL.md 路径。
7. 第 55 行：进入读取保护。
8. 第 56 行：读取并解析元数据。
9. 第 57 行：读取指令文本。
10. 第 58 行：捕获文件不存在。
11. 第 59 行：转换为配置错误。
12. 第 60 行：捕获 JSON 损坏。
13. 第 61 行：转换为格式错误。
14. 第 63 行：检查 enabled，缺失默认 true。
15. 第 64 行：禁用时抛错。
16. 第 65 行：检查元数据 ID 与目录 ID。
17. 第 66 行：不一致抛错。
18. 第 67 行：读取 allowedTools。
19. 第 68-70 行：要求数组中每项为非空字符串。
20. 第 71 行：格式错误抛错。
21. 第 72 行：计算未支持工具差集。
22. 第 73 行：检查差集非空。
23. 第 74 行：排序并连接名称。
24. 第 75-77 行：抛包含 Skill 和工具名的配置错误。
25. 第 78 行：开始构造 SkillDefinition。
26. 第 79-82 行：写 ID、名称、描述和指令。
27. 第 83 行：工具按顺序去重并转元组。
28. 第 84 行：返回定义。

### 3.13 `PromptLoader.render`、`load` 与 `_resolve`

文件：`python-agent/app/common/prompt_loader.py:19-46`

`render` 逐行解释：

1. 第 26 行：定义渲染函数。
2. 第 27 行：调用 load 读取模板。
3. 第 29 行：定义闭包 replace。
4. 第 30 行：读取占位符键。
5. 第 31 行：检查变量字典。
6. 第 32-34 行：缺失时抛包含 Prompt ID 和键的配置错误。
7. 第 35 行：存在时转字符串。
8. 第 37 行：替换全部占位符。
9. 第 38 行：再次检查残留。
10. 第 39 行：残留时抛错。
11. 第 40 行：返回渲染文本。

`load` 逐行解释：

1. 第 19 行：定义加载函数。
2. 第 20 行：调用 _resolve。
3. 第 21 行：进入读取保护。
4. 第 22 行：UTF-8 读取。
5. 第 23 行：捕获文件不存在。
6. 第 24 行：转换为 PromptConfigurationError。

`_resolve` 逐行解释：

1. 第 42 行：定义安全路径解析。
2. 第 43 行：拼接根目录后 resolve。
3. 第 44 行：检查根目录仍是父目录。
4. 第 45 行：越界抛错。
5. 第 46 行：返回路径。

### 3.14 `StructuredOutputInvoker.invoke` 及辅助函数

文件：`python-agent/app/infrastructure/reliability/structured_output.py:30-154`

`invoke`（第 30-70 行）逐行解释：

1. 第 30-37 行：定义通用结构化调用函数，输入模型、schema、业务 Prompt 和映射输入。
2. 第 38 行：调用 PromptLoader.render 生成格式约束。
3. 第 39 行：加载 shared/structured-output.md。
4. 第 40 行：开始变量字典。
5. 第 41 行：序列化 schema JSON。
6. 第 42 行：加入固定 few-shot 输入。
7. 第 43 行：调用 _few_shot_output 并序列化示例。
8. 第 44-45 行：结束格式 Prompt。
9. 第 46 行：创建消息列表。
10. 第 47 行：业务 Prompt 和格式约束组成系统消息。
11. 第 48 行：业务输入序列化为用户消息。
12. 第 49 行：结束消息列表。
13. 第 50 行：读取最大输出纠错次数。
14. 第 52 行：遍历初次和纠错尝试。
15. 第 53 行：调用 _invoke_model。
16. 第 54 行：进入校验保护。
17. 第 55 行：调用 _validate，成功返回。
18. 第 56 行：捕获 JSON、Pydantic、类型和值错误。
19. 第 57 行：调用 _readable_validation_error。
20. 第 58 行：检查次数耗尽。
21. 第 59 行：构造 ModelOutputError。
22. 第 60-61 行：消息包含次数、schema 和最后原因。
23. 第 62 行：从原错误抛出。
24. 第 63 行：仍可纠错时扩展消息。
25. 第 64 行：调用 _content_as_text 保存上一轮错误输出。
26. 第 65 行：开始纠错用户消息。
27. 第 66-67 行：要求只返回完整 JSON，并附校验原因。
28. 第 68-69 行：结束扩展。
29. 第 70 行：理论不可达保护。

`_invoke_model`（`python-agent/app/infrastructure/reliability/structured_output.py:72-75`）逐行解释：

1. 第 72 行：定义原始模型调用。
2. 第 73 行：检查无重试器。
3. 第 74 行：无重试时直接 ainvoke。
4. 第 75 行：有重试时调用 AsyncRetryExecutor.execute。

`_validate`（`python-agent/app/infrastructure/reliability/structured_output.py:77-84`）逐行解释：

1. 第 77 行：定义结构验证。
2. 第 78 行：原结果已是 schema 实例时命中。
3. 第 79 行：直接返回。
4. 第 80 行：调用 _content_as_text。
5. 第 81 行：清理围栏并解析 JSON。
6. 第 82 行：检查根节点字典。
7. 第 83 行：否则抛 TypeError。
8. 第 84 行：调用 schema.model_validate。

`_content_as_text`（`python-agent/app/infrastructure/reliability/structured_output.py:87-104`）逐行解释：

1. 第 87 行：定义文本提取。
2. 第 88-89 行：原结果为字符串时返回。
3. 第 90 行：读取 content 属性或原对象。
4. 第 91-92 行：content 为字符串时返回。
5. 第 93 行：列表时进入块提取。
6. 第 94 行：创建片段列表。
7. 第 95 行：遍历块。
8. 第 96-97 行：字符串块直接加入。
9. 第 98-99 行：映射块 text 为字符串时加入。
10. 第 100-101 行：有片段时连接返回。
11. 第 102-103 行：content 为映射时序列化返回。
12. 第 104 行：其他形态抛 TypeError。

`_strip_json_fence`（`python-agent/app/infrastructure/reliability/structured_output.py:107-112`）逐行解释：

1. 第 107 行：定义围栏清理。
2. 第 108 行：去首尾空白。
3. 第 109 行：检查三反引号包围。
4. 第 110 行：按行拆分。
5. 第 111 行：去围栏行并连接。
6. 第 112 行：返回文本。

`_readable_validation_error`（`python-agent/app/infrastructure/reliability/structured_output.py:115-120`）逐行解释：

1. 第 115 行：定义可读错误。
2. 第 116 行：识别 ValidationError。
3. 第 117 行：提取字段路径。
4. 第 118 行：最多前 8 项。
5. 第 119 行：其他错误转单行字符串。
6. 第 120 行：最多 500 字符，空时类名。

`_few_shot_output`（`python-agent/app/infrastructure/reliability/structured_output.py:123-154`）逐行解释：

1. 第 123 行：定义 schema 示例函数。
2. 第 124 行：说明提供实际合法示例。
3. 第 125-149 行：建立 schema 示例映射；本接口使用 ResumeEvaluation 项。
4. 第 150-153 行：爬取判断专用示例，本接口不触发。
5. 第 154 行：按 schema 名返回，未知时空字典。

### 3.15 `AsyncRetryExecutor.execute`

文件：`python-agent/app/infrastructure/reliability/retry.py:23-50`

逐行解释：

1. 第 23 行：定义异步重试函数。
2. 第 24 行：尝试编号从 1 到最大次数。
3. 第 25 行：进入单次保护。
4. 第 26-27 行：注释说明超时取消协程。
5. 第 28 行：调用 asyncio.wait_for。
6. 第 29 行：创建操作协程并用单次超时。
7. 第 30 行：成功返回。
8. 第 31 行：捕获异常。
9. 第 32 行：不可重试或最后一次时停止。
10. 第 33 行：区分可重试次数耗尽。
11. 第 34-37 行：构造可重试 AgentDependencyError 并链接原异常。
12. 第 38 行：不可重试错误原样抛出。
13. 第 39 行：调用 _backoff_seconds 并异步等待。
14. 第 40 行：理论不可达保护。
15. 第 42 行：定义 _is_retryable。
16. 第 43 行：按异常类名匹配策略集合。
17. 第 45 行：定义退避计算。
18. 第 46-49 行：取最大退避与指数退避较小值。
19. 第 50 行：毫秒转秒。

### 3.16 `AgentResponse.validate_code_category`

文件：`python-agent/app/common/contracts.py:177-182`

逐行解释：

1. 第 177 行：注册 code 字段校验器。
2. 第 178 行：声明类方法。
3. 第 179 行：定义业务码校验。
4. 第 180 行：要求首位类别属于 1~5。
5. 第 181 行：不满足抛 ValueError。
6. 第 182 行：返回合法值。

### 3.17 FastAPI 异常入口

`request_validation_error` 文件：`python-agent/app/api/application.py:292-299`

1. 第 292 行：注册请求模型校验错误处理器。
2. 第 293 行：定义异步函数。
3. 第 294 行：读取原始错误 body。
4. 第 295 行：body 为映射时作为上下文。
5. 第 296 行：调用 _error_json_response。
6. 第 297 行：转换 RequestError 并使用 HTTP 400。
7. 第 298 行：传入上下文。
8. 第 299 行：返回响应。

`application_error` 文件：`python-agent/app/api/application.py:301-304`

1. 第 301 行：注册 ApplicationException 处理器。
2. 第 302 行：定义异步函数。
3. 第 303 行：调用 _mark_failed_interview_progress；本接口路径会立即返回。
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
3. 第 325 行：本评价接口立即返回。
4. 第 326 行：仅 respond 才恢复上下文。
5. 第 327 行：仅 respond 才读取 sessionId。
6. 第 328 行：仅 respond 才读取服务。
7. 第 329 行：兼容读取 markProgressFailed。
8. 第 330 行：检查参数与方法。
9. 第 331 行：满足时标失败；本接口不执行。

### 3.18 统一错误响应辅助函数

`_request_context` 文件：`python-agent/app/api/application.py:379-388`

1. 第 379 行：定义异步上下文恢复。
2. 第 380 行：读取已记住上下文。
3. 第 381 行：检查映射。
4. 第 382 行：是映射直接返回。
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
11. 第 409 行：版本必须为非负整数，否则 0。
12. 第 410 行：answer None、currentStage FAILED，并调用 to_error_info。
13. 第 411 行：返回。

`_string_or_none` 文件：`python-agent/app/api/application.py:414-415`

1. 第 414 行：定义字符串清洗。
2. 第 415 行：非空字符串返回，否则 None。

`_session_status_or_failed` 文件：`python-agent/app/api/application.py:418-423`

1. 第 418 行：定义状态转换。
2. 第 419 行：说明运行失败不能误改现有会话。
3. 第 420 行：进入转换保护。
4. 第 421 行：构造 SessionStatus。
5. 第 422 行：捕获类型和值错误。
6. 第 423 行：失败回退 FAILED。

`ExceptionHandler.to_code` 文件：`python-agent/app/common/exceptions.py:139-146`

1. 第 139 行：声明类方法。
2. 第 140 行：定义 code 转换。
3. 第 141-142 行：项目异常返回自带 code。
4. 第 143-145 行：遍历内置映射并按类型返回 code。
5. 第 146 行：未知返回 500。

`ExceptionHandler.to_error_info` 文件：`python-agent/app/common/exceptions.py:116-137`

1. 第 116 行：声明类方法。
2. 第 117 行：定义错误详情转换。
3. 第 118 行：识别项目异常。
4. 第 119-123 行：构造并返回项目 errorType、message、retryable。
5. 第 125 行：遍历内置映射。
6. 第 126 行：按类型匹配。
7. 第 127-131 行：构造并返回内置映射错误。
8. 第 133 行：开始未知错误。
9. 第 134 行：类型 INTERNAL_ERROR。
10. 第 135 行：使用固定外部消息。
11. 第 136 行：不可重试。
12. 第 137 行：返回。

`_error_json_response` 文件：`python-agent/app/api/application.py:447-455`

1. 第 447-453 行：定义 JSON 包装函数。
2. 第 454 行：调用 _error_response。
3. 第 455 行：调用 AgentResponse.to_json_dict 并返回 JSONResponse。

`AgentResponse.to_json_dict` 文件：`python-agent/app/common/contracts.py:184-185`

1. 第 184 行：定义 JSON 字典转换。
2. 第 185 行：以 JSON 模式、字段别名并保留 null 导出。

## 4. 主流构建分析

主流简历评价服务通常采用异步任务与结果版本化：API 先保存输入快照和内容哈希，返回 evaluationRunId；Worker 调模型并把结构化结果、模型版本、Prompt 版本和审计元数据写入独立评价表，查询接口按 runId 返回状态与结果。优点是长耗时模型调用不会占用同步请求、结果可复现和对比、失败可重试；缺点是需要任务表或消息系统、状态轮询/SSE 以及输入与结果保留策略。

本项目已有 runId、输入指纹、结构化输出、本地重试和长期记忆乐观锁，同步调用在当前规模下实现简单且可接受。不过评价结果目前嵌入长期记忆 JSON，历史版本和模型审计能力有限。若采用主流方式，可新增 `resume_evaluation_runs` 表保存 userId、resumeId、runId、fingerprint、status、model、promptVersion、result、error 和时间；RabbitMQ 或 Python Worker 消费待评价任务，完成后再以 Outbox/事务更新长期记忆。该方案适合评价量增长或需要报告历史对比时，代价是引入最终一致性和任务清理机制。
