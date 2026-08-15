"""从外部文件加载 Skill 元数据和说明。"""

import json
import logging
import re
from dataclasses import dataclass
from pathlib import Path

from app.common.config import PROJECT_DIR
from app.common.exceptions import SkillConfigurationError


logger = logging.getLogger(__name__)

# Skills may only declare tools that the interview runtime can actually provide.
# Keep this list close to the registry validation so metadata cannot advertise a
# capability merely because a prompt happens to mention it.
SUPPORTED_INTERVIEW_TOOLS = frozenset({"rag.search"})

# Business directions come from Java as user intent, not as Skill IDs.  This
# registry-owned map only prepares eligible internal candidates; the planning
# model still makes the final allow-list selection.  More variants can be
# appended here without changing the Java/frontend contract.
DIRECTION_SKILL_CANDIDATES: dict[str, tuple[str, ...]] = {
    "java-backend": ("java-backend",),
    "python-backend": ("python-backend",),
    "frontend": ("frontend",),
    "system-design": ("system-design",),
    "algorithm": ("algorithm",),
    "ai-agent": ("ai-agent-dev",),
}


@dataclass(frozen=True)
class SkillDefinition:
    skill_id: str
    name: str
    description: str
    instructions: str
    allowed_tools: tuple[str, ...]


class SkillRegistry:
    def __init__(self, root: Path | None = None) -> None:
        self._root = root or PROJECT_DIR / "resources" / "skills"

    def get(self, skill_id: str) -> SkillDefinition:
        if not isinstance(skill_id, str) or not re.fullmatch(
            r"[a-z0-9]+(?:-[a-z0-9]+)*", skill_id
        ):
            raise SkillConfigurationError(f"Skill ID 格式错误: {skill_id}")
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
        raw_allowed_tools = metadata.get("allowedTools", [])
        if not isinstance(raw_allowed_tools, list) or not all(
            isinstance(tool, str) and tool.strip() for tool in raw_allowed_tools
        ):
            raise SkillConfigurationError(f"Skill allowedTools 格式错误: {skill_id}")
        unsupported_tools = set(raw_allowed_tools) - SUPPORTED_INTERVIEW_TOOLS
        if unsupported_tools:
            unsupported = ", ".join(sorted(unsupported_tools))
            raise SkillConfigurationError(
                f"Skill 声明了未实现的工具: {skill_id}: {unsupported}"
            )
        return SkillDefinition(
            skill_id=skill_id,
            name=str(metadata["name"]),
            description=str(metadata["description"]),
            instructions=instructions,
            allowed_tools=tuple(dict.fromkeys(raw_allowed_tools)),
        )

    def resolve_for_interview(self, skill_ids: list[str] | tuple[str, ...]) -> tuple[SkillDefinition, ...]:
        """Resolve persisted/model-selected IDs without letting a stale ID stop an interview."""
        resolved: list[SkillDefinition] = []
        seen: set[str] = set()
        for skill_id in skill_ids:
            if not isinstance(skill_id, str) or not skill_id.strip() or skill_id in seen:
                continue
            try:
                resolved.append(self.get(skill_id))
                seen.add(skill_id)
            except SkillConfigurationError:
                logger.warning("忽略不存在或无效的面试 Skill: %s", skill_id)
        if not resolved:
            resolved.append(self.get("interview-coach"))
        elif "interview-coach" not in seen:
            resolved.insert(0, self.get("interview-coach"))
        return tuple(resolved)

    def available_for_interview(self) -> tuple[SkillDefinition, ...]:
        """Return only enabled Skills that are both advertised and installed."""
        skill_ids = ["interview-coach", *(
            str(item["id"]) for item in self.public_catalog()
        )]
        available: list[SkillDefinition] = []
        seen: set[str] = set()
        for skill_id in skill_ids:
            if skill_id in seen:
                continue
            available.append(self.get(skill_id))
            seen.add(skill_id)
        return tuple(available)

    def selection_catalog(self) -> list[dict[str, object]]:
        """Safe metadata exposed to the planning model before it selects Skills."""
        return [
            {
                "id": item.skill_id,
                "name": item.name,
                "description": item.description,
                "allowedTools": list(item.allowed_tools),
            }
            for item in self.available_for_interview()
        ]

    def select_for_interview(
        self, *, target_role: str, jd_text: str | None, interview_direction: str | None = None
    ) -> tuple[SkillDefinition, ...]:
        """由下层 Agent 根据职位选择 Skill；上层只传递职位/JD 快照。"""
        normalized = f"{target_role}\n{jd_text or ''}".lower()
        selected = [self.get("interview-coach")]
        # interview_direction is business context only. Concrete Skills are
        # selected by the planning model from the runtime catalog below.
        for skill_id in DIRECTION_SKILL_CANDIDATES.get(interview_direction or "", ()):
            if (self._root / skill_id).exists():
                selected.append(self.get(skill_id))
        # Deterministic domain selection keeps the planner's eligible skills
        # aligned with files actually shipped in the image.
        domain_keywords = {
            "java-backend": ("java", "spring", "jvm"),
            "python-backend": ("python", "fastapi", "django", "flask"),
            "system-design": ("system design", "系统设计", "微服务", "分布式"),
            "algorithm": ("algorithm", "算法", "leetcode", "数据结构"),
            "computer-vision": (
                "computer vision", "计算机视觉", "opencv", "目标检测", "图像处理",
                "图像分割", "语义分割", "ocr", "yolo", "视觉算法",
            ),
        }
        for skill_id, keywords in domain_keywords.items():
            skill_dir = self._root / skill_id
            if skill_dir.exists() and any(keyword in normalized for keyword in keywords):
                selected.append(self.get(skill_id))
        unique: dict[str, SkillDefinition] = {item.skill_id: item for item in selected}
        return tuple(unique.values())

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
        validated = [self._validate_public_item(item) for item in catalog]
        catalog_ids = [str(item["id"]) for item in validated]
        if len(catalog_ids) != len(set(catalog_ids)):
            raise SkillConfigurationError("Skill 展示目录包含重复 ID")
        for item in validated:
            # The public API must never advertise a Skill that cannot be loaded.
            self.get(str(item["id"]))
        return validated

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
