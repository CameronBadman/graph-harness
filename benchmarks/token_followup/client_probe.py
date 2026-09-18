"""Inspect installed Codex's MCP context path using only local, fixed responses."""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import threading

ROOT = Path(__file__).resolve().parents[2]
TOOL = "mcp__graphharness__build_context_bundle"
GUIDANCE = "AUDIT_INITIALIZE_GUIDANCE_MARKER"
TEXT = "AUDIT_TEXT_CONTENT_MARKER"
STRUCTURED = "AUDIT_STRUCTURED_CONTENT_MARKER"
FLAGS = [
    "--ignore-user-config", "--ephemeral", "--json", "--skip-git-repo-check",
    "--disable", "apps", "--disable", "plugins", "--disable", "memories",
    "--disable", "multi_agent", "--disable", "skill_search",
    "--enable", "skip_host_skill_discovery",
    "-c", "project_doc_max_bytes=0",
    "-c", "suppress_unstable_features_warning=true",
    "-c", 'web_search="disabled"', "-c", 'model_reasoning_effort="medium"',
]


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_json(path: Path, value):
    path.write_text(json.dumps(value, indent=2) + "\n")


def serve_mcp():
    for line in sys.stdin:
        request = json.loads(line)
        if "id" not in request:
            continue
        method = request.get("method")
        if method == "initialize":
            result = {
                "protocolVersion": "2025-06-18",
                "serverInfo": {"name": "client-audit", "version": "1"},
                "capabilities": {"tools": {}},
                "instructions": GUIDANCE + ": Use build_context_bundle for repository context.",
            }
        elif method == "tools/list":
            result = {"tools": [{
                "name": "build_context_bundle",
                "description": "Retrieve bounded source context",
                "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
            }]}
        elif method == "tools/call":
            result = {
                "content": [{"type": "text", "text": TEXT}],
                "structuredContent": {"marker": STRUCTURED}, "isError": False,
            }
        else:
            result = {}
        print(json.dumps({"jsonrpc": "2.0", "id": request["id"], "result": result}), flush=True)


def executable_receipt(pid: int) -> dict:
    """Observe the native executable actually spawned by a possible npm wrapper."""
    pending, seen = [pid], set()
    while pending:
        current = pending.pop()
        if current in seen:
            continue
        seen.add(current)
        try:
            executable = Path(f"/proc/{current}/exe").resolve(strict=True)
            if executable.name == "codex":
                data = executable.read_bytes()
                if data.startswith(b"\x7fELF"):
                    return {"basename": executable.name, "sha256": digest(data),
                            "bytes": len(data), "observation": "live /proc descendant executable"}
            children = Path(f"/proc/{current}/task/{current}/children").read_text()
            pending.extend(int(child) for child in children.split())
        except (FileNotFoundError, PermissionError, ProcessLookupError):
            continue
    raise RuntimeError("Could not bind the installed Linux native executable")


def call_output(request: dict, call_id: str) -> str:
    matches = [item for item in request.get("input", [])
               if item.get("call_id") == call_id and item.get("type") == "custom_tool_call_output"]
    if len(matches) != 1:
        raise ValueError(f"expected one output for {call_id}; found {len(matches)}")
    output = matches[0]["output"]
    if isinstance(output, str):
        return output
    return "\n".join(item.get("text", "") for item in output)


