from datetime import datetime, timezone

import pytest

from app.agent.schedule.agent import ScheduleParseAgent
from app.agent.schedule.models import ScheduleParseResult
from app.core.prompt_loader import PromptLoader


class _StructuredModel:
    def with_structured_output(self, schema):
        return self

    async def ainvoke(self, messages):
        return ScheduleParseResult(
            title="后端技术面试",
            startAt=datetime(2026, 8, 8, 2, 0, tzinfo=timezone.utc),
            endAt=datetime(2026, 8, 8, 3, 0, tzinfo=timezone.utc),
            timezone="Asia/Shanghai",
        )


class _NoTimeModel(_StructuredModel):
    async def ainvoke(self, messages):
        return ScheduleParseResult(title="技术面试", timezone="Asia/Shanghai")


@pytest.mark.asyncio
async def test_schedule_agent_returns_structured_result():
    result = await ScheduleParseAgent(_StructuredModel(), PromptLoader()).parse(
        "明天上午十点进行后端技术面试", "Asia/Shanghai"
    )
    assert result.title == "后端技术面试"
    assert result.start_at is not None
    assert result.end_at is not None
    assert result.timezone_name == "Asia/Shanghai"


@pytest.mark.asyncio
async def test_schedule_agent_completes_explicit_chinese_time():
    result = await ScheduleParseAgent(_NoTimeModel(), PromptLoader()).parse(
        "请安排明天下午三点的技术面试，预计一小时。", "Asia/Shanghai"
    )
    assert result.start_at is not None
    assert result.start_at.hour == 15
    assert result.end_at is not None
    assert (result.end_at - result.start_at).total_seconds() == 3600
