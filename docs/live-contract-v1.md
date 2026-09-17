# Local service contract v1

Normative implementation target, not a claim of implementation. JSON keys use snake_case. All JSON API responses include `schema_version: 1` and `daemon_epoch`; reject unsupported versions. Static assets and SSE comments are exempt. Paths are relative to the canonical configured checkout. Times displayed as ISO UTC; expiry enforcement uses a monotonic clock. Repository and epoch IDs are opaque strings. Source bodies are never included in events.

## Authentication and onboarding

Supported first-release platform: Linux, local POSIX filesystem with Java 21, same-filesystem atomic replace and file locking. Reject unsupported filesystem/permission behavior; other platforms have no release guarantee until tested.

An owner-only descriptor outside the repository stores the bootstrap secret, epoch, canonical root and loopback endpoint. Bootstrap is accepted only through `Authorization: Bearer ...` by command-line clients. Agent credentials use the same header. Browser observer sessions use an HttpOnly, SameSite=Strict, Path=/ cookie, memory-only on server, over loopback HTTP. Never accept credentials in query parameters, asset files or logs. Require exact Host everywhere; permit static navigation/assets without Origin, require exact same Origin on browser API requests and reject foreign Origin even when Authorization is present. Browser uses fetch for API GETs with an explicit same-origin header if the browser omits Origin; daemon instead validates the browser's `Sec-Fetch-Site: same-origin` metadata for such safe GETs because JavaScript cannot set Origin itself. State-changing requests require Origin. Nonbrowser clients authenticate through headers. No CORS permission.

V1 browser onboarding is an explicit pairing flow, with no credential pasted into a page or URL:

1. Browser `POST /observer/pair` with `{}` and exact Origin. Daemon returns a random public 10-character `pairing_code`, expiry 60 seconds and sets a separate random HttpOnly pairing cookie. Rate limit 5 pending requests per minute and cap 8 pending pairs. Cookie secret is never exposed to JavaScript.
2. UI instructs user to run `graphharness authorize-browser ROOT PAIRING_CODE` locally. The CLI reads the protected descriptor and sends `POST /observer/approve` with bootstrap auth and `{"pairing_code":"…"}`. The code only selects a pending browser; bootstrap auth grants approval. CLI must display the exact endpoint/root it authorized, never secrets.
3. Browser polls `POST /observer/claim` with its pairing cookie. Unapproved returns 202/pending; approved returns an observer session and sets its cookie, expiring the one-use pair. Pair expiry returns `pairing_expired`. Rate-limit claim polling to once/second. Approval cannot promote to agent. Browser does not access the bootstrap secret.

| Method/path | Anonymous | Bootstrap | Agent | Observer |
| --- | --- | --- | --- | --- |
| GET `/`, `/assets/*` | Static UI only | Same | Same | Same |
| POST `/observer/pair`, `/observer/claim` | Pair cookie flow, same Origin | No additional authority | No additional authority | May re-pair |
| POST `/observer/approve` | Deny | Approve existing pair | Deny | Deny |
| POST `/sessions` | Deny | Create agent only | Deny | Deny |
| POST `/sessions/heartbeat`, DELETE `/sessions/current` | Deny | Deny | Own session | Own session |
| GET `/tools` | Deny | Deny | Available tool definitions | Deny |
| POST `/tools/call` | Deny | Deny | Available tools only | Deny |
| GET `/state`, GET `/events` | Deny | Deny | Read | Read |
| POST `/inspect` | Deny | Deny | Read | Read: `get_source`, `search_graph`, `get_node_detail` only |

Observer inspection is attributed to observer role and must not impersonate agent work. Tool availability is computed from backend, language and completed safety gates. B disables plan/apply/rebase/validation and edit recommendations as well as rename; unknown/disabled calls return `unsupported_operation`. C may enable verified single-file edits. A bridge never starts a daemon.

Session creation request: `{"schema_version":1,"agent_label":"Agent A","client":{"name":"client-name","version":"observed-version"}}`.

