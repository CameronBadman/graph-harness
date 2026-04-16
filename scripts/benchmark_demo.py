#!/usr/bin/env python3

import argparse
import json
import math
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


def estimate_tokens(text: str) -> int:
    return max(1, math.ceil(len(text) / 4))


class JsonRpcSession:
    def __init__(self, command: list[str]) -> None:
        self.proc = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        self.next_id = 1

    def close(self) -> None:
        if self.proc.stdin:
            self.proc.stdin.close()
        if self.proc.stdout:
            self.proc.stdout.close()
        if self.proc.stderr:
            self.proc.stderr.close()
        self.proc.wait(timeout=5)

    def request(self, method: str, params: dict[str, Any] | None = None) -> dict[str, Any]:
        payload = {
            "jsonrpc": "2.0",
            "id": self.next_id,
            "method": method,
            "params": params or {},
        }
        self.next_id += 1
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        frame = f"Content-Length: {len(body)}\r\n\r\n".encode("ascii") + body
        assert self.proc.stdin is not None
        self.proc.stdin.write(frame)
        self.proc.stdin.flush()
        return self._read_response()

    def tool_call(self, name: str, arguments: dict[str, Any] | None = None) -> dict[str, Any]:
        response = self.request("tools/call", {"name": name, "arguments": arguments or {}})
        result = response["result"]["structuredContent"]
        assert isinstance(result, dict)
        return result

    def _read_response(self) -> dict[str, Any]:
        assert self.proc.stdout is not None
        headers: dict[str, str] = {}
        while True:
            line = self.proc.stdout.readline()
            if not line:
                stderr = self.proc.stderr.read().decode("utf-8", errors="replace") if self.proc.stderr else ""
                raise RuntimeError(f"session closed unexpectedly\n{stderr}")
            text = line.decode("ascii").strip()
            if not text:
                break
            key, value = text.split(":", 1)
            headers[key.lower()] = value.strip()

        length = int(headers["content-length"])
        body = self.proc.stdout.read(length)
        return json.loads(body.decode("utf-8"))


@dataclass
class BenchmarkResult:
    label: str
    tokens: int
    artifacts: list[str]
    notes: list[str]


@dataclass
class TypedArtifacts:
    nodes: set[str]
    files: set[str]
    tests: set[str]
    validation_targets: set[str]


@dataclass
class TaskSpec:
    name: str
    expected_nodes: set[str]
    expected_files: set[str]
    expected_tests: set[str]
    expected_validation_targets: set[str]


@dataclass
class TaskScore:
    task: str
    node_recall: float
    file_recall: float
    test_recall: float
    validation_target_recall: float
    missing_nodes: list[str]
    missing_files: list[str]
    missing_tests: list[str]
    missing_validation_targets: list[str]
    task_success: bool


def normalize_artifact(value: str) -> str:
    base = value.split("[", 1)[0]
    if ":" in base and ".java:" in base:
        file_name, action = base.split(":", 1)
        stem = file_name.removesuffix(".java")
        return f"{stem.lower()}:{action.lower()}"

    if "." in base:
        parts = base.split(".")
        if len(parts) >= 2:
            return f"{parts[-2].lower()}:{parts[-1].lower()}"
    return base.lower()


def typed_artifacts(result: BenchmarkResult) -> TypedArtifacts:
    normalized = {normalize_artifact(item) for item in result.artifacts}
    nodes = {item for item in normalized if ":" in item and not item.endswith(":java") and not item.startswith("src/")}
    files = {item for item in normalized if item.endswith(":java") and not item.startswith("src/test/")}
    tests = {item for item in normalized if item.startswith("src/test/") or item.endswith("tests:java") or item.endswith("test:java")}
    validation_targets = {
        item for item in normalized
        if item == "project_root" or item.startswith("module:")
    }
    return TypedArtifacts(nodes=nodes, files=files, tests=tests, validation_targets=validation_targets)


def ratio(found: set[str], expected: set[str]) -> float:
    if not expected:
        return 1.0
    return len(found & expected) / len(expected)


