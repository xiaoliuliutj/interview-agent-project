from app.agent.mcp.server import lookup_interview_reference


def test_mcp_reference_tool_is_read_only_and_bounded() -> None:
    result = lookup_interview_reference("Redis")
    assert result
    assert "Redis" in result or "缓存" in result
    assert lookup_interview_reference("") == "查询内容不能为空"
    assert "不能超过" in lookup_interview_reference("x" * 201)
