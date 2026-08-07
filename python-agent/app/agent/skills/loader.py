"""从外部文件加载 Skill 元数据和说明。"""

import json
from dataclasses import dataclass
from pathlib import Path

from app.core.config import PROJECT_DIR
from app.core.exceptions import SkillConfigurationError


@dataclass(frozen=True)
class SkillDefinition:
    skill_id: str
    name: str
    description: str
    instructions: str
    allowed_tools: tuple[str, ...]


class SkillRegistry:
    def __init__(self, root: Path | None = None) -> None:
        self._root = root or PROJECT_DIR / "config" / "skills"

    def get(self, skill_id: str) -> SkillDefinition:
        skill_dir = self._root / skill_id
        metadata_path = skill_dir / "skill.json"
        instruction_path = skill_dir / "SKILL.md"
        try:
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            instructions = instruction_path.read_text(encoding="utf-8")
        except FileNotFoundError as error:
            raise SkillConfigurationError(f"Skill 文件不存在: {skill_id}") from error
        except json.JSONDecodeError as error:
            raise SkillConfigurationError(f"Skill 元数据格式错误: {skill_id}") from error

        if not metadata.get("enabled", True):
            raise SkillConfigurationError(f"Skill 未启用: {skill_id}")
        if metadata.get("id") != skill_id:
            raise SkillConfigurationError(f"Skill ID 不匹配: {skill_id}")
        return SkillDefinition(
            skill_id=skill_id,
            name=str(metadata["name"]),
            description=str(metadata["description"]),
            instructions=instructions,
            allowed_tools=tuple(metadata.get("allowedTools", [])),
        )

    def public_catalog(self) -> list[dict[str, object]]:
        """读取供上层展示的 Skill 目录；不暴露 SKILL.md 的内部指令。"""
        catalog_path = self._root / "catalog.json"
        try:
            catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
        except FileNotFoundError as error:
            raise SkillConfigurationError("Skill 展示目录不存在") from error
        except json.JSONDecodeError as error:
            raise SkillConfigurationError("Skill 展示目录格式错误") from error
        if not isinstance(catalog, list):
            raise SkillConfigurationError("Skill 展示目录必须是数组")
        return [self._validate_public_item(item) for item in catalog]

    def categories_for_jd(self, jd_text: str) -> list[dict[str, object]]:
        """用外置关键词从 JD 提取展示分类；这是确定性预处理，不调用模型。"""
        normalized = jd_text.strip().lower()
        if not normalized:
            return []
        catalog = self.public_catalog()
        candidates = [
            category
            for item in catalog
            for category in item["categories"]
            if isinstance(category, dict)
        ]
        matched = [
            category
            for category in candidates
            if any(
                str(keyword).lower() in normalized
                for keyword in category.get("keywords", [])
            )
        ]
        selected = matched or candidates
        return [
            {
                "key": category["key"],
                "label": category["label"],
                "priority": category["priority"],
                "ref": category.get("ref"),
                "shared": category.get("shared", False),
            }
            for category in selected
        ]

    @staticmethod
    def _validate_public_item(item: object) -> dict[str, object]:
        if not isinstance(item, dict):
            raise SkillConfigurationError("Skill 展示项必须是对象")
        required = {"id", "name", "description", "categories", "isPreset"}
        if not required.issubset(item):
            raise SkillConfigurationError("Skill 展示项缺少必要字段")
        if not isinstance(item["categories"], list):
            raise SkillConfigurationError("Skill 分类必须是数组")
        return dict(item)
