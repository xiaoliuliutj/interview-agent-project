from types import SimpleNamespace

import httpx
import pytest

from app.agent.rag.models import KnowledgeChunk, RagSearchResult
from app.agent.evaluation.models import ResumeEvaluation
from app.api.application import create_app
from app.core.contracts import SessionStatus

REQUEST_TIMESTAMP = "2026-08-09T00:00:00Z"


class FakeInterviewAgentService:
    def __init__(self) -> None:
        self.initialized_profile = None
        self.submitted_answer = None

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
        self, *, user_id, session_id, candidate_answer, run_id,
        expected_session_status, expected_state_version
    ):
        self.submitted_answer = candidate_answer
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
                turn_stage="OPENING",
                output={
                    "evaluationSummary": "项目描述完整。",
                    "action": "NEXT_STAGE",
                    "stage": "OPENING",
                },
            ),
        )

    async def complete_session(
        self, *, user_id, session_id, expected_session_status, expected_state_version
    ):
        return SimpleNamespace(
            user_id=user_id,
            session_id=session_id,
            status=SessionStatus.COMPLETED,
            state_version=2,
            current_question="面试已结束。",
        )


class FakeRagService:
    def __init__(self) -> None:
        self.deleted_knowledge_base_ids: list[str] = []

    async def search(self, query, *, use_case, knowledge_base_ids):
        return [
            RagSearchResult(
                chunk=KnowledgeChunk(
                    chunk_id="doc-1:0",
                    knowledge_base_id="kb-1",
                    document_id="doc-1",
                    source_name="reference.md",
                    chunk_index=0,
                    content="缓存一致性需要考虑更新顺序。",
                ),
                score=0.91,
            )
        ]

    async def index_document(self, document):
        return 1

    async def delete_knowledge_base(self, knowledge_base_id: str) -> None:
        self.deleted_knowledge_base_ids.append(knowledge_base_id)


class FakeResumeEvaluator:
    async def evaluate(self, *, subject_id, input_text, target_role):
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


class FakeResumeMemoryService:
    def __init__(self) -> None:
        self.calls: list[dict[str, object]] = []

    async def record_resume_analysis(self, **payload: object) -> None:
        self.calls.append(payload)

    async def get_resume_evaluation_run(self, **payload: object):
        return None

    async def activate_resume(self, **payload: object) -> None:
        self.calls.append(payload)


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
        "operation": "agent.session.initialize",
        "timestamp": REQUEST_TIMESTAMP,
        "candidate": {
            "candidateId": "candidate-1",
            "resumeId": "resume-1",
            "targetRole": "Java 后端",
            "resumeText": "有缓存项目经验",
            "jdText": "负责 Java 后端服务开发",
            "interviewDurationMinutes": 30,
            "desiredDifficulty": "MEDIUM",
            "questionCount": 6,
            "customCategories": [],
            "systemKnowledgeBaseIds": [],
            "userKnowledgeBaseIds": [],
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
            "turnStage",
            "currentStage",
        "output",
        "error",
        "timestamp",
    }
    assert body["code"] == 200
    assert body["status"] == "FAILED"
    assert body["requestId"] == "req-2"
    assert body["runId"] is None
    assert body["userId"] is None
    assert body["sessionId"] is None


@pytest.mark.asyncio
async def test_respond_endpoint_returns_only_candidate_visible_evaluation() -> None:
    service = FakeInterviewAgentService()
    transport = httpx.ASGITransport(app=create_app(service))
    payload = {
        "apiVersion": "v1",
        "requestId": "req-answer",
        "runId": "run-answer",
        "userId": "user-1",
        "sessionId": "session-1",
        "operation": "agent.respond",
        "sessionStatus": "ACTIVE",
        "stateVersion": 0,
        "answer": "我在项目中使用了 Redis。",
        "timestamp": REQUEST_TIMESTAMP,
    }

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/v1/agent/respond", json=payload)

    assert response.status_code == 200
    assert response.json()["output"] == {"evaluationSummary": "项目描述完整。"}
    assert response.json()["turnStage"] == "OPENING"
    assert service.submitted_answer == "我在项目中使用了 Redis。"


@pytest.mark.asyncio
async def test_respond_endpoint_rejects_question_field_instead_of_answer() -> None:
    transport = httpx.ASGITransport(app=create_app(FakeInterviewAgentService()))
    payload = {
        "apiVersion": "v1",
        "requestId": "req-answer-invalid",
        "runId": "run-answer-invalid",
        "userId": "user-1",
        "sessionId": "session-1",
        "operation": "agent.respond",
        "question": "这是候选人回答，但字段错误。",
        "timestamp": REQUEST_TIMESTAMP,
    }

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/v1/agent/respond", json=payload)

    assert response.status_code == 400
    assert response.json()["code"] == 200


@pytest.mark.asyncio
async def test_rag_search_endpoint_is_not_exposed() -> None:
    transport = httpx.ASGITransport(app=create_app(FakeInterviewAgentService()))
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/v1/agent/rag/search", json={})

    assert response.status_code == 404


@pytest.mark.asyncio
async def test_rag_index_requires_document_content_instead_of_question() -> None:
    transport = httpx.ASGITransport(app=create_app(FakeInterviewAgentService(), FakeRagService()))
    payload = {
        "apiVersion": "v1",
        "requestId": "req-rag-index",
        "runId": "run-rag-index",
        "userId": "user-1",
        "sessionId": "kb-index",
        "operation": "rag.index",
        "documentContent": "缓存一致性需要考虑更新顺序。",
        "knowledgeBaseIds": ["kb-1"],
        "documentId": "kb-1",
        "sourceName": "reference.md",
        "timestamp": REQUEST_TIMESTAMP,
    }
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/v1/agent/rag/index", json=payload)

    assert response.status_code == 200
    assert response.json()["answer"] == "1"

    payload["question"] = payload.pop("documentContent")
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        invalid_response = await client.post("/v1/agent/rag/index", json=payload)

    assert invalid_response.status_code == 400


