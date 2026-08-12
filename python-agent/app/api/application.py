"""FastAPI entrypoint for the lower-layer Agent service."""

import asyncio
import json
import logging
import hashlib
from collections.abc import Mapping

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.agent.evaluation.agent import ResumeEvaluationAgent
from app.agent.interview.models import CandidateProfile, InterviewSession, DEFAULT_TARGET_QUESTION_COUNT
from app.agent.interview.service import InterviewAgentService
from app.agent.memory.service import MemoryService
from app.agent.rag.models import KnowledgeDocument
from app.agent.rag.service import RagService
from app.agent.skills.loader import SkillRegistry
from app.agent.web_reader import crawl_public_site, fetch_public_article
from app.bootstrap import build_interview_agent_service, build_resume_evaluation_agent
from app.core.contracts import (
    AgentEvaluationRequest,
    AgentInitializationRequest,
    AgentRagIndexRequest,
    AgentRagDeleteRequest,
    AgentResumeMemoryActivationRequest,
    AgentRespondRequest,
    AgentResponse,
    AgentSessionCompletionRequest,
    AgentSkillRequest,
    AgentWebFetchRequest,
    AgentWebCrawlRequest,
    RunStatus,
    SessionStatus,
)
from app.core.exceptions import (
    AgentDependencyError,
    ApplicationException,
    ConsistencyError,
    ExceptionHandler,
    RequestError,
)

logger = logging.getLogger(__name__)

INTERVIEW_TURN_TIMEOUT_SECONDS = 150.0


