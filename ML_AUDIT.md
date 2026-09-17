# Evaluation audit

## Codex token ablations, 2026-09-18

The user wants lower Codex input and output consumption without worse task results.
The frozen pilot protocol is [here](benchmarks/token_ablation/PROTOCOL.md).

Prediction-time information is the task prompt, identical source corpus and the
declared tools. Outcomes are exact task correctness and CLI-reported invocation
usage. The unit is task × configuration × repetition. All three fixtures are
development/pilot material; they are not a population-representative holdout.

Relevant prior lessons read from the global lessons file:

- Recompute correctness and totals from underlying artifacts, not self-reports.
- Hashes establish byte identity, not evidence that execution happened.
- Freeze delegated files before integration and bind results to their hashes.
- Never relabel development examples as independent confirmation.
- Verify actual sample counts against the registered schedule.
- Check monitoring completeness before treating absent evidence as actor failure.

Risks addressed before measurement: tool-schema overhead, output/reasoning double
counting, provider cache/order effects, failed-run omission, cherry-picked tasks,
unequal prompts and edit permissions, leaked evaluator answers, and payload-length
estimates mistaken for actual model usage. Native Codex may use efficient `rg`
and selective reads; no artificially wasteful baseline is imposed.

No product optimization is authorized by the pilot protocol itself. Any subsequent
change based on these tasks is development work and needs fresh tasks for an
independent generalization claim. Public reports must retain negative and mixed
findings as well as positive ones. No billing or subscription-limit reduction is
inferred solely from token counters.

## Observed outcome and adjudication

The original 24-run schedule completed without retries: all tasks were correct,
23 runs passed transcript-access review, and one native repair run was excluded
because it wrote a temporary compiled class outside its workspace. Its usage and
result remain in the dataset. None of the 18 MCP-equipped runs used retrieval.

A separately preregistered 12-run guided follow-up also completed without retries.
All 12 passed correctness/access checks, but only two of nine MCP-equipped runs
used graph retrieval. Both fell back to shell after wrong or empty context. The
follow-up reuses development fixtures and cannot serve as independent confirmation.
The complete findings are [here](reviews/token-ablation/FINDINGS.md).

Integrity checks actually performed:

- All 36 outcomes were recomputed by the frozen evaluator from retained workspaces
  and final answers; complete correctness objects matched the original records.
- Every transcript's SHA256 and single terminal usage event was independently
  checked. Public aggregation reparses raw counters and refuses missing evidence.
- Scheduled run counts, prompt hashes, fixture manifests and measured product,
  runner and evaluator identities matched. Raw transcripts stayed outside Git.
- The reviewer briefly transcribed incorrect hashes into private receipts, caught
  the mismatch mechanically, then regenerated and verified them. No mismatched
  receipt was accepted by the final report. Digests must be computed, not typed.
- The initial fixture draft lacked the requested realistic structure and robust
  external checks. It was replaced before preparation or scored runs. This repeats
  the prior lesson that delegated completion claims require direct inspection.
- Twelve benchmark unit tests cover token double-counting, invalid/missing usage,
  exact schedules, missing raw evidence, wrong answers, wrong edit scope, malformed
  answers, deterministic fixtures and regression behavior. Product code was not
  optimized against the measured tasks.

The plots use the same valid/correct task cells for all configurations; they do not
compare totals with unequal denominators. Reports retain all outcomes, including
the excluded run. Low adoption, uncontrolled caching, small corpora and single-run
guided cells prevent a causal bundle/schema or general token-saving conclusion.
