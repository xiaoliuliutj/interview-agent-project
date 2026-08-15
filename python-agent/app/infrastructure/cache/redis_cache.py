"""Failure-tolerant asynchronous Redis cache for the Python Agent.

Cache operations deliberately never raise to a business workflow.  A Redis
outage therefore falls back to PostgreSQL, pgvector, or process-local state.
"""

import json
import logging
from typing import Any

from redis.asyncio import Redis
from redis.exceptions import RedisError


logger = logging.getLogger(__name__)


class RedisCache:
    def __init__(self, redis_url: str) -> None:
        self._client: Redis | None = (
            Redis.from_url(
                redis_url, decode_responses=True, socket_connect_timeout=0.2, socket_timeout=0.2,
            )
            if redis_url else None
        )

    @property
    def enabled(self) -> bool:
        return self._client is not None

    async def get_json(self, key: str) -> dict[str, Any] | list[Any] | None:
        if self._client is None:
            return None
        try:
            raw = await self._client.get(key)
            return json.loads(raw) if raw else None
        except (RedisError, json.JSONDecodeError, TypeError) as error:
            logger.warning("Python Redis read failed; falling back to persistent storage: key=%s", key, exc_info=error)
            return None

    async def set_json(self, key: str, value: Any, *, ttl_seconds: int) -> bool:
        if self._client is None:
            return False
        try:
            await self._client.set(key, json.dumps(value, ensure_ascii=False, separators=(",", ":")), ex=ttl_seconds)
            return True
        except (RedisError, TypeError, ValueError) as error:
            logger.warning("Python Redis write failed; keeping durable data unchanged: key=%s", key, exc_info=error)
            return False

    async def delete(self, *keys: str) -> None:
        if self._client is None or not keys:
            return
        try:
            await self._client.delete(*keys)
        except RedisError as error:
            logger.warning("Python Redis delete failed; stale entries will expire: keys=%s", keys, exc_info=error)

    async def delete_matching(self, pattern: str) -> None:
        """Best-effort invalidation for small, bounded cache namespaces only."""
        if self._client is None:
            return
        try:
            keys = [key async for key in self._client.scan_iter(match=pattern, count=200)]
            if keys:
                await self._client.delete(*keys)
        except RedisError as error:
            logger.warning("Python Redis pattern invalidation failed; entries will expire: pattern=%s", pattern, exc_info=error)

    async def close(self) -> None:
        if self._client is not None:
            try:
                await self._client.aclose()
            except RedisError as error:
                logger.warning("Python Redis close failed", exc_info=error)
