from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
import os
from pathlib import Path
import random
import signal
import subprocess
import tempfile
import time
import urllib.request

from tasks import TASK_IDS, contents, create_task, evaluate, manifest


ROOT = Path(__file__).resolve().parents[2]
ARMS = ("native", "full", "lean", "no_bundle")
LEAN = ("get_capabilities", "search_graph", "get_source", "get_node_detail", "build_context_bundle")
MODEL = "gpt-5.6-terra"
SEED = 20260918
COMMON_PROMPT = (
    "Work only within this task workspace. Use the available tools efficiently to inspect "
    "the actual code and complete the task. Do not access files outside this workspace, "
    "other sessions, benchmark code, or external resources. Do not delegate. "
    "Keep the final answer to the requested JSON object.\n\n"
)
FLAGS = [
    "--ignore-user-config", "--ephemeral", "--json", "--skip-git-repo-check",
    "--disable", "apps", "--disable", "plugins", "--disable", "memories",
    "--disable", "multi_agent", "--disable", "skill_search",
    "--enable", "skip_host_skill_discovery",
    "-c", 'web_search="disabled"', "-c", 'model_reasoning_effort="medium"',
    "-c", "project_doc_max_bytes=0", "-c", "suppress_unstable_features_warning=true",
]


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def stable(value) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode()


def write_json(path: Path, value):
    staged = path.with_suffix(path.suffix + ".tmp")
    staged.write_text(json.dumps(value, indent=2) + "\n")
    staged.replace(path)


def parse_usage(events: list[dict]) -> dict:
    completed = [event for event in events if event.get("type") == "turn.completed"]
    if len(completed) != 1:
        raise ValueError(f"expected exactly one turn.completed; found {len(completed)}")
    usage = completed[0].get("usage", {})
    names = ("input_tokens", "cached_input_tokens", "output_tokens", "reasoning_output_tokens")
    if any(type(usage.get(name)) is not int or usage[name] < 0 for name in names):
        raise ValueError("missing or invalid usage counter")
    if usage["cached_input_tokens"] > usage["input_tokens"]:
        raise ValueError("cached input exceeds input")
    if usage["reasoning_output_tokens"] > usage["output_tokens"]:
        raise ValueError("reasoning exceeds output")
    writes = usage.get("cache_write_input_tokens")
    if writes is not None and (type(writes) is not int or writes < 0):
        raise ValueError("invalid cache-write counter")
    return {
        **{name: usage[name] for name in names},
        "cache_write_input_tokens": writes,
        "uncached_input_tokens": usage["input_tokens"] - usage["cached_input_tokens"],
        "total_tokens": usage["input_tokens"] + usage["output_tokens"],
    }


def diagnostics(events: list[dict]) -> dict:
    terminal = {}
    for event in events:
        if event.get("type") == "item.completed":
            item = event.get("item", {})
            terminal[item.get("id", str(len(terminal)))] = item
    counts = Counter()
    shell_bytes = mcp_bytes = tool_errors = 0
    bundle_arguments = []
    for item in terminal.values():
        kind = item.get("type")
        if kind == "command_execution":
            counts["shell"] += 1
            shell_bytes += len(item.get("aggregated_output", "").encode())
            if item.get("exit_code", 0) != 0:
                tool_errors += 1
        elif kind == "mcp_tool_call":
            name = item.get("tool", "unknown")
            counts["mcp:" + name] += 1
            mcp_bytes += len(stable(item.get("result")))
            tool_errors += int(bool(item.get("error")) or item.get("status") == "failed")
            if name == "build_context_bundle":
                args = item.get("arguments", {})
                if isinstance(args, str):
                    try:
                        args = json.loads(args)
                    except ValueError:
                        args = {}
                bundle_arguments.append({"token_budget": args.get("token_budget", "default")})
    return {
        "tool_calls": dict(counts), "shell_output_bytes": shell_bytes,
        "mcp_result_json_bytes": mcp_bytes, "tool_errors": tool_errors,
        "bundle_arguments": bundle_arguments,
        "retrieval_used": any(key.startswith("mcp:") and key != "mcp:get_capabilities" for key in counts),
        "payload_measurement": "Serialized observable JSON/UTF-8 bytes; not model tokens.",
    }


def schedule() -> list[dict]:
    rng = random.Random(SEED)
    blocks = [(task, repetition) for task in TASK_IDS for repetition in (1, 2)]
    rng.shuffle(blocks)
    result = []
    for task, repetition in blocks:
        arms = list(ARMS)
        rng.shuffle(arms)
        for arm in arms:
            result.append({"id": f"{task}-{repetition}-{arm}", "task": task,
                           "repetition": repetition, "arm": arm})
    return result