def create_app(
    service: InterviewAgentService | None = None,
    rag_service: RagService | None = None,
    resume_evaluator: ResumeEvaluationAgent | None = None,
    memory_service: MemoryService | None = None,
) -> FastAPI:
    app = FastAPI(title="Interview Agent Service", version="v1")
    app.state.interview_agent_service = service
    app.state.rag_service = rag_service
    app.state.resume_evaluator = resume_evaluator
    app.state.memory_service = memory_service

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "UP"}

    @app.get("/v1/agent/sessions/{session_id}/progress")
    async def session_progress(session_id: str, request: Request) -> dict[str, str]:
        service = _resolve_service(request)
        progress = getattr(service, "progress_for", None)
        return {"stage": progress(session_id) if callable(progress) else "IDLE"}

    @app.post("/v1/agent/sessions/initialize", response_model=AgentResponse)
    async def initialize_session(payload: AgentInitializationRequest, request: Request) -> AgentResponse:
        session = await _resolve_service(request).initialize_session(
            user_id=payload.user_id,
            session_id=payload.session_id,
            profile=CandidateProfile.model_validate(
                {**payload.candidate.model_dump(), "question_count": DEFAULT_TARGET_QUESTION_COUNT}
            ),
            run_id=payload.run_id,
        )
        return _success_response(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, session=session,
            output={
                "currentPrimaryQuestionCount": getattr(session, "primary_question_count", 1),
                "totalPrimaryQuestionCount": getattr(session, "total_primary_question_count", 1),
                "currentFollowupCount": getattr(session, "followup_count", 0),
                "totalQuestionCount": getattr(session, "total_question_count", 1),
                "questionBudget": getattr(session, "target_question_count", None),
            },
        )

    @app.post("/v1/agent/respond", response_model=AgentResponse)
    async def respond(payload: AgentRespondRequest, request: Request) -> AgentResponse:
        service = _resolve_service(request)
        try:
            result = await asyncio.wait_for(
                service.submit_answer_for_run(
                    user_id=payload.user_id,
                    session_id=payload.session_id,
                    candidate_answer=payload.answer,
                    run_id=payload.run_id,
                    expected_session_status=payload.session_status,
                    expected_state_version=payload.state_version,
                ),
                timeout=INTERVIEW_TURN_TIMEOUT_SECONDS,
            )
        except TimeoutError as error:
            marker = getattr(service, "mark_progress_failed", None)
            if callable(marker):
                marker(payload.session_id)
            raise AgentDependencyError(
                "本轮面试处理超过 150 秒，请保留当前回答后重试",
                retryable=False,
            ) from error
        except BaseException:
            marker = getattr(service, "mark_progress_failed", None)
            if callable(marker):
                marker(payload.session_id)
            raise
        return _success_response(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, session=result.session,
            answer=result.snapshot.answer, output=_candidate_response_output(result.snapshot.output),
            state_version=result.snapshot.state_version,
            session_status=result.snapshot.session_status,
            turn_stage=result.snapshot.turn_stage,
            current_stage=getattr(result.snapshot, "current_stage", None),
        )

    @app.post("/v1/agent/sessions/complete", response_model=AgentResponse)
    async def complete_session(payload: AgentSessionCompletionRequest, request: Request) -> AgentResponse:
        service = _resolve_service(request)
        session = (await service.pause_session(
                       user_id=payload.user_id, session_id=payload.session_id,
                       expected_session_status=payload.session_status,
                       expected_state_version=payload.state_version,
                   ) if payload.operation == "agent.session.pause"
                   else await service.complete_session(
                       user_id=payload.user_id, session_id=payload.session_id,
                       expected_session_status=payload.session_status,
                       expected_state_version=payload.state_version,
                   ))
        return _success_response(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, session=session,
            output=(
                {"finalEvaluation": session.final_evaluation.model_dump(by_alias=True)}
                if getattr(session, "final_evaluation", None) is not None else None
            ),
            state_version=session.state_version, session_status=session.status,
        )

    @app.post("/v1/agent/evaluate/resume", response_model=AgentResponse)
    async def evaluate_resume(payload: AgentEvaluationRequest, request: Request) -> AgentResponse:
        fingerprint = _resume_evaluation_fingerprint(payload)
        memory_service = _resolve_memory_service(request)
        result = await memory_service.get_resume_evaluation_run(
            user_id=payload.user_id, resume_id=payload.subject_id,
            run_id=payload.run_id, evaluation_fingerprint=fingerprint,
        )
        if result is None:
            result = await _resolve_resume_evaluator(request).evaluate(
                subject_id=payload.subject_id,
                input_text=payload.input_text,
                target_role=payload.target_role,
            )
        try:
            await memory_service.record_resume_analysis(
                user_id=payload.user_id,
                resume_id=payload.subject_id,
                candidate_id=payload.candidate_id,
                resume_text=payload.input_text,
                target_role=payload.target_role,
                summary=result.summary,
                questions=[item.question for item in result.issues],
                priorities=[item.priority for item in result.issues],
                suggestions=[item.suggestion for item in result.issues] + result.suggestions,
                technical_stack=result.technical_stack,
                technical_depth=result.technical_depth,
                career_preferences=result.career_preferences,
                run_id=payload.run_id,
                evaluation_fingerprint=fingerprint,
                evaluation=result,
            )
        except ConsistencyError:
            replay = await memory_service.get_resume_evaluation_run(
                user_id=payload.user_id, resume_id=payload.subject_id,
                run_id=payload.run_id, evaluation_fingerprint=fingerprint,
            )
            if replay is None:
                raise
            result = replay
        return AgentResponse(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, code=100, status=RunStatus.COMPLETED,
            user_id=payload.user_id, session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE, state_version=0,
            answer=result.summary, output=result.model_dump(by_alias=True), error=None,
        )

    @app.post("/v1/agent/resume/activate", response_model=AgentResponse)
    async def activate_resume_memory(
        payload: AgentResumeMemoryActivationRequest, request: Request
    ) -> AgentResponse:
        await _resolve_memory_service(request).activate_resume(
            user_id=payload.user_id, resume_id=payload.subject_id,
            candidate_id=payload.candidate_id, resume_text=payload.input_text,
            target_role=payload.target_role, run_id=payload.run_id,
        )
        return AgentResponse(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, code=100, status=RunStatus.COMPLETED,
            user_id=payload.user_id, session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE, state_version=0,
            answer=None, output=None, error=None,
        )

    @app.post("/v1/agent/rag/index", response_model=AgentResponse)
    async def index_rag(payload: AgentRagIndexRequest, request: Request) -> AgentResponse:
        count = await _resolve_rag_service(request).index_document(KnowledgeDocument(
            knowledge_base_id=payload.knowledge_base_ids[0],
            document_id=payload.document_id,
            source_name=payload.source_name,
            content=payload.document_content,
        ))
        return AgentResponse(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, code=100, status=RunStatus.COMPLETED,
            user_id=payload.user_id, session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE, state_version=0,
            answer=str(count), output=None, error=None,
        )

    @app.post("/v1/agent/rag/delete", response_model=AgentResponse)
    async def delete_rag(payload: AgentRagDeleteRequest, request: Request) -> AgentResponse:
        await _resolve_rag_service(request).delete_knowledge_base(payload.knowledge_base_id)
        return AgentResponse(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, code=100, status=RunStatus.COMPLETED,
            user_id=payload.user_id, session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE, state_version=0,
            answer=None, output=None, error=None,
        )

    @app.post("/v1/agent/skills", response_model=AgentResponse)
    async def skills_catalog(payload: AgentSkillRequest) -> AgentResponse:
        if payload.operation == "agent.skills.parse-jd" and payload.input_text is None:
            raise RequestError("agent.skills.parse-jd requires inputText")
        registry = SkillRegistry()
        output = ({"skills": registry.public_catalog()} if payload.operation == "agent.skills.list"
                  else {"categories": registry.categories_for_jd(payload.input_text)})
        return AgentResponse(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, code=100, status=RunStatus.COMPLETED,
            user_id=payload.user_id, session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE, state_version=0,
            answer=json.dumps(output, ensure_ascii=False), output=output, error=None,
        )

    @app.post("/v1/tools/web/fetch", response_model=AgentResponse)
    async def fetch_web(payload: AgentWebFetchRequest) -> AgentResponse:
        """Fetch and extract a single public HTML page for preview/import.

        No page content is executed or fed into system instructions.  The
        caller receives Markdown plus provenance so the upper layer can ask
        for an explicit confirmation before indexing it.
        """
        document = await fetch_public_article(payload.url)
        return AgentResponse(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, code=100, status=RunStatus.COMPLETED,
            user_id=payload.user_id, session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE, state_version=0,
            answer=document.title, output=document.as_dict(), error=None,
        )

    @app.post("/v1/tools/web/crawl", response_model=AgentResponse)
    async def crawl_web(payload: AgentWebCrawlRequest) -> AgentResponse:
        from app.agent.llm.factory import LLMFactory
        from app.agent.web_crawl_agent import WebCrawlPlanningAgent
        from app.engineering.reliability.policy import RetryPolicy
        from app.engineering.reliability.retry import AsyncRetryExecutor
        assessor = WebCrawlPlanningAgent(
            LLMFactory.create_chat_model(), PromptLoader(), AsyncRetryExecutor(RetryPolicy.load())
        )
        result = await crawl_public_site(payload.url, topic=payload.topic, assessor=assessor)
        return AgentResponse(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, code=100, status=RunStatus.COMPLETED,
            user_id=payload.user_id, session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE, state_version=0,
            answer=f"{len(result.pages)} valid pages", output=result.as_dict(), error=None,
        )

    @app.exception_handler(RequestValidationError)
    async def request_validation_error(request: Request, error: RequestValidationError) -> JSONResponse:
        body = error.body
        context = body if isinstance(body, Mapping) else None
        return await _error_json_response(
            request, RequestError("request validation failed"), http_status=400,
            context=context,
        )

    @app.exception_handler(ApplicationException)
    async def application_error(request: Request, error: ApplicationException) -> JSONResponse:
        await _mark_failed_interview_progress(request)
        return await _error_json_response(request, error, http_status=200)

    @app.exception_handler(Exception)
    async def unexpected_error(request: Request, error: Exception) -> JSONResponse:
        logger.exception("Unhandled lower-layer agent error")
        await _mark_failed_interview_progress(request)
        return await _error_json_response(request, error, http_status=500)

    return app


