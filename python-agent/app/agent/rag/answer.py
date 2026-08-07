"""由 Agent 基于 RAG 证据生成答案；证据只存在于内部提示中。"""

import json
from typing import Protocol

from langchain_core.messages import HumanMessage, SystemMessage

from app.core.prompt_loader import PromptLoader
from app.engineering.reliability.retry import AsyncRetryExecutor

from .models import RagSearchResult


class ChatModel(Protocol):
    async def ainvoke(self, input_value: object) -> object: ...


class RagAnswerAgent:
    def __init__(self, model: ChatModel, prompt_loader: PromptLoader,
                 retry_executor: AsyncRetryExecutor | None = None) -> None:
        self._model = model
        self._prompt_loader = prompt_loader
        self._retry_executor = retry_executor

    async def answer(self, question: str, evidence: list[RagSearchResult]) -> str:
        payload = {
            "question": question,
            "evidence": [
                {"source": item.chunk.source_name, "content": item.chunk.content, "score": item.score}
                for item in evidence
            ],
        }
        messages = [
            SystemMessage(content=self._prompt_loader.render("rag/answer.md", {})),
            HumanMessage(content=json.dumps(payload, ensure_ascii=False)),
        ]
        invoke = lambda: self._model.ainvoke(messages)
        result = await self._retry_executor.execute(invoke) if self._retry_executor else await invoke()
        content = getattr(result, "content", result)
        return str(content).strip()
