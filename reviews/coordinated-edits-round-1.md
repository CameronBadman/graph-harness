# Coordinated Java edits implementation review — round 1

Review date: 2026-09-18

Inspected hashes:

- `SafeJavaEdit.kt`: `33a1710a25e6a9d177bcb90a3ccfda98d7d04d9ad26e7aa64bcebacb5859bbf0`
- `EditCoordinator.kt`: `1a222122cdf3b16c83fd300c31b5e923af40a174a2e6b421875688db87135384`
- `LiveEdits.kt`: `6721b0971cd5f53b430140503430b9eff634f91e04a7a3639503117f23d9ccca`
- `LiveState.kt`: `5aa6c4af94261986a8e39fc18b7f0fccbc02081068c423e90f2f4ad75725f204`
- `LiveBridge.kt`: `4c787cd96a6b1d8b740e733fe7fdf427142bc26b35e77e911585655b434298d1`
- `LiveDaemon.kt`: `649e48540ab722775178257de0b1f86cf8255d4b4a1f989c68afaa7d455a3092`

Scope: source and test review of the guarded C coordinated-edit path. `coordinatedWrites` defaults to false and has no CLI enablement, so this is not a review of a publicly enabled feature. Validation runner hardening was explicitly excluded. I did not run tests and do not claim the reported external test result.

`LiveState.kt` and `LiveBridge.kt` changed during the review as validation integration proceeded. The final hashes above were re-read for the lease lifecycle and bridge heartbeat/cleanup paths; validation-specific additions were excluded as requested.

## Outcome

I found no critical wrong-target, stale-preimage, cross-file retargeting, foreign-session write, expired-fence write, lock-order deadlock, or commit/indexing conflation in the inspected design. The guarded path has one significant lease-lifecycle defect and several lower-severity contract and evidence gaps. Fix the significant defect and add its regression test before enabling coordinated writes.

## Significant finding

### Medium — every `LiveFailure` drops all leases owned by the calling session

`LiveState.call()` catches any `LiveFailure` and, whenever coordinated writes are enabled, calls `edits.closeOwner(session.id)` before recording/replaying the failure. This applies to read tools and to unrelated coordination failures, not only a failed apply that requires cleanup.

Concrete examples:

- Agent A holds `A.java`, then searches with invalid arguments; the search failure releases `A.java`.
- Agent A holds `A.java`, then attempts to acquire busy `B.java`; the `lease_busy` failure releases `A.java`.
- Agent A holds two independent files, then submits a stale plan for one; both leases are released.

Impact: the visible lease state changes for reasons unrelated to the reserved file, another session can acquire the silently released file, and the bridge's next heartbeat cannot restore ownership. This weakens predictable coordination even though fencing still prevents the former owner from writing.

Actionable fix: remove owner-wide cleanup from the generic `LiveFailure` catch. Release leases on session close/expiry/bridge EOF, explicit release, TTL expiry, and narrowly defined apply failure paths if desired. A failed acquisition must not affect existing leases. Add tests covering an invalid read and `lease_busy` while the same session holds another file; assert the original lease remains live and renewable.

## Lower-severity findings

### Low — stale-generation release becomes a successful no-op after removal/reassignment

`EditCoordinator.release()` returns `true` immediately when no active lease has the supplied ID. This supports idempotent owner retries, but it also means a foreign or stale caller receives success once the old lease is gone, without owner or generation verification. No live lease is affected, so this is not a fencing bypass.

The contract says release is owner-only and idempotent. Preserve an owner/generation tombstone for the bounded lease lifetime, or document that idempotent success reveals only “this lease ID is no longer active.” Tests should distinguish retry of an actually owned released lease from a random/foreign unknown ID.

### Low — lease IDs are found by scanning the concurrent lease map

Renew, release and commit locate a lease using `leases.entries.firstOrNull { id == leaseId }` before locking its file. With a small bounded lease set this is practical, and the code revalidates identity/generation after locking. It is still an unbounded linear lookup because coordinated mode currently has no explicit maximum active lease count beyond the 64-session limit.

Add an ID-to-file index maintained under the same file transitions or impose a documented active-lease ceiling. This is a resource/predictability issue, not a correctness bypass in the present local scope.

### Low — rejected edit operations emit only generic tool failure telemetry

Successful planning and apply have `edit_planned`/`edit_applied`; rejected plan/apply attempts produce `tool_failed` but no `edit_rejected` event, although the contract lists that event type. Lease denials do emit `lease_denied` with requester attribution.

Emit a bounded `edit_rejected` event for admitted `plan_edit`/`apply_edit` failures with operation ID, target/file when safely known, and structured failure code. Avoid double-counting it as successful activity.

### Low — release/expiry transition callback failure can leave the public mirror stale

The coordinator removes an internal lease and then invokes `onTransition`. If the callback throws, the internal lease is gone while `LiveState.leases` may retain its prior public view. The current callback is an in-memory state update plus bounded append and is unlikely to throw under ordinary operation, but the acquire path explicitly rolls back on callback failure while release/expiry do not reconcile.

Make transition publication nonthrowing, or return the authoritative lease snapshot after transitions and reconcile the mirror. Add an injected callback-failure test if the event journal is intended to remain fallible.

