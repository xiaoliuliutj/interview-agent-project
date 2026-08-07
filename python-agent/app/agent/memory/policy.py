"""从外部配置读取记忆窗口和摘要边界。"""

import json
from dataclasses import dataclass
from pathlib import Path

from app.core.config import PROJECT_DIR
from app.core.exceptions import WorkflowConfigurationError


@dataclass(frozen=True)
class MemoryPolicy:
    short_term_turn_limit: int
    history_summary_max_characters: int
    max_resume_snapshots: int

    @classmethod
    def load(cls, path: Path | None = None) -> "MemoryPolicy":
        config_path = path or PROJECT_DIR / "config" / "agent" / "memory-policy.json"
        try:
            raw = json.loads(config_path.read_text(encoding="utf-8"))
            policy = cls(
                short_term_turn_limit=int(raw["shortTermTurnLimit"]),
                history_summary_max_characters=int(raw["historySummaryMaxCharacters"]),
                max_resume_snapshots=int(raw["maxResumeSnapshots"]),
            )
        except (FileNotFoundError, KeyError, ValueError, json.JSONDecodeError) as error:
            raise WorkflowConfigurationError("记忆策略配置无效") from error

        if policy.short_term_turn_limit not in {3, 4, 5}:
            raise WorkflowConfigurationError("短期记忆轮数必须为 3 到 5")
        if policy.history_summary_max_characters < 200:
            raise WorkflowConfigurationError("长期摘要容量不能小于 200 字符")
        if policy.max_resume_snapshots < 1:
            raise WorkflowConfigurationError("至少保留一份简历快照")
        return policy
