# GraphHarness

GraphHarness is a local MCP-style stdio server that gives coding agents structural context for Java repositories before they open source files directly.

This prototype is mostly read-only, with a narrow graph-guided edit path:

- snapshot-based Java project analysis
- topology-oriented summary and cluster expansion
- callers, callees, hierarchy, dependency, impact, and source retrieval tools
- preview-and-apply method body edits and method renames via `plan_edit` and `apply_edit`
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

For a simple benchmark that compares GraphHarness context use against a naive file-loading workflow:

```bash
python3 scripts/benchmark_demo.py ./result/bin/graphharness /path/to/java/repo
```

For an edit-focused demo that benchmarks full-body replacement, anchor patching, and method rename planning:

```bash
python3 scripts/edit_demo.py ./result/bin/graphharness /path/to/java/repo
```

That demo now exercises the intended edit loop:

- `get_edit_candidates`
- `verify_candidate`
- `plan_edit`
- `validate_edit`
- `get_agent_fitness`
- `get_cluster_fitness`
- `apply_edit` smoke test on a scratch copy

The available tools are:

- `get_summary_map`
- `get_edit_candidates`
- `verify_candidate`
- `plan_edit`
- `validate_edit`
- `get_agent_fitness`
- `get_cluster_fitness`
- `apply_edit`
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
- The current edit surface is intentionally narrow: `modify_method_body` and method-only `rename_node`, with preview diff generation and stale-file validation before apply.
- `modify_method_body` supports both full-body replacement and smaller anchor-based patch modes (`insert_before`, `insert_after`, `replace_line`).
- `get_edit_candidates` uses lightweight heuristics to suggest likely edit targets, operations, and starter payloads from a natural-language task description.
- `verify_candidate` is the cheap confirmation step for targeted edits; use it before `plan_edit` when `get_edit_candidates` returns `needs_disambiguation: true` or when the task wording is vague.
- `validate_edit` replays a planned edit, or validates an already-applied edit, on a scratch copy of the repo. It prefers Maven/Gradle test or compile commands and falls back to a local `javac -proc:none` syntax check when no project build tool is detected.
- When repo-aware validation is blocked by wrapper/bootstrap failures or environment constraints, `validate_edit` reports `attempted_validators`, marks the result as `degraded`, and falls back to syntax validation when possible.
- `validate_edit` scopes repo-aware validation to the nearest touched Maven/Gradle module when possible, and reports that choice as `validation_target`.
- `get_agent_fitness` reports a repo-wide structural fitness score, subscores, issues, and recommended actions for how well the codebase supports graph-guided agent workflows.
- `get_cluster_fitness` drills the same fitness model down to a single cluster so you can identify which subsystem is actually dragging the repo-wide score down.
# code-agent
