# /// script
# requires-python = ">=3.11"
# ///

import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request


AGENT_A = "Codex coordinated writer A"
AGENT_B = "Codex coordinated reader B"


def json_request(endpoint, path, method="GET", payload=None, credential=None, epoch=None, timeout=10):
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    request = urllib.request.Request(endpoint + path, data=data, method=method)
    request.add_header("Accept", "application/json")
    if data is not None:
        request.add_header("Content-Type", "application/json")
    if credential:
        request.add_header("Authorization", "Bearer " + credential)
    if epoch:
        request.add_header("X-GraphHarness-Epoch", epoch)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())


class EventCollector:
    def __init__(self, endpoint, credential, epoch):
        self.endpoint, self.credential, self.epoch = endpoint, credential, epoch
        self.events = []
        self.lock = threading.Lock()
        self.stop = threading.Event()
        self.transport_failure = None
        self.reset = None
        self.thread = threading.Thread(target=self._run, daemon=True)

    def start(self):
        self.thread.start()

    def _run(self):
        request = urllib.request.Request(self.endpoint + "/events?after=0")
        request.add_header("Authorization", "Bearer " + self.credential)
        request.add_header("X-GraphHarness-Epoch", self.epoch)
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                event_type = "message"
                for raw in response:
                    if self.stop.is_set():
                        return
                    line = raw.decode("utf-8", errors="strict").strip()
                    if line.startswith("event: "):
                        event_type = line[7:]
                        continue
                    if not line.startswith("data: "):
                        continue
                    event = json.loads(line[6:])
                    if event_type == "reset":
                        self.reset = event.get("reason", "reset")
                        return
                    with self.lock:
                        self.events.append(event)
        except (OSError, ValueError, urllib.error.URLError) as error:
            if not self.stop.is_set():
                self.transport_failure = type(error).__name__

    def snapshot(self):
        with self.lock:
            return list(self.events)

    def wait_for(self, predicate, timeout):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if self.transport_failure or self.reset:
                return False
            if predicate(self.snapshot()):
                return True
            time.sleep(0.1)
        return False

    def close(self):
        self.stop.set()


class MonitorHeartbeat:
    def __init__(self, endpoint, credential, epoch):
        self.endpoint, self.credential, self.epoch = endpoint, credential, epoch
        self.stop = threading.Event()
        self.failure = None
        self.thread = threading.Thread(target=self._run, daemon=True)

    def start(self):
        self.thread.start()

    def _run(self):
        while not self.stop.wait(10):
            try:
                status, _ = json_request(self.endpoint, "/sessions/heartbeat", "POST", {"schema_version": 1}, self.credential, self.epoch)
                if status != 200:
                    self.failure = "heartbeat_status_" + str(status)
                    return
            except (OSError, ValueError, urllib.error.URLError) as error:
                self.failure = type(error).__name__
                return

    def close(self):
        self.stop.set()


def wait_for_descriptor(runtime, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        descriptors = list(runtime.glob("*.json"))
        if len(descriptors) == 1:
            return json.loads(descriptors[0].read_text())
        time.sleep(0.1)
    raise RuntimeError("daemon did not publish a runtime descriptor")


def launch_agent(codex, launcher, root, runtime, label, prompt, model, log):
    command = [
        codex, "exec", "--ignore-user-config", "--ephemeral", "--sandbox", "workspace-write",
        "--skip-git-repo-check", "--model", model, "--cd", str(root),
        "--config", "mcp_servers.graphharness.command=" + json.dumps(str(launcher)),
        "--config", "mcp_servers.graphharness.args=" + json.dumps(["bridge", str(root), label]),
        "--config", "mcp_servers.graphharness.env.GRAPHHARNESS_RUNTIME_DIR=" + json.dumps(str(runtime)),
        "--config", "mcp_servers.graphharness.required=true",
        "--config", 'mcp_servers.graphharness.default_tools_approval_mode="approve"',
        prompt,
    ]
    return subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=dict(os.environ))


def relevant(events, label):
    return [event for event in events if event.get("agent_label") == label]


def compact_events(events):
    fields = ("event_id", "event_type", "session_id", "agent_label", "operation_id", "tool_name", "status")
    return [{field: event[field] for field in fields if event.get(field) is not None} for event in events]