Response: `{"schema_version":1,"daemon_epoch":"epoch","session_id":"opaque","session_credential":"secret-only-in-this-response","role":"agent","heartbeat_interval_ms":10000,"expires_in_ms":30000}`. Labels are bounded display metadata, not proof of model identity. Record optional client-reported model as unverified; demo evidence identifies actual configured client/model separately. All requests from an authenticated client carry `X-GraphHarness-Epoch`. Mismatch returns 409 `daemon_restarted` with new epoch; bridge terminates outstanding operations and re-discovers/re-authenticates. Old credentials never migrate. A current epoch with expired credentials returns 401 `session_expired`.

## Tools, errors and operation identity

`GET /tools` returns `{"schema_version":1,"daemon_epoch":"epoch","tools":[{"name":"get_source","description":"…","inputSchema":{"type":"object"}}]}` using MCP tool schemas. An authenticated call:

```json
{"schema_version":1,"operation_id":"17","name":"get_source","arguments":{"node_id":"method:example"}}
```

Success: `{"schema_version":1,"daemon_epoch":"epoch","operation_id":"17","result":{"snapshot_id":"s1","file_hash":"sha256","source":"…"}}`. `result` retains existing tool-specific public fields where meaningful. The bridge maps it to MCP `content`, optional object `structuredContent`, `isError:false`.

Failure envelope: `{"schema_version":1,"daemon_epoch":"epoch","operation_id":"17","error":{"code":"stale_source","message":"Source changed; refresh before reading.","details":{"file":"src/Example.java"}}}`. HTTP 400 invalid request, 401 session/auth, 403 forbidden role/origin, 404 missing resource, 409 stale/conflict/restart, 413 limits, 422 unsupported operation, 503 indexing/unavailable. No stack traces or input echo. Bridge maps tool errors to `isError:true`; malformed/unknown JSON-RPC methods are protocol errors. Notifications receive no reply.

Operation key is `(epoch, authenticated session_id, operation_id)`. IDs are decimal strings representing strictly increasing integers, issued by a bridge with one outstanding call per session; independent sessions operate concurrently. Each accepted call stores a canonical request digest and outcome; exact replay returns its result, payload mismatch returns `operation_reused`. Active duplicate returns `operation_in_progress` and never starts another write. Reject new IDs below the session high-water mark unless a retained replay exists. Retain at most 256 outcomes per live session and at most 64 sessions; evicted IDs return `operation_expired`, never execute again. Closed session credentials cease working. Retained outcomes have an 8 MiB/session byte ceiling; oversized result returns a bounded summary and cannot include unbounded source. Crash/restart invalidates epoch and completion may be uncertain. There is no crash-proof exactly-once claim.

## State and stream

`POST /inspect` uses `{"schema_version":1,"request_id":"browser-generated-uuid","name":"get_source","arguments":{"node_id":"method:example","include_context":0}}`. Allowed names are exactly `get_source`, `search_graph`, `get_node_detail`; agent credentials also may inspect but never gain write access through this route. Validate arguments against the same advertised tool schema; reject extra properties, invalid type/range, excessive depth or disabled backend. `request_id` is a bounded correlation string, not an idempotent edit ID; duplicate observer reads are allowed and each receives a fresh server `inspection_id`. They do not affect agent operation high-water marks.

Success: `{"schema_version":1,"daemon_epoch":"epoch","request_id":"browser-generated-uuid","inspection_id":"opaque","result":{"snapshot_id":"s1","file_hash":"sha256","source":"…"}}`. Errors use the same HTTP/status/error shape as tools with `request_id`/`inspection_id` instead of `operation_id`. Body ≤1 MiB, JSON nesting ≤16, string arguments ≤4 KiB, results ≤64 KiB; return `result_limit` with narrowing advice rather than invalid/truncated source syntax. `get_source` context 0–40 lines; search limit 200 returned nodes with explicit omitted count. File paths ≤4 KiB and labels ≤80 characters. Pair codes use uppercase RFC4648 base32 without padding; no punctuation.

Inspection emits `inspection_started/completed/failed`, including request/inspection IDs, authenticated session ID and role, actual returned node IDs/snapshot, and no source bodies. `operation_id` is null. UI separates observer inspection from coding-agent activity. Add success, stale source, oversized result, foreign role, malformed arguments and telemetry attribution examples to the shared contract fixture before UI/service integration. API shape is identical for agent/observer inspection apart from recorded role.

