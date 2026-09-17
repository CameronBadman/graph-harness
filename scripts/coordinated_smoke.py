# /// script
# requires-python = ">=3.11"
# dependencies = ["mcp==1.30.0"]
# ///

import argparse
import asyncio
from contextlib import AsyncExitStack
from datetime import timedelta
import json
import os
from pathlib import Path
import tempfile

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


async def main(args):
    launcher, ui = str(Path(args.launcher).resolve()), str(Path(args.ui).resolve())
    with tempfile.TemporaryDirectory(prefix="graphharness-coordination-") as directory:
        base = Path(directory)
        root, runtime = base / "fixture", base / "runtime"
        root.mkdir()
        runtime.mkdir(mode=0o700)
        (root / "Counter.java").write_text("public class Counter {\n public int value() { return 1; }\n}\n")
        (root / "CounterTest.java").write_text('public class CounterTest { public static void main(String[] args) { if (new Counter().value() != 2) throw new AssertionError("Expected the requested value 2"); System.out.println("Counter test passed"); } }\n')
        env = dict(os.environ, GRAPHHARNESS_RUNTIME_DIR=str(runtime))
        daemon = await asyncio.create_subprocess_exec(launcher, "daemon", str(root), ui, "--allow-edits", env=env, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE)
        try:
            while True:
                line = await asyncio.wait_for(daemon.stderr.readline(), 90)
                if b"GraphHarness Live:" in line:
                    break
                if not line:
                    raise RuntimeError("Daemon failed before startup")
            async with AsyncExitStack() as stack:
                sessions = []
                for label in ("SDK writer A (script)", "SDK writer B (script)"):
                    parameters = StdioServerParameters(command=launcher, args=["bridge", str(root), label], env=env)
                    read, write = await stack.enter_async_context(stdio_client(parameters))
                    session = await stack.enter_async_context(ClientSession(read, write, read_timeout_seconds=timedelta(seconds=150)))
                    await session.initialize()
                    sessions.append(session)
                a, b = sessions
                async def call(session, name, arguments=None):
                    result = await session.call_tool(name, arguments)
                    if result.isError:
                        raise AssertionError(f"{name}: {result.structuredContent}")
                    return result.structuredContent
                capabilities = await call(a, "get_capabilities")
                assert capabilities["coordinated_writes"]
                validation_args = {"command": ["sh", "-c", "mkdir -p build && javac -d build Counter.java CounterTest.java && java -cp build CounterTest"], "mode": "test"}
                before = await call(a, "validate_project", validation_args)
                assert before["exit_code"] != 0 and not before["command_passed"]
                found = await call(a, "search_graph", {"query": "Counter.value", "kind": "method"})
                node = found["results"][0]
                source = await call(a, "get_source", {"node_id": node["id"], "include_context": 0})
                plan = await call(a, "plan_edit", {"node_id": node["id"], "snapshot_id": source["snapshot_id"], "expected_file_hash": source["file_hash"], "new_body": "return 2;"})
                lease = await call(a, "acquire_edit_lease", {"file": node["file"]})
                denied = await b.call_tool("acquire_edit_lease", {"file": node["file"]})
                assert denied.isError and denied.structuredContent["error"]["code"] == "lease_busy"
                assert denied.structuredContent["error"]["details"]["owner"] == lease["session_id"]
                foreign = await b.call_tool("apply_edit", {"edit_id": plan["edit_id"], "lease_id": lease["lease_id"], "generation": lease["generation"]})
                assert foreign.isError and foreign.structuredContent["error"]["code"] == "not_owner"
                applied = await call(a, "apply_edit", {"edit_id": plan["edit_id"], "lease_id": lease["lease_id"], "generation": lease["generation"]})
                assert applied["committed"] and "return 2;" in (root / "Counter.java").read_text()
                await call(a, "release_edit_lease", {"lease_id": lease["lease_id"], "generation": lease["generation"]})
                after = await call(a, "validate_project", validation_args)
                assert after["command_passed"] and after["exit_code"] == 0 and not after["stale"]
                assert after["network_namespace"] and after["manifest_id"] != before["manifest_id"]
                assert after["filesystem_namespace"] and after["resource_limits"]["process_count"] == 64
                assert after["isolation_limitations"]
                print(json.dumps({"agent_evidence": False, "protocol": "official MCP SDK1.30.0", "checks": ["two_bridges", "capability", "failing_project_test", "parser_plan", "lease_conflict", "foreign_plan_rejected", "committed_edit", "release", "passing_project_test", "versioned_manifests", "offline_validation"],
                                  "before_exit": before["exit_code"], "after_exit": after["exit_code"], "before_manifest": before["manifest_id"], "after_manifest": after["manifest_id"],
                                  "filesystem_isolated": after["filesystem_namespace"], "resource_limits": after["resource_limits"], "isolation_limitations": after["isolation_limitations"]}))
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
