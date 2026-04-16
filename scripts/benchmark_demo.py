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


def main() -> int:
    parser = argparse.ArgumentParser(description="Compare GraphHarness context use against naive file loading.")
    parser.add_argument("server", help="Path to the GraphHarness server binary.")
    parser.add_argument("project_root", help="Path to the Java project to analyze.")
    args = parser.parse_args()

    harness = harness_run(args.server, args.project_root)
    naive = naive_run(args.project_root)
    harness_contract = harness_contract_run(args.server, args.project_root)
    naive_contract = naive_contract_run(args.project_root)

    print_result(harness)
    print_result(naive)
    print_result(harness_contract)
    print_result(naive_contract)

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
    return 0


if __name__ == "__main__":
    sys.exit(main())
