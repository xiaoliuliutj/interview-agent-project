"""外部 Prompt 文件加载与受控变量渲染。"""

import re
from pathlib import Path
from typing import Mapping

from .config import PROJECT_DIR
from .exceptions import PromptConfigurationError


class PromptLoader:
    """从 resources/prompts 读取 Prompt，禁止在业务代码中维护可修改模板。"""

    _placeholder_pattern = re.compile(r"{{\s*([a-zA-Z_][a-zA-Z0-9_]*)\s*}}")

    def __init__(self, root: Path | None = None) -> None:
        self._root = root or PROJECT_DIR / "resources" / "prompts"

    def load(self, prompt_id: str) -> str:
        path = self._resolve(prompt_id)
        try:
            return path.read_text(encoding="utf-8")
        except FileNotFoundError as error:
            raise PromptConfigurationError(f"Prompt 文件不存在: {prompt_id}") from error

    def render(self, prompt_id: str, variables: Mapping[str, object]) -> str:
        template = self.load(prompt_id)

        def replace(match: re.Match[str]) -> str:
            key = match.group(1)
            if key not in variables:
                raise PromptConfigurationError(
                    f"Prompt {prompt_id} 缺少变量: {key}"
                )
            return str(variables[key])

        rendered = self._placeholder_pattern.sub(replace, template)
        if self._placeholder_pattern.search(rendered):
            raise PromptConfigurationError(f"Prompt {prompt_id} 存在未替换变量")
        return rendered

    def _resolve(self, prompt_id: str) -> Path:
        path = (self._root / prompt_id).resolve()
        if self._root.resolve() not in path.parents:
            raise PromptConfigurationError("Prompt 路径不允许越过配置目录")
        return path
