# /// script
# requires-python = ">=3.11"
# dependencies = ["playwright==1.63.0"]
# ///

import argparse
import importlib.metadata
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

from playwright.sync_api import sync_playwright


def request(endpoint, path, method="GET", body=None, token=None, epoch=None, expected=(200,)):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if epoch:
        headers["X-GraphHarness-Epoch"] = epoch
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(urllib.request.Request(endpoint + path, data=data, headers=headers, method=method), timeout=10) as response:
            payload = json.loads(response.read())
            assert response.status in expected, (response.status, payload)
            return response.status, payload
    except urllib.error.HTTPError as error:
        payload = json.loads(error.read())
        if error.code in expected:
            return error.code, payload
        raise AssertionError((method, path, error.code, payload)) from error


def wait_for_descriptor(runtime):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        descriptors = list(runtime.glob("*.json"))
        if descriptors:
            return json.loads(descriptors[0].read_text())
        time.sleep(0.1)
    raise AssertionError("daemon descriptor did not appear")


def agent(endpoint, bootstrap, label):
    _, created = request(endpoint, "/sessions", "POST", {
        "schema_version": 1,
        "agent_label": label,
        "client": {"name": "coordinated-browser-smoke", "version": "1"},
    }, bootstrap)
    return created["session_credential"], created["daemon_epoch"]


def call(endpoint, credential, epoch, operation_id, name, arguments, expected=(200,)):
    return request(endpoint, "/tools/call", "POST", {
        "schema_version": 1,
        "operation_id": str(operation_id),
        "name": name,
        "arguments": arguments,
    }, credential, epoch, expected)


