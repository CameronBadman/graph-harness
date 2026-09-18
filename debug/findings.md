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

See hypotheses.md. No proposed optimization has yet been evaluated in this cycle.
