# Independent review of the retrieval-repair confirmation

Reviewed on 2026-09-18 by a delegated audit agent that did not implement the product
repairs or author the fresh task fixtures. Product changes were frozen before
scored execution; no tuning suggestions based on fresh outcomes were sent during
that execution.

## Scope and verified evidence

All **24 scored invocations and four separate calibrations** received independent
access review. All 28 receipts pass and bind both transcript and stderr hashes.
No access exclusion, observed evaluator leakage, cross-session access, delegation,
or unexplained source modification was found in the observable records.

For every scored invocation, the reviewer:

- Inspected every observable shell command, MCP request/result, file change and
  stderr record, plus final source changes and permitted `.scratch` probes.
- Recomputed raw transcript, stderr, prompt, schema, answer, frozen-manifest,
  evaluator, actual MCP contract, native-executable and workspace bindings.
- Recreated the original fixture and checked its initial manifest; checked the
  recorded final manifest and the exact prompt for its treatment.
- Re-executed the frozen external evaluator and reproduced its **complete outcome
  object**, including repair diff/output hashes. All 24 remained correct. Each
  repair passed the external Java probe's 7,957 behavior assertions.
- Checked the single terminal usage record against the reported input, cached
  input, output and reasoning-output counters. No private reasoning was inspected.

The calibration review separately confirmed native source reads, legacy MCP
search/source use, and one successful context bundle each for repaired and
navigation. The actual navigation bridge exposed its registered four tools.
Only the repaired bridges supplied initialization guidance. Calibration usage is
excluded from scored totals.

A separate final pass recomputed all 447 registered artifact hashes directly from
their files; all matched the frozen manifest.

Access review is a judgment about observable actions, not a filesystem sandbox
proof. JSONL omits code-mode wrapper scripts, so this review does **not** claim to
observe every internal step or establish result-projection compliance.

## Independently recomputed outcome

The table below was calculated directly from terminal usage records after all 24
runs completed. Each configuration covers the same six task/repetition cells; all
are valid and correct. Positive changes here mean **increases** relative to native.

| Configuration | Correct / valid runs | Input tokens | Output tokens | Input change vs native | Output change vs native | Retrieval runs |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Native | 6 / 6 | 378,053 | 5,235 | — | — | 0 / 6 |
| Legacy | 6 / 6 | 407,515 | 6,310 | +7.79% | +20.53% | 0 / 6 |
| Repaired | 6 / 6 | 552,264 | 7,233 | +46.08% | +38.17% | 6 / 6 |
| Navigation | 6 / 6 | 446,923 | 6,120 | +18.22% | +16.91% | 6 / 6 |

Input already includes cached input; output already includes reasoning. None of
the three harness configurations passes the registered continuation threshold.
These observed totals do not establish general causal effect sizes.

The repaired configurations resolved the explicit Java target in all eight Java
bundle calls. Their four behavioral polyglot bundles returned ambiguity with no
source. Every one of the 12 scored MCP-using runs subsequently used shell commands.
Thus reliable tool adoption improved, but successful target selection did not
consistently eliminate native reads or additional model rounds.

## Runtime errors retained in the comparison

| Configuration | Completed shell commands | Nonzero shell exits | Commands reporting JShell socket denial | Commands reporting non-Git errors | Stderr approval rejections |
| --- | ---: | ---: | ---: | ---: | ---: |
| Native | 16 | 4 | 2 | 2 | 0 |
| Legacy | 17 | 3 | 1 | 2 | 0 |
| Repaired | 17 | 5 | 1 | 2 | 0 |
| Navigation | 15 | 2 | 0 | 2 | 0 |

These categories can overlap. The repaired arm also had one command attempting to
read missing source filenames. Some nonzero exits are searches with no matches.
There were **zero MCP call errors** among the 12 scored MCP calls. Empty or
insufficient retrieval remains unhelpful even when its transport succeeds.

The stderr count specifically detects `Rejected(` approval events. It does not
mean sandbox restrictions caused no problems: four commands reported JShell's
local execution socket being denied. Those runs later used ordinary Java probes.
The non-Git fixture workspaces also caused Git inspection failures. Such trajectory
variation remains in the totals and cannot all be attributed to retrieval.

## Client-path diagnostic and limits

The separate [installed-client probe](../../debug/client-probe.json) used fixed
responses from a local mock provider, with zero real model invocations. It verified
that the tested CLI initially omitted GraphHarness tools and initialization
instructions from the visible context, while `ALL_TOOLS` discovery exposed them.
Printing an entire MCP result placed both representations in the next request;
printing `structuredContent` placed only that representation in the projected
call's output. Earlier unprojected output remained in conversation history.

That proves the observed client conversion path, not that every real agent followed
the projection snippet. The mock provider returned 404 for model-list retrieval;
fallback metadata and the custom provider are explicit diagnostic limitations.
Its byte counts and mocked zero usage are not token-efficiency measurements.

The scored pilot covers three generated tasks, two repetitions and two source
corpora. These are fresh development-confirmation fixtures, not a production
benchmark. Treatment guidance differs intentionally, and the navigation profile
changes schemas and responses together. Provider cache state is uncontrolled;
configured model identity is not independently provider-attested; usage is not
reconciled to a bill. Known host instruction/rule hashes and the installed Codex
binary are frozen, but complete effective host context and all runtime libraries
are not. Source admission verifies file counts, not each graph file's identity.

The reviewer found no blocking access, arithmetic or evidence-binding issue in the
completed pilot. The negative efficiency result must remain visible alongside the
verified routing and retrieval corrections.

After report generation, the reviewer cross-checked the published aggregate against
an independent raw-counter calculation: totals, six common cells, percentages,
adoption and threshold decisions all matched. All 12 published bundle mechanism
records were also reproduced from the raw observable calls. The rendered chart
was inspected and its values and increase/decrease labels match those totals.
