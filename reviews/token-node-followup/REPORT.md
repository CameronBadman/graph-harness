# Repaired node editing: guided development rerun

30 / 30 scored attempts retained; 30 valid after raw-evidence and access checks. Every correctness result was recomputed by the external evaluator. Target families require separate real node-commit preflight. Tasks are reused development data; explicit editing guidance and a common validation instruction changed before this freeze.

Configured model: `gpt-5.6-terra`; client: `codex-cli 0.154.0`. Input includes cached input; output includes reasoning. Counters are not independently reconciled billing.

| Configuration | Correct / valid / attempted | Read / node commit / new format runs | Stderr approval rejections | Known input | Known output |
| --- | ---: | ---: | ---: | ---: | ---: |
| native | 6 / 6 / 6 | 0 / 0 / 0 | 0 | 579,494 | 9,165 |
| compact | 6 / 6 / 6 | 6 / 0 / 0 | 0 | 845,138 | 10,740 |
| slim | 6 / 6 / 6 | 6 / 0 / 6 | 0 | 1,019,774 | 12,587 |
| node | 6 / 6 / 6 | 6 / 6 / 0 | 0 | 912,212 | 13,290 |
| combined | 6 / 6 / 6 | 6 / 6 / 6 | 0 | 884,722 | 12,287 |

Known totals include invalid and incorrect attempts with available counters. They are not the denominator for savings. Missing counters remain unknown. Adoption counts attempts at graph/source retrieval, including failed or unhelpful responses; it does not measure retrieval usefulness. Capability, edit and coordination calls do not count.

## Matched percentage decreases

All comparisons below use the same 6 task/repetition cells, requiring all five configurations to be valid and correct. Positive values mean fewer tokens; **negative values mean more tokens**. Formula: `100 × (baseline − treatment) / baseline`.

| Treatment versus baseline | Cells | Input decrease | Output decrease | Uncached-input decrease |
| --- | ---: | ---: | ---: | ---: |
| compact vs native | 6 | -45.8% | -17.2% | +14.9% |
| slim vs native | 6 | -76.0% | -37.3% | -12.2% |
| node vs native | 6 | -57.4% | -45.0% | -30.2% |
| combined vs native | 6 | -52.7% | -34.1% | +25.3% |
| slim vs compact | 6 | -20.7% | -17.2% | -31.9% |
| combined vs node | 6 | +3.0% | +7.5% | +42.7% |
| node vs compact | 6 | -7.9% | -23.7% | -53.1% |
| combined vs slim | 6 | +13.2% | +2.4% | +33.5% |

## Every attempt

| Task | Rep | Arm | Correct | Valid | Input | Cached | Output | Retrieval | Access | Stderr approval rejections |
| --- | ---: | --- | --- | --- | ---: | ---: | ---: | --- | --- | ---: |
| java_short | 1 | slim | True | True | 114715 | 91648 | 1930 | True | passed | 0 |
| java_short | 1 | node | True | True | 79523 | 68352 | 1276 | True | passed | 0 |
| java_short | 1 | combined | True | True | 113791 | 100608 | 1912 | True | passed | 0 |
| java_short | 1 | native | True | True | 73798 | 55296 | 1181 | False | passed | 0 |
| java_short | 1 | compact | True | True | 130871 | 98560 | 1738 | True | passed | 0 |
| java_long | 2 | combined | True | True | 182911 | 157440 | 2154 | True | passed | 0 |
| java_long | 2 | native | True | True | 141328 | 102656 | 1954 | False | passed | 0 |
| java_long | 2 | compact | True | True | 144784 | 129024 | 1466 | True | passed | 0 |
| java_long | 2 | slim | True | True | 202083 | 174592 | 2151 | True | passed | 0 |
| java_long | 2 | node | True | True | 142921 | 110848 | 2663 | True | passed | 0 |
| java_overload | 2 | node | True | True | 233894 | 191744 | 2781 | True | passed | 0 |
| java_overload | 2 | compact | True | True | 163063 | 147200 | 2404 | True | passed | 0 |
| java_overload | 2 | combined | True | True | 165649 | 148224 | 2063 | True | passed | 0 |
| java_overload | 2 | slim | True | True | 225684 | 189440 | 2860 | True | passed | 0 |
| java_overload | 2 | native | True | True | 74658 | 54272 | 1664 | False | passed | 0 |
| java_short | 2 | combined | True | True | 96098 | 83456 | 1702 | True | passed | 0 |
| java_short | 2 | compact | True | True | 97601 | 80384 | 1330 | True | passed | 0 |
| java_short | 2 | slim | True | True | 116389 | 93696 | 1390 | True | passed | 0 |
| java_short | 2 | node | True | True | 94926 | 58368 | 1572 | True | passed | 0 |
| java_short | 2 | native | True | True | 58085 | 49152 | 1110 | False | passed | 0 |
| java_overload | 1 | slim | True | True | 179454 | 152320 | 2144 | True | passed | 0 |
| java_overload | 1 | combined | True | True | 200984 | 182528 | 2367 | True | passed | 0 |
| java_overload | 1 | compact | True | True | 142551 | 125952 | 2173 | True | passed | 0 |
| java_overload | 1 | node | True | True | 253157 | 222976 | 2673 | True | passed | 0 |
| java_overload | 1 | native | True | True | 106347 | 85504 | 1557 | False | passed | 0 |
| java_long | 1 | compact | True | True | 166268 | 148224 | 1629 | True | passed | 0 |
| java_long | 1 | slim | True | True | 181449 | 165376 | 2112 | True | passed | 0 |
| java_long | 1 | native | True | True | 125278 | 96512 | 1699 | False | passed | 0 |
| java_long | 1 | node | True | True | 107791 | 82688 | 2325 | True | passed | 0 |
| java_long | 1 | combined | True | True | 125289 | 110848 | 2089 | True | passed | 0 |

