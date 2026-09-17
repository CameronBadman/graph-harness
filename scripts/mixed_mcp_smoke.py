# /// script
# requires-python = ">=3.11"
# dependencies = ["mcp==1.30.0"]
# ///

import argparse
import asyncio
from contextlib import AsyncExitStack
from datetime import timedelta
import hashlib
import json
import os
from pathlib import Path
import tempfile
import time

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


async def main(args):
    launcher, ui = str(Path(args.launcher).resolve()), str(Path(args.ui).resolve())
    inputs = {
        "Shared.java": "public class Shared { public int compute() { return 1; } }\n",
        "ticket.ts": "// 😀\r\nexport function compute(value: number) {\r\n  return value + 1;\r\n}\r\n",
        "display.js": "// 😀\r\nexport const compute = (value) => `${value}:😀`;\r\n",
        "queue.py": "# 😀\r\nclass Worker:\r\n    def compute(self, value):\r\n        \"\"\"A multiline\r\n        {description} 😀\r\n        \"\"\"\r\n        return value + 1\r\n",
        "extra.py": "def compute(value):\n    return value * 2\n",
        "duplicates.py": "def outer():\n    def inner():\n        return 1\ndef outer():\n    def inner():\n        return 2\n",
        "broken.py": "def missing(:\n    pass\n",
    }
    with tempfile.TemporaryDirectory(prefix="graphharness-mixed-sdk-") as directory:
        base = Path(directory)
        root, runtime = base / "fixture", base / "runtime"
        root.mkdir()
        runtime.mkdir(mode=0o700)
        (base / "parsers").mkdir()
        (base / "parsers" / "python_ast_parser.py").write_text('raise SystemExit("Untrusted working-directory parser was loaded")\n')
        for file, source in inputs.items():
            (root / file).write_bytes(source.encode())
        env = dict(os.environ, GRAPHHARNESS_RUNTIME_DIR=str(runtime))
        daemon = await asyncio.create_subprocess_exec(launcher, "daemon", str(root), ui, cwd=str(base), env=env,
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE)
        try:
            while True:
                line = await asyncio.wait_for(daemon.stderr.readline(), 90)
                if b"GraphHarness Live:" in line:
                    break
                if not line:
                    raise RuntimeError("Daemon failed before startup")
            async with AsyncExitStack() as stack:
                parameters = StdioServerParameters(command=launcher, args=["bridge", str(root), "SDK mixed navigation (script)"], env=env)
                read, write = await stack.enter_async_context(stdio_client(parameters))
                session = await stack.enter_async_context(ClientSession(read, write, read_timeout_seconds=timedelta(seconds=30)))
                await session.initialize()

                async def call(name, arguments=None):
                    result = await session.call_tool(name, arguments)
                    if result.isError:
                        raise AssertionError(f"{name}: {result.structuredContent}")
                    return result.structuredContent

                capabilities = await call("get_capabilities")
                assert {"java", "typescript", "javascript", "python"} <= set(capabilities["languages"]), capabilities
                adapters = capabilities["language_adapters"]
                assert adapters["typescript"]["available"] and adapters["javascript"]["available"] and adapters["python"]["available"]
                assert adapters["python"]["diagnostics"], "Invalid Python must be diagnosed"
                found = (await call("search_graph", {"query": "compute"}))["results"]
                assert len({n["id"] for n in found}) == len(found)
                sources = {}
                for file, language in (("ticket.ts", "typescript"), ("display.js", "javascript"), ("queue.py", "python"), ("extra.py", "python")):
                    node = next(n for n in found if n["file"] == file and n["kind"] != "file")
                    assert node["language"] == language and node["parent"]
                    span = node["byte_span"]
                    result = await call("get_source", {"node_id": node["id"], "include_context": 0})
                    raw = inputs[file].encode()
                    assert result["source"].encode() == raw[span["start"]:span["end"]]
                    assert result["file_hash"] == hashlib.sha256(raw).hexdigest()
                    assert result["language"] == language and result["provenance"] == adapters[language]["parser"]
                    detail = await call("get_node_detail", {"node_id": node["id"]})
                    relationships = detail["incoming_dependencies"] + detail["outgoing_dependencies"]
                    assert relationships and all(r["relationship"] == "contains" for r in relationships)
                    assert all(r["node"]["language"] == language for r in relationships)
                    unsupported = await session.call_tool("get_callers", {"node_id": node["id"]})
                    assert unsupported.isError and unsupported.structuredContent["error"]["code"] == "unsupported_operation"
                    sources[file] = node
                duplicates = (await call("search_graph", {"query": "inner"}))["results"]
                duplicates = [n for n in duplicates if n["file"] == "duplicates.py"]
                assert len(duplicates) == 2 and len({n["id"] for n in duplicates}) == 2
                assert len({n["parent"] for n in duplicates}) == 2
                context = await call("build_context_bundle", {"node_id": sources["queue.py"]["id"], "token_budget": 2048})
                assert any(item["node_id"] == sources["queue.py"]["id"] for item in context["source_slices"])
                assert "structural_context_only" in context["notes"]

                async def await_nodes(query, predicate):
                    deadline = time.monotonic() + 20
                    while time.monotonic() < deadline:
                        nodes = (await call("search_graph", {"query": query}))["results"]
                        if predicate(nodes):
                            return nodes
                        await asyncio.sleep(0.2)
                    raise AssertionError("Watcher did not publish the expected nodes")

                previous = sources["ticket.ts"]
                changed = "\r\n" + inputs["ticket.ts"]
                (root / "ticket.ts").write_bytes(changed.encode())
                changed_hash = hashlib.sha256(changed.encode()).hexdigest()
                refreshed = await await_nodes("compute", lambda ns: any(n["id"] == previous["id"] and n.get("file_hash") == changed_hash for n in ns))
                assert next(n for n in refreshed if n["id"] == previous["id"])["byte_span"]["start"] == previous["byte_span"]["start"] + 2
                (root / "ticket.ts").rename(root / "renamed.ts")
                await await_nodes("compute", lambda ns: any(n["file"] == "renamed.ts" for n in ns) and not any(n["file"] == "ticket.ts" for n in ns))
                (root / "extra.py").unlink()
                await await_nodes("compute", lambda ns: not any(n["file"] == "extra.py" for n in ns))
                (root / "added.py").write_text("def added_symbol():\n    return 7\n")
                await await_nodes("added_symbol", lambda ns: any(n["file"] == "added.py" for n in ns))
                print(json.dumps({"agent_evidence": False, "client": "official MCP SDK1.30.0", "languages": capabilities["languages"],
                    "adapters": adapters, "checks": ["installed_parser_discovery_outside_checkout", "distinct_language_labels", "exact_utf8_spans", "crlf_non_bmp", "hashes", "containment", "no_invented_semantics", "nested_duplicate_identity", "bounded_context", "syntax_diagnostics", "whitespace_identity", "watcher_create_change_rename_delete"],
                    "context_source_characters": sum(len(s["source"]) for s in context["source_slices"])}))
        finally:
            if daemon.returncode is None:
                daemon.terminate()
                try:
                    await asyncio.wait_for(daemon.wait(), 10)
                except asyncio.TimeoutError:
                    daemon.kill()
                    await daemon.wait()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("launcher")
    parser.add_argument("ui")
    asyncio.run(main(parser.parse_args()))
