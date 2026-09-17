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
