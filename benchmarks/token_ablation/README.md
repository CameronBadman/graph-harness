# Measuring GraphHarness's effect on Codex tokens

This benchmark runs real, fresh `codex exec --json` sessions. It checks task
correctness independently and records the CLI's input, cached input, output and
reasoning counters. `scripts/benchmark_demo.py` is an older payload-size heuristic;
its character estimates are not interchangeable with this benchmark's measurements.

Read [PROTOCOL.md](PROTOCOL.md) before running. It fixes three generated tasks,
four configurations and two repetitions. Read [GUIDED_PROTOCOL.md](GUIDED_PROTOCOL.md)
for the separately registered low-adoption follow-up. Both are small development
pilots; neither is a general coding benchmark or an independent held-out evaluation.

Prerequisites: a built GraphHarness distribution with parsers and UI assets, Java
21, Node and Python, and an authenticated Codex CLI supporting the registered model
and flags. The original run used CLI 0.154.0 and configured `gpt-5.6-terra` at medium
reasoning effort. The runner records exact CLI and installed artifact hashes; it
does not verify the provider's model identity or estimate a monetary bill.

Run the mechanical checks first:

```sh
python3 -m unittest discover -s benchmarks/token_ablation -p 'test_*.py' -v
```

Prepare a new private artifact directory and perform the four unscored integration
calibrations:

```sh
python3 benchmarks/token_ablation/run.py prepare --artifacts /tmp/gh-token-new
python3 benchmarks/token_ablation/run.py calibrate --artifacts /tmp/gh-token-new
```

Review each calibration's completed shell commands, file changes and MCP arguments
and results for outside-workspace/evaluator access. Write `access-review.json` in
that private directory, keyed by calibration ID. Each entry needs the actual JSONL
SHA256, `passed`, auditor identity, method and violations. A passing integration
run is deliberately not accepted as an audited measurement automatically.

```json
{
  "calibration-native": {
    "raw_events_sha256": "REPLACE_WITH_ACTUAL_DIGEST",
    "passed": true,
    "auditor": "reviewer identifier",
    "method": "reviewed completed shell, file-change and MCP events",
    "violations": []
  }
}
```

Then execute the frozen schedule. The model sessions run sequentially; the daemon
is freshly started/indexed for every MCP-equipped invocation. Each task has a
240-second timeout. Two consecutive infrastructure failures stop new launches.

```sh
python3 benchmarks/token_ablation/run.py run --artifacts /tmp/gh-token-new
```

Extend the access review to every scored invocation, using its actual transcript
digest. Review any ambiguous filesystem accesses manually; the host is not a sealed
benchmark vault. Do not grant a passing receipt based only on the model's answer.
Keep failed runs, model nonuse, timeouts and unknown token counts in the evidence.

```sh
python3 benchmarks/token_ablation/report.py /tmp/gh-token-new /tmp/gh-token-report
uv run --locked benchmarks/token_ablation/plot.py /tmp/gh-token-report
```

The report independently reparses terminal usage events and checks schedule, prompt,
fixture and review bindings. Raw transcripts, daemon descriptors and credentials
remain private. Only reviewed sanitized records and plots should enter Git.

If the completed availability pilot meets the registered low-adoption trigger:

```sh
python3 benchmarks/token_ablation/guided.py prepare --original /tmp/gh-token-new --artifacts /tmp/gh-token-guided
python3 benchmarks/token_ablation/guided.py run --original /tmp/gh-token-new --artifacts /tmp/gh-token-guided
```

Audit and report this second experiment separately. Its guidance is fixed at a
bundle budget of 1800; actual arguments and compliance remain observable outcomes.
It reuses development fixtures, so cache carry-over and previous exposure must be
reported. It does not convert them into a fresh holdout.

Do not delete and rerun an interrupted trial. An existing unrecorded directory
requires an explicit adjudication record, including what was observed and what is
unknown. If infrastructure or benchmark code must be corrected, preserve the failed
attempt and prepare a new artifact directory with a new manifest. Never silently
overwrite earlier evidence or replace the configured model midway.
