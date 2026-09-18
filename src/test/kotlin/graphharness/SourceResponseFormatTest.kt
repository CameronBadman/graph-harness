package graphharness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceResponseFormatTest {
    @Test
    fun roundTripKeepsLiteralUnicodeCrLfAndAllContext() {
        val node = node("method", "Example.java")
        val source = slice(node, "int value() {\r\n    return \"😀\".length();\r\n}")
        val original = bundle(listOf(node), listOf(source))
        val encoded = roundTrip(original)
        val entry = encoded.requiredArray("nodes").values.single().asObject()
        assertEquals(node, entry["node"])
        val encodedSource = entry.requiredArray("sources").values.single().asObject()
        assertEquals(source["source"], encodedSource["source"])
        assertEquals(setOf("node_id", "file", "file_hash", "language", "line_range", "byte_span", "provenance"),
            encodedSource.requiredArray("inherited_fields").values.map { it.asString() }.toSet())
        assertEquals(emptyJsonObject(), encodedSource["extra"])
    }

    @Test
    fun roundTripKeepsAmbiguityWithZeroSourceSlices() {
        val original = bundle(listOf(node("first", "a/Example.java"), node("second", "b/Example.java")), emptyList())
        val encoded = roundTrip(original)
        assertTrue(encoded.requiredArray("nodes").values.all { it.asObject().requiredArray("sources").values.isEmpty() })
        assertEquals(original["notes"], encoded.fields.getValue("context").asObject()["notes"])
    }

    @Test
    fun roundTripRestoresInterleavedMultipleSlicesInOriginalOrder() {
        val first = node("first", "A.java")
        val second = node("second", "B.java")
        val sources = listOf(slice(second, "second before"), slice(first, "first"), slice(second, "second after"))
        val encoded = roundTrip(bundle(listOf(first, second), sources))
        val entries = encoded.requiredArray("nodes").values.map { it.asObject() }
        assertEquals(listOf(1), entries[0].requiredArray("sources").values.map { it.asObject().fields.getValue("source_index").asInt() })
        assertEquals(listOf(0, 2), entries[1].requiredArray("sources").values.map { it.asObject().fields.getValue("source_index").asInt() })
    }

    @Test
    fun duplicateIdsAndUnmatchedSlicesStayExplicitAndUnattached() {
        val first = node("duplicate", "A.java")
        val second = node("duplicate", "B.java")
        val sources = listOf(slice(first, "ambiguous"), jObject("node_id" to "missing", "source" to "unknown"),
            jObject("source" to "without identity"), JNull)
        val encoded = roundTrip(bundle(listOf(first, second), sources))
        assertTrue(encoded.requiredArray("nodes").values.all { it.asObject().requiredArray("sources").values.isEmpty() })
        assertEquals(sources, encoded.requiredArray("unmatched_sources").values.map { it.asObject().fields.getValue("data") })
    }

    @Test
    fun differingProvenanceSpansAndAdditionalMetadataAreRetained() {
        val node = node("method", "Example.java")
        val source = slice(node, "partial source").let { JObject(LinkedHashMap(it.fields).apply {
            put("provenance", JString("alternate-parser"))
            put("byte_span", jObject("start" to 11, "end" to 17))
            put("line_range", jObject("start" to 3, "end" to 4))
            put("truncated", JBoolean(true))
        }) }
        val encoded = roundTrip(bundle(listOf(node), listOf(source)))
        val encodedSource = encoded.requiredArray("nodes").values.single().asObject().requiredArray("sources").values.single().asObject()
        val extra = encodedSource.fields.getValue("extra").asObject()
        listOf("provenance", "byte_span", "line_range", "truncated").forEach { assertEquals(source[it], extra[it]) }
        assertFalse(encodedSource.requiredArray("inherited_fields").values.any { it.asString() == "provenance" })
    }

    @Test
    fun absentAndNullFieldsRemainDistinct() {
        val node = jObject("id" to "method", "file" to JNull, "provenance" to "parser")
        val sources = listOf(
            jObject("node_id" to "method", "file" to JNull, "language" to JNull, "source" to JNull),
            jObject("node_id" to "method", "provenance" to JNull),
        )
        val encoded = roundTrip(bundle(listOf(node), sources))
        val encodedSources = encoded.requiredArray("nodes").values.single().asObject().requiredArray("sources").values.map { it.asObject() }
        assertEquals(JNull, encodedSources[0]["source"])
        assertFalse(encodedSources[1].fields.containsKey("source"))
        assertEquals(JNull, encodedSources[0].fields.getValue("extra").asObject()["language"])
        assertEquals(JNull, encodedSources[1].fields.getValue("extra").asObject()["provenance"])
    }

    @Test
    fun unexpectedBundleShapesAreLeftUnchanged() {
        listOf(jObject("focus_nodes" to JNull, "source_slices" to emptyList<String>()),
            jObject("focus_nodes" to emptyList<String>()), JString("diagnostic"))
            .forEach { assertEquals(it, SourceResponseFormat.encode(it)) }
    }

    @Test
    fun decoderRejectsDuplicateAndMissingIndexesRatherThanDroppingSources() {
        val node = node("method", "Example.java")
        val encoded = SourceResponseFormat.encode(bundle(listOf(node), listOf(slice(node, "code")))).asObject()
        val duplicate = JObject(LinkedHashMap(encoded.fields).apply {
            put("unmatched_sources", jValue(listOf(jObject("source_index" to 0, "data" to "duplicate"))))
        })
        assertFailsWith<IllegalArgumentException> { SourceResponseFormat.decode(duplicate) }
        val missing = JObject(LinkedHashMap(encoded.fields).apply {
            put("unmatched_sources", jValue(listOf(jObject("source_index" to 2, "data" to "gap"))))
        })
        assertFailsWith<IllegalArgumentException> { SourceResponseFormat.decode(missing) }
    }

    private fun roundTrip(original: JObject): JObject {
        val encoded = SourceResponseFormat.encode(original).asObject()
        val serialized = MiniJson.parse(encoded.stringify())
        assertEquals(original, SourceResponseFormat.decode(serialized))
        assertEquals(encoded, SourceResponseFormat.encode(SourceResponseFormat.decode(serialized)))
        return encoded
    }

    private fun node(id: String, file: String) = jObject("id" to id, "file" to file,
        "file_hash" to "a".repeat(64), "language" to "java", "line_range" to jObject("start" to 2, "end" to 8),
        "byte_span" to jObject("start" to 10, "end" to 50), "provenance" to "java-parser", "qualified_name" to "Example.value")

    private fun slice(node: JObject, source: String) = JObject(LinkedHashMap(node.fields).apply {
        remove("id")
        remove("qualified_name")
        put("node_id", node.fields.getValue("id"))
        put("source", JString(source))
    })

    private fun bundle(nodes: List<JsonValue>, sources: List<JsonValue>) = jObject(
        "response_format" to "navigation-v1", "focus_nodes" to nodes, "source_slices" to sources,
        "snapshot_id" to "snapshot", "snapshot_state" to jObject("pending_rebuild" to true),
        "relationships" to listOf(jObject("from" to "a", "to" to "b", "provenance" to "observed")),
        "notes" to listOf("ambiguous_target", "source_slices_truncated_for_budget"), "backend" to "fallback")
}
