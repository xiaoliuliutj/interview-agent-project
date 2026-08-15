"""最小本地 MCP Server：只读查询预置面试参考资料。"""

from pathlib import Path

from mcp.server.fastmcp import FastMCP

from app.common.config import PROJECT_DIR


REFERENCE_PATH = PROJECT_DIR / "resources" / "rag" / "sources" / "interview-basics.md"
mcp = FastMCP("interview-agent-reference")


def _load_reference() -> str:
    return REFERENCE_PATH.read_text(encoding="utf-8")


@mcp.tool()
def lookup_interview_reference(query: str) -> str:
    """从预置面试资料中返回与查询词相关的段落，不修改任何状态。"""

    normalized = query.strip()
    if not normalized:
        return "查询内容不能为空"
    if len(normalized) > 200:
        return "查询内容不能超过 200 个字符"

    paragraphs = [item.strip() for item in _load_reference().split("\n\n") if item.strip()]
    matched = [item for item in paragraphs if normalized.lower() in item.lower()]
    if not matched:
        matched = paragraphs[:2]
    return "\n\n---\n\n".join(matched[:5])


if __name__ == "__main__":
    mcp.run(transport="stdio")
