# Guided node-edit development rerun

Read [PROTOCOL.md](PROTOCOL.md). This reruns the previous three known repair tasks
with the parser fix and explicit node-edit guidance. It is development data,
not a fresh holdout. The original evaluator and fixture bytes are unchanged.
Native and all four graph controls run again: 30 scored invocations in total.
Every arm receives the same compilation/behavior-check requirement.

Build and preserve the complete installed product before freezing. Run:

```sh
python3 -m unittest benchmarks.token_node_followup.test_preflight benchmarks.token_node_followup.test_report benchmarks.token_node_followup.test_tasks benchmarks.token_node_followup.test_calibration -v
node --test scripts/codex-guidance.test.mjs
python3 benchmarks/token_node_followup/run_trials.py prepare --artifacts /tmp/gh-node-rerun-new --launcher /path/to/preserved-install/bin/graphharness
python3 benchmarks/token_node_followup/run_trials.py preflight --artifacts /tmp/gh-node-rerun-new
```

Preflight uses separate fresh copies of every task, the actual Joern backend,
unchanged-body planning and real node writes using known verified repair fixtures.
It externally checks behavior and unchanged scope. It is not a model experiment
and never places repaired code or evaluator answers in scored workspaces. Missing,
failed, unsupported or unbound target evidence blocks model launch.

Then run five unscored model calibrations:

```sh
python3 benchmarks/token_node_followup/run_trials.py calibrate --artifacts /tmp/gh-node-rerun-new
```

Independently audit preflight and all calibration commands, writes, MCP calls,
stderr and counters. Write private `access-review.json` keyed by calibration/run
ID with recomputed `raw_events_sha256`, `stderr_sha256`, `passed`, auditor identity,
method and violations. All calibration gates must pass before scoring. Preserve
failed attempts; fix and refreeze in a new directory rather than overwriting them.

```sh
python3 benchmarks/token_node_followup/run_trials.py run --artifacts /tmp/gh-node-rerun-new
python3 benchmarks/token_node_followup/report.py --artifacts /tmp/gh-node-rerun-new --output /tmp/gh-node-rerun-report
uv run --locked benchmarks/token_node_followup/plot.py /tmp/gh-node-rerun-report
```

Extend independent access review to all scored attempts before publishing a report.
The collector regrades outcomes, verifies target preflight, binds raw configuration
and usage, and compares the same valid/correct cells across all arms. Report every
failure and actual route, including native fallback or node-tool nonuse. Input
already includes cache and output already includes reasoning; percentages are
decreases, with negative values meaning more tokens. No billing claim follows.

Keep prompts, raw transcripts, answers, credentials and machine paths private.
Archive evidence with rehashed manifests while excluding daemon runtime directories.
Do not overwrite the earlier studies or describe this rerun as independent
confirmation on unseen tasks.
