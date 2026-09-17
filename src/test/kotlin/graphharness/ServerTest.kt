package graphharness

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerTest {
    @Test
    fun newlineTransportHandlesLifecycleAndNotifications() {
        val output = run(
            """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"client","version":"1"}}}
            {"jsonrpc":"2.0","method":"notifications/initialized"}
            {"jsonrpc":"2.0","method":"tools/call","params":{"name":"get_summary_map","arguments":{}}}
            {"jsonrpc":"2.0","id":"ping","method":"ping"}
            """.trimIndent() + "\n",
        )

        assertFalse(output.startsWith("Content-Length:"))
        val responses = output.trimEnd().lines().map { MiniJson.parse(it).asObject() }
        assertEquals(2, responses.size)
        assertEquals("2025-06-18", responses[0].fields.getValue("result").asObject().requiredString("protocolVersion"))
        assertEquals("ping", responses[1].fields.getValue("id").asString())
        assertTrue(responses[1].fields.getValue("result").asObject().fields.isEmpty())
    }

    @Test
    fun protocolErrorsAndToolFailuresHaveDifferentShapes() {
        val output = run(
            """
            {"jsonrpc":"2.0","id":1,"method":"missing/method"}
            {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"missing_tool","arguments":{}}}
            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{}}
            {"jsonrpc":"2.0","method":"missing/notification"}
            {"jsonrpc":"2.0"}
            """.trimIndent() + "\n",
        ).trimEnd().lines().map { MiniJson.parse(it).asObject() }

        assertEquals(4, output.size)
        assertEquals("-32601", output[0].fields.getValue("error").asObject().fields.getValue("code").stringify())
        val toolResult = output[1].fields.getValue("result").asObject()
        assertTrue((toolResult.fields.getValue("isError") as JBoolean).value)
        assertFalse(toolResult.fields.containsKey("structuredContent"))
        assertEquals("-32602", output[2].fields.getValue("error").asObject().fields.getValue("code").stringify())
        assertEquals("-32600", output[3].fields.getValue("error").asObject().fields.getValue("code").stringify())
    }

    @Test
    fun malformedAndOversizedNewlineMessagesReturnErrorsAndRecover() {
        val server = server(maxFrameBytes = 80)
        val output = ByteArrayOutputStream()
        server.run(
            ByteArrayInputStream("not json\n${"x".repeat(100)}\n{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n".toByteArray()),
            output,
        )

        val responses = output.toString(StandardCharsets.UTF_8).trimEnd().lines().map { MiniJson.parse(it).asObject() }
        assertEquals(3, responses.size)
        assertEquals("-32700", responses[0].fields.getValue("error").asObject().fields.getValue("code").stringify())
        assertEquals("-32600", responses[1].fields.getValue("error").asObject().fields.getValue("code").stringify())
        assertEquals("1", responses[2].fields.getValue("id").stringify())
    }

    @Test
    fun contentLengthCompatibilityUsesUtf8ByteLengthsAndEofIsGraceful() {
        val payload = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\",\"clientInfo\":{\"name\":\"🦄\"}}}"
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        val input = "Content-Length: ${bytes.size}\r\n\r\n$payload".toByteArray(StandardCharsets.UTF_8)
        val output = ByteArrayOutputStream()
        server().run(ByteArrayInputStream(input), output, McpTransport.CONTENT_LENGTH)

        val result = output.toString(StandardCharsets.UTF_8)
        assertTrue(result.startsWith("Content-Length: "))
        val body = result.substringAfter("\r\n\r\n")
        assertEquals("2025-06-18", MiniJson.parse(body).asObject().fields.getValue("result").asObject().requiredString("protocolVersion"))
        assertNull(run("").takeIf { it.isNotEmpty() })
    }

    @Test
    fun initializationNegotiatesSupportedVersionAndKeepsLegacyInitializeCompatible() {
        val negotiated = run(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\"}}\n",
        )
        val response = MiniJson.parse(negotiated.trim()).asObject()
        assertEquals("2025-06-18", response.fields.getValue("result").asObject().requiredString("protocolVersion"))

        val missingVersion = MiniJson.parse(
            run("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n").trim(),
        ).asObject()
        assertEquals("-32602", missingVersion.fields.getValue("error").asObject().fields.getValue("code").stringify())

        val output = ByteArrayOutputStream()
        val payload = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\",\"params\":{}}"
        val body = payload.toByteArray(StandardCharsets.UTF_8)
        server().run(
            ByteArrayInputStream("Content-Length: ${body.size}\r\n\r\n$payload".toByteArray(StandardCharsets.UTF_8)),
            output,
            McpTransport.CONTENT_LENGTH,
        )
        val legacyResponse = MiniJson.parse(output.toString(StandardCharsets.UTF_8).substringAfter("\r\n\r\n")).asObject()
        assertEquals("2025-06-18", legacyResponse.fields.getValue("result").asObject().requiredString("protocolVersion"))
    }

    @Test
    fun exposesDefinitionsAndReusableInvocation() {
        val server = server()
        assertTrue(server.toolDefinitions().any { it.name == "get_summary_map" })
        val error = runCatching { server.invokeTool("unknown", emptyJsonObject()) }.exceptionOrNull()
        assertTrue(error?.message?.contains("Unknown tool") == true)
    }

    private fun run(input: String): String {
        val output = ByteArrayOutputStream()
        server().run(ByteArrayInputStream(input.toByteArray(StandardCharsets.UTF_8)), output)
        return output.toString(StandardCharsets.UTF_8)
    }

    private fun server(maxFrameBytes: Int = 1_048_576): GraphHarnessServer =
        GraphHarnessServer(SnapshotManager(createTempDirectory("graphharness-server-test"), useJoern = false), maxFrameBytes = maxFrameBytes)
}
