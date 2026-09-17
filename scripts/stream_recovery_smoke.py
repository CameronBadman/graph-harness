# /// script
# requires-python = ">=3.11"
# dependencies = ["playwright==1.63.0"]
# ///

"""Scripted browser stream recovery coverage; it never changes production behavior."""

import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import urllib.request

from playwright.sync_api import sync_playwright


def request(endpoint, path, method="GET", body=None, token=None, epoch=None):
    headers = {"Content-Type": "application/json"}
    if token: headers["Authorization"] = f"Bearer {token}"
    if epoch: headers["X-GraphHarness-Epoch"] = epoch
    data = json.dumps(body).encode() if body is not None else None
    with urllib.request.urlopen(urllib.request.Request(endpoint + path, data=data, headers=headers, method=method), timeout=20) as response:
        return response.status, json.loads(response.read())


def descriptor(runtime):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        found = list(runtime.glob("*.json"))
        if found: return json.loads(found[0].read_text())
        time.sleep(.05)
    raise TimeoutError("daemon descriptor did not appear")


def event_after(endpoint, cursor, token, epoch, operation):
    headers = {"Authorization": f"Bearer {token}", "X-GraphHarness-Epoch": epoch}
    with urllib.request.urlopen(urllib.request.Request(f"{endpoint}/events?after={cursor}", headers=headers), timeout=30) as response:
        event, data = None, []
        while True:
            line = response.readline().decode().rstrip()
            if not line:
                if event == "activity" and data:
                    payload = json.loads("\n".join(data))
                    if payload.get("event_type") == "tool_completed" and payload.get("operation_id") == str(operation): return payload
                event, data = None, []
            elif line.startswith("event:"): event = line[6:].strip()
            elif line.startswith("data:"): data.append(line[5:].strip())


INIT = """
(() => {
  const nativeFetch = window.fetch.bind(window);
  const control = window.__ghRecovery = { mode: 'none', streams: 0, rejected: 0, resetStatus: null, epochStatus: null, active: null, cursors: [] };
  window.fetch = async (...args) => {
    const input = args[0], url = new URL(typeof input === 'string' ? input : input.url, location.href);
    if (url.pathname !== '/events') return nativeFetch(...args);
    control.streams += 1; control.cursors.push(url.searchParams.get('after'));
    const mode = control.mode; control.mode = 'none';
    if (mode === 'reject') { control.rejected += 1; throw new TypeError('Test-only fetch rejection'); }
    const options = { ...(args[1] || {}), headers: new Headers((args[1] || {}).headers || {}) };
    if (mode === 'stale') url.searchParams.set('after', '-1');
    if (mode === 'epoch') options.headers.set('X-GraphHarness-Epoch', 'test-stale-epoch');
    const response = await nativeFetch(url.toString(), options);
    if (mode === 'stale') control.resetStatus = response.status;
    if (mode === 'epoch') control.epochStatus = response.status;
    if (!response.ok || !response.body) return response;
    const reader = response.body.getReader(); let ended = false;
    return new Response(new ReadableStream({
      start(controller) {
        control.active = { stop(error) {
          if (ended) return; ended = true; void reader.cancel();
          if (error) controller.error(new TypeError('Test-only read failure')); else controller.close();
        }};
        void (async () => {
          try {
            while (!ended) {
              const next = await reader.read();
              if (ended) return;
              if (next.done) { ended = true; controller.close(); return; }
              controller.enqueue(next.value);
            }
          } catch (error) { if (!ended) { ended = true; controller.error(error); } }
        })();
      }, cancel(reason) { ended = true; return reader.cancel(reason); }
    }), { status: response.status, statusText: response.statusText, headers: response.headers });
  };
})();
"""


