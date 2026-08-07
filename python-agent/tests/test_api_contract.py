from types import SimpleNamespace

import httpx
import pytest

from app.agent.rag.models import KnowledgeChunk, RagSearchResult
from app.agent.evaluation.models import ResumeEvaluation
from app.api.application import create_app
from app.core.contracts import SessionStatus


class FakeInterviewAgentService:
    def __init__(self) -> None:
        self.initialized_profile = None

    async def initialize_session(self, *, user_id, session_id, profile, run_id=None):
        self.initialized_profile = profile
        return SimpleNamespace(
            user_id=user_id,
            session_id=session_id,
            status=SessionStatus.ACTIVE,
            state_version=0,
            current_question="请做一个简短的自我介绍。",
        )

    async def submit_answer_for_run(
        self, *, user_id, session_id, candidate_answer, run_id
    ):
        session = SimpleNamespace(
            user_id=user_id,
            session_id=session_id,
            status=SessionStatus.ACTIVE,
            state_version=1,
            current_question="请介绍一个你最熟悉的项目。",
        )
        return SimpleNamespace(
            session=session,
            snapshot=SimpleNamespace(
                answer=session.current_question,
                session_status=session.status,
                state_version=session.state_version,
                output={
                    "evaluationSummary": "项目描述完整。",
                    "action": "NEXT_STAGE",
                    "stage": "OPENING",
                },
            ),
        )

    async def complete_session(self, *, user_id, session_id):
        return SimpleNamespace(
            user_id=user_id,
            session_id=session_id,
            status=SessionStatus.COMPLETED,
            state_version=2,
            current_question="面试已结束。",
        )


class FakeRagService:
    async def search(self, query, *, use_case, knowledge_base_ids):
        return [
            RagSearchResult(
                chunk=KnowledgeChunk(
                    chunk_id="doc-1:0",
                    knowledge_base_id="kb-1",
                    document_id="doc-1",
                    source_name="test.md",
                    chunk_index=0,
                    content="缓存一致性需要考虑更新顺序。",
                ),
                score=0.91,
            )
        ]

    async def index_document(self, document):
        return 1


class FakeResumeEvaluator:
    async def evaluate(self, *, subject_id, input_text, target_role, knowledge_base_ids):
        return ResumeEvaluation(
            overallScore=82,
            contentScore=80,
            structureScore=85,
            skillMatchScore=84,
            expressionScore=78,
            projectScore=83,
            summary="简历信息完整。",
            strengths=["项目描述清晰"],
            suggestions=["补充量化结果"],
        )


@pytest.mark.asyncio
async def test_initialize_endpoint_returns_standard_response() -> None:
    service = FakeInterviewAgentService()
    transport = httpx.ASGITransport(app=create_app(service))
    payload = {
        "apiVersion": "v1",
        "requestId": "req-1",
        "runId": "run-1",
        "userId": "user-1",
        "sessionId": "session-1",
        "candidate": {
            "candidateId": "candidate-1",
            "resumeId": "resume-1",
            "targetRole": "Java 后端",
            "resumeText": "有缓存项目经验",
        },
    }

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/v1/agent/sessions/initialize", json=payload)

    assert response.status_code == 200
    body = response.json()
    assert body["code"] == 100
    assert body["answer"] == "请做一个简短的自我介绍。"
    assert service.initialized_profile.resume_id == "resume-1"


@pytest.mark.asyncio
async def test_validation_error_uses_same_standard_response_shape() -> None:
    transport = httpx.ASGITransport(app=create_app(FakeInterviewAgentService()))
    payload = {"apiVersion": "v1", "requestId": "req-2"}

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/v1/agent/respond", json=payload)

    assert response.status_code == 400
    body = response.json()
    assert set(body) == {
        "apiVersion",
        "requestId",
        "runId",
        "code",
        "status",
        "userId",
        "sessionId",
        "sessionStatus",
        "stateVersion",
        "answer",
        "output",
        "error",
        "timestamp",
    }
    assert body["code"] == 200
    assert body["status"] == "FAILED"


@pytest.mark.asyncio
async def test_respond_endpoint_returns_controlled_display_output() -> None:
    transport = httpx.ASGITransport(app=create_app(FakeInterviewAgentService()))
    payload = {
        "apiVersion": "v1",
        "requestId": "req-answer",
        "runId": "run-answer",
        "userId": "user-1",
        "sessionId": "session-1",
        "operation": "agent.respond",
        "question": "我在项目中使用了 Redis。",
    }

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/v1/agent/respond", json=payload)

    assert response.status_code == 200
    assert response.json()["output"] == {
        "evaluationSummary": "项目描述完整。",
        "action": "NEXT_STAGE",
        "stage": "OPENING",
    }


@pytest.mark.asyncio
async def test_rag_search_endpoint_returns_sources_in_standard_answer() -> None:
    transport = httpx.ASGITransport(
        app=create_app(FakeInterviewAgentService(), FakeRagService())
    )
    payload = {
        "apiVersion": "v1",
        "requestId": "req-rag",
        "runId": "run-rag",
        "userId": "user-1",
        "sessionId": "kb-query",
        "operation": "rag.search",
        "question": "缓存一致性",
        "knowledgeBaseIds": ["kb-1"],
        "useCase": "QUESTION_GENERATION",
    }
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/v1/agent/rag/search", json=payload)

    assert response.status_code == 200
    assert response.json()["code"] == 100
    assert "缓存一致性" in response.json()["answer"]


@pytest.mark.asyncio
async def test_complete_endpoint_is_a_standard_no_question_request() -> None:
    transport = httpx.ASGITransport(app=create_app(FakeInterviewAgentService()))
    payload = {
        "apiVersion": "v1",
        "requestId": "req-complete",
        "runId": "run-complete",
        "userId": "user-1",
        "sessionId": "session-1",
        "operation": "agent.session.complete",
    }

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/v1/agent/sessions/complete", json=payload)

    assert response.status_code == 200
    assert response.json()["sessionStatus"] == "COMPLETED"
    assert response.json()["output"] is None


@pytest.mark.asyncio
async def test_resume_evaluation_endpoint_returns_structured_output() -> None:
    transport = httpx.ASGITransport(
        app=create_app(FakeInterviewAgentService(), resume_evaluator=FakeResumeEvaluator())
    )
    payload = {
        "apiVersion": "v1",
        "requestId": "req-resume-eval",
        "runId": "run-resume-eval",
        "userId": "user-1",
        "sessionId": "resume-eval-1",
        "operation": "agent.resume.evaluate",
        "subjectType": "RESUME",
        "subjectId": "resume-1",
        "inputText": "熟悉 Java 和 Redis。",
        "targetRole": "Java 后端实习生",
    }
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/v1/agent/evaluate/resume", json=payload)

    assert response.status_code == 200
    body = response.json()
    assert body["answer"] == "简历信息完整。"
    assert body["output"]["overallScore"] == 82