def score_task(spec: TaskSpec, result: BenchmarkResult) -> TaskScore:
    typed = typed_artifacts(result)
    missing_nodes = sorted(spec.expected_nodes - typed.nodes)
    missing_files = sorted(spec.expected_files - typed.files)
    missing_tests = sorted(spec.expected_tests - typed.tests)
    missing_targets = sorted(spec.expected_validation_targets - typed.validation_targets)
    node_recall = ratio(typed.nodes, spec.expected_nodes)
    file_recall = ratio(typed.files, spec.expected_files)
    test_recall = ratio(typed.tests, spec.expected_tests)
    validation_target_recall = ratio(typed.validation_targets, spec.expected_validation_targets)
    task_success = (
        node_recall >= 1.0
        and file_recall >= 1.0
        and test_recall >= 1.0
        and validation_target_recall >= 1.0
    )
    return TaskScore(
        task=spec.name,
        node_recall=node_recall,
        file_recall=file_recall,
        test_recall=test_recall,
        validation_target_recall=validation_target_recall,
        missing_nodes=missing_nodes,
        missing_files=missing_files,
        missing_tests=missing_tests,
        missing_validation_targets=missing_targets,
        task_success=task_success,
    )


def choose_entrypoint(summary: dict[str, Any]) -> dict[str, Any]:
    entrypoints = summary.get("entrypoints", [])
    if not entrypoints:
        raise RuntimeError("get_summary_map returned no entrypoints")

    def score(node: dict[str, Any]) -> tuple[int, int]:
        name = node.get("name", "")
        score = 0
        if "Controller." in name:
            score += 50
        if "Service." in name:
            score += 30
        if "process" in name or "create" in name or "update" in name or "find" in name:
            score += 20
        if ".get" in name or ".set" in name or "Repository." in name:
            score -= 20
        return score, node.get("loc", 0)

    return sorted(entrypoints, key=score, reverse=True)[0]


def harness_run(server: str, project_root: str) -> BenchmarkResult:
    session = JsonRpcSession([server, project_root])
    tokens = 0
    notes: list[str] = []
    artifacts: list[str] = []

    try:
        session.request("initialize", {})

        summary = session.tool_call("get_summary_map")
        tokens += estimate_tokens(json.dumps(summary, separators=(",", ":")))
        notes.append(f"summary_map={summary['snapshot_id']}")
        notes.append(f"summary_mode={summary.get('summary_mode', 'unknown')}")
        chosen_entrypoint = choose_entrypoint(summary)
        notes.append(f"chosen_entrypoint={chosen_entrypoint['name']}")

        search_query = chosen_entrypoint["name"].split(".")[-1]
        entry_search = session.tool_call("search_graph", {"query": search_query, "kind": "method"})
        tokens += estimate_tokens(json.dumps(entry_search, separators=(",", ":")))
        entry = next((item for item in entry_search["results"] if item["name"] == chosen_entrypoint["name"]), entry_search["results"][0])
        artifacts.append(entry["name"])

        source = session.tool_call("get_source", {"node_id": entry["id"], "include_context": 1})
        tokens += estimate_tokens(json.dumps(source, separators=(",", ":")))

        summary_mode = summary.get("summary_mode", "standard")
        used_tools = ["get_summary_map", "search_graph", "get_source"]

        if summary_mode in ("standard", "expanded"):
            node_detail = session.tool_call("get_node_detail", {"node_id": entry["id"]})
            tokens += estimate_tokens(json.dumps(node_detail, separators=(",", ":")))
            used_tools.append("get_node_detail")

            call_paths = session.tool_call("get_call_paths", {"node_id": entry["id"], "max_depth": 3})
            tokens += estimate_tokens(json.dumps(call_paths, separators=(",", ":")))
            for path in call_paths["paths"]:
                artifacts.extend(node["name"] for node in path["nodes"])
            used_tools.append("get_call_paths")

            impact = session.tool_call("get_impact", {"node_id": entry["id"], "max_depth": 2})
            tokens += estimate_tokens(json.dumps(impact, separators=(",", ":")))
            artifacts.extend(node["name"] for node in impact["affected_nodes"])
            used_tools.append("get_impact")

        if summary_mode == "expanded" and summary.get("clusters"):
            cluster = summary["clusters"][0]
            cluster_detail = session.tool_call("get_cluster_detail", {"cluster_id": cluster["cluster_id"]})
            tokens += estimate_tokens(json.dumps(cluster_detail, separators=(",", ":")))
            used_tools.append("get_cluster_detail")

        notes.append(f"used_tools={','.join(used_tools)}")
    finally:
        session.close()

    return BenchmarkResult(
        label="graphharness",
        tokens=tokens,
        artifacts=sorted(set(artifacts)),
        notes=notes,
    )


