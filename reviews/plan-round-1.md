# Independent development-plan critique — round 1

Reviewed file: `DEVELOPMENT_PLAN.md`
Reviewed SHA-256: `42990706f23562cb76db6073aaac897b4962bb84f8becc9b52dee6af85e5615a`
Review date: 2026-09-18
Review scope: plan quality only. This score is not evidence that GraphHarness Live is implemented, correct, usable, or release-ready.

## Score

| Frozen dimension | Weight | Score | Assessment |
| --- | ---: | ---: | --- |
| Product value and compelling demo | 15 | 15 | The shared graph, observed agent activity, real contention, and explicit refusal to depict private reasoning form a clear and credible product story. |
| Repository grounding and reuse | 10 | 10 | The plan accurately identifies the Kotlin runtime, Content-Length transport, live-file source reads, dirty-set publication race, unsafe rename, brace-scanning boundary, and fallback restrictions in the inspected source. |
| Realistic scope, sequencing and cut lines | 20 | 18 | Read-only, coordinated-write, language, and release gates have honest cut lines. The four-hour attempt is treated as a risk budget. A protocol sequencing ambiguity and missing assignment dependency graph reduce executability. |
| Architecture and integration coherence | 15 | 12 | The daemon/bridge/browser split and single authority are coherent, but delegated file ownership conflicts with the stated one-writer rule, and several boundary schemas are deferred until implementation. |
| Concurrency, event and analysis correctness | 20 | 17 | The plan handles fencing, epochs, immutable snapshot bytes, generations, commit/index separation, cursor reset, and external-write limits unusually well. Authority roles, operation identity scope, and validation capture still have material ambiguities. |
| Measurable verification and visual acceptance | 15 | 15 | Gates contain meaningful positive and adversarial evidence, deterministic race barriers, actual-client checks, browser accessibility, performance measurement, and a clean distinction between scripted tests and real agents. |
| Submission/handoff readiness and honest limits | 5 | 5 | Packaging is concrete and external publication remains explicitly unauthorized; plan scores are correctly separated from product evidence. |
| **Total** | **100** | **92** | **Arithmetic: 15 + 10 + 18 + 12 + 17 + 15 + 5 = 92.** |

The plan does not yet meet its own target of at least 95 with no blocking planning weaknesses.

## Blocking planning weaknesses

### 1. Delegation boundaries conflict with the one-writer rule

The protocol worker owns `Server.kt`; the lead owns shared service/session/events; the snapshot worker owns `Analyzer.kt`, `AnalysisSupport.kt`, `EditSupport.kt`, and “relevant model fields”; and language workers own adapter files. In the current repository, dispatch and serialization are concentrated in `Server.kt`, snapshot authority is the `SnapshotManager` in `Analyzer.kt`, and shared response/edit types span `Model.kt` and `EditSupport.kt`. Implementing daemon routing, event instrumentation, session-derived ownership, leases, new graph fields, and adapters will therefore require overlapping changes to files assigned to different owners. The plan says a shared file has one writer but does not define when ownership transfers, which interfaces must be frozen first, or who performs integration edits.

Exact fix: add a dependency/ownership table for each implementation tranche. Name the concrete new modules and the sole owner of every existing shared file. A workable split would have the lead first extract and freeze protocol-neutral interfaces and wire DTO/schema files, then transfer `Server.kt` exclusively to the bridge worker, keep `Analyzer.kt`/`Model.kt` with one snapshot owner, and make the lead integrate through new files without editing owned files concurrently. State explicit handoff points, required clean diffs, and which worker—not merely “root”—resolves each cross-boundary change. Browser work should begin only after a versioned schema fixture is committed.

### 2. The executable wire and authority contract is still deferred

The plan says the exact JSON schema “will be checked in with service implementation.” That leaves independent protocol, browser, and service workers free to make incompatible choices about `/state`, SSE reset events, tool result/error envelopes, session roles, observer onboarding, and restart behavior. `POST /sessions` is described as bootstrap-authenticated, while browser onboarding is a choice between two materially different mechanisms. The browser is said to have no write authority, but the role/capability model and which endpoints each credential can invoke are not enumerated. Operation ID uniqueness and idempotency storage are scoped only loosely “within epoch”; collision behavior across sessions is unspecified.

Exact fix: freeze a small versioned contract before parallel implementation. Include request/response examples or JSON Schema for session creation, tool calls, state, every SSE control envelope (`event`, `reset`, heartbeat), and structured errors. Define credential roles and an endpoint/method authorization matrix. Choose one browser onboarding mechanism for V1. Define operation identity as `(epoch, session_id, operation_id)`, specify payload-digest mismatch handling and bounded outcome retention, and state the exact reset/re-authentication response after daemon restart.

### 3. Phase A depends on implementation that the milestone/delegation sequence does not place

Milestone A requires proving newline MCP with an actual SDK/client, but the baseline implements only Content-Length framing and the protocol workstream is otherwise presented alongside service/UI work. It is unclear whether A includes modifying the legacy launcher, building a new bridge, or only recording a known failure. This affects the first gate and every later real-agent claim.

Exact fix: split A into `A0 baseline evidence` and `A1 transport spike`. Specify the artifact for A1 (for example, a new bridge executable using newline-delimited JSON-RPC while legacy Content-Length mode remains named and isolated), its sole file owner, the selected real client/SDK test, and the minimum transcript assertions. Make B depend on A1. If the real client cannot be configured, mark A1 blocked and permit only deterministic protocol-client development—not a passing real-agent gate.

## Non-blocking improvements

- Define validation-copy inputs mechanically: inclusion/exclusion rules for tracked, untracked, ignored, generated, and symlinked files; timeout/output/environment/network policy; and whether working-tree changes are expected in the manifest. “Relevant inputs” is too subjective for independent implementations, although the drift gate is sound.
- Define the supported platform for V1 atomic replacement and OS locking. The plan permits explicit unsupported results, but packaging should name the release platform rather than discover portability only during release evidence.
- Add resource ceilings for event ring entries/bytes, retained snapshot bytes, request bodies, source budget, parser subprocesses, and validation output. The plan correctly asks for bounded behavior but leaves several bounds to divergent implementers.
- Clarify whether “two real coding agents” means two separate client processes/sessions and how work is assigned without requiring a new orchestration engine. The demo gate should record prompts/tasks and client process identities without journaling prompt bodies in product telemetry.
- Turn `DEVELOPMENT_STATUS.md` into the named acceptance ledger schema now: gate, commit/artifact, command, environment, outcome, evidence path, limitations, reviewer. Its current high-level status is honest but not yet sufficient for the later evidence volume.

## Remaining implementation uncertainty

Even after the planning blockers are fixed, several questions require implementation evidence rather than more prose: whether a standard client accepts the bridge lifecycle and framing; whether Joern and fallback snapshots can share stable normalized IDs; whether parser dependencies install reproducibly in the JVM/Nix setup; whether filesystem replacement and root locking meet the stated guarantees on the supported OS; whether event latency and 1,000-symbol rendering targets hold; and whether the test-copy manifest captures the actual build dependency surface. None of these should reduce the candor of the plan, but none can be treated as verified until their gates run.

No build or protocol test was run for this critique. `DEVELOPMENT_STATUS.md` currently reports the baseline build as in progress, so this review does not infer a passing baseline.