def main():
    parser = argparse.ArgumentParser(description="Scripted browser SSE recovery smoke; not agent evidence.")
    parser.add_argument("launcher"); parser.add_argument("--ui", default="ui/dist"); parser.add_argument("--fixture", default="examples/ticket-office"); parser.add_argument("--chromium", required=True); parser.add_argument("--artifacts", default="/tmp/graphharness-stream-recovery")
    args = parser.parse_args(); Path(args.artifacts).mkdir(parents=True, exist_ok=True); source_launcher, ui, fixture = Path(args.launcher).resolve(), Path(args.ui).resolve(), Path(args.fixture).resolve()
    temp = Path(tempfile.mkdtemp(prefix="graphharness-stream-recovery-")); checkout, runtime, distribution = temp / "fixture", temp / "runtime", temp / "distribution"
    shutil.copytree(fixture, checkout); runtime.mkdir(mode=0o700); shutil.copytree(source_launcher.parent.parent, distribution); launcher = distribution / "bin" / source_launcher.name
    env = dict(os.environ, GRAPHHARNESS_RUNTIME_DIR=str(runtime)); daemon = subprocess.Popen([str(launcher), "daemon", str(checkout), str(ui)], env=env, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        info = descriptor(runtime); endpoint, epoch = info["endpoint"], None
        _, agent = request(endpoint, "/sessions", "POST", {"schema_version": 1, "agent_label": "RECOVERY SCRIPT", "client": {"name": "stream-recovery", "version": "1"}}, info["bootstrap_credential"])
        token, epoch = agent["session_credential"], agent["daemon_epoch"]
        with sync_playwright() as pw:
            browser = pw.chromium.launch(headless=True, executable_path=args.chromium); context = browser.new_context(viewport={"width": 1440, "height": 900}); page = context.new_page(); page.set_default_timeout(30_000); errors = []
            page.add_init_script(INIT); page.on("pageerror", lambda error: errors.append(str(error))); page.goto(endpoint)
            page.get_by_role("button", name="Start pairing").click(); code = page.locator("code.command").inner_text().split()[-1]
            approved = subprocess.run([str(launcher), "authorize-browser", str(checkout), code], env=env, capture_output=True, text=True, timeout=10); assert approved.returncode == 0, approved.stderr
            page.locator("#workspace").wait_for(state="visible")

            page.wait_for_function("() => window.__ghRecovery.active !== null")

            def invoke(op):
                request(endpoint, "/sessions/heartbeat", "POST", {"schema_version": 1}, token, epoch)
                _, state = request(endpoint, "/state", token=token, epoch=epoch)
                _, result = request(endpoint, "/tools/call", "POST", {"schema_version": 1, "operation_id": str(op), "name": "search_graph", "arguments": {"query": "reserve"}}, token, epoch)
                assert result["operation_id"] == str(op)
                return event_after(endpoint, state["cursor"], token, epoch, op)

            def rendered(event):
                page.wait_for_function("id => document.querySelectorAll(`#activity [data-event-id='${id}']`).length === 1", arg=event["event_id"])
                page.get_by_text("Live observer", exact=True).wait_for(state="visible")

            outcomes = {}
            for op, name, error, mode in [(1, "read_failure", True, "none"), (2, "clean_eof", False, "none"), (3, "fetch_rejection", False, "reject")]:
                streams = page.evaluate("window.__ghRecovery.streams")
                page.evaluate("options => { window.__ghRecovery.mode = options.mode; window.__ghRecovery.active.stop(options.error); }", {"mode": mode, "error": error})
                event = invoke(op)
                rendered(event)
                assert page.evaluate("window.__ghRecovery.streams") > streams
                outcomes[name] = {"event_id": event["event_id"], "rendered_once": True}
            assert page.evaluate("window.__ghRecovery.rejected") == 1

            for op, name, mode, status in [(4, "same_epoch_reset", "stale", 200), (5, "epoch_409", "epoch", 409)]:
                streams = page.evaluate("window.__ghRecovery.streams")
                page.evaluate("mode => { window.__ghRecovery.mode = mode; window.__ghRecovery.active.stop(false); }", mode)
                field = "resetStatus" if mode == "stale" else "epochStatus"
                page.wait_for_function("check => window.__ghRecovery[check.field] === check.status && window.__ghRecovery.streams >= check.streams + 2", arg={"field": field, "status": status, "streams": streams})
                page.get_by_text("Earlier activity is unavailable after an authoritative stream reset.", exact=True).wait_for(state="visible")
                event = invoke(op)
                rendered(event)
                outcomes[name] = {"server_status": status, "event_id": event["event_id"], "rendered_once": True}
            page.screenshot(path=str(Path(args.artifacts) / "stream-recovery-1440x900.png"), full_page=True)
            assert not errors, errors; context.close(); browser.close()
        print(json.dumps({"scripted": True, "claim_boundary": "Test-only browser transport injection with real server events, reset and epoch rejection; not agent evidence.", "checks": outcomes}))
    finally:
        daemon.terminate()
        try: daemon.wait(timeout=5)
        except subprocess.TimeoutExpired: daemon.kill(); daemon.wait(timeout=5)
        shutil.rmtree(temp, ignore_errors=True)


if __name__ == "__main__": main()