## Every matched pair

| Task | Rep | Treatment versus baseline | Input decrease | Output decrease |
| --- | ---: | --- | ---: | ---: |
| java_long | 1 | compact vs native | -32.7% | +4.1% |
| java_long | 2 | compact vs native | -2.4% | +25.0% |
| java_overload | 1 | compact vs native | -34.0% | -39.6% |
| java_overload | 2 | compact vs native | -118.4% | -44.5% |
| java_short | 1 | compact vs native | -77.3% | -47.2% |
| java_short | 2 | compact vs native | -68.0% | -19.8% |
| java_long | 1 | slim vs native | -44.8% | -24.3% |
| java_long | 2 | slim vs native | -43.0% | -10.1% |
| java_overload | 1 | slim vs native | -68.7% | -37.7% |
| java_overload | 2 | slim vs native | -202.3% | -71.9% |
| java_short | 1 | slim vs native | -55.4% | -63.4% |
| java_short | 2 | slim vs native | -100.4% | -25.2% |
| java_long | 1 | node vs native | +14.0% | -36.8% |
| java_long | 2 | node vs native | -1.1% | -36.3% |
| java_overload | 1 | node vs native | -138.0% | -71.7% |
| java_overload | 2 | node vs native | -213.3% | -67.1% |
| java_short | 1 | node vs native | -7.8% | -8.0% |
| java_short | 2 | node vs native | -63.4% | -41.6% |
| java_long | 1 | combined vs native | -0.0% | -23.0% |
| java_long | 2 | combined vs native | -29.4% | -10.2% |
| java_overload | 1 | combined vs native | -89.0% | -52.0% |
| java_overload | 2 | combined vs native | -121.9% | -24.0% |
| java_short | 1 | combined vs native | -54.2% | -61.9% |
| java_short | 2 | combined vs native | -65.4% | -53.3% |
| java_long | 1 | slim vs compact | -9.1% | -29.7% |
| java_long | 2 | slim vs compact | -39.6% | -46.7% |
| java_overload | 1 | slim vs compact | -25.9% | +1.3% |
| java_overload | 2 | slim vs compact | -38.4% | -19.0% |
| java_short | 1 | slim vs compact | +12.3% | -11.0% |
| java_short | 2 | slim vs compact | -19.2% | -4.5% |
| java_long | 1 | combined vs node | -16.2% | +10.2% |
| java_long | 2 | combined vs node | -28.0% | +19.1% |
| java_overload | 1 | combined vs node | +20.6% | +11.4% |
| java_overload | 2 | combined vs node | +29.2% | +25.8% |
| java_short | 1 | combined vs node | -43.1% | -49.8% |
| java_short | 2 | combined vs node | -1.2% | -8.3% |
| java_long | 1 | node vs compact | +35.2% | -42.7% |
| java_long | 2 | node vs compact | +1.3% | -81.7% |
| java_overload | 1 | node vs compact | -77.6% | -23.0% |
| java_overload | 2 | node vs compact | -43.4% | -15.7% |
| java_short | 1 | node vs compact | +39.2% | +26.6% |
| java_short | 2 | node vs compact | +2.7% | -18.2% |
| java_long | 1 | combined vs slim | +31.0% | +1.1% |
| java_long | 2 | combined vs slim | +9.5% | -0.1% |
| java_overload | 1 | combined vs slim | -12.0% | -10.4% |
| java_overload | 2 | combined vs slim | +26.6% | +27.9% |
| java_short | 1 | combined vs slim | +0.8% | +0.9% |
| java_short | 2 | combined vs slim | +17.4% | -22.4% |

## Registered continuation threshold

At least 20% lower input than native, no output increase, all tasks correct, and retrieval in at least half the treatment attempts; node/format arms also require their intended mechanism in at least half. Incomplete common measurement coverage cannot pass.

- compact: not passed (input_decrease_at_least_20_percent, no_output_increase).
- slim: not passed (input_decrease_at_least_20_percent, no_output_increase).
- node: not passed (input_decrease_at_least_20_percent, no_output_increase).
- combined: not passed (input_decrease_at_least_20_percent, no_output_increase).

## Limits and evidence

Three reused generated tasks, two repetitions and one shared source corpus constitute a guided development rerun, not a production benchmark or statistical generalization. Provider cache state is uncontrolled. All graph arms receive identical explicit integration guidance, including its input overhead; native does not. Retrieval selection stays fixed. Format and guided edit availability vary factorially. Known task results informed repairs; this is not a fresh holdout. A common compilation/behavior-check requirement applies to every arm. Low adoption limits attribution. Stderr approval rejections count only the observed Rejected( marker; sandbox or runtime failures inside command output remain separate tool errors. Zero in that column does not imply no tool failures.

Private raw transcripts, stderr, prompts, answers and final workspaces are bound by digests in runs.json. The collector verifies per-run records, rederives usage and diagnostics, checks the frozen artifact and fixture identities, and reruns the external correctness evaluator. Access review is a separate human/agent judgment and is not proved by a hash. Calibration is not included in these scored totals. Backend and adapter metadata in runs.json matches captured capabilities; external system runtime binaries are not fully pinned. Admission checks indexed file counts, not a per-file graph identity proof. Failed-compilation diagnostic hashes may change with private evaluator temporary paths; both hashes remain recorded and all semantic correctness fields must still agree. bindings.json omits local launcher/workspace paths; raw prompts, answers and access-review prose are not copied into this report.
