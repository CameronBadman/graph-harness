# Coordinated Java edits implementation rereview — round 2

Review date: 2026-09-18

Inspected hashes:

- `SafeJavaEdit.kt`: `33a1710a25e6a9d177bcb90a3ccfda98d7d04d9ad26e7aa64bcebacb5859bbf0`
- `EditCoordinator.kt`: `d23dc1264a43af37118e3474ae1956bc703d1c3735ced4f8385e6c0c574888ba`
- `LiveEdits.kt`: `4cc9b01ed05b7f63d8ab3eeea724672db230e1b3204067efa5d7f5e135361461`
- `LiveState.kt`: `d0579ada90c332b084f7db49b92df4be0036df06ff34b125c655a293346e41ac`
- `LiveBridge.kt`: `4c787cd96a6b1d8b740e733fe7fdf427142bc26b35e77e911585655b434298d1`
- `LiveDaemon.kt`: `8ba837b8c65a88f6d73e83c44f2223260aca1e763f80ac87b6904a8759af5077`
- `ValidationRunner.kt`: `9c02ca7e2a7f6158b40545c2dd68257b9a4ae6c4e8938604e66e9bb2f68b1fd9`
- `Main.kt`: `3f02a1638e7f2e5ca1e942c0046ff346042a73601bad3827bc330ba9a188688b`

Scope: current C implementation, including validation. I did not run tests. Reported test counts, namespace probes and live SDK evidence remain root-supplied results until recorded in the project evidence ledger.

## Outcome

The coordinated Java write path has no unsafe wrong-target, stale-write, lease, fencing, lock-order, commit, replay, heartbeat or cleanup path in the inspected source. Round-one findings are substantively resolved.

Two blockers remain for C enablement. `validate_project` lacks filesystem/resource isolation. Separately, root-reported official-SDK evidence shows the default preferred Joern backend cannot yet bind a simple analyzed method to the safe parser target, returning `target_not_found`. Do not call the live C gate complete until both are resolved or the affected capabilities are explicitly disabled.

## Blocking finding

### High — arbitrary validation commands escape the caller sandbox through the daemon

`validate_project` accepts an arbitrary command array from an authenticated agent. `ValidationRunner` launches it under:

```text
unshare --user --map-root-user --net --pid --fork --kill-child=KILL --mount-proc -- COMMAND...
```

This isolates network interfaces and process visibility, but it does not confine the filesystem to the captured scratch copy. The process retains the daemon user's host filesystem permissions and can use absolute paths or `..` from the scratch working directory to read or modify the original checkout, home directory, runtime descriptors, SSH/config files, and other user-accessible paths. Network namespaces also do not block access to reachable Unix-domain sockets. PID namespaces do not impose CPU, memory, process-count or file-size limits; a hostile command or build script can exhaust host resources before the 120-second timeout.

This matters even when the coding client itself is read-only or sandboxed: the MCP call crosses into a long-lived unsandboxed daemon, which then executes the agent-supplied command. The tool is therefore a sandbox escape by design, not merely a limitation of validation evidence. Clearing environment variables and excluding some copied secret filenames does not prevent direct host filesystem reads.

Actionable fixes, in priority order:

1. Immediately remove `validate_project` from `definitions()`, `validation_modes`, and `--allow-edits` sessions until isolation is complete. Keep plan/lease/apply usable without validation.
2. Run validation in a real filesystem sandbox. A suitable Linux implementation can use bubblewrap or an equivalent primitive with a new mount namespace, scratch as the only writable project tree, read-only minimal toolchain/runtime mounts, an empty/private home and tmp, no host runtime sockets/devices, no network, and a minimal proc/dev view. Fail closed if the sandbox is unavailable.
3. Apply resource controls outside the child namespace: cgroup v2 or equivalent CPU, memory, PID and output/file limits. Timeout/process-tree killing is still required but is not a resource sandbox.
4. Restrict commands to explicit project validation profiles selected by ID, rather than accepting arbitrary executables and shell payloads from the agent. This reduces direct abuse but does not replace filesystem isolation because repository build scripts are executable input.
5. Add negative tests proving a validation command cannot read or modify a known host file outside scratch, cannot connect to a host Unix socket, cannot see the original checkout path, and cannot exceed process/memory limits. The current loopback test proves only network-namespace separation.

### High — the default preferred backend does not yet produce edit-bindable method metadata

Root-reported official-SDK testing against the daemon's normal Joern-preferred analysis path found a simple `Counter.value` node, but `plan_edit` rejected it as `target_not_found`. The fallback/unit parser path passes. The exact mismatch—enclosing qualified name, normalized parameter types, start/end lines, or another exported field—was still being instrumented when this review was requested.

The failure is safe: `SafeJavaEditor.selectCandidate()` requires all metadata to agree and refuses to guess, so it did not modify the wrong method. It is nevertheless a live C blocker because the default daemon prefers Joern and cannot currently complete the advertised plan/apply flow on the tested fixture.

Actionable fix:

1. Capture the actual Joern `MethodInfo` fields and the Javac candidates for the failing fixture without weakening selection.
2. Normalize backend metadata at the adapter boundary into one edit-target identity: repository-relative file, binary/source enclosing type convention, method/constructor kind, erased or source parameter types, and parser-consistent half-open byte or exact line span.
3. Prefer a stable parser-derived disambiguation key/byte span stored in the snapshot over attempting increasingly permissive matching during edit.
4. Add the real preferred-backend fixture as a regression covering ordinary methods, overloads, nested types, CRLF and Unicode. Keep the fallback fixture separate so one backend cannot mask the other.
5. Rerun the official SDK plan/lease/apply flow under the actual default backend. Do not enable by falling back silently or dropping parent/signature/span checks.

