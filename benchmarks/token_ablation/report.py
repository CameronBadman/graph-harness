from __future__ import annotations

import argparse
from collections import Counter
import csv
import json
from pathlib import Path
import statistics

from run import ARMS, digest, parse_usage, stable, write_json


METRICS = ("input_tokens", "cached_input_tokens", "uncached_input_tokens", "output_tokens",
           "reasoning_output_tokens", "total_tokens")


def collect(base):
    frozen = json.loads((base / "frozen.json").read_text())
    raw_rows = json.loads((base / "results.json").read_text())
    reviews = json.loads((base / "access-review.json").read_text())
    expected = {row["id"]: row for row in frozen["schedule"]}
    if len({row["id"] for row in raw_rows}) != len(raw_rows):
        raise ValueError("duplicate run")
    rows = []
    for raw in raw_rows:
        row = dict(raw)
        registered = expected[row["id"]]
        if any(row[key] != registered[key] for key in ("arm", "task", "repetition")):
            raise ValueError("run metadata differs from schedule")
        if row["frozen_sha256"] != digest((base / "frozen.json").read_bytes()):
            raise ValueError("run not bound to frozen protocol")
        spec = frozen["tasks"][row["task"]]
        if row["prompt_sha256"] != digest(spec["prompt"].encode()):
            raise ValueError("prompt differs from registered task")
        if row["fixture_sha256"] != spec["manifest_sha256"] or row["workspace_before_sha256"] != spec["manifest_sha256"]:
            raise ValueError("fixture differs from registered corpus")
        events_file = base / row["id"] / "events.jsonl"
        if row.get("raw_events_sha256") and not events_file.is_file():
            raise ValueError("raw evidence missing")
        if events_file.is_file():
            raw_events = events_file.read_bytes()
            if digest(raw_events) != row["raw_events_sha256"]:
                raise ValueError("raw evidence changed")
            if row["usage"] is not None:
                events = [json.loads(line) for line in raw_events.splitlines() if line.strip()]
                if parse_usage(events) != row["usage"]:
                    raise ValueError("usage summary differs from raw counters")
        review = reviews.get(row["id"], {})
        audited = (review.get("passed") is True and review.get("raw_events_sha256") == row.get("raw_events_sha256")
                   and bool(review.get("auditor")) and bool(review.get("method")) and not review.get("violations"))
        row["access_audit"] = review or {"status": "pending"}
        row["valid_measurement"] = bool(audited and row.get("instrumentation_valid"))
        rows.append(row)
    return frozen, rows


def summarize(rows):
    arms = {}
    for arm in ARMS:
        attempts = [row for row in rows if row["arm"] == arm]
        valid = [row for row in attempts if row["valid_measurement"]]
        correct = [row for row in valid if row["correctness"]["passed"]]
        used = [row for row in correct if row["diagnostics"]["retrieval_used"]]
        tool_counts = Counter()
        for row in valid:
            tool_counts.update(row["diagnostics"]["tool_calls"])
        arms[arm] = {
            "attempts": len(attempts), "valid": len(valid), "correct": len(correct),
            "correct_with_retrieval": len(used),
            "retrieval_interpretation": "not applicable" if arm == "native" else (
                "sufficient adoption for this pilot" if correct and len(used) >= len(correct) / 2
                else "inconclusive about retrieval efficiency: fewer than half used MCP retrieval"),
            "totals": {metric: sum(row["usage"][metric] for row in valid) for metric in METRICS},
            "median": {metric: statistics.median(row["usage"][metric] for row in valid) if valid else None
                       for metric in METRICS},
            "range": {metric: [min(row["usage"][metric] for row in valid),
                                max(row["usage"][metric] for row in valid)] if valid else None
                      for metric in METRICS},
            "tool_calls": dict(tool_counts),
            "elapsed_seconds": sum(row.get("elapsed_seconds", 0) for row in attempts),
            "setup_seconds": sum(row.get("setup_seconds", 0) for row in attempts),
        }
    paired = []
    index = {(row["task"], row["repetition"], row["arm"]): row for row in rows}
    for task, repetition in sorted({(row["task"], row["repetition"]) for row in rows}):
        for base, treatment in (("native", "full"), ("native", "lean"), ("native", "no_bundle"),
                                ("full", "lean"), ("no_bundle", "lean")):
            left, right = index.get((task, repetition, base)), index.get((task, repetition, treatment))
            if left is None or right is None:
                continue
            if not left["valid_measurement"] or not right["valid_measurement"]:
                continue
            paired.append({
                "task": task, "repetition": repetition, "baseline": base, "treatment": treatment,
                "both_correct": left["correctness"]["passed"] and right["correctness"]["passed"],
                "difference": {metric: right["usage"][metric] - left["usage"][metric] for metric in METRICS},
                "percent_change": {metric: 100 * (right["usage"][metric] / left["usage"][metric] - 1)
                                   if left["usage"][metric] else None for metric in METRICS},
            })
    return {"arms": arms, "pairs": paired}


