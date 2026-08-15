"""从外部工作流配置加载六阶段定义和固定开场消息。"""

import json
from dataclasses import dataclass
from pathlib import Path

from app.common.config import PROJECT_DIR
from app.common.exceptions import WorkflowConfigurationError
from app.common.prompt_loader import PromptLoader

from .models import InterviewStage


@dataclass(frozen=True)
class InterviewWorkflow:
    stages: tuple[InterviewStage, ...]
    opening_prompt: str

    @classmethod
    def load(
        cls,
        prompt_loader: PromptLoader,
        path: Path | None = None,
    ) -> "InterviewWorkflow":
        config_path = path or PROJECT_DIR / "resources" / "agent" / "interview-workflow.json"
        try:
            raw = json.loads(config_path.read_text(encoding="utf-8"))
            stages = tuple(InterviewStage(item) for item in raw["stages"])
            workflow = cls(stages=stages, opening_prompt=str(raw["openingPrompt"]))
        except (FileNotFoundError, KeyError, ValueError, json.JSONDecodeError) as error:
            raise WorkflowConfigurationError("面试工作流配置无效") from error

        if list(workflow.stages) != list(InterviewStage):
            raise WorkflowConfigurationError("工作流必须按六个固定阶段完整配置")
        prompt_loader.load(workflow.opening_prompt)
        return workflow

    def opening_message(self, prompt_loader: PromptLoader, target_role: str) -> str:
        return prompt_loader.render(self.opening_prompt, {"target_role": target_role})
