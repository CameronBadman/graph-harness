package graphharness

import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class LiveBridge(
    private val root: Path,
    private val runtimeDirectory: Path = LocalRuntime.defaultDirectory(),
    private val agentLabel: String = "GraphHarness MCP",
    private val clientName: String = "graphharness-bridge",
    private val clientVersion: String = "0.1.0",
    private val navigationProfile: Boolean = false,
) : AutoCloseable {
    private var descriptor: RuntimeDescriptor? = null
    private var sessionId: String? = null
    private var credential: String? = null
    private var nextOperation = 0L
    private var initialized = false
    private var initializedNotification = false
    private val heartbeats = Executors.newSingleThreadScheduledExecutor()

    fun run(input: InputStream, output: OutputStream) {
        connect()
        try {
            while (true) {
                val line = try {
                    readLine(input)
                } catch (failure: BridgeFailure) {
                    writeLine(output, error(JNull, -32600, failure.message.orEmpty()))
                    continue
                } ?: break
                val response = process(line)
                if (response != null) writeLine(output, response)
            }
        } finally {
            close()
        }
    }

    override fun close() {
        val id = sessionId
        if (id != null) runCatching { request("DELETE", "/sessions/current", null, session = true) }
        sessionId = null
        credential = null
        heartbeats.shutdownNow()
    }

    private fun connect() {
        val discovered = LocalRuntime.discover(root, runtimeDirectory)
        descriptor = discovered
        val created = request(
            "POST", "/sessions",
            jObject("schema_version" to 1, "agent_label" to agentLabel,
                "client" to jObject("name" to clientName, "version" to clientVersion)),
            bootstrap = true,
        ).asObject()
        val result = created
        sessionId = result.requiredString("session_id")
        credential = result.requiredString("session_credential")
        heartbeats.scheduleAtFixedRate({ runCatching { request("POST", "/sessions/heartbeat", jObject("schema_version" to 1), session = true) } }, 10, 10, TimeUnit.SECONDS)
    }

    private fun process(line: String): JObject? {
        if (preparseDepth(line) > MAX_JSON_DEPTH) return error(JNull, -32600, "Invalid Request")
        val parsed = try { MiniJson.parse(line) } catch (_: Exception) { return error(JNull, -32700, "Parse error") }
        val message = parsed as? JObject ?: return error(JNull, -32600, "Invalid Request")
        if (message.optionalString("jsonrpc") != "2.0") return error(JNull, -32600, "Invalid Request")
        val id = message["id"]
        if (message.fields.containsKey("id") && id !is JString && id !is JNumber && id != JNull) {
            return error(JNull, -32600, "Invalid Request")
        }
        val notification = !message.fields.containsKey("id")
        val method = message.optionalString("method") ?: return error(JNull, -32600, "Invalid Request")
        val paramsValue = message["params"]
        if (paramsValue != null && paramsValue !is JObject) {
            return if (notification) null else error(id ?: JNull, -32602, "Invalid params")
        }
        if (notification) {
            if (method == "notifications/initialized" && initialized) initializedNotification = true
            return null
        }
        val params = paramsValue as? JObject ?: emptyJsonObject()
        return try {
            when (method) {
                "initialize" -> initialize(id ?: JNull, params)
                "ping" -> result(id ?: JNull, emptyJsonObject())
                "tools/list" -> requireInitialized(id ?: JNull) ?: listTools(id ?: JNull)
                "tools/call" -> requireInitialized(id ?: JNull) ?: toolCall(id ?: JNull, params)
                else -> error(id ?: JNull, -32601, "Method not found")
            }
        } catch (failure: BridgeFailure) {
            if (method == "tools/call") toolError(id ?: JNull, failure) else error(id ?: JNull, -32603, "Daemon request failed")
        } catch (_: Exception) {
            error(id ?: JNull, -32603, "Internal error")
        }
    }

    private fun toolCall(id: JsonValue, params: JObject): JObject {
        val name = params.optionalString("name") ?: return error(id, -32602, "Invalid params")
        if (navigationProfile && name !in NavigationProfile.tools) return error(id, -32602, "Tool is not available in the navigation profile")
        val argumentsValue = params["arguments"]
        if (argumentsValue != null && argumentsValue !is JObject) return error(id, -32602, "Invalid params")
        val arguments = argumentsValue as? JObject ?: emptyJsonObject()
        val operation = (++nextOperation).toString()
        val response = request("POST", "/tools/call", jObject("schema_version" to 1, "operation_id" to operation, "name" to name, "arguments" to arguments), session = true).asObject()
        val original = response.fields.getValue("result")
        val value = if (navigationProfile) NavigationProfile.compact(name, original) else original
        return result(id, jObject(
            "content" to listOf(mapOf("type" to "text", "text" to value.stringify())),
            "structuredContent" to (value as? JObject),
            "isError" to false,
        ))
    }

    private fun listTools(id: JsonValue): JObject {
        val available = request("GET", "/tools", null, session = true).asObject().fields.getValue("tools") as JArray
        val selected = if (navigationProfile) JArray(available.values.filter {
            (it as? JObject)?.optionalString("name") in NavigationProfile.tools
        }) else available
        return result(id, jObject("tools" to selected))
    }

    private fun initialize(id: JsonValue, params: JObject): JObject {
        if (params.optionalString("protocolVersion") == null) return error(id, -32602, "Invalid params")
        if (initialized) return error(id, -32600, "Invalid Request")
        val response = result(id, jObject(
            "protocolVersion" to "2025-06-18",
            "serverInfo" to jObject("name" to "graphharness-live-bridge", "version" to clientVersion),
            "capabilities" to jObject("tools" to emptyMap<String, Any?>()),
            "instructions" to NavigationProfile.instructions,
        ))
        initialized = true
        return response
    }

    private fun requireInitialized(id: JsonValue): JObject? =
        if (initialized && initializedNotification) null else error(id, -32600, "Server not initialized")

    private fun request(method: String, path: String, body: JObject?, bootstrap: Boolean = false, session: Boolean = false): JsonValue {
        val active = descriptor ?: throw BridgeFailure("Daemon is unavailable.")
        val connection = URL(active.endpoint + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 5_000
        connection.readTimeout = if (path == "/tools/call") 150_000 else 10_000
        connection.setRequestProperty("Accept", "application/json")
        if (bootstrap) connection.setRequestProperty("Authorization", "Bearer ${active.bootstrapCredential}")
        if (session) {
            connection.setRequestProperty("Authorization", "Bearer ${credential ?: throw BridgeFailure("Session unavailable.")}")
            connection.setRequestProperty("X-GraphHarness-Epoch", active.daemonEpoch)
        }
        if (body != null) {
            val bytes = body.stringify().toByteArray(StandardCharsets.UTF_8)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(bytes) }
        }
        val status = connection.responseCode
        val input = if (status in 200..299) connection.inputStream else connection.errorStream
        val parsed = input?.use { MiniJson.parse(decodeUtf8(readBounded(it, MAX_RESPONSE_BYTES))) }
        connection.disconnect()
        if (status !in 200..299) {
            val envelope = parsed as? JObject
            val error = envelope?.optionalObject("error")
            throw BridgeFailure(
                message = error?.optionalString("message") ?: "Daemon request failed.",
                code = error?.optionalString("code") ?: "daemon_request_failed",
                details = error,
                operationId = envelope?.optionalString("operation_id"),
            )
        }
        return parsed ?: throw BridgeFailure("Daemon returned no response.")
    }

    private fun result(id: JsonValue, value: JsonValue): JObject = jObject("jsonrpc" to "2.0", "id" to id, "result" to value)
    private fun error(id: JsonValue, code: Int, message: String): JObject = jObject("jsonrpc" to "2.0", "id" to id, "error" to jObject("code" to code, "message" to message))
    private fun toolError(id: JsonValue, failure: BridgeFailure): JObject = result(id, jObject(
        "content" to listOf(mapOf("type" to "text", "text" to failure.message.orEmpty())),
        "structuredContent" to jObject("error" to (failure.details ?: jObject("code" to failure.code, "message" to failure.message.orEmpty())), "operation_id" to failure.operationId),
        "isError" to true,
    ))

    private fun readLine(input: InputStream): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val next = input.read()
            if (next == -1) return if (bytes.isEmpty()) null else decodeClientUtf8(bytes.toByteArray())
            if (next == '\n'.code) return decodeClientUtf8(bytes.toByteArray())
            if (bytes.size >= MAX_FRAME_BYTES) {
                while (input.read().let { it != -1 && it != '\n'.code }) {
                }
                throw BridgeFailure("MCP message exceeds 1 MiB.")
            }
            if (next != '\r'.code) bytes += next.toByte()
        }
    }

    private fun writeLine(output: OutputStream, payload: JObject) {
        output.write(payload.stringify().toByteArray(StandardCharsets.UTF_8))
        output.write('\n'.code)
        output.flush()
    }

    private fun readBounded(input: InputStream, maximum: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count == -1) return output.toByteArray()
            if (output.size() + count > maximum) throw BridgeFailure("Daemon response exceeds the configured limit.")
            output.write(buffer, 0, count)
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        throw BridgeFailure("Daemon returned malformed UTF-8.")
    }

    private fun decodeClientUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        throw BridgeFailure("Invalid UTF-8 MCP message.")
    }

    private fun preparseDepth(payload: String): Int {
        var depth = 0
        var maximum = 0
        var quoted = false
        var escaped = false
        payload.forEach { character ->
            if (quoted) {
                if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; maximum = maxOf(maximum, depth) }
                '}', ']' -> depth--
            }
        }
        return maximum
    }

    private class BridgeFailure(
        message: String,
        val code: String = "bridge_failure",
        val details: JObject? = null,
        val operationId: String? = null,
    ) : RuntimeException(message)

    private companion object {
        const val MAX_RESPONSE_BYTES = 1_048_576
        const val MAX_FRAME_BYTES = 1_048_576
        const val MAX_JSON_DEPTH = 16
    }
}
