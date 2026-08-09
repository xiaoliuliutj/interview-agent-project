import httpx
import pytest

from app.api.application import create_app

REQUEST_TIMESTAMP = "2026-08-09T00:00:00Z"


@pytest.mark.asyncio
async def test_skill_catalog_and_jd_parser_are_deterministic() -> None:
    transport = httpx.ASGITransport(app=create_app())
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        catalog_response = await client.post(
            "/v1/agent/skills",
            json={
                "apiVersion": "v1",
                "requestId": "skill-list-1",
                "runId": "skill-list-run-1",
                "userId": "catalog-user",
                "sessionId": "skill-catalog",
                "operation": "agent.skills.list",
                "timestamp": REQUEST_TIMESTAMP,
            },
        )
        jd_response = await client.post(
            "/v1/agent/skills",
            json={
                "apiVersion": "v1",
                "requestId": "skill-jd-1",
                "runId": "skill-jd-run-1",
                "userId": "catalog-user",
                "sessionId": "skill-catalog",
                "operation": "agent.skills.parse-jd",
                "inputText": "需要 Java、Spring Boot 和 Redis 经验",
                "timestamp": REQUEST_TIMESTAMP,
            },
        )

    assert catalog_response.status_code == 200
    assert catalog_response.json()["code"] == 100
    assert catalog_response.json()["output"]["skills"][0]["id"] == "java-backend"
    assert jd_response.status_code == 200
    categories = jd_response.json()["output"]["categories"]
    assert {item["key"] for item in categories} >= {"java", "spring", "distributed"}
