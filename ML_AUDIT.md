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

## Retrieval repairs and fresh confirmation, 2026-09-18

The [registered follow-up protocol](benchmarks/token_followup/PROTOCOL.md) compares
native, preserved old build, repaired full tools with discovery guidance, and the
repaired compact profile with the same guidance. The decision and unit remain total
input/output per correct task × configuration × repetition. The success rule was
at least 20% less total input, no output increase, full correctness and retrieval
in at least half the treatment runs. No arm met it.

An independent fixture worker prepared three new synthetic tasks on two corpora;
the product repairs were frozen before fixture answers were inspected. Product,
prompts, evaluator, schedule, CLI executable/launcher and known host instruction
hashes were frozen before the randomized 24-run schedule. Four integration
calibrations are separate from all scored totals. Every scored run was correct
and access-valid; none was retried or excluded. An independent reviewer reproduced
all complete scored evaluator outcomes, raw usage totals and registered artifact
hashes. All arms share the same six correct/valid task cells.

Against native, repaired full tools increased input/output by 46.1%/38.2%; compact
navigation increased them by 18.2%/16.9%. Both used retrieval in every run. The old
build increased them by 7.8%/20.5% with no scored retrieval. These are descriptive
results: explicit guidance differs by treatment, profile changes are bundled,
provider caching is uncontrolled, runtime errors occurred, and tasks are small.
They cannot establish separate causal effects or production generalization.

Additional integrity checks and verified corrections:

- Direct old/fixed replay reproduced wrong constructor selection and missed path
  search. Three invalid replay observations from an ignored ancestor directory are
  retained and excluded; scored runs independently check admitted source counts.
- A zero-model mock-provider diagnostic captured actual client requests. It proves
  deferred discovery and a single-result projection route, not real-agent compliance
  or provider token savings. Raw host context stays private. Known host files are
  hashed, but complete effective context and external runtimes are not fully pinned.
- Historical stderr revealed seven policy rejections missed by JSONL completed-call
  counters. Old usage remains unchanged. Follow-up stderr is retained and audited;
  its zero approval rejections does not exclude observed runtime/sandbox failures.
- Regrading detected nondeterministic temporary-path text in failed compiler
  diagnostics. Semantic failure fields must still match; both differing hashes and
  the exception are retained. Adversarial tests reject changed semantic outcomes.
- A new Java file-source test expected a type node incorrectly. An independent JVM
  probe established file-node behavior; only the expectation changed. All 97 JVM
  and 23 follow-up benchmark tests subsequently passed.
- All attempts, raw usage, failures and exclusions remain bound to private archived
  evidence. Public reports omit credentials and raw host instructions. No fresh
  result was used to tune the product before this report.

The global lessons record the generalized discovery/presentation, index-admission
and transient-diagnostic failure patterns. Mechanical prevention now includes
real admission checks, raw evidence binding, frozen treatment hashes and semantic
regrade comparisons. [Findings](reviews/token-fix/FINDINGS.md) and the
[independent review](reviews/token-fix/INDEPENDENT_REVIEW.md) state remaining limits.
