# Development status

Updated 2026-09-18. Local implementation and verification; nothing pushed, deployed or submitted. Scores below evaluate planning, not software quality or competition prospects.

## Codex token-efficiency investigation

The [fresh follow-up](reviews/token-fix/FINDINGS.md) completed **24 scored runs and
four calibrations**, with all outcomes correct and all independent access reviews
passing. The repaired full tools used **46.1% more input / 38.2% more output** than
native; the optional navigation profile used **18.2% more input / 16.9% more output**.
The preserved old build used 7.8% more input / 20.5% more output on these same tasks.
No scored attempt was excluded or retried. The registered efficiency gate failed.

Qualified target/path resolution, explicit ambiguity, code-mode discovery guidance
and an optional compact four-tool bridge are implemented. Both repaired arms used
retrieval in 6/6 runs; the preserved old arm used it in 0/6. Mixed-language behavioral
queries still returned no source, and Java navigation bundles did not eliminate
subsequent source inspection. Repair shell edits/tests are expected and are not by
themselves failed retrieval. The full bridge remains default; do not advertise a
token-saving benefit. Results apply to small generated fixtures, with uncontrolled
provider caching and differing integration guidance.

The current JVM suite passed **97 tests**, and the follow-up benchmark passed
**23 tests**. [Verification receipts](reviews/token-fix/checks.json) and the
[independent review](reviews/token-fix/INDEPENDENT_REVIEW.md) bind the results.

### Earlier pilot

The [token-ablation findings](reviews/token-ablation/FINDINGS.md) record 36 actual
Codex task runs and four separate integration calibrations. All tasks passed their
external checks; 35 runs passed independent access review, with one retained
exclusion. The product was frozen throughout. Twelve benchmark unit tests pass.

This pilot does **not** establish token savings. Default MCP adoption was 0/18;
conditional graph-first guidance produced only 2/9 uses, both followed by shell
fallback after wrong/empty bundles. Those findings motivated the follow-up above.
Cached input is reported separately; these counters
are not a monetary-cost or subscription-quota estimate. The generated fixtures
are small development cases, not large-repository or multi-agent evidence.

## Completion ledger

| Milestone | State | Evidence and limits |
| --- | --- | --- |
| A baseline and MCP | Verified | Original HEAD `2c775198edff0e48588b150571751f3bdbada0db`; 12 original Joern tests passed. Standard newline MCP and explicit legacy framing; official SDK initialization/list/call/error checks passed |
| Development plan | Independently reviewed | Delegated Sol review: 92 → 95 with blockers → 98/100 without planning blockers. Actual reviewed hashes and all rounds retained under `reviews/plan-round-*.md` |
| B shared daemon and live UI | Implemented and verified | Canonical root ownership, private discovery, authenticated per-bridge identities, local browser pairing, retained events, bounded graph and observed source/tool activity |
| C coordinated Java edits | Implemented and verified | Parser-confirmed single-method plans; file leases, expiry/fencing, owner checks, stale-byte rejection, atomic replacement, committed receipts and preview inspection. Real two-client conflict and actual-agent contention verified |
| D structural languages | Implemented and verified | Java, TypeScript, JavaScript and Python. Exact UTF-8 spans where parser metadata matches, containment, source/context, syntax diagnostics and create/change/rename/delete checks. Non-Java semantic calls and edits unavailable |
| E packaging | Implemented and locally verified | Distribution bundles UI and pinned TS parser. Copied installation with spaces, unrelated working directory and empty application config passed; missing Python/Node degrade honestly. This is an existing Linux host, not a fresh OS |
| E public assets | Prepared locally | README, public mixed fixture, static landing page and unpublished launch copy. Recorded public-fixture browser screenshot distinguishes scripted clients. Completed real-agent recording: 97.28s raw, 88.48s presented at 1.1×. Real conflict, guarded repair, release and fresh reader acquisition; fixture changed from expected failure to passing |
| Publication | Not performed | No push, deployment, Product Hunt submission or eligibility claim |

