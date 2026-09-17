# GraphHarness Live development plan

Status: version 1.3; independent review after round-two fixes. Updated 2026-09-18.

This is the executable development plan for the supplied GraphHarness Live handoff. It preserves the full release scope while separating intermediate prototypes from release completion. The reported 96/100 in the earlier conversation is historical, user-supplied planning evidence; it is not a review performed in this checkout or evidence of working software.

## Outcome

Give agents a shared structural map of a checkout and let a developer inspect their observed searches, reads, edits, validation and contention. Keep the Kotlin runtime. One authoritative daemon serves multiple authenticated MCP bridges and a browser. Agents navigate the same graph the developer sees. Display tool activity, never private reasoning or guessed authorship.

The complete first release requires Java, TypeScript/JavaScript and Python navigation; two real agent sessions; a source-linked interactive graph; safe single-file coordinated Java edits; recovery; a reproducible example; installation instructions; actual screenshots and recording; and a prepared static landing page. Navigation does not imply semantic editing or cross-language call resolution. No publishing, deployment, external messaging or competition submission is authorized by this plan.

## Baseline assessment

Inspected checkout: `2c775198edff0e48588b150571751f3bdbada0db`, branch `main`. Initial untracked `.codex` and `.gradle/` are user/environment state and must be preserved. No `live/` experiment is present.

| Evidence | Observed state | Consequence |
| --- | --- | --- |
| `build.gradle.kts`, `flake.nix` | Kotlin/JVM, Java 21; Gradle tests and a direct Kotlin compilation route | Reuse Kotlin; verify both documented packaging and tests |
| `Server.kt:295` onward | Content-Length framing, private tool dispatch, old initialization protocol | Add standard newline MCP transport with lifecycle/error tests; retain old mode explicitly |
| `Analyzer.kt:120` onward | Watcher and apply paths can install snapshots; installation clears all dirty paths | Establish one publication pipeline before claiming concurrent consistency |
| `Analyzer.kt:2135` onward | Source retrieval reads files at snapshot-derived ranges | Capture immutable bytes and reject current-source requests if hashes differ |
| `Analyzer.kt`, `EditSupport.kt` | Existing edit planning/application and scratch validation | Audit independently; disable unsafe operations on the daemon until their gates pass |
| Repository file inventory | No browser application or multi-language adapters | These are new implementation work |

Build/test outcome and further source findings belong in `DEVELOPMENT_STATUS.md`, with actual commands and failures. Java 21 is available; Gradle/Kotlin are supplied by the repository Nix environment rather than the initial shell PATH. The original 12 tests passed in the Nix environment; this does not cover the identified race and source hazards.

## Delegation and integration policy

The lead owns contracts, sequencing, integration and acceptance. Bounded coding tasks go to implementation agents; independent critique goes to a reviewer. Use non-Astra implementation/review models where available, as requested. Record actual configured model names; never infer the demo model from a label. With four available slots, use the lead plus at most three agents. Do not split tightly coupled ownership across simultaneous editors.

| Workstream | Owner and file boundary | Can run with | Acceptance/handoff |
| --- | --- | --- | --- |
| Baseline safety audit | Read-only implementation agent | Build verification, plan writing | Concrete code locations, contradictory tests, hazards and suggested boundaries |
| Protocol and bridge | Implementation agent: `Server.kt`, new bridge/protocol files, protocol tests | UI against frozen contract | Real MCP initialization/list/call/notification/error transcript, no stdout pollution |
| Snapshot/source safety | Implementation agent: `Analyzer.kt`, `AnalysisSupport.kt`, `EditSupport.kt`, relevant model fields and tests | UI, daemon files after interface agreement | Immutable bytes/hash/spans, deterministic race and boundary tests |
| Shared service/session/events | Lead initially; delegate isolated journal/lease modules after contracts stabilize | Bridge and UI work | Single daemon ownership, authenticated identity, cursor recovery, distinct sessions |
| Browser | Implementation agent: `ui/` only | Service development using explicit fixture contract | Real service integration, source/search/follow/list/conflict/reconnect at target sizes |
| Languages | Implementation agent: adapter files and fixtures only | UI/coordination after snapshot boundary exists | TS/JS then Python fixture truth; parser/provenance/capability labels |
| Independent review | Reviewer: read-only code, writes only `reviews/` | Lead integration/verification | Weighted score, blockers, exact evidence; never approves its own implementation |

