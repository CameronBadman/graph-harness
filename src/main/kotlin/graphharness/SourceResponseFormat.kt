package graphharness

internal object SourceResponseFormat {
    private val inheritedKeys = linkedMapOf(
        "node_id" to "id",
        "file" to "file",
        "file_hash" to "file_hash",
        "language" to "language",
        "line_range" to "line_range",
        "byte_span" to "byte_span",
        "provenance" to "provenance",
    )

    fun encode(value: JsonValue): JsonValue {
        val bundle = value as? JObject ?: return value
        val nodes = bundle["focus_nodes"] as? JArray ?: return value
        val sources = bundle["source_slices"] as? JArray ?: return value
        val ids = nodes.values.map { (it as? JObject)?.optionalString("id") }
        val uniqueNodes = ids.withIndex().filter { it.value != null }.groupBy { it.value }
            .filterValues { it.size == 1 }.mapValues { it.value.single().index }
        val grouped = List(nodes.values.size) { mutableListOf<JsonValue>() }
        val unmatched = mutableListOf<JsonValue>()
        sources.values.forEachIndexed { index, original ->
            val slice = original as? JObject
            val nodeIndex = slice?.optionalString("node_id")?.let(uniqueNodes::get)
            if (slice == null || nodeIndex == null) {
                unmatched += jObject("source_index" to index, "data" to original)
                return@forEachIndexed
            }
            val node = nodes.values[nodeIndex].asObject()
            val inherited = inheritedKeys.filter { (sourceKey, nodeKey) ->
                slice.fields.containsKey(sourceKey) && node.fields.containsKey(nodeKey) && slice[sourceKey] == node[nodeKey]
            }.keys.toList()
            val extra = JObject(LinkedHashMap(slice.fields).apply {
                remove("source")
                inherited.forEach(::remove)
            })
            grouped[nodeIndex] += jObject(
                "source_index" to index,
                "source" to slice["source"],
                "inherited_fields" to inherited,
                "extra" to extra,
            )
        }
        return jObject(
            "format" to "source-v1",
            "context" to JObject(LinkedHashMap(bundle.fields).apply {
                remove("focus_nodes")
                remove("source_slices")
            }),
            "nodes" to nodes.values.mapIndexed { index, node -> jObject("node" to node, "sources" to grouped[index]) },
            "unmatched_sources" to unmatched,
        )
    }

    fun decode(value: JsonValue): JObject {
        val encoded = value.asObject()
        require(encoded.requiredString("format") == "source-v1") { "Unsupported source response format" }
        val context = encoded.fields.getValue("context").asObject()
        require("focus_nodes" !in context.fields && "source_slices" !in context.fields)
        val sources = sortedMapOf<Int, JsonValue>()
        fun add(index: Int, source: JsonValue) {
            require(index >= 0 && sources.putIfAbsent(index, source) == null) { "Invalid or duplicate source index" }
        }
        val nodes = encoded.requiredArray("nodes").values.map { entryValue ->
            val entry = entryValue.asObject()
            val node = entry.fields.getValue("node")
            entry.requiredArray("sources").values.forEach { sourceValue ->
                val source = sourceValue.asObject()
                val objectNode = node.asObject()
                val extra = source.fields.getValue("extra").asObject()
                require("source" !in extra.fields)
                val restored = LinkedHashMap(extra.fields)
                source.requiredArray("inherited_fields").values.forEach { keyValue ->
                    val key = keyValue.asString()
                    val nodeKey = inheritedKeys[key] ?: error("Invalid inherited source field")
                    require(key !in restored)
                    restored[key] = objectNode.fields.getValue(nodeKey)
                }
                source["source"]?.let { restored["source"] = it }
                add(source.fields.getValue("source_index").asInt(), JObject(restored))
            }
            node
        }
        encoded.requiredArray("unmatched_sources").values.forEach { entryValue ->
            val entry = entryValue.asObject()
            add(entry.fields.getValue("source_index").asInt(), entry.fields.getValue("data"))
        }
        require(sources.keys.toList() == (0 until sources.size).toList()) { "Non-contiguous source indexes" }
        return JObject(LinkedHashMap(context.fields).apply {
            put("focus_nodes", JArray(nodes))
            put("source_slices", JArray(sources.values.toList()))
        })
    }
}