def harness_bundle_run(server: str, project_root: str) -> BenchmarkResult:
    session = JsonRpcSession([server, project_root])
    tokens = 0
    notes: list[str] = []
    artifacts: list[str] = []

    try:
        session.request("initialize", {})

        capabilities = session.tool_call("get_capabilities")
        tokens += estimate_tokens(json.dumps(capabilities, separators=(",", ":")))
        notes.append(f"analysis_engine={capabilities.get('analysis_engine', 'unknown')}")

        summary = session.tool_call("get_summary_map")
        tokens += estimate_tokens(json.dumps(summary, separators=(",", ":")))
        chosen_entrypoint = choose_entrypoint(summary)
        notes.append(f"chosen_entrypoint={chosen_entrypoint['name']}")

        bundle = session.tool_call(
            "build_context_bundle",
            {
                "node_id": chosen_entrypoint["id"],
                "token_budget": 2200,
            },
        )
        tokens += estimate_tokens(json.dumps(bundle, separators=(",", ":")))
        artifacts.extend(node["name"] for node in bundle.get("focus_nodes", []))
        artifacts.extend(node["name"] for node in bundle.get("entrypoints", []))
        artifacts.extend(bundle.get("impact_files", []))
        artifacts.extend(slice_["node_id"] for slice_ in bundle.get("source_slices", []))
        notes.append(f"bundle_summary_mode={bundle.get('summary_mode', 'unknown')}")
        notes.append(f"bundle_notes={','.join(bundle.get('notes', []))}")
    finally:
        session.close()

    return BenchmarkResult(
        label="graphharness-bundle",
        tokens=tokens,
        artifacts=sorted(set(artifacts)),
        notes=notes,
    )


def harness_contract_run(server: str, project_root: str) -> BenchmarkResult:
    session = JsonRpcSession([server, project_root])
    tokens = 0
    notes: list[str] = []
    artifacts: list[str] = []

    try:
        session.request("initialize", {})

        summary = session.tool_call("get_summary_map")
        tokens += estimate_tokens(json.dumps(summary, separators=(",", ":")))
        notes.append(f"summary_map={summary['snapshot_id']}")
        notes.append(f"summary_mode={summary.get('summary_mode', 'unknown')}")

        repo_search = session.tool_call("search_graph", {"query": "find", "kind": "method"})
        tokens += estimate_tokens(json.dumps(repo_search, separators=(",", ":")))
        candidates = repo_search.get("results", [])
        target = next((item for item in candidates if "Repository." in item["name"]), candidates[0])
        artifacts.append(target["name"])
        notes.append(f"contract_target={target['name']}")

        implementations = session.tool_call("get_implementations", {"node_id": target["id"]})
        tokens += estimate_tokens(json.dumps(implementations, separators=(",", ":")))
        for item in implementations["items"]:
            artifacts.append(f"{item['node']['name']}[{item.get('relationship', 'unknown')}]")

        callers = session.tool_call("get_callers", {"node_id": target["id"], "depth": 2})
        tokens += estimate_tokens(json.dumps(callers, separators=(",", ":")))
        artifacts.extend(item["node"]["name"] for item in callers["items"])

        impact = session.tool_call("get_impact", {"node_id": target["id"], "max_depth": 2})
        tokens += estimate_tokens(json.dumps(impact, separators=(",", ":")))
        artifacts.extend(node["name"] for node in impact["affected_nodes"])

        used_tools = ["get_summary_map", "search_graph", "get_implementations", "get_callers", "get_impact"]
        notes.append(f"used_tools={','.join(used_tools)}")
    finally:
        session.close()

    return BenchmarkResult(
        label="graphharness-contract",
        tokens=tokens,
        artifacts=sorted(set(artifacts)),
        notes=notes,
    )


