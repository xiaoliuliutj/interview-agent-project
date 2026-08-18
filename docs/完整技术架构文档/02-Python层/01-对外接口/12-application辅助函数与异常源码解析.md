# FastAPI application.py：辅助函数、异常处理与完整路由源码

## 1. 接口定义

该文件不是单独一个 HTTP 接口，而是 Python 服务的 FastAPI 应用工厂和统一应用层边界。它创建应用、保存四类服务依赖、注册 11 个路由、注册三类异常处理器，并提供服务解析、成功/失败响应、请求上下文、输出白名单和幂等指纹辅助函数。

| HTTP 方法 | 路径 | 路由函数 |
|---|---|---|
| GET | `/health` | `health` |
| GET | `/v1/agent/sessions/{session_id}/progress` | `session_progress` |
| POST | `/v1/agent/sessions/initialize` | `initialize_session` |
| POST | `/v1/agent/respond` | `respond` |
| POST | `/v1/agent/sessions/complete` | `complete_session` |
| POST | `/v1/agent/evaluate/resume` | `evaluate_resume` |
| POST | `/v1/agent/resume/activate` | `activate_resume_memory` |
| POST | `/v1/agent/rag/index` | `index_rag` |
| POST | `/v1/agent/rag/delete` | `delete_rag` |
| POST | `/v1/tools/web/fetch` | `fetch_web` |
| POST | `/v1/tools/web/crawl` | `crawl_web` |

应用工厂文件：`python-agent/app/api/application.py:48-458`。

## 2. 函数调用链

~~~text
模块导入
 -> app = create_app
    -> 创建 FastAPI
    -> 保存 interview_agent_service / rag_service / resume_evaluator / memory_service
    -> 注册 health / session_progress / initialize_session / respond
    -> 注册 complete_session / evaluate_resume / activate_resume_memory
    -> 注册 index_rag / delete_rag / fetch_web / crawl_web
    -> 注册 request_validation_error / application_error / unexpected_error

GET /health
 -> health

GET /v1/agent/sessions/{session_id}/progress
 -> session_progress -> _resolve_service
    -> [冷启动] build_interview_agent_service
 -> InterviewAgentService.progress_for_async
 -> [无异步方法] InterviewAgentService.progress_for

POST /v1/agent/sessions/initialize
 -> initialize_session -> _remember_request_context -> _resolve_service
 -> InterviewAgentService.initialize_session -> _success_response
 -> AgentResponse.validate_code_category

POST /v1/agent/respond
 -> respond -> _remember_request_context -> _resolve_service
 -> InterviewAgentService.submit_answer_for_run
 -> _candidate_response_output -> _success_response
 -> AgentResponse.validate_code_category
 -> [超时或 BaseException] InterviewAgentService.mark_progress_failed

POST /v1/agent/sessions/complete
 -> complete_session -> _remember_request_context -> _resolve_service
 -> [pause operation] InterviewAgentService.pause_session
 -> [其他 operation] InterviewAgentService.complete_session
 -> _success_response -> AgentResponse.validate_code_category

POST /v1/agent/evaluate/resume
 -> evaluate_resume -> _remember_request_context -> _resume_evaluation_fingerprint
 -> _resolve_memory_service -> MemoryService.get_resume_evaluation_run
 -> [未命中] _resolve_resume_evaluator -> ResumeEvaluationAgent.evaluate
 -> MemoryService.record_resume_analysis
 -> [ConsistencyError] MemoryService.get_resume_evaluation_run
 -> AgentResponse.validate_code_category

POST /v1/agent/resume/activate
 -> activate_resume_memory -> _remember_request_context
 -> _resolve_memory_service -> MemoryService.activate_resume
 -> AgentResponse.validate_code_category

POST /v1/agent/rag/index
 -> index_rag -> _remember_request_context -> _resolve_rag_service
 -> RagService.index_document -> AgentResponse.validate_code_category

POST /v1/agent/rag/delete
 -> delete_rag -> _remember_request_context -> _resolve_rag_service
 -> RagService.delete_knowledge_base -> AgentResponse.validate_code_category

POST /v1/tools/web/fetch
 -> fetch_web -> _remember_request_context -> fetch_public_article
 -> WebDocument.as_dict -> AgentResponse.validate_code_category

POST /v1/tools/web/crawl
 -> crawl_web -> _remember_request_context
 -> LLMFactory.create_chat_model / PromptLoader / RetryPolicy.load / AsyncRetryExecutor
 -> WebCrawlPlanningAgent -> crawl_public_site
 -> CrawlResult.as_dict -> AgentResponse.validate_code_category

请求模型校验失败
 -> request_validation_error -> RequestError
 -> _error_json_response -> _error_response
 -> [_error_response 未收到上下文] _request_context
 -> _session_status_or_failed / _string_or_none
 -> ExceptionHandler.to_code / ExceptionHandler.to_error_info
 -> AgentResponse.validate_code_category -> AgentResponse.to_json_dict

ApplicationException
 -> application_error -> _mark_failed_interview_progress
 -> [respond 路径] _request_context -> _string_or_none
 -> InterviewAgentService.mark_progress_failed
 -> _error_json_response -> _error_response
 -> ExceptionHandler.to_code / ExceptionHandler.to_error_info
 -> AgentResponse.validate_code_category -> AgentResponse.to_json_dict

