# Token-efficiency investigation — 2026-09-18

Decision: whether this integration can reduce total Codex input and output while
preserving task correctness. Earlier pilots remain unchanged development evidence.

- H1 (high): task resolution loses qualified identifiers and chooses an unrelated
  constructor or ignores non-Java function kinds. Test the old and repaired resolver
  on explicit qualified names, paths, overloaded/ambiguous targets, and polyglot
  declarations. A correct old result falsifies this explanation for that case.
- H2 (medium): advertised tools are insufficiently discoverable to the client.
  Audit actual tool-list/discovery behavior and compare explicit supported setup;
  distinguish unavailable tools from available tools the model did not select.
- H3 (medium): redundant metadata and extra retrieval round trips add avoidable
  context. Compare equivalent source/provenance payloads, then real Codex usage;
  serialized bytes alone cannot establish billed tokens or end-to-end savings.
- H4 (medium): small fixtures, stochastic trajectories, and provider caching
  explain much of the aggregate difference. Repeat identical workflows in randomized
  matched blocks and report all input (cached included), output, correctness and use.

No source/evaluator-specific answer shortcuts. Fresh evaluation tasks are separated
from development regressions. No claims of generality from synthetic pilot results.

Iteration 2: H1 reproduced for path/multi-language/ambiguity/unknown-target cases and
the constructor observation with the real Joern backend. H2/H3 have positive
installed-client mock evidence for deferred discovery and duplicate representations,
but effects on real model trajectories remain to be measured. H4 remains plausible;
historical stderr confirms policy recovery as an additional confound.

Final outcome: H1 is confirmed by old/fixed Joern replays and regression tests.
H2 has direct installed-client evidence; explicit discovery plus repaired tools
raised observed adoption from 0/6 to 6/6 in each repaired arm. This bundled change
does not isolate the causal contribution of each instruction. H3 is confirmed for
duplicate presentation in the mock-client route, but the real trial JSONL cannot
verify projection compliance. Compact navigation used less than repaired full
tools but still 18.2% more input and 16.9% more output than native. The hypothesis
that these fixes suffice for token savings failed this test. H4 and runtime-error
variation remain unresolved contributors. Every attempt and negative result is
retained; no product tuning followed inspection of the fresh outcomes.