def run_attempt(args, attempt):
    launcher = Path(args.launcher).resolve()
    ui = Path(args.ui).resolve()
    codex = args.codex
    with tempfile.TemporaryDirectory(prefix="graphharness-coordinated-agents-") as directory:
        base = Path(directory)
        root, runtime = base / "fixture", base / "runtime"
        root.mkdir()
        runtime.mkdir(mode=0o700)
        (root / "Counter.java").write_text("public class Counter {\n    public int value() { return 1; }\n}\n")
        daemon_log = tempfile.NamedTemporaryFile(prefix="graphharness-daemon-", suffix=".log", delete=False)
        daemon = subprocess.Popen([str(launcher), "daemon", str(root), str(ui), "--allow-edits"], stdout=subprocess.DEVNULL, stderr=daemon_log, env=dict(os.environ, GRAPHHARNESS_RUNTIME_DIR=str(runtime)))
        monitor_credential = None
        collector = heartbeat = None
        agents = []
        try:
            descriptor = wait_for_descriptor(runtime, 90)
            endpoint, epoch, bootstrap = descriptor["endpoint"], descriptor["daemon_epoch"], descriptor["bootstrap_credential"]
            status, session = json_request(endpoint, "/sessions", "POST", {
                "schema_version": 1, "agent_label": "demo journal observer", "client": {"name": "coordinated_agent_demo", "version": "1"},
            }, bootstrap)
            if status != 200:
                raise RuntimeError("monitor session creation failed")
            monitor_credential = session["session_credential"]
            collector = EventCollector(endpoint, monitor_credential, epoch)
            collector.start()
            heartbeat = MonitorHeartbeat(endpoint, monitor_credential, epoch)
            heartbeat.start()

            a_log = tempfile.NamedTemporaryFile(prefix="graphharness-codex-a-", suffix=".log", delete=False)
            b_log = tempfile.NamedTemporaryFile(prefix="graphharness-codex-b-", suffix=".log", delete=False)
            agents.extend([a_log, b_log])
            prompt_a = (
                "Use the GraphHarness MCP tools for all code inspection and edits. Do not edit files directly. "
                "Find Counter.value, read fresh source, plan replacing its body with `return 2;`, and reserve Counter.java. "
                "After the reservation succeeds, keep it for 35 seconds before applying the saved plan and releasing it. "
                "You may use `sleep 35` only to create that checkpoint; do not use shell commands to inspect or modify files. "
                "Then apply through GraphHarness and release the lease. Report MCP failure if any step is unavailable."
            )
            process_a = launch_agent(codex, launcher, root, runtime, AGENT_A, prompt_a, args.model, a_log)
            agents.append(process_a)
            acquired = collector.wait_for(lambda events: any(event.get("agent_label") == AGENT_A and event.get("event_type") == "lease_acquired" for event in events), 90)
            if not acquired:
                raise RuntimeError("writer did not acquire a lease through the daemon")

            prompt_b = (
                "Use the GraphHarness MCP tools for all inspection and coordination. Do not edit files directly. "
                "Read Counter.value, then attempt to reserve Counter.java exactly once. If GraphHarness reports lease_busy, do not edit; "
                "wait 40 seconds using only `sleep 40`, reread Counter.value, reserve Counter.java once, and release it. "
                "If the initial reservation succeeds, report that the requested contention checkpoint was missed and release it."
            )
            process_b = launch_agent(codex, launcher, root, runtime, AGENT_B, prompt_b, args.model, b_log)
            agents.append(process_b)
            deadline = time.monotonic() + args.timeout
            while any(process.poll() is None for process in (process_a, process_b)) and time.monotonic() < deadline:
                time.sleep(0.2)
            if any(process.poll() is None for process in (process_a, process_b)):
                raise RuntimeError("agent execution exceeded the configured timeout")
            collector.wait_for(lambda events: any(event.get("agent_label") == AGENT_B and event.get("event_type") == "lease_released" for event in events), 10)
            events = collector.snapshot()
            a_events, b_events = relevant(events, AGENT_A), relevant(events, AGENT_B)
            a_acquired = next((event for event in a_events if event.get("event_type") == "lease_acquired"), None)
            denied = next((event for event in b_events if event.get("event_type") == "lease_denied"), None)
            applied = next((event for event in a_events if event.get("event_type") == "edit_applied"), None)
            released = next((event for event in a_events if event.get("event_type") == "lease_released"), None)
            b_read_after = [event for event in b_events if released and event.get("event_type") == "tool_completed" and event.get("tool_name") == "get_source" and int(event["event_id"]) > int(released["event_id"])]
            b_acquired_after = next((event for event in b_events if event.get("event_type") == "lease_acquired"), None)
            a_session_ids = {event.get("session_id") for event in a_events if event.get("session_id")}
            b_session_ids = {event.get("session_id") for event in b_events if event.get("session_id")}
            conflict = bool(denied and a_acquired and int(denied["event_id"]) > int(a_acquired["event_id"]))
            post_release = bool(released and b_acquired_after and int(b_acquired_after["event_id"]) > int(released["event_id"]))
            fixture_file = "Counter.java"
            lease_file_verified = bool(a_acquired and denied and applied and released and b_acquired_after and all(
                fixture_file in event.get("file_paths", []) for event in (a_acquired, denied, applied, released, b_acquired_after)
            ))
            stream_healthy = not collector.transport_failure and not collector.reset and not heartbeat.failure
            compliant = bool(conflict and applied and released and post_release and b_read_after and lease_file_verified and stream_healthy and a_session_ids and b_session_ids and a_session_ids.isdisjoint(b_session_ids) and process_a.returncode == 0 and process_b.returncode == 0)
            return {
                "attempt": attempt,
                "configured_model": args.model,
                "model_identity_verified": False,
                "codex_cli_version": subprocess.check_output([codex, "--version"], text=True).strip(),
                "agent_exit_codes": {"a": process_a.returncode, "b": process_b.returncode},
                "authoritative_journal": compact_events([event for event in events if event.get("agent_label") in {AGENT_A, AGENT_B}]),
                "journal_event_count": len(events),
                "distinct_agent_session_ids": {"a": sorted(a_session_ids), "b": sorted(b_session_ids)},
                "overlap_observed": bool(a_acquired and denied),
                "lease_busy_observed": bool(denied),
                "apply_observed": bool(applied),
                "release_observed": bool(released),
                "reader_proceeded_after_release": post_release,
                "lease_file_verified": lease_file_verified,
                "monitor_stream": {"healthy": stream_healthy, "transport_failure": collector.transport_failure, "reset": collector.reset, "heartbeat_failure": heartbeat.failure},
                "compliant": compliant,
            }
        finally:
            if collector:
                collector.close()
            if heartbeat:
                heartbeat.close()
            for item in agents:
                if isinstance(item, subprocess.Popen) and item.poll() is None:
                    item.terminate()
                    try:
                        item.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        item.kill()
                        item.wait()
                elif hasattr(item, "close"):
                    item.close()
            if monitor_credential:
                json_request(endpoint, "/sessions/current", "DELETE", credential=monitor_credential, epoch=epoch)
            if daemon.poll() is None:
                daemon.terminate()
                try:
                    daemon.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    daemon.kill()
                    daemon.wait()
            daemon_log.close()


def main(args):
    reports = []
    for attempt in range(1, args.attempts + 1):
        try:
            report = run_attempt(args, attempt)
        except Exception as error:
            report = {"attempt": attempt, "compliant": False, "failure": type(error).__name__}
        reports.append(report)
        if report.get("compliant"):
            break
    output = {"demo": "two real Codex CLI processes with daemon-attributed events", "attempts": reports}
    rendered = json.dumps(output, indent=2)
    if args.report:
        Path(args.report).write_text(rendered + "\n")
    print(rendered)
    return 0 if any(report.get("compliant") for report in reports) else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("launcher")
    parser.add_argument("ui")
    parser.add_argument("--codex", default="codex")
    parser.add_argument("--model", default="gpt-5.6-terra")
    parser.add_argument("--attempts", type=int, choices=(1, 2), default=1)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--report")
    raise SystemExit(main(parser.parse_args()))
