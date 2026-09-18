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
