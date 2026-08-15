# Python 下层：模型输出与重试

## 目标

兼容 OpenAI 协议的模型服务并不一定支持原生 Structured Outputs。下层因此不能只依赖 SDK 的 `parse` 方法，而要自行保证模型结果可解析、可校验、可失败收敛。

## 实现

- `StructuredOutputInvoker` 接收业务 Prompt、Pydantic Schema 和真实输入。
- 它加载共享 Prompt 模板，注入 JSON Schema 与每个结果模型对应的合法 few-shot 输出示例。
- 模型只被要求返回一个 JSON 对象；下层去掉可选 JSON Markdown 围栏后执行 `json.loads` 与 `model_validate`。
- 解析或字段校验失败时，下层把具体缺失字段或格式原因追加到同一模型对话，请模型修复完整 JSON。最多修复 2 次。
- 连续 3 次输出仍不合约时，抛出不可重试的 `MODEL_OUTPUT_INVALID`（业务码 502），上层可直接把原因持久化并展示。

## 调用上限

`config/agent/reliability.json` 是唯一配置来源：网络、限流和超时类调用最多总尝试 5 次；每次物理调用由 `asyncio.wait_for` 限制为 120 秒。聊天模型与 RAG Embedding 均使用此执行器。格式修复不等同于网络重试，不能交给消息队列无意义重放。

## 已覆盖的节点

简历评估、面试规划、回答评分、流程路由、题目生成、面试总结均经过该组件。测试覆盖首次格式错误后修复成功，以及两次修复仍失败时携带字段原因返回的场景。
