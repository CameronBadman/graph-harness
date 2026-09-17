# Final bounded release source review

Review date: 2026-09-18

I reviewed the frozen source while root's integration tests were running. I did not run Gradle, browser automation, `npm audit`, the demo, or performance measurements.

## Exact inspected hashes

- `src/main/kotlin/graphharness/Analyzer.kt`: `f58c9aa0346bebe2899edbc75d5c623d2defca5aeb4a3c6bb60faf1cb0216662`
- `src/main/kotlin/graphharness/JavaDeclarations.kt`: `ebfc06261424c38b646b59777db7813d867a01a98e65e46d711ec74993d1a162`
- `src/main/kotlin/graphharness/JavaStructure.kt`: `54cf72d410a2fa949a7f50abed26929743982a198c399ecf4b469bfd24301709`
- `src/main/kotlin/graphharness/StructuralAdapters.kt`: `b2e055fc32e5bf97665ea5ee7abe02e34d7958556de0206ddc562be921c19c9f`
- `src/main/kotlin/graphharness/Server.kt`: `aa01462aeae7eaea4263a2ce323bb49766d8628211fbe7630af92f2acef86e6d`
- `src/main/kotlin/graphharness/LiveState.kt`: `317feeb527eb946a11490e25d99b2a55658640d0c45ddac7f49d049d72e5fa90`
- `src/main/kotlin/graphharness/LiveDaemon.kt`: `8ba837b8c65a88f6d73e83c44f2223260aca1e763f80ac87b6904a8759af5077`
- `src/main/kotlin/graphharness/Main.kt`: `828ac8582c5e01f3fde3039376956eec4adbc2f23f06d0719b2496d06be3e95e`
- `src/main/kotlin/graphharness/Model.kt`: `992df230fec5376dad27d03e104c99b5f60099ceba29787098504f788e1c6e18`
- `ui/src/main.ts`: `9e13046f16b60a3bf09066f7d466ae28cd6fc89fd483eb76466b7488d12ce6d6`
- `ui/src/style.css`: `10c2be9afabf4d1781961160da569515116135f3bb93847e846eb89cc8d3ca0c`
- `build.gradle.kts`: `3ac2c9b7e709881bd61545900dfee93b5822675eab1167486fcc74de8fd84c53`
- `parsers/package.json`: `1e72d6ec3e51d9a6021b32a65cd53523d6ee6881df14abfe590ce6cc85729627`
- `parsers/package-lock.json`: `78a8000f686128608cf1849032094db25f43996b1e6adbfe9777fe3cff0b3c84`
- `parsers/python_ast_parser.py`: `7ac5131fb31bccff137b3330b7fe3d3b7ff95f41fccba4b8ee8e310ff615cf65`
- `parsers/typescript_compiler_parser.cjs`: `71207a57b1686bc8a9fa3a43d71c5cbc1f49d580bba532cbbeb4c7c6219deeb3`

## Release blockers

### High — retained-cursor reset can loop forever within the same daemon epoch

`consumeSse()` handles an SSE `reset` by calling `refreshState(true)`. A 409 from `/events` takes the same path. `refreshState()` updates `lastDeliveredCursor` only when the epoch changed or the local cursor is null. A journal-retention reset normally occurs within the same epoch with a non-null but obsolete cursor, so the subsequent `subscribe()` sends that obsolete cursor again. The daemon can respond with another reset, creating a reset/fetch/resubscribe loop without restoring live activity.

This violates the explicit reconnect contract: when the requested cursor is no longer retained, the browser must discard the obsolete cursor and resume from the authoritative state cursor.

Recommended fix:

1. Give `refreshState` an explicit authoritative-reset mode. After fetching `/state`, set `lastDeliveredCursor = fresh.cursor` before subscribing, even when the epoch is unchanged.
2. Clear or label the retained activity timeline on reset because it is no longer a contiguous replay. Do not overlay pre-reset events as if no gap occurred.
3. Alternatively consume the reset event's cursor only if the server contract guarantees it is the same atomic state cursor; fetching authoritative state remains safer.
4. Add a browser test that starts with a cursor older than the journal ring, receives reset, reconnects once using the current cursor, then receives a new event exactly once.

### High — stream fetch/read failure refreshes state but does not restart SSE

