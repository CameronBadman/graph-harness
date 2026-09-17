# Coordinated Java edits implementation rereview — round 3

Review date: 2026-09-18

Inspected implementation hashes:

- `SafeJavaEdit.kt`: `98444a6a6860a0ad9493a95d1e45b23fc8b5b6ff1d53bda70e36aa193c608335`
- `EditCoordinator.kt`: `c53a3fde35ab43346ba295fc585cd7f8f30a927aaea819f9a1b15384637fca03`
- `LiveEdits.kt`: `4cc9b01ed05b7f63d8ab3eeea724672db230e1b3204067efa5d7f5e135361461`
- `LiveState.kt`: `66bb73bec95385838c1e53321fb927c78aca51f0ffb1a25f8dbee88231c09e74`
- `JoernAdapter.kt`: `440357860fa942ba4c7b7269ccc47b20a06b3b0c75c44335cbc219860b67be37`
- `ValidationRunner.kt`: `9bf53c738e8dc781399f341b6227c24dba1946206f44f4886224c1b811b7247d`
- `docs/live-contract-v1.md`: `ab4d91036e54377dde2299ecaca411eb0c63eba185221528290df0843684f194`

Relevant saved test hashes:

- `SafeJavaEditTest.kt`: `60971f836f3f45cc870083c74e68ea87be5bd690b98be112bca2ed4aff6432c5`
- `ValidationRunnerTest.kt`: `e82b3c057305eee3fa684531f34a9fe9ce63ec94805b000b25d3dce057847766`
- `LiveStateTest.kt`: `34ae26b882fc7021ee4c7d8ffc0c6138810250391807f311f3a7f736629adeca`
- `LiveEditsTest.kt`: `6dc2c29effa61d7c5a73bc9bc88b8b8e2a1e7464c6ab66d0c8e74b17a167a89f`
- `EditCoordinatorTest.kt`: `29edcba652c3b7c78e59e513d50593bf4276401d2a3455829bffd5802e50ed45`
- `CoordinatedDaemonTest.kt`: `c14731f8f8369b1282cb3130fa1f4e47a05a1d5ced0d05ac4a579f7ca5c33334`

Scope: the C coordinated-edit milestone in its explicit local `--allow-edits` mode. I did not run Gradle, the preferred-backend fixture, bubblewrap probes, or the SDK flow. Test and live-flow results reported by root are not independently claimed here.

## Outcome

I found no remaining source-level blocker in the inspected C implementation. Both round-two blockers are addressed in the saved code without weakening the safe edit selector or overstating validation isolation. C should still remain unaccepted until the frozen source passes the pending full test run, the real Joern-preferred regression, and one fresh official-SDK two-client flow. Those are evidence gates, not additional design work.

## Round-two blocker disposition

### Arbitrary validation escaped through the daemon — resolved in source, runtime gate pending

`ValidationRunner` now fails closed unless `/usr/bin/bwrap` and `/usr/bin/prlimit` are executable. It launches the selected command under a new user, network, PID, IPC, UTS and cgroup namespace; exposes only the captured scratch tree as writable `/workspace`; supplies private `/home` and `/tmp`; mounts `/usr` and accepted external Java/Kotlin homes read-only; clears the environment; uses UID/GID 65534; and does not mount the original checkout or host runtime directories. The implementation retains the copied-input manifest checks and secret/symlink rejection.

The runner applies inherited per-process address-space, CPU, process-count, open-file, output-file and core limits, bounds captured stdout/stderr, admits one validation at a time per daemon, times out execution, and kills the visible process tree. The API accurately reports the limits and explicitly says there is no delegated writable cgroup for aggregate tree memory or CPU enforcement. I therefore do not treat the absence of a cgroup as a blocker for this local opt-in developer tool; the implementation no longer claims tree-wide resource accounting.

Saved negative tests attempt to falsify host-file/original-root/Unix-socket access, host-loopback access, memory limits, process limits and timeout handling. Their current execution result remains a required gate because this review did not run them and bubblewrap behavior is kernel/environment dependent.

### Preferred Joern metadata could not bind safe edit targets — resolved in source, integration gate pending

