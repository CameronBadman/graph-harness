# GraphHarness Live

Give coding agents a shared map of a checkout, and watch their actual searches, source reads and coordinated changes on an interactive graph.

The local daemon provides **Java, TypeScript, JavaScript and Python structural navigation**, separate authenticated MCP sessions, and a browser observer. Java additionally supports opt-in coordinated method-body edits. Agent activity comes from actual tool dispatch; it does not represent private reasoning. See the [verification ledger](DEVELOPMENT_STATUS.md) for measured results, artifact versions and remaining limits.

[Watch the recorded two-agent demo](docs/assets/real-agents-demo.mp4) · [Development plan](DEVELOPMENT_PLAN.md) · [Evidence and limits](DEVELOPMENT_STATUS.md)

The recording uses two actual Codex clients configured with `gpt-5.6-terra`, deliberate reservation timing, and the public fixture. Playback is 1.1×. The journal and expected-failure/passing-test evidence are in [the recording report](reviews/recorded-demo-evidence.json).

**The token-saving goal remains unmet.** The latest [30-run comparison](reviews/token-interface/FINDINGS.md) found **66.8% more input and 77.6% more output** with the experimental source-centered format, and **58.4% more input and 58.3% more output** with that format plus node editing available, versus native Codex. All repairs passed, but no scored agent used the node editor; a separate diagnostic also found two targets unsupported by existing parser matching. These small synthetic results measure the offered workflow, not the efficiency of executing node writes. The [earlier 24-run follow-up](reviews/token-fix/FINDINGS.md) and [original pilot](reviews/token-ablation/FINDINGS.md) remain unchanged; their tasks differ.

## Build and run

The supported live environment is Linux with a local POSIX filesystem, JDK 21, Gradle 8, Node.js 20.19+ or 22.12+, npm, and Python 3.11+. The checked-in Nix development shell supplies the JVM tools; Node/npm must also be available.

From this repository:

```bash
./scripts/build_live.sh
build/install/graphharness/bin/graphharness daemon examples/ticket-office
```

The build script installs locked UI and parser dependencies, builds the browser, runs the JVM tests and packages the application. It uses Gradle 8/JDK 21 directly or the Nix development shell. The distribution includes browser assets and the pinned TypeScript compiler; Node and Python remain external runtimes. Copy the entire `build/install/graphharness` directory when installing elsewhere. The daemon prints a loopback URL to stderr after indexing; Joern's first analysis can take time. Open that exact URL, choose **Start pairing**, then run the displayed `authorize-browser` command from a terminal. The public pairing code expires after 60 seconds. Approval grants the browser read-only inspection; it never exposes the daemon bootstrap credential.

The daemon must start explicitly. A second daemon for the same canonical checkout is rejected. Bridges connect to the running daemon; they do not launch another coordinator. Stop the daemon with Ctrl-C.

Joern is preferred when available:

```bash
./scripts/install_joern.sh
export GRAPHHARNESS_JOERN_HOME="$HOME/.local/share/graphharness/joern"
```

Otherwise GraphHarness uses a javac-backed Java declaration parser with approximate call analysis. The UI and capability tool identify the backend. Fallback mode disables call paths, implementations and impact rather than promising reliable results for them.

## Connect Codex sessions

Use absolute paths for your installation and checkout in the client's MCP configuration. For Codex, add a server entry to your chosen configuration:

```toml
[mcp_servers.graphharness]
command = "/absolute/path/to/graph-harness/build/install/graphharness/bin/graphharness"
args = ["bridge", "/absolute/path/to/checkout", "Inventory agent"]
required = true
```

A second Codex session can use a different display label. Every bridge obtains its own server-issued identity; labels do not grant authority. Both bridges must use the same checkout and runtime directory. `GRAPHHARNESS_RUNTIME_DIR` optionally selects an owner-only runtime directory and must match across daemon, bridge and authorization commands. Otherwise the runtime uses a private application directory outside the checkout.