def naive_run(project_root: str) -> BenchmarkResult:
    root = Path(project_root)
    files = sorted(root.rglob("*Controller.java"))[:3]
    files += sorted(root.rglob("*Service.java"))[:3]
    files += sorted(root.rglob("*Repository.java"))[:3]
    files = list(dict.fromkeys(files))
    loaded_text: list[str] = []
    artifacts: list[str] = []
    for path in files:
        text = path.read_text()
        loaded_text.append(text)
        for needle in ("process", "create", "update", "find", "save"):
            if needle in text:
                artifacts.append(f"{path.name}:{needle}")

    return BenchmarkResult(
        label="naive-file-load",
        tokens=sum(estimate_tokens(text) for text in loaded_text),
        artifacts=sorted(set(artifacts)),
        notes=[f"loaded_files={','.join(path.name for path in files)}"],
    )


def naive_contract_run(project_root: str) -> BenchmarkResult:
    root = Path(project_root)
    files = sorted(root.rglob("*Repository.java"))[:3]
    files += sorted(root.rglob("*Controller.java"))[:4]
    files = list(dict.fromkeys(files))
    loaded_text: list[str] = []
    artifacts: list[str] = []
    for path in files:
        text = path.read_text()
        loaded_text.append(text)
        if "find" in text:
            artifacts.append(f"{path.name}:find")
        if "owners." in text or "repository." in text or "vetRepository" in text:
            artifacts.append(f"{path.name}:repo-call")

    return BenchmarkResult(
        label="naive-contract",
        tokens=sum(estimate_tokens(text) for text in loaded_text),
        artifacts=sorted(set(artifacts)),
        notes=[f"loaded_files={','.join(path.name for path in files)}"],
    )


def harness_edit_run(server: str, project_root: str) -> tuple[BenchmarkResult, BenchmarkResult]:
    session = JsonRpcSession([server, project_root])
    try:
        session.request("initialize", {})

        search = session.tool_call("search_graph", {"query": "processCreationForm", "kind": "method"})
        target = next(item for item in search["results"] if item["name"].endswith("PetController.processCreationForm"))

        replace_plan = session.tool_call(
            "plan_edit",
            {
                "operation": "modify_method_body",
                "target_node_id": target["id"],
                "payload": {
                    "new_body": 'pet.setOwner(owner);\nowner.addPet(pet);\nthis.owners.save(owner);\nredirectAttributes.addFlashAttribute("message", "New Pet has been Added");\nreturn "redirect:/owners/{ownerId}";',
                },
            },
        )
        patch_plan = session.tool_call(
            "plan_edit",
            {
                "operation": "modify_method_body",
                "target_node_id": target["id"],
                "payload": {
                    "patch_mode": "insert_before",
                    "anchor": "owner.addPet(pet);",
                    "snippet": "pet.setOwner(owner);",
                },
            },
        )
    finally:
        session.close()

    return (
        BenchmarkResult(
            label="graphharness-edit-replace",
            tokens=estimate_tokens(json.dumps(search, separators=(",", ":"))) + estimate_tokens(json.dumps(replace_plan, separators=(",", ":"))),
            artifacts=[target["name"], *replace_plan["affected_files"]],
            notes=[f"plan_edit_tokens={estimate_tokens(json.dumps(replace_plan, separators=(',', ':')))}"],
        ),
        BenchmarkResult(
            label="graphharness-edit-patch",
            tokens=estimate_tokens(json.dumps(search, separators=(",", ":"))) + estimate_tokens(json.dumps(patch_plan, separators=(",", ":"))),
            artifacts=[target["name"], *patch_plan["affected_files"]],
            notes=[f"plan_edit_tokens={estimate_tokens(json.dumps(patch_plan, separators=(',', ':')))}"],
        ),
    )


