import pytest

from app.agent.evaluation.models import ResumeEvaluation
from app.core.exceptions import ModelOutputError
from app.core.prompt_loader import PromptLoader
from app.engineering.reliability.policy import RetryPolicy
from app.engineering.reliability.retry import AsyncRetryExecutor
from app.engineering.reliability.structured_output import StructuredOutputInvoker


class SequencedModel:
    def __init__(self, responses: list[object]) -> None:
        self._responses = responses
        self.messages: list[object] = []

    async def ainvoke(self, messages: object) -> object:
        self.messages = list(messages)  # type: ignore[arg-type]
        return self._responses.pop(0)


def executor() -> AsyncRetryExecutor:
    return AsyncRetryExecutor(RetryPolicy(
        max_attempts=5,
        initial_backoff_milliseconds=0,
        max_backoff_milliseconds=0,
        retryable_errors=frozenset({"TimeoutError"}),
        attempt_timeout_seconds=1,
        max_output_correction_attempts=2,
    ))


def valid_resume_json() -> str:
    return '''{
        "overallScore": 80, "contentScore": 80, "structureScore": 80,
        "skillMatchScore": 80, "expressionScore": 80, "projectScore": 80,
        "summary": "简历信息完整", "strengths": ["项目明确"],
        "suggestions": ["补充指标"], "issues": [],
        "technicalStack": ["Java"], "technicalDepth": ["项目实践"],
        "careerPreferences": ["后端开发"]
    }'''


@pytest.mark.asyncio
async def test_invalid_output_is_corrected_with_feedback() -> None:
    model = SequencedModel(['{"summary": "字段不完整"}', valid_resume_json()])
    invoker = StructuredOutputInvoker(PromptLoader(), executor())

    result = await invoker.invoke(
        model=model,
        schema=ResumeEvaluation,
        business_prompt="评估简历",
        input_payload={"resumeText": "Java 项目"},
    )

    assert result.overall_score == 80
    assert len(model._responses) == 0
    assert "上一轮输出未通过程序校验" in str(model.messages[-1].content)
    assert "contentScore" in str(model.messages[-1].content)


@pytest.mark.asyncio
async def test_invalid_output_returns_reason_after_two_corrections() -> None:
    model = SequencedModel(['{}', '{}', '{}'])
    invoker = StructuredOutputInvoker(PromptLoader(), executor())

    with pytest.raises(ModelOutputError) as error:
        await invoker.invoke(
            model=model,
            schema=ResumeEvaluation,
            business_prompt="评估简历",
            input_payload={"resumeText": "Java 项目"},
        )

    assert "连续 3 次" in error.value.message
    assert "contentScore" in error.value.message
