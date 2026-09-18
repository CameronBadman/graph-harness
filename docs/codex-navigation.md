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
when using this workflow. It discovers the bundle and optional node editor together,
invokes the bundle, and emits `structuredContent ?? content` once. The same guidance
applies to read-only and edit-enabled profiles; actual tool availability determines
the route. Do not dump the whole tool catalogue merely to locate a known tool.
Native inspection remains available when the graph lacks the required evidence.

This explicit setup matters in Codex CLI 0.154.0 code mode: a local mock-provider
diagnostic found MCP tools behind `ALL_TOOLS`, with initialization guidance visible
only after discovery. Printing the complete MCP result exposed both serialized
representations. This is a client-path observation, not every version or provider.

[Official MCP documentation](https://learn.chatgpt.com/docs/extend/mcp?surface=cli)
describes initialization guidance and `required` startup behavior. Guidance does
not force model compliance; the benchmark records actual graph usage.

Use qualified identifiers such as `OrderPolicy.accept`, or `web/orders.ts:resolve`.
Ambiguous names return candidate nodes without arbitrarily selecting their source.
For an overload, match the candidate's file and parameter signature, then pass its
exact ID as an argument: `build_context_bundle({node_id: candidate.id, token_budget: 1800})`
or `get_source({node_id: candidate.id})`. Putting the ID inside the natural-language
`task` field does not select that node. Do not choose the first candidate arbitrarily.
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

The printed guidance now directs agents to use the advertised node editor for an
eligible body-only change before native editing. It includes an executable call
example with the fresh selected node ID, snapshot ID and file hash. An absent tool,
unsupported or oversized change, or actual tool error allows native editing.
This is a guided workflow, different from merely making the editor available in
the earlier experiment; it is not evidence that the route saves tokens.

The tool acquires and releases its own brief file reservation. An existing
reservation, including your own, returns `lease_busy` and remains untouched. The
small receipt reports commit/hash/indexing state; the browser retains the preview.
Only `committed=true` confirms a write; a rejected call is not a successful edit.
Syntax validation is not project testing. Use native tools to run the actual tests.
After a lost response or restart, reread source before another edit; a repeated
MCP call is not automatically a replay of the original write.

Both options are experimental and disabled by default. Smaller response bytes or
fewer coordination calls alone do not establish lower total Codex token use. The
[earlier 30-run comparison](../reviews/token-interface/FINDINGS.md) found 66.8% more input
and 77.6% more output with the new format than native Codex; the combined options
used 58.4% more input and 58.3% more output. All tasks passed, but no scored run
called the node editor. The experiment therefore does not establish the token
efficiency of actually writing through nodes.

The separate [eligibility diagnostic](../reviews/token-interface/edit-eligibility.json)
found an inherited Joern restriction in that study's frozen build: backend-qualified
custom parameter types did not match compiler source spellings, rejecting two of
the three targets with `target_not_found`. That historical result remains recorded.
Do not infer edit support from navigation support, a byte span or body size alone;
the current parser and version checks still decide eligibility and fail closed.

The current matcher resolves matching custom types from immutable snapshot
declarations and explicit imports while preserving full package identity, array
rank, generic structure and overload checks. It does not compare only short type
names. Ambiguous or unresolved wildcard/static imports, inherited type names and
backend-erased generics can still be unsupported. The declaration inventory is
bounded to 512 Java files and 8 MiB; an incomplete inventory or parse errors can
prevent reference-type resolution. Primitive, exact qualified and locally declared
cases can remain usable. The [guided rerun](../reviews/token-node-followup/FINDINGS.md)
verified real Joern-backed commits for all three target families before launching
models. All 12 node-enabled runs then used the tool, and all 30 repairs passed.
Node-only increased total input/output by 57.4%/45.0% versus native; combined
increased them by 52.7%/34.1%. No graph arm met the token-saving gate. These reused
development tasks do not establish unfamiliar-repository performance.

Body indentation can still introduce diff noise: two scored runs made an extra
node commit solely to fix indentation, and one retained excess indentation inside
the allowed method body. The behavior/scope gate does not grade formatting. Those
costs and outcomes are retained in the report.
