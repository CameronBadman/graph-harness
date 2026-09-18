from __future__ import annotations

import argparse
import importlib.util
import json
import os
from pathlib import Path
import random
import re
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
OLD = ROOT / "benchmarks/token_ablation"
sys.path.insert(0, str(OLD))
import run as support
sys.path.pop(0)
spec = importlib.util.spec_from_file_location("fresh_tasks", Path(__file__).with_name("tasks.py"))
tasks = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tasks)

ARMS = ("native", "legacy", "repaired", "navigation")
NAVIGATION = {"build_context_bundle", "search_graph", "get_source", "get_source_batch"}
COMMON = support.COMMON_PROMPT + "Put temporary build/probe files under .scratch in this workspace. Do not delete files.\n\n"
digest, stable, write_json = support.digest, support.stable, support.write_json


def identity(launchers):
    paths = [Path(__file__), Path(__file__).with_name("tasks.py"), Path(__file__).with_name("PROTOCOL.md"),
             OLD / "run.py", OLD / "tasks.py"]
    result = {str(p.relative_to(ROOT)): digest(p.read_bytes()) for p in paths}
    entry, native = codex_paths()
    result["codex/launcher"] = digest(entry.read_bytes())
    result["codex/native"] = digest(native.read_bytes())
    host = Path.home() / ".codex"
    if (host / "AGENTS.md").is_file():
        result["host/global-instructions"] = digest((host / "AGENTS.md").read_bytes())
    for path in sorted((host / "rules").rglob("*")):
        if path.is_file():
            result["host/rules/" + str(path.relative_to(host / "rules"))] = digest(path.read_bytes())
    for arm, launcher in launchers.items():
        distribution = Path(launcher).parent.parent
        installed = [Path(launcher)]
        for directory in ("lib", "parsers", "ui"):
            installed += [p for p in (distribution / directory).rglob("*")
                          if p.is_file() and "__pycache__" not in p.parts]
        result.update({arm + "/" + str(p.relative_to(distribution)): digest(p.read_bytes()) for p in sorted(installed)})
    return result


def codex_paths():
    entry = Path(shutil.which("codex")).resolve(strict=True)
    with entry.open("rb") as stream:
        if stream.read(4) == b"\x7fELF":
            return entry, entry
    candidates = []
    for path in entry.parent.parent.rglob("codex"):
        if path.is_file():
            with path.open("rb") as stream:
                if stream.read(4) == b"\x7fELF":
                    candidates.append(path)
    if len(candidates) != 1:
        raise ValueError("expected one installed native Codex executable")
    return entry, candidates[0]


def prepare(base, legacy, repaired):
    if base.exists() and any(base.iterdir()):
        raise ValueError("use an empty artifact directory")
    base.mkdir(parents=True, mode=0o700, exist_ok=True)
    os.chmod(base, 0o700)
    launchers = {"legacy": str(legacy), "repaired": str(repaired), "navigation": str(repaired)}
    generated = {}
    with tempfile.TemporaryDirectory(prefix="gh-fresh-spec-") as directory:
        for task in tasks.TASK_IDS:
            value = tasks.create_task(task, Path(directory) / task)
            generated[task] = {**value, "prompt": COMMON + value["prompt"]}
    rng = random.Random(2026091803)
    blocks = [(task, repetition) for task in tasks.TASK_IDS for repetition in (1, 2)]
    rng.shuffle(blocks)
    schedule = []
    for task, repetition in blocks:
        arms = list(ARMS)
        rng.shuffle(arms)
        schedule += [{"id": f"{task}-{repetition}-{arm}", "task": task, "repetition": repetition, "arm": arm} for arm in arms]
    guidance = subprocess.check_output([str(repaired), "codex-instructions"], text=True).strip()
    frozen = {"schema_version": 1, "model": support.MODEL, "flags": support.FLAGS,
              "integration_guidance": guidance,
              "codex_executable": str(codex_paths()[1]),
              "codex_version": subprocess.check_output(["codex", "--version"], text=True).strip(),
              "repository_head": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip(),
              "artifact_hashes": identity(launchers), "launchers": launchers,
              "schedule_seed": 2026091803, "schedule": schedule, "tasks": generated,
              "timeout_seconds": 240, "model_identity": "Configured alias; no provider attestation."}
    write_json(base / "frozen.json", frozen)
    print(json.dumps({"prepared": True, "runs": len(schedule), "frozen_sha256": digest(stable(frozen))}), flush=True)