## Earlier integrated release build

`nix develop --offline --command gradle test installDist --console=plain` passed in **2m23s: 88 tests, zero failures/errors/skips**. `reviews/release-jvm-evidence.json` records source, helper, UI and distribution hashes and per-suite totals. That artifact supersedes the earlier C-only build (80 tests, recorded separately).

Environment: Linux; host JDK 21.0.11 and Nix-shell JDK 21.0.10; Gradle 8.14.4 through Nix; Kotlin 2.0.21; Joern 4.0.520 / CPG 1.7.62. TypeScript compiler 5.9.2; the official-SDK run reported Python AST 3.12.13 (host Python command: 3.13.12). Node 24.14.0 and npm 11.9.0. Browser checks use Playwright 1.63.0 and installed Chromium 145.0.7632.6. Runtimes on another installation can differ; capabilities expose the versions actually used.

## Evidence and interpretation

- `reviews/coordinated-sdk-evidence.json`: two official SDK clients, lease conflict and foreign-owner rejection, actual failing Java assertion repaired into passing tests on different captured manifests. Scripted clients are not autonomous agents.
- `reviews/real-agent-navigation.json` and `reviews/real-agent-coordination.json`: actual Codex CLI processes with distinct, overlapping server-issued identities. The coordinated run observed acquire → denial → commit/release → fresh read/acquire. Configured model `gpt-5.6-terra`; provider-side model identity is not independently attested.
- `reviews/coordinated-browser-evidence.json`: actual scripted plan/conflict/diff/apply/release in a paired browser; 1440×900, 1280×800 and 390×844 without overflow or page errors; HttpOnly/SameSite cookie and no credential in localStorage.
- `reviews/mixed-sdk-evidence.json`: official SDK on the installed four-language artifact; exact retained byte/hash/span checks, parser diagnostics, nested identities, bounded context and watcher create/change/rename/delete.
- `reviews/installation-evidence.json`: copied install, empty application configuration, paths with spaces, bundled discovery outside the checkout and missing-runtime degradation.
- `reviews/release-coordinated-sdk-evidence.json`: integrated artifact repeat of actual failing-to-passing tests and two-client contention.
- `reviews/mixed-browser-accepted.json` and `reviews/coordinated-browser-accepted.json`: final UI repeats passed after source/visual fixes; separate groups, bounded zoom, source, conflict, actual diff and target viewports.
- `reviews/stream-recovery-evidence.json`: browser read failure, clean EOF, fetch rejection, server SSE reset and epoch409 recovery passed with checked events rendered once.
- `reviews/mixed-browser-evidence.json`: historical installed artifact before final UI fixes; TypeScript/JavaScript/Python filters, keyboard selection and source inspection. No agent-work claim follows from this browser script.
- `reviews/performance-pilot.json`: a pre-final artifact indexed 1,001 method nodes in 6.82s and displayed an externally changed snapshot after 7.72s. Activity DOM insertion p95 was 83ms over 30 actual scripted calls. The three-second refresh target was missed. DOM timing is not compositor paint timing. Offline emulation did not close SSE, so its reconnect probe was inconclusive; a deterministic transport cutoff is being checked separately.
- Independent source reviews are retained in `reviews/read-only-implementation*.md`, `reviews/coordinated-edits-round-*.md` and `reviews/polyglot-implementation.md`. A favorable source review never substitutes for runtime evidence.

Final performance (`reviews/performance-accepted.json`): **72ms activity DOM p95** across 30 scripted calls on the 1,001-method fixture; initial indexing **6.87s**, external edit to updated graph **7.64s**. The 500ms activity target passed; the three-second indexing/refresh targets failed. The host had 32 reported CPUs and 30.39GiB RAM. This is one local synthetic run, not a cross-machine guarantee.

