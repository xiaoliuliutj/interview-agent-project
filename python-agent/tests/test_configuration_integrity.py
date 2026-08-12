import ast
import json
import re
from pathlib import Path

from app.agent.skills.loader import SkillRegistry
from app.core.config import PROJECT_DIR


REPOSITORY_ROOT = PROJECT_DIR.parent
INTERNAL_SKILLS = {"interview-coach", "resume-analyst"}
OPERATION_PATTERN = re.compile(
    r'"(agent\.(?:respond|session\.[a-z-]+|resume\.[a-z-]+|skills\.[a-z-]+)'
    r'|rag\.[a-z-]+|tool\.web\.[a-z-]+)"'
)


def test_skill_catalog_matches_installed_skill_directories() -> None:
    registry = SkillRegistry()
    catalog_items = registry.public_catalog()
    catalog_ids = {str(item["id"]) for item in catalog_items}
    installed_ids = {
        path.parent.name
        for path in (PROJECT_DIR / "config" / "skills").glob("*/skill.json")
    }

    assert len(catalog_ids) == len(catalog_items)
    assert installed_ids == catalog_ids | INTERNAL_SKILLS
    for skill_id in installed_ids:
        skill_dir = PROJECT_DIR / "config" / "skills" / skill_id
        metadata = json.loads((skill_dir / "skill.json").read_text(encoding="utf-8"))
        assert metadata["id"] == skill_id
        assert metadata["enabled"] is True
        assert (skill_dir / "SKILL.md").read_text(encoding="utf-8").strip()
        registry.get(skill_id)  # also validates declared runtime tools


def test_static_prompt_references_exist() -> None:
    prompt_root = PROJECT_DIR / "config" / "prompts"
    references: set[str] = set()
    for source_path in (PROJECT_DIR / "app").rglob("*.py"):
        tree = ast.parse(source_path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if not isinstance(node, ast.Call) or not node.args:
                continue
            function = node.func
            if not isinstance(function, ast.Attribute) or function.attr not in {"load", "render"}:
                continue
            prompt = node.args[0]
            if isinstance(prompt, ast.Constant) and isinstance(prompt.value, str) and prompt.value.endswith(".md"):
                references.add(prompt.value)

    assert references
    missing = sorted(reference for reference in references if not (prompt_root / reference).is_file())
    assert missing == []


def test_frontend_skill_icon_ids_do_not_reference_unknown_skills() -> None:
    catalog = json.loads(
        (PROJECT_DIR / "config" / "skills" / "catalog.json").read_text(encoding="utf-8")
    )
    catalog_ids = {str(item["id"]) for item in catalog}
    icon_source = (
        REPOSITORY_ROOT / "frontend" / "src" / "utils" / "skillIcons.tsx"
    ).read_text(encoding="utf-8")
    icon_ids = set(re.findall(r"^\s*'([a-z0-9-]+)':", icon_source, re.MULTILINE))

    assert icon_ids <= catalog_ids | {"custom"}


def test_java_to_python_agent_operations_match_contracts() -> None:
    java_source = "\n".join(
        path.read_text(encoding="utf-8")
        for path in (REPOSITORY_ROOT / "java-backend" / "src" / "main" / "java").rglob("*.java")
    )
    python_contracts = (PROJECT_DIR / "app" / "core" / "contracts.py").read_text(
        encoding="utf-8"
    )
    java_operations = set(OPERATION_PATTERN.findall(java_source))
    python_operations = set(OPERATION_PATTERN.findall(python_contracts))

    assert java_operations
    assert java_operations == python_operations