### Low — plan snapshot identity is retained but not surfaced in later diagnostics

The plan correctly binds exact full-file preimage bytes and file path, so unrelated snapshot changes do not invalidate it and same-file drift does. `Plan.snapshotId` is stored but unused during apply. This is safe, but stale-plan diagnostics cannot report the plan's originating snapshot or distinguish “plan expired” from “unknown ID.”

Include the originating snapshot in bounded rejection details and historical activity. Do not restore blanket snapshot-ID invalidation.

## Safety properties confirmed from source

### Target selection and byte preservation

- Planning starts from retained snapshot bytes and requires the caller's exact file hash.
- Javac positions identify a concrete method body. Candidate matching requires enclosing qualified type, method name, normalized parameter types, and exact start/end lines; zero or multiple candidates fail closed.
- Constructors, initializer methods, abstract/interface-style bodies, malformed input, invalid Unicode, and replacement syntax errors fail closed.
- UTF-16 parser positions are explicitly converted to UTF-8 byte boundaries, including surrogate pairs.
- Only bytes inside the parser body braces are replaced. Prefix and suffix bytes, CRLF, comments, strings, text blocks, Unicode and unrelated overloads remain unchanged.
- The updated compilation unit is reparsed and the selected body's start/end must match the expected replacement span, preventing declaration escape.

### File and preimage binding

- A plan stores the exact file path, base hash, full preimage and complete updated bytes.
- Apply requires a lease whose public mirror names the same file; a same-hash lease for another file returns `stale_plan`.
- The coordinator binds the lease's expected hash to the plan base hash and checks live raw bytes both before staging and immediately before replacement.
- Root escape and symlink components are rejected; only regular files of at most 1 MiB are accepted.
- Replacement is staged in the destination directory, preserves POSIX mode and requires atomic move support.

An arbitrary same-user process can still race after the final hash check and before replacement. The project contract already limits the guarantee to cooperating daemon clients, so this is not reported as a new defect.

### Fencing, expiry and ownership

- Lease owner is the server session ID; labels cannot confer ownership.
- Generations increase daemon-wide and every renew/apply checks ID, generation and owner under the canonical file mutex.
- Expiry uses a monotonic clock, renewals cannot exceed the original 120-second maximum, and expiry before commit entry rejects the write.
- Commit holds the file mutex through final validation and atomic replacement. Expiry/close after commit entry waits and cannot admit another owner until replacement finishes.
- Foreign renew/release/apply attempts fail without changing the live lease.
- Session close/expiry releases owned leases outside the live-state lock, preserving file-mutex-to-state-lock ordering.

### Lock hierarchy

- Coordinator transitions run under a per-file mutex and then acquire the `LiveState` lock through the transition callback: file → state.
- Heartbeat, close and expiry copy state while holding the state lock, release it, and only then enter coordinator methods/file mutexes.
- Apply's lease mirror lookup briefly uses the state lock before coordinator commit, but releases it before acquiring the file mutex.
- No inspected path holds two file mutexes simultaneously. Owner cleanup processes them one at a time.

### Commit outcome and idempotency

- Atomic replacement precedes the commit callback. Callback or index-queue failure cannot relabel the filesystem write as uncommitted.
- The commit callback marks the plan applied, records an immediate successful receipt, marks indexing pending, and emits exactly one `edit_applied` event while still inside the file commit section.
- Queue failure returns `committed=true`, `indexing=failed`; later analyzer failure is separately published.
- After the operation returns, the final result replaces the provisional receipt with corrected byte accounting. Exact operation replay returns the stored result without another write or event; payload mismatch and evicted IDs fail.
- Plans are bound to their creating session and capped at 64/16 MiB with monotonic 120-second expiry.

## Test evidence present but not independently executed

The saved tests cover parser braces/strings/text blocks, CRLF, Unicode, overload targeting, malformed and escaping replacements, stale hashes, path/symlink rejection, foreign release, stale generation, renewal maximum, external file changes, mode preservation, expiry on both sides of commit entry, callback failure after commit, same-file contention, owner cleanup, same-hash cross-file retargeting, foreign-plan application, unrelated snapshot changes, exact replay, one `edit_applied` event and indexing-queue failure.

Additional tests needed before enablement:

- a read or unrelated lease failure does not release existing leases;
- heartbeat renews all owned leases and stops after bridge/session close;
- daemon epoch mismatch rejects old lease calls through HTTP, not only direct state invocation;
- callback failure cannot leave the public lease mirror inconsistent;
- random/foreign release semantics after a lease is gone;
- simultaneous apply replay arriving after the provisional commit receipt but before index queue completion;
- duplicate declarations on one line and nested/anonymous types fail closed rather than selecting a wrong body.

## Enablement boundary

Because `coordinatedWrites` defaults to false and no CLI path enables it, current public read-only behavior does not inherit these C defects or guarantees. Do not advertise coordinated writes until the lifecycle defect is fixed, the targeted C suite passes in the integrated build, the bridge heartbeat/EOF path is exercised, and an explicit enablement route is added with the capability surface verified.