def current_identity(launcher: Path) -> dict:
    files = [Path(__file__), Path(__file__).with_name("tasks.py"),
             Path(__file__).with_name("PROTOCOL.md")]
    identity = {str(path.relative_to(ROOT)): digest(path.read_bytes()) for path in files}
    distribution = launcher.parent.parent
    installed = [launcher]
    for folder in ("lib", "parsers"):
        installed += [path for path in (distribution / folder).rglob("*")
                      if path.is_file() and "__pycache__" not in path.parts]
    identity.update({"distribution/" + str(path.relative_to(distribution)): digest(path.read_bytes())
                     for path in sorted(installed)})
    return identity


def prepare(base: Path, launcher: Path, codex: str):
    if base.exists() and any(base.iterdir()):
        raise ValueError("preparation requires an empty artifact directory")
    base.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(base, 0o700)
    specs = {}
    with tempfile.TemporaryDirectory(prefix="graphharness-ablation-spec-") as directory:
        for task in TASK_IDS:
            spec = create_task(task, Path(directory) / task)
            specs[task] = {**spec, "prompt": COMMON_PROMPT + spec["prompt"]}
    frozen = {
        "schema_version": 1, "model": MODEL, "reasoning_effort": "medium",
        "codex_version": subprocess.check_output([codex, "--version"], text=True).strip(),
        "repository_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
        "artifact_hashes": current_identity(launcher), "flags": FLAGS,
        "schedule_seed": SEED, "schedule": schedule(), "tasks": specs,
        "timeout_seconds": 240, "model_identity": "Configured alias; no provider attestation.",
    }
    write_json(base / "frozen.json", frozen)
    print(json.dumps({"prepared": True, "scored_runs": len(frozen["schedule"]),
                      "frozen_sha256": digest((base / "frozen.json").read_bytes())}), flush=True)


def request(descriptor: dict, path: str, method="GET", payload=None, credential=None):
    body = None if payload is None else stable(payload)
    req = urllib.request.Request(descriptor["endpoint"] + path, data=body, method=method)
    req.add_header("Authorization", "Bearer " + (credential or descriptor["bootstrap_credential"]))
    req.add_header("X-GraphHarness-Epoch", descriptor["daemon_epoch"])
    req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=15) as response:
        return json.load(response)


def stop(process):
    if process is None or process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=3)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)


def start_daemon(launcher: Path, workspace: Path, runtime: Path, log):
    runtime.mkdir(mode=0o700)
    started = time.monotonic()
    process = subprocess.Popen([str(launcher), "daemon", str(workspace)],
                               env=dict(os.environ, GRAPHHARNESS_RUNTIME_DIR=str(runtime)),
                               stdout=subprocess.DEVNULL, stderr=log, start_new_session=True)
    try:
        descriptor = None
        while time.monotonic() - started < 90:
            if process.poll() is not None:
                raise RuntimeError("daemon exited before readiness")
            files = list(runtime.glob("*.json"))
            if files:
                descriptor = json.loads(files[0].read_text())
                break
            time.sleep(0.1)
        if descriptor is None:
            raise RuntimeError("daemon startup timeout")
        session = request(descriptor, "/sessions", "POST", {
            "schema_version": 1, "agent_label": "Benchmark setup",
            "client": {"name": "token-ablation", "version": "1"},
        })
        credential = session["session_credential"]
        try:
            definitions = request(descriptor, "/tools", credential=credential)["tools"]
            capabilities = request(descriptor, "/tools/call", "POST", {
                "schema_version": 1, "name": "get_capabilities", "arguments": {}, "operation_id": "1",
            }, credential)["result"]
        finally:
            request(descriptor, "/sessions/current", "DELETE", credential=credential)
        return process, definitions, capabilities, round(time.monotonic() - started, 3)
    except BaseException:
        stop(process)
        raise


