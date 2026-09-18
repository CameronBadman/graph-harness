from __future__ import annotations

import json
from pathlib import Path
import tempfile
import urllib.error

from benchmarks.token_node_followup import run_trials as trials
from benchmarks.token_node_followup.test_tasks import InterfaceTaskTests

CALLS = ("search_graph", "get_source", "plan_edit", "replace_node_body")
EVIDENCE = {"answer.json", "capabilities.json", "tool-definitions.json", "daemon-command.json"} | {
    name + ending for name in CALLS for ending in ("-request.json", "-response.json")}


def repair_body(task):
    with tempfile.TemporaryDirectory(prefix="gh-preflight-repair-") as directory:
        root = Path(directory)
        trials.tasks.create_task(task, root)
        helper = InterfaceTaskTests()
        helper.fix(task, root)
        return helper.body(task, root)


def run(base):
    frozen = trials.verify(base)
    folder = base / "preflight"
    folder.mkdir(mode=0o700)
    rows = []
    for task in trials.tasks.TASK_IDS:
        attempt = folder / task
        attempt.mkdir(mode=0o700)
        workspace, runtime = attempt / "workspace", attempt / "runtime"
        spec = trials.tasks.create_task(task, workspace)
        target_file, signature = trials.tasks.TARGETS[task]
        relative = trials.tasks.JAVA_PREFIX + target_file
        original = (workspace / relative).read_bytes()
        beginning, end = trials.tasks.body_span(original, signature)
        line = original[:original.index(signature.encode())].count(b"\n") + 1
        method_name = signature.split("(")[0].split()[-1]
        row = {"task": task, "frozen_sha256": trials.digest((base / "frozen.json").read_bytes()),
               "workspace_before_sha256": spec["manifest_sha256"], "file": relative,
               "original_file_hash": trials.digest(original), "passed": False}
        daemon, credential = None, None
        try:
            with (attempt / "daemon.log").open("w") as log:
                launcher = Path(frozen["launchers"]["node"])
                trials.write_json(attempt / "daemon-command.json", [str(launcher), "daemon", str(workspace), "--allow-edits"])
                daemon, definitions, capabilities, _ = trials.start_daemon(launcher, workspace, runtime, log)
                trials.write_json(attempt / "capabilities.json", capabilities)
                trials.write_json(attempt / "tool-definitions.json", definitions)
                row["admission"] = trials.verify_admission(workspace, runtime)
                if capabilities.get("analysis_engine") != "joern" or not capabilities.get("coordinated_writes"):
                    raise ValueError("preflight requires the actual write-enabled Joern backend")
                descriptor = json.loads(next(runtime.glob("*.json")).read_text())
                session = trials.support.request(descriptor, "/sessions", "POST", {
                    "schema_version": 1, "agent_label": "Target eligibility preflight",
                    "client": {"name": "node-rerun-preflight", "version": "1"}})
                credential = session["session_credential"]
                sequence = 0

                def call(name, arguments):
                    nonlocal sequence
                    sequence += 1
                    payload = {"schema_version": 1, "name": name, "arguments": arguments,
                               "operation_id": str(sequence)}
                    trials.write_json(attempt / (name + "-request.json"), payload)
                    try:
                        value = trials.support.request(descriptor, "/tools/call", "POST", payload, credential)
                    except urllib.error.HTTPError as error:
                        trials.write_json(attempt / (name + "-response.json"), json.loads(error.read()))
                        raise
                    trials.write_json(attempt / (name + "-response.json"), value)
                    return value["result"]

                found = call("search_graph", {"query": target_file[:-5] + "." + method_name, "kind": "method"})
                candidates = [node for node in found["results"] if node.get("file") == relative
                              and node.get("line_range", {}).get("start") == line]
                if len(candidates) != 1:
                    raise ValueError("preflight could not identify the exact declaration")
                node = candidates[0]
                source = call("get_source", {"node_id": node["id"], "include_context": 0})
                span = source.get("byte_span") or {}
                if (source.get("file_hash") != trials.digest(original)
                        or source.get("provenance") != "javac_parser"
                        or original[span.get("start", 0):span.get("end", 0)].decode() != source["source"]):
                    raise ValueError("source identity or parser span is inconsistent")
                common = {"node_id": node["id"], "snapshot_id": source["snapshot_id"],
                          "expected_file_hash": source["file_hash"]}
                plan = call("plan_edit", dict(common, new_body=original[beginning:end].decode()))
                if plan.get("syntax_checked") is not True or plan.get("committed") is not False:
                    raise ValueError("original-body plan did not confirm eligibility")
                body = repair_body(task)
                if not body or len(body) > 4096:
                    raise ValueError("preflight repair exceeds the registered tool scope")
                receipt = call("replace_node_body", dict(common, new_body=body))
                final_hash = trials.digest((workspace / relative).read_bytes())
                if (receipt.get("committed") is not True or receipt.get("file_hash") != final_hash
                        or final_hash == source["file_hash"]):
                    raise ValueError("node edit did not commit the changed source")
                row.update(node_id=node["id"], committed=True, plan_created=True, file_hash=final_hash)
        except Exception as error:
            row["failure_category"] = type(error).__name__
        finally:
            if credential:
                try:
                    trials.support.request(descriptor, "/sessions/current", "DELETE", credential=credential)
                except Exception:
                    pass
            trials.support.stop(daemon)
        answer = {"changed_file": relative, "status": "fixed"}
        trials.write_json(attempt / "answer.json", answer)
        row["correctness"] = trials.tasks.evaluate(task, workspace, json.dumps(answer))
        row["workspace_after_sha256"] = trials.tasks.manifest(workspace)
        row["passed"] = bool(row.get("committed") and row.get("plan_created")
                             and row["correctness"]["passed"] and "failure_category" not in row)
        row["evidence_sha256"] = {p.name: trials.digest(p.read_bytes()) for p in sorted(attempt.glob("*.json"))}
        trials.write_json(attempt / "record.json", row)
        rows.append(row)
        trials.write_json(base / "preflight.json", rows)
        print(json.dumps({"preflight_task": task, "passed": row["passed"],
                          "committed": row.get("committed", False), "failure": row.get("failure_category")}), flush=True)
    verify(base, frozen)