def verify(base):
    frozen = json.loads((base / "frozen.json").read_text())
    if identity(frozen["launchers"]) != frozen["artifact_hashes"]:
        raise ValueError("frozen product or evaluator changed")
    if subprocess.check_output(["codex", "--version"], text=True).strip() != frozen["codex_version"]:
        raise ValueError("Codex changed")
    return frozen


def verify_admission(workspace, runtime):
    descriptor = json.loads(next(runtime.glob("*.json")).read_text())
    session = support.request(descriptor, "/sessions", "POST", {
        "schema_version": 1, "agent_label": "Admission check", "client": {"name": "token-followup", "version": "1"}})
    credential = session["session_credential"]
    try:
        summary = support.request(descriptor, "/tools/call", "POST", {
            "schema_version": 1, "name": "get_summary_map", "arguments": {}, "operation_id": "1"}, credential)["result"]
        expected = sum(Path(name).suffix in {".java", ".ts", ".tsx", ".js", ".jsx", ".py"}
                       for name in tasks.contents(workspace))
        observed = summary["project"]["total_files"]
        if expected == 0 or observed != expected:
            raise ValueError("indexed file admission differs from the source manifest")
        return {"expected_source_files": expected, "indexed_source_files": observed}
    finally:
        support.request(descriptor, "/sessions/current", "DELETE", credential=credential)


def capture_contract(launcher, args, runtime):
    messages = [
        {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "contract-check", "version": "1"}}},
        {"jsonrpc": "2.0", "method": "notifications/initialized"},
        {"jsonrpc": "2.0", "id": 2, "method": "tools/list"},
    ]
    result = subprocess.run([str(launcher), *args], input="".join(json.dumps(m) + "\n" for m in messages),
                            text=True, capture_output=True, timeout=30,
                            env=dict(os.environ, GRAPHHARNESS_RUNTIME_DIR=str(runtime)), check=True)
    responses = [json.loads(line) for line in result.stdout.splitlines()]
    if len(responses) != 2 or any("error" in r for r in responses):
        raise ValueError("MCP contract capture failed")
    return {"initialize": responses[0]["result"], "tools": responses[1]["result"]["tools"]}