其他 Exception
 -> unexpected_error -> logger.exception
 -> _mark_failed_interview_progress
 -> [respond 路径] _request_context -> _string_or_none
 -> InterviewAgentService.mark_progress_failed
 -> _error_json_response -> _error_response
 -> [_error_response 未收到上下文] _request_context
 -> _session_status_or_failed / _string_or_none
 -> ExceptionHandler.to_code / ExceptionHandler.to_error_info
 -> AgentResponse.validate_code_category -> AgentResponse.to_json_dict
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
        async_progress = getattr(service, "progress_for_async", None)
        if callable(async_progress):
            return {"stage": await async_progress(session_id)}
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
        from app.agents.web_crawl.agent import WebCrawlPlanningAgent
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

### 3.2 `create_app`

文件：`python-agent/app/api/application.py:48-312`

1. 第 48 行：定义 FastAPI 应用工厂并开始多行签名。
2. 第 49 行：接收可选 `InterviewAgentService`，用于测试注入或外部装配。
3. 第 50 行：接收可选 `RagService`。
4. 第 51 行：接收可选 `ResumeEvaluationAgent`。
5. 第 52 行：接收可选 `MemoryService`。
6. 第 53 行：声明返回 `FastAPI` 实例并结束函数签名。
7. 第 54 行：创建标题为 Interview Agent Service、版本为 v1 的 FastAPI 应用。
8. 第 55 行：把面试 Agent 服务写入 `app.state.interview_agent_service`。
9. 第 56 行：把 RAG 服务写入 `app.state.rag_service`。
10. 第 57 行：把简历评价 Agent 写入 `app.state.resume_evaluator`。
11. 第 58 行：把记忆服务写入 `app.state.memory_service`。
12. 第 60 行：注册健康检查路由；对应函数在 3.3 逐行解析。
13. 第 64 行：注册会话进度查询路由；对应函数在 3.4 逐行解析。
14. 第 73 行：注册面试初始化路由；对应函数在 3.5 逐行解析。
15. 第 96 行：注册回答提交路由；对应函数在 3.6 逐行解析。
16. 第 135 行：注册会话暂停/完成路由；对应函数在 3.7 逐行解析。
17. 第 159 行：注册简历评价路由；对应函数在 3.8 逐行解析。
18. 第 208 行：注册简历记忆激活路由；对应函数在 3.9 逐行解析。
19. 第 226 行：注册 RAG 索引路由；对应函数在 3.10 逐行解析。
20. 第 243 行：注册 RAG 删除路由；对应函数在 3.11 逐行解析。
21. 第 255 行：注册单页抓取路由；对应函数在 3.12 逐行解析。
22. 第 273 行：注册站点抓取路由；对应函数在 3.13 逐行解析。
23. 第 292 行：注册请求校验异常处理器；对应函数在后续异常章节逐行解析。
24. 第 301 行：注册项目异常处理器；对应函数在后续异常章节逐行解析。
25. 第 306 行：注册兜底异常处理器；对应函数在后续异常章节逐行解析。
26. 第 312 行：全部路由和异常处理器注册完成后返回应用实例。

### 3.3 `health`

文件：`python-agent/app/api/application.py:60-62`

```python
    @app.get("/health")
    async def health() -> dict[str, str]:
        return {"status": "UP"}
```

逐行解释：

1. 第 60 行：`@app.get` 把下一 个异步函数注册为 GET `/health` 路由；装饰器本身由 FastAPI 执行，项目代码提供路径和函数绑定。
2. 第 61 行：定义无参数异步函数 `health`，返回类型标注为字符串键和值组成的字典；请求体、鉴权信息和会话信息均不参与该函数。
3. 第 62 行：构造并返回状态字典，值固定为 `UP`；FastAPI 随后把该 Python 字典编码为 HTTP 200 JSON 响应。

### 3.4 `session_progress`

文件：`python-agent/app/api/application.py:64-71`。

```python
    @app.get("/v1/agent/sessions/{session_id}/progress")
    async def session_progress(session_id: str, request: Request) -> dict[str, str]:
        service = _resolve_service(request)
        async_progress = getattr(service, "progress_for_async", None)
        if callable(async_progress):
            return {"stage": await async_progress(session_id)}
        progress = getattr(service, "progress_for", None)
        return {"stage": progress(session_id) if callable(progress) else "IDLE"}
```

1. 第 64 行注册带 `session_id` 路径参数的 GET 路由。
2. 第 65 行声明异步函数，FastAPI 将路径参数和 Request 注入。
3. 第 66 行调用 `_resolve_service` 获取应用级面试服务。
4. 第 67 行通过 `getattr` 读取可选 `progress_for_async`，允许测试替身没有异步方法。
5. 第 68 行：检查异步进度方法是否可调用。
6. 第 69 行：可调用时等待它返回阶段并立即包装成字典；真实生产服务进入此分支。
7. 第 70 行：异步方法不存在时读取可选同步 `progress_for`。
8. 第 71 行：同步方法可调用时传入 sessionId，否则回退为 `IDLE`，最后包装成字典返回。

### 3.5 `initialize_session`

文件：`python-agent/app/api/application.py:73-94`

```python
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
```

逐行解释：