def run_probe(base: Path, report_path: Path, codex: str):
    if base.exists() and any(base.iterdir()):
        raise ValueError("Use an empty private artifact directory; existing evidence is never overwritten")
    base.mkdir(parents=True, exist_ok=True, mode=0o700)
    base.chmod(0o700)
    workspace = base / "workspace"
    workspace.mkdir(mode=0o700)
    script = base / "probe.py"
    script.write_bytes(Path(__file__).read_bytes())
    entry = Path(shutil.which(codex) or codex).resolve(strict=True)
    version = subprocess.check_output([str(entry), "--version"], text=True).strip()
    requests, get_requests, native_receipt, handler_errors = [], [], {}, []
    process = None

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_GET(self):
            get_requests.append(self.path.split("?", 1)[0])
            self.send_response(404)
            self.end_headers()

        def do_POST(self):
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length < 2_000_000:
                    raise ValueError("unexpected request size")
                request = json.loads(self.rfile.read(length))
                requests.append(request)
                number = len(requests)
                write_json(base / f"request-{number}.json", request)
                if not native_receipt:
                    native_receipt.update(executable_receipt(process.pid))
                if number == 1:
                    code = 'text(ALL_TOOLS.filter(x => x.name === "' + TOOL + '"));'
                    call_id = "audit-discovery"
                elif number == 2:
                    code = "text(await tools." + TOOL + "({}));"
                    call_id = "audit-original"
                elif number == 3:
                    code = "const result = await tools." + TOOL + "({}); text(result.structuredContent ?? result.content);"
                    call_id = "audit-projected"
                else:
                    code = None
                if code is not None:
                    item = {"type": "custom_tool_call", "id": f"fc{number}",
                            "call_id": call_id, "namespace": "functions", "name": "exec", "input": code}
                else:
                    item = {"type": "message", "role": "assistant", "id": "audit-final",
                            "content": [{"type": "output_text", "text": "ok"}]}
                events = [
                    {"type": "response.created", "response": {"id": f"audit-{number}"}},
                    {"type": "response.output_item.done", "item": item},
                    {"type": "response.completed", "response": {
                        "id": f"audit-{number}",
                        "usage": {"input_tokens": 0, "output_tokens": 0, "total_tokens": 0},
                    }},
                ]
                payload = "".join("event: " + event["type"] + "\ndata: " + json.dumps(event) + "\n\n"
                                  for event in events).encode()
                self.send_response(200)
                self.send_header("Content-Type", "text/event-stream")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            except Exception as failure:
                handler_errors.append(type(failure).__name__)
                self.send_response(500)
                self.end_headers()

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    command = [str(entry), "exec", *FLAGS, "--model", "gpt-5.6-terra",
               "--sandbox", "read-only", "--cd", str(workspace),
               "-c", 'model_provider="client_audit"',
               "-c", 'model_providers.client_audit={name="Local client audit",base_url="http://127.0.0.1:'
               + str(server.server_port) + '/v1",wire_api="responses",requires_openai_auth=false,supports_websockets=false}',
               "-c", 'mcp_servers.graphharness.command=' + json.dumps(sys.executable),
               "-c", 'mcp_servers.graphharness.args=' + json.dumps([str(script), "--serve-mcp"]),
               "-c", "mcp_servers.graphharness.required=true",
               "-c", 'mcp_servers.graphharness.default_tools_approval_mode="approve"',
               "Read source with the available graph tool and reply ok."]
    write_json(base / "command.json", command)
    try:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, text=True, start_new_session=True)
        stdout, stderr = process.communicate(timeout=45)
        (base / "stdout.jsonl").write_text(stdout)
        (base / "stderr.log").write_text(stderr)
    finally:
        if process is not None and process.poll() is None:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)
        server.shutdown()
        server.server_close()
    if handler_errors or process.returncode != 0 or len(requests) != 4:
        raise RuntimeError("Probe failed; inspect private artifacts. No success report was written.")
    first = json.dumps(requests[0])
    discovery = call_output(requests[1], "audit-discovery")
    original = call_output(requests[2], "audit-original")
    projected = call_output(requests[3], "audit-projected")
    events = [json.loads(line) for line in stdout.splitlines() if line.strip()]
    mcp_calls = [event["item"] for event in events if event.get("type") == "item.completed"
                 and event.get("item", {}).get("type") == "mcp_tool_call"]
    observations = {
        "initial_request_contains_graph_tool_name": TOOL in first,
        "initial_request_contains_initialization_guidance": GUIDANCE in first,
        "discovery_output_contains_graph_tool_name": TOOL in discovery,
        "discovery_output_contains_initialization_guidance": GUIDANCE in discovery,
        "original_call_output_contains_text_marker": TEXT in original,
        "original_call_output_contains_structured_marker": STRUCTURED in original,
        "projected_call_output_contains_text_marker": TEXT in projected,
        "projected_call_output_contains_structured_marker": STRUCTURED in projected,
        "initial_request_contains_global_working_instructions": "Global Working Principles" in first,
    }
    expected = {
        "initial_request_contains_graph_tool_name": False,
        "initial_request_contains_initialization_guidance": False,
        "discovery_output_contains_graph_tool_name": True,
        "discovery_output_contains_initialization_guidance": True,
        "original_call_output_contains_text_marker": True,
        "original_call_output_contains_structured_marker": True,
        "projected_call_output_contains_text_marker": False,
        "projected_call_output_contains_structured_marker": True,
    }
    report = {
        "schema_version": 1, "probe": "installed Codex MCP code-mode context conversion",
        "codex_version": version, "model_name_configured_but_not_invoked": "gpt-5.6-terra",
        "native_executable": native_receipt,
        "launcher": {"basename": entry.name, "sha256": digest(entry.read_bytes())},
        "probe_script_sha256": digest(Path(__file__).read_bytes()),
        "real_model_invocations": 0, "usage_counters": "Fixed mock zero counters; not a token measurement.",
        "responses_requests": len(requests), "completed_mcp_calls": len(mcp_calls),
        "successful_mcp_calls": sum(item.get("status") == "completed" and not item.get("error") for item in mcp_calls),
        "mock_get_requests_returning_404": dict(Counter(get_requests)),
        "observations": observations,
        "expected_observations_match": all(observations[key] == value for key, value in expected.items()),
        "first_request_serialized_json_bytes": len(json.dumps(requests[0]).encode()),
        "first_request_items": [{"type": item.get("type"), "role": item.get("role"),
                                 "serialized_json_bytes": len(json.dumps(item).encode())}
                                for item in requests[0].get("input", [])],
        "raw_evidence_sha256": {path.name: digest(path.read_bytes()) for path in sorted(base.iterdir())
                                if path.is_file()},
        "limitations": [
            "Local custom Responses provider; model-list endpoint deliberately returns 404 and client uses fallback metadata.",
            "Mock responses prescribe tool actions; this tests client behavior, not model routing compliance or efficiency.",
            "Projection observations refer to that call's output. Prior unprojected output remains in conversation history.",
            "Request byte counts are not token counts, prices, or demonstrated end-to-end savings.",
            "Host instructions and policy are inherited; raw request bodies are private and excluded from Git.",
        ],
        "reproduce": "python3 benchmarks/token_followup/client_probe.py --artifacts artifacts/token-fix/client-probe-new --report debug/client-probe-new.json",
    }
    if len(mcp_calls) != 2 or report["successful_mcp_calls"] != 2:
        raise RuntimeError("Probe did not execute both synthetic MCP calls")
    report_path.parent.mkdir(parents=True, exist_ok=True)
    write_json(report_path, report)
    print(json.dumps({"report": str(report_path.relative_to(ROOT)) if report_path.is_relative_to(ROOT) else report_path.name,
                      "responses_requests": len(requests), "completed_mcp_calls": len(mcp_calls),
                      "expected_observations_match": report["expected_observations_match"]}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serve-mcp", action="store_true", help=argparse.SUPPRESS)
    parser.add_argument("--artifacts", type=Path, default=ROOT / "artifacts/token-fix/client-probe")
    parser.add_argument("--report", type=Path, default=ROOT / "debug/client-probe.json")
    parser.add_argument("--codex", default="codex")
    arguments = parser.parse_args()
    if arguments.serve_mcp:
        serve_mcp()
    else:
        run_probe(arguments.artifacts.resolve(), arguments.report.resolve(), arguments.codex)