def harness_feature_run(server: str, project_root: str) -> BenchmarkResult:
    session = JsonRpcSession([server, project_root])
    tokens = 0
    artifacts: list[str] = []
    notes: list[str] = []
    try:
        session.request("initialize", {})
        creation_search = session.tool_call("search_graph", {"query": "processCreationForm", "kind": "method"})
        update_search = session.tool_call("search_graph", {"query": "updatePetDetails", "kind": "method"})
        init_search = session.tool_call("search_graph", {"query": "initCreationForm", "kind": "method"})
        tokens += estimate_tokens(json.dumps(creation_search, separators=(",", ":")))
        tokens += estimate_tokens(json.dumps(update_search, separators=(",", ":")))
        tokens += estimate_tokens(json.dumps(init_search, separators=(",", ":")))
        relevant = [
            next(item for item in creation_search["results"] if item["name"].endswith("PetController.processCreationForm")),
            next(item for item in update_search["results"] if item["name"].endswith("PetController.updatePetDetails")),
            next(item for item in init_search["results"] if item["name"].endswith("PetController.initCreationForm")),
        ]
        node_ids = [item["id"] for item in relevant]
        artifacts.extend(item["name"] for item in relevant)

        bundle = session.tool_call(
            "build_context_bundle",
            {
                "node_id": relevant[0]["id"],
                "token_budget": 1800,
            },
        )
        tokens += estimate_tokens(json.dumps(bundle, separators=(",", ":")))
        artifacts.extend(node["name"] for node in bundle.get("focus_nodes", []))
        notes.append(f"bundle_target={bundle.get('chosen_node_id', 'none')}")

        source_batch = session.tool_call("get_source_batch", {"node_ids": node_ids})
        tokens += estimate_tokens(json.dumps(source_batch, separators=(",", ":")))
        artifacts.extend(item["node_id"] for item in source_batch["sources"])

        validation_targets = session.tool_call("get_validation_targets", {"node_id": relevant[1]["id"]})
        tokens += estimate_tokens(json.dumps(validation_targets, separators=(",", ":")))
        artifacts.extend(target["identifier"] for target in validation_targets["validation_targets"])
        notes.append(f"used_tools=search_graph,build_context_bundle,get_source_batch,get_validation_targets")
    finally:
        session.close()

    return BenchmarkResult(
        label="graphharness-feature",
        tokens=tokens,
        artifacts=sorted(set(artifacts)),
        notes=notes,
    )


def naive_edit_run(project_root: str) -> BenchmarkResult:
    root = Path(project_root)
    pet_controller = next(root.rglob("PetController.java"))
    controller_text = pet_controller.read_text()

    patch_payload = {
        "file": pet_controller.name,
        "source": controller_text,
        "requested_change": "Insert pet.setOwner(owner); before owner.addPet(pet); in processCreationForm and keep behavior unchanged.",
    }

    return BenchmarkResult(
        label="naive-edit-patch",
        tokens=estimate_tokens(json.dumps(patch_payload, separators=(",", ":"))),
        artifacts=[pet_controller.name],
        notes=[f"file_lines={len(controller_text.splitlines())}"],
    )


def naive_feature_run(project_root: str) -> BenchmarkResult:
    root = Path(project_root)
    pet_controller = next(root.rglob("PetController.java"))
    pet_controller_tests = next(root.rglob("PetControllerTests.java"))
    owner_model = next(root.rglob("Owner.java"))
    loaded_files = [pet_controller, pet_controller_tests, owner_model]
    loaded_text = [path.read_text() for path in loaded_files]
    artifacts = [
        "PetController.java:initCreationForm",
        "PetController.java:processCreationForm",
        "PetController.java:updatePetDetails",
        "PetControllerTests.java",
        "Owner.java:addPet",
    ]
    payload = {
        "requested_change": "Ensure pet.setOwner(owner) happens before owner.addPet(pet) across PetController creation and update flows and identify likely tests to validate.",
        "files": [
            {"file": path.name, "source": text}
            for path, text in zip(loaded_files, loaded_text)
        ],
    }
    return BenchmarkResult(
        label="naive-feature",
        tokens=estimate_tokens(json.dumps(payload, separators=(",", ":"))),
        artifacts=artifacts,
        notes=[f"loaded_files={','.join(path.name for path in loaded_files)}"],
    )


