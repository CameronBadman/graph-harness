# /// script
# requires-python = ">=3.11"
# dependencies = ["playwright==1.63.0"]
# ///

"""End-to-end local performance evidence. Synthetic inputs and SCRIPT clients are not agent evidence."""

import argparse
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


def request(endpoint, path, method="GET", body=None, token=None, epoch=None, timeout=15):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if epoch:
        headers["X-GraphHarness-Epoch"] = epoch
    data = json.dumps(body).encode() if body is not None else None
    try:
        with urllib.request.urlopen(urllib.request.Request(endpoint + path, data=data, headers=headers, method=method), timeout=timeout) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        raise AssertionError((method, path, error.code, json.loads(error.read()))) from error


def wait_descriptor(runtime, deadline):
    while time.monotonic() < deadline:
        descriptors = list(runtime.glob("*.json"))
        if descriptors:
            return json.loads(descriptors[0].read_text())
        time.sleep(0.05)
    raise TimeoutError("daemon descriptor did not appear")


def create_agent(endpoint, bootstrap):
    _, result = request(endpoint, "/sessions", "POST", {
        "schema_version": 1, "agent_label": "PERF SCRIPT",
        "client": {"name": "performance-smoke", "version": "1"},
    }, bootstrap)
    return result["session_credential"], result["daemon_epoch"]


def call(endpoint, credential, epoch, operation_id, name, arguments):
    return request(endpoint, "/tools/call", "POST", {
        "schema_version": 1, "operation_id": str(operation_id), "name": name, "arguments": arguments,
    }, credential, epoch)


def next_sse_event(endpoint, cursor, token, epoch, expected_type, timeout, predicate=lambda event: True):
    headers = {"Authorization": f"Bearer {token}", "X-GraphHarness-Epoch": epoch}
    request_object = urllib.request.Request(f"{endpoint}/events?after={cursor}", headers=headers)
    with urllib.request.urlopen(request_object, timeout=timeout) as response:
        event_name, data = None, []
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            line = response.readline().decode("utf-8").rstrip("\r\n")
            if not line:
                if event_name == "activity" and data:
                    payload = json.loads("\n".join(data))
                    if payload.get("event_type") == expected_type and predicate(payload):
                        return payload
                event_name, data = None, []
                continue
            if line.startswith("event:"): event_name = line[6:].strip()
            elif line.startswith("data:"): data.append(line[5:].strip())
    raise TimeoutError(f"Timed out waiting for SSE {expected_type}")


def synthetic_fixture(root):
    source = root / "Synthetic.java"
    methods = "\n".join(f"    public static int symbol{i:04d}() {{ return {i}; }}" for i in range(1000))
    source.write_text(f"public final class Synthetic {{\n{methods}\n}}\n", encoding="utf-8")
    return source


def machine_summary():
    memory_kib = None
    try:
        for line in Path("/proc/meminfo").read_text().splitlines():
            if line.startswith("MemTotal:"):
                memory_kib = int(line.split()[1]); break
    except OSError:
        pass
    return {"cpu_count": os.cpu_count(), "memory_gib": round(memory_kib / 1024 / 1024, 2) if memory_kib else None}