def _resolve_service(request: Request) -> InterviewAgentService:
    service = request.app.state.interview_agent_service
    if service is None:
        service = build_interview_agent_service()
        request.app.state.interview_agent_service = service
    return service


async def _mark_failed_interview_progress(request: Request) -> None:
    if request.url.path != "/v1/agent/respond":
        return
    context = await _request_context(request)
    session_id = _string_or_none(context.get("sessionId"))
    service = request.app.state.interview_agent_service
    marker = getattr(service, "mark_progress_failed", None)
    if session_id and callable(marker):
        marker(session_id)


def _resolve_rag_service(request: Request) -> RagService:
    service = request.app.state.rag_service
    if service is None:
        from app.bootstrap import build_rag_service
        service = build_rag_service()
        request.app.state.rag_service = service
    return service


def _resolve_resume_evaluator(request: Request) -> ResumeEvaluationAgent:
    evaluator = request.app.state.resume_evaluator
    if evaluator is None:
        evaluator = build_resume_evaluation_agent()
        request.app.state.resume_evaluator = evaluator
    return evaluator


def _resolve_memory_service(request: Request) -> MemoryService:
    service = request.app.state.memory_service
    if service is None:
        from app.bootstrap import build_memory_service
        service = build_memory_service()
        request.app.state.memory_service = service
    return service


