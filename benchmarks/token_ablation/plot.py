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


def plot(directory):
    arms = json.loads((directory / "summary.json").read_text())["arms"]
    labels = ["Native Codex", "Full harness", "Lean harness", "Without bundles"]
    keys = ["native", "full", "lean", "no_bundle"]
    fig, axes = plt.subplots(1, 2, figsize=(11, 4.5), layout="constrained")
    frozen = json.loads((directory / "frozen.json").read_text())
    repetitions = len({row["repetition"] for row in frozen["schedule"]})
    fig.suptitle(f"Codex token ablation: three small tasks, {repetitions} repetition(s)", fontsize=14)
    for axis, metric, subset, title in (
        (axes[0], "input_tokens", "cached_input_tokens", "Input tokens (cached included)"),
        (axes[1], "output_tokens", "reasoning_output_tokens", "Output tokens (reasoning included)"),
    ):
        total = [arms[key]["totals"][metric] for key in keys]
        part = [arms[key]["totals"][subset] for key in keys]
        remainder = [a - b for a, b in zip(total, part)]
        axis.barh(labels, remainder, color="#2463a0", label="Uncached" if metric == "input_tokens" else "Other output")
        axis.barh(labels, part, left=remainder, color="#a9cae8", label="Cached" if metric == "input_tokens" else "Reasoning")
        axis.invert_yaxis()
        axis.set_title(title, fontsize=11)
        axis.set_xlabel("CLI-reported tokens, summed across valid runs")
        axis.xaxis.set_major_formatter(matplotlib.ticker.StrMethodFormatter("{x:,.0f}"))
        axis.spines[["top", "right"]].set_visible(False)
        axis.legend(loc="lower right", fontsize=8)
        axis.margins(x=0.22)
        for index, value in enumerate(total):
            axis.annotate(f"{value:,}", (value, index), xytext=(5, 0), textcoords="offset points", va="center", fontsize=9)
    fig.savefig(directory / "token-comparison.svg")
    fig.savefig(directory / "token-comparison.png", dpi=160)
    plt.close(fig)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("directory", type=Path)
    plot(parser.parse_args().directory)