def distribution_digest(root):
    import hashlib
    digest = hashlib.sha256()
    for item in sorted(path for path in root.rglob("*") if path.is_file()):
        digest.update(item.relative_to(root).as_posix().encode())
        digest.update(item.read_bytes())
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description="Local synthetic GraphHarness performance smoke; not agent evidence.")
    parser.add_argument("launcher")
    parser.add_argument("--ui", default="ui/dist")
    parser.add_argument("--chromium", required=True)
    parser.add_argument("--samples", type=int, default=30)
    parser.add_argument("--timeout-seconds", type=int, default=90)
    parser.add_argument("--artifacts", default="/tmp/graphharness-performance-smoke")
    args = parser.parse_args()
    assert args.samples >= 30
    launcher, ui, chromium = Path(args.launcher).resolve(), Path(args.ui).resolve(), Path(args.chromium).resolve()
    artifacts = Path(args.artifacts); artifacts.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix="graphharness-performance-fixture-"))
    checkout, runtime, copied_distribution = temporary / "fixture", temporary / "runtime", temporary / "distribution"
    checkout.mkdir(); runtime.mkdir(mode=0o700); source_path = synthetic_fixture(checkout)
    distribution_root = launcher.parent.parent
    shutil.copytree(distribution_root, copied_distribution)
    launcher = copied_distribution / "bin" / launcher.name
    env = os.environ.copy(); env["GRAPHHARNESS_RUNTIME_DIR"] = str(runtime)
    started = time.monotonic()
    daemon = subprocess.Popen([str(launcher), "daemon", str(checkout), str(ui)], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    report = {"scripted": True, "claim_boundary": "Synthetic fixture and SCRIPT client measurements; not autonomous-agent evidence.", "machine": machine_summary(), "samples_requested": args.samples,
              "launcher_distribution_sha256": distribution_digest(copied_distribution)}
    try:
        descriptor = wait_descriptor(runtime, time.monotonic() + args.timeout_seconds)
        endpoint = descriptor["endpoint"]
        credential, epoch = create_agent(endpoint, descriptor["bootstrap_credential"])
        indexed_state = None
        deadline = time.monotonic() + args.timeout_seconds
        while time.monotonic() < deadline:
            _, candidate = request(endpoint, "/state", token=credential, epoch=epoch)
            methods = [node for node in candidate["snapshot"]["nodes"] if node.get("kind") == "method"]
            if candidate["snapshot"]["indexing"] == "idle" and len(methods) >= 1000:
                indexed_state = candidate; break
            time.sleep(0.1)
        if indexed_state is None:
            raise TimeoutError("initial indexing did not reach idle with 1,000 methods")
        report["initial_index_elapsed_ms"] = round((time.monotonic() - started) * 1000, 2)
        report["method_symbol_count"] = len([node for node in indexed_state["snapshot"]["nodes"] if node.get("kind") == "method"])

        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True, executable_path=str(chromium))
            context = browser.new_context(viewport={"width": 1440, "height": 900}, reduced_motion="reduce")
            page = context.new_page(); page.set_default_timeout(15_000); errors = []
            page.on("pageerror", lambda error: errors.append(str(error)))
            page.goto(endpoint)
            page.get_by_role("button", name="Start pairing").click()
            code = page.locator("code.command").inner_text().split()[-1]
            approved = subprocess.run([str(launcher), "authorize-browser", str(checkout), code], env=env, capture_output=True, text=True, timeout=10)
            assert approved.returncode == 0, approved.stderr
            page.locator("#workspace").wait_for(state="visible")
            page.get_by_text("Live observer", exact=True).wait_for(state="visible")
            page.evaluate("""
                () => {
                  const observedAt = new Map();
                  const paintAt = new Map();
                  const recordCurrentItems = () => {
                    document.querySelectorAll('#activity [data-event-id]').forEach(item => {
                      const id = item.dataset.eventId;
                      if (!id) return;
                      if (!observedAt.has(id)) {
                        observedAt.set(id, Date.now());
                        requestAnimationFrame(() => {
                          const value = performance.timeOrigin + performance.now();
                          paintAt.set(id, value);
                          document.querySelectorAll('#activity [data-event-id]').forEach(current => {
                            if (current.dataset.eventId === id) current.dataset.rafOpportunityEpochMs = String(value);
                          });
                        });
                      }
                      item.dataset.domInsertedEpochMs = String(observedAt.get(id));
                      if (paintAt.has(id)) item.dataset.rafOpportunityEpochMs = String(paintAt.get(id));
                    });
                  };
                  new MutationObserver(recordCurrentItems).observe(document.querySelector('#activity'), { childList: true, subtree: true });
                  recordCurrentItems();
                }
            """)
            target = next(node for node in indexed_state["snapshot"]["nodes"] if node.get("kind") == "method" and node.get("name", "").endswith("symbol0000"))
            operation = 1; samples = []; claimed = set()
            for index in range(args.samples):
                call(endpoint, credential, epoch, operation, "search_graph", {"query": f"symbol{index:04d}", "kind": "method"}); operation += 1
                _, source_result = call(endpoint, credential, epoch, operation, "get_source", {"node_id": target["id"]}); operation += 1
                assert "symbol0000" in source_result["result"]["source"]
                if index % 10 == 9:
                    request(endpoint, "/sessions/heartbeat", "POST", {"schema_version": 1}, credential, epoch)
                page.wait_for_function("""claimed => [...document.querySelectorAll('#activity [data-event-id]')].some(item =>
                    !claimed.includes(item.dataset.eventId) && item.dataset.eventType === 'tool_completed' && item.dataset.toolName === 'get_source' && item.dataset.domInsertedEpochMs)""", arg=list(claimed))
                event_id, event = page.evaluate("""claimed => {
                    const item = [...document.querySelectorAll('#activity [data-event-id]')]
                      .filter(item => !claimed.includes(item.dataset.eventId) && item.dataset.eventType === 'tool_completed' && item.dataset.toolName === 'get_source' && item.dataset.domInsertedEpochMs)
                      .sort((a, b) => Number(b.dataset.eventId) - Number(a.dataset.eventId))[0];
                    return [item.dataset.eventId, {timestamp:item.dataset.eventTimestamp, dom_epoch_ms:Number(item.dataset.domInsertedEpochMs), raf_epoch_ms:item.dataset.rafOpportunityEpochMs ? Number(item.dataset.rafOpportunityEpochMs) : null}];
                }""", arg=list(claimed))
                claimed.add(event_id)
                page.wait_for_function("id => { const item = document.querySelector(`#activity [data-event-id='${id}']`); return item?.dataset.rafOpportunityEpochMs !== undefined; }", arg=event_id)
                event["raf_epoch_ms"] = page.evaluate("id => Number(document.querySelector(`#activity [data-event-id='${id}']`).dataset.rafOpportunityEpochMs)", event_id)
                samples.append({"event_id": event_id, "dom_update_ms": page.evaluate("event => event.dom_epoch_ms - Date.parse(event.timestamp)", event), "paint_opportunity_ms": page.evaluate("event => event.raf_epoch_ms - Date.parse(event.timestamp)", event)})
            report["event_latency_method"] = "first MutationObserver DOM insertion of an actual activity item minus daemon UTC event timestamp; paint_opportunity is the following requestAnimationFrame timestamp, not compositor presentation"
            report["event_latency_sample_count"] = len(samples)
            ordered = sorted(item["dom_update_ms"] for item in samples)
            report["event_dom_update_p95_ms"] = ordered[round((len(ordered) - 1) * .95)]
            report["event_paint_opportunity_p95_ms"] = sorted(item["paint_opportunity_ms"] for item in samples)[round((len(samples) - 1) * .95)]

            page.get_by_role("searchbox", name="Search symbols or files").fill("symbol0000")
            page.get_by_role("button", name="Code list").click()
            page.locator("#list-panel button").filter(has_text="symbol0000").first.click()
            page.get_by_role("button", name="Load source").click()
            page.locator("pre.source").wait_for(state="visible")
            assert "symbol0000" in page.locator("pre.source").inner_text()
            page.get_by_role("button", name="Code list").click()
            _, fresh_state = request(endpoint, "/state", token=credential, epoch=epoch)
            before_cursor = fresh_state["cursor"]
            before_snapshot_id = fresh_state["snapshot"]["snapshot_id"]
            edit_started_epoch_ms = time.time() * 1000
            source_path.write_text(source_path.read_text(encoding="utf-8") + "// external performance smoke change\n", encoding="utf-8")
            external = next_sse_event(endpoint, before_cursor, credential, epoch, "snapshot_updated", args.timeout_seconds,
                                      lambda event: event.get("snapshot_id") != before_snapshot_id)
            assert external.get("session_id") is None and external.get("agent_label") is None
            page.wait_for_function("snapshot => document.querySelector('#workspace')?.dataset.snapshotId === snapshot", arg=external["snapshot_id"])
            page.wait_for_function("id => document.querySelector(`#activity [data-event-id='${id}']`)?.dataset.domInsertedEpochMs !== undefined", arg=external["event_id"])
            dom_seen_epoch_ms = page.evaluate("id => Number(document.querySelector(`#activity [data-event-id='${id}']`).dataset.domInsertedEpochMs)", external["event_id"])
            report["external_edit_visible_latency_ms"] = round(dom_seen_epoch_ms - edit_started_epoch_ms, 2)
            report["external_snapshot_owner"] = "unattributed"
            report["targets"] = {"initial_index_ms": 3000, "event_dom_update_p95_ms": 500, "external_edit_visible_ms": 3000}
            report["target_results"] = {
                "initial_index": report["initial_index_elapsed_ms"] <= 3000,
                "event_dom_update": report["event_dom_update_p95_ms"] <= 500,
                "external_edit_visible": report["external_edit_visible_latency_ms"] <= 3000,
            }
            page.get_by_role("button", name="Fit graph").click()
            page.screenshot(path=str(artifacts / "performance-1440x900.png"), full_page=True)
            assert not errors, errors
            context.close(); browser.close()
        (artifacts / "performance-report.json").write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report))
    finally:
        daemon.terminate()
        try: daemon.wait(timeout=5)
        except subprocess.TimeoutExpired: daemon.kill(); daemon.wait(timeout=5)
        shutil.rmtree(temporary, ignore_errors=True)


if __name__ == "__main__":
    main()
