# /// script
# requires-python = ">=3.11"
# dependencies = ["mcp==1.30.0"]
# ///

import asyncio
import hashlib
import importlib.metadata
import json
import sys
import tempfile
from datetime import timedelta
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from mcp.shared.exceptions import McpError


async def smoke(command: str) -> None:
    with tempfile.TemporaryDirectory(prefix="graphharness-mcp-smoke-") as directory:
        root = Path(directory)
        source = (
            "public class Greeting {\r\n"
            "    public String greet() {\r\n"
            '        return "Hello 🦉";\r\n'
            "    }\r\n"
            "}\r\n"
        ).encode("utf-8")
        (root / "Greeting.java").write_bytes(source)
        parameters = StdioServerParameters(command=command, args=["stdio", directory])
        async with stdio_client(parameters) as (read, write):
            async with ClientSession(read, write, read_timeout_seconds=timedelta(seconds=60)) as session:
                initialized = await session.initialize()
                assert initialized.serverInfo.name == "graphharness"
                assert initialized.protocolVersion == "2025-06-18"
                await session.send_ping()
                definitions = await session.list_tools()
                assert {"search_graph", "get_source"} <= {tool.name for tool in definitions.tools}
                found = await session.call_tool("search_graph", {"query": "Greeting.greet", "kind": "method"})
                assert not found.isError
                found_data = found.structuredContent
                assert found_data and len(found_data["results"]) == 1, found_data
                node_id = found_data["results"][0]["id"]
                inspected = await session.call_tool("get_source", {"node_id": node_id})
                assert not inspected.isError
                inspected_data = inspected.structuredContent
                assert inspected_data and "Hello 🦉" in inspected_data["source"]
                assert inspected_data["file_hash"] == hashlib.sha256(source).hexdigest()
                assert "\r\n" in inspected_data["source"]
                failed = await session.call_tool("get_source", {"node_id": "missing-node"})
                assert failed.isError
                try:
                    unknown = await session.call_tool("missing-tool", {})
                    assert unknown.isError
                except McpError as error:
                    assert error.error.code in (-32601, -32602)
                print(json.dumps({
                    "sdk": f"mcp {importlib.metadata.version('mcp')}",
                    "protocol": initialized.protocolVersion,
                    "server": initialized.serverInfo.name,
                    "tools": len(definitions.tools),
                    "checks": ["initialize", "ping", "list", "search", "source", "raw_hash", "unicode", "crlf", "tool_failure", "unknown_tool"],
                    "agent_evidence": False,
                }))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: uv run scripts/mcp_smoke.py /path/to/graphharness")
    asyncio.run(smoke(str(Path(sys.argv[1]).resolve())))