1. 第 73 行：注册 POST 路由并声明响应模型；FastAPI 会在返回前按 `AgentResponse` 校验和序列化。
2. 第 74 行：声明异步入口，Pydantic 已把请求体解析为 `AgentInitializationRequest`。
3. 第 75 行：调用 `_remember_request_context` 保存别名字段形式的请求上下文，为异常响应提供 requestId、runId 和会话信息。
4. 第 76 行：解析面试服务并异步调用其初始化方法。
5. 第 77 行：把候选人所属用户 ID 传入服务，用于权限和长期记忆隔离。
6. 第 78 行：把请求中的会话 ID 传入，服务据此做幂等读取和持久化。
7. 第 79 行：开始构造领域层 `CandidateProfile`，而不是把接口 DTO 直接传进 Agent。
8. 第 80 行：复制候选人子模型字段，并强制覆盖 `question_count` 为项目默认题量，防止上层绕过题量策略。
9. 第 81 行：结束 `model_validate` 调用；Pydantic 在此执行类型和字段校验。
10. 第 79 行：把 runId 传给服务，用于同一初始化请求的幂等快照。
11. 第 80 行：结束异步服务调用，得到已创建或幂等返回的 `InterviewSession`。
12. 第 81 行：进入统一成功响应构造函数。
13. 第 82 行：透传 API 版本和请求 ID。
14. 第 83 行：透传运行 ID 和会话对象。
15. 第 84 行：开始组装候选人可见的题量统计输出。
16. 第 85 行：读取当前主问题数；兼容缺少字段的替身对象时回退为 1。
17. 第 86 行：读取累计主问题数，缺失时回退为 1。
18. 第 87 行：读取当前追问数，缺失时回退为 0。
19. 第 88 行：读取总问题数，缺失时回退为 1。
20. 第 89 行：读取服务计算的题量预算，缺失时返回 `None`。
21. 第 90 行：结束统计字典。
22. 第 91 行：结束 `_success_response` 调用并返回 `AgentResponse`。

### 3.6 `respond`

文件：`python-agent/app/api/application.py:96-133`

```python
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
```

逐行解释：

1. 第 96 行：`@app.post` 把 HTTP `POST /v1/agent/respond` 注册到 FastAPI，并指定返回值必须通过 `AgentResponse` 校验与序列化。
2. 第 97 行：定义异步路由函数；`payload` 已经由 `AgentRespondRequest` 完成字段、枚举与别名校验，`request` 提供应用状态和请求上下文。
3. 第 98 行：调用项目函数 `_remember_request_context`，把已校验请求按 JSON 别名保存到 `request.state`，使后续异常响应仍能恢复 `requestId`、`runId`、`sessionId` 等字段。
4. 第 99 行：调用项目函数 `_resolve_service`；优先取应用内已缓存的 `InterviewAgentService`，冷启动时才组装真实依赖。
5. 第 100 行：进入同时覆盖服务调用和总超时转换的 `try` 区域。
6. 第 101 行：调用标准库 `asyncio.wait_for`，把整轮面试处理协程置于 150 秒的接口级时间边界内。
7. 第 102 行：调用项目函数 `InterviewAgentService.submit_answer_for_run`；该公开入口继续委托 `_submit_answer` 执行业务状态机。
8. 第 103 行：把请求中的 `userId` 传为 `user_id`，供服务层校验会话所有者。
9. 第 104 行：传入 `sessionId`，用于读取唯一面试会话。
10. 第 105 行：把本轮候选人答案传入 `candidate_answer`，后续会用于评价、记录和幂等校验。
11. 第 106 行：传入 `runId`；服务层用它保存或命中本轮快照，防止重复请求再次调用模型。
12. 第 107 行：传入上层看到的 `sessionStatus`，作为跨服务状态一致性检查的一部分。
13. 第 108 行：传入上层看到的 `stateVersion`，与数据库乐观锁版本共同阻止陈旧请求覆盖新状态。
14. 第 109 行：结束 `submit_answer_for_run` 参数列表，得到一个待等待的协程对象。
15. 第 110 行：把 `INTERVIEW_TURN_TIMEOUT_SECONDS` 作为 `wait_for` 的 `timeout`；该常量当前为 `150.0` 秒。
16. 第 111 行：结束等待表达式；成功结果保存到 `result`，其中包含保存后的会话和本轮不可变快照。
17. 第 112 行：捕获由接口级 `wait_for` 抛出的 `TimeoutError`，并将原异常绑定为 `error`。
18. 第 113 行：通过 `getattr` 读取 `mark_progress_failed`，这样测试替身即使没有该方法也不会在异常转换阶段再次失败。
19. 第 114 行：用 `callable` 判断读取到的属性确实可调用。
20. 第 115 行：调用项目函数 `mark_progress_failed(sessionId)`，把本机进度和可用的 Redis 进度快照标为 `FAILED`。
21. 第 116 行：开始构造项目异常 `AgentDependencyError`。
22. 第 117 行：给出明确的 150 秒超时提示，并要求调用方保留当前回答。
23. 第 118 行：设置 `retryable=False`；接口无法确认被取消协程的外部副作用，因而不鼓励上层自动重复提交。
24. 第 119 行：以 `raise ... from error` 抛出转换后的应用异常，同时保留原始超时异常链。
25. 第 120 行：捕获其余 `BaseException`，包含普通异常以及协程取消等不属于 `Exception` 的控制流异常。
26. 第 121 行：再次兼容性读取 `mark_progress_failed`。
27. 第 122 行：确认失败标记属性可调用。
28. 第 123 行：把当前会话进度标为 `FAILED`，避免进度接口长期停留在某个处理中阶段。
29. 第 124 行：不改变异常类型，原样重新抛出并交给 FastAPI 异常处理器或上层取消逻辑。
30. 第 125 行：正常路径调用项目函数 `_success_response` 构造统一协议响应。
31. 第 126 行：传回请求中的 `apiVersion` 与 `requestId`，用于上下层协议关联。
32. 第 127 行：传回 `runId`，并把服务层保存后的 `result.session` 交给响应构造器读取用户和会话标识。
33. 第 128 行：使用快照中的下一题/总结作为 `answer`；同时调用项目函数 `_candidate_response_output` 对服务层输出再次执行候选人字段白名单过滤。
34. 第 129 行：使用快照版本而不是临时会话版本，确保返回值与该 `runId` 的幂等结果完全一致。
35. 第 130 行：使用快照中的会话状态，使重复请求也返回首次执行时的状态。
36. 第 131 行：返回被评价答案所属的 `turnStage`。
37. 第 132 行：读取快照的 `current_stage`；`getattr(..., None)` 兼容升级前持久化的旧快照。
38. 第 133 行：结束 `_success_response` 调用并返回 `AgentResponse`。