The Joern export now obtains the enclosing type by traversing `m.typeDecl`, exports `lineNumberEnd` with an AST-line fallback, and retains parameter/signature metadata. `SafeJavaEditor` still requires exact parser-confirmed enclosing type, method name, normalized parameters, and start/end lines. It accepts both source nested names (`demo.Outer.Nested`) and binary nested names (`demo.Outer$Nested`) rather than dropping any disambiguation check.

The saved preferred-backend fixture covers an ordinary method, primitive overloads, a nested class, CRLF, and non-BMP Unicode, and applies the parser plan to every discovered `value` method. This directly targets the previously observed `Counter.value` failure. Because the test selects Joern only when installed and its latest binary-name fix had not completed its rerun when assigned, the blocker is resolved at code-review level but not yet at acceptance-evidence level.

## Lifecycle and commit rereview

No regression was found in the coordinated-write invariants:

- Failed `apply_edit` cleanup is limited to the supplied lease ID and generation. Other tool failures do not release unrelated reservations.
- Session close and expiry interrupt an active validation, then perform lease cleanup outside the state lock. `ValidationRunner` converts the interruption to `validation_cancelled` and forcibly terminates the process tree.
- File operations retain file-mutex → state/journal-lock order. State-driven close/expiry leaves the state lock before entering coordinator file locks.
- The atomic filesystem replacement precedes the commit callback. The callback records the receipt before publishing `edit_applied`; if later event/index handling fails, replay still returns `committed=true` and does not write again or run failed-apply lease cleanup.
- Exact receipt replay remains digest-bound and high-water protected. A plan is owner-bound, one-file-bound, preimage-bound, and cannot be applied under a same-hash lease for another file.
- Joern subprocess output retained in memory is capped at 1 MiB while the reader continues draining the pipe, execution has a 120-second bound, and live descendants are forcibly destroyed on timeout/interruption.

## Remaining acceptance gates

These are required before marking C complete; none requires broadening the milestone:

1. Record a clean full Gradle result from the exact frozen source. A passing earlier subset is insufficient after changes to Joern export, cancellation and validation isolation.
2. Record the preferred-backend test on a machine where Joern is actually detected, including the backend assertion and all four method plans. A fallback-only pass does not close the prior live failure.
3. Repeat the official-SDK two-client flow against the default preferred backend: plan, acquire/conflict, foreign rejection, apply, reread, and real Java red/green assertion. The earlier successful flow used the older validation runner and does not independently prove the new sandbox.
4. Run and record the bubblewrap negative tests on the supported Linux environment. If user namespaces or bubblewrap are unavailable, verify that `validate_project` returns `validation_blocked`; it must not fall back to unsandboxed execution.
5. Preserve the API wording that limits are per process and validation mode is caller-declared. Do not present `command_passed` as proof that repository tests ran without showing the exact command and manifest.

## Residual limitations, not C blockers

- RLIMIT address-space and CPU bounds are inherited per process, not aggregate cgroup budgets. A process tree can consume more aggregate memory/CPU than the per-process values. The response discloses this.
- The source manifest does not pin read-only toolchain binaries or dependency caches. The response discloses toolchain mount scope and this provenance limitation.
- Validation remains Linux-specific and requires bubblewrap/prlimit. Failing closed is acceptable for the current local prototype; installation and troubleshooting documentation should state the requirement.
- Filename-based secret exclusion cannot recognize every sensitive project file. The filesystem sandbox prevents captured commands from reaching uncopied host files, while included source remains intentionally available to the validation command and its authenticated caller.
- The Joern regression is environment-conditional. CI or release evidence should include one explicit Joern-enabled lane so fallback analysis cannot mask future adapter drift.

## Integrity statement

This review evaluates source correctness and saved test intent, not product completion. I did not execute tests or reproduce root's reported SDK and sandbox results. No passing result should be attributed to this review until the exact hashes above are matched to recorded command output.

Integrator transcription correction: mechanically recomputed all listed artifact hashes. Corrected the recorded digest for `EditCoordinator.kt`. Review findings are unchanged.

## Root-reported acceptance addendum

After this source review, root reported a frozen-source Gradle run of 80 tests with zero failures, errors, or skips in 2m49s; five preferred `SafeJavaEdit` tests passing with four Joern targets; all 11 `ValidationRunner` tests passing; and a fresh official-SDK red/green flow passing against the new runner. I did not execute or independently inspect those command records. Root also reported that an additional filesystem/limit assertion was still running, so this review does not claim that result.
