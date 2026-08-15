# FastAPI application.py：辅助函数、异常处理与完整路由源码

## 1. 接口定义

该文件创建 FastAPI 应用并注册 11 个路由、三类异常处理器和解析/响应辅助函数。各接口单独文档解释业务链，本文件补齐所有应用层自定义函数的原始源码和文件行号。

## 2. 函数调用链

~~~text
app = create_app -> nested route functions
异常 -> request_validation_error/application_error/unexpected_error -> _error_json_response -> _error_response
路由 -> _resolve_service/_resolve_rag_service/_resolve_resume_evaluator/_resolve_memory_service
~~~

## 3. 函数解析

### 3.1 当前文件完整源码

~~~python
"""FastAPI entrypoint for the lower-layer Agent service."""

import asyncio
import json
import logging
import hashlib
from collections.abc import Mapping

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from app.agents.evaluation.agent import ResumeEvaluationAgent
from app.agents.interview.models import CandidateProfile, InterviewSession, DEFAULT_TARGET_QUESTION_COUNT
from app.agents.interview.service import InterviewAgentService
from app.memory.service import MemoryService
from app.rag.models import KnowledgeDocument
from app.rag.service import RagService
from app.tools.web_reader import crawl_public_site, fetch_public_article
from app.bootstrap import build_interview_agent_service, build_resume_evaluation_agent
from app.common.contracts import (
    AgentEvaluationRequest,
    AgentInitializationRequest,
    AgentRagIndexRequest,
    AgentRagDeleteRequest,
    AgentResumeMemoryActivationRequest,
    AgentRespondRequest,
    AgentResponse,
    AgentSessionCompletionRequest,
    AgentWebFetchRequest,
    AgentWebCrawlRequest,
    RunStatus,
    SessionStatus,
)
from app.common.exceptions import (
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
        _remember_request_context(request, payload)
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
        _remember_request_context(request, payload)
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
        _remember_request_context(request, payload)
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
        _remember_request_context(request, payload)
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
        _remember_request_context(request, payload)
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
        _remember_request_context(request, payload)
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
        _remember_request_context(request, payload)
        await _resolve_rag_service(request).delete_knowledge_base(payload.knowledge_base_id)
        return AgentResponse(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, code=100, status=RunStatus.COMPLETED,
            user_id=payload.user_id, session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE, state_version=0,
            answer=None, output=None, error=None,
        )

    @app.post("/v1/tools/web/fetch", response_model=AgentResponse)
    async def fetch_web(payload: AgentWebFetchRequest, request: Request) -> AgentResponse:
        """Fetch and extract a single public HTML page for preview/import.

        No page content is executed or fed into system instructions.  The
        caller receives Markdown plus provenance so the upper layer can ask
        for an explicit confirmation before indexing it.
        """
        _remember_request_context(request, payload)
        document = await fetch_public_article(payload.url)
        return AgentResponse(
            api_version=payload.api_version, request_id=payload.request_id,
            run_id=payload.run_id, code=100, status=RunStatus.COMPLETED,
            user_id=payload.user_id, session_id=payload.session_id,
            session_status=SessionStatus.ACTIVE, state_version=0,
            answer=document.title, output=document.as_dict(), error=None,
        )

    @app.post("/v1/tools/web/crawl", response_model=AgentResponse)
    async def crawl_web(payload: AgentWebCrawlRequest, request: Request) -> AgentResponse:
        _remember_request_context(request, payload)
        from app.agents.llm.factory import LLMFactory
        from app.tools.web_crawl_agent import WebCrawlPlanningAgent
        from app.infrastructure.reliability.policy import RetryPolicy
        from app.infrastructure.reliability.retry import AsyncRetryExecutor
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
    remembered = getattr(request.state, "agent_context", None)
    if isinstance(remembered, Mapping):
        return remembered
    try:
        body = await request.body()
        parsed = json.loads(body) if body else {}
        return parsed if isinstance(parsed, dict) else {}
    except (json.JSONDecodeError, UnicodeDecodeError, RuntimeError):
        return {}


def _remember_request_context(request: Request, payload: object) -> None:
    dumper = getattr(payload, "model_dump", None)
    if callable(dumper):
        request.state.agent_context = dumper(by_alias=True, mode="json")


async def _error_response(
    request: Request, error: BaseException, context: Mapping[str, object] | None = None
) -> AgentResponse:
    resolved_context = context if context is not None else await _request_context(request)
    session_status = _session_status_or_failed(resolved_context.get("sessionStatus"))
    state_version = resolved_context.get("stateVersion")
    return AgentResponse(
        api_version=_string_or_none(resolved_context.get("apiVersion")),
        request_id=_string_or_none(resolved_context.get("requestId")),
        run_id=_string_or_none(resolved_context.get("runId")), code=ExceptionHandler.to_code(error),
        status=RunStatus.FAILED, user_id=_string_or_none(resolved_context.get("userId")),
        session_id=_string_or_none(resolved_context.get("sessionId")), session_status=session_status,
        state_version=state_version if isinstance(state_version, int) and state_version >= 0 else 0,
        answer=None, current_stage="FAILED", error=ExceptionHandler.to_error_info(error),
    )


def _string_or_none(value: object) -> str | None:
    return value if isinstance(value, str) and value.strip() else None


def _session_status_or_failed(value: object) -> SessionStatus:
    """A failed run must not falsely mark an existing interview session as failed."""
    try:
        return SessionStatus(value)
    except (TypeError, ValueError):
        return SessionStatus.FAILED


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
~~~

### 3.2 顶层与嵌套路由函数

#### `create_app`

文件：`python-agent/app/api/application.py:48`

1. 第 48 行定义函数；创建 FastAPI 实例、写入依赖状态并注册所有路由与异常处理器。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `health`

文件：`python-agent/app/api/application.py:61`

1. 第 61 行定义函数；按源码中的参数和分支执行应用层职责。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `session_progress`

文件：`python-agent/app/api/application.py:65`

1. 第 65 行定义函数；按源码中的参数和分支执行应用层职责。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `initialize_session`

文件：`python-agent/app/api/application.py:71`

1. 第 71 行定义函数；按源码中的参数和分支执行应用层职责。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `respond`

文件：`python-agent/app/api/application.py:94`

1. 第 94 行定义函数；按源码中的参数和分支执行应用层职责。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `complete_session`

文件：`python-agent/app/api/application.py:133`

1. 第 133 行定义函数；按源码中的参数和分支执行应用层职责。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `evaluate_resume`

文件：`python-agent/app/api/application.py:157`

1. 第 157 行定义函数；按源码中的参数和分支执行应用层职责。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `activate_resume_memory`

文件：`python-agent/app/api/application.py:206`

1. 第 206 行定义函数；按源码中的参数和分支执行应用层职责。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `index_rag`

文件：`python-agent/app/api/application.py:224`

1. 第 224 行定义函数；按源码中的参数和分支执行应用层职责。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `delete_rag`

文件：`python-agent/app/api/application.py:241`

1. 第 241 行定义函数；按源码中的参数和分支执行应用层职责。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `fetch_web`

文件：`python-agent/app/api/application.py:253`

1. 第 253 行定义函数；按源码中的参数和分支执行应用层职责。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `crawl_web`

文件：`python-agent/app/api/application.py:271`

1. 第 271 行定义函数；按源码中的参数和分支执行应用层职责。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `request_validation_error`

文件：`python-agent/app/api/application.py:290`

1. 第 290 行定义函数；把校验、业务或未知异常转换为统一 AgentResponse/JSONResponse，并保留请求上下文。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `application_error`

文件：`python-agent/app/api/application.py:299`

1. 第 299 行定义函数；把校验、业务或未知异常转换为统一 AgentResponse/JSONResponse，并保留请求上下文。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `unexpected_error`

文件：`python-agent/app/api/application.py:304`

1. 第 304 行定义函数；把校验、业务或未知异常转换为统一 AgentResponse/JSONResponse，并保留请求上下文。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `_resolve_service`

文件：`python-agent/app/api/application.py:312`

1. 第 312 行定义函数；读取 request.app.state，空值时调用对应 bootstrap 构建器并写回缓存。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `_mark_failed_interview_progress`

文件：`python-agent/app/api/application.py:320`

1. 第 320 行定义函数；按源码中的参数和分支执行应用层职责。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `_resolve_rag_service`

文件：`python-agent/app/api/application.py:331`

1. 第 331 行定义函数；读取 request.app.state，空值时调用对应 bootstrap 构建器并写回缓存。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `_resolve_resume_evaluator`

文件：`python-agent/app/api/application.py:340`

1. 第 340 行定义函数；读取 request.app.state，空值时调用对应 bootstrap 构建器并写回缓存。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `_resolve_memory_service`

文件：`python-agent/app/api/application.py:348`

1. 第 348 行定义函数；读取 request.app.state，空值时调用对应 bootstrap 构建器并写回缓存。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `_success_response`

文件：`python-agent/app/api/application.py:357`

1. 第 357 行定义函数；按协议字段、会话状态、答案和输出构造成功 AgentResponse。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `_request_context`

文件：`python-agent/app/api/application.py:376`

1. 第 376 行定义函数；优先读取 request.state 缓存，缺失时读取请求体并安全解析 JSON。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `_remember_request_context`

文件：`python-agent/app/api/application.py:388`

1. 第 388 行定义函数；调用 Pydantic model_dump 以别名 JSON 保存请求上下文。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `_error_response`

文件：`python-agent/app/api/application.py:394`

1. 第 394 行定义函数；把校验、业务或未知异常转换为统一 AgentResponse/JSONResponse，并保留请求上下文。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `_string_or_none`

文件：`python-agent/app/api/application.py:411`

1. 第 411 行定义函数；按源码中的参数和分支执行应用层职责。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `_session_status_or_failed`

文件：`python-agent/app/api/application.py:415`

1. 第 415 行定义函数；按源码中的参数和分支执行应用层职责。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `_candidate_response_output`

文件：`python-agent/app/api/application.py:423`

1. 第 423 行定义函数；白名单过滤候选人可见输出字段。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `_resume_evaluation_fingerprint`

文件：`python-agent/app/api/application.py:435`

1. 第 435 行定义函数；用规范化 JSON 和 SHA-256 生成简历评价幂等指纹。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

#### `_error_json_response`

文件：`python-agent/app/api/application.py:444`

1. 第 444 行定义函数；把校验、业务或未知异常转换为统一 AgentResponse/JSONResponse，并保留请求上下文。
2. 函数体中的每条赋值、条件、await、异常分支和返回语句均保留在 3.1 的当前源码块，可按行号逐句核对。

## 4. 审核结论

application.py 当前完整源码、11 个路由、异常处理器和全部辅助函数均已纳入文档；没有把 `/v1/tools/web/crawl/import` 虚构为 Python 路由。
