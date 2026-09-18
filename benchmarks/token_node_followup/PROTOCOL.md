# Repaired node editing and explicit workflow: development rerun

Registered before new product results or model trials, 2026-09-18.

## Decision and scope

Fix the confirmed mismatch between backend-qualified Java parameter types and
compiler source spellings without weakening edit target identity. Make overload
resolution and advertised node editing explicit in the emitted Codex guidance.
Then test whether the resulting full workflow lowers total task input/output.

This reuses the three known Java repair tasks from token_interface unchanged.
Their answers and earlier trajectories are now development data. This is an
authorized diagnostic rerun, not a fresh holdout, unbiased generalization estimate,
or isolated causal estimate of the parser fix. Do not alter fixture answers,
evaluators or task selection to favor the new product. Prior results stay intact.

## Treatments and execution

Five arms: native, compact, slim, node, combined. Three tasks, two repetitions,
30 scored invocations. Native has native tools only. All graph arms have the same
four navigation tools and exactly the same new emitted guidance. Slim/combined
enable source-v1; node/combined additionally expose replace_node_body. The shared
guidance explicitly discovers the editor, resolves overloads by node_id and asks
for supported in-scope Java body edits through that editor when available. Native
fallback remains available and its use must be reported, not excluded afterward.
All graph daemons have writes enabled; bridge tool exposure controls the factor.

Rerun every control contemporaneously. Use configured gpt-5.6-terra, medium
reasoning and the same native permissions/base task prompt as the prior study.
Add one identical requirement to every arm: validate the repair with compilation
and focused behavior checks using native tools. This pre-launch revision equalizes
validation demand that otherwise appeared only in graph guidance. It is a disclosed
change from the previous study, not a change made after observing rerun outcomes.
New graph guidance changes the workflow; compare costs including that guidance.
Randomize arm order within each task/repetition block and block order with seed
2026091805. Run sequentially, with a 240-second model timeout and no silent retries.
Stop new launches after two consecutive infrastructure failures. Keep all failures.

Freeze the built distribution, runner, unchanged task evaluator, protocol, emitted
guidance, exact prompts/schemas, CLI launcher/native binary, known host instructions
and schedule. Verify before each invocation. Use fresh task workspaces outside
ignored ancestors and verify admitted source counts. Never expose preflight
answers, prior result workspaces or evaluator internals to the scored model.

## Required preflight

Before any scored model launch, use the actual frozen Joern backend on separate
fresh copies of every target family. Resolve the precise target, read current
source/snapshot/hash and obtain a parser-checked plan with the original body.
Then perform a real one-call node repair using a retained previously verified
repair, independently compile/check behavior and unchanged scope, and verify
committed=true plus a changed raw-byte file hash. Bind the target, backend,
arguments, receipts and final workspace. These non-model preflights are excluded
from token totals and are not evidence of autonomous adoption. Abort launch if
any target is ineligible, no commit occurs or correctness/scope fails. Preserve
failed preflights, fix/refreeze before scoring, and never rerun over an attempt.

Also run five separate unscored model calibrations for actual native/graph routes,
source-v1 and node commits. Require behavior, routes, raw instrumentation and
independent access audit to pass. Do not treat calibration on a simple method as
proof of target-family eligibility; both gates are necessary.

## Metrics and adjudication

Use one terminal usage event per invocation: input includes cached input; output
includes reasoning. Report both subsets without double-counting. The primary
denominator is the same valid/correct task/repetition cells across all five arms.
Keep costs, errors and correctness for every scheduled attempt, including invalid
and noncompliant ones. Do not select only runs that used the desired tool.

Report native vs each graph arm, compact vs slim, node vs combined, compact vs node,
and slim vs combined. Percentage decrease is 100*(baseline-treatment)/baseline;
negative means increased usage. Report actual retrieval, node calls/commits,
source-v1 use, native fallback, shell calls and observed repeated source exposure.
Presentation bytes remain distinct from actual model tokens. Cache is uncontrolled.

The acceptance gate remains >=20% less aggregate total input than native, no
aggregate output increase, all six attempts correct/access-valid, complete common
coverage and retrieval in at least half. Node/combined also require node commits
in at least half; slim/combined require actual new-format use in at least half.
State exact adoption counts even when this minimum passes. Nonuse or support
failure limits claims about execution of the mechanism; guided success would not
prove default autonomous adoption. No billing or production-efficiency claim.

An independent reviewer audits commands, writes, actual MCP responses, source
provenance, final edits, raw stderr and usage. The external evaluator is not an OS
sandbox, so inspect for exploitation as well as passing behavior. Recompute all
outcomes and bindings. Retain old and new calibration, score and access records
separately; archive private evidence without credentials/runtime directories.

## Ownership and stop rule

Separate workers own safe parser matching and actionable tool guidance; the
integrator owns preflight/runner, full build and Git index; the independent reviewer
does not author those changes. Commit verified milestones. Once scoring starts,
do not tune product, prompts or evaluator from intermediate outcomes. Complete
the fixed schedule unless its registered infrastructure stop condition is met.
Report the measured result honestly, including a negative or mixed outcome.
