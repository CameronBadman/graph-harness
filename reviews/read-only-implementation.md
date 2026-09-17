# Read-only A/B implementation review

Review date: 2026-09-18

Inspected implementation hashes:

- `LiveState.kt`: `acfa323efe34ecdc002f55ceea22b0799b11728183db4904f34ed4a7e6a9a816`
- `LiveDaemon.kt`: `44afc03c5f7c8db5bc35f2b38c832b48988bb8933c409812a2a5d09144c62cc8`
- `LiveBridge.kt`: `47423668f4c418df19cc3c51f36e212fae48914289238e0f0b582a196902be8`
- `LocalRuntime.kt`: `16c75c2e9929499101b21ff850304a7fe39127f69af8629d4c80a3e4953ed5b5`

Scope: bounded source/test review against the current read-only A/B milestone and `docs/live-contract-v1.md`. I did not run builds or tests. Coordinated writes, leases and validation are future C work and are not implementation defects here.

## Blocking findings

### High — `/state` cannot honor its advertised graph bound

`LiveState.state()` constructs up to 10,000 nodes and 20,000 edges, but `LiveDaemon.json()` rejects every JSON response above 1 MiB and substitutes a `result_limit` error. A graph much smaller than the advertised maximum can exceed 1 MiB, causing the initial browser state fetch to return no graph rather than an explicitly truncated usable graph. The node/edge count caps therefore do not enforce the actual transport bound.

Evidence: `LiveState.state()` uses `take(10_000)` and `take(20_000)`; `LiveDaemon.json()` replaces payloads above `MAX_RESPONSE_BYTES = 1_048_576` with HTTP 413. Existing daemon tests use one node and do not exercise bounded state serialization.

Impact: the core read-only browser can fail completely on an ordinary medium repository, despite the contract promising a bounded graph plus omission counts.

Required fix: build state to a byte budget, preserving a valid envelope and explicit omitted counts, or define and implement pagination/focused state retrieval. Test with long legal identifiers/paths and enough nodes/edges to cross 1 MiB; assert a 200 response, valid graph prefix and accurate omission metadata.

### High — current bridge does not enforce MCP initialization or strict JSON-RPC request validity

`LiveBridge.process()` accepts `tools/list`, `tools/call` and `ping` before `initialize`, has no initialized-state transition, accepts repeated initialize calls, and treats any message with an `id` as a request without validating the allowed JSON-RPC ID types. It also parses a missing/non-object `params` as an empty object and silently ignores every notification before validating its method or shape. This differs from the stricter reusable `GraphHarnessServer` behavior and leaves actual-client compatibility dependent on permissive client behavior.

Evidence: `process()` is stateless; it checks only `jsonrpc == "2.0"`, presence of a string method, and whether the `id` field exists. No `LiveBridgeTest.kt` existed in the inspected test inventory. The root reports a protocol fix is already assigned; this finding records the inspected implementation and remains blocking until the replacement tests pass.

Impact: protocol-invalid traffic can reach daemon tools, and SDK/client behavior may diverge between direct stdio and live bridge modes.

Required fix: share or exactly mirror the tested server lifecycle and request validation: legal ID types, initialize negotiation, one initialization lifecycle, initialized notification handling, required object params where applicable, parse/depth/UTF-8 limits, and notification silence. Run the official SDK transcript through `LiveBridge`, not only `GraphHarnessServer`.

## Significant findings

### Medium — operation errors lose required correlation and structured codes at the HTTP-to-MCP boundary

`LiveState.call()` correctly scopes replay by session and operation ID and stores successful and `LiveFailure` outcomes. However, `LiveDaemon.failure()` emits a generic error envelope with no `operation_id`, including exact replays of failed operations. `LiveBridge.request()` then discards the daemon error code and details, retaining only the message, and maps every daemon tool failure to unstructured MCP text.

Evidence: `call()` throws stored failures; the route-level catch invokes `failure(exchange, failure)`, whose envelope contains only `error`. `LiveBridge.request()` reads only `error.message`.

Impact: callers cannot correlate HTTP failure responses with an operation, distinguish `operation_expired` from `unsupported_operation` mechanically through the bridge, or audit a failed replay using the contract's envelope.

Required fix: attach operation ID to all accepted-call errors, preserve daemon code/details in a bounded structured tool error, and add replay tests for success, unsupported operation, payload mismatch and evicted IDs through HTTP and MCP.

### Medium — inspection events omit the browser request ID required for end-to-end attribution

The inspect response includes both `request_id` and server `inspection_id`, but journal events include only `inspection_id`. The contract requires inspection telemetry to contain both so the UI can match its request to started/completed/failed activity without guessing.

Evidence: `LiveState.inspect()` passes only `inspectionId` into `execute()`; `append()` has no request-ID parameter or event field.

Impact: concurrent observer reads cannot be correlated to their initiating UI request, weakening the product's observed-action attribution.

Required fix: carry the validated request ID through all three inspection events and test two concurrent inspections with distinct request IDs.

### Medium — per-event 16 KiB bound is not enforced

The journal is bounded to 2,000 events/4 MiB and SSE pending delivery is bounded, but an individual event is not bounded to 16 KiB. Capping lists to 64 node IDs and 32 paths is insufficient because accepted strings may each be up to 4 KiB. One event can therefore be hundreds of KiB, immediately trigger slow-consumer reset, and consume a substantial fraction of the journal.

