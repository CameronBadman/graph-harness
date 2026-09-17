# /// script
# requires-python = ">=3.11"
# dependencies = ["matplotlib==3.10.6"]
# ///

import argparse
import json
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
matplotlib.rcParams["svg.hashsalt"] = "graphharness-token-pilot"


def plot(directory):
    rows = json.loads((directory / "runs.json").read_text())
    labels = ["Native Codex", "Full harness", "Lean harness", "Without bundles"]
    keys = ["native", "full", "lean", "no_bundle"]
    cells = [set((row["task"], row["repetition"]) for row in rows if row["arm"] == key
                 and row["valid_measurement"] and row["correctness"]["passed"]) for key in keys]
    matched = set.intersection(*cells)
    if not matched:
        raise ValueError("no common correct cells for a fair plot")
    totals = {key: {metric: sum(row["usage"][metric] for row in rows if row["arm"] == key
                               and (row["task"], row["repetition"]) in matched)
                    for metric in ("input_tokens", "cached_input_tokens", "output_tokens", "reasoning_output_tokens")}
              for key in keys}
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.5), layout="constrained")
    frozen = json.loads((directory / "frozen.json").read_text())
    repetitions = len({row["repetition"] for row in frozen["schedule"]})
    phase = "Guided workflow" if frozen.get("experiment") else "Tools available; no graph retrieval used"
    fig.suptitle(f"{phase}\n{len(matched)} matched correct task/repetition cells per configuration", fontsize=13)
    for axis, metric, subset, title in (
        (axes[0], "input_tokens", "cached_input_tokens", "Input tokens (cached included)"),
        (axes[1], "output_tokens", "reasoning_output_tokens", "Output tokens (reasoning included)"),
    ):
        total = [totals[key][metric] for key in keys]
        part = [totals[key][subset] for key in keys]
        remainder = [a - b for a, b in zip(total, part)]
        axis.barh(labels, remainder, color="#2463a0", label="Uncached" if metric == "input_tokens" else "Other output")
        axis.barh(labels, part, left=remainder, color="#a9cae8", label="Cached" if metric == "input_tokens" else "Reasoning")
        axis.invert_yaxis()
        axis.set_title(title, fontsize=11)
        axis.set_xlabel("Tokens summed across the same matched cells")
        axis.xaxis.set_major_locator(matplotlib.ticker.MaxNLocator(5))
        axis.xaxis.set_major_formatter(matplotlib.ticker.FuncFormatter(lambda x, _: f"{x / 1000:g}k" if x else "0"))
        axis.spines[["top", "right"]].set_visible(False)
        axis.legend(loc="upper center", bbox_to_anchor=(0.5, -0.22), ncol=2, fontsize=8)
        axis.margins(x=0.22)
        for index, value in enumerate(total):
            axis.annotate(f"{value:,}", (value, index), xytext=(5, 0), textcoords="offset points", va="center", fontsize=9)
    svg = directory / "token-comparison.svg"
    fig.savefig(svg, metadata={"Date": None})
    svg.write_text("\n".join(line.rstrip() for line in svg.read_text().splitlines()) + "\n")
    fig.savefig(directory / "token-comparison.png", dpi=160)
    plt.close(fig)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    plot(parser.parse_args().directory)