def verify(base, frozen):
    rows = json.loads((base / "preflight.json").read_text())
    if len(rows) != len(trials.tasks.TASK_IDS) or {r["task"] for r in rows} != set(trials.tasks.TASK_IDS):
        raise ValueError("every target requires a retained preflight")
    for row in rows:
        folder = base / "preflight" / row["task"]
        if json.loads((folder / "record.json").read_text()) != row or row.get("passed") is not True:
            raise ValueError("target preflight did not pass")
        if row.get("frozen_sha256") != trials.digest((base / "frozen.json").read_bytes()):
            raise ValueError("preflight has a different product/evaluation freeze")
        if row.get("workspace_before_sha256") != frozen["tasks"][row["task"]]["manifest_sha256"]:
            raise ValueError("preflight fixture differs from registered task")
        if set(row.get("evidence_sha256", {})) != EVIDENCE:
            raise ValueError("preflight requires the complete evidence binding")
        for name, value in row["evidence_sha256"].items():
            if Path(name).name != name or trials.digest((folder / name).read_bytes()) != value:
                raise ValueError("preflight evidence changed")
        capabilities = json.loads((folder / "capabilities.json").read_text())
        if capabilities.get("analysis_engine") != "joern" or capabilities.get("coordinated_writes") is not True:
            raise ValueError("preflight backend differs from registration")
        command = json.loads((folder / "daemon-command.json").read_text())
        if (not isinstance(command, list) or len(command) != 4 or command[0] != frozen["launchers"]["node"]
                or command[1] != "daemon" or command[3] != "--allow-edits"
                or not isinstance(command[2], str) or not Path(command[2]).is_absolute()):
            raise ValueError("preflight daemon command differs from frozen launcher/write mode")
        definitions = json.loads((folder / "tool-definitions.json").read_text())
        if not set(CALLS).issubset({value["name"] for value in definitions}):
            raise ValueError("preflight tools lack the required route")
        requests = {name: json.loads((folder / (name + "-request.json")).read_text()) for name in CALLS}
        responses = {name: json.loads((folder / (name + "-response.json")).read_text()) for name in CALLS}
        epochs = {response.get("daemon_epoch") for response in responses.values()}
        if len(epochs) != 1 or None in epochs:
            raise ValueError("preflight responses do not belong to one daemon epoch")
        for index, name in enumerate(CALLS, 1):
            request, response = requests[name], responses[name]
            if (request.get("schema_version") != 1 or request.get("name") != name
                    or request.get("operation_id") != str(index)
                    or response.get("operation_id") != str(index)
                    or response.get("schema_version") != 1 or "error" in response):
                raise ValueError("preflight tool request/response correlation is invalid")
        source = json.loads((folder / "get_source-response.json").read_text())["result"]
        plan = json.loads((folder / "plan_edit-response.json").read_text())["result"]
        receipt = json.loads((folder / "replace_node_body-response.json").read_text())["result"]
        if (plan.get("syntax_checked") is not True or plan.get("committed") is not False
                or receipt.get("committed") is not True or row.get("committed") is not True
                or row.get("plan_created") is not True):
            raise ValueError("preflight did not prove planning and a node commit")
        workspace = folder / "workspace"
        target_file, signature = trials.tasks.TARGETS[row["task"]]
        relative = trials.tasks.JAVA_PREFIX + target_file
        if row["file"] != relative:
            raise ValueError("preflight changed the registered target file")
        with tempfile.TemporaryDirectory(prefix="gh-preflight-verify-") as temporary:
            pristine = Path(temporary)
            spec = trials.tasks.create_task(row["task"], pristine)
            original = (pristine / relative).read_bytes()
        if spec["manifest_sha256"] != row["workspace_before_sha256"]:
            raise ValueError("preflight original fixture cannot be reproduced")
        expected_count = sum(Path(name).suffix == ".java" for name in spec["workspace_files"])
        if row.get("admission") != {"expected_source_files": expected_count, "indexed_source_files": expected_count}:
            raise ValueError("preflight index admission differs from fixture")
        original_hash = trials.digest(original)
        line = original[:original.index(signature.encode())].count(b"\n") + 1
        method_name = signature.split("(")[0].split()[-1]
        if requests["search_graph"]["arguments"] != {"query": target_file[:-5] + "." + method_name, "kind": "method"}:
            raise ValueError("preflight searched a different target")
        candidates = [node for node in responses["search_graph"]["result"]["results"]
                      if node.get("file") == relative and node.get("line_range", {}).get("start") == line]
        if len(candidates) != 1 or candidates[0].get("id") != row["node_id"]:
            raise ValueError("preflight did not select the exact target declaration")
        node = candidates[0]
        span = source.get("byte_span") or {}
        if (source.get("file_hash") != original_hash or row["original_file_hash"] != original_hash
                or source.get("file") != relative or source.get("provenance") != "javac_parser"
                or span != node.get("byte_span") or source.get("line_range") != node.get("line_range")
                or type(span.get("start")) is not int or type(span.get("end")) is not int
                or not 0 <= span["start"] < span["end"] <= len(original)
                or original[span["start"]:span["end"]].decode() != source.get("source")):
            raise ValueError("preflight original source/hash/span is inconsistent")
        if requests["get_source"]["arguments"] != {"node_id": row["node_id"], "include_context": 0}:
            raise ValueError("preflight read a different node")
        actual_hash = trials.digest((workspace / row["file"]).read_bytes())
        if (actual_hash != row["file_hash"] or receipt.get("file_hash") != actual_hash
                or actual_hash == row["original_file_hash"] or source.get("file_hash") != row["original_file_hash"]
                or trials.tasks.manifest(workspace) != row["workspace_after_sha256"]):
            raise ValueError("preflight source hashes or final manifest disagree")
        expected = {"node_id": row["node_id"], "snapshot_id": source["snapshot_id"],
                    "expected_file_hash": source["file_hash"]}
        beginning, end = trials.tasks.body_span(original, signature)
        bodies = {"plan_edit": original[beginning:end].decode(), "replace_node_body": repair_body(row["task"])}
        for name in ("plan_edit", "replace_node_body"):
            arguments = json.loads((folder / (name + "-request.json")).read_text())["arguments"]
            if arguments != dict(expected, new_body=bodies[name]):
                raise ValueError("preflight used mismatched target/version credentials")
        for value in (plan, receipt):
            if value.get("node_id") != row["node_id"] or value.get("file") != relative:
                raise ValueError("preflight receipt identifies a different target")
        if plan.get("base_hash") != original_hash or plan.get("snapshot_id") != source["snapshot_id"]:
            raise ValueError("preflight plan identifies a different source version")
        result = trials.tasks.evaluate(row["task"], workspace, (folder / "answer.json").read_text())
        if result != row["correctness"] or result.get("passed") is not True:
            raise ValueError("preflight behavior or edit scope did not regrade")
    return rows