`GET /state` reads snapshot publication, session/lease state and journal cursor under the coordinator state lock. No tool or network work runs under that lock. Captured immutable state can be serialized after releasing it.

```json
{
  "schema_version": 1,
  "daemon_epoch": "epoch",
  "repository_id": "opaque-root-id",
  "repository_name": "public-fixture",
  "cursor": "42",
  "snapshot": {
    "snapshot_id": "s1", "generated_at": "2026-09-18T00:00:00Z",
    "analysis_engine": "fallback-parser", "semantic_level": "approximate",
    "indexing": "idle", "diagnostics": [], "omitted_file_count": 0,
    "nodes": [{"id":"method:example","kind":"method","language":"java","name":"example","qualified_name":"Example.example","file":"src/Example.java","parent":null,"byte_span":{"start":10,"end":40},"line_range":{"start":2,"end":4},"file_hash":"sha256"}],
    "edges": [{"from":"a","to":"b","relationship":"contains","provenance":"parser","resolution":"observed"}]
  },
  "sessions": [{"session_id":"session-a","agent_label":"Agent A","role":"agent","connection":"connected","last_seen":"2026-09-18T00:00:00Z","active_operation_id":null}],
  "leases": [],
  "capabilities": {"languages":["java"],"coordinated_writes":false,"disabled_tools":["apply_edit","plan_edit","validate_edit"]}
}
```

If a backend cannot prove a byte span or parent, emit `null` with diagnostics rather than inventing it; source navigation may remain line-based and approximate, and that adapter's exact-span/edit gate remains unpassed. Indexing states: idle/pending/building/failed. A failed build preserves the last good snapshot and exposes failure/pending generations. Snapshot export omits absolute root paths and raw byte caches. Source inspection goes through the safe tool path.

Connect `GET /events?after=42` with epoch header (native EventSource cannot set it; browser uses fetch streaming with cookie). Ordinary message:

```text
id: epoch:43
event: activity
data: {"schema_version":1,"event_id":"43","daemon_epoch":"epoch","repository_id":"root","timestamp":"2026-09-18T00:00:01Z","session_id":"session-a","agent_label":"Agent A","role":"agent","operation_id":"17","event_type":"tool_completed","tool_name":"get_source","snapshot_id":"s1","node_ids":["method:example"],"file_paths":["src/Example.java"],"status":"completed","duration_ms":12}

```

Control reset: `event: reset`, data `{"schema_version":1,"daemon_epoch":"epoch","reason":"cursor_expired","cursor":"current"}` then close stream; client fetches state and subscribes after its cursor. Invalid/future cursors reset as `invalid_cursor`; epoch mismatch is HTTP 409 before streaming. Heartbeat every 10 seconds is SSE comment `: keepalive` and has no event ID. No cursor advancement from heartbeat. Ordered event IDs are decimal strings, increment only under the state/journal lock. No gap between atomic state capture and later subscribe: journal retains intervening events, or explicitly resets.

Event types: session_connected/disconnected/expired; tool_started/completed/failed; snapshot_updated/indexing_failed; lease_acquired/denied/renewed/released/expired; edit_planned/applied/rejected; validation_started/completed/failed/cancelled. Node IDs on completed reads are extracted from the actual returned result. Unknown external ownership uses null session/label. Failure details are bounded structured codes, not arbitrary source or exception dumps. Nonblocking lease denial never means queued work.

Journal ceilings: 2,000 events or 4 MiB, whichever first. Bound one event to 16 KiB (truncate lists with explicit omitted counts). Each stream has independent delivery, 128-event/256 KiB pending ceiling, disconnect/reset a slow consumer; at most 8 streams. A network write never holds the journal/tool lock. Source request body ≤1 MiB, source result budget ≤64 KiB; full graph export ≤10,000 nodes with explicit truncation, focused requests for remainder. File size ≤1 MiB, candidate source count ≤10,000; snapshot retention 3 versions or 128 MiB source bytes, with explicit unavailable-history responses. Analysis deadline 60 seconds, one parser helper process at a time, cancellation kills its process tree. Validation deadline 120 seconds, output tail ≤64 KiB. Any unimplemented limit is an unpassed gate, not implied protection.