`./scripts/build_live.sh` also completed the documented setup route using locked npm installs and Gradle packaging; the unchanged JVM test task was up to date. Final UI source and distribution hashes are in `reviews/release-artifact.json`.

The final recording passed with two real Codex clients, distinct sessions, healthy monitoring and exit codes 0/0. Its ordered events were writer acquire18 → reader denial32 → writer apply39/release43 → fresh reader source → acquire56/release59. Validation lifecycle changed from failed12 to completed47; an independent fixture execution also passed after the repair. `reviews/recorded-demo-evidence.json` records configuration, scope and media provenance.

## Delegation and ownership

Astra handled integration, authoritative live state, build packaging and acceptance. Delegated Terra agents delivered protocol/daemon/bridge work, snapshot/source safety, structural adapters and Java fallback parsing, validation isolation, browser UI, browser/performance checks and real-agent recording. A separate Sol agent critiqued the plan and implementation. The UI worker later hit an account usage limit; the integrator completed and verified its saved reconnect/layout changes locally. Shared source was handed back and frozen before Gradle runs; no concurrent Gradle builds were used for acceptance.

## Negative results and corrections

- The first recording attempt failed before agent launch because the recorder created a fresh browser context without completing pairing. The corrected run waits for the cookie claim and retains a second already-authorized page. Failed pairing videos were deleted; `reviews/recording-attempts.json` records the cause.

- Final source review found two browser reconnect failures despite earlier happy-path checks: explicit same-epoch reset retained an obsolete cursor, and a stream error did not restart subscription. Final visual inspection also caught overlapping real file leaves and excessive zoom. The fixes passed forced reset/transport-error checks and fresh screenshot review. The original independent review is preserved with a clearly attributed integrator disposition.

- The first real-client navigation run did not overlap. It remains in `reviews/real-agent-navigation-sequential.json`; only the subsequent concurrent run supports an overlap claim.
- Two initial contention runs lost monitor liveness because the observer did not heartbeat. `reviews/real-agent-coordination-incomplete-observability.json` retains them. The monitor was fixed; absent captured activity was not treated as proof the agents failed.
- Joern initially exported a traversal string as the enclosing type and omitted the closing method line. Safe planning rejected it. The exporter was corrected without weakening target matching, and nested/overload/Unicode/CRLF cases passed.
- Structural integration exposed duplicate nested identities, parser timeout handling and fallback Java nested-owner metadata errors. The producer/parsers were corrected; strict byte/hash/span checks were retained. The final suite includes the corresponding regression fixtures.
- Test harness assumptions were corrected where they contradicted actual contracts: unsupported search arguments, missing epoch/session fields, obsolete JavaScript labels and expectations about trailing newlines outside exact declaration spans. These were not reasons to relax production validation.
- Fresh npm advisory checks for UI and parser dependencies failed because the advisory endpoint was unavailable. An offline audit result is not evidence of a current vulnerability-free dependency tree. Exact dependency locks remain checked in.

## Material boundaries

- Supported release environment is local Linux/POSIX with JDK 21, Node and Python. No cross-platform or new-OS installation guarantee has been established.
- Reservations protect cooperating writes through this daemon. Arbitrary external editors can still race; separate-file edits can remain semantically incompatible. No distributed coordination or multi-file atomic refactor is claimed.
- Safe live writes are Java method-body replacements only. Legacy direct-stdio regex rename/body edits remain experimental and are excluded from live coordination.
- Validation captures working-tree inputs and manifests, then uses bubblewrap filesystem/user/process/network isolation and per-process `prlimit` ceilings. It fails closed when unavailable. It has no aggregate cgroup budget; external toolchain mounts are read-only but not pinned by the source manifest. Command/mode alone does not prove meaningful test coverage.
- Java calls remain backend-dependent. Non-Java adapters provide definitions/containment, not imports, resolved calls, inferred runtime links or semantic refactoring. Anonymous/dynamic constructs and some destructuring are omitted. Missing/unmatched spans stay diagnosed rather than invented.
- Legacy summary package/type/method totals, entrypoints, clusters and hotspots cover Java; separately labeled structural counts cover all parsed languages. Parser availability failures are cached until source changes or daemon restart.
- Structural helpers have bounded input/output and five-second deadlines, with a 30-second per-build admission budget. Java commands have their own 120-second deadline; no hard global indexing deadline or three-second refresh guarantee is claimed.
- Historical replay is an activity timeline, not exact reconstruction of old graph topology. Source/prompt/credential data is excluded from event journals by default.
- No token-saving, semantic-safety, competition eligibility or provider-attested model claim is made. Submission requirements and the current challenge state need verification before any future publication.

