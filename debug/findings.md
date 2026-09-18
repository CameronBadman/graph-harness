# Token-efficiency investigation

## Symptom

At product commit 38492c2, guided trials on three small tasks recorded 47.2% more
input and 34.8% more output for the full toolset versus native Codex. Other profiles
also increased total input. Only two of nine graph-enabled trials used retrieval;
both returned wrong or empty context and then used native shell reads.

## Evidence and scope

Prior evidence: reviews/token-ablation/FINDINGS.md and private archived raw JSONL.
These are development fixtures and descriptive measurements, not a causal estimate
or a clean holdout. Preserve all original runs and exclusions. Use real usage counters,
external correctness checks, source manifests and transcript audits for follow-ups.

## Work allocation

Integrator: protocol/payload inspection, experiment registration, integration/tests,
reviewed milestone commits. Retrieval worker: general resolution fixes and regression
tests. Independent auditor: raw trial and discovery analysis; no product edits.

## Initial hypotheses

See hypotheses.md. Direct reproductions and client diagnostics below precede the
separately frozen model comparison.

## Iteration 1 — independent reproductions

The original implementation failed four of five new focused regression cases:
file-path search, mixed-language targets, overloaded ambiguity, and unknown targets.
The qualified-name fallback-parser case passed; reproducing the observed constructor
requires the actual Joern backend, so it is being checked separately. Command:
`nix develop --command gradle test --tests graphharness.ContextRetrievalTest`.

An installed-CLI mock-provider diagnostic (no real model call) found that GraphHarness
was absent from the initial code-mode tool list. Server initialization guidance was
visible after discovery only. Printing the complete MCP result included text and
structured representations. The optimization therefore includes explicit discovery
and single-representation workflow guidance; initialization instructions alone are
insufficient. The mock provider is a diagnostic boundary, not production inference.

Independent historical stderr review found seven shell policy rejections absent
from JSONL completed-tool counters (four availability, three guided). Original usage
totals remain intact. Extra repair turns cannot be wholly attributed to GraphHarness.
The follow-up freezes known host instructions/rules and tracks stderr separately.

## Iteration 2 — repaired retrieval and limits

Exact original tool arguments were replayed on the preserved and repaired installed
artifacts with Joern 4.0.520/cpg 1.7.62. The repair request changes from selecting
`CouponPolicy.<init>` to `CouponPolicy.discount` and its actual source. The payment
path search changes from zero results to eight functions in the two relevant files.
The original behavior-only bundle remains unresolved and now reports ambiguity;
there is no semantic natural-language search claim. See retrieval-reproduction.json.
Returning useful source/candidates increased raw HTTP payload bytes in this replay.

A replay setup error placed fixtures under an ignored ancestor directory, producing
an empty index despite a valid fixture manifest. Those three observations remain
excluded. Corrected replays independently assert admitted source counts. The new
runner applies the same count check before a model invocation and uses /tmp fixtures.

The full JVM run passed 96/97 tests; the new file-reference test wrongly expected a
type rather than the actual Java file node produced by JavaStructure. An independent
JVM probe confirmed complete correct source retrieval. The expectation was corrected,
without product changes; all seven focused retrieval tests then passed. The previous
focused Analyzer/StructuralAdapter/LiveBridge suite also passed.

## Iteration 3 — frozen fresh confirmation

The final full JVM rerun passed all 97 tests; the separate benchmark suite passed
23. Product code remained frozen throughout 24 randomized scored invocations on
three fresh synthetic tasks, plus four unscored calibrations. All 28 passed their
external correctness and independent access checks. There were no scored exclusions
or retries. See [full findings](../reviews/token-fix/FINDINGS.md).

Both repaired configurations used retrieval in 6/6 trials. Relative to native,
repaired full tools used 46.1% more input and 38.2% more output; navigation used
18.2% more input and 16.9% more output. The preserved old build used 7.8% more input
and 20.5% more output on these same tasks. Do not compare these percentages with
the earlier pilot as if the task populations were identical.

All four polyglot bundles returned ambiguous candidates without source. All four
Java navigation bundles returned the named method but were followed by more source
inspection. Repair bundles selected the target correctly; shell editing/testing
after them is expected. This supports insufficient replacement of native inspection
as a remaining mechanism, without assigning each token to a feature. JShell socket
denials and non-Git inspection errors were retained in usage; zero stderr approval
rejections does not mean zero tool failures.

The registered efficiency threshold failed. Keep the verified resolution fixes and
optional profile, but make no token-saving claim or mandatory bundle-first
recommendation. The independent auditor reproduced the totals, all complete scored
evaluator outcomes and artifact bindings. Fresh outcomes were not used to retune
the product during this cycle. The experiment is development confirmation, not
production-repository generalization.
