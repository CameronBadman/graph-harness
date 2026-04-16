#!/usr/bin/env python3

import argparse
import json
import math
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any


def estimate_tokens(value: Any) -> int:
    text = value if isinstance(value, str) else json.dumps(value, separators=(",", ":"))
    return max(1, math.ceil(len(text) / 4))


class JsonRpcSession:
    def __init__(self, command: list[str], env: dict[str, str] | None = None) -> None:
        self.proc = subprocess.Popen(
            command,
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env,
        )
        self.next_id = 1

    def close(self) -> None:
        try:
            self.proc.terminate()
            self.proc.wait(timeout=5)
        except Exception:
            self.proc.kill()

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


def detect_javac() -> str | None:
    for candidate in ("javac", "/usr/bin/javac", "/nix/var/nix/profiles/default/bin/javac"):
        if shutil.which(candidate):
            return candidate
    return None


def syntax_check(project_root: Path) -> dict[str, Any]:
    javac = detect_javac()
    java_files = sorted(str(path) for path in project_root.rglob("*.java"))
    if not javac or not java_files:
        return {"supported": False, "ok": None, "details": "javac unavailable or no Java files"}
    proc = subprocess.run(
        [javac, "-proc:none", "-d", str(project_root / ".gh-edit-build"), *java_files],
        capture_output=True,
        text=True,
    )
    return {
        "supported": True,
        "ok": proc.returncode == 0,
        "details": (proc.stderr or proc.stdout).strip().splitlines()[:8],
    }


def run_method_patch_demo(server: str, project_root: Path, env: dict[str, str]) -> dict[str, Any]:
    session = JsonRpcSession([server, str(project_root)], env=env)
    try:
        session.request("initialize", {})
        candidate_task = 'Insert "pet.setOwner(owner);" before "owner.addPet(pet);" in processCreationForm'
        candidates = session.tool_call("get_edit_candidates", {"task": candidate_task, "limit": 3})
        target = candidates["candidates"][0]["node"]
        replace_plan = session.tool_call(
            "plan_edit",
            {
                "operation": "modify_method_body",
                "target_node_id": target["id"],
                "payload": {
                    "new_body": 'pet.setOwner(owner);\nowner.addPet(pet);\nthis.owners.save(owner);\nredirectAttributes.addFlashAttribute("message", "New Pet has been Added");\nreturn "redirect:/owners/{ownerId}";'
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
        return {
            "candidate_task": candidate_task,
            "top_candidate": candidates["candidates"][0],
            "target": target["name"],
            "replace_plan_tokens": estimate_tokens(replace_plan),
            "patch_plan_tokens": estimate_tokens(patch_plan),
            "patch_reduction_percent": round(
                100.0 * (1.0 - (estimate_tokens(patch_plan) / estimate_tokens(replace_plan))),
                2,
            ),
            "replace_diff_excerpt": "\n".join(replace_plan["diff"].splitlines()[:10]),
            "patch_diff_excerpt": "\n".join(patch_plan["diff"].splitlines()[:10]),
        }
    finally:
        session.close()


def run_rename_demo(server: str, project_root: Path, env: dict[str, str]) -> dict[str, Any]:
    session = JsonRpcSession([server, str(project_root)], env=env)
    try:
        session.request("initialize", {})
        candidate_task = "Rename method findById to findOwnerById in the repository"
        candidates = session.tool_call("get_edit_candidates", {"task": candidate_task, "limit": 3})
        target = candidates["candidates"][0]["node"]
        plan = session.tool_call(
            "plan_edit",
            {
                "operation": "rename_node",
                "target_node_id": target["id"],
                "payload": {"new_name": "findOwnerById"},
            },
        )
        return {
            "candidate_task": candidate_task,
            "top_candidate": candidates["candidates"][0],
            "target": target["name"],
            "rename_plan_tokens": estimate_tokens(plan),
            "affected_files": plan["affected_files"][:10],
            "affected_file_count": len(plan["affected_files"]),
            "diff_excerpt": "\n".join(plan["diff"].splitlines()[:16]),
        }
    finally:
        session.close()


def run_apply_smoke(server: str, source_root: Path, env: dict[str, str]) -> dict[str, Any]:
    scratch = Path("/tmp/graphharness-edit-demo")
    if scratch.exists():
        shutil.rmtree(scratch)
    shutil.copytree(source_root, scratch)

    session = JsonRpcSession([server, str(scratch)], env=env)
    try:
        session.request("initialize", {})
        search = session.tool_call("search_graph", {"query": "processCreationForm", "kind": "method"})
        target = next(item for item in search["results"] if item["name"].endswith("PetController.processCreationForm"))
        plan = session.tool_call(
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
        validation = session.tool_call("validate_edit", {"edit_id": plan["edit_id"], "mode": "auto"})
        apply_result = session.tool_call("apply_edit", {"edit_id": plan["edit_id"]})
        source = session.tool_call("get_source", {"node_id": target["id"], "include_context": 0})
    finally:
        session.close()

    return {
        "validation_success": validation["success"],
        "validation_scope": validation["validation_scope"],
        "validator": validation["validator"],
        "validation_errors": validation["validation_errors"],
        "apply_success": apply_result["success"],
        "snapshot_id": apply_result["snapshot_id"],
        "updated_nodes": apply_result["updated_nodes"][:5],
        "syntax_check": syntax_check(scratch),
        "source_excerpt": "\n".join(source["source"].splitlines()[:8]),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Run benchmarked GraphHarness edit workflows.")
    parser.add_argument("server", help="Path to the GraphHarness server binary.")
    parser.add_argument("project_root", help="Path to the Java project to analyze.")
    args = parser.parse_args()

    env = dict(os.environ)
    joern_home = env.get("GRAPHHARNESS_JOERN_HOME") or str(Path.home() / ".local/share/graphharness/joern")
    env["GRAPHHARNESS_JOERN_HOME"] = joern_home

    project_root = Path(args.project_root).resolve()

    patch_demo = run_method_patch_demo(args.server, project_root, env)
    rename_demo = run_rename_demo(args.server, project_root, env)
    apply_demo = run_apply_smoke(args.server, project_root, env)

    print("## edit-benchmark(method-patch)")
    print(json.dumps(patch_demo, indent=2))
    print("\n## edit-benchmark(rename)")
    print(json.dumps(rename_demo, indent=2))
    print("\n## edit-apply-smoke")
    print(json.dumps(apply_demo, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