def print_result(result: BenchmarkResult) -> None:
    print(f"\n## {result.label}")
    print(json.dumps(
        {
            "approx_tokens": result.tokens,
            "artifacts": result.artifacts,
            "normalized_artifacts": sorted({normalize_artifact(item) for item in result.artifacts}),
            "notes": result.notes,
        },
        indent=2,
    ))


def print_score(score: TaskScore, label: str) -> None:
    print(f"\n## score({label})")
    print(json.dumps(
        {
            "task": score.task,
            "task_success": score.task_success,
            "node_recall": round(score.node_recall, 2),
            "file_recall": round(score.file_recall, 2),
            "test_recall": round(score.test_recall, 2),
            "validation_target_recall": round(score.validation_target_recall, 2),
            "missing_nodes": score.missing_nodes,
            "missing_files": score.missing_files,
            "missing_tests": score.missing_tests,
            "missing_validation_targets": score.missing_validation_targets,
        },
        indent=2,
    ))


def main() -> int:
    parser = argparse.ArgumentParser(description="Compare GraphHarness context use against naive file loading.")
    parser.add_argument("server", help="Path to the GraphHarness server binary.")
    parser.add_argument("project_root", help="Path to the Java project to analyze.")
    args = parser.parse_args()

    harness = harness_run(args.server, args.project_root)
    harness_bundle = harness_bundle_run(args.server, args.project_root)
    naive = naive_run(args.project_root)
    harness_contract = harness_contract_run(args.server, args.project_root)
    naive_contract = naive_contract_run(args.project_root)
    harness_edit_replace, harness_edit_patch = harness_edit_run(args.server, args.project_root)
    naive_edit_patch = naive_edit_run(args.project_root)
    harness_feature = harness_feature_run(args.server, args.project_root)
    naive_feature = naive_feature_run(args.project_root)

    orientation_task = TaskSpec(
        name="orientation_controller_flow",
        expected_nodes={
            "petcontroller:processcreationform",
            "owner:addpet",
        },
        expected_files=set(),
        expected_tests=set(),
        expected_validation_targets=set(),
    )
    contract_task = TaskSpec(
        name="contract_impact",
        expected_nodes={
            "ownerrepository:findbyid",
            "ownercontroller:findowner",
            "petcontroller:findowner",
        },
        expected_files=set(),
        expected_tests=set(),
        expected_validation_targets=set(),
    )
    feature_task = TaskSpec(
        name="feature_owner_pet_consistency",
        expected_nodes={
            "petcontroller:initcreationform",
            "petcontroller:processcreationform",
            "petcontroller:updatepetdetails",
            "owner:addpet",
        },
        expected_files=set(),
        expected_tests={"src/test/java/org/springframework/samples/petclinic/owner/petcontrollertests:java"},
        expected_validation_targets={"project_root"},
    )
    edit_task = TaskSpec(
        name="edit_patch",
        expected_nodes={"petcontroller:processcreationform"},
        expected_files={"src/main/java/org/springframework/samples/petclinic/owner/petcontroller:java"},
        expected_tests=set(),
        expected_validation_targets=set(),
    )

    print_result(harness)
    print_result(harness_bundle)
    print_result(naive)
    print_result(harness_contract)
    print_result(naive_contract)
    print_result(harness_edit_replace)
    print_result(harness_edit_patch)
    print_result(naive_edit_patch)
    print_result(harness_feature)
    print_result(naive_feature)

    print_score(score_task(orientation_task, harness), "graphharness")
    print_score(score_task(orientation_task, harness_bundle), "graphharness-bundle")
    print_score(score_task(orientation_task, naive), "naive")
    print_score(score_task(contract_task, harness_contract), "graphharness-contract")
    print_score(score_task(contract_task, naive_contract), "naive-contract")
    print_score(score_task(edit_task, harness_edit_patch), "graphharness-edit-patch")
    print_score(score_task(edit_task, naive_edit_patch), "naive-edit-patch")
    print_score(score_task(feature_task, harness_feature), "graphharness-feature")
    print_score(score_task(feature_task, naive_feature), "naive-feature")

    reduction = 0.0
    if naive.tokens:
        reduction = 100.0 * (1.0 - (harness.tokens / naive.tokens))
    overlap = sorted({normalize_artifact(item) for item in harness.artifacts} & {normalize_artifact(item) for item in naive.artifacts})
    print("\n## comparison")
    print(json.dumps(
        {
            "token_reduction_percent": round(reduction, 2),
            "artifact_overlap": overlap,
            "graphharness_tokens": harness.tokens,
            "naive_tokens": naive.tokens,
        },
        indent=2,
    ))
    bundle_reduction = 0.0
    if naive.tokens:
        bundle_reduction = 100.0 * (1.0 - (harness_bundle.tokens / naive.tokens))
    bundle_vs_multicall = 0.0
    if harness.tokens:
        bundle_vs_multicall = 100.0 * (1.0 - (harness_bundle.tokens / harness.tokens))
    bundle_overlap = sorted({normalize_artifact(item) for item in harness_bundle.artifacts} & {normalize_artifact(item) for item in naive.artifacts})
    print("\n## comparison(bundle)")
    print(json.dumps(
        {
            "token_reduction_percent_vs_naive": round(bundle_reduction, 2),
            "token_reduction_percent_vs_multicall": round(bundle_vs_multicall, 2),
            "artifact_overlap": bundle_overlap,
            "graphharness_bundle_tokens": harness_bundle.tokens,
            "graphharness_multicall_tokens": harness.tokens,
            "naive_tokens": naive.tokens,
        },
        indent=2,
    ))
    contract_reduction = 0.0
    if naive_contract.tokens:
        contract_reduction = 100.0 * (1.0 - (harness_contract.tokens / naive_contract.tokens))
    contract_overlap = sorted(
        {normalize_artifact(item) for item in harness_contract.artifacts} &
        {normalize_artifact(item) for item in naive_contract.artifacts},
    )
    print("\n## comparison(contract)")
    print(json.dumps(
        {
            "token_reduction_percent": round(contract_reduction, 2),
            "artifact_overlap": contract_overlap,
            "graphharness_tokens": harness_contract.tokens,
            "naive_tokens": naive_contract.tokens,
        },
        indent=2,
    ))
    print("\n## comparison(edit-patch)")
    print(json.dumps(
        {
            "replace_plan_tokens": harness_edit_replace.tokens,
            "patch_plan_tokens": harness_edit_patch.tokens,
            "naive_file_tokens": naive_edit_patch.tokens,
            "patch_vs_replace_reduction_percent": round(100.0 * (1.0 - (harness_edit_patch.tokens / harness_edit_replace.tokens)), 2),
            "patch_vs_naive_reduction_percent": round(100.0 * (1.0 - (harness_edit_patch.tokens / naive_edit_patch.tokens)), 2),
        },
        indent=2,
    ))
    feature_reduction = 0.0
    if naive_feature.tokens:
        feature_reduction = 100.0 * (1.0 - (harness_feature.tokens / naive_feature.tokens))
    feature_overlap = sorted(
        {normalize_artifact(item) for item in harness_feature.artifacts} &
        {normalize_artifact(item) for item in naive_feature.artifacts},
    )
    print("\n## comparison(feature)")
    print(json.dumps(
        {
            "graphharness_feature_tokens": harness_feature.tokens,
            "naive_feature_tokens": naive_feature.tokens,
            "feature_vs_naive_reduction_percent": round(feature_reduction, 2),
            "artifact_overlap": feature_overlap,
        },
        indent=2,
    ))
    return 0


if __name__ == "__main__":
    sys.exit(main())