Before each assignment give dependencies, owned files, invariants, checks and stop conditions. Agents do not revert unrelated edits or run Git cleanup. A shared file has one writer; request interface changes from its owner. Report changed files, commands, observed results and unverified claims. Root reviews diffs and runs integrated checks after each merged workstream. Avoid several Gradle builds racing in the same build directory.

Concrete ownership transfers and dependency graph:

| Tranche | Sole ownership | Transfer and dependency |
| --- | --- | --- |
| A0 | Lead: plan/status, build environment; audit agent: read-only | Baseline report before architecture changes; baseline tests before modified-suite claims |
| A1 protocol | `mcp_protocol`: `Server.kt`, new protocol helper and `ServerTest.kt`; lead: `Main.kt`, launch scripts, packaging | Worker exposes `toolDefinitions()` and `invokeTool(name, arguments)` and newline/explicit legacy options; after worker stops editing and reports exact diff, lead reviews/tests then takes Server ownership for B routing if needed |
| B source prerequisite | `baseline_audit`: `Analyzer.kt`, `AnalysisSupport.kt`, `EditSupport.kt`, `Model.kt`, new snapshot tests | Owns all graph DTO changes. Exposes current snapshot, safe source, refresh, close; lead requests modifications from this owner and does not edit those files concurrently. Ownership returns after reviewed diff and passing tests |
| B service | Lead: new `LocalRuntime.kt`, `LiveDaemon.kt`, `LiveBridge.kt`, service-only DTOs and tests, `Main.kt` | Uses A1 dispatch and snapshot APIs; owns HTTP/event/session adaptation in new files only. No edits to analyzer DTOs while source agent owns them |
| B browser | UI worker: `ui/` only, after contract v1 is frozen in `docs/live-contract-v1.md` | Consumer fixture uses exact v1 examples. API changes proposed to lead, root edits contract, both consumers acknowledge before adoption. No mock evidence accepted as integrated gate |
| C coordination | Lease worker: new `EditCoordinator.kt`/tests; snapshot owner: existing edit DTOs/boundaries/validation; lead: routing | Start after B acceptance. Lease worker proposes needed analyzer API; sole snapshot owner implements it. Lead never splices concurrent worker patches into the same file |
| D adapters | Adapter worker: new adapter/helper/fixture files; snapshot owner: Analyzer/Model integration | Start after Java snapshot contract tests. Worker hands off normalized parse results; snapshot owner wires publication and capabilities |

All existing-file ownership is recorded in status before each reassignment. Completed workers stop editing before transfer. A clean handoff means reviewed scoped diff and no unfinished worker mutations, not a clean Git tree; unrelated user changes remain. Dependency order: A0 → A1 → B integrated acceptance → C → D → E; B source work and B UI construction may run alongside A1, but cannot pass B until all prerequisites are verified. Independent review is never the implementation owner.

## Milestones and dependencies

The earlier four-hour schedule is a risk estimate, not a completion promise. Preserve at least one hour for integration/demo in a four-hour attempt. Continue the full release after the attempt if required; smaller deliverables remain explicitly incomplete. Do not broaden features when a prerequisite gate fails.

| Milestone | Work | Exit gate / cut line |
| --- | --- | --- |
| A0 — Baseline and executable plan | Run existing tests; inspect restrictions; critique plan | Preserve baseline evidence and user changes; diagnose failures without loosening checks |
| A1 — Transport spike | Protocol worker repairs GraphHarnessServer newline transport; lead wires default `stdio` and explicit `legacy-stdio` launcher; run official Python MCP SDK stdio client against compiled executable | Assert initialize/version, tools/list, real search/source result, notification silence, errors, EOF and stderr-only diagnostics. This proves SDK interoperability, not an autonomous agent. If unavailable, mark blocked and allow deterministic protocol development only |
| B — Read-only live slice | Explicit daemon startup/discovery; root lock; sessions; shared read dispatch; event journal; minimal browser; immutable source responses | One real agent reads a node and the matching event appears; then two real connections. Daemon exposes no edit/validation command execution yet |
| C — Coordinated Java writes | Lexically/parser-safe spans; leases/fencing; preimage checks; operation idempotency; atomic replacement; versioned validation | All coordination, boundary, snapshot and validation gates below. Otherwise retain read-only status and no coordination claims |
| D — Mixed-language navigation | Parser-backed TS/JS then Python adapters; ignore policy; incremental cache; mixed graph | Every advertised language passes fixtures; no regex-only support claims or invented same-name calls |
| E — Usability and release evidence | Browser polish, clean installation, useful context task, performance measurement, two-agent recording, README and landing assets | Complete gate ledger; unresolved gates prevent release-complete/submission-ready claims |

