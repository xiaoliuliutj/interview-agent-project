"""LLM planning for bounded documentation crawl tasks."""

from pydantic import BaseModel, Field

from app.common.prompt_loader import PromptLoader
from app.infrastructure.reliability.retry import AsyncRetryExecutor
from app.infrastructure.reliability.structured_output import RawChatModel, StructuredOutputInvoker


class CrawlLinkChoice(BaseModel):
    url: str
    priority: int = Field(ge=1, le=100)


class CrawlPageDecision(BaseModel):
    page_type: str = Field(alias="pageType", pattern="^(CONTENT|DIRECTORY|IRRELEVANT)$")
    include_as_knowledge: bool = Field(alias="includeAsKnowledge")
    expand_links: bool = Field(alias="expandLinks")
    relevance_score: int = Field(alias="relevanceScore", ge=0, le=100)
    reason: str = Field(min_length=1, max_length=300)
    selected_links: list[CrawlLinkChoice] = Field(default_factory=list, alias="selectedLinks", max_length=100)

    model_config = {"populate_by_name": True}


class WebCrawlPlanningAgent:
    def __init__(self, model: RawChatModel, prompt_loader: PromptLoader,
                 retry_executor: AsyncRetryExecutor | None = None) -> None:
        self._model = model
        self._prompt_loader = prompt_loader
        self._invoker = StructuredOutputInvoker(prompt_loader, retry_executor)

    async def assess(self, *, title: str, url: str, topic: str | None,
                     markdown: str, candidate_links: list[str]) -> CrawlPageDecision:
        return await self._invoker.invoke(
            model=self._model, schema=CrawlPageDecision,
            business_prompt=self._prompt_loader.render("web-crawl/planner.md", {}),
            input_payload={"requestedTopic": topic, "pageUrl": url, "pageTitle": title,
                           "cleanedPageText": markdown[:12000], "candidateLinks": candidate_links[:100]},
        )
