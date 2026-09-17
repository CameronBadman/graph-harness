# Codex token ablation pilot

Status: protocol fixed before scored model runs. Product baseline: `cfc571a`.

The decision is whether making GraphHarness available reduces the model input and
output needed for a correct repository task. The unit is one fresh Codex invocation
on one task, configuration and repetition. This is a descriptive pilot on generated
repositories, not an estimate for typical production repositories or an independent
held-out benchmark. No product, prompt, task or evaluator tuning follows the results
within this experiment.

## Configurations

| Name | Available repository tools |
| --- | --- |
| native | Codex built-in shell and edit tools; no MCP server |
| full | Same built-ins plus the shipped daemon's available read-only GraphHarness tools |
| lean | Same built-ins plus `get_capabilities`, `search_graph`, `get_source`, `get_node_detail`, `build_context_bundle` |
| no_bundle | Same as lean, excluding `build_context_bundle` |

All receive exactly the same task prompt and final-response schema. Agents choose
how to inspect code; none is instructed to prefer GraphHarness or dump whole files.
Repairs use the same direct edit permissions in every configuration. This isolates
context acquisition from coordination and lease costs. The browser is not open.
Its effect on human oversight and concurrent agent coordination is outside this
experiment.

Native versus full estimates the effect of offering the current read-only harness.
Full versus lean changes both tool schemas and available graph features. Lean
versus no_bundle changes bundle availability and its schema. Neither comparison
alone separates response size, schema size and changes in the agent's strategy.
Actual MCP use is reported; comparisons restricted to users of MCP are observational.
If fewer than half of correct runs in a treatment use a GraphHarness retrieval tool,
that treatment is inconclusive about retrieval efficiency.

## Tasks and schedule

Three new deterministic fixtures: Java branch/call-chain reasoning, TypeScript and
Python structural/behavioral lookup, and a Java boundary-condition repair. Each has
a mechanically checked JSON answer; repair additionally requires external behavior
tests and a restricted production diff. Generators, tests and answers live outside
agent workspaces. Task authors do not see token outcomes before freezing their files.
Fixture creation must refuse to erase an existing workspace.

Run each task twice in each of four configurations: 24 scored invocations. Shuffle
task/repetition blocks and configuration order within each block with seed 20260918.
Write and hash the complete schedule before the first scored invocation. Every
invocation starts from identical fixture bytes with a fresh session, never resume.
There is no best-run selection or retry of completed scored invocations.

Before these runs, use a separate one-method calibration fixture for one invocation
per configuration. It must perform a real source read (shell for native; MCP for
the other three), return the value, and expose one terminal usage record. Calibration
prompts intentionally differ by tool route and are excluded from comparative results.
Calibration validates integration and token extraction, not savings.

Pin Codex CLI version, configured model `gpt-5.6-terra`, reasoning effort `medium`,
task timeout 240 seconds and sandbox per task. Disable optional apps, plugins,
memory, subagents, skill discovery and web search equally. Disable ambient AGENTS
loading for these self-contained fixtures. Record requested configuration and actual
MCP inventory/backend. Provider model identity is not independently attested.
Keep product source/build hashes and generator/evaluator/runner hashes in receipts.

The daemon is freshly indexed before each MCP run; setup duration is reported
separately. CLI elapsed time starts after setup. Cache state at the provider cannot
be forced cold. Randomized order and separate cached-input accounting address this
partially; they do not remove all cache/order effects. No token-budget setting or
bundle-budget tuning is introduced; the current tool defaults and agent-selected
arguments are part of the observed behavior.

## Accounting and correctness

Use actual `codex exec --json` `turn.completed.usage`, requiring exactly one terminal
completion, valid nonnegative integer counters, and a successful process exit.
Never substitute character counts or sum repeated cumulative usage records.

- Input: `input_tokens`, including cached input.
- Cached input: `cached_input_tokens`.
- Uncached input: input minus cached input.
- Output: `output_tokens`, including reasoning.
- Reasoning: `reasoning_output_tokens`, a subset of output, not added again.
- Combined measured model tokens: input plus output.
- Cache writes: retain the reported field separately; do not invent billing weights.

These are CLI-reported invocation counters, not independently reconciled invoices
or subscription quota. Tool payload byte counts and call counts are diagnostic
measures, never called token savings. Latency includes model and tool work; indexing
is shown separately.

Evaluate every run independently of its configuration. Report all failures,
timeouts, missing usage and incomplete blocks. Missing counters are unknown, never
zero. Stop further launches after two consecutive infrastructure failures (for
example quota/authentication failure); preserve the partial schedule. Do not replace
the requested model midway to manufacture a complete comparison.

Report success counts first, all measured usage, paired differences for matching
task/repetition cells, and a paired-correct subset requiring correctness in both
compared configurations. Show individual cells, aggregate totals and median/range;
do not claim statistical significance or general savings from this small pilot.
Reducing tokens by returning a wrong answer is not a benefit.

## Leakage and evidence boundaries

Agent workspaces contain only newly generated task source and task instructions.
No prior demo results, product claims, answer keys, hidden tests, task generators,
benchmark reports or other runs are supplied. Prompts prohibit outside-workspace
reads. External evaluation runs after the model finishes on a temporary copy.
The host filesystem is not a sealed benchmark vault; transcripts must be checked
for out-of-workspace access, which invalidates an affected run. Raw logs and
credentials stay outside Git with private permissions. Commit sanitized records,
hashes and reproducible evaluation code, not authentication material.

Independent review before runs checks accounting and confounding. Review after
runs checks the evaluator, arithmetic, exclusions and interpretation. This is an
evaluation of the frozen product, not an optimization loop or score target.

References: [Codex JSONL usage](https://learn.chatgpt.com/docs/non-interactive-mode),
[MCP tool allowlists](https://learn.chatgpt.com/docs/config-file/config-reference),
[output/reasoning token accounting](https://developers.openai.com/api/reference/cli/resources/responses/methods/retrieve).