## Lock hierarchy and atomic observation

There is one coordinator state/journal lock and one mutex per canonical file. Allowed nested order is **file mutex → coordinator state/journal lock** only. Never acquire or wait for a file mutex while holding the coordinator lock. GET state/events take only coordinator lock and copy immutable public state and cursor, releasing before JSON serialization/network output. They never query mutable file-owned maps or wait for analysis. Per-file operations acquire the file mutex first; brief state validation/mirror update/event append uses the coordinator lock, then releases it. No filesystem/parsing/process/network work is done under coordinator lock.

The coordinator owns immutable public lease/session/snapshot references, operation receipts and journal. Any visible state mutation and its bounded event append happen atomically under its lock. A successful lease transition updates its public mirror and cursor together before releasing the file mutex. The snapshot publisher sends an immutable new snapshot to coordinator publication, which swaps the served snapshot reference and appends `snapshot_updated` in one lock section; raw analyzer `current()` is not independently served by HTTP. This allows GET state plus cursor to represent a single published view even while analysis or an edit is in progress.

Apply holds file mutex through final source/lease validation and replacement. It briefly acquires coordinator lock to validate session/fencing and mark `commit_in_progress`, then releases coordinator lock for filesystem replacement, then reacquires it to record committed outcome, indexing pending and `edit_applied` together. Another owner cannot be admitted for this file until mutex is released. An expiry/close after entry into commit section allows that commit to finish and records cleanup afterward. Before entry, session expiry makes the operation fail. No analysis/publication wait occurs under file mutex.

Session expiry/EOF/shutdown first marks session closing under coordinator lock and copies owned file keys, then releases coordinator lock. It visits each file mutex separately, revalidates ownership under coordinator lock, performs bounded state/event cleanup and releases both before the next file. Never hold two file mutexes. TTL expiration and renew use the same file-first sequence; state may show a lease as elapsed/pending cleanup but cannot grant a new owner outside this path. Shutdown drains commit sections within a bounded deadline and otherwise leaves restart completion uncertain.

Required deterministic test: barriers race state fetch, journal subscribe, session expiry, renewal and apply. Assert bounded completion/no deadlock, no grants to two live owners, and for every captured cursor that its state reflects all relevant events through that cursor and no later published transition. Force expiry both before and after entry to the commit section. File byte visibility outside the daemon's published state remains subject to the stated external-process boundary.

## Leases and validation inputs

Lease tool schemas use `file`; renew/release use `lease_id`, decimal-string `generation`; apply uses `edit_id`, `lease_id`, `generation`, with operation identity from the authenticated envelope. Expected file hash and epoch are bound by server. Lease public view includes file, ID, generation, session owner, target node if proven, expiry UTC and remaining milliseconds; TTL 30 s, absolute hold ≤120 s, renew each 10 s. Expiry authority is monotonic, not browser time. Every modifying transition uses the same file mutex. Busy details name holder and expiry; another file remains available.

Validation captures current working-tree inputs, including modified and untracked nonignored files, not just HEAD. Use `git ls-files --cached --others --exclude-standard -z` when Git exists, intersect with existing confined regular files; explicitly include build scripts/lockfiles/config tracked by Git even when an ignore matches. For non-Git fixtures, bounded walk excluding `.git`, runtime/cache, build/dist/vendor/node_modules and declared ignore rules. Reject symlinked inputs for V1 instead of silently following them. Do not copy secrets from environment files; if a declared build depends on excluded/unavailable inputs, report unsupported/incomplete validation scope rather than a passing project test. Run an explicitly selected project command in the copy, no global secrets inherited; allow only documented toolchain variables. Default offline, no network/bootstrap fallback counted as tests. If dependencies are unavailable, report blocked/degraded with actual scope. Hash both source and copy before execution and source afterward, report manifest plus drift. Include build tool/dependency versions in evidence; a file manifest does not pin external caches by itself.