The final landing page passed 1440×900 and 390×844 layout checks, loaded its public screenshot and played the packaged MP4 in Chromium (`reviews/landing-evidence.json`). The final UI distribution also passed the copied-install check again (`reviews/installation-accepted.json`).

Generalized lessons were recorded for frozen delegated handoffs, proving concurrent sessions, producer-side metadata integrity, monitor liveness, and checking recovery branches plus rendered output. No project identifiers or private details were placed in the global lesson file.

## Version-history correction

The implementation was initially left uncommitted through multiple verified milestones. That was a workflow mistake. Following the user's correction, the integrator organized the existing work into coherent commits with current timestamps, preserving the prior history and local user state. These commits were created retrospectively; they do not pretend to be checkpoints made during the earlier development session.

The shared `LiveFailure` declaration was moved, unchanged, from `LiveState.kt` into `LiveFailure.kt` so the graph/protocol foundation compiles independently of the daemon. Earlier release manifests and review hashes remain historical evidence of the pre-move artifact. New staged-boundary verification is recorded separately in `reviews/git-history-verification.json`.

The repository's `AGENTS.md` now requires coherent verified milestone commits, explicit staging, and a single integrating owner of the Git index during delegated work. No existing commits were rewritten and nothing was pushed.

The retrospective boundaries passed isolated checks: 32 graph/protocol tests plus official SDK; 26 edit/validation tests; 30 daemon/session tests; locked UI build and packaging; then the full 88-test suite. Fresh mixed-language and coordinated SDK flows, copied installation and forced browser recovery also passed on the staged integration tree. The evidence maps each tested tree to its resulting code commit.

## Source format and node editing, 2026-09-18

The optional navigation bridge now supports `--response-format source-v1` and
`--node-edits`. The first groups bundle source under matching nodes and inherits
identical metadata; retrieval selection, source bytes and limitations stay intact.
The second exposes `replace_node_body`, a guarded single-call Java method-body
replacement that uses the existing coordinator and retained preview. It rejects
existing reservations, including the caller's, and releases only the reservation
it acquired. Neither option is enabled by default.

The full product build passed **122 JVM tests**, with no failures, errors or skips.
Coverage includes exact format round trips, ambiguous IDs and absent/null metadata;
stale source, overloads, scope escape, reservation contention and cleanup; expiry,
disconnect, operation replay and committed writes whose indexing fails. Tests also
verify that guessing the new tool name cannot bypass the bridge opt-in. Benchmark
validation passed **36 tests**, including adversarial collector checks, independent
Java behavior/scope evaluators and calibration regressions.

The implementation and experiment were delegated separately: interface/bridge,
node-edit transaction, fresh fixtures/evaluation, and independent evidence audit.
The integrating agent owns the runner, review, build and Git index. The registered
protocol, node editing, response format, frozen evaluator and calibration correction
were committed as separate milestones. Nothing was pushed.

The [30-run study](reviews/token-interface/FINDINGS.md) retained every scheduled
attempt; all were correct and independently access-valid. Compared with native,
compact increased input/output by 55.4%/63.7%, the new format by 66.8%/77.6%, node
availability by 46.0%/56.7%, and combined options by 58.4%/58.3%. No configuration
met the efficiency gate. All 24 graph runs attempted retrieval, but none of the
12 node-enabled runs called the editor. Token effects of executing node writes
therefore remain unmeasured.

