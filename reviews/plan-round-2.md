# Independent development-plan critique — round 2

Reviewed files:

- `DEVELOPMENT_PLAN.md` version 1.2 — SHA-256 `80ca105b1c28967fcabade3639b2bc1ced9388b56db5aabb4fc067e7ba7614a6`
- `docs/live-contract-v1.md` — SHA-256 `8459faff7cd608c823c128dfa3b2e91819d74d21b12935cf5991b11ea7b58d4f`

Review date: 2026-09-18
Review scope: plan and normative contract quality only. This score is not implementation, test, interoperability, performance, security, or release evidence.

## Score

| Frozen dimension | Weight | Score | Assessment |
| --- | ---: | ---: | --- |
| Product value and compelling demo | 15 | 15 | The product promise and demo remain specific, understandable, and honest about observed activity versus private reasoning. |
| Repository grounding and reuse | 10 | 10 | The plan remains closely tied to the actual Kotlin structure and identified hazards, and now records the limited baseline test result without treating it as hazard coverage. |
| Realistic scope, sequencing and cut lines | 20 | 19 | A0/A1 are now distinct, the artifact and SDK assertions are concrete, and later milestones have hard fallback states. A few cross-module contract details still precede safe parallel implementation. |
| Architecture and integration coherence | 15 | 13 | Ownership transfers and the normative service contract remove the broad round-one ambiguity. The browser inspection boundary is still undefined despite being required, and cross-owner lock composition lacks an ordering rule. |
| Concurrency, event and analysis correctness | 20 | 18 | Epochs, fencing, replay high-water behavior, state/event cursor continuity, bounded consumers, immutable source, and validation capture are well specified. The coordinator/journal/file-lock interaction remains a material concurrency gap. |
| Measurable verification and visual acceptance | 15 | 15 | The evidence ledger has concrete adversarial gates, real-client versus scripted distinctions, visual acceptance, resource limits, and measured performance rather than assumed claims. |
| Submission/handoff readiness and honest limits | 5 | 5 | Status, evidence columns, demo provenance, supported platform, packaging, and external-action boundaries are explicit. |
| **Total** | **100** | **95** | **Arithmetic: 15 + 10 + 19 + 13 + 18 + 15 + 5 = 95.** |

The numeric target is met, but the plan does **not** yet satisfy its stronger stop condition of at least 95 with no blocking planning weakness.

## Round-one blocker disposition

### 1. Delegation boundaries conflicted with the one-writer rule — resolved

Version 1.2 assigns sole owners by tranche, names existing and new files, defines transfer points, prevents concurrent edits to analyzer DTOs, and makes interface changes requests to the active owner. It also distinguishes a clean ownership handoff from a clean Git tree. This is sufficiently executable for the current four-slot setup.

### 2. Executable wire and authority contract was deferred — substantially resolved, with one consumer gap

The normative contract now chooses one browser-pairing flow; defines credential roles and endpoint permissions; scopes operation identity to epoch/session/ID; specifies digest mismatch, active duplicate, retention and high-water behavior; gives state, event and reset examples; and sets resource ceilings. The original broad blocker is resolved. However, `POST /inspect`, the browser's only source/search/detail route, appears only in the authorization matrix. Its request, success/error response, operation/event correlation, limits, and allowed tool argument validation are absent. Since the UI and daemon have separate owners, this missing boundary can still produce incompatible implementations.

Exact fix: define `POST /inspect` in `docs/live-contract-v1.md` with a concrete envelope and examples. State whether it reuses the tool-call shape without agent operation IDs or uses an observer-scoped request ID; enumerate the three permitted tool names; specify structured errors, source/result limits, and whether observer reads emit `tool_started/completed/failed` or a distinct inspection event. Add this route to the shared contract fixture assertions before UI/service implementation proceeds.

### 3. A1 transport implementation and gate were not placed — resolved

A1 now names the worker/file ownership, exposed dispatch APIs, default and legacy launcher modes, official Python MCP SDK test, required lifecycle assertions, and blocked-state consequence. It correctly avoids treating SDK interoperability as autonomous-agent proof.

## Remaining blocking planning weakness

### Cross-module lock ordering is not defined

The contract says `GET /state` captures snapshot, sessions, leases and journal cursor under a coordinator state lock; event IDs increment under the state/journal lock; and every lease/edit transition uses the same per-file mutex. Coordination is assigned to a lease worker while event/session routing belongs to the lead. The plan does not say whether a file-mutex holder may acquire the coordinator/journal lock to publish a lease/edit event, whether session expiry holds the coordinator lock while releasing file leases, or how an atomic state view is obtained without taking locks in the opposite order. This leaves a realistic deadlock and inconsistent-snapshot risk exactly at the boundary between independently implemented modules.

Exact fix: freeze a lock hierarchy and event-publication rule in the normative contract. For example: never wait for a file mutex while holding the coordinator/journal lock; mutation computes a bounded event while holding the file mutex, commits lease/edit state, releases it, then appends the event under the journal lock; if state and cursor must be atomic across those domains, use a single coordinator mutation executor or immutable versioned state publication rather than nested locks. Specify how session expiry/daemon shutdown follows the same rule and add a barrier-controlled test that races state reads, expiry, renewal and apply while detecting nontermination and cursor/state inconsistency.

## Non-blocking observations

- “All responses include `daemon_epoch`” should explicitly exempt static asset responses and SSE comments, or say “all JSON API responses.” This is easy to infer but should not remain normative wording.
- The pairing contract should state whether top-level static navigations without an `Origin` header are allowed; “require same Origin on all browser requests” otherwise conflicts with ordinary browser navigation semantics. Require exact Origin on state-changing/API requests and retain exact Host checking everywhere.
- The contract gives body and source limits but does not bound labels, names, path length, argument nesting, JSON depth, or pairing-code character alphabet. These can be implementation constants with negative tests rather than another planning round.
- The validation environment says to allow documented toolchain variables but does not name the initial allowlist. Record it in implementation configuration and evidence so validation behavior is reproducible.
- The ownership table still refers to “new snapshot tests” without naming whether the active owner may edit the existing `AnalyzerTest.kt`. Recording that file explicitly at assignment time, as the plan already requires for existing ownership, is sufficient.

## Remaining implementation uncertainty

Planning cannot establish standard-client interoperability, daemon descriptor safety on the target filesystem, lock and atomic-replace behavior, Joern/fallback normalized identity, parser dependency reproducibility, browser performance, or validation-manifest completeness. Those remain correctly gated by execution evidence. The contract's detailed limits and unsupported states make these uncertainties reportable without overstating product readiness.

No build, protocol test, browser test, or implementation test was run for this critique. Ongoing source changes by implementation workers were not evaluated or scored.