## C implementation additions

The following additions are implemented behind coordinated-mode enablement and must pass C integration checks before being advertised. The original planning review predates these additions; its recorded hashes remain historical evidence, not a review of this extended contract.

- `plan_edit` accepts `node_id`, `snapshot_id`, `expected_file_hash`, `new_body`. It supports replacement of one concrete Java method body; constructor/initializer/rename/anchor edits remain unavailable. Planning requires current snapshot identity so an old node ID cannot be rebound silently. Once created, a plan depends on its exact preimage and file, so an unrelated snapshot update does not invalidate apply.
- `apply_edit` accepts `edit_id`, `lease_id`, `generation`; identity comes from the operation envelope. Failure cleanup is limited to that apply's supplied reservation; an unrelated read or busy acquisition must not drop other owned reservations. Plans expire after 120 seconds and are bounded to 64/16 MiB.
- Agent heartbeats renew owned leases through the coordinator, subject to the original 120-second maximum. This keeps renewal independent from a long-running tool operation and does not consume tool operation IDs. The bridge still sends the ten-second heartbeat and closes its session on EOF.
- `validate_project` accepts a caller-selected `command` array and declared `mode` (`test` or `compile`). It reports actual wrapped command, source manifest, exit code, output tail, exclusions, stale status and scope. A declared mode is not independent evidence that the command contains meaningful project tests. No syntax fallback is mislabeled as tests.
- Validation uses a captured working-tree copy inside bubblewrap with private filesystem/user/process/network namespaces, a private HOME/TMPDIR, read-only system/toolchain mounts and an allowlist of environment variables. Missing isolation blocks validation. `prlimit` applies per-process resource ceilings; aggregate process-tree memory/CPU are not cgroup-limited. API evidence lists limits and mount scope. External toolchain binaries are not pinned by the source manifest.
- `GET /edits/{edit_id}` is an authenticated read for agent/observer roles. It returns the bounded actual before/after preview and originating snapshot while the plan is retained; otherwise `stale_plan`. Sources stay out of the event journal. Admitted edit events carry `edit_id` for preview correlation.

## D implementation additions and effective bounds

- `language_adapters` names parser, version, availability, capabilities and diagnostics per language. Java retains Joern/fallback distinctions. TypeScript/JavaScript use the pinned TypeScript compiler API; Python uses the installed interpreter's AST. Non-Java semantic traversals and edits are rejected.
- Node/source responses carry language, qualified name, parent, parser provenance and nullable half-open UTF-8 byte spans. Source with `include_context=0` slices those exact bytes when available; surrounding-context source can use a larger line slice. Hashes always identify the original complete file.
- Java fallback declarations now use javac ASTs. Exact Java overlays require unique matching enclosing-type/parameter/range metadata. Unmatched backend nodes retain diagnosed approximate spans rather than fabricated parser certainty.
- Structural containment edges are exact and parser-observed. No non-Java call/type-resolution edges are generated. Hierarchical node identities distinguish repeated declarations and survive unrelated whitespace changes.
- Legacy summary package/type/method totals, clusters, hotspots and entrypoints cover Java. `semantic_summary_scope` states that boundary; `structural_nodes_by_language` separately reports definitions across parsed languages.
- Installed UI/parser discovery is based on the loaded application location, not an arbitrary checkout or working directory. Explicit operator environment overrides are trusted configuration.
- Effective structural bounds: retained source file <=1 MiB, helper JSON input <=6.4 MB, output <=2 MiB, <=10,000 definitions, five-second helper deadline and 30-second admission budget per build (one final admitted invocation can finish afterward). Java/Joern commands use a 120-second process deadline. These implemented bounds supersede the earlier aspirational 60-second overall analysis deadline; total indexing time is not a hard 60-second guarantee.
- Helper results are cached by language/path/source hash. Changing installed runtimes requires a daemon restart or changed source. Missing runtimes degrade capabilities visibly.
- Browser reconnect retains the last delivered cursor in the same epoch. Explicit reset or a new epoch refreshes authoritative state. Historical activity never implies retained historical source/topology.