### 3.7 `complete_session` 路由函数

文件：`python-agent/app/api/application.py:135-157`

```python
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
```

逐行解释：

1. 第 135 行：`@app.post` 注册 HTTP `POST /v1/agent/sessions/complete`，并要求响应通过 `AgentResponse` 校验。
2. 第 136 行：定义异步路由；`payload` 已由 `AgentSessionCompletionRequest` 校验，`request` 提供应用状态与异常上下文。
3. 第 137 行：调用项目函数 `_remember_request_context`，按协议别名保存已校验请求，供异常处理恢复关联字段。
4. 第 138 行：调用项目函数 `_resolve_service` 取得进程级 `InterviewAgentService`；冷启动时组装真实依赖。
5. 第 139 行：开始条件表达式；暂停分支等待 `service.pause_session`。
6. 第 140 行：传入 userId 与 sessionId，暂停服务会校验会话归属。
7. 第 141 行：传入上层观测到的 sessionStatus。
8. 第 142 行：传入上层观测到的 stateVersion。
9. 第 143 行：只有 operation 精确等于 `agent.session.pause` 才选择暂停分支。
10. 第 144 行：其余已通过请求模型校验的操作等待 `service.complete_session`。
11. 第 145 行：完成分支传入相同 userId 和 sessionId。
12. 第 146 行：传入预期会话状态。
13. 第 147 行：传入预期状态版本。
14. 第 148 行：结束条件表达式，任一分支返回的持久化会话保存为 `session`。
15. 第 149 行：调用项目函数 `_success_response` 构造统一协议响应。
16. 第 150 行：复制 apiVersion 与 requestId。
17. 第 151 行：复制 runId，并传入服务层返回的会话；用户和 sessionId 将从会话读取。
18. 第 152 行：开始构造可选 output。
19. 第 153 行：最终评价存在时按字段别名序列化并包装为 `finalEvaluation`。
20. 第 154 行：通过 `getattr(..., None)` 兼容旧会话缺少字段；暂停分支通常返回 `None`。
21. 第 155 行：结束条件输出表达式。
22. 第 156 行：显式返回数据库保存后的 stateVersion 和 sessionStatus。
23. 第 157 行：结束并返回 `AgentResponse`。

### 3.8 `evaluate_resume`

文件：`python-agent/app/api/application.py:159-206`

```python
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
```

逐行解释：

1. 第 159 行：注册 HTTP `POST /v1/agent/evaluate/resume`，响应模型为 `AgentResponse`。
2. 第 160 行：定义异步路由，接收已校验 `AgentEvaluationRequest` 和 FastAPI Request。
3. 第 161 行：调用 `_remember_request_context` 保存协议上下文。
4. 第 162 行：调用 `_resume_evaluation_fingerprint(payload)` 计算业务输入指纹。
5. 第 163 行：调用 `_resolve_memory_service` 取得或冷启动长期记忆服务。
6. 第 164 行：调用 `MemoryService.get_resume_evaluation_run` 查询 runId 快照。
7. 第 165 行：传入用户 ID 和作为 resumeId 使用的 subjectId。
8. 第 166 行：传入 runId 和当前输入指纹。
9. 第 167 行：结束查询，结果可能为 `ResumeEvaluation` 或 `None`。
10. 第 168 行：只有幂等快照未命中时才调用模型。
11. 第 169 行：调用 `_resolve_resume_evaluator(request)` 并继续调用 `ResumeEvaluationAgent.evaluate`。
12. 第 170 行：传入 subjectId。
13. 第 171 行：传入简历原文。
14. 第 172 行：传入目标岗位。
15. 第 173 行：得到结构化评价。
16. 第 174 行：进入长期记忆保存的并发保护。
17. 第 175 行：调用 `MemoryService.record_resume_analysis`。
18. 第 176 行：传入 userId。
19. 第 177 行：传入 resumeId。
20. 第 178 行：传入 candidateId。
21. 第 179 行：传入简历原文快照。
22. 第 180 行：传入目标岗位。
23. 第 181 行：传入评价摘要。
24. 第 182 行：从每个 issues 项提取 question 列表。
25. 第 183 行：提取 priority 列表。
26. 第 184 行：把每个 issue 的 suggestion 与评价总 suggestions 连接。
27. 第 185 行：传入技术栈。
28. 第 186 行：传入技术深度。
29. 第 187 行：传入职业偏好。
30. 第 188 行：传入 runId。
31. 第 189 行：传入评价指纹。
32. 第 190 行：传入完整结构化评价，供幂等快照保存。
33. 第 191 行：结束记忆写入。
34. 第 192 行：捕获长期记忆乐观锁或 runId 输入冲突。
35. 第 193 行：按完全相同条件再次调用 `get_resume_evaluation_run`。
36. 第 194 行：传入用户与简历 ID。
37. 第 195 行：传入 runId 与指纹。
38. 第 196 行：结束回读。
39. 第 197 行：检查并发请求是否没有留下可重放结果。
40. 第 198 行：没有结果时原样重新抛出 ConsistencyError。
41. 第 199 行：有结果时用持久化 replay 覆盖当前结果，保证并发响应一致。
42. 第 200 行：开始构造 `AgentResponse`。
43. 第 201 行：复制 apiVersion 与 requestId。
44. 第 202 行：复制 runId，成功 code 为 100，运行状态为 COMPLETED。
45. 第 203 行：复制 userId 和 sessionId。
46. 第 204 行：该独立评价不推进面试会话，协议状态固定 ACTIVE、版本固定 0。
47. 第 205 行：answer 返回摘要，output 按别名序列化完整评价，error 为 None。
48. 第 206 行：返回响应。

