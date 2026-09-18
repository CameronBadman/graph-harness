# Slim interface and node-edit factorial pilot

30 / 30 scored attempts retained; 30 valid after raw-evidence and access checks. Every correctness result was recomputed by the external evaluator.

Configured model: `gpt-5.6-terra`; client: `codex-cli 0.154.0`. Input includes cached input; output includes reasoning. Counters are not independently reconciled billing.

| Configuration | Correct / valid / attempted | Read / node commit / new format runs | Stderr approval rejections | Known input | Known output |
| --- | ---: | ---: | ---: | ---: | ---: |
| native | 6 / 6 / 6 | 0 / 0 / 0 | 0 | 542,445 | 6,721 |
| compact | 6 / 6 / 6 | 6 / 0 / 0 | 0 | 843,191 | 11,003 |
| slim | 6 / 6 / 6 | 6 / 0 / 6 | 0 | 905,018 | 11,936 |
| node | 6 / 6 / 6 | 6 / 0 / 0 | 0 | 792,097 | 10,532 |
| combined | 6 / 6 / 6 | 6 / 0 / 6 | 0 | 858,988 | 10,640 |

Known totals include invalid and incorrect attempts with available counters. They are not the denominator for savings. Missing counters remain unknown. Adoption counts attempts at graph/source retrieval, including failed or unhelpful responses; it does not measure retrieval usefulness. Capability, edit and coordination calls do not count.

## Matched percentage decreases

All comparisons below use the same 6 task/repetition cells, requiring all five configurations to be valid and correct. Positive values mean fewer tokens; **negative values mean more tokens**. Formula: `100 × (baseline − treatment) / baseline`.

| Treatment versus baseline | Cells | Input decrease | Output decrease | Uncached-input decrease |
| --- | ---: | ---: | ---: | ---: |
| compact vs native | 6 | -55.4% | -63.7% | -16.9% |
| slim vs native | 6 | -66.8% | -77.6% | -72.0% |
| node vs native | 6 | -46.0% | -56.7% | -29.4% |
| combined vs native | 6 | -58.4% | -58.3% | -36.3% |
| slim vs compact | 6 | -7.3% | -8.5% | -47.1% |
| combined vs node | 6 | -8.4% | -1.0% | -5.3% |
| node vs compact | 6 | +6.1% | +4.3% | -10.7% |
| combined vs slim | 6 | +5.1% | +10.9% | +20.7% |

## Every attempt

| Task | Rep | Arm | Correct | Valid | Input | Cached | Output | Retrieval | Access | Stderr approval rejections |
| --- | ---: | --- | --- | --- | ---: | ---: | ---: | --- | --- | ---: |
| java_overload | 1 | node | True | True | 96770 | 73472 | 2114 | True | passed | 0 |
| java_overload | 1 | native | True | True | 107284 | 85504 | 1668 | False | passed | 0 |
| java_overload | 1 | compact | True | True | 170302 | 137984 | 2350 | True | passed | 0 |
| java_overload | 1 | combined | True | True | 158107 | 132096 | 2079 | True | passed | 0 |
| java_overload | 1 | slim | True | True | 200051 | 156416 | 2461 | True | passed | 0 |
| java_short | 1 | slim | True | True | 110002 | 88576 | 1452 | True | passed | 0 |
| java_short | 1 | node | True | True | 127108 | 103680 | 1529 | True | passed | 0 |
| java_short | 1 | compact | True | True | 96911 | 86528 | 1299 | True | passed | 0 |
| java_short | 1 | native | True | True | 91111 | 71424 | 1062 | False | passed | 0 |
| java_short | 1 | combined | True | True | 107104 | 71424 | 1558 | True | passed | 0 |
| java_short | 2 | compact | True | True | 110504 | 98560 | 1426 | True | passed | 0 |
| java_short | 2 | combined | True | True | 142941 | 128768 | 1776 | True | passed | 0 |
| java_short | 2 | slim | True | True | 139709 | 117760 | 1855 | True | passed | 0 |
| java_short | 2 | native | True | True | 89420 | 69376 | 1296 | False | passed | 0 |
| java_short | 2 | node | True | True | 92725 | 66304 | 1472 | True | passed | 0 |
| java_long | 1 | compact | True | True | 157364 | 132096 | 2278 | True | passed | 0 |
| java_long | 1 | slim | True | True | 131299 | 91648 | 1643 | True | passed | 0 |
| java_long | 1 | native | True | True | 74506 | 65280 | 644 | False | passed | 0 |
| java_long | 1 | combined | True | True | 159681 | 133120 | 1510 | True | passed | 0 |
| java_long | 1 | node | True | True | 177840 | 163328 | 1998 | True | passed | 0 |
| java_overload | 2 | slim | True | True | 173142 | 147968 | 2731 | True | passed | 0 |
| java_overload | 2 | node | True | True | 165144 | 143872 | 2002 | True | passed | 0 |
| java_overload | 2 | compact | True | True | 168055 | 152064 | 2277 | True | passed | 0 |
| java_overload | 2 | native | True | True | 71163 | 62208 | 936 | False | passed | 0 |
| java_overload | 2 | combined | True | True | 153964 | 131840 | 1958 | True | passed | 0 |
| java_long | 2 | native | True | True | 108961 | 87552 | 1115 | False | passed | 0 |
| java_long | 2 | node | True | True | 132510 | 110592 | 1417 | True | passed | 0 |
| java_long | 2 | compact | True | True | 140055 | 117760 | 1373 | True | passed | 0 |
| java_long | 2 | slim | True | True | 150815 | 128768 | 1794 | True | passed | 0 |
| java_long | 2 | combined | True | True | 137191 | 123904 | 1759 | True | passed | 0 |

