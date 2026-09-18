package graphharness

internal object NavigationProfile {
    val tools = setOf("build_context_bundle", "search_graph", "get_source", "get_source_batch")
    const val instructions = "GraphHarness indexes this checkout. For symbol or multi-file code tasks, use build_context_bundle when it can replace source inspection, using the task's qualified symbols and file paths. Reuse returned source before another read; check notes and source_slices, or context and nodes[].sources in source-v1. In source-v1, each inherited_fields entry takes its value from the enclosing node (node_id uses node.id); extra contains differing metadata. Resolve ambiguity with search_graph, then get_source or get_source_batch. If replace_node_body is advertised, it can replace supported Java method bodies using the returned snapshot_id and file_hash. Use native tools when retrieval or editing is insufficient, and for project tests. Java relationships depend on the backend; other languages provide structural context only. Source versions and truncation are explicit."
    val codexInstructions = """
        GraphHarness is connected for this checkout. For symbol exploration, consider a context bundle when it can replace a source read. In Codex code mode, discover and call it in one functions.exec invocation:
        const t = ALL_TOOLS.find(t => t.name.includes("graphharness") && t.name.endsWith("build_context_bundle"));
        if (t) { const r = await tools[t.name]({task: "YOUR TASK WITH QUALIFIED SYMBOLS OR FILE PATHS", token_budget: 1800}); text(r.structuredContent ?? r.content); }
        else { text("GraphHarness bundle unavailable; use native inspection."); }
        Substitute the actual task, not the placeholder. If tools are directly exposed, call build_context_bundle directly. Do not print the whole tool catalogue or both representations of the response. Reuse returned source before another read. Check source_slices and notes, or context and nodes[].sources in source-v1: inherited_fields entries take their values from the enclosing node (node_id uses node.id), while extra contains differing metadata. If replace_node_body is advertised, consider its node_id, snapshot_id, expected_file_hash and new_body arguments for supported Java method-body replacements; native editing remains available. Use native tools if context or editing support is insufficient, and run real project tests.
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
