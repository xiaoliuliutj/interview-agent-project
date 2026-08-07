"""日程解析的模型输出定义。"""

from datetime import datetime

from pydantic import BaseModel, ConfigDict, Field


class ScheduleParseResult(BaseModel):
    """模型只能抽取明确表达的信息；无法确认的时间字段必须为 null。"""

    model_config = ConfigDict(populate_by_name=True, extra="forbid")

    title: str = Field(min_length=1, max_length=255)
    start_at: datetime | None = Field(default=None, alias="startAt")
    end_at: datetime | None = Field(default=None, alias="endAt")
    timezone_name: str = Field(alias="timezone", min_length=1)
