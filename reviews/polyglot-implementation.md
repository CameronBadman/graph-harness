# Polyglot structural navigation and packaging review

Review date: 2026-09-18

Scope: D structural adapters and distribution wiring in `Analyzer.kt`, `Model.kt`, `StructuralAdapters.kt`, `JavaStructure.kt`, the Python and TypeScript helpers, server/live metadata, `Main.kt`, and `build.gradle.kts`. I did not run Gradle, `installDist`, helper probes, or the SDK flow. Root-reported results are identified separately and are not independent evidence from this review.

## Outcome

I found no blocking security or correctness defect for the advertised D scope: Java plus TypeScript, JavaScript and Python structural navigation, exact retained-source spans, parser-observed containment, and honest capability degradation. The implementation does not invent cross-language calls or imply semantic edit support for structural adapters.

Release acceptance still depends on the frozen targeted tests and installed-distribution checks that were running when this review was requested. The earlier mixed-language SDK pass preceded the latest Java overlay and parser-discovery changes, so it should be repeated against the final artifact.

## Correctness findings

### Exact source identity and spans

- Source capture retains immutable UTF-8 bytes and a SHA-256 hash. Helper requests include the retained text and hash; responses are accepted only when both echoed and independently computed helper hashes equal the retained source hash.
- Python uses AST line/column positions, whose columns are UTF-8 byte offsets, combined with byte-indexed line starts. TypeScript converts compiler UTF-16 positions to UTF-8 offsets with `Buffer.byteLength`. CRLF and non-BMP text are therefore handled in their native position domains before producing the common half-open byte span.
- Kotlin validates nonnegative, ordered spans within the retained byte size. `get_source` with no context slices the retained bytes directly, rechecks live disk bytes against that retained version, and returns snapshot ID, file hash, language, provenance, and span.
- Java overlay positions come from Javac, are converted from UTF-16 indices through explicit UTF-8 boundaries, and attach only when enclosing type, method name, normalized parameters, and exact line range identify one existing Java node. It does not replace existing semantic node IDs.

### Node identity and containment

- Structural helper identities include repository-relative file, enclosing declaration identity, declaration kind/name, and a same-scope ordinal. Duplicate overload-like declarations and repeated nested Python definitions therefore remain distinct.
- Parent links use helper-issued parent identities. File nodes provide a fallback root, and emitted containment edges are marked `parser_observed` and `exact`.
- Java anonymous classes with empty names are skipped as structural overlay subtrees. This avoids attaching an anonymous same-name method to the enclosing named method. It sacrifices some anonymous-class visibility rather than guessing.
- The merged graph contains Java nodes plus structural nodes. Existing edges are retained only when both endpoints are Java; non-Java adapters add containment only. No same-name or cross-language call edge is fabricated.

### Failure and rebuild behavior

- Syntax errors produce diagnostics and no definitions. Missing helpers, process failures, timeouts, oversized output, invalid JSON, and hash mismatches produce unavailable adapter results with no structural claims.
- Captured source count, per-file bytes and total retained bytes are bounded. Helper output is capped at 2 MiB, definitions at 10,000, diagnostics at 100, each helper call at five seconds, and the per-build adapter loop has a 30-second admission deadline.
- The adapter cache keys language, path, and source hash. Rebuilds reuse unchanged results and evict entries no longer represented by captured sources. Watcher support covers all advertised extensions and the serialized snapshot publication path retains the existing dirty-generation guarantees.
- Adapter availability, parser/version, structural capabilities, diagnostics, omitted counts, and source-admission diagnostics are exposed rather than collapsing degraded analysis into a healthy semantic backend.

## Packaging and trust boundary

- The distribution copies built UI assets and the parser directory, including the locked TypeScript 5.9.2 dependency. Installed lookup derives `ui` and `parsers` from the loaded code artifact location, not the analyzed repository or current working directory.
- Project files named like parser helpers are not discovered or executed merely because the daemon starts in that project directory. Explicit `GRAPHHARNESS_PARSERS_DIR`, `GRAPHHARNESS_TYPESCRIPT_PATH`, `GRAPHHARNESS_NODE`, and `GRAPHHARNESS_PYTHON` overrides remain an operator trust decision.
- Helper paths are passed as argument-vector entries, not through a shell. Repository filenames and source are JSON data on stdin. Helper output is parsed as bounded JSON and cannot directly mutate graph state outside the decoder's validated fields.
- The TypeScript dependency is exact-version pinned in both package metadata and lockfile. Installation/release evidence should continue to use `npm ci`, not an unconstrained install.