def execute(spec: dict, run: dict, base: Path, launcher: Path, codex: str, calibration=False):
    folder = base / run["id"]
    folder.mkdir(mode=0o700)
    workspace = folder / "workspace"
    if calibration:
        workspace.mkdir()
        (workspace / "Gauge.java").write_text("public class Gauge { public int reading() { return 37; } }\n")
    else:
        generated = create_task(run["task"], workspace)
        if generated["manifest_sha256"] != spec["manifest_sha256"]:
            raise RuntimeError("fixture differs from frozen manifest")
    schema = folder / "schema.json"
    write_json(schema, spec["response_schema"])
    prompt = spec["prompt"]
    (folder / "prompt.txt").write_text(prompt)
    command = [codex, "exec", *FLAGS, "--model", MODEL,
               "--sandbox", "workspace-write" if spec["writable"] else "read-only",
               "--cd", str(workspace), "--output-schema", str(schema),
               "--output-last-message", str(folder / "answer.json")]
    record = {**run, "prompt_sha256": digest(prompt.encode()),
              "schema_sha256": digest(stable(spec["response_schema"])),
              "fixture_sha256": spec.get("manifest_sha256"),
              "started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
              "usage": None, "correctness": {"passed": False, "details": "not evaluated"},
              "infrastructure_failure": False, "setup_seconds": 0,
              "evaluator_sha256": digest(Path(__file__).with_name("tasks.py").read_bytes()),
              "frozen_sha256": digest((base / "frozen.json").read_bytes()),
              "workspace_before_sha256": manifest(workspace),
              "access_audit": {"status": "pending", "note": "Manual transcript audit required before comparison."}}
    daemon = process = None
    with (folder / "daemon.log").open("w") as daemon_log:
        try:
            if run["arm"] != "native":
                runtime = folder / "runtime"
                daemon, definitions, capabilities, setup = start_daemon(launcher, workspace, runtime, daemon_log)
                selected = definitions
                if run["arm"] != "full":
                    names = LEAN if run["arm"] == "lean" else LEAN[:-1]
                    selected = [tool for tool in definitions if tool["name"] in names]
                    if {tool["name"] for tool in selected} != set(names):
                        raise RuntimeError("declared tools unavailable")
                    command += ["-c", "mcp_servers.graphharness.enabled_tools=" + json.dumps(list(names))]
                command += [
                    "-c", "mcp_servers.graphharness.command=" + json.dumps(str(launcher)),
                    "-c", "mcp_servers.graphharness.args=" + json.dumps(["bridge", str(workspace), "Benchmark client"]),
                    "-c", "mcp_servers.graphharness.env.GRAPHHARNESS_RUNTIME_DIR=" + json.dumps(str(runtime)),
                    "-c", "mcp_servers.graphharness.required=true",
                    "-c", 'mcp_servers.graphharness.default_tools_approval_mode="approve"',
                ]
                record.update(setup_seconds=setup, tools=[tool["name"] for tool in selected],
                              tool_schema_sha256=digest(stable(selected)),
                              tool_schema_json_bytes=len(stable(selected)))
                record["backend"] = {key: capabilities.get(key) for key in
                                     ("analysis_engine", "engine_version", "languages", "language_adapters", "semantic_level")}
                write_json(folder / "capabilities.json", capabilities)
                write_json(folder / "tool-definitions.json", selected)
            else:
                record.update(tools=[], tool_schema_json_bytes=0, tool_schema_sha256=digest(stable([])))
            write_json(folder / "command.json", command + ["-"])
            start = time.monotonic()
            with (folder / "events.jsonl").open("w") as stdout, (folder / "stderr.log").open("w") as stderr:
                process = subprocess.Popen(command + ["-"], stdin=subprocess.PIPE, stdout=stdout, stderr=stderr,
                                           text=True, start_new_session=True)
                try:
                    process.communicate(prompt, timeout=240)
                    record["timed_out"] = False
                except subprocess.TimeoutExpired:
                    record["timed_out"] = True
                    stop(process)
            record.update(elapsed_seconds=round(time.monotonic() - start, 3), exit_code=process.returncode)
            raw = (folder / "events.jsonl").read_bytes()
            record["raw_events_sha256"] = digest(raw)
            events = [json.loads(line) for line in raw.splitlines() if line.strip()]
            record["diagnostics"] = diagnostics(events)
            try:
                record["usage"] = parse_usage(events)
            except ValueError as error:
                record["usage_error"] = str(error)
                if not record["timed_out"]:
                    record["infrastructure_failure"] = True
                    record["failure_category"] = "missing_or_invalid_terminal_usage"
            text = raw.decode(errors="replace") + (folder / "stderr.log").read_text()
            if any(marker in text.lower() for marker in ("usage limit", "quota exceeded", "unauthorized", "authentication failed")):
                record["infrastructure_failure"] = True
                record["failure_category"] = "quota_or_authentication"
            answer_file = folder / "answer.json"
            answer = answer_file.read_text() if answer_file.exists() else ""
            record["answer_sha256"] = digest(answer.encode())
            if calibration:
                try:
                    correct = json.loads(answer) == {"reading": 37}
                except ValueError:
                    correct = False
                route = record["diagnostics"]["tool_calls"]
                used_expected_route = (route.get("shell", 0) > 0 if run["arm"] == "native"
                                       else route.get("mcp:get_source", 0) > 0)
                record["correctness"] = {"passed": correct and used_expected_route,
                                         "details": "correct source value and required calibration route"}
            else:
                record["correctness"] = evaluate(run["task"], workspace, answer)
            record["instrumentation_valid"] = (record["usage"] is not None and process.returncode == 0
                                                and not record["timed_out"] and not record["infrastructure_failure"])
            record["valid_measurement"] = False
        except Exception as error:
            record.update(infrastructure_failure=True, valid_measurement=False,
                          failure_category=type(error).__name__)
        finally:
            stop(process)
            stop(daemon)
            record["workspace_after_sha256"] = manifest(workspace)
            record["workspace_file_hashes_after"] = {name: digest(data) for name, data in contents(workspace).items()}
    write_json(folder / "record.json", record)
    return record


