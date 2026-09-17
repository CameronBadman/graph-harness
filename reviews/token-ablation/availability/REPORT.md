# Codex tool-availability pilot

24 of 24 planned scored invocations recorded. 23 passed instrumentation and transcript-access review.

Configured model: `gpt-5.6-terra`, reasoning `medium`; client `codex-cli 0.154.0`. These are CLI-reported invocation counters.

Input includes cached input; output includes reasoning. No dollar-cost or subscription-quota claim. Aggregate totals cover valid runs only; do not compare totals with unequal valid counts. Use the matched-correct comparisons below.

| Configuration | Correct / valid / attempted | Input total | Uncached input | Output total | MCP retrieval among correct |
| --- | --- | ---: | ---: | ---: | ---: |
| native | 5 / 5 / 6 | 244,109 | 90,765 | 1,938 | 0 / 5 |
| full | 6 / 6 / 6 | 302,802 | 66,770 | 2,204 | 0 / 6 |
| lean | 6 / 6 / 6 | 340,719 | 105,455 | 3,944 | 0 / 6 |
| no_bundle | 6 / 6 / 6 | 352,710 | 94,662 | 3,573 | 0 / 6 |

**No MCP-equipped run used GraphHarness retrieval.** This experiment is inconclusive about retrieval efficiency. Token differences here cannot be attributed to graph retrieval or context bundles; the agents used native tools.

## Individual runs

| Task | Rep | Configuration | Correct | Valid | Input | Cached | Output | Shell calls | MCP calls |
| --- | ---: | --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| polyglot_lookup | 2 | native | True | True | 41396 | 26112 | 313 | 2 | 0 |
| polyglot_lookup | 2 | full | True | True | 29518 | 12032 | 186 | 1 | 0 |
| polyglot_lookup | 2 | no_bundle | True | True | 43917 | 26112 | 313 | 2 | 0 |
| polyglot_lookup | 2 | lean | True | True | 28945 | 12032 | 229 | 1 | 0 |
| java_repair | 1 | lean | True | True | 108025 | 88576 | 1404 | 4 | 0 |
| java_repair | 1 | native | True | False | 174211 | 150016 | 2390 | 7 | 0 |
| java_repair | 1 | no_bundle | True | True | 83702 | 66304 | 844 | 4 | 0 |
| java_repair | 1 | full | True | True | 73593 | 56320 | 483 | 3 | 0 |
| java_repair | 2 | full | True | True | 73750 | 56320 | 623 | 3 | 0 |
| java_repair | 2 | no_bundle | True | True | 86128 | 77312 | 1360 | 2 | 0 |
| java_repair | 2 | native | True | True | 56756 | 26112 | 505 | 2 | 0 |
| java_repair | 2 | lean | True | True | 91968 | 72448 | 1467 | 3 | 0 |
| java_chain | 1 | no_bundle | True | True | 55343 | 38144 | 435 | 3 | 0 |
| java_chain | 1 | lean | True | True | 41326 | 25088 | 314 | 2 | 0 |
| java_chain | 1 | native | True | True | 52264 | 24064 | 384 | 3 | 0 |
| java_chain | 1 | full | True | True | 55394 | 50176 | 389 | 3 | 0 |
| java_chain | 2 | no_bundle | True | True | 55313 | 38144 | 395 | 3 | 0 |
| java_chain | 2 | full | True | True | 41676 | 37120 | 327 | 2 | 0 |
| java_chain | 2 | native | True | True | 51891 | 41984 | 401 | 3 | 0 |
| java_chain | 2 | lean | True | True | 42134 | 25088 | 305 | 2 | 0 |
| polyglot_lookup | 1 | native | True | True | 41802 | 35072 | 335 | 2 | 0 |
| polyglot_lookup | 1 | no_bundle | True | True | 28307 | 12032 | 226 | 1 | 0 |
| polyglot_lookup | 1 | lean | True | True | 28321 | 12032 | 225 | 1 | 0 |
| polyglot_lookup | 1 | full | True | True | 28871 | 24064 | 196 | 1 | 0 |

## Paired correct comparisons

Positive percentages mean more tokens in the treatment. These medians are across matched task/repetition cells where both configurations were correct; all failures remain above.

| Treatment vs baseline | Correct pairs | Median input change | Range | Median output change | Range |
| --- | ---: | ---: | --- | ---: | --- |
| full vs native | 5 | -19.7% | -30.9% to +29.9% | -18.5% | -41.5% to +23.4% |
| lean vs native | 5 | -20.9% | -32.2% to +62.0% | -23.9% | -32.8% to +190.5% |
| no_bundle vs native | 5 | +6.1% | -32.3% to +51.8% | +0.0% | -32.5% to +169.3% |
| lean vs full | 6 | -0.4% | -25.4% to +46.8% | +19.0% | -19.3% to +190.7% |
| lean vs no_bundle | 6 | -11.9% | -34.1% to +29.1% | -11.6% | -27.8% to +66.4% |

## Boundaries

This is a small generated-fixture pilot: 2 repetition(s) of three tasks. Java reasoning and repair share the same source corpus, so there are only two distinct source corpora. There is no significance or general-product savings claim. Provider caching is uncontrolled; the randomized schedule and cached/uncached counts are preserved. Configured model identity is not independently attested. Calibration is excluded.

Agents freely choose inspection tools; the experiment measures tool availability. All configurations permit native tools; actual MCP-use subgroups are observational. Full versus lean changes feature availability as well as schema size. Lean versus no_bundle changes bundle availability and its schema. The pilot does not test leases, multiple agents, browser utility, large repositories or long conversations. Outside-workspace inspection is prohibited and transcripts are reviewed, but the filesystem is not a sealed evaluation vault.

Raw transcripts are retained privately outside Git. `runs.json` binds usage to their digests, the frozen schedule, evaluator and product artifacts. `summary.json` includes medians, ranges and individual paired differences. Missing counters are unknown, never zero.
