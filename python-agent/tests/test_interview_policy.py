from types import SimpleNamespace

import pytest

from app.agent.interview.models import InterviewAction, InterviewRoute, InterviewStage
from app.core.contracts import SessionStatus

from test_interview_service import (
    InMemorySessionRepository,
    build_plan,
    build_profile,
    build_service,
    evaluation,
)


@pytest.mark.asyncio
async def test_opening_is_registered_and_replanned_before_project_question() -> None:
    repository, events = InMemorySessionRepository(), []
    service, _, _, _ = build_service(
        repository,
        [evaluation()],
        [InterviewRoute(action=InterviewAction.NEXT_STAGE, next_topic="项目架构")],
        events,
    )
    session = await service.initialize_session(
        user_id="user-1", session_id="session-1", profile=build_profile()
    )
    assert session.asked_question_catalog == [session.current_question]
    await service.submit_answer_for_run(
        user_id="user-1", session_id="session-1", candidate_answer="我做过一个 Redis 项目",
        run_id="run-1", expected_session_status=SessionStatus.ACTIVE,
        expected_state_version=0,
    )
    saved = await repository.get("session-1")
    assert saved is not None
    assert saved.target_question_count == 7
    assert saved.current_stage == InterviewStage.PROJECT
    assert len(saved.asked_question_catalog) == 2


def test_topic_limit_forces_stage_change() -> None:
    # 直接验证代码级硬边界，不依赖模型是否遵守 Prompt。
    repository, events = InMemorySessionRepository(), []
    service, _, _, _ = build_service(repository, [], [], events)
    session = SimpleNamespace(
        current_stage=InterviewStage.PROJECT,
        current_topic="项目",
        topic_question_counts={"项目": 3},
        stage_question_counts={"PROJECT": 3},
        total_primary_question_count=4,
        target_question_count=7,
        plan=build_plan(),
    )
    # 使用真实会话模型的路由边界方法所需的仅是这些状态字段。
    route = service._enforce_route_limits(
        session,
        InterviewRoute(action=InterviewAction.FOLLOW_UP, next_topic="项目"),
        {InterviewAction.FOLLOW_UP, InterviewAction.NEXT_STAGE},
        InterviewStage.FUNDAMENTAL,
    )
    assert route.action == InterviewAction.NEXT_STAGE


def test_total_question_budget_includes_followups_and_hard_stops_at_twenty() -> None:
    repository, events = InMemorySessionRepository(), []
    service, _, _, _ = build_service(repository, [], [], events)
    session = SimpleNamespace(
        current_stage=InterviewStage.PROJECT,
        current_topic="项目",
        topic_question_counts={},
        stage_question_counts={"PROJECT": 1},
        total_question_count=20,
        total_primary_question_count=8,
        target_question_count=20,
        primary_question_count=1,
        followup_count=0,
        plan=build_plan(),
    )
    actions = service._allowed_actions(session, evaluation(20, "回答明显不足"))
    assert actions == {InterviewAction.NEXT_STAGE, InterviewAction.END_INTERVIEW}