def render(frozen, rows, summary, output):
    output.mkdir(parents=True, exist_ok=True)
    write_json(output / "frozen.json", frozen)
    write_json(output / "runs.json", rows)
    write_json(output / "summary.json", summary)
    with (output / "runs.csv").open("w") as stream:
        fields = ["id", "task", "repetition", "arm", "correct", "valid", *METRICS,
                  "elapsed_seconds", "setup_seconds", "retrieval_used"]
        writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
        writer.writeheader()
        for row in rows:
            writer.writerow({**{key: row.get(key) for key in fields if key in row},
                             **{key: (row.get("usage") or {}).get(key) for key in METRICS},
                             "correct": row["correctness"]["passed"], "valid": row["valid_measurement"],
                             "retrieval_used": row.get("diagnostics", {}).get("retrieval_used")})
    guided = frozen.get("experiment") == "guided-workflow-follow-up"
    repetitions = len({row["repetition"] for row in frozen["schedule"]})
    title = "Guided Codex workflow follow-up" if guided else "Codex tool-availability pilot"
    lines = ["# " + title, "",
             f"{len(rows)} of {len(frozen['schedule'])} planned scored invocations recorded. "
             f"{sum(row['valid_measurement'] for row in rows)} passed instrumentation and transcript-access review.", "",
             f"Configured model: `{frozen['model']}`, reasoning `{frozen['reasoning_effort']}`; "
             f"client `{frozen['codex_version']}`. These are CLI-reported invocation counters.", "",
             "Input includes cached input; output includes reasoning. No dollar-cost or subscription-quota claim. "
             "Aggregate totals cover valid runs only; do not compare totals with unequal valid counts. "
             "Use the matched-correct comparisons below.", "",
             "| Configuration | Correct / valid / attempted | Input total | Uncached input | Output total | MCP retrieval among correct |",
             "| --- | --- | ---: | ---: | ---: | ---: |"]
    for name, arm in summary["arms"].items():
        lines.append(f"| {name} | {arm['correct']} / {arm['valid']} / {arm['attempts']} | "
                     f"{arm['totals']['input_tokens']:,} | {arm['totals']['uncached_input_tokens']:,} | "
                     f"{arm['totals']['output_tokens']:,} | {arm['correct_with_retrieval']} / {arm['correct']} |")
    if not any(row.get("diagnostics", {}).get("retrieval_used") for row in rows if row["arm"] != "native"):
        lines += ["", "**No MCP-equipped run used GraphHarness retrieval.** This experiment is inconclusive "
                  "about retrieval efficiency. Token differences here cannot be attributed to graph retrieval "
                  "or context bundles; the agents used native tools."]
    lines += ["", "## Individual runs", "",
              "| Task | Rep | Configuration | Correct | Valid | Input | Cached | Output | Shell calls | MCP calls |",
              "| --- | ---: | --- | --- | --- | ---: | ---: | ---: | ---: | ---: |"]
    for row in rows:
        usage = row.get("usage") or {}
        calls = row.get("diagnostics", {}).get("tool_calls", {})
        lines.append(f"| {row['task']} | {row['repetition']} | {row['arm']} | {row['correctness']['passed']} | "
                     f"{row['valid_measurement']} | {usage.get('input_tokens', 'unknown')} | "
                     f"{usage.get('cached_input_tokens', 'unknown')} | {usage.get('output_tokens', 'unknown')} | "
                     f"{calls.get('shell', 0)} | {sum(count for key, count in calls.items() if key.startswith('mcp:'))} |")
    lines += ["", "## Paired correct comparisons", "",
              "Positive percentages mean more tokens in the treatment. These medians are across matched "
              "task/repetition cells where both configurations were correct; all failures remain above.", "",
              "| Treatment vs baseline | Correct pairs | Median input change | Range | Median output change | Range |",
              "| --- | ---: | ---: | --- | ---: | --- |"]
    for base, treatment in (("native", "full"), ("native", "lean"), ("native", "no_bundle"),
                            ("full", "lean"), ("no_bundle", "lean")):
        pairs = [pair for pair in summary["pairs"] if pair["baseline"] == base
                 and pair["treatment"] == treatment and pair["both_correct"]]
        if not pairs:
            continue
        inputs = [pair["percent_change"]["input_tokens"] for pair in pairs]
        outputs = [pair["percent_change"]["output_tokens"] for pair in pairs]
        lines.append(f"| {treatment} vs {base} | {len(pairs)} | {statistics.median(inputs):+.1f}% | "
                     f"{min(inputs):+.1f}% to {max(inputs):+.1f}% | {statistics.median(outputs):+.1f}% | "
                     f"{min(outputs):+.1f}% to {max(outputs):+.1f}% |")
    lines += ["", "## Boundaries", "",
              f"This is a small generated-fixture pilot: {repetitions} repetition(s) of three tasks. Java reasoning and "
              "repair share the same source corpus, so there are only two distinct source corpora. "
              "There is no significance or general-product savings claim. Provider caching is uncontrolled; "
              "the randomized schedule and cached/uncached counts are preserved. Configured model identity "
              "is not independently attested. Calibration is excluded.", "",
              ("Identical conditional guidance requests bundle-first or search/source-first inspection where available. "
               "The tasks reuse development fixtures; this is exploratory, not independent confirmation. "
               if guided else "Agents freely choose inspection tools; the experiment measures tool availability. ") +
              "All configurations permit native tools; actual MCP-use subgroups are observational. Full versus "
              "lean changes feature availability as well as schema size. Lean versus no_bundle changes bundle "
              "availability and its schema" + (", plus prescribed routing policy" if guided else "") +
              ". The pilot does not test leases, multiple agents, browser utility, "
              "large repositories or long conversations. Outside-workspace inspection is prohibited and "
              "transcripts are reviewed, but the filesystem is not a sealed evaluation vault.", "",
              "Raw transcripts are retained privately outside Git. `runs.json` binds usage to their digests, "
              "the frozen schedule, evaluator and product artifacts. `summary.json` includes medians, "
              "ranges and individual paired differences. Missing counters are unknown, never zero.", ""]
    (output / "REPORT.md").write_text("\n".join(lines))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("artifacts", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    frozen, rows = collect(args.artifacts)
    render(frozen, rows, summarize(rows), args.output)
