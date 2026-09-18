# Diagnosing and retesting Codex token overhead

Read [PROTOCOL.md](PROTOCOL.md) before running. This follow-up preserves the old
installed GraphHarness, compares it with the repaired full and navigation profiles,
and retains an efficient native Codex baseline. Fixtures and evaluators were authored
separately from the product fixes. This is a small generated development comparison.

The [completed findings](../../reviews/token-fix/FINDINGS.md) retain the negative
result: neither repaired configuration reduced input and output versus native.

The [client diagnostic](../../debug/client-probe.json) uses a local mock provider
with fixed responses and no actual model calls. It measures client discovery and
serialization behavior; its mock counters are not token-efficiency measurements.

```sh
python3 benchmarks/token_followup/client_probe.py --artifacts /tmp/gh-client-new --report /tmp/gh-client-report.json
python3 -m unittest discover -s benchmarks/token_followup -p 'test_*.py' -v
```

Use an installed legacy distribution saved before the fixes and a tested repaired
distribution. The benchmark requires the pinned CLI/model access, Java, Node, Python,
and the installed language adapters. Avoid placing workspaces below ignored source
directories. The runner checks source-file admission before invoking the model.

```sh
python3 benchmarks/token_followup/run_trials.py prepare --artifacts /tmp/gh-followup-new --legacy /path/to/legacy/bin/graphharness --repaired /path/to/repaired/bin/graphharness
python3 benchmarks/token_followup/run_trials.py calibrate --artifacts /tmp/gh-followup-new
```

Independently inspect each calibration's commands, edits, MCP calls, stderr, and
workspace for access outside the permitted task or to evaluator data. Write
`access-review.json` keyed by run ID. Each receipt requires the recomputed
`raw_events_sha256`, `stderr_sha256`, boolean `passed`, auditor identity, method,
and `violations` list. Calibration requires all four valid and correct routes.

```sh
python3 benchmarks/token_followup/run_trials.py run --artifacts /tmp/gh-followup-new
```

Extend the independent access review to all 24 attempts, including unsuccessful
ones. Never retry an existing attempt directory to replace an inconvenient result.
Repaired and navigation arms include the exact emitted `codex-instructions` snippet;
its token overhead is counted. Native tools remain available to every arm.

```sh
python3 benchmarks/token_followup/report.py --artifacts /tmp/gh-followup-new --output /tmp/gh-followup-report
uv run --locked benchmarks/token_followup/plot.py /tmp/gh-followup-report
```

The collector verifies raw bindings and recomputes correctness and counters. Its
percentage decreases use task/repetition cells valid and correct in every arm;
negative decreases mean increases. All attempts remain visible. Cache subsets are
reported separately; input includes cache and output includes reasoning exactly once.

Keep raw captures private: they can contain machine paths, host instructions, and
runtime authentication material. Commit only reviewed sanitized reports. A safe
archive excludes daemon runtime directories and preserves original digests.
