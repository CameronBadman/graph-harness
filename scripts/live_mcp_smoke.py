# /// script
# requires-python = ">=3.11"
# dependencies = ["mcp==1.30.0"]
# ///

import argparse
import asyncio
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


async def main(args):
    root = Path(args.root).resolve()
    env = dict(os.environ)
    env["GRAPHHARNESS_RUNTIME_DIR"] = str(Path(args.runtime_directory).resolve())
    parameters = StdioServerParameters(command=str(Path(args.launcher).resolve()), args=["bridge", str(root), "SDK verification (script)"], env=env)
    async with stdio_client(parameters) as (read, write):
        async with ClientSession(read, write) as session:
            initialized = await session.initialize()
            assert initialized.serverInfo.name == "graphharness-live-bridge"
            assert initialized.protocolVersion == "2025-06-18"
            await session.send_ping()
            definitions = await session.list_tools()
            names = {tool.name for tool in definitions.tools}
            assert {"search_graph", "get_source"} <= names
            assert not {"plan_edit", "apply_edit", "validate_edit", "rename_node"} & names
            capabilities = await session.call_tool("get_capabilities")
            assert not capabilities.isError
            assert not capabilities.structuredContent["coordinated_writes"]
            found = await session.call_tool("search_graph", {"query": "TicketInventory.reserve", "kind": "method"})
            assert not found.isError and len(found.structuredContent["results"]) == 1
            node = found.structuredContent["results"][0]
            source = await session.call_tool("get_source", {"node_id": node["id"]})
            assert not source.isError and "remaining" in source.structuredContent["source"]
            assert source.structuredContent["file_hash"] == hashlib.sha256((root / node["file"]).read_bytes()).hexdigest()
            disabled = await session.call_tool("apply_edit", {})
            assert disabled.isError
            assert disabled.structuredContent["error"]["code"] == "unsupported_operation"
            assert disabled.structuredContent["operation_id"]
            print(json.dumps({"sdk": importlib.metadata.version("mcp"), "server": initialized.serverInfo.name, "protocol": initialized.protocolVersion,
                              "tools": len(names), "checks": ["initialize", "ping", "list", "omitted_arguments", "capabilities", "search", "source_hash", "writes_disabled", "structured_error"], "agent_evidence": False}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("launcher")
    parser.add_argument("root")
    parser.add_argument("runtime_directory")
    asyncio.run(main(parser.parse_args()))
