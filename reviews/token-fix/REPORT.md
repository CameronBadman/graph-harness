# Fresh retrieval-repair confirmation

24 / 24 scored attempts retained; 24 valid after raw-evidence and access checks. Every correctness result was recomputed by the external evaluator.

Configured model: `gpt-5.6-terra`; client: `codex-cli 0.154.0`. Input includes cached input; output includes reasoning. Counters are not independently reconciled billing.

| Configuration | Correct / valid / attempted | Read retrieval attempted / runs | Stderr approval rejections | Known input | Known output |
| --- | ---: | ---: | ---: | ---: | ---: |
| native | 6 / 6 / 6 | 0 / 6 | 0 | 378,053 | 5,235 |
| legacy | 6 / 6 / 6 | 0 / 6 | 0 | 407,515 | 6,310 |
| repaired | 6 / 6 / 6 | 6 / 6 | 0 | 552,264 | 7,233 |
| navigation | 6 / 6 / 6 | 6 / 6 | 0 | 446,923 | 6,120 |

Known totals include invalid and incorrect attempts with available counters. They are not the denominator for savings. Missing counters remain unknown. Adoption counts attempts at graph/source retrieval, including failed or unhelpful responses; it does not measure retrieval usefulness. Capability, edit and coordination calls do not count.

## Matched percentage decreases

All comparisons below use the same 6 task/repetition cells, requiring all four configurations to be valid and correct. Positive values mean fewer tokens; **negative values mean more tokens**. Formula: `100 × (baseline − treatment) / baseline`.

| Treatment versus baseline | Cells | Input decrease | Output decrease | Uncached-input decrease |
| --- | ---: | ---: | ---: | ---: |
| legacy vs native | 6 | -7.8% | -20.5% | +7.1% |
| repaired vs native | 6 | -46.1% | -38.2% | -29.5% |
| navigation vs native | 6 | -18.2% | -16.9% | -9.9% |
| native vs legacy | 6 | +7.2% | +17.0% | -7.7% |
| repaired vs legacy | 6 | -35.5% | -14.6% | -39.4% |
| navigation vs legacy | 6 | -9.7% | +3.0% | -18.4% |

## Every attempt

| Task | Rep | Arm | Correct | Valid | Input | Cached | Output | Retrieval | Access | Stderr approval rejections |
| --- | ---: | --- | --- | --- | ---: | ---: | ---: | --- | --- | ---: |
| polyglot_retry | 2 | legacy | True | True | 41726 | 26112 | 408 | False | passed | 0 |
| polyglot_retry | 2 | native | True | True | 39328 | 24064 | 408 | False | passed | 0 |
| polyglot_retry | 2 | repaired | True | True | 73257 | 65280 | 739 | True | passed | 0 |
| polyglot_retry | 2 | navigation | True | True | 42479 | 26112 | 420 | True | passed | 0 |
| java_dispatch | 1 | repaired | True | True | 95313 | 61440 | 768 | True | passed | 0 |
| java_dispatch | 1 | legacy | True | True | 57338 | 25088 | 788 | False | passed | 0 |
| java_dispatch | 1 | native | True | True | 45643 | 24064 | 433 | False | passed | 0 |
| java_dispatch | 1 | navigation | True | True | 82341 | 61440 | 678 | True | passed | 0 |
| polyglot_retry | 1 | legacy | True | True | 41920 | 26112 | 511 | False | passed | 0 |
| polyglot_retry | 1 | navigation | True | True | 42420 | 36096 | 399 | True | passed | 0 |
| polyglot_retry | 1 | native | True | True | 39842 | 25088 | 379 | False | passed | 0 |
| polyglot_retry | 1 | repaired | True | True | 42627 | 26112 | 418 | True | passed | 0 |
| java_dispatch | 2 | navigation | True | True | 65898 | 31232 | 561 | True | passed | 0 |
| java_dispatch | 2 | legacy | True | True | 57479 | 50176 | 749 | False | passed | 0 |
| java_dispatch | 2 | native | True | True | 69391 | 51200 | 737 | False | passed | 0 |
| java_dispatch | 2 | repaired | True | True | 69074 | 47360 | 589 | True | passed | 0 |
| java_backoff | 2 | repaired | True | True | 119745 | 96768 | 2416 | True | passed | 0 |
| java_backoff | 2 | legacy | True | True | 95402 | 75520 | 1747 | False | passed | 0 |
| java_backoff | 2 | native | True | True | 91646 | 71424 | 1755 | False | passed | 0 |
| java_backoff | 2 | navigation | True | True | 94758 | 73472 | 2126 | True | passed | 0 |
| java_backoff | 1 | native | True | True | 92203 | 71424 | 1523 | False | passed | 0 |
| java_backoff | 1 | navigation | True | True | 119027 | 96768 | 1936 | True | passed | 0 |
| java_backoff | 1 | repaired | True | True | 152248 | 111872 | 2303 | True | passed | 0 |
| java_backoff | 1 | legacy | True | True | 113650 | 101632 | 2107 | False | passed | 0 |

