from contextlib import contextmanager
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from benchmarks.token_node_followup import preflight as pf


@contextmanager
def evidence():
    with tempfile.TemporaryDirectory() as temporary:
        base = Path(temporary)
        frozen = {"tasks": {}, "launchers": {"node": "/private/frozen/bin/graphharness"}}
        rows = []
        for task in pf.trials.tasks.TASK_IDS:
            folder = base / "preflight" / task
            workspace = folder / "workspace"
            spec = pf.trials.tasks.create_task(task, workspace)
            frozen["tasks"][task] = spec
            filename, signature = pf.trials.tasks.TARGETS[task]
            relative = pf.trials.tasks.JAVA_PREFIX + filename
            original = (workspace / relative).read_bytes()
            begin, end = pf.trials.tasks.body_span(original, signature)
            start = original.index(signature.encode())
            stop = end + 1
            span = {"start": start, "end": stop}
            lines = {"start": original[:start].count(b"\n") + 1, "end": original[:stop - 1].count(b"\n") + 1}
            node = {"id": "fixture-node-" + task, "file": relative, "byte_span": span, "line_range": lines}
            source = {"file": relative, "file_hash": pf.trials.digest(original), "snapshot_id": "fixture-snapshot",
                      "source": original[start:stop].decode(), "byte_span": span, "line_range": lines,
                      "provenance": "javac_parser"}
            body = pf.repair_body(task)
            pf.InterfaceTaskTests().replace_body(task, workspace, body)
            after_hash = pf.trials.digest((workspace / relative).read_bytes())
            common = {"node_id": node["id"], "snapshot_id": source["snapshot_id"], "expected_file_hash": source["file_hash"]}
            method_name = signature.split("(")[0].split()[-1]
            args = {"search_graph": {"query": filename[:-5] + "." + method_name, "kind": "method"},
                    "get_source": {"node_id": node["id"], "include_context": 0},
                    "plan_edit": dict(common, new_body=original[begin:end].decode()),
                    "replace_node_body": dict(common, new_body=body)}
            results = {"search_graph": {"results": [node]}, "get_source": source,
                       "plan_edit": {"committed": False, "syntax_checked": True, "node_id": node["id"],
                                     "file": relative, "base_hash": source["file_hash"], "snapshot_id": source["snapshot_id"]},
                       "replace_node_body": {"committed": True, "file_hash": after_hash,
                                             "node_id": node["id"], "file": relative}}
            for index, name in enumerate(pf.CALLS, 1):
                pf.trials.write_json(folder / (name + "-request.json"), {
                    "schema_version": 1, "name": name, "operation_id": str(index), "arguments": args[name]})
                pf.trials.write_json(folder / (name + "-response.json"), {
                    "schema_version": 1, "daemon_epoch": "fixture-epoch", "operation_id": str(index), "result": results[name]})
            pf.trials.write_json(folder / "capabilities.json", {"analysis_engine": "joern", "coordinated_writes": True})
            pf.trials.write_json(folder / "tool-definitions.json", [{"name": name} for name in pf.CALLS])
            pf.trials.write_json(folder / "daemon-command.json", [frozen["launchers"]["node"], "daemon", str(workspace), "--allow-edits"])
            pf.trials.write_json(folder / "answer.json", {"changed_file": relative, "status": "fixed"})
            rows.append({"task": task, "workspace_before_sha256": spec["manifest_sha256"],
                         "workspace_after_sha256": pf.trials.tasks.manifest(workspace), "file": relative,
                         "original_file_hash": source["file_hash"], "file_hash": after_hash, "node_id": node["id"],
                         "passed": True, "committed": True, "plan_created": True,
                         "admission": {"expected_source_files": 24, "indexed_source_files": 24},
                         "correctness": {"passed": True, "details": "separate unit evaluator"}})
        pf.trials.write_json(base / "frozen.json", frozen)
        for row in rows:
            row["frozen_sha256"] = pf.trials.digest((base / "frozen.json").read_bytes())
        save(base, rows)
        with patch.object(pf.trials.tasks, "evaluate", return_value={"passed": True, "details": "separate unit evaluator"}) as grade:
            yield base, frozen, rows, grade


