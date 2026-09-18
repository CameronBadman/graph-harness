# Independent implementation and trial audit

Reviewed 18 September 2026 by the delegated audit agent. This reviewer did not author the product changes, fresh task fixtures, runner, or collector, and did not launch any model trial. Fresh task code was inspected only after product freeze `70aa722`. Evaluation freeze `7abd0f6` has SHA-256 `a98597f5e034630a9401eb70a275a640377d97d52a2ccb9c113406669a7d8ade`.

The retained evidence supports a negative token-efficiency result for these configurations on this pilot. All 30 scored attempts passed the external behavioral and scope checks and the independent observable-access review. No configuration met the registered continuation threshold. No scored attempt called the node edit tool, so these results do not measure savings from actually editing through nodes.

## Results independently checked

The following figures were recalculated directly from each raw terminal `turn.completed` usage event, without using the collector's percentage helper. Input includes cached input; output includes reasoning. Percentages below are **increases versus native**, for readability.

| Configuration | Input tokens | Output tokens | Input increase | Output increase |
| --- | ---: | ---: | ---: | ---: |
| Native | 542,445 | 6,721 | — | — |
| Current compact | 843,191 | 11,003 | 55.44% | 63.71% |
| Source format | 905,018 | 11,936 | 66.84% | 77.59% |
| Node edits enabled | 792,097 | 10,532 | 46.02% | 56.70% |
| Source format and node edits enabled | 858,988 | 10,640 | 58.35% | 58.31% |

All comparisons use the same six task/repetition cells across all five arms. None was excluded. I checked the five arm totals, eight aggregate contrasts, all 48 matched pairs across six metrics, and all 30 public run counters against raw evidence. Cached input and reasoning output were not added twice. The public chart's values and percentage direction match the report.

The source-format contrast used 7.33% more input and 8.48% more output than compact. Adding node-edit availability corresponds to 6.06% less input versus compact and 5.09% less versus source format, but there were **zero node-edit calls or commits in all 12 enabled attempts**. Those differences cannot establish a saving from the edit mechanism. The experiment varies availability, not compulsory usage. All 24 graph attempts made a retrieval call; all 12 source-format attempts received that format.

## Observable workflow and capability findings

Eight short-task bundle calls returned source, including the target. Eight long-task bundle calls returned target source with an explicit budget-truncation note; agents also inspected helper code with native tools. Across eight overload-task runs, 11 bundle calls returned ambiguous candidates and no source. Some requests put a node ID into task text rather than the explicit `node_id` argument and remained ambiguous. Every scored repair ultimately used native editing. No scored node-edit rejection occurred because no node edit was attempted.

I checked all 24 returned source slices against original/final file hashes and the supplied source location: 16 had byte spans, and eight long-method slices had line ranges only. Every source slice matched its bytes and available location. These checks establish source consistency for the observed slices, not complete graph semantics.

A separately authorized **post-score, non-model, plan-only diagnostic** found an additional support limitation. On fresh fixtures with the frozen product and Joern backend, the short primitive-parameter target produced a plan. The long target and custom-type overload both returned HTTP 422 `target_not_found`, even with exact current node ID, snapshot, hash, and their unchanged original bodies below the argument limit. No apply operation ran; all three fixture manifests stayed unchanged. See [edit-eligibility.json](edit-eligibility.json).

The matching code compares AST parameter spellings with backend parameter types after removing whitespace. Here Joern supplies package-qualified types while the source uses simple names. Both structural span enrichment and the shared edit preparation path use that comparison. This code predates the new one-call wrapper (`SafeJavaEdit.kt` last changed in `4f50318`; `JavaStructure.kt` in `8547b2b`). The one-call operation delegates to the same preparation path. This is a material inherited eligibility limitation, not evidence that the new transaction wrapper caused the failure. It also does not establish why any model chose native editing. The no-argument calibration demonstrated tool connectivity and commits but did not establish eligibility for these custom-type targets. No scored denominator or acceptance gate was changed after this finding.

## Verification and integrity

- Reviewed the product diff and relevant parser/edit/bridge paths. Two initial review findings—unintended full-bridge exposure and retained rejected plans—were corrected before freeze with regression coverage. No further new blocking correctness or security regression was demonstrated in the reviewed changes. This is a bounded review, not a complete security audit.
- Verified all 147 installed-product file hashes against the frozen distribution and independently inspected retained JUnit XML: 122 tests, zero failures, errors, or skips. Independently reran the 19 collector tests and four calibration regression tests successfully. The integrating agent separately ran the complete 36-test benchmark suite.
- Reviewed fresh fixtures and evaluator implementation: three tasks on one shared 24-file Java corpus, with separately expressed behavior references and parser-confirmed unchanged scope. The external checks cover 22,194 short-task assertions, 4,173 long-task assertions, and 3,178 overload-task assertions per successful evaluation. Reexecuted grading for every scored workspace and reproduced its complete correctness result.
- Inspected each scored trial's observable commands, MCP calls/results, file-change events, final source diff, scratch probes, and stderr. Checked raw/prompt/answer hashes, exact frozen prompt/schema/configuration, before/after manifests, bridge exposure, daemon capabilities, and terminal counters. All 30 access receipts bind the inspected transcript and stderr hashes. No observed access to hidden evaluator data, out-of-scope source changes, delegation, or evaluator exploitation was found.
- Command failures were retained, including nonexistent Git/test paths, runtime/socket failures, and incorrect agent-authored probe expectations. These are separate from access violations or final correctness. The collector's command-error count is not a complete count of failures inside compound shell commands; a later success can mask an earlier nonzero exit. Zero stderr approval-rejection markers does not mean zero operational errors.
- Verified the nine retained calibration records and public [calibration-history.json](calibration-history.json). The initial compact calibration's scratch-location violation remains rejected; the initial node calibration remains recorded incorrect even though independent execution established a valid equivalent nested-block repair. The calibration checker and uniform scratch guidance changed before any scored attempt, all five calibrations reran under a new freeze, and the original failed attempt was preserved.
- Recompiled and executed the frozen-format development probe against six retained earlier responses. All six JSON round trips were lossless. Serialized UTF-8 bytes fell from 21,862 to 20,016 overall, an 8.44% reduction; both empty-source responses grew 5.27%. These reproduce [format-development.json](format-development.json) and are **byte measurements, not token savings**.

## Limits and decision

The sample is three generated repair tasks with two repetitions on one small corpus. It is not six independent repositories or a representative production benchmark. All graph arms share integration guidance that native lacks, so native comparisons include this overhead. Backend versions and known host configuration were captured, but provider cache state, full effective host context, and external runtime binaries were not completely isolated. Model identity is the configured `gpt-5.6-terra`, not provider attestation. CLI usage was not reconciled with billing.

JSONL exposes tool actions and outputs but does not expose code-mode wrapper scripts or private reasoning. Access review therefore does not prove compliance with unobservable discovery/projection details and cannot infer model intent. The external Java evaluator is not an OS sandbox; inspection of actual submitted code and probes found no exploitation, but scope checks alone would not provide that assurance.

The evidence is sufficient to retain and report this negative pilot honestly. It does not support a token-saving claim, switching the default format, or claiming that node edits were successfully evaluated across all task families. Preserve the current results. Any future optimization needs a new freeze and independent fresh measurements, with supported edit strata verified before scoring and native fallbacks reported explicitly.