def _success_response(*, api_version: str, request_id: str, run_id: str,
                      session: InterviewSession, answer: str | None = None,
                      output: dict[str, object] | None = None,
                      state_version: int | None = None,
                      session_status: SessionStatus | None = None,
                      turn_stage: str | None = None,
                      current_stage: str | None = None) -> AgentResponse:
    return AgentResponse(
        api_version=api_version, request_id=request_id, run_id=run_id,
        code=100, status=RunStatus.COMPLETED, user_id=session.user_id,
        session_id=session.session_id, session_status=session_status or session.status,
        state_version=state_version if state_version is not None else session.state_version,
        answer=answer if answer is not None else session.current_question,
        turn_stage=turn_stage,
        current_stage=current_stage or getattr(session, "current_stage", None),
        output=output, error=None,
    )


async def _request_context(request: Request) -> Mapping[str, object]:
    try:
        body = await request.body()
        parsed = json.loads(body) if body else {}
        return parsed if isinstance(parsed, dict) else {}
    except (json.JSONDecodeError, UnicodeDecodeError, RuntimeError):
        return {}


async def _error_response(
    request: Request, error: BaseException, context: Mapping[str, object] | None = None
) -> AgentResponse:
    resolved_context = context if context is not None else await _request_context(request)
    return AgentResponse(
        api_version=_string_or_none(resolved_context.get("apiVersion")),
        request_id=_string_or_none(resolved_context.get("requestId")),
        run_id=_string_or_none(resolved_context.get("runId")), code=ExceptionHandler.to_code(error),
        status=RunStatus.FAILED, user_id=_string_or_none(resolved_context.get("userId")),
        session_id=_string_or_none(resolved_context.get("sessionId")), session_status=SessionStatus.FAILED,
        state_version=0, answer=None, error=ExceptionHandler.to_error_info(error),
    )


def _string_or_none(value: object) -> str | None:
    return value if isinstance(value, str) and value.strip() else None


def _candidate_response_output(output: dict[str, object] | None) -> dict[str, object] | None:
    """Whitelist candidate-facing fields at the lower-layer boundary."""
    if not output:
        return None
    allowed = {
        "evaluationSummary", "evaluationScore", "strengths", "weaknesses", "currentPrimaryQuestionCount", "totalPrimaryQuestionCount",
        "currentFollowupCount", "totalQuestionCount", "questionBudget", "finalEvaluation",
    }
    visible = {key: value for key, value in output.items() if key in allowed}
    return visible or None


def _resume_evaluation_fingerprint(payload: AgentEvaluationRequest) -> str:
    canonical = json.dumps({
        "subjectId": payload.subject_id,
        "inputText": payload.input_text,
        "targetRole": payload.target_role,
    }, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


async def _error_json_response(
    request: Request,
    error: BaseException,
    *,
    http_status: int,
    context: Mapping[str, object] | None = None,
) -> JSONResponse:
    response = await _error_response(request, error, context)
    return JSONResponse(status_code=http_status, content=response.to_json_dict())


app = create_app()
