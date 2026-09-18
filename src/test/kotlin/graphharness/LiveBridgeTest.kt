package graphharness

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LiveBridgeTest {
    @Test
    fun bridgeRejectsToolsBeforeLifecycleCompletionAndDeepRequests() {
        fixture().use { fixture ->
            val deep = "[".repeat(17) + "0" + "]".repeat(17)
            val payload = """
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get_capabilities","arguments":{}}}
                {"jsonrpc":"2.0","id":3,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}
                {"jsonrpc":"2.0","id":4,"method":"tools/list"}
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                {"jsonrpc":"2.0","id":5,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}
                {"jsonrpc":"2.0","id":6,"method":"ping","params":$deep}
                """.trimIndent() + "\n"
            val output = ByteArrayOutputStream()

            fixture.bridge.run(ByteArrayInputStream(payload.toByteArray(StandardCharsets.UTF_8)), output)

            val responses = output.toString(StandardCharsets.UTF_8).trimEnd().lines().map { MiniJson.parse(it).asObject() }
            assertEquals(6, responses.size)
            assertEquals("-32600", errorCode(responses[0]))
            assertEquals("-32600", errorCode(responses[1]))
            assertEquals("2025-06-18", responses[2].fields.getValue("result").asObject().requiredString("protocolVersion"))
            assertEquals("-32600", errorCode(responses[3]))
            assertEquals("-32600", errorCode(responses[4]))
            assertEquals("-32600", errorCode(responses[5]))
        }
    }

    @Test
    fun bridgeHandlesSdkStyleReadCallsAndRejectsInvalidProtocolMessages() {
        fixture().use { fixture ->
            val payload = """
                {"jsonrpc":"2.0","id":0,"method":"tools/list"}
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"sdk","version":"1"}}}
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                {"jsonrpc":"2.0","method":"tools/call","params":{"name":"get_capabilities"}}
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"get_capabilities"}}
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"search_graph","arguments":{"query":"Example"}}}
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"get_capabilities","arguments":[]}}
                {"jsonrpc":"2.0","id":6,"method":"tools/list","params":[]}
                {"jsonrpc":"2.0","id":{},"method":"ping"}
                {"jsonrpc":"2.0","id":7,"method":"initialize","params":{}}
                {"jsonrpc":"2.0","id":8,"method":"tools/call","params":{"name":"apply_edit","arguments":{}}}
                """.trimIndent() + "\n"
            val input = payload.toByteArray(StandardCharsets.UTF_8) + byteArrayOf(0xff.toByte(), '\n'.code.toByte())
            val output = ByteArrayOutputStream()

            fixture.bridge.run(ByteArrayInputStream(input), output)

            val responses = output.toString(StandardCharsets.UTF_8).trimEnd().lines().map { MiniJson.parse(it).asObject() }
            assertEquals(11, responses.size)
            assertEquals("-32600", errorCode(responses[0]))
            assertEquals("2025-06-18", responses[1].fields.getValue("result").asObject().requiredString("protocolVersion"))
            assertTrue(responses[2].fields.getValue("result").asObject().fields.getValue("tools") is JArray)
            val call = responses[3].fields.getValue("result").asObject()
            assertFalse((call.fields.getValue("isError") as JBoolean).value)
            assertTrue(call.fields.getValue("structuredContent") is JObject)
            assertFalse((responses[4].fields.getValue("result").asObject().fields.getValue("isError") as JBoolean).value)
            assertEquals("-32602", errorCode(responses[5]))
            assertEquals("-32602", errorCode(responses[6]))
            assertEquals("-32600", errorCode(responses[7]))
            assertEquals("-32602", errorCode(responses[8]))
            val toolError = responses[9].fields.getValue("result").asObject()
            assertTrue((toolError.fields.getValue("isError") as JBoolean).value)
            val structuredError = toolError.fields.getValue("structuredContent").asObject()
            assertEquals("unsupported_operation", structuredError.fields.getValue("error").asObject().requiredString("code"))
            assertTrue(structuredError.optionalString("operation_id") != null)
            assertEquals("-32600", errorCode(responses[10]))
        }
    }

    private fun errorCode(response: JObject): String = response.fields.getValue("error").asObject().fields.getValue("code").stringify()

    @Test
    fun navigationProfilePreservesSourceAndVersionAndRejectsUnadvertisedTools() {
        fixture(navigation = true).use { fixture ->
            val payload = """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"build_context_bundle","arguments":{"task":"Example.value"}}}
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"apply_edit","arguments":{}}}
                """.trimIndent() + "\n"
            val output = ByteArrayOutputStream()
            fixture.bridge.run(ByteArrayInputStream(payload.toByteArray(StandardCharsets.UTF_8)), output)
            val responses = output.toString(StandardCharsets.UTF_8).trimEnd().lines().map { MiniJson.parse(it).asObject() }
            assertTrue(responses[0].fields.getValue("result").asObject().requiredString("instructions").contains("source_slices"))
            val tools = responses[1].fields.getValue("result").asObject().fields.getValue("tools") as JArray
            assertEquals(NavigationProfile.tools, tools.values.map { it.asObject().requiredString("name") }.toSet())
            val call = responses[2].fields.getValue("result").asObject()
            val value = call.fields.getValue("structuredContent").asObject()
            val text = ((call.fields.getValue("content") as JArray).values.single()).asObject().requiredString("text")
            assertEquals(value.stringify(), text)
            assertEquals("navigation-v1", value.requiredString("response_format"))
            assertFalse(value.fields.containsKey("task"))
            assertFalse(value.fields.containsKey("entrypoints"))
            assertTrue(value.requiredString("snapshot_id").isNotBlank())
            assertTrue(value.fields.containsKey("snapshot_state"))
            assertTrue(value.fields.containsKey("semantic_level"))
            assertTrue(value.fields.containsKey("notes"))
            val sources = value.fields.getValue("source_slices") as JArray
            assertTrue(sources.values.any { it.asObject().requiredString("source").contains("return 1;") })
            assertTrue(sources.values.all { it.asObject().requiredString("file_hash").length == 64 })
            assertEquals("-32602", errorCode(responses[3]))
        }
    }

    @Test
    fun compactProjectionRetainsEverySourceRelationshipAndCaveat() {
        val source = jObject("node_id" to "n", "source" to "😀\r\nreturn 7;", "file_hash" to "a".repeat(64),
            "byte_span" to jObject("start" to 2, "end" to 19))
        val original = jObject("task" to "inspect", "source_slices" to listOf(source),
            "relationships" to listOf(jObject("from" to "n", "to" to "m", "resolution" to "best_effort")),
            "notes" to listOf("source_slices_truncated_for_budget", "structural_context_only"),
            "snapshot_id" to "snapshot", "snapshot_state" to jObject("pending_rebuild" to true),
            "semantic_level" to "best_effort", "analysis_engine" to "fallback-parser")
        val compact = NavigationProfile.compact("build_context_bundle", original).asObject()
        listOf("source_slices", "relationships", "notes", "snapshot_id", "snapshot_state", "semantic_level", "analysis_engine")
            .forEach { key -> assertEquals(original.fields[key], compact.fields[key]) }
    }

    @Test
    fun sourceFormatChangesOnlyBundlesAndKeepsBothMcpRepresentationsConsistent() {
        fixture(navigation = true, responseFormat = "source-v1").use { fixture ->
            val responses = call(fixture, """
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"build_context_bundle","arguments":{"task":"Example.value"}}}
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"search_graph","arguments":{"query":"Example.value"}}}
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"replace_node_body","arguments":{}}}
            """.trimIndent())
            val bundleCall = responses[1].fields.getValue("result").asObject()
            val bundle = bundleCall.fields.getValue("structuredContent").asObject()
            assertEquals("source-v1", bundle.requiredString("format"))
            assertEquals(bundle.stringify(), bundleCall.requiredArray("content").values.single().asObject().requiredString("text"))
            val restored = SourceResponseFormat.decode(bundle)
            assertEquals("navigation-v1", restored.requiredString("response_format"))
            assertTrue(restored.requiredString("snapshot_id").isNotBlank())
            assertTrue(restored.requiredArray("source_slices").values.any { it.asObject().requiredString("source").contains("return 1;") })
            val search = responses[2].fields.getValue("result").asObject().fields.getValue("structuredContent").asObject()
            assertEquals("navigation-v1", search.requiredString("response_format"))
            assertFalse(search.fields.containsKey("format"))
            assertEquals("-32602", errorCode(responses[3]))
        }
    }

    @Test
    fun nodeEditingProfileRequiresWriteCapableDaemonBeforeMcpStartup() {
        fixture(navigation = true, nodeEdits = true).use { fixture ->
            val output = ByteArrayOutputStream()
            val failure = assertFailsWith<IllegalArgumentException> {
                fixture.bridge.run(ByteArrayInputStream(ByteArray(0)), output)
            }
            assertTrue(failure.message.orEmpty().contains("--allow-edits"))
            assertEquals(0, output.size())
        }
    }

    @Test
    fun nodeEditingProfileAdvertisesOnlyFourReadsAndOneWrite() {
        fixture(navigation = true, nodeEdits = true, allowEdits = true).use { fixture ->
            val responses = call(fixture, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"apply_edit","arguments":{}}}
            """.trimIndent())
            val tools = responses[1].fields.getValue("result").asObject().requiredArray("tools")
            assertEquals(NavigationProfile.tools + "replace_node_body", tools.values.map { it.asObject().requiredString("name") }.toSet())
            assertEquals("-32602", errorCode(responses[2]))
        }
    }

    @Test
    fun fullBridgeDoesNotExposeOrDispatchNodeEditsWithoutOptIn() {
        fixture(allowEdits = true).use { fixture ->
            val responses = call(fixture, """
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"replace_node_body","arguments":{}}}
            """.trimIndent())
            val names = responses[1].fields.getValue("result").asObject().requiredArray("tools")
                .values.map { it.asObject().requiredString("name") }
            assertTrue("plan_edit" in names)
            assertFalse("replace_node_body" in names)
            assertEquals("-32602", errorCode(responses[2]))
        }
    }

    @Test
    fun experimentalBridgeFlagsAreExplicitAndDefaultsRemainUnchanged() {
        assertEquals(BridgeOptions("checkout", "Coding agent", false, "navigation-v1", false), bridgeOptions(listOf("checkout")))
        assertEquals(BridgeOptions("checkout", "Agent", true, "source-v1", true),
            bridgeOptions(listOf("checkout", "Agent", "--navigation", "--response-format", "source-v1", "--node-edits")))
        listOf(listOf("checkout", "--response-format", "source-v1"), listOf("checkout", "--node-edits"),
            listOf("checkout", "--navigation", "--response-format"), listOf("checkout", "--navigation", "--response-format", "unknown"),
            listOf("checkout", "--unknown")).forEach { arguments ->
            assertFailsWith<IllegalArgumentException> { bridgeOptions(arguments) }
        }
    }

    private fun call(fixture: Fixture, requests: String): List<JObject> {
        val payload = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}
            {"jsonrpc":"2.0","method":"notifications/initialized"}
        """.trimIndent() + "\n" + requests + "\n"
        val output = ByteArrayOutputStream()
        fixture.bridge.run(ByteArrayInputStream(payload.toByteArray(StandardCharsets.UTF_8)), output)
        return output.toString(StandardCharsets.UTF_8).trimEnd().lines().map { MiniJson.parse(it).asObject() }
    }

    private fun fixture(navigation: Boolean = false, responseFormat: String = "navigation-v1", nodeEdits: Boolean = false, allowEdits: Boolean = false): Fixture {
        val root = createTempDirectory("graphharness-bridge-root")
        Files.writeString(root.resolve("Example.java"), "class Example { int value() { return 1; } }")
        val ui = createTempDirectory("graphharness-bridge-ui")
        Files.writeString(ui.resolve("index.html"), "<main>GraphHarness</main>")
        val runtimeDirectory = createTempDirectory("graphharness-bridge-runtime")
        Files.setPosixFilePermissions(runtimeDirectory, ownerOnlyDirectoryPermissions)
        val runtime = LocalRuntime.acquire(root, runtimeDirectory)
        val manager = SnapshotManager(root, useJoern = false)
        val daemon = LiveDaemon(manager, runtime, ui, coordinatedWrites = allowEdits)
        return Fixture(manager, daemon, LiveBridge(root, runtimeDirectory, navigationProfile = navigation,
            responseFormat = responseFormat, nodeEdits = nodeEdits))
    }

    private class Fixture(private val manager: SnapshotManager, private val daemon: LiveDaemon, val bridge: LiveBridge) : AutoCloseable {
        override fun close() {
            bridge.close()
            daemon.close()
            manager.close()
        }
    }

    private companion object {
        val ownerOnlyDirectoryPermissions = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
    }
}
