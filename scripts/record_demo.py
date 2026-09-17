# /// script
# requires-python = ">=3.11"
# dependencies = ["playwright==1.63.0"]
# ///

import argparse
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time

from playwright.sync_api import sync_playwright

from coordinated_agent_demo import (
    AGENT_A, AGENT_B, EventCollector, MonitorHeartbeat, compact_events,
    json_request, launch_agent, relevant, wait_for_descriptor,
)


def wait_for(predicate, timeout, label, clients=()):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        if clients and any(client.poll() is not None for client in clients):
            raise RuntimeError("A real client exited before " + label)
        time.sleep(0.15)
    raise RuntimeError(f"timed out waiting for {label}")


def fixture_test(checkout):
    return subprocess.run(["bash", "-lc", "mkdir -p build && javac -d build java/*.java && java -cp build tickets.TicketOfficeTest"], cwd=checkout, capture_output=True, text=True, timeout=30)


def main(args):
    artifacts = Path(args.artifacts).resolve()
    artifacts.mkdir(parents=True, exist_ok=True)
    lock_path = artifacts / ".record_demo.lock"
    with lock_path.open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        run(args, artifacts)


def run(args, artifacts):
    launcher, ui, fixture = Path(args.launcher).resolve(), Path(args.ui).resolve(), Path(args.fixture).resolve()
    if not launcher.is_file() or not ui.is_dir() or not fixture.is_dir():
        raise RuntimeError("launcher, built UI, and public fixture are required")
    temporary = Path(tempfile.mkdtemp(prefix="graphharness-recording-"))
    checkout, runtime = temporary / "fixture", temporary / "runtime"
    shutil.copytree(fixture, checkout)
    test_file = checkout / "java" / "TicketOfficeTest.java"
    test_file.write_text(test_file.read_text().replace(
        'if (inventory.reserve(2)) throw new AssertionError("Oversold capacity");',
        'if (inventory.reserve(0)) throw new AssertionError("Zero reservation should be rejected without mutation");\n'
        '        try { inventory.reserve(-1); throw new AssertionError("Negative reservation should throw"); } catch (IllegalArgumentException expected) { }\n'
        '        if (inventory.reserve(2)) throw new AssertionError("Oversold capacity");',
    ))
    before_test = fixture_test(checkout)
    if before_test.returncode == 0 or "Quantity must be positive" not in before_test.stderr:
        raise RuntimeError("repair fixture did not fail for the expected zero-reservation behavior")
    runtime.mkdir(mode=0o700)
    env = dict(os.environ, GRAPHHARNESS_RUNTIME_DIR=str(runtime))
    daemon = subprocess.Popen([str(launcher), "daemon", str(checkout), str(ui), "--allow-edits"], env=env, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, text=True)
    observer = collector = heartbeat = None
    endpoint = epoch = None
    browser = context = pairing_video = None
    processes, logs = [], []
    try:
        descriptor = wait_for_descriptor(runtime, 60)
        endpoint, epoch, bootstrap = descriptor["endpoint"], descriptor["daemon_epoch"], descriptor["bootstrap_credential"]
        status, observer = json_request(endpoint, "/sessions", "POST", {"schema_version": 1, "agent_label": "recording observer", "client": {"name": "record_demo", "version": "1"}}, bootstrap)
        if status != 200:
            raise RuntimeError("observer session failed")
        credential = observer["session_credential"]
        collector, heartbeat = EventCollector(endpoint, credential, epoch), MonitorHeartbeat(endpoint, credential, epoch)
        collector.start(); heartbeat.start()
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True, executable_path=args.chromium)
            context = browser.new_context(viewport={"width": 1440, "height": 900}, record_video_dir=str(artifacts), record_video_size={"width": 1440, "height": 900})
            pairing_page = context.new_page()
            pairing_video = pairing_page.video
            pairing_page.goto(endpoint)
            pairing_page.get_by_role("button", name="Start pairing").click()
            code = pairing_page.locator("code.command").inner_text().split()[-1]
            approved = subprocess.run([str(launcher), "authorize-browser", str(checkout), code], env=env, capture_output=True, text=True, timeout=10)
            if approved.returncode:
                raise RuntimeError("browser authorization failed")
            pairing_page.locator("#workspace").wait_for(state="visible")
            page = context.new_page()
            recording_video = page.video
            page.goto(endpoint)
            page.locator("#workspace").wait_for(state="visible")
            a_log = tempfile.NamedTemporaryFile(prefix="graphharness-record-a-", suffix=".log", delete=False)
            logs.append(a_log)
            prompt_a = "Use only GraphHarness MCP tools. Do not use shell or direct edits, except `sleep 35` for the deliberate recording checkpoint. Find TicketInventory.reserve, read current source, and plan replacing its body with: `if (quantity < 0) throw new IllegalArgumentException(\"Quantity must be positive\"); if (quantity == 0) return false; if (quantity > remaining) return false; remaining -= quantity; return true;`. Use validation tools to identify validation before applying. Reserve its Java file, hold the reservation for 35 seconds, apply the saved plan through GraphHarness, release immediately, then validate the applied edit through GraphHarness using `javac -d build java/*.java && java -cp build tickets.TicketOfficeTest`. Report failures honestly."
            process_a = launch_agent(args.codex, launcher, checkout, runtime, AGENT_A, prompt_a, args.model, a_log)
            processes.append(process_a)
            wait_for(lambda: any(e.get("agent_label") == AGENT_A and e.get("event_type") == "lease_acquired" for e in collector.snapshot()), 90, "writer lease", [process_a])
            b_log = tempfile.NamedTemporaryFile(prefix="graphharness-record-b-", suffix=".log", delete=False)
            logs.append(b_log)
            prompt_b = "Use only GraphHarness MCP tools. Do not use shell or direct edits, except `sleep 40` for the deliberate recording checkpoint. Search and read a TypeScript or Python symbol first, then read TicketInventory.reserve and attempt exactly one reservation of its Java file. If busy, do not edit; after 40 seconds reread the Java source, reserve once, and release it. Report failures honestly."
            process_b = launch_agent(args.codex, launcher, checkout, runtime, AGENT_B, prompt_b, args.model, b_log)
            processes.append(process_b)
            wait_for(lambda: any(e.get("agent_label") == AGENT_B and e.get("event_type") == "lease_denied" for e in collector.snapshot()), 90, "orchestrated conflict", [process_b])
            page.get_by_role("searchbox", name="Search symbols or files").fill("reserve")
            page.locator("#activity").get_by_text("reservation denied").wait_for(state="visible")
            page.screenshot(path=str(artifacts / "orchestrated-conflict.png"), full_page=True)
            wait_for(lambda: any(e.get("agent_label") == AGENT_A and e.get("event_type") == "edit_applied" for e in collector.snapshot()), 120, "applied edit")
            page.get_by_text("applied an edit").wait_for(state="visible")
            page.get_by_role("button", name="View applied edit diff").click()
            page.get_by_role("heading", name="Applied edit").wait_for(state="visible")
            page.screenshot(path=str(artifacts / "applied-edit.png"), full_page=True)
            wait_for(lambda: all(p.poll() is not None for p in processes), args.timeout, "Codex clients")
            context.close(); browser.close()
            if pairing_video:
                Path(pairing_video.path()).unlink(missing_ok=True)
        events = collector.snapshot()
        a_events, b_events = relevant(events, AGENT_A), relevant(events, AGENT_B)
        sessions_a = sorted({e.get("session_id") for e in a_events if e.get("session_id")})
        sessions_b = sorted({e.get("session_id") for e in b_events if e.get("session_id")})
        after_test = fixture_test(checkout)
        event_id = lambda event: int(event.get("event_id", -1))
        writer_acquired = next((e for e in a_events if e.get("event_type") == "lease_acquired"), None)
        writer_applied = next((e for e in a_events if e.get("event_type") == "edit_applied"), None)
        writer_released = next((e for e in a_events if e.get("event_type") == "lease_released"), None)
        reader_denied = next((e for e in b_events if e.get("event_type") == "lease_denied"), None)
        reader_reacquired = next((e for e in b_events if e.get("event_type") == "lease_acquired"), None)
        reader_reread = next((e for e in b_events if writer_released and e.get("event_type") == "tool_completed" and e.get("tool_name") == "get_source" and event_id(e) > event_id(writer_released)), None)
        ordered = bool(writer_acquired and writer_applied and writer_released and reader_denied and reader_reacquired and reader_reread and event_id(writer_acquired) < event_id(reader_denied) < event_id(writer_applied) < event_id(writer_released) < event_id(reader_reread) < event_id(reader_reacquired))
        report = {
            "configured_model": args.model,
            "model_identity_verified": False,
            "content": "public fixture only; credentials and private client logs were deleted after the run",
            "activity_claim": "Two real Codex CLI clients performed observed MCP operations. Reservation contention was deliberately orchestrated, not autonomous behavior.",
            "languages_expected": ["java", "typescript", "python"],
            "health": {"daemon_epoch": epoch, "stream_healthy": not collector.transport_failure and not collector.reset and not heartbeat.failure, "agent_exit_codes": [p.returncode for p in processes]},
            "distinct_session_ids": {"writer": sessions_a, "reader": sessions_b, "distinct": bool(set(sessions_a).isdisjoint(sessions_b))},
            "repair_test": {"before_expected_failure": before_test.returncode != 0 and "Quantity must be positive" in before_test.stderr, "after_exit_code": after_test.returncode, "after_output": after_test.stdout[-500:]},
            "ordered_observed_sequence": ordered,
            "journal": compact_events([e for e in events if e.get("agent_label") in {AGENT_A, AGENT_B}]),
        }
        (artifacts / "recording-evidence.json").write_text(json.dumps(report, indent=2) + "\n")
        if not report["health"]["stream_healthy"] or not report["distinct_session_ids"]["distinct"] or not sessions_a or not sessions_b or any(code != 0 for code in report["health"]["agent_exit_codes"]) or not ordered or after_test.returncode:
            raise RuntimeError("recording evidence health check failed")
    finally:
        if context:
            try:
                context.close()
            except Exception:
                pass
        if browser:
            try:
                browser.close()
            except Exception:
                pass
        if pairing_video:
            try:
                Path(pairing_video.path()).unlink(missing_ok=True)
            except Exception:
                pass
        if collector: collector.close()
        if heartbeat: heartbeat.close()
        for process in processes:
            if process.poll() is None:
                process.terminate()
                try: process.wait(timeout=5)
                except subprocess.TimeoutExpired: process.kill()
        for log in logs:
            name = log.name
            log.close()
            Path(name).unlink(missing_ok=True)
        if observer and endpoint and epoch:
            json_request(endpoint, "/sessions/current", "DELETE", credential=observer["session_credential"], epoch=epoch)
        if daemon.poll() is None:
            daemon.terminate()
            try: daemon.wait(timeout=10)
            except subprocess.TimeoutExpired: daemon.kill()
        shutil.rmtree(temporary, ignore_errors=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Record a public-fixture, orchestrated two-Codex GraphHarness demonstration.")
    parser.add_argument("launcher")
    parser.add_argument("--ui", default="ui/dist")
    parser.add_argument("--fixture", default="examples/ticket-office")
    parser.add_argument("--artifacts", default="artifacts/recording")
    parser.add_argument("--chromium")
    parser.add_argument("--codex", default="codex")
    parser.add_argument("--model", default="gpt-5.6-terra")
    parser.add_argument("--timeout", type=int, default=240)
    main(parser.parse_args())
