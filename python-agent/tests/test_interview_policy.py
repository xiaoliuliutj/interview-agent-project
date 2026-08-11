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
    assert saved.target_question_count == 20
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


def test_primary_stage_cannot_be_skipped_before_two_questions() -> None:
    repository, events = InMemorySessionRepository(), []
    service, _, _, _ = build_service(repository, [], [], events)
    session = SimpleNamespace(
        current_stage=InterviewStage.PROJECT,
        current_topic="项目",
        stage_question_counts={"PROJECT": 1},
        topic_question_counts={"项目": 1},
        total_question_count=2,
        target_question_count=20,
        primary_question_count=1,
        followup_count=0,
        plan=build_plan(),
    )
    actions = service._allowed_actions(session, evaluation(90, "回答完整"))
    assert InterviewAction.NEXT_QUESTION in actions
    assert InterviewAction.NEXT_STAGE not in actions
    assert InterviewAction.END_INTERVIEW not in actions


def test_invalid_next_stage_before_minimum_uses_current_stage_topic() -> None:
    repository, events = InMemorySessionRepository(), []
    service, _, _, _ = build_service(repository, [], [], events)
    session = SimpleNamespace(
        current_stage=InterviewStage.PROJECT,
        current_topic="项目",
        stage_question_counts={"PROJECT": 1},
        topic_question_counts={"项目": 1},
        total_question_count=2,
        total_primary_question_count=2,
        target_question_count=20,
        primary_question_count=1,
        followup_count=0,
        plan=build_plan(),
    )
    allowed = service._allowed_actions(session, evaluation(90, "回答完整"))
    route = service._enforce_route_limits(
        session,
        InterviewRoute(action=InterviewAction.NEXT_STAGE, next_topic="下一阶段 Java 基础"),
        allowed,
        InterviewStage.FUNDAMENTAL,
        evaluation(90, "回答完整"),
    )
    assert route == InterviewRoute(action=InterviewAction.NEXT_QUESTION, next_topic="项目")


def test_middle_stage_never_allows_ending_the_whole_interview() -> None:
    repository, events = InMemorySessionRepository(), []
    service, _, _, _ = build_service(repository, [], [], events)
    session = SimpleNamespace(
        current_stage=InterviewStage.PROJECT,
        current_topic="项目",
        stage_question_counts={"PROJECT": 2},
        topic_question_counts={"项目": 2},
        total_question_count=3,
        target_question_count=20,
        primary_question_count=2,
        followup_count=0,
        plan=build_plan(),
    )
    actions = service._allowed_actions(session, evaluation(85, "回答完整"))
    assert actions == {InterviewAction.NEXT_QUESTION, InterviewAction.NEXT_STAGE}


def test_followup_depends_on_answer_quality_and_weaknesses() -> None:
    repository, events = InMemorySessionRepository(), []
    service, _, _, _ = build_service(repository, [], [], events)
    session = SimpleNamespace(
        current_stage=InterviewStage.PROJECT,
        current_topic="项目",
        stage_question_counts={"PROJECT": 2},
        topic_question_counts={"项目": 1},
        total_question_count=3,
        target_question_count=20,
        primary_question_count=2,
        followup_count=0,
        plan=build_plan(),
    )
    assert InterviewAction.FOLLOW_UP in service._allowed_actions(session, evaluation(55, "缺少细节"))
    assert InterviewAction.FOLLOW_UP not in service._allowed_actions(session, evaluation(85, "回答完整"))


def test_fourth_primary_question_can_still_receive_a_legal_followup() -> None:
    repository, events = InMemorySessionRepository(), []
    service, _, _, _ = build_service(repository, [], [], events)
    session = SimpleNamespace(
        current_stage=InterviewStage.PROJECT,
        current_topic="项目",
        stage_question_counts={"PROJECT": 4},
        topic_question_counts={"项目": 1},
        total_question_count=5,
        target_question_count=20,
        primary_question_count=4,
        followup_count=0,
        plan=build_plan(),
    )
    low_score = evaluation(50, "缺少关键边界")
    actions = service._allowed_actions(session, low_score)
    assert actions == {InterviewAction.FOLLOW_UP, InterviewAction.NEXT_STAGE}
    route = service._enforce_route_limits(
        session,
        InterviewRoute(action=InterviewAction.FOLLOW_UP, next_topic="被模型偷换的主题"),
        actions,
        InterviewStage.FUNDAMENTAL,
        low_score,
    )
    assert route == InterviewRoute(action=InterviewAction.FOLLOW_UP, next_topic="项目")


def test_coding_second_question_is_forced_only_after_severe_failure() -> None:
    repository, events = InMemorySessionRepository(), []
    service, _, _, _ = build_service(repository, [], [], events)
    session = SimpleNamespace(
        current_stage=InterviewStage.CODING,
        current_topic="算法",
        stage_question_counts={"CODING": 1},
        topic_question_counts={"算法": 1},
        total_question_count=8,
        target_question_count=20,
        primary_question_count=1,
        followup_count=0,
        plan=build_plan(),
    )
    assert service._allowed_actions(session, evaluation(39, "严重不足")) == {InterviewAction.NEXT_QUESTION}
    assert service._allowed_actions(session, evaluation(40, "达到最低要求")) == {InterviewAction.NEXT_STAGE}
    session.stage_question_counts = {"CODING": 2}
    session.primary_question_count = 2
    assert service._allowed_actions(session, evaluation(10, "第二题仍不足")) == {InterviewAction.NEXT_STAGE}


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
    assert actions == {InterviewAction.END_INTERVIEW}