A separate post-score plan-only diagnostic confirmed an inherited Joern/compiler
type-spelling mismatch: the custom-parameter long and overloaded targets return
`target_not_found`; the primitive-parameter short target is eligible. No file was
changed and no scored outcome was altered. This limitation prevents broad edit
support claims; both options remain experimental. The actual
[checks](reviews/token-interface/checks.json), [eligibility evidence](reviews/token-interface/edit-eligibility.json)
and [independent review](reviews/token-interface/INDEPENDENT_REVIEW.md) distinguish
product verification from the unsuccessful efficiency goal.

## Qualified Java matching and guided rerun, 2026-09-18

The inherited custom-parameter mismatch is repaired in `9ce342a`. Java source
enrichment and safe body editing share a resolver based on immutable snapshot
declarations, lexical scope and explicit imports. Package identity, generic
shape, array rank, overload uniqueness, owner/name/line identity, raw-byte hashes
and edit boundaries remain checked. Unknown resolution fails closed; this does
not add general semantic refactoring. The declaration inventory is bounded to
512 Java files and 8 MiB. Unresolved wildcard/static imports, inherited names,
erased generics and incomplete inventories can remain unsupported.

Guidance commit `346d71b` discovers the editor explicitly, resolves overloads using
the selected node_id argument and directs eligible edits through the advertised
tool. It preserves native fallback and real commit/error distinctions. This is a
different guided workflow from the previous availability-only study.

The full build passed **132 JVM tests**, with zero failures/errors/skips. Six
executable guidance tests and 42 benchmark tests passed; the 19 collector tests
passed again after report metadata changed. Independent review checked the matcher,
guidance and preflight gate. A parser worker's external API failure ended its
turn after code/tests were saved; the integrator verified the saved source and
completed the full build. No unverified worker completion claim was accepted.

All three target families passed actual Joern-backed original-body planning and
real one-call repairs on separate fresh copies. Independent behavior/scope checks
and review passed, including the long and overloaded methods rejected previously.
Known repairs are confined to non-model preflight. The scored rerun uses the same
known development tasks and fresh runs of every control; it is not an unseen-task
confirmation. The [registered protocol](benchmarks/token_node_followup/PROTOCOL.md)
discloses the new guidance and equal compilation/behavior-check requirement.

The [completed rerun](reviews/token-node-followup/FINDINGS.md) retained all 30
attempts with no retries, exclusions, missing usage, timeouts or infrastructure
failures. All were correct and independently access-valid. All 24 graph runs
attempted retrieval; all 12 enabled runs used node editing, with 14 successful
commits and no rejected node calls or native production-edit fallback. The
independent reviewer regraded every outcome, checked 47 returned source slices,
reconstructed all 14 node commits and reconciled eight aggregate contrasts plus
48 paired comparisons from raw terminal counters.

The token-saving goal still failed. Versus native, compact increased input/output
by 45.8%/17.2%, source format by 76.0%/37.3%, node editing by 57.4%/45.0%, and combined
by 52.7%/34.1%. Both enabled arms still made 22 shell calls, the same aggregate count
as native, in addition to graph calls. Two node-only runs made whitespace-only
follow-up commits; one long-method result retained excess indentation. Formatting
quality remains outside the behavior/scope gate and is an unresolved limitation.
Compact and combined had lower uncached input, but uncontrolled cache state and
higher total input/output prevent a total-token or billing-saving claim.

The historical failed study remains intact. The new study uses known development
tasks and changed guidance/validation instructions, so it does not isolate the
parser fix or establish unseen-task performance. The implementation fixes,
protocol, preflight/evaluator and final evidence have separate milestone commits;
no history was rewritten and nothing was pushed.
