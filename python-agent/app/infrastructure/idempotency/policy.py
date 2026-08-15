"""会话内 runId 快照窗口配置。"""

import json
from dataclasses import dataclass
from pathlib import Path

from app.common.config import PROJECT_DIR
from app.common.exceptions import ReliabilityConfigurationError


@dataclass(frozen=True)
class IdempotencyPolicy:
    max_run_snapshots: int

    @classmethod
    def load(cls, path: Path | None = None) -> "IdempotencyPolicy":
        config_path = path or PROJECT_DIR / "resources" / "agent" / "idempotency.json"
        try:
            policy = cls(
                max_run_snapshots=int(
                    json.loads(config_path.read_text(encoding="utf-8"))["maxRunSnapshots"]
                )
            )
        except (FileNotFoundError, KeyError, ValueError, TypeError, json.JSONDecodeError) as error:
            raise ReliabilityConfigurationError("Agent 幂等策略配置无效") from error
        if policy.max_run_snapshots < 1:
            raise ReliabilityConfigurationError("runId 快照窗口必须大于 0")
        return policy
