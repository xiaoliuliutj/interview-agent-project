"""FastAPI 入口与统一响应映射。"""

import json
import logging
from uuid import uuid4
from collections.abc import Mapping

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.agent.interview.models import CandidateProfile, InterviewSession
from app.agent.interview.service import InterviewAgentService
from app.agent.rag.models import RagUseCase
from app.agent.rag.models import KnowledgeDocument
from app.agent.rag.service import RagService
from app.agent.rag.answer import RagAnswerAgent
from app.agent.schedule.agent import ScheduleParseAgent
from app.bootstrap import build_interview_agent_service, build_resume_evaluation_agent
from app.agent.evaluation.agent import ResumeEvaluationAgent
from app.agent.skills.loader import SkillRegistry
from app.agent.memory.service import MemoryService
from app.core.contracts import (
    AgentInitializationRequest,
    AgentEvaluationRequest,
    AgentSessionCompletionRequest,
    AgentScheduleParseRequest,
    AgentRequest,
    AgentResponse,
    ErrorInfo,
    RunStatus,
    SessionStatus,
)
from app.core.exceptions import (
    ApplicationException,
    ExceptionHandler,
    RagConfigurationError,
    RequestError,
)


logger = logging.getLogger(__name__)


def create_app(
    service: InterviewAgentService | None = None,
    rag_service: RagService | None = None,
    resume_evaluator: ResumeEvaluationAgent | None = None,
    memory_service: MemoryService | None = None,
    rag_answer_agent: RagAnswerAgent | None = None,
    schedule_parse_agent: ScheduleParseAgent | None = None,
) -> FastAPI:
    app = FastAPI(title="Interview Agent Service", version="v1")
    app.state.interview_agent_service = service
    app.state.rag_service = rag_service
    app.state.resume_evaluator = resume_evaluator
    app.state.memory_service = memory_service
    app.state.rag_answer_agent = rag_answer_agent
    app.state.schedule_parse_agent = schedule_parse_agent

    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "UP"}

    @app.post("/v1/agent/sessions/initialize", response_model=AgentResponse)
    async def initialize_session(
        payload: AgentInitializationRequest, request: Request
    ) -> AgentResponse:
        current_service = _resolve_service(request)
        profile = CandidateProfile.model_validate(payload.candidate.model_dump())
        session = await current_service.initialize_session(
            user_id=payload.user_id,
            session_id=payload.session_id,
            profile=profile,
            run_id=payload.run_id,
        )
        return _success_response(
            api_version=payload.api_version,
            request_id=payload.request_id,
            run_id=payload.run_id,
            session=session,
        )

    @app.post("/v1/agent/respond", response_model=AgentResponse)
    async def respond(payload: AgentRequest, request: Request) -> AgentResponse:
        if payload.operation != "agent.respond":
            raise RequestError("operation 必须为 agent.respond")
        current_service = _resolve_service(request)
        result = await current_service.submit_answer_for_run(
            user_id=payload.user_id,
            session_id=payload.session_id,
            candidate_answer=payload.question,
            run_id=payload.run_id,
        )
        return _success_response(
            api_version=payload.api_version,
            request_id=payload.request_id,
            run_id=payload.run_id,
            session=result.session,
            answer=result.snapshot.answer,
            output=result.snapshot.output,
            state_version=result.snapshot.state_version,
            session_status=result.snapshot.session_status,
        )

    @app.post("/v1/agent/sessions/complete", response_model=AgentResponse)
    async def complete_session(
        payload: AgentSessionCompletionRequest, request: Request
    ) -> AgentResponse:
        current_service = _resolve_service(request)
        session = await (
            current_service.interrupt_session(user_id=payload.user_id, session_id=payload.session_id)
            if payload.operation == "agent.session.interrupt"
            else current_service.complete_session(user_id=payload.user_id, session_id=payload.session_id)
        )
        return _success_response(
            api_version=payload.api_version,
            request_id=payload.request_id,
            run_id=payload.run_id,
            session=session,
            output=(session.final_evaluation.model_dump(by_alias=True)
                    if getattr(session, "final_evaluation", None) is not None else None),
            state_version=session.state_version,
            session_status=session.status,
        )

    @app.post("/v1/agent/evaluate/resume", response_model=AgentResponse)
    async def evaluate_resume(
        payload: AgentEvaluationRequest, request: Request
    ) -> AgentResponse:
        evaluator = _resolve_resume_evaluator(request)
        result = await evaluator.evaluate(
            subject_id=payload.subject_id,
            input_text=payload.input_text,
            target_role=payload.target_role,
            knowledge_base_ids=tuple(payload.knowledge_base_ids or ()),
        )
        current_memory = _resolve_memory_service(request)
        if current_memory is not None:
            await current_memory.record_resume_analysis(
                user_id=payload.user_id,
                resume_id=payload.subject_id,
                candidate_id=payload.candidate_id or payload.subject_id,
                resume_text=payload.input_text,
                target_role=payload.target_role,
                summary=result.summary,
                questions=[item.question for item in result.issues],
                priorities=[item.priority for item in result.issues],
                suggestions=[item.suggestion for item in result.issues] + result.suggestions,
            )
        return AgentResponse(
            api_version=payload.api_version,
            request_id=payload.request_id,
            run_id=payload.run_id,
            code=100,
            status=RunStatus.COMPLETED,
            user_id=payload.user_id,
            session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE,
            state_version=0,
            answer=result.summary,
            output=result.model_dump(by_alias=True),
            error=None,
        )

    @app.post("/v1/agent/rag/search", response_model=AgentResponse)
    async def search_rag(payload: AgentRequest, request: Request) -> AgentResponse:
        if payload.operation != "rag.search":
            raise RequestError("operation 必须为 rag.search")
        current_rag_service = _resolve_rag_service(request)
        try:
            use_case = RagUseCase(payload.use_case or RagUseCase.QUESTION_GENERATION)
        except ValueError as error:
            raise RequestError("useCase 不受支持") from error
        results = await current_rag_service.search(
            payload.question,
            use_case=use_case,
            knowledge_base_ids=tuple(payload.knowledge_base_ids or ()),
        )
        answer_agent = _resolve_rag_answer_agent(request)
        if answer_agent is None:
            raise RagConfigurationError("RAG 回答 Agent 未配置，无法基于检索结果生成回答")
        answer = await answer_agent.answer(payload.question, results)
        sources = [
            {
                "score": result.score,
                "knowledgeBaseId": result.chunk.knowledge_base_id,
                "documentId": result.chunk.document_id,
                "sourceName": result.chunk.source_name,
                "metadata": result.chunk.metadata,
            }
            for result in results
        ]
        return AgentResponse(
            api_version=payload.api_version,
            request_id=payload.request_id,
            run_id=payload.run_id,
            code=100,
            status=RunStatus.COMPLETED,
            user_id=payload.user_id,
            session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE,
            state_version=0,
            answer=answer,
            output={"sources": sources},
            error=None,
        )

    @app.post("/v1/agent/rag/index", response_model=AgentResponse)
    async def index_rag(payload: AgentRequest, request: Request) -> AgentResponse:
        if payload.operation != "rag.index":
            raise RequestError("operation 必须为 rag.index")
        if not payload.knowledge_base_ids or len(payload.knowledge_base_ids) != 1:
            raise RequestError("rag.index 必须携带一个 knowledgeBaseIds")
        if not payload.document_id or not payload.source_name:
            raise RequestError("rag.index 缺少 documentId 或 sourceName")
        count = await _resolve_rag_service(request).index_document(
            KnowledgeDocument(
                knowledge_base_id=payload.knowledge_base_ids[0],
                document_id=payload.document_id,
                source_name=payload.source_name,
                content=payload.question,
            )
        )
        return AgentResponse(
            api_version=payload.api_version,
            request_id=payload.request_id,
            run_id=payload.run_id,
            code=100,
            status=RunStatus.COMPLETED,
            user_id=payload.user_id,
            session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE,
            state_version=0,
            answer=str(count),
            error=None,
        )

    @app.post("/v1/agent/skills", response_model=AgentResponse)
    async def skills_catalog(payload: AgentRequest) -> AgentResponse:
        if payload.operation not in {"agent.skills.list", "agent.skills.parse-jd"}:
            raise RequestError("operation 必须是 agent.skills.list 或 agent.skills.parse-jd")
        registry = SkillRegistry()
        if payload.operation == "agent.skills.list":
            output = {"skills": registry.public_catalog()}
        else:
            output = {"categories": registry.categories_for_jd(payload.question)}
        return AgentResponse(
            api_version=payload.api_version,
            request_id=payload.request_id,
            run_id=payload.run_id,
            code=100,
            status=RunStatus.COMPLETED,
            user_id=payload.user_id,
            session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE,
            state_version=0,
            answer=json.dumps(output, ensure_ascii=False),
            output=output,
            error=None,
        )

    @app.post("/v1/agent/schedule/parse", response_model=AgentResponse)
    async def parse_schedule(
        payload: AgentScheduleParseRequest, request: Request
    ) -> AgentResponse:
        result = await _resolve_schedule_parse_agent(request).parse(
            payload.input_text, payload.timezone_name
        )
        return AgentResponse(
            api_version=payload.api_version,
            request_id=payload.request_id,
            run_id=payload.run_id,
            code=100,
            status=RunStatus.COMPLETED,
            user_id=payload.user_id,
            session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE,
            state_version=0,
            answer=result.title,
            output=result.model_dump(mode="json", by_alias=True),
            error=None,
        )

    @app.exception_handler(RequestValidationError)
    async def request_validation_error(
        request: Request, error: RequestValidationError
    ) -> JSONResponse:
        return await _error_json_response(
            request,
            RequestError("请求参数不合法"),
            http_status=400,
        )

    @app.exception_handler(ApplicationException)
    async def application_error(
        request: Request, error: ApplicationException
    ) -> JSONResponse:
        return await _error_json_response(request, error, http_status=200)

    @app.exception_handler(Exception)
    async def unexpected_error(request: Request, error: Exception) -> JSONResponse:
        logger.exception("Unhandled lower-layer agent error")
        return await _error_json_response(request, error, http_status=500)

    return app