def load_frozen(base: Path, launcher: Path, codex: str):
    frozen = json.loads((base / "frozen.json").read_text())
    if current_identity(launcher) != frozen["artifact_hashes"]:
        raise ValueError("implementation changed after preparation")
    if subprocess.check_output([codex, "--version"], text=True).strip() != frozen["codex_version"]:
        raise ValueError("Codex version changed after preparation")
    return frozen


def calibrate(base: Path, launcher: Path, codex: str):
    load_frozen(base, launcher, codex)
    results = []
    for arm in ARMS:
        route = ("Use shell to read Gauge.java." if arm == "native" else
                 "Use GraphHarness search_graph to find Gauge.reading and get_source to read it. Do not use shell.")
        spec = {"prompt": route + ' Return exactly {"reading": integer} with its return value.',
                "writable": False, "response_schema": {
                    "type": "object", "properties": {"reading": {"type": "integer"}},
                    "required": ["reading"], "additionalProperties": False}}
        record = execute(spec, {"id": "calibration-" + arm, "arm": arm, "task": "calibration"},
                         base, launcher, codex, calibration=True)
        results.append(record)
        write_json(base / "calibration.json", results)
        print(json.dumps({"calibration": arm, "correct": record["correctness"]["passed"],
                          "valid": record.get("valid_measurement"), "failure": record.get("failure_category")}), flush=True)
        if not record.get("instrumentation_valid") or not record["correctness"]["passed"]:
            raise RuntimeError("calibration failed; scored runs not authorized by the protocol")


def run_all(base: Path, launcher: Path, codex: str):
    frozen = load_frozen(base, launcher, codex)
    calibration = json.loads((base / "calibration.json").read_text())
    if len(calibration) != 4 or not all(row.get("instrumentation_valid") and row["correctness"]["passed"] for row in calibration):
        raise ValueError("complete successful calibration required")
    reviews = json.loads((base / "access-review.json").read_text())
    for row in calibration:
        review = reviews.get(row["id"], {})
        if review.get("raw_events_sha256") != row["raw_events_sha256"] or review.get("passed") is not True:
            raise ValueError("calibration requires a bound passing transcript access review")
    output = base / "results.json"
    records = json.loads(output.read_text()) if output.exists() else []
    completed = {record["id"] for record in records}
    consecutive_infrastructure = 0
    for run in frozen["schedule"]:
        if run["id"] in completed:
            continue
        if (base / run["id"]).exists():
            raise ValueError("unrecorded attempt exists; investigate without silently repeating it")
        record = execute(frozen["tasks"][run["task"]], run, base, launcher, codex)
        records.append(record)
        write_json(output, records)
        print(json.dumps({"completed": len(records), "planned": len(frozen["schedule"]), "run": run["id"],
                          "correct": record["correctness"]["passed"], "usage": record["usage"],
                          "valid": record.get("valid_measurement"), "failure": record.get("failure_category")}), flush=True)
        consecutive_infrastructure = consecutive_infrastructure + 1 if record["infrastructure_failure"] else 0
        if consecutive_infrastructure >= 2:
            print(json.dumps({"stopped": "two consecutive infrastructure failures"}), flush=True)
            break


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("prepare", "calibrate", "run"))
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--launcher", type=Path, default=ROOT / "build/install/graphharness/bin/graphharness")
    parser.add_argument("--codex", default="codex")
    args = parser.parse_args()
    operation = {"prepare": prepare, "calibrate": calibrate, "run": run_all}[args.action]
    operation(args.artifacts.resolve(), args.launcher.resolve(), args.codex)
