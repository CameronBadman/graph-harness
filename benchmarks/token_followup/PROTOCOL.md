# Retrieval repair and navigation-profile confirmation

Registered before scored runs on 2026-09-18. Decision: whether the repaired
integration merits further token-efficiency work. The earlier 36 trials stay intact.

## Treatments and units

Three independently authored fresh generated tasks (localized Java repair, Java
multi-file navigation, TS/Python navigation), two fresh sessions each, four arms:

- native: efficient normal Codex shell/edit tools, no GraphHarness.
- legacy: the preserved installed artifact from the original pilot, full MCP tools.
- repaired: corrected resolution plus explicit code-mode discovery/projection guidance
  from `graphharness codex-instructions` appended to the task, and initialization instructions,
  otherwise full tools and full responses.
- navigation: the same repaired artifact with `bridge --navigation`: bundle,
  search, single/batched source tools, and a compact projection of the same results.

24 invocations, randomized arm order within task/repetition blocks, seed 2026091803.
Same base task prompt, model (`gpt-5.6-terra`), medium reasoning, CLI flags, output schema,
workspace, and permissions across arms. Preserve native tools in every arm. No
hard tool restriction: actual adoption is an outcome. Repaired and navigation arms
receive the same explicit integration guidance (including its token overhead), which
the native and legacy arms do not. Initialization guidance is not initially visible
in the installed client's code mode; the user-visible setup snippet addresses that.
The repaired-vs-legacy comparison changes related features and cannot identify
their separate effects. Navigation changes the tool interface and response format
together; it is a profile comparison, not a clean payload-only ablation.

## Gates, freezing and accounting

Freeze installed artifact/CLI/runner/evaluator/protocol hashes, known host instruction/
rule file hashes, the exact emitted integration snippet, and fixture manifests
before execution; confirm these bindings before every invocation. Product workers
do not inspect fresh evaluator answers while implementing. No product/prompt changes
after freezing. Every run uses a new workspace and, when applicable, ready daemon.
Check indexed file counts against the independent source manifest before the
model runs; fixtures must not silently disappear under ancestor ignore rules.
Perform one unscored integration calibration per arm on a separate simple fixture
first. Audit calibration access, correctness, and expected route before scored runs.
Capture the real bridge initialize/tools-list contract. Repaired/navigation
calibrations exercise the emitted discovery/bundle workflow; legacy uses search/read.
Bind both the CLI launcher and native binary; execute that native binary directly.

Use the real terminal usage counters: total input includes cached input, output
includes reasoning; neither subset is added twice. Retain stderr policy-rejection
counts separately because rejected shell calls can be missing from JSONL tool items.
No API-price, subscription-quota or cache-controlled claim. Daemon preparation time
is separate from model time. Timeout 240 seconds per invocation; preserve failures,
unknown usage and incorrect answers. Stop launching after two consecutive
infrastructure failures. Never silently retry an existing attempt directory.

External evaluators test answers and repair behavior/scope, independently of the
graph. Temporary probe/build files may exist under `.scratch/`; source bytes outside
that directory remain constrained. Identical prompts tell all arms to keep temporary
files inside the workspace and not delete files. No evaluator/other-session access.
Independently review commands/MCP calls/file changes and stderr for every invocation;
pending or failed access review excludes savings claims but remains in the report.

## Reporting and decision rule

Report all attempts, correctness, access exclusions, adoption, policy rejections,
input/cached/uncached/output tokens, and latency. Show totals and percentage reduction
`100 * (baseline - treatment) / baseline` on common valid/correct task/repetition
cells. Also show individual paired results, including regressions, not just winners.

The proposed continuation threshold is at least 20% fewer aggregate input tokens
than native, no aggregate output increase, all tasks correct, and actual retrieval
in at least half the treatment runs. Passing this small pilot justifies broader
evaluation, not a general product claim. Failure remains a negative result. Low
adoption prevents attribution to retrieval. No post-result fixture or threshold edits.

These fresh synthetic tasks are confirmation within a development investigation;
they are not a production-repository benchmark or a statistical generalization.
Repeated runs share task corpora and provider cache state is uncontrolled. Prior
shell-policy rejections, stochastic turns and raw payload size are separate factors.