## Every matched pair

| Task | Rep | Treatment versus baseline | Input decrease | Output decrease |
| --- | ---: | --- | ---: | ---: |
| java_long | 1 | compact vs native | -111.2% | -253.7% |
| java_long | 2 | compact vs native | -28.5% | -23.1% |
| java_overload | 1 | compact vs native | -58.7% | -40.9% |
| java_overload | 2 | compact vs native | -136.2% | -143.3% |
| java_short | 1 | compact vs native | -6.4% | -22.3% |
| java_short | 2 | compact vs native | -23.6% | -10.0% |
| java_long | 1 | slim vs native | -76.2% | -155.1% |
| java_long | 2 | slim vs native | -38.4% | -60.9% |
| java_overload | 1 | slim vs native | -86.5% | -47.5% |
| java_overload | 2 | slim vs native | -143.3% | -191.8% |
| java_short | 1 | slim vs native | -20.7% | -36.7% |
| java_short | 2 | slim vs native | -56.2% | -43.1% |
| java_long | 1 | node vs native | -138.7% | -210.2% |
| java_long | 2 | node vs native | -21.6% | -27.1% |
| java_overload | 1 | node vs native | +9.8% | -26.7% |
| java_overload | 2 | node vs native | -132.1% | -113.9% |
| java_short | 1 | node vs native | -39.5% | -44.0% |
| java_short | 2 | node vs native | -3.7% | -13.6% |
| java_long | 1 | combined vs native | -114.3% | -134.5% |
| java_long | 2 | combined vs native | -25.9% | -57.8% |
| java_overload | 1 | combined vs native | -47.4% | -24.6% |
| java_overload | 2 | combined vs native | -116.4% | -109.2% |
| java_short | 1 | combined vs native | -17.6% | -46.7% |
| java_short | 2 | combined vs native | -59.9% | -37.0% |
| java_long | 1 | slim vs compact | +16.6% | +27.9% |
| java_long | 2 | slim vs compact | -7.7% | -30.7% |
| java_overload | 1 | slim vs compact | -17.5% | -4.7% |
| java_overload | 2 | slim vs compact | -3.0% | -19.9% |
| java_short | 1 | slim vs compact | -13.5% | -11.8% |
| java_short | 2 | slim vs compact | -26.4% | -30.1% |
| java_long | 1 | combined vs node | +10.2% | +24.4% |
| java_long | 2 | combined vs node | -3.5% | -24.1% |
| java_overload | 1 | combined vs node | -63.4% | +1.7% |
| java_overload | 2 | combined vs node | +6.8% | +2.2% |
| java_short | 1 | combined vs node | +15.7% | -1.9% |
| java_short | 2 | combined vs node | -54.2% | -20.7% |
| java_long | 1 | node vs compact | -13.0% | +12.3% |
| java_long | 2 | node vs compact | +5.4% | -3.2% |
| java_overload | 1 | node vs compact | +43.2% | +10.0% |
| java_overload | 2 | node vs compact | +1.7% | +12.1% |
| java_short | 1 | node vs compact | -31.2% | -17.7% |
| java_short | 2 | node vs compact | +16.1% | -3.2% |
| java_long | 1 | combined vs slim | -21.6% | +8.1% |
| java_long | 2 | combined vs slim | +9.0% | +2.0% |
| java_overload | 1 | combined vs slim | +21.0% | +15.5% |
| java_overload | 2 | combined vs slim | +11.1% | +28.3% |
| java_short | 1 | combined vs slim | +2.6% | -7.3% |
| java_short | 2 | combined vs slim | -2.3% | +4.3% |

## Registered continuation threshold

At least 20% lower input than native, no output increase, all tasks correct, and retrieval in at least half the treatment attempts; node/format arms also require their intended mechanism in at least half. Incomplete common measurement coverage cannot pass.

- compact: not passed (input_decrease_at_least_20_percent, no_output_increase).
- slim: not passed (input_decrease_at_least_20_percent, no_output_increase).
- node: not passed (input_decrease_at_least_20_percent, no_output_increase, node_edit_in_at_least_half_attempts).
- combined: not passed (input_decrease_at_least_20_percent, no_output_increase, node_edit_in_at_least_half_attempts).

## Limits and evidence

Three generated tasks, two repetitions and one shared source corpus constitute a development confirmation, not a production benchmark or statistical generalization. Provider cache state is uncontrolled. All graph arms receive identical explicit integration guidance, including its input overhead; native does not. Retrieval selection stays fixed. Format and edit availability vary factorially. Low adoption limits attribution. Stderr approval rejections count only the observed Rejected( marker; sandbox or runtime failures inside command output remain separate tool errors. Zero in that column does not imply no tool failures.

Private raw transcripts, stderr, prompts, answers and final workspaces are bound by digests in runs.json. The collector verifies per-run records, rederives usage and diagnostics, checks the frozen artifact and fixture identities, and reruns the external correctness evaluator. Access review is a separate human/agent judgment and is not proved by a hash. Calibration is not included in these scored totals. Backend and adapter metadata in runs.json matches captured capabilities; external system runtime binaries are not fully pinned. Admission checks indexed file counts, not a per-file graph identity proof. Failed-compilation diagnostic hashes may change with private evaluator temporary paths; both hashes remain recorded and all semantic correctness fields must still agree. bindings.json omits local launcher/workspace paths; raw prompts, answers and access-review prose are not copied into this report.
