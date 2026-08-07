"""生产环境的下层 Agent 服务依赖组装。"""

from app.agent.interview.agent import InterviewDecisionAgent, InterviewPlanner, InterviewSummaryAgent
from app.agent.evaluation.agent import ResumeEvaluationAgent
from app.agent.interview.service import InterviewAgentService
from app.agent.interview.workflow import InterviewWorkflow
from app.agent.llm.factory import LLMFactory
from app.agent.memory.policy import MemoryPolicy
from app.agent.memory.service import MemoryService
from app.agent.rag.embedding import OpenAIEmbeddingProvider
from app.agent.rag.policy import RagPolicy
from app.agent.rag.service import RagSearchTool, RagService
from app.agent.rag.answer import RagAnswerAgent
from app.agent.schedule.agent import ScheduleParseAgent
from app.engineering.reliability.policy import RetryPolicy
from app.engineering.reliability.retry import AsyncRetryExecutor
from app.engineering.idempotency.policy import IdempotencyPolicy
from app.agent.skills.loader import SkillRegistry
from app.core.config import Settings, get_settings
from app.core.prompt_loader import PromptLoader
from app.engineering.persistence.database import create_session_factory
from app.engineering.persistence.interview_session_repository import (
    PostgresInterviewSessionRepository,
)
from app.engineering.persistence.long_term_memory_repository import (
    PostgresLongTermMemoryRepository,
)
from app.engineering.persistence.rag_vector_repository import (
    PostgresRagVectorRepository,
)


def build_memory_service(settings: Settings | None = None) -> MemoryService:
    current = settings or get_settings()
    session_factory = create_session_factory(current)
    return MemoryService(
        PostgresLongTermMemoryRepository(session_factory), MemoryPolicy.load()
    )


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
    rag_tool = None
    if current.embedding_model:
        rag_tool = RagSearchTool(build_rag_service(current))

    return InterviewAgentService(
        planner=InterviewPlanner(
            model, prompt_loader, skill_registry, rag_tool, retry_executor
        ),
        decision_agent=InterviewDecisionAgent(
            model, prompt_loader, skill_registry, rag_tool, retry_executor
        ),
        repository=PostgresInterviewSessionRepository(session_factory),
        workflow=workflow,
        prompt_loader=prompt_loader,
        memory_service=build_memory_service(current),
        summary_agent=InterviewSummaryAgent(model, prompt_loader, retry_executor),
        idempotency_policy=IdempotencyPolicy.load(),
    )


def build_rag_service(settings: Settings | None = None) -> RagService:
    current = settings or get_settings()
    session_factory = create_session_factory(current)
    return RagService(
        repository=PostgresRagVectorRepository(session_factory),
        embedding_provider=OpenAIEmbeddingProvider(current),
        policy=RagPolicy.load(),
    )


def build_rag_answer_agent(settings: Settings | None = None) -> RagAnswerAgent:
    current = settings or get_settings()
    return RagAnswerAgent(
        LLMFactory.create_chat_model(current), PromptLoader(),
        AsyncRetryExecutor(RetryPolicy.load()),
    )


def build_resume_evaluation_agent(
    settings: Settings | None = None,
) -> ResumeEvaluationAgent:
    current = settings or get_settings()
    rag_tool = RagSearchTool(build_rag_service(current)) if current.embedding_model else None
    retry_executor = AsyncRetryExecutor(RetryPolicy.load())
    return ResumeEvaluationAgent(
        model=LLMFactory.create_chat_model(current),
        prompt_loader=PromptLoader(),
        skill_registry=SkillRegistry(),
        rag_tool=rag_tool,
        retry_executor=retry_executor,
    )


def build_schedule_parse_agent(settings: Settings | None = None) -> ScheduleParseAgent:
    current = settings or get_settings()
    return ScheduleParseAgent(
        LLMFactory.create_chat_model(current), PromptLoader(),
        AsyncRetryExecutor(RetryPolicy.load()),
    )