The bridge uses newline-delimited MCP stdio. Protocol messages are the only stdout output. Current configuration keys follow the [official Codex MCP documentation](https://learn.chatgpt.com/docs/extend/mcp?surface=cli).

For read-only exploration, the optional [Codex navigation setup](docs/codex-navigation.md)
uses four tools, compact responses, and explicit code-mode discovery guidance.
Run `graphharness codex-instructions` to print that guidance. The measured workflow
did not save tokens; keep it optional rather than requiring bundle-first use for efficiency.

Try the public [ticket-office example](examples/ticket-office/README.md): ask one agent to inspect `TicketInventory.reserve` and another to inspect `PriceQuote.totalCents`. Watch their search/source events, select an event to focus its nodes, follow a session, or use the keyboard-operable code list to inspect source. Use the language filter to inspect the fixture’s TypeScript formatter and Python report. These have parser-observed definitions and containment; runtime calls between languages are not inferred.

## Coordinated edits

Start a daemon with writes enabled explicitly:

```bash
build/install/graphharness/bin/graphharness daemon examples/ticket-office --allow-edits
```

Agents read fresh source, call `plan_edit` with the returned snapshot/file hash and a new method body, acquire a file lease, inspect the preview, apply it, and release. A second agent receives `lease_busy` with the holder and expiration. It can keep reading; there is no waiting queue. The bridge renews its leases, with a 120-second maximum hold. The browser shows reservations, denied requests and the actual retained before/after preview.

The experimental navigation bridge can also expose a single-call Java editor:

```bash
graphharness bridge /absolute/path/to/checkout Codex --navigation --node-edits
```

`replace_node_body` takes the fresh node ID, snapshot ID, file hash and replacement
body statements. It validates the target, briefly reserves the file, commits the
change and releases its own reservation. Existing reservations, including the
caller's, return `lease_busy` without being altered. The receipt distinguishes a
committed edit from pending indexing; syntax validation does not run project tests.
The daemon must have `--allow-edits`. An independent `--response-format source-v1`
option groups bundle source under nodes without dropping context information. Both
options are off by default; see the [configuration and limits](docs/codex-navigation.md#experimental-response-format-and-node-editing).

Only a concrete Java method whose enclosing type, parameter metadata and source bounds match the compiler parser can be edited. Unsupported metadata fails closed. Constructors, initializers, multi-file rename and semantic refactoring are unavailable. Reservations coordinate clients using this daemon; they do not make arbitrary external editor writes transactional or prevent incompatible changes in separate files.

`validate_project` runs an explicitly chosen command on a captured copy with a source manifest. It requires Linux user namespaces, `/usr/bin/bwrap` and `/usr/bin/prlimit`; missing isolation support returns `validation_blocked`. The sandbox has private filesystem/process/network namespaces and read-only system/toolchain mounts. Dependencies must already be available offline. Its resource limits apply per process; aggregate tree memory/CPU are not controlled by a cgroup. Results include the command, manifest, exit code, stale-input state and isolation limits. A caller-declared `test` mode alone does not establish that meaningful tests ran.

The reproducible coordination check uses two official SDK clients and an actual failing-to-passing Java assertion:

```bash
uv run --script scripts/coordinated_smoke.py build/install/graphharness/bin/graphharness ui/dist
```

The separate `scripts/coordinated_agent_demo.py` runs two actual Codex CLI processes with an orchestrated reservation checkpoint. Its report checks server-issued sessions and event order; the [recorded evidence](reviews/real-agent-coordination.json) identifies the configured model and monitoring health. Scripted clients and real agents are reported separately.

## Capabilities and limits

| Surface | Current behavior |
| --- | --- |
| Live daemon / bridge | Search, source, structural context and per-language capabilities; Java semantic tools retain their backend restrictions |
| Browser | Grouped graph, source inspector, search, sessions, observed activity, cursor recovery, reservation conflicts and retained edit previews |
| Source versions | Snapshot bytes and hashes stay together; a current-source request rejects disk drift |
| Graph quality | Exact UTF-8 spans and parser containment where available; Joern or approximate Java calls can be incomplete. Unsupported spans remain null with diagnostics |
| Graph size | State has node, edge and encoded-byte limits; omitted counts remain visible; search can locate omitted symbols |
| Writes / reservations | Opt-in parser-confirmed Java method-body replacement; file leases, stale preimage rejection and commit receipts |
| Languages | Java, TS/TSX, JS/JSX and Python definitions; no inferred cross-language runtime calls or non-Java semantic edit support |
| Replay | Retained activity history; no exact reconstruction of old graph topology |

Package/type/method totals, clusters, hotspots and entrypoints in the legacy summary cover Java; `structural_nodes_by_language` covers all parsed languages. Dynamic constructs, anonymous declarations and some destructuring are omitted. Syntax errors and unavailable interpreters produce visible diagnostics.

External file changes have no inferred agent owner. A connected agent with no recent tool call may still be working elsewhere. Browser reconnection either resumes retained events or explicitly refreshes authoritative state. Source, prompts and credentials are excluded from the event journal by default.

## Verification

```bash
nix develop --command gradle test installDist --console=plain
uv run --script scripts/mcp_smoke.py build/install/graphharness/bin/graphharness
npm --prefix ui run build
npm --prefix ui audit
uv run --script scripts/mixed_mcp_smoke.py build/install/graphharness/bin/graphharness ui/dist
python3 scripts/installation_smoke.py build/install/graphharness
```

The MCP smoke script pins the official Python SDK and uses a temporary Java fixture. The browser check in `scripts/browser_smoke.py` exercises a running daemon and local browser approval; its arguments name the endpoint, launcher, fixture root and runtime directory. It requires Playwright and an installed Chromium. See the ledger for the exact environment used here. A passing script is not proof of an autonomous agent; real client evidence is recorded separately.

The final local synthetic benchmark used 1,001 method nodes: activity DOM insertion was 72ms p95 over 30 scripted calls, initial indexing took 6.87s, and an external edit appeared in the updated graph after 7.64s. The three-second refresh target was missed; see the [measured evidence](reviews/performance-accepted.json).

## Troubleshooting

- No Python/TypeScript symbols: check `get_capabilities` or the browser diagnostics, then install Python/Node and rebuild with `npm --prefix parsers ci`. Restart the daemon after changing runtime availability; unavailable parser results are cached for unchanged files.
- Pairing expired: start pairing again and authorize the newly displayed code within 60 seconds. Use the same runtime directory as the daemon.
- Source became stale: wait for indexing, search again and reread before planning. A committed edit can be visible on disk while the graph is still indexing.
- Validation blocked: inspect returned isolation diagnostics. Install bubblewrap and util-linux `prlimit`, enable supported Linux user namespaces, and ensure dependencies are available offline. Validation does not silently fall back to running on the host.
- Slow large-repository indexing: reduce generated/vendor inputs and inspect omitted counts. The current release does not guarantee a three-second refresh; performance evidence records the measured result.

## Direct stdio compatibility

`graphharness stdio ROOT` (or `graphharness ROOT`) runs the original standalone server using newline MCP. `graphharness legacy-stdio ROOT` retains the old Content-Length framing for the demo scripts. These modes own separate snapshots, have no live coordination guarantee, and are not the live multi-agent route.

Legacy edit tools remain experimental: rename is regex based and method-body boundaries are not the new safe parser-backed implementation. They are not exposed by the live daemon. Use scratch checkouts for legacy edit experiments. The original `scripts/session_demo.py`, `benchmark_demo.py` and `edit_demo.py` explicitly select compatibility framing.

`nix build` remains the standalone JVM package; the Gradle distribution above is the documented live build that includes browser assets. No hosted service, public launch or competition submission has been deployed by this work.