In `subscribe()`'s catch block, the browser schedules `refreshState()` with the default `restartStream = false`. With an existing same-epoch state, `refreshState()` does not call `subscribe()`. Therefore a rejected fetch or stream read error changes the badge to reconnecting, refreshes the snapshot once, and can leave activity streaming permanently stopped. Normal clean EOF schedules `subscribe()` directly; the exceptional path does not.

Recommended fix:

1. On a transient stream exception, call `refreshState(true)` or schedule `subscribe()` after the state refresh completes.
2. Preserve `lastDeliveredCursor` for ordinary transient reconnects so retained events are replayed; use the authoritative reset behavior above only after reset/409.
3. Ensure one reconnect owner at a time so error timers cannot replace a newer stream. The existing controller identity check is a suitable guard.
4. Add browser tests for initial fetch rejection and mid-stream read failure. Each should reconnect from the last delivered cursor, receive the missed retained event once, and return the connection badge to live.

## Reviewed surfaces without a blocker

### Fallback Java AST and exact overlay

The fallback now derives declarations from Javac AST rather than brace/name regexes. Named classes carry lexical enclosing names; concrete non-constructor methods require a named enclosing type and use parser ranges, parameters, return type and AST source. Anonymous classes are skipped as subtrees, so an anonymous same-name method cannot overwrite the enclosing method. The structural overlay attaches byte spans only when file, source/binary enclosing name, exact line range, method name and normalized parameters select one existing node. Failure omits the overlay instead of guessing.

### Non-Java semantic guards and honest summaries

Call paths, callers, callees, implementations, type hierarchy, dependencies and impact all call `requireJavaSemantic`. Edit verification/planning/validation targeting are guarded in dispatch and again in manager edit paths. Non-Java node detail exposes only edges actually present, which are containment edges. Context bundles label `structural_context_only`.

`ProjectSummary` now reports `structural_nodes_by_language` and an explicit `semantic_summary_scope` stating that package/type/method totals, clusters, entrypoints and hotspots are Java-only. This resolves the earlier risk of presenting Java semantic aggregates as whole-repository polyglot totals. Non-Java file nodes now carry the exact whole-file byte span.

### Trusted packaged asset discovery

Default UI and parser directories derive from the loaded code artifact location. Installed layout resolves sibling `ui` and `parsers` directories; development lookup searches ancestors for the source tree. The analyzed repository and current working directory are not searched for lookalike helpers. Parser scripts are invoked as argument-vector paths and receive source as JSON on stdin. Explicit environment overrides remain operator-controlled configuration.

The helper JSON ceiling is now 6.4 MB, sufficient for worst-case JSON escaping of an admitted 1 MiB UTF-8 source while remaining bounded. TypeScript remains exact-version pinned in package metadata and lockfile.

## Evidence and release limits

- Root reported that the full frozen Gradle suite was running. This review makes no claim about its result.
- Root reported earlier activity latency of 83 ms p95 and a graph-refresh pilot above the three-second target. The latency result does not cure the two reconnect defects. The refresh miss should be reported as a performance limitation rather than hidden or reframed as a pass.
- A recorded real-agent demo and final installed-artifact tests were still underway. Source review cannot substitute for them.
- Network-backed npm audit was unavailable. An earlier offline result is not fresh registry evidence and should not be represented as such.

## Release decision from source review

Do not call the live observer reconnect contract complete until both UI cursor/restart defects are fixed and exercised with forced reset and transport-failure browser tests. I found no other blocker in the bounded source surfaces reviewed here.

## Integrator disposition after review

This addendum is by the integrating agent, not a further independent review. The delegated UI worker saved initial fixes before hitting its usage limit; the integrator completed layout and recovery-test work.

- Explicit reset/409 uses an authoritative cursor reset and clears the history with a visible gap notice. Ordinary transport errors preserve the delivered cursor and restart the stream. Timer callbacks check their originating controller before acting.
- `reviews/stream-recovery-evidence.json` records actual passing browser checks for mid-stream read failure, clean EOF, rejected fetch, server SSE reset and server epoch409. These use test-only transport injection and real server events; checked events each rendered once.
- File groups now use actual file parents with seeded leaf positions. Zoom is bounded, and layout spacing accounts for group sizes. Mixed-language source and coordinated preview/conflict checks passed again; integrator inspected both screenshots.
- `reviews/release-artifact.json` names the final UI/distribution hashes. JVM sources remain byte-identical to the 88-test pass.

The two cited defects are resolved in the checked scenarios. There is no claim that the original reviewer independently rereviewed the later fixes or that finite tests prove every network interleaving.