### 3.9 `activate_resume_memory`

文件：`python-agent/app/api/application.py:208-224`

```python
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
```

逐行解释：

1. 第 208 行：注册 HTTP `POST /v1/agent/resume/activate`，响应模型为 AgentResponse。
2. 第 209 行：定义异步路由函数。
3. 第 210 行：接收已校验 AgentResumeMemoryActivationRequest 与 Request。
4. 第 211 行：结束函数签名。
5. 第 212 行：调用 _remember_request_context 保存错误响应上下文。
6. 第 213 行：调用 _resolve_memory_service，并继续等待 MemoryService.activate_resume。
7. 第 214 行：传入 userId 与作为 resumeId 的 subjectId。
8. 第 215 行：传入 candidateId 与完整简历原文。
9. 第 216 行：传入 targetRole 与 runId。
10. 第 217 行：结束调用；返回 LongTermMemory 不向接口暴露。
11. 第 218 行：开始构造 AgentResponse。
12. 第 219 行：复制 apiVersion 与 requestId。
13. 第 220 行：复制 runId，code 100，运行状态 COMPLETED。
14. 第 221 行：复制 userId 与 sessionId。
15. 第 222 行：激活不推进面试会话，协议状态固定 ACTIVE、版本 0。
16. 第 223 行：没有问题或评价结果，answer/output/error 均为 None。
17. 第 224 行：返回响应。

### 3.10 `index_rag`

文件：`python-agent/app/api/application.py:226-241`

```python
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
```

逐行解释：

1. 第 226 行：注册 HTTP `POST /v1/agent/rag/index`，响应模型为 AgentResponse。
2. 第 227 行：定义异步路由并接收已校验请求和 Request。
3. 第 228 行：调用 _remember_request_context 保存协议上下文。
4. 第 229 行：调用 _resolve_rag_service，并把 KnowledgeDocument 传给 index_document。
5. 第 230 行：明确取 knowledgeBaseIds 列表第一个值作为索引目标库。
6. 第 231 行：传入 documentId。
7. 第 232 行：传入 sourceName。
8. 第 233 行：传入完整 documentContent。
9. 第 234 行：结束文档与服务调用，等待得到 chunk 数量。
10. 第 235 行：开始构造 AgentResponse。
11. 第 236 行：复制 apiVersion 与 requestId。
12. 第 237 行：复制 runId，code 100，运行状态 COMPLETED。
13. 第 238 行：复制 userId 与 sessionId。
14. 第 239 行：RAG 索引不推进面试会话，状态固定 ACTIVE、版本 0。
15. 第 240 行：把切片数转字符串作为 answer，output/error 为 None。
16. 第 241 行：返回响应。

### 3.11 `delete_rag`

文件：`python-agent/app/api/application.py:243-253`

```python
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
```

逐行解释：

1. 第 243 行：用 `@app.post` 注册 `POST /v1/agent/rag/delete`，并要求成功结果满足 `AgentResponse`。
2. 第 244 行：定义异步路由；`payload` 已由 FastAPI 按 `AgentRagDeleteRequest` 完成字段校验，`request` 提供应用状态和请求上下文。
3. 第 245 行：调用项目函数 `_remember_request_context`，把协议字段保存到 `request.state`，供统一异常响应复用。
4. 第 246 行：先调用项目函数 `_resolve_rag_service(request)` 取得或构建 RAG 服务，再调用项目函数 `delete_knowledge_base`，传入请求中的唯一知识库 ID，并等待删除完成。
5. 第 247 行：开始构造成功 `AgentResponse`；构造期间会执行项目字段校验器 `validate_code_category`。
6. 第 248 行：原样复制 `apiVersion` 和 `requestId`，使响应与调用请求关联。
7. 第 249 行：复制 `runId`，把业务码设为 `100`，并把运行状态设为 `COMPLETED`。
8. 第 250 行：复制 `userId` 和 `sessionId`。
9. 第 251 行：删除知识库索引不推进面试状态，因此响应会话状态固定为 `ACTIVE`，状态版本固定为 `0`。
10. 第 252 行：删除接口没有答案和结构化业务输出，故 `answer`、`output`、`error` 均为 `None`。
11. 第 253 行：结束响应构造并返回给 FastAPI。

### 3.12 `fetch_web`

文件：`python-agent/app/api/application.py:255-271`。

```python
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
```

1. 第 255 行：注册 `POST /v1/tools/web/fetch`，并把 `AgentResponse` 指定为响应模型。
2. 第 256 行：定义异步路由，接收校验后的 `AgentWebFetchRequest` 与当前 FastAPI `Request`。
3. 第 257 行：文档字符串说明本函数只抓取并提取一个明确指定的公开 HTML 页面。
4. 第 258 行：文档字符串中的空行分隔概要和安全约束。
5. 第 259 行：声明抓取到的页面内容不会被执行，也不会作为系统指令输入。
6. 第 260 行：说明调用方得到的是 Markdown 与来源信息。
7. 第 261 行：说明上层必须先获得明确确认，才能把预览结果写入索引。
8. 第 262 行：结束路由文档字符串。
9. 第 263 行：调用项目函数 `_remember_request_context`，保存协议上下文供异常响应恢复。
10. 第 264 行：调用并等待项目函数 `fetch_public_article(payload.url)`，完成 URL 安全校验、下载与正文提取。
11. 第 265 行：开始构造成功 `AgentResponse`；构造时触发项目校验器 `validate_code_category`。
12. 第 266 行：复制 `apiVersion` 和 `requestId`。
13. 第 267 行：复制 `runId`，设置成功码 `100` 和运行状态 `COMPLETED`。
14. 第 268 行：复制 `userId` 和 `sessionId`。
15. 第 269 行：网页预览不推进面试状态，因此会话状态固定为 `ACTIVE`，版本为 `0`。
16. 第 270 行：把网页标题作为 `answer`，调用项目函数 `WebDocument.as_dict()` 生成 `output`，并把 `error` 设为 `None`。
17. 第 271 行：结束并返回响应。

