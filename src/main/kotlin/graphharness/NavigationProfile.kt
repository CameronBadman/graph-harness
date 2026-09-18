package graphharness

internal object NavigationProfile {
    val tools = setOf("build_context_bundle", "search_graph", "get_source", "get_source_batch")
    const val instructions = "GraphHarness indexes this checkout. For symbol or multi-file code tasks, use build_context_bundle when it can replace source inspection, using qualified symbols and file paths. Reuse returned source; check notes and source_slices, or context and nodes[].sources in source-v1. In source-v1, inherited_fields take values from the enclosing node (node_id uses node.id); extra contains differing metadata. For ambiguous overloads, inspect candidate signatures and paths, then pass the selected id as node_id to build_context_bundle or get_source; do not put node ids in task text. If replace_node_body is advertised, use it for an in-scope Java method-body change of at most 4096 characters, with fresh node_id, snapshot_id, expected_file_hash and complete new_body statements without outer braces. Use native editing when the tool is absent, the change is unsupported/out of scope/too large, or an actual tool error prevents it. A successful write requires a committed=true receipt; reread after an uncertain outcome. Always run native project tests. Java relationships depend on the backend; other languages provide structural context only. Source versions and truncation are explicit."
    val codexInstructions = """
        GraphHarness is connected for this checkout. For symbol exploration, use a context bundle when it can replace a source read. In one Codex functions.exec call, discover the bundle and optional node editor, then retrieve context:
        ```javascript
        const find = name => ALL_TOOLS.find(t => t.name.includes("graphharness") && t.name.endsWith(name));
        const bundle = find("build_context_bundle"), edit = find("replace_node_body");
        text({replace_node_body_available: Boolean(edit)});
        if (bundle) { const r = await tools[bundle.name]({task: "YOUR TASK WITH QUALIFIED SYMBOLS OR FILE PATHS", token_budget: 1800}); text(r.structuredContent ?? r.content); }
        else { text("GraphHarness bundle unavailable; use native inspection."); }
        ```
        Substitute the actual task. If tools are directly exposed, call them directly. Print each response once using structuredContent ?? content, not the whole catalogue or both representations. Reuse source_slices and notes, or context and nodes[].sources in source-v1: inherited_fields use the enclosing node (node_id uses node.id), while extra contains differing metadata.
        For ambiguous overloads, select the candidate matching the requested file and parameter signature. Pass its exact id as the node_id argument to build_context_bundle({node_id: candidate.id, token_budget: 1800}) or get_source({node_id: candidate.id}); never put a node id into task text or choose an arbitrary first candidate. Use native inspection if the candidates do not identify the required source.
        When replace_node_body is advertised and the requested change is a supported Java method body of at most 4096 characters, use it before native editing. Use the selected node id, snapshot_id and file_hash from the same fresh source response (source-v1 stores snapshot_id in context). Supply the complete replacement body statements, without outer braces; imports, signatures and other files are outside this operation. With those actual values substituted, invoke:
        ```javascript
        const edit = ALL_TOOLS.find(t => t.name.includes("graphharness") && t.name.endsWith("replace_node_body"));
        if (edit) { const r = await tools[edit.name]({node_id: "SELECTED_NODE_ID", snapshot_id: "RETURNED_SNAPSHOT_ID", expected_file_hash: "RETURNED_FILE_HASH", new_body: "COMPLETE_REPLACEMENT_BODY_STATEMENTS"}); text(r.structuredContent ?? r.content); }
        else { text("GraphHarness node editing unavailable; use native editing."); }
        ```
        Use native editing if the tool is absent, the change is unsupported/out of scope/too large, or an actual tool error prevents it. Do not assume a rejection without calling an eligible advertised tool. Only a receipt with committed=true confirms the write; after a lost response or uncertain outcome, reread before retrying or falling back. Run real project tests with native tools after either editing route; syntax_checked is not a test pass.
    """.trimIndent()

    fun compact(name: String, value: JsonValue): JsonValue {
        val original = value as? JObject ?: return value
        if (name !in tools) return value
        val fields = LinkedHashMap(original.fields)
        listOf("build_duration_ms", "generated_at").forEach(fields::remove)
        if (name == "build_context_bundle") {
            listOf("task", "node_id", "clusters", "entrypoints", "summary_mode").forEach(fields::remove)
        }
        listOf("focus_nodes", "results").forEach { key ->
            val nodes = fields[key] as? JArray ?: return@forEach
            fields[key] = JArray(nodes.values.map { node ->
                val objectNode = node as? JObject ?: return@map node
                JObject(LinkedHashMap(objectNode.fields).apply {
                    listOf("visibility", "annotations", "complexity", "loc").forEach(::remove)
                })
            })
        }
        fields["response_format"] = JString("navigation-v1")
        return JObject(fields)
    }
}