Start the next milestone only after its dependencies pass. UI design can proceed in parallel against a frozen schema, but mock data must be labeled and never used as live activity evidence. A deterministic client integration test is not proof of autonomous agent use.

## Frozen service and data contracts

The normative versioned endpoint examples, authorization matrix, pairing flow, error/event envelopes, operation identity and resource bounds are frozen in [contract v1](docs/live-contract-v1.md) before service/browser implementation. They take precedence over abbreviated prose here. Lead owns contract edits; bridge/UI owners acknowledge changes before implementation. Fixture/schema assertions must exercise both consumers against it.

### Daemon and session authority

- Explicit startup with canonical `toRealPath` checkout identity. Separate worktrees are separate checkouts. Bridges never launch a coordinator automatically.
- Process-lifetime OS file lock in an owner-only runtime directory, not a PID-only lock. Reject symlink substitutions and wrong owner/permissions. A second daemon for an alias of the same root refuses startup. Owner-only descriptor stores loopback endpoint, root identity, epoch and bootstrap credential. Shutdown removes only its own descriptor. Credentials never enter Git, stdout, URLs, activity or browser assets.
- Bind `127.0.0.1`, validate Host and same Origin, bound request sizes, constrain paths including symlink escapes. Browser has a separate observer session and no general tool authority. V1 onboarding uses the contract's browser pairing cookie/public code, bootstrap-authenticated local CLI approval and one-use observer-cookie exchange. No URL credential or pasted bootstrap secret. Prove the flow during B; failure blocks browser authentication, not an excuse to expose unauthenticated APIs.
- `POST /sessions`: bootstrap-authenticated identity/credential issuance. `POST /sessions/heartbeat` and `DELETE /sessions/current`: authenticated liveness/close. `POST /tools/call`: authenticated session-derived owner, operation ID. No caller-supplied owner is authoritative.
- `GET /state` returns an atomic snapshot/session/lease/cursor view; `GET /events?after=...` is observer SSE. SSE is the browser event transport, independent of newline MCP on bridge stdio. Bridge logs only to stderr.
- Bridge heartbeat every 10 seconds; session timeout 30 seconds. EOF closes sessions and releases idle leases. Apply already inside its commit section completes before cleanup. Crash leaves only bounded lease life. Restart changes epoch, invalidates sessions/plans/leases and requires re-reading source.
- Errors include `lease_busy`, `lease_expired`, `not_owner`, `stale_source`, `stale_plan`, `unsupported_operation`, `session_expired`, `daemon_restarted`, `validation_inputs_changed`. MCP distinguishes protocol errors from tool execution errors; notifications never receive responses.
- Preserve old launcher as explicitly uncoordinated compatibility mode; it has no daemon write guarantee and cannot be used in the coordinated demo.

### Graph, snapshots and source

- Snapshot: repository ID, snapshot ID, timestamp, backend versions, file hashes, diagnostics, indexing status and omitted-file count. Node: ID, kind, language, name/qualified name, relative path, parent, byte and line spans, file hash. Edge: endpoints, relation, provenance and resolution class.
- UTF-8 raw byte spans are zero-based half-open; displayed lines one-based. Preserve CRLF and unchanged bytes. Never apply Tree-sitter byte offsets to Kotlin UTF-16 indices. Reject unsupported encoding. Distinguish overloads and duplicate names. IDs survive unrelated whitespace when feasible; rename identity correspondence is not presumed.
- Each build consumes immutable source bytes and their manifest. One serialized publication pipeline installs snapshots in order. Dirty paths have monotonically increasing generations; clear only generations consumed by that build and schedule follow-up for later changes. Apply queues indexing after commit and does not create an independent builder. Retain source bytes with their snapshot; current-source access checks current raw bytes/hash and returns `stale_source` on mismatch.
- Keep parser containment distinct from approximate calls; omit/mark ambiguity. Fallback `get_call_paths`, `get_implementations`, `get_impact` remain restricted. No cross-language runtime call claims without evidence. Syntax errors yield diagnostics/reduced capabilities.
- Respect ignores; omit generated/vendor/build outputs; enforce root confinement, size/count/time/cancellation limits and surface omissions. Cache unchanged files. Register created directories and account for watcher overflow with rescan. Backend helpers parse immutable supplied bytes, never own sessions, locks or graph authority.
- Source/context responses identify snapshot and file hash, use a bounded source budget and explain truncation. Historical events without retained topology/source show recorded names/paths and unavailable history, not fabricated reconstruction.