### 3.13 `crawl_web`

文件：`python-agent/app/api/application.py:273-290`

```python
    @app.post("/v1/tools/web/crawl", response_model=AgentResponse)
    async def crawl_web(payload: AgentWebCrawlRequest, request: Request) -> AgentResponse:
        _remember_request_context(request, payload)
        from app.agents.llm.factory import LLMFactory
        from app.agents.web_crawl.agent import WebCrawlPlanningAgent
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
```

逐行解释：

1. 第 273 行：用 `@app.post` 注册 `POST /v1/tools/web/crawl`，并要求返回值满足 `AgentResponse`。
2. 第 274 行：定义异步路由；`payload` 已由 FastAPI 按 `AgentWebCrawlRequest` 校验，`request` 提供请求状态。
3. 第 275 行：调用项目函数 `_remember_request_context` 保存协议字段，供统一异常响应恢复请求号、运行号和身份字段。
4. 第 276 行：延迟导入 `LLMFactory`；只有调用抓取接口时才加载模型工厂。
5. 第 277 行：从当前实际路径 `app.agents.web_crawl.agent` 延迟导入 `WebCrawlPlanningAgent`。
6. 第 278 行：延迟导入 `RetryPolicy`。
7. 第 279 行：延迟导入 `AsyncRetryExecutor`。
8. 第 280 行：开始构造页面规划 Agent。
9. 第 281 行：依次调用项目函数 `LLMFactory.create_chat_model()`、`PromptLoader()`、`RetryPolicy.load()` 和 `AsyncRetryExecutor(...)`，并把三项依赖注入规划 Agent。
10. 第 282 行：结束 `WebCrawlPlanningAgent` 构造；其构造函数还会创建 `StructuredOutputInvoker`。
11. 第 283 行：调用并等待项目函数 `crawl_public_site`，传入入口 URL、可选主题和规划 Agent。
12. 第 284 行：开始构造成功 `AgentResponse`；构造时会调用项目校验器 `validate_code_category`。
13. 第 285 行：复制 `apiVersion` 与 `requestId`。
14. 第 286 行：复制 `runId`，设置成功业务码 `100` 和运行状态 `COMPLETED`。
15. 第 287 行：复制 `userId` 与 `sessionId`。
16. 第 288 行：站点抓取不推进面试状态，因此会话状态固定为 `ACTIVE`，版本为 `0`。
17. 第 289 行：用有效页面数生成英文答案；调用项目函数 `CrawlResult.as_dict()` 生成完整输出，并把错误设为 `None`。
18. 第 290 行：结束响应构造并返回。

### 3.14 三个 FastAPI 异常处理器

`request_validation_error` 文件：`python-agent/app/api/application.py:292-299`

1. 第 292 行：用 `@app.exception_handler` 注册 `RequestValidationError` 处理器。
2. 第 293 行：定义异步处理函数，接收当前请求和 FastAPI 校验异常。
3. 第 294 行：读取校验异常保存的原始请求体。
4. 第 295 行：只有请求体根节点是 Mapping 时才直接作为协议上下文，否则使用 `None`。
5. 第 296 行：调用并等待项目函数 `_error_json_response`。
6. 第 297 行：把框架校验异常转换为 `RequestError`，并指定 HTTP 400。
7. 第 298 行：传入可用的原始请求上下文。
8. 第 299 行：结束调用并返回统一 JSONResponse。

`application_error` 文件：`python-agent/app/api/application.py:301-304`

1. 第 301 行：注册所有 `ApplicationException` 子类的统一处理器。
2. 第 302 行：定义异步处理函数。
3. 第 303 行：调用项目函数 `_mark_failed_interview_progress`，仅回答接口会尝试补写失败进度。
4. 第 304 行：调用项目函数 `_error_json_response`，按协议用 HTTP 200 返回项目错误。

`unexpected_error` 文件：`python-agent/app/api/application.py:306-310`

1. 第 306 行：注册所有其他 `Exception` 的兜底处理器。
2. 第 307 行：定义异步处理函数。
3. 第 308 行：用 `logger.exception` 记录完整未处理异常堆栈。
4. 第 309 行：调用项目函数 `_mark_failed_interview_progress` 尝试补偿回答进度。
5. 第 310 行：调用 `_error_json_response`，以 HTTP 500 返回脱敏失败响应。

### 3.15 `_resolve_service`

文件：`python-agent/app/api/application.py:315-320`

1. 第 315 行：定义面试服务解析函数。
2. 第 316 行：从 `request.app.state.interview_agent_service` 读取已注入或已缓存实例。
3. 第 317 行：检查服务是否为 `None`。
4. 第 318 行：首次使用时调用项目函数 `build_interview_agent_service()` 构造完整依赖图。
5. 第 319 行：把新实例写回应用状态，使后续请求复用。
6. 第 320 行：返回已有或新建服务。

### 3.16 `_mark_failed_interview_progress`

文件：`python-agent/app/api/application.py:323-331`