def _resolve_service(request: Request) -> InterviewAgentService:
    service = request.app.state.interview_agent_service
    if service is None:
        service = build_interview_agent_service()
        request.app.state.interview_agent_service = service
    return service


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


def _resolve_memory_service(request: Request) -> MemoryService | None:
    service = request.app.state.memory_service
    if service is not None:
        return service
    # Resume evaluation may be used without a configured database in local tests.
    try:
        from app.bootstrap import build_memory_service
        service = build_memory_service()
        request.app.state.memory_service = service
        return service
    except Exception:
        return None


def _resolve_rag_answer_agent(request: Request) -> RagAnswerAgent | None:
    agent = request.app.state.rag_answer_agent
    if agent is not None:
        return agent
    try:
        from app.bootstrap import build_rag_answer_agent
        agent = build_rag_answer_agent()
        request.app.state.rag_answer_agent = agent
        return agent
    except Exception:
        return None


def _resolve_schedule_parse_agent(request: Request) -> ScheduleParseAgent:
    agent = request.app.state.schedule_parse_agent
    if agent is None:
        from app.bootstrap import build_schedule_parse_agent

        agent = build_schedule_parse_agent()
        request.app.state.schedule_parse_agent = agent
    return agent


def _success_response(
    *,
    api_version: str,
    request_id: str,
    run_id: str,
    session: InterviewSession,
    answer: str | None = None,
    output: dict[str, object] | None = None,
    state_version: int | None = None,
    session_status: SessionStatus | None = None,
) -> AgentResponse:
    return AgentResponse(
        api_version=api_version,
        request_id=request_id,
        run_id=run_id,
        code=100,
        status=RunStatus.COMPLETED,
        user_id=session.user_id,
        session_id=session.session_id,
        session_status=session_status or session.status,
        state_version=state_version if state_version is not None else session.state_version,
        answer=answer if answer is not None else session.current_question,
        output=output,
        error=None,
    )


async def _request_context(request: Request) -> Mapping[str, object]:
    try:
        body = await request.body()
        parsed = json.loads(body) if body else {}
        return parsed if isinstance(parsed, dict) else {}
    except (json.JSONDecodeError, UnicodeDecodeError):
        return {}


async def _error_response(request: Request, error: BaseException) -> AgentResponse:
    context = await _request_context(request)
    return AgentResponse(
        api_version=str(context.get("apiVersion", "v1")),
        request_id=str(context.get("requestId", "invalid-request")),
        run_id=str(context.get("runId", "invalid-run")),
        code=ExceptionHandler.to_code(error),
        status=RunStatus.FAILED,
        user_id=str(context.get("userId") or uuid4()),
        session_id=str(context.get("sessionId") or uuid4()),
        session_status=SessionStatus.FAILED,
        state_version=0,
        answer=None,
        error=ExceptionHandler.to_error_info(error),
    )


async def _error_json_response(
    request: Request, error: BaseException, *, http_status: int
) -> JSONResponse:
    response = await _error_response(request, error)
    return JSONResponse(status_code=http_status, content=response.to_json_dict())


app = create_app()
