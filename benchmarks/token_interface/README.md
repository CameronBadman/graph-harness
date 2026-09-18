# Source format and node-edit comparison

Read [PROTOCOL.md](PROTOCOL.md). This study compares native Codex with a 2×2 graph
workflow: current/slim bundle format × native/node edits. Three unfamiliar Java
repair tasks and two repetitions give 30 scored invocations. Five integration
calibrations are separate. All graph arms receive identical guidance and have
native fallback; actual mechanism use is measured.

Build/test the product, then preserve the complete installed distribution and
freeze the evaluator before running:

```sh
python3 -m unittest benchmarks.token_interface.test_tasks benchmarks.token_interface.test_report -v
python3 benchmarks/token_interface/run_trials.py prepare --artifacts /tmp/gh-interface-new --launcher /path/to/preserved-install/bin/graphharness
python3 benchmarks/token_interface/run_trials.py calibrate --artifacts /tmp/gh-interface-new
```

Independently audit calibration commands, source changes, MCP calls and stderr.
Write private `access-review.json` keyed by run ID, with recomputed
`raw_events_sha256`, `stderr_sha256`, boolean `passed`, auditor identity, method
and `violations` array. All five calibrations must pass before scoring.

```sh
python3 benchmarks/token_interface/run_trials.py run --artifacts /tmp/gh-interface-new
```

Extend access review to every scored attempt, including failures. Never overwrite
or retry an attempt directory. The collector checks raw bindings, actual CLI/MCP
configuration, write-enabled daemon evidence, usage and complete evaluator outcomes:

```sh
python3 benchmarks/token_interface/report.py --artifacts /tmp/gh-interface-new --output /tmp/gh-interface-report
```

Report negative results and every matched task. Input already includes cached input;
output includes reasoning. Negative percentage decreases mean increases. The same
valid/correct cells are used for every arm; missing/incorrect attempts remain
visible and prevent passing the full acceptance gate.

This is a small synthetic comparison on one shared source corpus. Guidance differs
between native and graph arms; provider caching is uncontrolled. The selected model
is a configured alias. No general billing or production-efficiency conclusion follows.

Raw transcripts, host instructions, machine paths and daemon credentials stay
private. Archive raw evidence without runtime directories; publish only reviewed
sanitized reports. Preserve existing experiments unchanged.
