# Guided Codex workflow follow-up

12 of 12 planned scored invocations recorded. 12 passed instrumentation and transcript-access review.

Configured model: `gpt-5.6-terra`, reasoning `medium`; client `codex-cli 0.154.0`. These are CLI-reported invocation counters.

Input includes cached input; output includes reasoning. No dollar-cost or subscription-quota claim. Aggregate totals cover valid runs only; do not compare totals with unequal valid counts. Use the matched-correct comparisons below.

| Configuration | Correct / valid / attempted | Input total | Uncached input | Output total | MCP retrieval among correct |
| --- | --- | ---: | ---: | ---: | ---: |
| native | 3 / 3 / 3 | 151,730 | 65,458 | 1,560 | 0 / 3 |
| full | 3 / 3 / 3 | 223,335 | 27,495 | 2,103 | 1 / 3 |
| lean | 3 / 3 / 3 | 205,383 | 37,447 | 2,408 | 1 / 3 |
| no_bundle | 3 / 3 / 3 | 162,416 | 23,920 | 1,449 | 0 / 3 |

## Individual runs

| Task | Rep | Configuration | Correct | Valid | Input | Cached | Output | Shell calls | MCP calls |
| --- | ---: | --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| polyglot_lookup | 1 | no_bundle | True | True | 29694 | 25088 | 212 | 1 | 0 |
| polyglot_lookup | 1 | full | True | True | 72637 | 66304 | 514 | 1 | 2 |
| polyglot_lookup | 1 | native | True | True | 28133 | 12032 | 199 | 1 | 0 |
| polyglot_lookup | 1 | lean | True | True | 29752 | 25088 | 240 | 1 | 0 |
| java_chain | 1 | native | True | True | 52399 | 36096 | 379 | 3 | 0 |
| java_chain | 1 | full | True | True | 42221 | 36096 | 375 | 2 | 0 |
| java_chain | 1 | lean | True | True | 42478 | 25088 | 318 | 2 | 0 |
| java_chain | 1 | no_bundle | True | True | 41424 | 37120 | 369 | 2 | 0 |
| java_repair | 1 | lean | True | True | 133153 | 117760 | 1850 | 3 | 1 |
| java_repair | 1 | full | True | True | 108477 | 93440 | 1214 | 4 | 0 |
| java_repair | 1 | no_bundle | True | True | 91298 | 76288 | 868 | 3 | 0 |
| java_repair | 1 | native | True | True | 71198 | 38144 | 982 | 3 | 0 |

## Paired correct comparisons

Positive percentages mean more tokens in the treatment. These medians are across matched task/repetition cells where both configurations were correct; all failures remain above.

| Treatment vs baseline | Correct pairs | Median input change | Range | Median output change | Range |
| --- | ---: | ---: | --- | ---: | --- |
| full vs native | 3 | +52.4% | -19.4% to +158.2% | +23.6% | -1.1% to +158.3% |
| lean vs native | 3 | +5.8% | -18.9% to +87.0% | +20.6% | -16.1% to +88.4% |
| no_bundle vs native | 3 | +5.5% | -20.9% to +28.2% | -2.6% | -11.6% to +6.5% |
| lean vs full | 3 | +0.6% | -59.0% to +22.7% | -15.2% | -53.3% to +52.4% |
| lean vs no_bundle | 3 | +2.5% | +0.2% to +45.8% | +13.2% | -13.8% to +113.1% |

## Boundaries

This is a small generated-fixture pilot: 1 repetition(s) of three tasks. Java reasoning and repair share the same source corpus, so there are only two distinct source corpora. There is no significance or general-product savings claim. Provider caching is uncontrolled; the randomized schedule and cached/uncached counts are preserved. Configured model identity is not independently attested. Calibration is excluded.

Identical conditional guidance requests bundle-first or search/source-first inspection where available. The tasks reuse development fixtures; this is exploratory, not independent confirmation. All configurations permit native tools; actual MCP-use subgroups are observational. Full versus lean changes feature availability as well as schema size. Lean versus no_bundle changes bundle availability and its schema, plus prescribed routing policy. The pilot does not test leases, multiple agents, browser utility, large repositories or long conversations. Outside-workspace inspection is prohibited and transcripts are reviewed, but the filesystem is not a sealed evaluation vault.

Raw transcripts are retained privately outside Git. `runs.json` binds usage to their digests, the frozen schedule, evaluator and product artifacts. `summary.json` includes medians, ranges and individual paired differences. Missing counters are unknown, never zero.
