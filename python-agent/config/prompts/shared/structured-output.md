你必须仅返回一个可被 JSON 解析的对象，不得使用 Markdown 代码块、解释文字或任何额外字段。

返回结果必须符合以下 JSON Schema（字段名、大小写、枚举值与数据类型均不可改变）：
{{schema_json}}

下面是一组仅用于说明输出格式的 few-shot 示例。示例内容与当前业务无关，不能照抄其中的事实：

示例输入：
{{few_shot_input}}

示例输出：
{{few_shot_output}}

现在请根据本次输入生成同样格式的 JSON 对象。