def save(base, rows, bind=True):
    for row in rows:
        folder = base / "preflight" / row["task"]
        if bind:
            row["evidence_sha256"] = {name: pf.trials.digest((folder / name).read_bytes()) for name in pf.EVIDENCE}
        pf.trials.write_json(folder / "record.json", row)
    pf.trials.write_json(base / "preflight.json", rows)


class PreflightEvidenceTests(unittest.TestCase):
    def test_complete_binding_requires_each_target_and_regrades_all(self):
        with evidence() as (base, frozen, rows, grade):
            self.assertEqual(pf.verify(base, frozen), rows)
            self.assertEqual(grade.call_count, 3)

    def test_incomplete_or_duplicate_target_set_is_rejected(self):
        for duplicate in (False, True):
            with self.subTest(duplicate=duplicate), evidence() as (base, frozen, rows, _):
                pf.trials.write_json(base / "preflight.json", rows[:-1] + ([rows[0]] if duplicate else []))
                with self.assertRaises(ValueError): pf.verify(base, frozen)

    def test_missing_evidence_binding_or_changed_raw_file_is_rejected(self):
        for mode in ("empty", "partial", "raw"):
            with self.subTest(mode=mode), evidence() as (base, frozen, rows, _):
                if mode == "empty": rows[0]["evidence_sha256"] = {}
                elif mode == "partial": rows[0]["evidence_sha256"].pop("plan_edit-request.json")
                else: (base / "preflight" / rows[0]["task"] / "answer.json").write_text("{}")
                save(base, rows, bind=False)
                with self.assertRaises(ValueError): pf.verify(base, frozen)

    def test_rebound_inconsistent_tool_evidence_cannot_fake_eligibility(self):
        mutations = {
            "plan_edit-request.json": lambda x: x["arguments"].update(new_body="return true;"),
            "replace_node_body-request.json": lambda x: x.update(name="apply_edit"),
            "get_source-response.json": lambda x: x["result"]["byte_span"].update(start=0),
            "search_graph-response.json": lambda x: x["result"]["results"][0].update(id="wrong-overload"),
            "plan_edit-response.json": lambda x: x["result"].update(syntax_checked=False),
            "replace_node_body-response.json": lambda x: x["result"].update(committed=False),
            "capabilities.json": lambda x: x.update(analysis_engine="fallback"),
        }
        for name, mutate in mutations.items():
            with self.subTest(name=name), evidence() as (base, frozen, rows, _):
                path = base / "preflight" / rows[0]["task"] / name
                value = json.loads(path.read_text()); mutate(value); pf.trials.write_json(path, value)
                save(base, rows)
                with self.assertRaises(ValueError): pf.verify(base, frozen)

    def test_wrong_launcher_or_read_only_daemon_is_rejected(self):
        for index, replacement in ((0, "/private/different-build"), (3, "--read-only")):
            with self.subTest(index=index), evidence() as (base, frozen, rows, _):
                path = base / "preflight" / rows[0]["task"] / "daemon-command.json"
                value = json.loads(path.read_text()); value[index] = replacement; pf.trials.write_json(path, value)
                save(base, rows)
                with self.assertRaises(ValueError): pf.verify(base, frozen)

    def test_wrong_freeze_target_final_bytes_or_behavior_is_rejected(self):
        for mode in ("freeze", "target", "bytes", "behavior"):
            with self.subTest(mode=mode), evidence() as (base, frozen, rows, grade):
                if mode == "freeze": rows[0]["frozen_sha256"] = "different-freeze"
                elif mode == "target": rows[0]["file"] = "../outside.java"
                elif mode == "bytes":
                    path = base / "preflight" / rows[0]["task"] / "workspace" / rows[0]["file"]
                    path.write_text(path.read_text() + "\n")
                else: grade.return_value = {"passed": False}
                save(base, rows)
                with self.assertRaises(ValueError): pf.verify(base, frozen)


if __name__ == "__main__":
    unittest.main()
