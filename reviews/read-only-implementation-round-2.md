# Read-only A/B implementation rereview — round 2

Review date: 2026-09-18

Final inspected implementation hashes:

- `LiveState.kt`: `8a5f6f7b93d2e4681ac0996429f1f45ffc3ed2261448efc4db3fc04457f5f77a`
- `LiveDaemon.kt`: `946017e5f4c52ba3b8638b86e2a24bfb5cb787004b2010c29f7673c36757bc5c`
- `LiveBridge.kt`: `5203d36aea426ba5ac16205febaf7ca0ea1964c3ad55c5439c72a376532f0280`
- `LocalRuntime.kt`: `16c75c2e9929499101b21ff850304a7fe39127f69af8629d4c80a3e4953ed5b5`

Also inspected the saved `LiveStateTest.kt`, `LiveDaemonTest.kt`, `LiveBridgeTest.kt`, and `LocalRuntimeTest.kt`. Tests were running elsewhere; I did not run them and do not claim that they pass.

Scope remains the read-only A/B milestone. C-stage leases, coordinated edits and validation are absent by design and are not counted as defects.

## Outcome

I found no remaining blocking source defect for the bounded read-only A/B milestone. The two prior high-severity blockers are resolved in the saved files, as are the material attribution, capability, event-bound, failed-replay, claim-throttle and static-asset findings. Completion still depends on the targeted and integrated tests actually passing, official SDK/client evidence, and browser verification.

## Prior finding disposition

### `/state` failed above 1 MiB — resolved

`LiveState` now constructs and caches a graph view outside the state lock. Nodes are admitted to a 512 KiB budget and edges to a 760 KiB cumulative budget, with 10,000/20,000 count caps, omission counts, and edges restricted to admitted node IDs. Session/diagnostic/envelope overhead retains margin below the daemon's 1 MiB response ceiling. Epoch comparison prevents an older prepared graph from replacing a newer publication.

Saved tests now create a large graph and assert a sub-1 MiB valid state response, nonzero node omissions, and no dangling edges. These assertions are present but were not executed by this review.

### Bridge lifecycle and strict request validation — resolved for A/B

`LiveBridge` now tracks initialize and initialized-notification state, rejects tool calls before lifecycle completion, rejects repeated initialization, validates ID and params shapes, supports omitted tool arguments as an empty object, bounds frame size and JSON depth, reports malformed UTF-8, and emits `structuredContent` only for object results. Daemon tool failures retain their code/details and operation ID through MCP `isError` results.

`LiveBridgeTest.kt` now exercises pre-initialization calls, lifecycle ordering, SDK-style calls, invalid params/IDs, deep JSON, malformed UTF-8 and correlated disabled-tool errors. The official SDK/client gate remains separate runtime evidence.

### Failed operation correlation and replay — resolved

The daemon call/inspect routes catch `LiveFailure` after extracting the operation/request ID and include that correlation in the HTTP error envelope. The bridge preserves the daemon error object and operation ID. State receipts remain digest-bound, session-scoped, high-water protected and bounded; the new eviction test verifies that an evicted ID is rejected rather than executed again.

### Inspection request attribution — resolved

`request_id` now flows through inspection started/completed/failed events alongside the server inspection ID. Tests create two inspections and assert distinct inspection IDs with the two initiating request IDs.

### Per-event 16 KiB bound — resolved

Event construction now serializes against the byte ceiling, dropping file then node references until the event fits and reporting separate omitted-file/node counts. Fixed fields are bounded sufficiently that an empty-reference event fits. A saved adverse test submits a very long rejected tool name and asserts every retained event remains within 16 KiB.

### Read-only capability consistency — resolved

The live capability response derives `available_tools` from the actual definitions, derives disabled tools from the full server definition set, filters guarantees to the available set, empties edits/validation, marks coordinated writes false, and reports the actual backend semantic level. A saved test checks the visible surface and guarantee keys.

### Claim polling and static asset bounds — resolved

Pair state now records monotonic last-claim time and rejects a second claim inside one second. Static assets are rejected above 1 MiB before `readAllBytes`. The claim throttle has a saved HTTP test.

## Remaining non-blocking issues and limits

### Medium — valid non-object JSON is classified as a parse error

`LiveBridge.process()` parses and immediately calls `asObject()` inside the same catch that maps syntax failures to JSON-RPC `-32700 Parse error`. A syntactically valid top-level array, string or number is an invalid JSON-RPC request and should return `-32600 Invalid Request`, not a parse error.

This traffic is rejected and cannot reach daemon tools, so it is not an A/B authorization or execution blocker. Separate parsing from top-level object validation and add one test for each valid non-object JSON type.

### Low — read completion may be journaled after session expiry

As before, a read runs outside the state lock. Maintenance can remove the session and append `session_expired`; the read can then append a terminal tool event and store a receipt on the detached session object. Authentication remains revoked, so this is not an authorization bypass. It can make the activity ordering confusing for a tool call exceeding the 30-second liveness window.

Choose and test a policy: delay terminal expiry publication for active reads, cancel/mark the read, or explicitly label completion after expiry.

### Low — analyzer status is still sampled across separate calls

`publishSnapshot()` separately reads current snapshot, last failure and pending-rebuild state. Epoch regression is now prevented and expensive graph preparation stays outside the live-state lock, which fixes the dangerous publication behavior. A rapid analyzer transition can still briefly pair a snapshot with a stale pending/failure phase until the next 250 ms maintenance pass.

An immutable analyzer publication record would remove the transient mismatch. For this local read-only view, eventual correction is a reasonable documented limitation if a deterministic race test confirms no epoch regression or stale graph replacement.

### Low — static size check has a local TOCTOU window

The daemon calls `Files.size()` and then `Files.readAllBytes()`. A same-user process can replace/grow the asset between those calls. This is not a remote path escape and the UI assets are operator-selected local files, but the size bound is not transactional. A bounded streaming read would enforce the ceiling under concurrent local mutation.

### Low — early inspection rejection has no server inspection ID

Unsupported inspection names and invalid request IDs are rejected before `inspection_id` is allocated. Their response now preserves `request_id`, which is enough for UI correlation, but the contract's general error wording can be read as expecting both IDs. Clarify that `inspection_id` exists only after request admission, or allocate it before permitted-tool validation.

## Evidence limits

The saved tests materially improve coverage: large-state budgeting, dangling-edge exclusion, receipt eviction, request attribution, event size, capability truth, correlated failed replay, claim throttling and bridge adverse protocol cases are represented. They still do not by themselves prove:

- official MCP SDK interoperability or two real coding-client sessions;
- browser rendering, reconnect behavior or event latency;
- publication behavior under forced concurrent epoch changes;
- the eight-stream cap and slow-consumer reset under a real backlog;
- session-limit behavior and active-read expiry ordering;
- static-asset enforcement under concurrent local replacement.

Those are remaining verification items, not source blockers found by this rereview.

## Confirmed boundary

The read-only daemon continues to exclude plan/apply/rebase/validation and edit recommendation operations. Empty lease state and absent C coordination are accurately represented. This review does not authorize coordination claims or treat future C behavior as implemented.
