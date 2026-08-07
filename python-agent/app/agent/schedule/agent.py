"""基于外置 Prompt 的日程结构化抽取。"""

import json
import re
from datetime import datetime, timedelta
from typing import Protocol
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from langchain_core.messages import HumanMessage, SystemMessage

from app.core.prompt_loader import PromptLoader
from app.engineering.reliability.retry import AsyncRetryExecutor

from .models import ScheduleParseResult


class StructuredChatModel(Protocol):
    def with_structured_output(self, schema: type[object]) -> "StructuredChatModel": ...

    async def ainvoke(self, input_value: object) -> object: ...


class ScheduleParseAgent:
    def __init__(
        self,
        model: StructuredChatModel,
        prompt_loader: PromptLoader,
        retry_executor: AsyncRetryExecutor | None = None,
    ) -> None:
        self._model = model
        self._prompt_loader = prompt_loader
        self._retry_executor = retry_executor

    async def parse(self, text: str, timezone_name: str) -> ScheduleParseResult:
        normalized = text.strip()
        if not normalized:
            raise ValueError("日程文本不能为空")
        try:
            local_timezone = ZoneInfo(timezone_name)
        except ZoneInfoNotFoundError as error:
            raise ValueError("用户时区不是有效的 IANA 时区") from error
        system_prompt = self._prompt_loader.render(
            "schedule/parse.md",
            {
                "current_time": datetime.now(local_timezone).isoformat(),
                "timezone": timezone_name,
            },
        )
        parser = self._model.with_structured_output(ScheduleParseResult)
        messages = [
            SystemMessage(content=system_prompt),
            HumanMessage(content=json.dumps({"rawText": normalized}, ensure_ascii=False)),
        ]
        result = (
            await self._retry_executor.execute(lambda: parser.ainvoke(messages))
            if self._retry_executor is not None
            else await parser.ainvoke(messages)
        )
        if not isinstance(result, ScheduleParseResult):
            raise TypeError("模型未返回日程解析结构")
        return self._complete_explicit_time(result, normalized, local_timezone)

    def _complete_explicit_time(
        self, result: ScheduleParseResult, text: str, local_timezone: ZoneInfo
    ) -> ScheduleParseResult:
        """只把文本中明确的中文日期/时刻/时长转成时间，未知信息保持 null。"""

        day_match = re.search(r"(今天|明天|后天)", text)
        time_match = re.search(
            r"(上午|早上|中午|下午|晚上|凌晨)?\s*([0-9]{1,2}|[一二三四五六七八九十两零〇]+)\s*(?:点|时)(?:\s*([0-9]{1,2})\s*分?)?",
            text,
        )
        if day_match is None or time_match is None:
            return result

        day_offset = {"今天": 0, "明天": 1, "后天": 2}[day_match.group(1)]
        hour = self._chinese_number(time_match.group(2))
        minute = int(time_match.group(3) or 0)
        period = time_match.group(1) or ""
        if period in {"下午", "晚上"} and hour < 12:
            hour += 12
        elif period == "中午" and hour < 11:
            hour += 12
        if hour > 23 or minute > 59:
            return result
        local_date = datetime.now(local_timezone).date()
        start = datetime.combine(
            local_date + timedelta(days=day_offset),
            datetime.min.time().replace(hour=hour, minute=minute),
            tzinfo=local_timezone,
        )
        duration_match = re.search(
            r"(?:预计|时长|持续)?\s*([0-9]+(?:\.[0-9]+)?|[一二三四五六七八九十两]+)\s*(小时|分钟|h|min)",
            text,
            re.I,
        )
        end = result.end_at
        if end is None and duration_match is not None:
            raw_amount = duration_match.group(1)
            amount = float(raw_amount) if raw_amount[0].isdigit() else float(self._chinese_number(raw_amount))
            minutes = round(amount * 60) if duration_match.group(2).lower() in {"小时", "h"} else round(amount)
            end = start + timedelta(minutes=minutes)
        model_start = self._attach_timezone(result.start_at, local_timezone)
        model_end = self._attach_timezone(end, local_timezone)
        return result.model_copy(update={"start_at": model_start or start, "end_at": model_end})

    def _attach_timezone(self, value: datetime | None, local_timezone: ZoneInfo) -> datetime | None:
        if value is None:
            return None
        return value if value.tzinfo is not None else value.replace(tzinfo=local_timezone)

    def _chinese_number(self, value: str) -> int:
        if value.isdigit():
            return int(value)
        values = {"零": 0, "〇": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4,
                  "五": 5, "六": 6, "七": 7, "八": 8, "九": 9}
        if value == "十":
            return 10
        if "十" in value:
            left, _, right = value.partition("十")
            return (values.get(left, 1) if left else 1) * 10 + (values.get(right, 0) if right else 0)
        return values.get(value, -1)
