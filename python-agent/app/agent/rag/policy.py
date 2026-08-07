"""RAG 外部策略配置。"""

import json
from dataclasses import dataclass
from pathlib import Path

from app.core.config import PROJECT_DIR
from app.core.exceptions import RagConfigurationError

from .models import RagUseCase


@dataclass(frozen=True)
class RagPolicy:
    chunk_size_tokens: int
    chunk_overlap_tokens: int
    embedding_batch_size: int
    default_top_k: int
    default_min_score: float
    fallback_candidate_multiplier: int
    default_knowledge_base_ids: tuple[str, ...]
    allowed_use_cases: frozenset[RagUseCase]

    @classmethod
    def load(cls, path: Path | None = None) -> "RagPolicy":
        config_path = path or PROJECT_DIR / "config" / "rag" / "rag-policy.json"
        try:
            raw = json.loads(config_path.read_text(encoding="utf-8"))
            policy = cls(
                chunk_size_tokens=int(raw["chunkSizeTokens"]),
                chunk_overlap_tokens=int(raw["chunkOverlapTokens"]),
                embedding_batch_size=int(raw["embeddingBatchSize"]),
                default_top_k=int(raw["defaultTopK"]),
                default_min_score=float(raw["defaultMinScore"]),
                fallback_candidate_multiplier=int(raw["fallbackCandidateMultiplier"]),
                default_knowledge_base_ids=tuple(raw["defaultKnowledgeBaseIds"]),
                allowed_use_cases=frozenset(
                    RagUseCase(value) for value in raw["allowedUseCases"]
                ),
            )
        except (
            FileNotFoundError,
            KeyError,
            ValueError,
            TypeError,
            json.JSONDecodeError,
        ) as error:
            raise RagConfigurationError("RAG 策略配置无效") from error

        if policy.chunk_size_tokens < 1 or policy.chunk_overlap_tokens < 0:
            raise RagConfigurationError("RAG 切片参数必须为正数")
        if policy.chunk_overlap_tokens >= policy.chunk_size_tokens:
            raise RagConfigurationError("RAG 重叠 Token 必须小于切片大小")
        if policy.embedding_batch_size < 1:
            raise RagConfigurationError("RAG 向量化批大小必须大于 0")
        if policy.default_top_k < 1 or not 0 <= policy.default_min_score <= 1:
            raise RagConfigurationError("RAG 检索参数无效")
        if not policy.default_knowledge_base_ids or not policy.allowed_use_cases:
            raise RagConfigurationError("RAG 知识库或用途不能为空")
        return policy
