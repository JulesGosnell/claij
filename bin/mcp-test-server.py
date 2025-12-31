#!/usr/bin/env python3
"""Minimal MCP server for testing - fast start, fast stop."""
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("test-mcp")

@mcp.tool()
async def echo(message: str) -> str:
    """Echo back the message."""
    return message

@mcp.tool()
async def add(a: int, b: int) -> int:
    """Add two numbers."""
    return a + b

if __name__ == "__main__":
    mcp.run(transport="stdio")