1. 第 323 行：定义异步失败进度补偿函数。
2. 第 324 行：检查当前请求路径是否不是 `/v1/agent/respond`。
3. 第 325 行：其他接口立即返回，不误写面试回答进度。
4. 第 326 行：回答接口调用项目函数 `_request_context` 恢复协议字段。
5. 第 327 行：读取 `sessionId` 并调用项目函数 `_string_or_none` 清洗。
6. 第 328 行：直接读取当前应用状态中的面试服务；异常处理中不触发冷启动。
7. 第 329 行：通过 `getattr` 读取可选 `mark_progress_failed` 方法。
8. 第 330 行：要求 sessionId 有效且 marker 可调用。
9. 第 331 行：条件满足时调用项目函数 `mark_progress_failed(session_id)`。

### 3.17 `_resolve_rag_service`、`_resolve_resume_evaluator` 与 `_resolve_memory_service`

`_resolve_rag_service` 文件：`python-agent/app/api/application.py:334-340`

1. 第 334 行：定义 RAG 服务解析函数。
2. 第 335 行：从应用状态读取已有 RAG 服务。
3. 第 336 行：检查服务是否为空。
4. 第 337 行：首次使用时延迟导入项目工厂 `build_rag_service`。
5. 第 338 行：调用项目函数 `build_rag_service()` 创建服务。
6. 第 339 行：把服务写回应用状态。
7. 第 340 行：返回服务。

`_resolve_resume_evaluator` 文件：`python-agent/app/api/application.py:343-348`

1. 第 343 行：定义简历评价 Agent 解析函数。
2. 第 344 行：从应用状态读取已有 evaluator。
3. 第 345 行：检查 evaluator 是否为空。
4. 第 346 行：首次使用时调用项目函数 `build_resume_evaluation_agent()`。
5. 第 347 行：把新 evaluator 写回应用状态。
6. 第 348 行：返回 evaluator。

`_resolve_memory_service` 文件：`python-agent/app/api/application.py:351-357`

1. 第 351 行：定义记忆服务解析函数。
2. 第 352 行：从应用状态读取已有记忆服务。
3. 第 353 行：检查服务是否为空。
4. 第 354 行：首次使用时延迟导入项目工厂 `build_memory_service`。
5. 第 355 行：调用项目函数 `build_memory_service()`。
6. 第 356 行：把新服务写回应用状态。
7. 第 357 行：返回服务。

### 3.18 `_success_response`

文件：`python-agent/app/api/application.py:360-376`

1. 第 360 行：定义成功响应构造函数，并把所有参数限定为关键字参数。
2. 第 361 行：接收必需的协议版本、请求号、运行号，以及会话和可选答案。
3. 第 362 行：接收可选结构化输出。
4. 第 363 行：接收可选状态版本覆盖值。
5. 第 364 行：接收可选会话状态覆盖值。
6. 第 365 行：接收可选当前轮阶段。
7. 第 366 行：接收可选当前总体阶段，声明返回 `AgentResponse`。
8. 第 367 行：开始构造并返回统一成功响应。
9. 第 368 行：写协议版本、请求号和运行号。
10. 第 369 行：写成功码 `100`、运行状态 `COMPLETED`，并从会话读取 userId。
11. 第 370 行：写 sessionId；显式会话状态优先，否则使用会话当前状态。
12. 第 371 行：显式状态版本非空时使用覆盖值，否则使用会话版本；数值 0 不会被误判为空。
13. 第 372 行：显式答案非空时使用答案，否则使用会话当前问题。
14. 第 373 行：写入当前轮阶段。
15. 第 374 行：显式总体阶段优先，否则兼容读取会话 `current_stage`，不存在时为 `None`。
16. 第 375 行：写结构化输出，并把错误固定为 `None`。
17. 第 376 行：结束响应构造并返回；Pydantic 会调用项目校验器 `AgentResponse.validate_code_category`。

### 3.19 `_request_context` 与 `_remember_request_context`

`_request_context` 文件：`python-agent/app/api/application.py:379-388`

1. 第 379 行：定义异步请求上下文恢复函数。
2. 第 380 行：通过 `getattr` 读取先前保存的 `request.state.agent_context`。
3. 第 381 行：检查记忆值是否为 Mapping。
4. 第 382 行：是映射时直接返回，避免重复读取请求体。
5. 第 383 行：没有可用记忆时进入请求体读取保护。
6. 第 384 行：异步读取原始请求体字节。
7. 第 385 行：请求体非空时用 `json.loads` 解析，否则使用空字典。
8. 第 386 行：只有解析根节点为字典时返回，其他 JSON 根节点回退为空字典。
9. 第 387 行：捕获 JSON 格式、Unicode 解码或请求体重复读取导致的运行时错误。
10. 第 388 行：恢复失败时返回空字典，保证错误响应本身仍可生成。

`_remember_request_context` 文件：`python-agent/app/api/application.py:391-394`

1. 第 391 行：定义请求上下文记忆函数。
2. 第 392 行：读取 payload 的可选 `model_dump` 方法。
3. 第 393 行：检查该属性可调用。
4. 第 394 行：按字段别名和 JSON 模式转储请求模型，保存到 request.state。

### 3.20 `_error_response`、`_string_or_none` 与 `_session_status_or_failed`

`_error_response` 文件：`python-agent/app/api/application.py:397-411`