### Events and browser

Envelope: event ID, epoch, timestamp, repository ID, session ID, label, operation ID, type, snapshot ID, involved node IDs/paths, status and duration. Started/completed/failed tool events come from actual dispatch. Context events highlight returned symbols; external changes remain unattributed. Never journal complete prompts, source bodies, credentials or unbounded command output.

Bounded ordered ring with a state cursor; resume retained events, deduplicate epoch/ID, explicitly reset expired cursors. A slow browser must not block tools. UI separates observed work, idle connection, disconnection, last-seen and nonblocking denial. Do not show denied requesters as queued waiters. Event replay is labeled recorded. Refresh stale node references against current snapshot.

Desktop targets 1440×900 and 1280×800 plus usable narrow layout. TypeScript with pinned Cytoscape.js and lockfile; React optional. Stable grouped positions, focused neighborhoods, language badges independent of agent color, source inspector, searchable keyboard-accessible list, activity rail, follow-agent, fit/filter, visible errors and reconnect state. Lease/conflict uses names/badges/countdown as well as color. Respect reduced motion. No invented agent activity or chatbot filler.

Measure on a named machine/fixture: 1,000-symbol navigation, event render latency p95 target 500 ms, small-edit indexing target 3 s. Report sample count, actual observed timings and failures. These targets are not current product claims; reduce detail rather than silently discard updates.

### Coordinated edit and validation guarantees

- Disable `rename_node` on coordinated endpoints. Enable only verified single-file Java method edits after replacing naive brace boundaries and proving string/comment/text-block/overload/Unicode/CRLF cases. Structural adapters remain read-only until independent edit gates pass.
- Exclusive canonical-file lease: server session owner, lease ID, fencing generation, epoch, expected hash, monotonic expiry. Default TTL 30 seconds, renew every 10 seconds, absolute maximum 120 seconds from acquisition. Owner-only renewal/release; release idempotent. Acquisition is nonblocking busy/acquired; no queue or fairness promise. Do not hold several files while waiting for another.
- Plan is bound to creating session, exact preimage bytes, file hash and target span. Under one per-file mutex: validate live owner/epoch/generation, one-file scope and preimage; stage same-filesystem replacement, validate supported syntax, recheck source and lease, atomically replace preserving mode. Acquire/renew/release/expiry/apply transitions serialize consistently. Unsupported filesystem semantics fail explicitly.
- Record committed operation immediately after replacement, emit `edit_applied`, return `committed=true` with indexing pending; index failure is separate. Release file mutex before waiting for indexing/tests. Within epoch, repeated operation ID returns original outcome; altered payload reuse fails. Restart completion may be uncertain; require fresh source, never claim crash-proof exactly-once writes.
- Operation identity is `(epoch, session_id, operation_id)` with per-session increasing decimal IDs, bounded retained outcomes and a high-water mark rejecting expired replays; see contract. Retention eviction must never permit a second write. Independent sessions may issue the same numeric operation ID without colliding.
- Remove blanket snapshot-ID staleness only after preimage/span regression tests: unrelated-file updates may preserve a valid plan, same-file drift must reject it.
- Run real tests outside mutex in an isolated validation copy. Hash relevant source/build/lock/config inputs; verify source and copy manifests after capture, retry/fail on drift. Attach manifest ID, actual command, exit code, scope and diagnostics. Compare inputs afterward, mark stale if changed. Distinguish project tests, compile, syntax and degraded results. Do not silently fall back while labeling syntax as tests.
- Leases cover cooperating harness clients only. External writes can still race between final check and rename; do not claim OS-wide transactions or semantic safety across files. Graph overlap is a warning. Separate worktrees/integration are later work.

## Verification and evidence ledger

Every gate records implemented, verified, deferred or blocked with command, fixture, environment and limitations. Baseline failures are retained; replacement coverage must justify intentional behavior changes. Test fixtures must exercise semantics independently rather than repeat implementation assumptions.

Ledger columns: gate, state, artifact/version, command, environment/backend, observed outcome, evidence path, limitations, reviewer. Machine-readable JUnit/browser traces supplement summaries; do not commit credential-bearing logs. Actual demo uses two separate coding-client processes with distinct bridge/session IDs and assigned public-fixture tasks; no new orchestrator. Record task descriptions and configured model/client outside product telemetry. SDK/script-only evidence cannot satisfy this gate.

