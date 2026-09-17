# Separately registered guided-workflow follow-up

Registered after the first five availability-pilot runs, before any guided runs.
Those initial runs all used native shell; no MCP-equipped run had used retrieval.
Run this follow-up if the completed availability pilot remains below 50% retrieval
adoption among correct MCP-equipped runs. Preserve the original 24-run experiment.

This follow-up asks whether explicit graph-first guidance makes the current harness
useful. It is an exploratory workflow comparison, not the original tool-availability
effect, a pure payload ablation or independent confirmation.

Use the same three small task fixtures and unchanged product, model, reasoning,
permissions, timeout and evaluator. These are reused development fixtures, not a
fresh holdout. Run one repetition of each of the four configurations, 12 invocations,
with randomized task/configuration order using seed 20260919. Never replace a run
that ignores guidance. Provider cache carry-over is a material limitation; retain
input, cached and uncached counts separately and avoid cold-cache claims.

Append this identical guidance to every task prompt, including native:

> For repository inspection, follow this routing policy: if build_context_bundle is
> available, use it as your first source-retrieval operation with the task text and
> token_budget=1800. If that tool is unavailable but search_graph is available, start
> with search_graph and get_source. If neither is available, use efficient native
> shell reads. After the initial retrieval, use whichever available tools you need
> to finish correctly. Use native editing and test commands for any required change.

This intentionally changes the inspection workflow. Full versus lean can still
change both schemas and capabilities. Lean versus no_bundle compares bundle-first
against search/source-first guidance, including their schemas and routing overhead.
Do not call the difference a pure effect of bundle contents. Native receives the
same conditional text so its prompt-length cost is included too.

Reuse the passing integration calibration only while its CLI, frozen runner, fixture
evaluator and installed product hashes still match. Record the calibration's original
frozen manifest hash and access-review receipts. It supplies no scored token values
to this follow-up. Write a new frozen schedule and prompt manifest before launch,
and require a separate bound transcript-access review for every guided run.

Report the two experiments separately, every individual run, actual routing, task
correctness and input/output counters. One repetition per task cannot establish
stability. No product or prompt optimization occurs during either frozen run set.
