import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import urllib.request


def request(endpoint, path, credential=None, body=None, epoch=None):
    headers = {"Content-Type": "application/json"}
    if credential:
        headers["Authorization"] = "Bearer " + credential
    if epoch:
        headers["X-GraphHarness-Epoch"] = epoch
    payload = None if body is None else json.dumps(body).encode()
    with urllib.request.urlopen(urllib.request.Request(endpoint + path, data=payload, headers=headers), timeout=10) as response:
        return response.read()


def check(launcher, root, runtime, cwd, env, runtimes_available):
    log = tempfile.TemporaryFile()
    daemon = subprocess.Popen([str(launcher), "daemon", str(root)], cwd=cwd, env=env, stdout=subprocess.DEVNULL, stderr=log)
    try:
        deadline = time.monotonic() + 45
        descriptor = None
        while time.monotonic() < deadline:
            if daemon.poll() is not None:
                raise AssertionError("Installed daemon failed before startup")
            files = list(runtime.glob("*.json"))
            if files:
                descriptor = json.loads(files[0].read_text())
                try:
                    request(descriptor["endpoint"], "/")
                    break
                except OSError:
                    pass
            time.sleep(0.1)
        else:
            raise AssertionError("Installed daemon startup timed out")
        endpoint = descriptor["endpoint"]
        page = request(endpoint, "/").decode()
        assert "GraphHarness" in page and "/assets/" in page
        session = json.loads(request(endpoint, "/sessions", descriptor["bootstrap_credential"], {
            "schema_version": 1, "agent_label": "Installation script", "client": {"name": "installation_smoke", "version": "1"},
        }))
        state = json.loads(request(endpoint, "/state", session["session_credential"], epoch=descriptor["daemon_epoch"]))
        languages = set(state["capabilities"]["languages"])
        assert state["snapshot"]["analysis_engine"] == "fallback-parser"
        if runtimes_available:
            assert {"java", "typescript", "python"} <= languages
            assert {"demo.ts", "demo.py"} <= {n["file"] for n in state["snapshot"]["nodes"] if n["kind"] != "file"}
        else:
            assert "typescript" not in languages and "python" not in languages
            adapters = state["snapshot"]["language_adapters"]
            assert not adapters["typescript"]["available"] and adapters["typescript"]["diagnostics"]
            assert not adapters["python"]["available"] and adapters["python"]["diagnostics"]
        return {"languages": sorted(languages), "backend": state["snapshot"]["analysis_engine"], "node_count": len(state["snapshot"]["nodes"])}
    finally:
        daemon.terminate()
        try:
            daemon.wait(timeout=10)
        except subprocess.TimeoutExpired:
            daemon.kill()
            daemon.wait()
        log.close()


def main(args):
    with tempfile.TemporaryDirectory(prefix="graphharness-install-") as directory:
        base = Path(directory)
        application = base / "GraphHarness App"
        shutil.copytree(Path(args.distribution).resolve(), application)
        root, runtime, cwd, home = (base / name for name in ("Public Fixture", "runtime", "elsewhere", "home"))
        for folder in (root, runtime, cwd, home):
            folder.mkdir(mode=0o700)
        (root / "Example.java").write_text("class Example {\n int value() { return 1; }\n}\n")
        (root / "demo.ts").write_text("export function label(value: string) { return value; }\n")
        (root / "demo.py").write_text("def label(value):\n    return value\n")
        env = dict(os.environ)
        for name in ("GRAPHHARNESS_PARSERS_DIR", "GRAPHHARNESS_TYPESCRIPT_PATH", "NODE_PATH", "GRAPHHARNESS_JOERN_HOME", "JOERN_HOME", "GRAPHHARNESS_NODE", "GRAPHHARNESS_PYTHON", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS"):
            env.pop(name, None)
        env.update(PATH="/usr/bin:/bin", JAVA_OPTS="-Duser.home=" + str(home), GRAPHHARNESS_REQUIRE_JOERN="false", GRAPHHARNESS_RUNTIME_DIR=str(runtime))
        if env.get("JAVA_HOME"):
            env["JAVA_HOME"] = str(Path(env["JAVA_HOME"]).resolve())
        launcher = application / "bin" / "graphharness"
        installed = check(launcher, root, runtime, cwd, env, True)
        env.update(GRAPHHARNESS_NODE="/unavailable/graphharness-node", GRAPHHARNESS_PYTHON="/unavailable/graphharness-python")
        degraded = check(launcher, root, runtime, cwd, env, False)
        print(json.dumps({"checks": ["copied_distribution", "paths_with_spaces", "empty_application_config", "unrelated_working_directory", "bundled_ui_and_parsers", "missing_runtime_degradation"],
            "installed": installed, "missing_runtimes": degraded, "fresh_operating_system": False,
            "limitation": "Fresh application prefix/configuration on the existing supported Linux host; not a new operating-system installation."}))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("distribution")
    main(parser.parse_args())
