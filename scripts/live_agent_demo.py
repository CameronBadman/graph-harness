# /// script
# requires-python = ">=3.11"
# dependencies = ["playwright==1.63.0"]
# ///

import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

from playwright.sync_api import sync_playwright


def main(args):
    root, launcher = Path(args.root).resolve(), Path(args.launcher).resolve()
    artifacts = Path(args.artifacts).resolve()
    artifacts.mkdir(parents=True, exist_ok=True)
    env = dict(os.environ)
    env["GRAPHHARNESS_RUNTIME_DIR"] = str(Path(args.runtime_directory).resolve())
    tasks = [
        ("Inventory agent", "Find TicketInventory.reserve, read its source, and explain what happens when quantity exceeds remaining capacity."),
        ("Pricing agent", "Find PriceQuote.totalCents, read its source, and explain how invalid inputs and integer overflow are handled."),
    ]
    processes, logs = [], []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True, executable_path=args.chromium)
        context = browser.new_context(viewport={"width": 1440, "height": 900}, record_video_dir=str(artifacts), record_video_size={"width": 1440, "height": 900})
        page = context.new_page()
        page.goto(args.endpoint)
        page.get_by_role("button", name="Start pairing").click()
        code = page.locator("code.command").inner_text().split()[-1]
        subprocess.run([str(launcher), "authorize-browser", str(root), code], env=env, check=True, capture_output=True, timeout=10)
        page.locator("#workspace").wait_for(state="visible")
        try:
            for label, task in tasks:
                fd, log_name = tempfile.mkstemp(prefix="graphharness-agent-", suffix=".log")
                log = os.fdopen(fd, "w")
                logs.append((log, log_name))
                command = [args.codex, "exec", "--ignore-user-config", "--ephemeral", "--sandbox", "read-only", "--model", args.model, "--cd", str(root),
                           "--config", "mcp_servers.graphharness.command=" + json.dumps(str(launcher)),
                           "--config", "mcp_servers.graphharness.args=" + json.dumps(["bridge", str(root), label]),
                           "--config", "mcp_servers.graphharness.env.GRAPHHARNESS_RUNTIME_DIR=" + json.dumps(env["GRAPHHARNESS_RUNTIME_DIR"]),
                           "--config", "mcp_servers.graphharness.required=true",
                           "--config", 'mcp_servers.graphharness.enabled_tools=["get_capabilities","search_graph","get_source"]',
                           "--config", 'mcp_servers.graphharness.default_tools_approval_mode="approve"',
                           "Use only graphharness MCP tools. First inspect capabilities. " + task + " Do not edit files or use shell. If MCP is unavailable report failure."]
                processes.append(subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=env))
            deadline = time.monotonic() + 180
            overlap_observed = False
            while any(process.poll() is None for process in processes):
                if time.monotonic() > deadline:
                    raise TimeoutError("Agent demo exceeded 180 seconds")
                text = page.locator("#workspace").inner_text()
                if all(label in text for label, _ in tasks):
                    overlap_observed = True
                page.wait_for_timeout(250)
            page.wait_for_timeout(1000)
            page.screenshot(path=str(artifacts / "real-agents.png"), full_page=True)
            report = {"client_version": subprocess.check_output([args.codex, "--version"], text=True).strip(), "configured_model": args.model,
                      "exit_codes": [process.returncode for process in processes], "labels_visible_in_browser": overlap_observed,
                      "private_logs": [name for _, name in logs], "claim": "Real concurrent client launch; journal required to establish connected session overlap."}
            (artifacts / "run.json").write_text(json.dumps(report, indent=2) + "\n")
            print(json.dumps(report))
            assert report["exit_codes"] == [0, 0]
        finally:
            for process in processes:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait()
            for log, _ in logs:
                log.close()
            context.close()
            browser.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("endpoint")
    parser.add_argument("launcher")
    parser.add_argument("root")
    parser.add_argument("runtime_directory")
    parser.add_argument("--chromium")
    parser.add_argument("--codex", default="codex")
    parser.add_argument("--model", default="gpt-5.6-terra")
    parser.add_argument("--artifacts", default="/tmp/graphharness-live-demo")
    main(parser.parse_args())