## Every matched pair

| Task | Rep | Treatment versus baseline | Input decrease | Output decrease |
| --- | ---: | --- | ---: | ---: |
| java_backoff | 1 | legacy vs native | -23.3% | -38.3% |
| java_backoff | 2 | legacy vs native | -4.1% | +0.5% |
| java_dispatch | 1 | legacy vs native | -25.6% | -82.0% |
| java_dispatch | 2 | legacy vs native | +17.2% | -1.6% |
| polyglot_retry | 1 | legacy vs native | -5.2% | -34.8% |
| polyglot_retry | 2 | legacy vs native | -6.1% | +0.0% |
| java_backoff | 1 | repaired vs native | -65.1% | -51.2% |
| java_backoff | 2 | repaired vs native | -30.7% | -37.7% |
| java_dispatch | 1 | repaired vs native | -108.8% | -77.4% |
| java_dispatch | 2 | repaired vs native | +0.5% | +20.1% |
| polyglot_retry | 1 | repaired vs native | -7.0% | -10.3% |
| polyglot_retry | 2 | repaired vs native | -86.3% | -81.1% |
| java_backoff | 1 | navigation vs native | -29.1% | -27.1% |
| java_backoff | 2 | navigation vs native | -3.4% | -21.1% |
| java_dispatch | 1 | navigation vs native | -80.4% | -56.6% |
| java_dispatch | 2 | navigation vs native | +5.0% | +23.9% |
| polyglot_retry | 1 | navigation vs native | -6.5% | -5.3% |
| polyglot_retry | 2 | navigation vs native | -8.0% | -2.9% |
| java_backoff | 1 | native vs legacy | +18.9% | +27.7% |
| java_backoff | 2 | native vs legacy | +3.9% | -0.5% |
| java_dispatch | 1 | native vs legacy | +20.4% | +45.1% |
| java_dispatch | 2 | native vs legacy | -20.7% | +1.6% |
| polyglot_retry | 1 | native vs legacy | +5.0% | +25.8% |
| polyglot_retry | 2 | native vs legacy | +5.7% | +0.0% |
| java_backoff | 1 | repaired vs legacy | -34.0% | -9.3% |
| java_backoff | 2 | repaired vs legacy | -25.5% | -38.3% |
| java_dispatch | 1 | repaired vs legacy | -66.2% | +2.5% |
| java_dispatch | 2 | repaired vs legacy | -20.2% | +21.4% |
| polyglot_retry | 1 | repaired vs legacy | -1.7% | +18.2% |
| polyglot_retry | 2 | repaired vs legacy | -75.6% | -81.1% |
| java_backoff | 1 | navigation vs legacy | -4.7% | +8.1% |
| java_backoff | 2 | navigation vs legacy | +0.7% | -21.7% |
| java_dispatch | 1 | navigation vs legacy | -43.6% | +14.0% |
| java_dispatch | 2 | navigation vs legacy | -14.6% | +25.1% |
| polyglot_retry | 1 | navigation vs legacy | -1.2% | +21.9% |
| polyglot_retry | 2 | navigation vs legacy | -1.8% | -2.9% |

## Registered continuation threshold

At least 20% lower input than native, no output increase, all tasks correct, and retrieval in at least half the treatment attempts. Incomplete common measurement coverage cannot pass.

- legacy: not passed (input_decrease_at_least_20_percent, no_output_increase, retrieval_in_at_least_half_attempts).
- repaired: not passed (input_decrease_at_least_20_percent, no_output_increase).
- navigation: not passed (input_decrease_at_least_20_percent, no_output_increase).

## Limits and evidence

Three generated tasks, two repetitions and two source corpora constitute a development confirmation, not a production benchmark or statistical generalization. Provider cache state is uncontrolled. Repaired and navigation receive explicit integration guidance, including its input overhead; native and legacy do not. Repaired versus legacy changes resolution and integration together. Navigation changes tool availability and response format together. Low tool adoption limits attribution. Stderr approval rejections count only the observed Rejected( marker; sandbox or runtime failures inside command output remain separate tool errors. Zero in that column does not imply no tool failures.

Private raw transcripts, stderr, prompts, answers and final workspaces are bound by digests in runs.json. The collector verifies per-run records, rederives usage and diagnostics, checks the frozen artifact and fixture identities, and reruns the external correctness evaluator. Access review is a separate human/agent judgment and is not proved by a hash. Calibration is not included in these scored totals. Backend and adapter metadata in runs.json matches captured capabilities; external system runtime binaries are not fully pinned. Admission checks indexed file counts, not a per-file graph identity proof. Failed-compilation diagnostic hashes may change with private evaluator temporary paths; both hashes remain recorded and all semantic correctness fields must still agree. bindings.json omits local launcher/workspace paths; raw prompts, answers and access-review prose are not copied into this report.