| Gate | Required negative/positive evidence |
| --- | --- |
| Regression/build | Original suite result by backend; Kotlin build; documented fresh setup; no developer absolute paths |
| MCP | Actual SDK/client initialize/list/call, notification silence, unsupported requests/errors, Unicode/newlines, EOF, no stdout pollution |
| Authority | Two real sessions distinct despite identical labels; alias-root second daemon rejected; unauthorized/foreign-origin access rejected; root/symlink confinement |
| Contention/fencing | Barrier-synchronized acquisitions give exactly one winner; loser cannot write; other file available; foreign renew/release fail; expired/reassigned/restarted owner cannot write |
| Source/edits | Old preimage rejected; unrelated file update allowed; duplicate names/moved lines cannot retarget; strings/comments/text blocks/Unicode/CRLF preserve all unrelated bytes; rename/multifile edits unavailable |
| Snapshot races | Deterministic barriers during build/source read force drift; no lost dirty generations or publication regression; bytes/hash/spans agree |
| Commit/validation | Index failure still reports committed; replay never writes twice; payload mismatch rejected; drift cannot enter validation unnoticed; results become stale when inputs change |
| Recovery/watcher | Retained reconnect events complete or reset explicit; duplicates deduped; slow consumer isolated; create/change/rename/delete/new directory/overflow; external ownership unknown |
| Languages | Definitions/nesting/duplicate names/non-BMP/CRLF/multiline strings/syntax errors/source extraction in each advertised adapter; no false cross-language joins |
| Browser/context | Select/search/follow/event focus/conflict/readable list; viewport/keyboard/reduced-motion/error states; named context task reports bytes/budget/omissions |
| Actual demo | Two actual coding agents through shared bridge, observed activity, real reserve/apply/conflict, deterministic independent contention test, actual fixture tests and recorded model/client metadata |

## Packaging and completion

Check in a small public mixed-language fixture with deterministic tasks/tests. Document prerequisites, one installation path, daemon startup, chosen real-client MCP configuration, two-session walkthrough, capability/backend matrix, troubleshooting and guarantee boundaries. Prepare actual screenshots, 75–90 second recording and `docs/` static landing assets. A landing page may describe installation and play a public recorded fixture; it cannot host the local daemon or accept arbitrary remote execution.

Product Hunt material, if requested for this handoff: tagline ≤60 characters, description ≤260, thumbnail/gallery/video, tool credits, ≤3 topics and maker comment grounded in actual model use. Current scheduling, eligibility and launch requirements must be checked against official sources before any publication. The supplied launch guide is not present as a file in this checkout; its pasted summary is not eligibility evidence. No launch action is part of implementation completion.

## Independent critique

Freeze the rubric before review: product/demo 15, repository grounding 10, scope/sequencing/cut lines 20, architecture/integration 15, concurrency/event/analysis correctness 20, verification/visual acceptance 15, handoff/readiness 5. Total 100. Target approximately 95 with no blocking planning weaknesses; never ask a reviewer to award the target.

Reviewer reads the full actual file and relevant code, reports dimensional scores with arithmetic checked, exact blockers and actionable fixes. Save every review in `reviews/` with version/hash reviewed. Root revises substantive issues, then reviewer rereads the changed file. Do not resample reviewers to select a favorable score or count repeated model agreement as runtime evidence. Stop when target/no-blocker condition is met, or report unresolved uncertainty that planning cannot settle. A score never overrides an unpassed release gate.

Round 1: 92/100; blockers were overlapping file ownership, deferred wire/authority details, and unspecified placement of the A1 transport artifact. Version 1.2 defines ownership transfers, freezes contract v1 and splits A0/A1. Retain [full first review](reviews/plan-round-1.md); request independent reread of both files, with no requested outcome.

Round 2: 95/100 but two blockers remained: browser inspection wire details and cross-module lock hierarchy. Version 1.3 adds observer-specific inspection envelopes/events and file-mutex-before-state-lock ordering with atomic public state/event publication and expiry cleanup. Retain [full second review](reviews/plan-round-2.md). Numeric score alone is not the stop condition.

References consulted for contracts: [MCP stdio transport](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports), [Cytoscape.js APIs](https://js.cytoscape.org/). Repository code and measured execution remain the authority for implementation status.
