"""面向 OpenAI-compatible 模型的通用受控 JSON 输出能力。"""

import json
from collections.abc import Mapping
from typing import Any, Protocol, TypeVar

from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from pydantic import BaseModel, ValidationError

from app.common.exceptions import ModelOutputError
from app.common.prompt_loader import PromptLoader

from .retry import AsyncRetryExecutor


T = TypeVar("T", bound=BaseModel)


class RawChatModel(Protocol):
    async def ainvoke(self, input_value: object) -> object: ...


class StructuredOutputInvoker:
    """以提示词约束 + 本地校验替代供应商专有 Structured Outputs。"""

    def __init__(self, prompt_loader: PromptLoader, retry_executor: AsyncRetryExecutor | None) -> None:
        self._prompt_loader = prompt_loader
        self._retry_executor = retry_executor

    async def invoke(
        self,
        *,
        model: RawChatModel,
        schema: type[T],
        business_prompt: str,
        input_payload: Mapping[str, object],
    ) -> T:
        format_prompt = self._prompt_loader.render(
            "shared/structured-output.md",
            {
                "schema_json": json.dumps(schema.model_json_schema(by_alias=True), ensure_ascii=False),
                "few_shot_input": json.dumps({"task": "格式示例"}, ensure_ascii=False),
                "few_shot_output": json.dumps(_few_shot_output(schema), ensure_ascii=False),
            },
        )
        messages: list[object] = [
            SystemMessage(content=f"{business_prompt}\n\n{format_prompt}"),
            HumanMessage(content=json.dumps(input_payload, ensure_ascii=False, default=str)),
        ]
        max_corrections = self._retry_executor.max_output_correction_attempts if self._retry_executor else 0

        for correction_attempt in range(max_corrections + 1):
            raw_result = await self._invoke_model(model, messages)
            try:
                return self._validate(schema, raw_result)
            except (json.JSONDecodeError, ValidationError, TypeError, ValueError) as error:
                reason = _readable_validation_error(error)
                if correction_attempt == max_corrections:
                    raise ModelOutputError(
                        f"模型连续 {correction_attempt + 1} 次未返回符合 {schema.__name__} 的 JSON；"
                        f"最后一次原因：{reason}"
                    ) from error
                messages.extend([
                    AIMessage(content=_content_as_text(raw_result)),
                    HumanMessage(content=(
                        "你上一轮输出未通过程序校验。请只修复并返回完整 JSON，不能省略字段，"
                        f"不能输出解释或 Markdown。校验原因：{reason}"
                    )),
                ])
        raise AssertionError("unreachable")

    async def _invoke_model(self, model: RawChatModel, messages: object) -> object:
        if self._retry_executor is None:
            return await model.ainvoke(messages)
        return await self._retry_executor.execute(lambda: model.ainvoke(messages))

    def _validate(self, schema: type[T], raw_result: object) -> T:
        if isinstance(raw_result, schema):
            return raw_result
        content = _content_as_text(raw_result)
        payload = json.loads(_strip_json_fence(content))
        if not isinstance(payload, dict):
            raise TypeError("模型输出根节点必须是 JSON 对象")
        return schema.model_validate(payload)


def _content_as_text(raw_result: object) -> str:
    if isinstance(raw_result, str):
        return raw_result
    content = getattr(raw_result, "content", raw_result)
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        fragments: list[str] = []
        for item in content:
            if isinstance(item, str):
                fragments.append(item)
            elif isinstance(item, Mapping) and isinstance(item.get("text"), str):
                fragments.append(item["text"])
        if fragments:
            return "".join(fragments)
    if isinstance(content, Mapping):
        return json.dumps(content, ensure_ascii=False)
    raise TypeError("模型响应不包含可解析的文本内容")


def _strip_json_fence(content: str) -> str:
    text = content.strip()
    if text.startswith("```") and text.endswith("```"):
        lines = text.splitlines()
        text = "\n".join(lines[1:-1]).strip()
    return text


def _readable_validation_error(error: BaseException) -> str:
    if isinstance(error, ValidationError):
        fields = [".".join(str(part) for part in item["loc"]) for item in error.errors()]
        return "字段校验失败：" + ", ".join(fields[:8])
    message = str(error).strip().replace("\n", " ")
    return message[:500] or error.__class__.__name__


def _few_shot_output(schema: type[BaseModel]) -> dict[str, Any]:
    """为每个实际结构提供合法的最小示例，避免格式提示停留在抽象层。"""
    examples: dict[str, dict[str, Any]] = {
        "ResumeEvaluation": {
            "overallScore": 75, "contentScore": 76, "structureScore": 78,
            "skillMatchScore": 74, "expressionScore": 72, "projectScore": 75,
            "summary": "示例：经历与目标岗位基本匹配。", "strengths": ["项目描述具体"],
            "suggestions": ["补充量化结果"], "issues": [{"question": "请说明项目职责", "priority": "MEDIUM", "suggestion": "补充职责边界"}],
            "technicalStack": ["Java"], "technicalDepth": ["有项目实践"], "careerPreferences": ["后端开发"],
        },
        "InterviewPlan": {
            "candidate_summary": "示例候选人", "strategy_summary": "按阶段考察", "selectedSkills": ["java-backend"],
            "stages": [
                {"stage": "OPENING", "max_primary_questions": 1, "max_followups_per_question": 0, "difficulty": "MEDIUM", "topics": ["经历概述"], "time_budget_minutes": 3},
                {"stage": "PROJECT", "max_primary_questions": 2, "max_followups_per_question": 1, "difficulty": "MEDIUM", "topics": ["项目"], "time_budget_minutes": 10},
                {"stage": "FUNDAMENTAL", "max_primary_questions": 2, "max_followups_per_question": 1, "difficulty": "MEDIUM", "topics": ["基础"], "time_budget_minutes": 10},
                {"stage": "SCENARIO", "max_primary_questions": 1, "max_followups_per_question": 1, "difficulty": "MEDIUM", "topics": ["场景"], "time_budget_minutes": 6},
                {"stage": "CODING", "max_primary_questions": 1, "max_followups_per_question": 0, "difficulty": "MEDIUM", "topics": ["算法"], "time_budget_minutes": 8},
                {"stage": "SUMMARY", "max_primary_questions": 1, "max_followups_per_question": 0, "difficulty": "MEDIUM", "topics": ["总结"], "time_budget_minutes": 3},
            ],
        },
        "InterviewSkillSelection": {"selectedSkills": ["interview-coach"]},
        "InterviewEvaluation": {"evaluation_summary": "回答覆盖了核心概念。", "score": 75, "answer_summary": "说明了基本原理。", "strengths": ["概念正确"], "weaknesses": ["缺少细节"], "preferences": []},
        "InterviewRoute": {"action": "NEXT_QUESTION", "next_topic": "线程池参数设计"},
        "GeneratedQuestion": {"question": "请说明线程池核心参数及其作用。"},
        "InterviewSummary": {"overallScore": 76, "summary": "整体基础扎实，工程细节可继续加强。", "strengths": ["基础概念准确"], "weaknesses": ["场景分析不足"], "suggestions": ["补充项目指标与取舍"],},
    }
    if schema.__name__ == "CrawlPageDecision":
        return {"pageType": "CONTENT", "includeAsKnowledge": True,
                "expandLinks": False, "relevanceScore": 85,
                "reason": "rich technical content", "selectedLinks": []}
    return examples.get(schema.__name__, {})