## Non-blocking limitations

### Effective helper input ceiling is lower for escape-heavy files

Source admission permits a 1 MiB file, while each helper permits a 1.25 MiB JSON request. JSON escaping can expand source substantially, so a valid admitted file containing many quotes, backslashes, or control characters may be reported unavailable even though its raw bytes are below 1 MiB. This fails safely and produces a diagnostic, but the documented supported-size expectation should use the effective serialized limit or the protocol should send length-delimited raw source separately. Raising the helper request cap to a bounded worst-case JSON expansion is the simplest fix.

### Overall build deadline is an admission deadline

The 30-second deadline is checked before each file. A helper admitted just before it expires may consume its separate five-second timeout, so wall time can exceed 30 seconds by roughly one invocation. Describe it as an admission budget, as the diagnostic already does, or pass the remaining duration into the helper timeout if a hard wall-clock bound is required.

### One daemon serializes helper calls, but the bound is per manager

Holding the adapter-cache monitor through invocation serializes structural helper processes for one `SnapshotManager`, providing a simple admission limit. Multiple daemon/manager instances can each run a helper, and there is no machine-global quota. That matches the one-authoritative-daemon-per-checkout architecture and is not a distributed resource guarantee.

### Structural nodes are not incorporated into Java package clusters

The merge adds structural nodes and containment but retains the Java-derived cluster model. Search, source, node detail and context work; summary package/type/method totals and clusters may underrepresent non-Java structure. The capability list does not promise polyglot clustering, but the UI and README should avoid presenting Java-derived totals as whole-repository polyglot totals. A later release can build language/directory clusters over all nodes.

### Helper availability is cached until source change or restart

An unavailable result is cached by source hash. Installing a missing interpreter/dependency during a running daemon does not retry unchanged files. Restarting or changing the file recovers it. This is acceptable for startup-configured local tooling but should appear in troubleshooting guidance.

### Structural coverage is deliberately partial

TypeScript destructuring declarations, anonymous declarations, dynamic Python constructs, imports, calls, and runtime cross-language relations are omitted. Java anonymous class internals can also be absent from the exact-span overlay. These omissions are preferable to false graph edges and match the declared structural-only capability.

## Required release evidence

1. Record the current frozen targeted test run after the corrected JavaScript fixture and latest anonymous-class overlay. It must include exact UTF-8/CRLF source slices, duplicate identities, containment endpoints, syntax-failure diagnostics, watcher updates, and the no-fabricated-edge assertion.
2. Run `installDist` from a clean environment and launch the installed binary from a directory containing malicious lookalike parser files. Confirm the graph uses the packaged helpers and pinned TypeScript runtime.
3. Repeat the official-SDK mixed Java/TypeScript/JavaScript/Python flow against that installed artifact, verifying source/hash/span consistency, containment, duplicate declarations, context labeling, and watcher refresh.
4. Record graceful degradation separately by making Python or Node unavailable and confirming adapter metadata/diagnostics show unavailable with zero definitions. Do not count a machine with missing runtimes as a four-language success.
5. Confirm the final graph contains no non-Java `calls`, `implements`, `extends`, or `uses_type` edges unless a future adapter adds independently evidenced semantics and updates its capability contract.

## Root-reported evidence available at review time

Root reported an initial source-first compile with 20 targeted tests yielding 19 passes and one fixture expectation error, then corrected the JavaScript expectation. Root also reported an independent SDK mixed-language flow passing source, containment, context, duplicate, CRLF/emoji, and watcher checks before the latest Java-overlay and trusted-parser changes. The current targeted tests and `installDist` were still running. These reports support the direction but do not close the required final-artifact gates, and I did not execute or inspect their raw command records.

## Integrity statement

This is a source and test-design review, not proof that the distribution currently installs or that all advertised runtimes exist on a clean machine. No runtime result is independently claimed here. The attractive four-language demo remains contingent on final installed-artifact evidence and must retain honest per-language capability labels.
