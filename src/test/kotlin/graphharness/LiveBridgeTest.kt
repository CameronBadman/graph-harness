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

    private fun fixture(): Fixture {
        val root = createTempDirectory("graphharness-bridge-root")
        Files.writeString(root.resolve("Example.java"), "class Example { int value() { return 1; } }")
        val ui = createTempDirectory("graphharness-bridge-ui")
        Files.writeString(ui.resolve("index.html"), "<main>GraphHarness</main>")
        val runtimeDirectory = createTempDirectory("graphharness-bridge-runtime")
        Files.setPosixFilePermissions(runtimeDirectory, ownerOnlyDirectoryPermissions)
        val runtime = LocalRuntime.acquire(root, runtimeDirectory)
        val manager = SnapshotManager(root, useJoern = false)
        val daemon = LiveDaemon(manager, runtime, ui)
        return Fixture(manager, daemon, LiveBridge(root, runtimeDirectory))
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
