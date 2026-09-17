# Independent development-plan critique — round 3

Reviewed files:

- `DEVELOPMENT_PLAN.md` version 1.3 — SHA-256 `91f0ffa0909183a71787a556957d4e7526ea443752b1bf52ac3c15e929047104`
- `docs/live-contract-v1.md` — SHA-256 `96ed921ccc277f35ad2e2bc2d5438ddef7368f2473ff6b16756f97403217442d`

Review date: 2026-09-18
Review scope: plan and normative contract quality only. This score is not implementation, test, interoperability, performance, security, or release evidence.

## Score

| Frozen dimension | Weight | Score | Assessment |
| --- | ---: | ---: | --- |
| Product value and compelling demo | 15 | 15 | The product and demo remain concrete, useful, and honest about observed actions, inferred structure, and proof boundaries. |
| Repository grounding and reuse | 10 | 10 | The plan is anchored to the actual Kotlin architecture and known baseline hazards, with baseline tests correctly reported as limited evidence. |
| Realistic scope, sequencing and cut lines | 20 | 19 | A0–E dependencies, owner transfers, read-only fallback, language gates, and release cut lines are executable. The complete release remains ambitious, but the plan does not disguise that risk. |
| Architecture and integration coherence | 15 | 15 | Daemon, bridge, analyzer, coordinator and browser responsibilities now have frozen boundaries, explicit ownership transfers, a usable observer contract, and a coherent publication model. |
| Concurrency, event and analysis correctness | 20 | 19 | The contract covers lock order, atomic public state/event updates, expiry and commit races, fencing, replay, snapshot/source consistency and external-process limits. Only implementation proof can establish the guarantees. |
| Measurable verification and visual acceptance | 15 | 15 | Positive and adversarial gates cover protocol, authority, races, recovery, browser behavior, languages, performance, real-agent evidence, and limits without substituting a critique score for runtime results. |
| Submission/handoff readiness and honest limits | 5 | 5 | Evidence-ledger fields, supported platform, packaging outputs, provenance requirements and external-action boundaries are explicit. |
| **Total** | **100** | **98** | **Arithmetic: 15 + 10 + 19 + 15 + 19 + 15 + 5 = 98.** |

The plan meets the target and I found no blocking planning weaknesses. Planning iteration can stop. This conclusion applies only to the reviewed plan/contract hashes; every product and release claim remains dependent on the stated implementation gates.

## Round-two blocker disposition

### 1. Browser inspection contract — resolved

`POST /inspect` now defines the request, success and error correlation fields; exact permitted operations; role behavior; shared schema validation; body, argument and result limits; stale and malformed cases; observer-specific telemetry; and fixture requirements. It keeps observer reads visibly separate from coding-agent operations and does not let browser request IDs enter edit idempotency state. UI and service owners now have enough normative detail to implement independently and detect drift in shared fixtures.

### 2. Cross-module lock ordering — resolved

The contract permits only file-mutex-to-coordinator-lock nesting and prohibits the reverse. It defines immutable public mirrors, atomic visible-state/event publication, snapshot publication, apply commit sequencing, expiry/EOF/shutdown cleanup outside the coordinator lock, one-file-at-a-time cleanup, and behavior on both sides of commit entry. The barrier-controlled race gate exercises state capture, subscribe, expiry, renewal and apply, including cursor consistency and nontermination. This closes the delegation boundary that previously allowed deadlock-prone implementations.

## Non-blocking clarifications

- The later exhaustive-looking `Event types:` list omits `inspection_started`, `inspection_completed` and `inspection_failed`, although the preceding normative inspection section explicitly requires them. Add them to that list when the contract is next touched so generated enums or fixture readers do not overlook them.
- The browser-origin paragraph says fetch uses an “explicit same-origin header” and then correctly says JavaScript cannot set `Origin` and the daemon uses `Sec-Fetch-Site` for safe GETs. Rephrase the former as browser-supplied fetch metadata to remove the small wording tension.
- Assign the existing snapshot test file explicitly when recording the B-source ownership transfer. The plan already requires that assignment-time record, so this does not need another planning revision.
- Record the concrete validation environment-variable allowlist in implementation configuration and evidence. The current contract correctly prevents inheriting arbitrary secrets but leaves the safe toolchain set to implementation.

## Remaining implementation uncertainty

The following are gates, not planning defects: official SDK interoperability; filesystem descriptor, lock and atomic-replace behavior on the supported Linux setup; correctness of the proposed concurrency implementation under forced schedules; parser dependency installation and normalized identities; browser performance and accessibility; validation-copy completeness; two actual client sessions; and TS/JS/Python adapter truth. A 98/100 plan score provides no evidence that any of them pass.

No build, protocol, browser, concurrency, or implementation test was run for this critique. Ongoing test results from the root or other workers were not used in the score.
