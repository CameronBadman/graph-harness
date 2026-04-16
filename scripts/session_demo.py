#!/usr/bin/env python3

import argparse
import json
import subprocess
import sys
from typing import Any


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


def print_json(title: str, value: Any) -> None:
    print(f"\n## {title}")
    print(json.dumps(value, indent=2, sort_keys=False))


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


def main() -> int:
    parser = argparse.ArgumentParser(description="Run a persistent GraphHarness MCP-style demo session.")
    parser.add_argument("server", help="Path to the GraphHarness server binary.")
    parser.add_argument("project_root", help="Path to the Java project to analyze.")
    args = parser.parse_args()

    session = JsonRpcSession([args.server, args.project_root])
    try:
        initialize = session.request("initialize", {})
        print_json("initialize", initialize["result"])

        tools = session.request("tools/list", {})
        print_json("tools/list", tools["result"])

        summary = session.tool_call("get_summary_map")
        print_json("get_summary_map", summary)

        chosen_entrypoint = choose_entrypoint(summary)
        search_query = chosen_entrypoint["name"].split(".")[-1]
        search = session.tool_call("search_graph", {"query": search_query, "kind": "method"})
        print_json("search_graph", search)
        results = search.get("results", [])
        if not results:
            raise RuntimeError(f"search_graph returned no results for query={search_query}")

        node_id = next((item["id"] for item in results if item["name"] == chosen_entrypoint["name"]), results[0]["id"])
        source = session.tool_call("get_source", {"node_id": node_id, "include_context": 1})
        print_json("get_source", source)

        summary_mode = summary.get("summary_mode", "standard")
        detail = None
        call_paths = None
        impact = None
        cluster_detail = None

        if summary_mode in ("standard", "expanded"):
            detail = session.tool_call("get_node_detail", {"node_id": node_id})
            print_json("get_node_detail", detail)

            call_paths = session.tool_call("get_call_paths", {"node_id": node_id, "max_depth": 3})
            print_json("get_call_paths", call_paths)

            impact = session.tool_call("get_impact", {"node_id": node_id, "max_depth": 2})
            print_json("get_impact", impact)

        if summary_mode == "expanded" and summary.get("clusters"):
            cluster_detail = session.tool_call("get_cluster_detail", {"cluster_id": summary["clusters"][0]["cluster_id"]})
            print_json("get_cluster_detail", cluster_detail)

        implementation_query = None
        for candidate in summary.get("entrypoints", []):
            name = candidate.get("name", "")
            if "Repository." in name or "Validator." in name:
                implementation_query = name.split(".")[-1]
                break

        implementations = None
        if implementation_query:
            implementation_search = session.tool_call("search_graph", {"query": implementation_query, "kind": "method"})
            print_json("search_graph(implementation)", implementation_search)
            implementation_results = implementation_search.get("results", [])
            if implementation_results:
                implementation_node_id = implementation_results[0]["id"]
                implementations = session.tool_call("get_implementations", {"node_id": implementation_node_id})
                print_json("get_implementations", implementations)

        snapshot_ids = {
            summary["snapshot_id"],
            search["snapshot_id"],
            source["snapshot_id"],
        }
        if detail is not None:
            snapshot_ids.add(detail["snapshot_id"])
        if call_paths is not None:
            snapshot_ids.add(call_paths["snapshot_id"])
        if impact is not None:
            snapshot_ids.add(impact["snapshot_id"])
        if cluster_detail is not None:
            snapshot_ids.add(cluster_detail["snapshot_id"])
        if implementations is not None:
            snapshot_ids.add(implementations["snapshot_id"])
        print(f"\nSnapshot ids observed: {sorted(snapshot_ids)}")
        if len(snapshot_ids) == 1:
            print("Persistent session check: OK")
        else:
            print("Persistent session check: snapshots changed during the walkthrough")
        return 0
    finally:
        session.close()


if __name__ == "__main__":
    sys.exit(main())