def execute(base, frozen, row, calibration=False):
    folder = base / row["id"]
    folder.mkdir(mode=0o700)
    workspace = folder / "workspace"
    if calibration:
        workspace.mkdir()
        (workspace / "Gauge.java").write_text("public class Gauge { public int reading() { return 37; } }\n")
        route = ("Use shell to read Gauge.java." if row["arm"] == "native" else
                 "Use GraphHarness search_graph for Gauge.reading and get_source to read it. Do not use shell.")
        if row["arm"] in ("repaired", "navigation"):
            route = "Read Gauge.reading's return value. " + frozen["integration_guidance"]
        spec = {"prompt": COMMON + route + ' Return exactly {"reading": integer}.', "writable": False,
                "response_schema": {"type": "object", "properties": {"reading": {"type": "integer"}},
                                    "required": ["reading"], "additionalProperties": False},
                "manifest_sha256": tasks.manifest(workspace)}
    else:
        spec = dict(frozen["tasks"][row["task"]])
        if tasks.create_task(row["task"], workspace)["manifest_sha256"] != spec["manifest_sha256"]:
            raise ValueError("fixture drift")
        if row["arm"] in ("repaired", "navigation"):
            spec["prompt"] += "\n\n" + frozen["integration_guidance"]
    write_json(folder / "schema.json", spec["response_schema"])
    (folder / "prompt.txt").write_text(spec["prompt"])
    command = [frozen["codex_executable"], "exec", *frozen["flags"], "--model", frozen["model"], "--sandbox",
               "workspace-write" if spec["writable"] else "read-only", "--cd", str(workspace),
               "--output-schema", str(folder / "schema.json"), "--output-last-message", str(folder / "answer.json")]
    record = {**row, "usage": None, "correctness": {"passed": False}, "instrumentation_valid": False,
              "valid_measurement": False, "infrastructure_failure": False, "setup_seconds": 0,
              "fixture_sha256": spec["manifest_sha256"], "workspace_before_sha256": tasks.manifest(workspace),
              "prompt_sha256": digest(spec["prompt"].encode()), "schema_sha256": digest(stable(spec["response_schema"])),
              "frozen_sha256": digest((base / "frozen.json").read_bytes()),
              "evaluator_sha256": digest(Path(__file__).with_name("tasks.py").read_bytes()),
              "started_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    daemon = process = None
    try:
        with (folder / "daemon.log").open("w") as daemon_log:
            if row["arm"] != "native":
                launcher = Path(frozen["launchers"][row["arm"]])
                runtime = folder / "runtime"
                daemon, definitions, capabilities, elapsed = support.start_daemon(launcher, workspace, runtime, daemon_log)
                record["admission"] = verify_admission(workspace, runtime)
                args = ["bridge", str(workspace), "Token follow-up"]
                if row["arm"] == "navigation":
                    args += ["--navigation"]
                contract = capture_contract(launcher, args, runtime)
                selected = contract["tools"]
                expected_tools = NAVIGATION if row["arm"] == "navigation" else {t["name"] for t in definitions}
                if {t["name"] for t in selected} != expected_tools:
                    raise ValueError("bridge tool contract differs from registered profile")
                write_json(folder / "mcp-contract.json", contract)
                record["mcp_contract_sha256"] = digest(stable(contract))
                command += ["-c", "mcp_servers.graphharness.command=" + json.dumps(str(launcher)),
                            "-c", "mcp_servers.graphharness.args=" + json.dumps(args),
                            "-c", "mcp_servers.graphharness.env.GRAPHHARNESS_RUNTIME_DIR=" + json.dumps(str(runtime)),
                            "-c", "mcp_servers.graphharness.required=true",
                            "-c", 'mcp_servers.graphharness.default_tools_approval_mode="approve"']
                record.update(setup_seconds=elapsed, tool_schema_json_bytes=len(stable(selected)),
                              tool_schema_sha256=digest(stable(selected)), tools=[t["name"] for t in selected],
                              backend={key: capabilities.get(key) for key in
                                       ("analysis_engine", "engine_version", "languages", "language_adapters", "semantic_level")})
                write_json(folder / "capabilities.json", capabilities)
                write_json(folder / "tool-definitions.json", selected)
            else:
                record.update(tools=[], tool_schema_json_bytes=0, tool_schema_sha256=digest(stable([])))
            write_json(folder / "command.json", command + ["-"])
            started = time.monotonic()
            with (folder / "events.jsonl").open("w") as stdout, (folder / "stderr.log").open("w") as stderr:
                process = subprocess.Popen(command + ["-"], stdin=subprocess.PIPE, stdout=stdout, stderr=stderr,
                                           text=True, start_new_session=True)
                try:
                    process.communicate(spec["prompt"], timeout=240)
                    record["timed_out"] = False
                except subprocess.TimeoutExpired:
                    record["timed_out"] = True
                    support.stop(process)
            record.update(elapsed_seconds=round(time.monotonic() - started, 3), exit_code=process.returncode)
            raw = (folder / "events.jsonl").read_bytes()
            stderr = (folder / "stderr.log").read_bytes()
            events = [json.loads(line) for line in raw.splitlines() if line.strip()]
            record.update(raw_events_sha256=digest(raw), stderr_sha256=digest(stderr), diagnostics=support.diagnostics(events))
            record["diagnostics"]["stderr_policy_rejections"] = len(re.findall(r"Rejected\(", stderr.decode(errors="replace")))
            try:
                record["usage"] = support.parse_usage(events)
            except ValueError as error:
                record["usage_error"] = str(error)
                record["infrastructure_failure"] = not record["timed_out"]
            answer = (folder / "answer.json").read_text() if (folder / "answer.json").exists() else ""
            record["answer_sha256"] = digest(answer.encode())
            if calibration:
                try:
                    correct = json.loads(answer) == {"reading": 37}
                except ValueError:
                    correct = False
                route = record["diagnostics"]["tool_calls"]
                expected_call = "mcp:build_context_bundle" if row["arm"] in ("repaired", "navigation") else "mcp:get_source"
                record["correctness"] = {"passed": correct and (route.get("shell", 0) > 0 if row["arm"] == "native"
                                                               else route.get(expected_call, 0) > 0)}
            else:
                record["correctness"] = tasks.evaluate(row["task"], workspace, answer)
            record["instrumentation_valid"] = bool(record["usage"] is not None and process.returncode == 0
                                                    and not record["timed_out"] and not record["infrastructure_failure"])
    except Exception as error:
        record.update(infrastructure_failure=True, failure_category=type(error).__name__)
    finally:
        support.stop(process)
        support.stop(daemon)
        record["workspace_after_sha256"] = tasks.manifest(workspace)
    write_json(folder / "record.json", record)
    return record


def audit_passed(base, row):
    reviews = json.loads((base / "access-review.json").read_text())
    review = reviews.get(row["id"], {})
    folder = base / row["id"]
    if (digest((folder / "events.jsonl").read_bytes()) != row.get("raw_events_sha256")
            or digest((folder / "stderr.log").read_bytes()) != row.get("stderr_sha256")):
        return False
    return (review.get("passed") is True and review.get("raw_events_sha256") == row.get("raw_events_sha256")
            and review.get("stderr_sha256") == row.get("stderr_sha256") and bool(review.get("auditor"))
            and bool(review.get("method")) and review.get("violations") == [])


def run(base, calibration=False):
    frozen = verify(base)
    schedule = ([{"id": "calibration-" + arm, "arm": arm, "task": "calibration", "repetition": 0} for arm in ARMS]
                if calibration else frozen["schedule"])
    if not calibration:
        calibrated = json.loads((base / "calibration.json").read_text())
        if ({r["arm"] for r in calibrated} != set(ARMS) or len(calibrated) != len(ARMS)
                or not all(r["instrumentation_valid"] and r["correctness"]["passed"] and audit_passed(base, r) for r in calibrated)):
            raise ValueError("all calibration arms must pass correctness, instrumentation, and access review")
    output = base / ("calibration.json" if calibration else "results.json")
    records = json.loads(output.read_text()) if output.exists() else []
    completed = {r["id"] for r in records}
    failures = 0
    for row in schedule:
        if row["id"] in completed:
            continue
        verify(base)
        if (base / row["id"]).exists():
            raise ValueError("unrecorded attempt exists; retain and adjudicate it without rerunning")
        record = execute(base, frozen, row, calibration)
        records.append(record)
        write_json(output, records)
        print(json.dumps({"completed": len(records), "run": row["id"], "correct": record["correctness"]["passed"],
                          "usage": record["usage"], "diagnostics": record.get("diagnostics"),
                          "failure": record.get("failure_category")}), flush=True)
        failures = failures + 1 if record["infrastructure_failure"] else 0
        if failures >= 2 or (calibration and not (record["instrumentation_valid"] and record["correctness"]["passed"])):
            raise RuntimeError("calibration failure or consecutive infrastructure failures; keep every attempt")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("prepare", "calibrate", "run"))
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--legacy", type=Path, default=ROOT / "artifacts/token-fix/2026-09-18/baseline-install/bin/graphharness")
    parser.add_argument("--repaired", type=Path, default=ROOT / "build/install/graphharness/bin/graphharness")
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(args.artifacts.resolve(), args.legacy.resolve(), args.repaired.resolve())
    else:
        run(args.artifacts.resolve(), calibration=args.action == "calibrate")
