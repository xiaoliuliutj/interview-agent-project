"""生产环境的下层 Agent 服务依赖组装。"""

from app.agents.interview.agent import InterviewEvaluationAgent, InterviewPlanner, InterviewQuestionAgent, InterviewRoutingAgent, InterviewSummaryAgent
from app.agents.evaluation.agent import ResumeEvaluationAgent
from app.agents.interview.service import InterviewAgentService
from app.agents.interview.workflow import InterviewWorkflow
from app.agents.llm.factory import LLMFactory
from app.memory.policy import MemoryPolicy
from app.memory.service import MemoryService
from app.rag.embedding import OpenAIEmbeddingProvider
from app.rag.policy import RagPolicy
from app.rag.service import RagSearchTool, RagService
from app.tools.web_search import WebEvidenceTool
from app.infrastructure.reliability.policy import RetryPolicy
from app.infrastructure.reliability.retry import AsyncRetryExecutor
from app.infrastructure.idempotency.policy import IdempotencyPolicy
from app.tools.skills.loader import SkillRegistry
from app.common.config import Settings, get_settings
from app.common.prompt_loader import PromptLoader
from app.infrastructure.persistence.database import create_session_factory
from app.infrastructure.persistence.interview_session_repository import (
    PostgresInterviewSessionRepository,
)
from app.infrastructure.persistence.long_term_memory_repository import (
    PostgresLongTermMemoryRepository,
)
from app.infrastructure.persistence.rag_vector_repository import (
    PostgresRagVectorRepository,
)
from app.infrastructure.cache.redis_cache import RedisCache


def build_memory_service(settings: Settings | None = None) -> MemoryService:
    current = settings or get_settings()
    session_factory = create_session_factory(current)
    return MemoryService(
        PostgresLongTermMemoryRepository(session_factory), MemoryPolicy.load()
    )


def build_cache(settings: Settings | None = None) -> RedisCache:
    current = settings or get_settings()
    return RedisCache(current.redis_url)

def build_interview_agent_service(
    settings: Settings | None = None,
) -> InterviewAgentService:
    """建立真实依赖；不自动建表，也不允许在无数据库时退化为临时文件。"""

    current = settings or get_settings()
    session_factory = create_session_factory(current)
    prompt_loader = PromptLoader()
    skill_registry = SkillRegistry()
    workflow = InterviewWorkflow.load(prompt_loader)
    model = LLMFactory.create_chat_model(current)
    retry_executor = AsyncRetryExecutor(RetryPolicy.load())
    rag_tool = RagSearchTool(build_rag_service(current)) if current.embedding_model else None

    return InterviewAgentService(
        planner=InterviewPlanner(
            model, prompt_loader, skill_registry, retry_executor
        ),
        evaluation_agent=InterviewEvaluationAgent(
            model, prompt_loader, skill_registry, retry_executor
        ),
        routing_agent=InterviewRoutingAgent(
            model, prompt_loader, skill_registry, retry_executor
        ),
        question_agent=InterviewQuestionAgent(model, prompt_loader, skill_registry, retry_executor),
        rag_tool=rag_tool,
        repository=PostgresInterviewSessionRepository(session_factory, build_cache(current)),
        workflow=workflow,
        prompt_loader=prompt_loader,
        memory_service=build_memory_service(current),
        summary_agent=InterviewSummaryAgent(model, prompt_loader, retry_executor),
        idempotency_policy=IdempotencyPolicy.load(),
        web_evidence_tool=WebEvidenceTool(),
        cache=build_cache(current),
    )


def build_rag_service(settings: Settings | None = None) -> RagService:
    current = settings or get_settings()
    session_factory = create_session_factory(current)
    retry_executor = AsyncRetryExecutor(RetryPolicy.load())
    return RagService(
        repository=PostgresRagVectorRepository(session_factory),
        embedding_provider=OpenAIEmbeddingProvider(current, retry_executor),
        policy=RagPolicy.load(),
        cache=build_cache(current),
    )


def build_resume_evaluation_agent(
    settings: Settings | None = None,
) -> ResumeEvaluationAgent:
    current = settings or get_settings()
    retry_executor = AsyncRetryExecutor(RetryPolicy.load())
    return ResumeEvaluationAgent(
        model=LLMFactory.create_chat_model(current),
        prompt_loader=PromptLoader(),
        skill_registry=SkillRegistry(),
        retry_executor=retry_executor,
    )
