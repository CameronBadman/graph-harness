from __future__ import annotations

import argparse
import json
from pathlib import Path
import random
import shutil
import subprocess

import run


GUIDANCE = (
    "For repository inspection, follow this routing policy: if build_context_bundle is "
    "available, use it as your first source-retrieval operation with the task text and "
    "token_budget=1800. If that tool is unavailable but search_graph is available, start "
    "with search_graph and get_source. If neither is available, use efficient native "
    "shell reads. After the initial retrieval, use whichever available tools you need "
    "to finish correctly. Use native editing and test commands for any required change."
)


def prepare(original, target, launcher, codex):
    frozen = run.load_frozen(original, launcher, codex)
    records = json.loads((original / "results.json").read_text())
    if len(records) != len(frozen["schedule"]):
        raise ValueError("finish the original schedule before the follow-up")
    eligible = [row for row in records if row["arm"] != "native" and row["correctness"]["passed"]]
    if not eligible or sum(row["diagnostics"]["retrieval_used"] for row in eligible) >= len(eligible) / 2:
        raise ValueError("predeclared low-adoption trigger not met")
    if target.exists() and any(target.iterdir()):
        raise ValueError("follow-up requires an empty artifact directory")
    target.mkdir(parents=True, exist_ok=True, mode=0o700)
    frozen["calibration_origin_frozen_sha256"] = run.digest((original / "frozen.json").read_bytes())
    frozen["experiment"] = "guided-workflow-follow-up"
    frozen["guidance"] = GUIDANCE
    frozen["guided_runner_sha256"] = run.digest(Path(__file__).read_bytes())
    frozen["guided_protocol_sha256"] = run.digest(Path(__file__).with_name("GUIDED_PROTOCOL.md").read_bytes())
    frozen["repository_head"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=run.ROOT, text=True).strip()
    rng = random.Random(20260919)
    tasks = list(frozen["tasks"])
    rng.shuffle(tasks)
    schedule = []
    for task in tasks:
        arms = list(run.ARMS)
        rng.shuffle(arms)
        for arm in arms:
            schedule.append({"id": f"{task}-1-{arm}", "task": task, "repetition": 1, "arm": arm})
        frozen["tasks"][task]["prompt"] += "\n\n" + GUIDANCE
    frozen["schedule_seed"] = 20260919
    frozen["schedule"] = schedule
    run.write_json(target / "frozen.json", frozen)
    calibration = json.loads((original / "calibration.json").read_text())
    reviews = json.loads((original / "access-review.json").read_text())
    for row in calibration:
        source = original / row["id"]
        destination = target / row["id"]
        destination.mkdir(mode=0o700)
        for name in ("record.json", "events.jsonl"):
            shutil.copyfile(source / name, destination / name)
    run.write_json(target / "calibration.json", calibration)
    run.write_json(target / "access-review.json", {row["id"]: reviews[row["id"]] for row in calibration})
    print(json.dumps({"prepared_guided_runs": len(schedule),
                      "frozen_sha256": run.digest((target / "frozen.json").read_bytes())}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=("prepare", "run"))
    parser.add_argument("--original", type=Path, required=True)
    parser.add_argument("--artifacts", type=Path, required=True)
    parser.add_argument("--launcher", type=Path, default=run.ROOT / "build/install/graphharness/bin/graphharness")
    parser.add_argument("--codex", default="codex")
    args = parser.parse_args()
    if args.action == "prepare":
        prepare(args.original.resolve(), args.artifacts.resolve(), args.launcher.resolve(), args.codex)
    else:
        frozen = json.loads((args.artifacts / "frozen.json").read_text())
        if frozen["guided_runner_sha256"] != run.digest(Path(__file__).read_bytes()):
            raise ValueError("guided runner changed after preparation")
        if frozen["guided_protocol_sha256"] != run.digest(Path(__file__).with_name("GUIDED_PROTOCOL.md").read_bytes()):
            raise ValueError("guided protocol changed after preparation")
        run.run_all(args.artifacts.resolve(), args.launcher.resolve(), args.codex)