## Round-one finding disposition

### Generic failures released every session lease — resolved

The generic failure catch no longer calls `closeOwner`. Cleanup is limited to a failed `apply_edit` and uses only the supplied lease ID/generation; explicit release, session close/expiry, bridge EOF and TTL expiry retain their existing paths. Saved regression coverage checks that an invalid read and an unrelated `lease_busy` failure preserve an existing reservation, that heartbeats renew it, and that session close removes it.

### Unknown/stale release semantics — resolved

The coordinator now maintains bounded 120-second tombstones keyed by lease ID with owner/generation. Idempotent release succeeds only for the recorded owner and generation; foreign and stale generations fail. Expired/unknown IDs fail rather than pretending ownership.

### Lease lookup/resource bound — resolved

An ID-to-file index replaces map scans, and fair semaphore admission caps active leases at 128. Tombstones are capped at 1,024. The acquire rollback returns the admission permit, and successful removal returns it after transition publication.

### Transition publication rollback — resolved

Renew restores its prior expiry if publication fails. Release/expiry remove internal state, call the transition, and restore the active lease/index without releasing admission if publication fails. Saved tests inject failures for renew and release and then verify the lease remains usable.

### Rejected-edit and plan-origin evidence — resolved

Rejected `plan_edit`/`apply_edit` calls emit `edit_rejected` with bounded failure code. Planned events and previews carry the edit ID and originating snapshot, and the authenticated `/edits/{id}` route exposes the retained bounded preview to agent/observer sessions. It does not make the preview public.

## Coordinated-write correctness confirmed

- Javac parser positions, enclosing type, name, normalized parameter types and exact line range select one concrete overload or fail closed.
- Full retained file bytes and hash form the plan preimage; disk bytes are checked during plan and twice before atomic replacement.
- Plan and lease must name the same file, preventing same-hash cross-file retargeting.
- Leases and plans are session-owned; generation and epoch fencing prevent foreign, stale, expired or restarted sessions from writing.
- Per-file mutexes serialize acquire/renew/release/expiry/apply. All state publication from coordinator transitions follows file mutex → state lock; state-driven cleanup releases the state lock before entering file mutexes.
- Expiry before commit entry rejects; expiry/EOF after final commit entry waits on the file mutex and cannot admit another owner before the atomic move completes.
- Atomic replacement preserves POSIX mode and fails if same-filesystem atomic move is unavailable.
- The filesystem commit occurs before event/index callbacks. A successful commit receipt and `edit_applied` event are installed immediately; later queue/index failure remains `committed=true` with failed/pending indexing evidence.
- Operation receipts are digest-bound and high-water protected. Exact replay cannot write or emit `edit_applied` twice.
- Bridge heartbeats renew owned leases within the absolute 120-second hold and bridge EOF closes the session, releasing leases.
- `--allow-edits` is explicit; default daemon mode remains read-only and capability responses reflect the selected mode.

## Validation integrity strengths

These controls are useful once execution isolation is fixed:

- Inputs come from tracked plus untracked nonignored Git files, with a bounded fallback walk.
- Files, total bytes, candidate count and Git enumeration output are bounded; symlink inputs and root escapes fail closed.
- Captured bytes and modes are hashed, copied, rehashed, and compared with a full source manifest before execution.
- A second source manifest after execution marks evidence stale when files are added, removed or changed.
- `.env`-style, PEM/key and credential-named inputs are excluded and cause incomplete scope rather than a clean full-scope claim.
- Environment inheritance is restricted to PATH/JDK/Kotlin variables, with private scratch HOME/TMP.
- Output is a bounded tail; exit code, timeout, command, declared mode, manifest, scope, exclusion counts, offline request and namespace state are explicit.
- Timeout and interruption attempt to kill the namespace process tree; network unshare fails closed.

## Remaining non-blocking validation limitations

### Secret-file classification is necessarily incomplete

The filename heuristic does not exclude common credential-bearing files such as `.npmrc`, `.pypirc`, `.netrc`, Maven settings, Gradle properties, cloud CLI configs or arbitrary project configuration. Filesystem isolation is the main protection; broaden deny rules and let projects explicitly declare required sensitive inputs as unavailable rather than copying them silently.

### Caller-declared mode is not proof of tests or compilation

An agent can submit `true` with `mode: test`; the result correctly calls this `declared_mode` and `command_passed`, but consumers must not turn that into “tests passed.” Prefer named, repository-derived validation profiles and display the exact command prominently.

### Client disconnect does not currently cancel validation

Timeout and thread interruption are handled, but a bridge/client disconnect does not propagate cancellation to an already running daemon request. It can continue until completion or 120 seconds. Add operation cancellation tied to session close/daemon shutdown after the execution sandbox is safe.

### API compresses exclusion detail

`ImmutableValidationEvidence` retains omitted/excluded diagnostics, while `validateProject` returns only their counts. Return bounded diagnostic names/reasons so a user can judge incomplete scope without consulting internal state.

### Toolchain provenance remains partial

The response admits that PATH/JDK binaries and external caches are not pinned by the source manifest. Record resolved executable paths and versions; mount dependency caches read-only in the future sandbox and distinguish unavailable dependencies from test failures.

## Evidence limits

Saved tests cover the C correctness cases and eight validation behaviors, including full-manifest drift, added-file staleness, symlink rejection, bounded output, exit status, timeout and host-loopback isolation. The reported 44/46 followed by corrected HTTP fixtures and passing focused tests was not independently rerun here. The temporary live SDK C test was still running when requested and is not evidence used by this review.

The current suite does not falsify host filesystem escape, Unix-socket access or resource exhaustion. Those negative tests are required before enabling `validate_project`.