Evidence: `append()` serializes capped counts but never checks encoded byte length. `validateArguments()` permits 4 KiB strings and arrays of up to 200 strings.

Impact: valid read results can violate the contract and make event recovery unreliable. This is also a local resource-amplification path for authenticated clients.

Required fix: enforce the encoded 16 KiB ceiling by truncating node/path arrays to a byte budget with separate omitted-node and omitted-file counts. Test worst-case legal strings. The root reports this fix is already assigned.

### Medium — capability responses are internally inconsistent in read-only mode

The live tool list correctly excludes edit tools, and the modified `get_capabilities` result empties `edit_operations`/`validation_modes` and sets `coordinated_writes=false`. It does not rewrite the underlying `disabled_tools` or all tool guarantees, so clients can receive a read-only available-tool list alongside baseline capability fields that do not fully describe the live endpoint's disabled surface.

Evidence: `execute()` overwrites four fields only; the baseline capability object is generated by `SnapshotManager.capabilities()` for the uncoordinated server.

Impact: agents may plan around tools that the live endpoint correctly rejects. It also undermines the contract's rule that availability reflects completed safety gates.

Required fix: produce one live capability view from the actual live definitions and disabled-gate set, including guarantees. Add an assertion that every advertised available tool is callable in principle and every unsafe tool is explicitly absent/disabled. The root reports this fix is already assigned.

### Medium — pairing claim polling is not rate-limited as specified

Pair creation is rate/cap limited, but `/observer/claim` has no once-per-second enforcement. A holder of a pairing cookie can submit claims as fast as the HTTP executor accepts them until approval/expiry.

Evidence: `claim()` calls `purgePairs()` and searches by cookie but stores no last-claim time or attempt count.

Impact: this does not bypass approval, but it permits unnecessary local request amplification against the fixed 16-thread/32-request executor during onboarding.

Required fix: track monotonic last-claim time per pair and return 429 before one second. Add a deterministic-clock test.

## Lower-severity correctness and coverage gaps

### Low — session expiry can produce terminal events after `session_expired`

A read operation executes outside the state lock. Maintenance can remove its session at 30 seconds and append `session_expired`; the operation then appends `tool_completed`/`tool_failed` without revalidating liveness and stores a receipt on the detached session object. The credential cannot be reused, so this is not an authorization bypass, but event order can imply work completed for a session already declared expired.

Decide and document one behavior for read-only operations: allow an in-flight read to finish and delay terminal expiry publication, or mark its terminal event as completed-after-expiry/cancelled. Add a forced-clock/barrier test.

### Low — static assets are read without a byte bound

Path confinement is sound, but `Files.readAllBytes(target)` can allocate the complete configured asset. A mistakenly large local asset can exhaust heap. Stream assets with a configured size ceiling or reject oversized files.

### Low — snapshot status is sampled from separate manager calls

`publishSnapshot()` reads `manager.current()`, `lastBuildFailure()` and `snapshotState().pending_rebuild` separately before taking the live-state lock. Each value is individually safe, but they need not describe one analyzer instant. A fast build transition can briefly pair a new snapshot with stale failure/pending status.

Expose one immutable analyzer publication/status record or tolerate and explicitly test eventual correction. The root reports source-admission/publication diagnostics are already being revised.

### Coverage gap — current tests do not exercise the contract's adverse bounds

The inspected tests cover distinct identities, basic replay, expiry/reset, origin rejection, pairing happy path, path confinement, two SSE streams, descriptor permissions and direct stdio framing. They do not cover live-bridge lifecycle, malformed UTF-8/depth, HTTP failure replay correlation, receipt eviction/high-water rejection, session limit, 1 MiB state behavior, 16 KiB event behavior, claim throttling, inspection attribution, stream cap/reset under real backlog, or snapshot publication races.

## Confirmed strengths

- The daemon binds only `127.0.0.1`, requires the exact Host, rejects foreign origins, separates bootstrap/agent/observer authority, and requires browser cookie origin/fetch metadata.
- Runtime discovery canonicalizes the checkout, uses an OS file lock, validates a loopback-only descriptor endpoint, enforces owner-only POSIX permissions, rejects symlinked runtime paths, atomically publishes the descriptor, and removes only its own epoch.
- Session credentials and bootstrap credentials are random, bearer comparison is constant-time, labels are bounded, roles derive from server-issued sessions, and labels cannot confer ownership.
- Operation identity is session-scoped, monotonic, digest-bound and replay-protected. Eviction leaves the high-water mark, preventing an old ID from executing again.
- Total journal retention, per-stream pending delivery, stream count, request body, JSON nesting, inspection result, parser-facing argument and session-count bounds exist. Network writes occur outside the state lock.
- Snapshot/state and journal cursor are copied under one live-state lock; tool events are derived from actual returned results and exclude source bodies.
- The read-only endpoint excludes edit/apply/validation tools. Missing C lease/edit behavior is correctly reported as absent and was not counted as a defect in this review.

## Release implication

Do not call the read-only A/B slice complete until the two high-severity blockers are fixed and exercised through the actual bridge/browser boundaries. The medium findings should be closed before relying on the UI for accurate multi-agent supervision or claiming the contract's bounded recovery behavior. Passing the existing unit tests alone would not establish these properties.