1. 第 397 行：定义异常到协议响应的异步转换函数并开始多行签名。
2. 第 398 行：接收请求、原异常和可选显式上下文。
3. 第 399 行：声明返回 `AgentResponse` 并结束签名。
4. 第 400 行：显式上下文优先，否则调用项目函数 `_request_context` 恢复请求字段。
5. 第 401 行：读取 sessionStatus 并调用项目函数 `_session_status_or_failed` 转换。
6. 第 402 行：读取未经信任的 stateVersion。
7. 第 403 行：开始构造失败 `AgentResponse`。
8. 第 404 行：读取 apiVersion 并调用项目函数 `_string_or_none` 清洗。
9. 第 405 行：读取 requestId 并清洗。
10. 第 406 行：清洗 runId，并调用项目函数 `ExceptionHandler.to_code(error)` 生成协议错误码。
11. 第 407 行：把运行状态固定为 `FAILED`，清洗 userId。
12. 第 408 行：清洗 sessionId，写入已解析会话状态。
13. 第 409 行：stateVersion 只有是非负整数时才保留，否则回退为 0。
14. 第 410 行：失败响应不返回答案，把 currentStage 设为 `FAILED`，并调用 `ExceptionHandler.to_error_info`。
15. 第 411 行：结束并返回失败响应；构造时触发 `AgentResponse.validate_code_category`。

`_string_or_none` 文件：`python-agent/app/api/application.py:414-415`

1. 第 414 行：定义协议字符串清洗函数。
2. 第 415 行：只有值是字符串且去空白后非空时返回原字符串，否则返回 `None`。

`_session_status_or_failed` 文件：`python-agent/app/api/application.py:418-423`

1. 第 418 行：定义会话状态安全转换函数。
2. 第 419 行：文档字符串说明运行失败不能错误覆盖已有会话状态。
3. 第 420 行：进入枚举转换保护。
4. 第 421 行：用输入值构造 `SessionStatus`；合法字符串或枚举会成功返回。
5. 第 422 行：捕获类型错误和值错误。
6. 第 423 行：无法转换时回退为 `SessionStatus.FAILED`。

### 3.21 `_candidate_response_output`

文件：`python-agent/app/api/application.py:426-435`

1. 第 426 行：定义候选人可见输出过滤函数。
2. 第 427 行：文档字符串声明下层边界只允许白名单字段面向候选人。
3. 第 428 行：检查输出为空字典或 `None`。
4. 第 429 行：无输出时返回 `None`。
5. 第 430 行：开始创建允许字段集合。
6. 第 431 行：允许评价摘要、分数、优缺点以及当前/总主问题数。
7. 第 432 行：允许追问数、总题数、题目预算和最终评价。
8. 第 433 行：结束白名单集合。
9. 第 434 行：遍历原输出，只保留键存在于白名单的项。
10. 第 435 行：过滤结果非空时返回字典，否则返回 `None`。

### 3.22 `_resume_evaluation_fingerprint`

文件：`python-agent/app/api/application.py:438-444`

1. 第 438 行：定义简历评价输入指纹函数。
2. 第 439 行：开始构造并序列化规范 JSON。
3. 第 440 行：写 subjectId。
4. 第 441 行：写完整简历输入文本。
5. 第 442 行：写目标岗位。
6. 第 443 行：保留中文、按键排序，并使用无多余空格的分隔符，确保相同语义输入产生稳定字节串。
7. 第 444 行：把规范 JSON 编码为 UTF-8，计算 SHA-256 并返回十六进制摘要。

### 3.23 `_error_json_response`

文件：`python-agent/app/api/application.py:447-455`

1. 第 447 行：定义统一 JSON 异常响应函数并开始多行签名。
2. 第 448 行：接收当前 Request。
3. 第 449 行：接收原始异常。
4. 第 450 行：用 `*` 把后续参数限定为关键字参数。
5. 第 451 行：接收最终 HTTP 状态码。
6. 第 452 行：接收可选显式协议上下文。
7. 第 453 行：声明返回 `JSONResponse` 并结束签名。
8. 第 454 行：调用项目函数 `_error_response` 构造协议级 `AgentResponse`。
9. 第 455 行：调用项目函数 `AgentResponse.to_json_dict`，再以指定状态码构造并返回 JSONResponse。

### 3.24 模块级 `app = create_app()`

文件：`python-agent/app/api/application.py:458-458`

1. 第 458 行：模块导入时调用项目函数 `create_app()`，生成 ASGI 服务器默认加载的全局 FastAPI 应用；服务依赖初始为 `None`，各解析函数在首次请求时懒加载并缓存。

## 4. 主流构建分析

主流 FastAPI 工程通常把应用工厂、路由、依赖注入和异常处理拆到不同模块：`main.py` 只创建应用并挂载 Router；每个业务域维护独立 `APIRouter`；服务依赖通过 `Depends` 和带生命周期的 provider 管理；异常映射集中在 exception handler 模块。优点是文件职责单一、单元测试可按 Router 隔离、依赖生命周期明确，也便于 OpenAPI 分组；缺点是小项目文件数量增加，跨模块追踪一次请求需要更多跳转，错误的 provider 设计还可能制造隐式全局状态。

本项目当前 `application.py` 把 11 个接口放在一个工厂内，协议边界和统一错误响应容易整体审查，并通过 `app.state` 支持测试注入和首次请求懒加载；在现有规模下仍可工作。主要不足是单文件接近 460 行，路由闭包不能被其他模块单独导入测试，四组 `_resolve_*` 存在重复模式，且业务依赖的首次构建延迟落在真实请求上。

若实施模块化，可按 sessions、evaluation、rag、web 四个域创建 `APIRouter`，把 `_remember_request_context` 和统一异常响应保留在 application 层；为 InterviewAgentService、RagService、ResumeEvaluationAgent、MemoryService 编写带 `@lru_cache` 或 lifespan 状态的 provider，并通过 `Depends` 注入。应用 lifespan 中可选择预热关键服务并在退出时关闭 Redis、数据库引擎和模型客户端。迁移应先保持现有路径、请求/响应模型和错误码不变，再逐个 Router 搬迁，以避免 Java 调用契约发生变化。