def main():
    parser = argparse.ArgumentParser(description="Scripted coordination browser smoke; it is not agent evidence.")
    parser.add_argument("launcher")
    parser.add_argument("--ui", default="ui/dist")
    parser.add_argument("--fixture", default="examples/ticket-office")
    parser.add_argument("--chromium")
    parser.add_argument("--artifacts", default="/tmp/graphharness-coordinated-browser-check")
    args = parser.parse_args()
    launcher = Path(args.launcher).resolve()
    fixture = Path(args.fixture).resolve()
    ui = Path(args.ui).resolve()
    artifacts = Path(args.artifacts); artifacts.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix="graphharness-coordinated-smoke-"))
    checkout, runtime = temporary / "fixture", temporary / "runtime"
    shutil.copytree(fixture, checkout)
    runtime.mkdir(mode=0o700)
    env = os.environ.copy(); env["GRAPHHARNESS_RUNTIME_DIR"] = str(runtime)
    daemon = subprocess.Popen([str(launcher), "daemon", str(checkout), str(ui), "--allow-edits"], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        descriptor = wait_for_descriptor(runtime)
        endpoint = descriptor["endpoint"]
        print("daemon_ready", flush=True)
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True, executable_path=args.chromium)
            context = browser.new_context(viewport={"width": 1440, "height": 900}, reduced_motion="reduce")
            page = context.new_page(); errors = []
            page.set_default_timeout(5_000)
            page.on("pageerror", lambda error: errors.append(str(error)))
            page.goto(endpoint)
            page.get_by_role("button", name="Start pairing").click()
            code = page.locator("code.command").inner_text().split()[-1]
            approved = subprocess.run([str(launcher), "authorize-browser", str(checkout), code], env=env, capture_output=True, text=True, timeout=10)
            assert approved.returncode == 0, approved.stderr
            page.locator("#workspace").wait_for(state="visible")
            print("browser_paired", flush=True)
            a_credential, epoch = agent(endpoint, descriptor["bootstrap_credential"], "SCRIPT A")
            b_credential, b_epoch = agent(endpoint, descriptor["bootstrap_credential"], "SCRIPT B")
            assert epoch == b_epoch
            _, state = request(endpoint, "/state", token=a_credential, epoch=epoch)
            reserve = next(node for node in state["snapshot"]["nodes"] if node.get("qualified_name", "").endswith("TicketInventory.reserve"))
            _, source = call(endpoint, a_credential, epoch, 1, "get_source", {"node_id": reserve["id"]})
            assert "remaining" in source["result"]["source"]
            replacement = """if (quantity <= 0) throw new IllegalArgumentException(\"Quantity must be positive\");
if (quantity > remaining) return false;
remaining -= quantity;
return true;"""
            _, planned = call(endpoint, a_credential, epoch, 2, "plan_edit", {
                "node_id": reserve["id"], "snapshot_id": state["snapshot"]["snapshot_id"],
                "expected_file_hash": reserve["file_hash"], "new_body": replacement,
            })
            edit = planned["result"]
            _, acquired = call(endpoint, a_credential, epoch, 3, "acquire_edit_lease", {"file": reserve["file"]})
            lease = acquired["result"]
            conflict_status, conflict = call(endpoint, b_credential, epoch, 1, "acquire_edit_lease", {"file": reserve["file"]}, expected=(409,))
            assert conflict_status == 409 and conflict["error"]["code"] == "lease_busy"
            print("plan_and_conflict", flush=True)
            page.get_by_role("searchbox", name="Search symbols or files").fill("reserve")
            page.get_by_role("button", name="Code list").click()
            page.locator("#list-panel button").filter(has_text="reserve").first.click()
            page.locator(".lease-badge").wait_for(state="visible")
            assert "SCRIPT A" in page.locator(".lease-badge").inner_text()
            diff_button = page.get_by_role("button", name="View planned edit diff")
            diff_button.scroll_into_view_if_needed()
            diff_button.wait_for(state="visible")
            diff_button.focus()
            page.keyboard.press("Enter")
            page.get_by_role("heading", name="Planned edit").wait_for(state="visible")
            assert "Before" in page.locator("#inspector").inner_text() and "After" in page.locator("#inspector").inner_text()
            assert "project tests did not run" in page.locator("#inspector").inner_text()
            page.locator("#activity").get_by_text("reservation denied").wait_for(state="visible")
            assert "holder: SCRIPT A" in page.locator("#activity").inner_text()
            print("ui_lease_and_preview", flush=True)

            _, applied = call(endpoint, a_credential, epoch, 4, "apply_edit", {"edit_id": edit["edit_id"], "lease_id": lease["lease_id"], "generation": lease["generation"]})
            assert applied["result"]["committed"] is True
            _, released = call(endpoint, a_credential, epoch, 5, "release_edit_lease", {"lease_id": lease["lease_id"], "generation": lease["generation"]})
            assert released["result"]["released"] is True
            page.get_by_text("applied an edit").wait_for(state="visible")
            page.get_by_role("button", name="View applied edit diff").click()
            page.get_by_role("heading", name="Applied edit").wait_for(state="visible")
            assert "committed" in page.locator("#inspector").inner_text()
            print("applied_and_previewed", flush=True)
            page.get_by_role("searchbox", name="Search symbols or files").fill("")
            page.get_by_role("button", name="Code list").click()
            page.get_by_role("button", name="Fit graph").click()
            for width, height in [(1440, 900), (1280, 800), (390, 844)]:
                page.set_viewport_size({"width": width, "height": height})
                page.screenshot(path=str(artifacts / f"coordinated-{width}x{height}.png"), full_page=True)
                assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth"), f"Horizontal overflow at {width}"
            assert not errors, errors
            observer = next(cookie for cookie in context.cookies() if cookie["name"] == "gh_observer")
            assert observer["httpOnly"] and observer["sameSite"] == "Strict"
            assert page.evaluate("localStorage.length") == 0
            context.close(); browser.close()
        print(json.dumps({"scripted_clients": ["SCRIPT A", "SCRIPT B"], "browser": "paired observer", "checks": ["plan", "lease_acquired", "lease_denied", "retained_diff", "apply", "release", "event_reconciliation", "viewports"], "artifacts": str(artifacts)}))
    finally:
        daemon.terminate()
        try:
            daemon.wait(timeout=5)
        except subprocess.TimeoutExpired:
            daemon.kill(); daemon.wait(timeout=5)
        shutil.rmtree(temporary, ignore_errors=True)


if __name__ == "__main__":
    main()
