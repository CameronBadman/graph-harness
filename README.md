# GraphHarness

GraphHarness is a local MCP-style stdio server that gives coding agents structural context for Java repositories before they open source files directly.

This prototype is intentionally read-only:

- snapshot-based Java project analysis
- topology-oriented summary and cluster expansion
- callers, callees, hierarchy, dependency, impact, and source retrieval tools
- local stdio transport using JSON-RPC with MCP-compatible `tools/list` and `tools/call`

## Status

This implementation now prefers a **Joern-backed** Java analysis path when Joern is installed. If Joern is unavailable, it falls back to the lightweight parser so the server still runs, but the intended path is Joern first.

## Run With Nix

```bash
nix build
./result/bin/graphharness /path/to/java/repo
```

Install Joern locally for the preferred analysis engine:

```bash
./scripts/install_joern.sh
export GRAPHHARNESS_JOERN_HOME="$HOME/.local/share/graphharness/joern"
```

For local development:

```bash
nix develop
kotlinc src/main/kotlin/graphharness/*.kt -include-runtime -d graphharness.jar
java -XX:+PerfDisableSharedMem -jar graphharness.jar /path/to/java/repo
```

## MCP-style Usage

The server speaks JSON-RPC 2.0 over stdio using `Content-Length` framing. It supports:

- `initialize`
- `notifications/initialized`
- `tools/list`
- `tools/call`

For a persistent demo session that behaves more like a real MCP client:

```bash
python3 scripts/session_demo.py ./result/bin/graphharness /path/to/java/repo
```

The available tools are:

- `get_summary_map`
- `get_cluster_detail`
- `get_node_detail`
- `get_call_paths`
- `search_graph`
- `get_callers`
- `get_callees`
- `get_implementations`
- `get_type_hierarchy`
- `get_dependencies`
- `get_impact`
- `get_source`
- `get_source_batch`

## Notes

- Every response includes a `snapshot_id`.
- File watching triggers background snapshot rebuilds with atomic swap on completion.
- Clustering is package-oriented in this prototype.
- The packaged launcher disables JVM shared perf data to avoid noisy `hsperfdata` warnings during CLI use.
- When Joern is installed, GraphHarness builds the project snapshot from a Joern-generated CPG and derives the tool responses from that graph model.
# code-agent
