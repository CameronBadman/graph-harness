package graphharness

internal object NavigationProfile {
    val tools = setOf("build_context_bundle", "search_graph", "get_source", "get_source_batch")
    const val instructions = "GraphHarness indexes this checkout. For symbol or multi-file code tasks, start with build_context_bundle using the task's qualified symbols and file paths. Check notes and source_slices before another read. Resolve ambiguity with search_graph, then get_source or get_source_batch. Use native tools when retrieval is missing or insufficient, and for edits/tests. Java relationships depend on the backend; other languages provide structural context only. Source versions and truncation are explicit."
    val codexInstructions = """
        GraphHarness is connected for this checkout. Before source exploration, retrieve a context bundle. In Codex code mode, discover and call it in one functions.exec invocation:
        const t = ALL_TOOLS.find(t => t.name.includes("graphharness") && t.name.endsWith("build_context_bundle"));
        if (t) { const r = await tools[t.name]({task: "YOUR TASK WITH QUALIFIED SYMBOLS OR FILE PATHS", token_budget: 1800}); text(r.structuredContent ?? r.content); }
        else { text("GraphHarness bundle unavailable; use native inspection."); }
        Substitute the actual task, not the placeholder. If tools are directly exposed, call build_context_bundle directly. Do not print the whole tool catalogue or both representations of the response. Check source_slices and notes; use native tools if context is missing or insufficient, and for edits/tests.
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
