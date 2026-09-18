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

## Experimental response format and node editing

Two independent options are available on the navigation bridge:

```toml
args = ["bridge", "/absolute/path/to/checkout", "Codex", "--navigation", "--response-format", "source-v1", "--node-edits"]
```

`--response-format source-v1` groups source slices under their nodes and inherits
identical metadata instead of repeating it. Source text, versions, relationships
and limitations are preserved; retrieval selection and budget stay unchanged.
Only context bundles use this format. Omit the option for the existing format.

`--node-edits` requires a daemon started with `--allow-edits`. It adds one tool:
`replace_node_body(node_id, snapshot_id, expected_file_hash, new_body)`. Supply
fresh identifiers/hash from source and the complete body statements, without the
outer braces. The existing limit is 4,096 characters per argument. Only supported
Java method bodies can change; imports, signatures and other languages cannot.

The tool acquires and releases its own brief file reservation. An existing
reservation, including your own, returns `lease_busy` and remains untouched. The
small receipt reports commit/hash/indexing state; the browser retains the preview.
Syntax validation is not project testing. Use native tools to run the actual tests.
After a lost response or restart, reread source before another edit; a repeated
MCP call is not automatically a replay of the original write.

Both options are experimental and disabled by default. Smaller response bytes or
fewer coordination calls alone do not establish lower total Codex token use. The
[30-run comparison](../reviews/token-interface/FINDINGS.md) found 66.8% more input
and 77.6% more output with the new format than native Codex; the combined options
used 58.4% more input and 58.3% more output. All tasks passed, but no scored run
called the node editor. The experiment therefore does not establish the token
efficiency of actually writing through nodes.

The separate [eligibility diagnostic](../reviews/token-interface/edit-eligibility.json)
found an inherited Joern restriction: methods using custom parameter types can
return `target_not_found` because backend-qualified types do not match compiler
source spellings. Two of the three benchmark targets were rejected by this check.
Do not infer edit support from navigation support or body size alone. The editor
fails closed; native editing remains available.
