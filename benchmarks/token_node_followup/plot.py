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


def render(directory):
    summary = json.loads((directory / "summary.json").read_text())
    comparisons = [row for row in summary["comparisons"] if row["baseline"] == "native"]
    if len(comparisons) != 4 or not summary["common_cells"]:
        raise ValueError("four configurations and common valid/correct cells are required")
    labels = {"compact": "Current compact", "slim": "Source format", "node": "Node tool offered", "combined": "Format + tool offered"}
    plt.rcParams.update({"font.family": "DejaVu Sans", "svg.hashsalt": "graphharness-token-interface"})
    fig, axes = plt.subplots(1, 2, figsize=(11, 5.2), layout="constrained")
    for axis, metric, title in zip(axes, ("input_tokens", "output_tokens"), ("Input tokens", "Output tokens")):
        values = [row["percent_decrease"][metric] for row in comparisons]
        axis.barh(range(4), values, color=["#24845b" if value >= 0 else "#b44442" for value in values])
        axis.set_yticks(range(4), [labels[row["treatment"]] for row in comparisons])
        axis.invert_yaxis()
        axis.axvline(0, color="#334155", linewidth=1)
        axis.set_xlabel("Decrease versus native Codex (%)")
        axis.set_title(title, fontweight="bold")
        extent = max(20, max(abs(value) for value in values) * 1.3)
        axis.set_xlim(min(-10, min(values) - extent * .25), max(25, max(values) + extent * .25))
        for y, value in enumerate(values):
            axis.annotate(f"{value:+.1f}%", (value, y), xytext=(5 if value >= 0 else -5, 0),
                          textcoords="offset points", va="center", ha="left" if value >= 0 else "right")
        axis.spines[["top", "right"]].set_visible(False)
    count = len(summary["common_cells"])
    enabled = [summary["arms"][arm] for arm in ("node", "combined")]
    used = sum(arm["node_edit_committed_runs"] for arm in enabled)
    attempts = sum(arm["attempts"] for arm in enabled)
    fig.suptitle(f"Actual Codex usage · {count} matched correct task/repetition cells\n"
                 f"Negative percentages mean MORE tokens · node commits in {used}/{attempts} enabled runs", fontsize=13)
    fig.savefig(directory / "token-change.png", dpi=170)
    svg_path = directory / "token-change.svg"
    fig.savefig(svg_path, metadata={"Date": None})
    svg_path.write_text("\n".join(line.rstrip() for line in svg_path.read_text().splitlines()) + "\n")
    plt.close(fig)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("report", type=Path)
    render(parser.parse_args().report)
