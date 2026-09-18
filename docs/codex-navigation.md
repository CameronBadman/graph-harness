# Using GraphHarness for Codex navigation

The optional navigation profile exposes four read-only tools: context bundles,
symbol/path search, single-source reads, and batched source reads. It omits project
orientation sections, timing fields, and node annotation/visibility/complexity
summaries. It retains source, hashes, byte/line spans, snapshot state, relationships,
provenance, and limitations. The full bridge remains the default.

The [24-run follow-up](../reviews/token-fix/FINDINGS.md) measured **18.2% more input
and 16.9% more output** for this profile than native Codex on three small synthetic
tasks. All tasks passed and retrieval was used in every profile run. This is an
optional experimental workflow, not a proven token optimization; mandatory
bundle-first use is not recommended on the strength of these results.

Start the daemon as documented in the README, then configure the bridge:

```toml
[mcp_servers.graphharness]
command = "/absolute/path/to/graphharness"
args = ["bridge", "/absolute/path/to/checkout", "Codex", "--navigation"]
required = true
```

If a custom runtime directory is used, pass the same `GRAPHHARNESS_RUNTIME_DIR` to
the bridge. Native Codex edits retain the existing external-writer guarantee boundary.

Print the short integration guidance:

```sh
graphharness codex-instructions
```

Include that text in the task instructions or the checkout's agent instructions
when using this workflow. It discovers and invokes the bundle in one code-mode
tool call and emits `structuredContent ?? content` once. Do not dump the whole tool
catalogue merely to locate a known tool. Native inspection remains available when
the graph lacks the required evidence.

This explicit setup matters in Codex CLI 0.154.0 code mode: a local mock-provider
diagnostic found MCP tools behind `ALL_TOOLS`, with initialization guidance visible
only after discovery. Printing the complete MCP result exposed both serialized
representations. This is a client-path observation, not every version or provider.

[Official MCP documentation](https://learn.chatgpt.com/docs/extend/mcp?surface=cli)
describes initialization guidance and `required` startup behavior. Guidance does
not force model compliance; the benchmark records actual graph usage.

Use qualified identifiers such as `OrderPolicy.accept`, or `web/orders.ts:resolve`.
Ambiguous names return candidate nodes without arbitrarily selecting their source.
Behavior-only descriptions are not semantic search. The bundle source budget
remains approximate; notes report omitted slices. Structural navigation does not
imply complete runtime call resolution.