@pytest.mark.asyncio
async def test_rag_delete_endpoint_deletes_exact_knowledge_base_id() -> None:
    rag_service = FakeRagService()
    transport = httpx.ASGITransport(app=create_app(FakeInterviewAgentService(), rag_service))
    payload = {
        "apiVersion": "v1",
        "requestId": "req-rag-delete",
        "runId": "run-rag-delete",
        "userId": "user-1",
        "sessionId": "kb-delete-1",
        "operation": "rag.delete",
        "knowledgeBaseId": "kb-1",
        "timestamp": REQUEST_TIMESTAMP,
    }

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/v1/agent/rag/delete", json=payload)

    assert response.status_code == 200
    assert response.json()["code"] == 100
    assert response.json()["answer"] is None
    assert rag_service.deleted_knowledge_base_ids == ["kb-1"]


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
        "sessionStatus": "ACTIVE",
        "stateVersion": 0,
        "timestamp": REQUEST_TIMESTAMP,
    }

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/v1/agent/sessions/complete", json=payload)

    assert response.status_code == 200
    assert response.json()["sessionStatus"] == "COMPLETED"
    assert response.json()["output"] is None


@pytest.mark.asyncio
async def test_resume_evaluation_endpoint_returns_structured_output() -> None:
    memory_service = FakeResumeMemoryService()
    transport = httpx.ASGITransport(
        app=create_app(
            FakeInterviewAgentService(),
            resume_evaluator=FakeResumeEvaluator(),
            memory_service=memory_service,
        )
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
        "candidateId": "candidate-1",
        "inputText": "熟悉 Java 和 Redis。",
        "targetRole": "Java 后端实习生",
        "timestamp": REQUEST_TIMESTAMP,
    }
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/v1/agent/evaluate/resume", json=payload)

    assert response.status_code == 200
    body = response.json()
    assert body["answer"] == "简历信息完整。"
    assert body["output"]["overallScore"] == 82
    assert memory_service.calls[0]["technical_stack"] == []


@pytest.mark.asyncio
async def test_resume_activation_endpoint_uses_the_standard_contract() -> None:
    memory_service = FakeResumeMemoryService()
    transport = httpx.ASGITransport(
        app=create_app(FakeInterviewAgentService(), memory_service=memory_service)
    )
    payload = {
        "apiVersion": "v1",
        "requestId": "req-resume-activate",
        "runId": "run-resume-activate",
        "userId": "user-1",
        "sessionId": "resume-memory-candidate-1",
        "operation": "agent.resume.activate",
        "subjectId": "resume-2",
        "candidateId": "candidate-1",
        "inputText": "熟悉 Java、Redis 和 PostgreSQL。",
        "targetRole": "Java 后端",
        "timestamp": REQUEST_TIMESTAMP,
    }

    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/v1/agent/resume/activate", json=payload)

    assert response.status_code == 200
    assert response.json()["code"] == 100
    assert memory_service.calls == [{
        "user_id": "user-1", "resume_id": "resume-2",
        "candidate_id": "candidate-1", "resume_text": "熟悉 Java、Redis 和 PostgreSQL。",
        "target_role": "Java 后端", "run_id": "run-resume-activate",
    }]


@pytest.mark.asyncio
async def test_resume_activation_rejects_a_missing_operation() -> None:
    transport = httpx.ASGITransport(
        app=create_app(FakeInterviewAgentService(), memory_service=FakeResumeMemoryService())
    )
    payload = {
        "apiVersion": "v1",
        "requestId": "req-resume-activate-invalid",
        "runId": "run-resume-activate-invalid",
        "userId": "user-1",
        "sessionId": "resume-memory-candidate-1",
        "subjectId": "resume-2",
        "candidateId": "candidate-1",
        "inputText": "熟悉 Java、Redis 和 PostgreSQL。",
        "targetRole": "Java 后端",
        "timestamp": REQUEST_TIMESTAMP,
    }
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/v1/agent/resume/activate", json=payload)

    assert response.status_code == 400
    assert response.json()["code"] == 200


@pytest.mark.asyncio
async def test_resume_evaluation_rejects_removed_rag_parameter() -> None:
    transport = httpx.ASGITransport(
        app=create_app(
            FakeInterviewAgentService(),
            resume_evaluator=FakeResumeEvaluator(),
            memory_service=FakeResumeMemoryService(),
        )
    )
    payload = {
        "apiVersion": "v1",
        "requestId": "req-resume-eval-no-rag",
        "runId": "run-resume-eval-no-rag",
        "userId": "user-1",
        "sessionId": "resume-eval-1",
        "operation": "agent.resume.evaluate",
        "subjectType": "RESUME",
        "subjectId": "resume-1",
        "candidateId": "candidate-1",
        "inputText": "熟悉 Java 和 Redis。",
        "targetRole": "Java 后端实习生",
        "knowledgeBaseIds": ["deprecated-kb"],
        "timestamp": REQUEST_TIMESTAMP,
    }
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.post("/v1/agent/evaluate/resume", json=payload)

    assert response.status_code == 400
    assert response.json()["code"] == 200
