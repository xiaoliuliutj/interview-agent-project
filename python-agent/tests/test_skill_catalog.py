import httpx
import json
import pytest
from types import SimpleNamespace

from app.agent.interview.agent import InterviewPlanner
from app.agent.interview.models import CandidateProfile, Difficulty
from app.agent.skills.loader import SkillRegistry
from app.api.application import create_app
from app.core.prompt_loader import PromptLoader

REQUEST_TIMESTAMP = "2026-08-09T00:00:00Z"


@pytest.mark.asyncio
async def test_skill_catalog_and_jd_parser_are_deterministic() -> None:
    transport = httpx.ASGITransport(app=create_app())
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        catalog_response = await client.post(
            "/v1/agent/skills",
            json={
                "apiVersion": "v1",
                "requestId": "skill-list-1",
                "runId": "skill-list-run-1",
                "userId": "catalog-user",
                "sessionId": "skill-catalog",
                "operation": "agent.skills.list",
                "timestamp": REQUEST_TIMESTAMP,
            },
        )
        jd_response = await client.post(
            "/v1/agent/skills",
            json={
                "apiVersion": "v1",
                "requestId": "skill-jd-1",
                "runId": "skill-jd-run-1",
                "userId": "catalog-user",
                "sessionId": "skill-catalog",
                "operation": "agent.skills.parse-jd",
                "inputText": "需要 Java、Spring Boot 和 Redis 经验",
                "timestamp": REQUEST_TIMESTAMP,
            },
        )

    assert catalog_response.status_code == 200
    assert catalog_response.json()["code"] == 100
    assert catalog_response.json()["output"]["skills"][0]["id"] == "java-backend"
    assert jd_response.status_code == 200
    categories = jd_response.json()["output"]["categories"]
    assert {item["key"] for item in categories} >= {"java", "spring", "distributed"}


def test_computer_vision_skill_is_shipped_and_selectable() -> None:
    registry = SkillRegistry()

    skill = registry.get("computer-vision")
    selected = registry.select_for_interview(
        target_role="计算机视觉算法工程师", jd_text="要求熟悉 OpenCV、目标检测、YOLO 和 ONNX 推理"
    )

    assert skill.skill_id == "computer-vision"
    assert "computer-vision" in {item.skill_id for item in selected}


def test_unknown_persisted_skill_falls_back_to_interview_coach() -> None:
    resolved = SkillRegistry().resolve_for_interview(["missing-skill"])

    assert [item.skill_id for item in resolved] == ["interview-coach"]


class SequencedPlanningModel:
    def __init__(self) -> None:
        self.calls: list[object] = []

    async def ainvoke(self, messages: object) -> object:
        self.calls.append(messages)
        if len(self.calls) == 1:
            return SimpleNamespace(content=json.dumps({
                "selectedSkills": ["computer-vision", "not-installed"]
            }))
        return SimpleNamespace(content=json.dumps({
            "candidate_summary": "视觉算法候选人",
            "strategy_summary": "按阶段考察视觉项目和基础",
            "selectedSkills": ["not-installed"],
            "stages": [
                {"stage": "OPENING", "max_primary_questions": 1, "max_followups_per_question": 0, "difficulty": "MEDIUM", "topics": ["自我介绍"], "time_budget_minutes": 2},
                {"stage": "PROJECT", "max_primary_questions": 2, "max_followups_per_question": 1, "difficulty": "MEDIUM", "topics": ["视觉项目"], "time_budget_minutes": 8},
                {"stage": "FUNDAMENTAL", "max_primary_questions": 2, "max_followups_per_question": 1, "difficulty": "MEDIUM", "topics": ["检测与分割"], "time_budget_minutes": 8},
                {"stage": "SCENARIO", "max_primary_questions": 2, "max_followups_per_question": 1, "difficulty": "MEDIUM", "topics": ["模型优化"], "time_budget_minutes": 8},
                {"stage": "CODING", "max_primary_questions": 1, "max_followups_per_question": 0, "difficulty": "MEDIUM", "topics": ["数组"], "time_budget_minutes": 6},
                {"stage": "SUMMARY", "max_primary_questions": 1, "max_followups_per_question": 0, "difficulty": "MEDIUM", "topics": ["总结"], "time_budget_minutes": 2}
            ]
        }, ensure_ascii=False))


@pytest.mark.asyncio
async def test_planner_reads_runtime_catalog_before_selecting_skills() -> None:
    model = SequencedPlanningModel()
    planner = InterviewPlanner(model, PromptLoader(), SkillRegistry())
    profile = CandidateProfile(
        candidate_id="candidate-vision", resume_id="resume-vision",
        resume_text="使用 YOLO 完成目标检测并通过 ONNX 部署",
        jd_text="计算机视觉、OpenCV、目标检测",
        target_role="计算机视觉算法工程师", interview_duration_minutes=30,
        desired_difficulty=Difficulty.MEDIUM, question_count=20,
        requested_skill_id=None, custom_categories=[],
        system_knowledge_base_ids=[], user_knowledge_base_ids=[],
    )

    plan = await planner.create_plan(profile)

    assert len(model.calls) == 2
    selection_input = json.loads(model.calls[0][-1].content)
    assert "computer-vision" in {
        item["id"] for item in selection_input["availableSkills"]
    }
    assert plan.selected_skills == ["interview-coach", "computer-vision"]
