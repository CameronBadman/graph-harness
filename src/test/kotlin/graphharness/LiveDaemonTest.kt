package graphharness

import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveDaemonTest {
    @Test
    fun bootstrapCreatesAgentAndReadOnlyAuthorityIsEnforced() {
        fixture().use { fixture ->
            val unauthenticated = fixture.send("GET", "/state")
            assertEquals(403, unauthenticated.status)

            val session = fixture.createSession()
            val state = fixture.send("GET", "/state", credential = session.credential)
            assertEquals(200, state.status)
            assertTrue(state.body.contains("\"daemon_epoch\":\"${fixture.runtime.epoch}\""))

            val disabled = fixture.send(
                "POST", "/tools/call",
                jObject("schema_version" to 1, "operation_id" to "1", "name" to "apply_edit", "arguments" to emptyJsonObject()),
                session.credential,
            )
            assertEquals(422, disabled.status)
            assertTrue(disabled.body.contains("unsupported_operation"))
        }
    }

    @Test
    fun foreignOriginsAreRejectedAndEventCursorResetsExplicitly() {
        fixture().use { fixture ->
            val session = fixture.createSession()
            val foreign = HttpRequest.newBuilder(URI(fixture.daemon.endpoint + "/state"))
                .header("Authorization", "Bearer ${session.credential}")
                .header("X-GraphHarness-Epoch", fixture.runtime.epoch)
                .header("Origin", "http://evil.example")
                .GET().timeout(Duration.ofSeconds(2)).build()
            assertEquals(403, HttpClient.newHttpClient().send(foreign, HttpResponse.BodyHandlers.ofString()).statusCode())

            val reset = fixture.send("GET", "/events?after=999", credential = session.credential)
            assertEquals(200, reset.status)
            assertTrue(reset.body.contains("event: reset"))
            assertTrue(reset.body.contains("cursor_expired"))
        }
    }

    @Test
    fun staticServingStaysWithinUiDirectory() {
        fixture().use { fixture ->
            val index = fixture.send("GET", "/")
            assertEquals(200, index.status)
            assertTrue(index.body.contains("GraphHarness"))
            val escaped = fixture.send("GET", "/assets/../../outside")
            assertEquals(404, escaped.status)
        }
    }

    @Test
    fun browserPairingUsesOriginBoundCookiesAndInitialStateDiscoversEpoch() {
        fixture().use { fixture ->
            val client = HttpClient.newHttpClient()
            val pair = browserRequest(client, fixture.daemon.endpoint, "/observer/pair", "{}")
            assertEquals(200, pair.statusCode())
            val code = MiniJson.parse(pair.body()).asObject().requiredString("pairing_code")
            val pairCookie = pair.headers().firstValue("Set-Cookie").orElseThrow().substringAfter("gh_pair=").substringBefore(';')

            val approved = fixture.send("POST", "/observer/approve", jObject("schema_version" to 1, "pairing_code" to code), bootstrap = true)
            assertEquals(200, approved.status)

            val claim = browserRequest(client, fixture.daemon.endpoint, "/observer/claim", "{}", "gh_pair=$pairCookie")
            assertEquals(200, claim.statusCode())
            val observerCookie = claim.headers().allValues("Set-Cookie").first { it.startsWith("gh_observer=") }.substringAfter("gh_observer=").substringBefore(';')

            val initialState = HttpRequest.newBuilder(URI(fixture.daemon.endpoint + "/state"))
                .header("Origin", fixture.daemon.endpoint)
                .header("Cookie", "gh_observer=$observerCookie")
                .GET().timeout(Duration.ofSeconds(2)).build()
            assertEquals(200, client.send(initialState, HttpResponse.BodyHandlers.ofString()).statusCode())

            val missingOrigin = HttpRequest.newBuilder(URI(fixture.daemon.endpoint + "/sessions/heartbeat"))
                .header("Cookie", "gh_observer=$observerCookie")
                .POST(HttpRequest.BodyPublishers.ofString("{\"schema_version\":1}"))
                .timeout(Duration.ofSeconds(2)).build()
            assertEquals(403, client.send(missingOrigin, HttpResponse.BodyHandlers.ofString()).statusCode())
        }
    }

    @Test
    fun activeEventStreamsDoNotBlockToolRequests() {
        fixture().use { fixture ->
            val session = fixture.createSession()
            val client = HttpClient.newHttpClient()
            val request = HttpRequest.newBuilder(URI(fixture.daemon.endpoint + "/events?after=0"))
                .header("Authorization", "Bearer ${session.credential}")
                .header("X-GraphHarness-Epoch", fixture.runtime.epoch)
                .GET().timeout(Duration.ofSeconds(2)).build()
            val streams = listOf(
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()),
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()),
            ).map { it.get() }
            try {
                val tools = fixture.send("GET", "/tools", credential = session.credential)
                assertEquals(200, tools.status)
            } finally {
                streams.forEach { it.body().close() }
            }
        }
    }

    @Test
    fun failedOperationsReplayWithCorrelationAndRejectChangedPayloads() {
        fixture().use { fixture ->
            val session = fixture.createSession()
            val request = jObject("schema_version" to 1, "operation_id" to "1", "name" to "apply_edit", "arguments" to emptyJsonObject())

            val first = fixture.send("POST", "/tools/call", request, session.credential)
            val replay = fixture.send("POST", "/tools/call", request, session.credential)
            val changed = fixture.send("POST", "/tools/call", jObject(
                "schema_version" to 1, "operation_id" to "1", "name" to "plan_edit", "arguments" to emptyJsonObject(),
            ), session.credential)

            assertEquals(422, first.status)
            assertEquals(422, replay.status)
            assertEquals("unsupported_operation", errorCode(first.body))
            assertEquals("unsupported_operation", errorCode(replay.body))
            assertEquals("1", MiniJson.parse(replay.body).asObject().requiredString("operation_id"))
            assertEquals(409, changed.status)
            assertEquals("operation_reused", errorCode(changed.body))
            assertEquals("1", MiniJson.parse(changed.body).asObject().requiredString("operation_id"))
        }
    }

    @Test
    fun inspectionFailuresKeepBrowserRequestCorrelation() {
        fixture().use { fixture ->
            val session = fixture.createSession()
            val response = fixture.send("POST", "/inspect", jObject(
                "schema_version" to 1, "request_id" to "browser-request-1", "name" to "apply_edit", "arguments" to emptyJsonObject(),
            ), session.credential)

            assertEquals(422, response.status)
            assertEquals("unsupported_operation", errorCode(response.body))
            assertEquals("browser-request-1", MiniJson.parse(response.body).asObject().requiredString("request_id"))
        }
    }

    @Test
    fun pendingPairClaimsAreRateLimitedWithoutWaiting() {
        fixture().use { fixture ->
            val client = HttpClient.newHttpClient()
            val pair = browserRequest(client, fixture.daemon.endpoint, "/observer/pair", "{}")
            val cookie = pair.headers().firstValue("Set-Cookie").orElseThrow().substringAfter("gh_pair=").substringBefore(';')

            assertEquals(200, pair.statusCode())
            assertEquals(202, browserRequest(client, fixture.daemon.endpoint, "/observer/claim", "{}", "gh_pair=$cookie").statusCode())
            assertEquals(429, browserRequest(client, fixture.daemon.endpoint, "/observer/claim", "{}", "gh_pair=$cookie").statusCode())
        }
    }

    @Test
    fun stateEndpointBoundsLargeGraphResponses() {
        val methods = (0 until 500).joinToString(separator = "") { index ->
            "  int m${index}${"a".repeat(900)}() { return 0; }\n"
        }
        fixture("class Example {\n$methods}\n").use { fixture ->
            val session = fixture.createSession()
            val response = fixture.send("GET", "/state", credential = session.credential)

            assertEquals(200, response.status)
            assertTrue(response.body.toByteArray(StandardCharsets.UTF_8).size <= 1_048_576)
            val snapshot = MiniJson.parse(response.body).asObject().fields.getValue("snapshot").asObject()
            assertTrue((snapshot.fields.getValue("omitted_node_count") as JNumber).raw.toLong() > 0)
        }
    }

    private fun errorCode(body: String): String = MiniJson.parse(body).asObject().fields.getValue("error").asObject().requiredString("code")

    private fun fixture(source: String = "class Example { int value() { return 1; } }"): Fixture {
        val root = createTempDirectory("graphharness-live-root")
        Files.writeString(root.resolve("Example.java"), source)
        val ui = createTempDirectory("graphharness-live-ui")
        Files.writeString(ui.resolve("index.html"), "<main>GraphHarness</main>")
        val runtimeDirectory = createTempDirectory("graphharness-live-runtime")
        Files.setPosixFilePermissions(runtimeDirectory, ownerOnlyDirectoryPermissions)
        val runtime = LocalRuntime.acquire(root, runtimeDirectory)
        val manager = SnapshotManager(root, useJoern = false)
        return Fixture(runtime, manager, LiveDaemon(manager, runtime, ui))
    }

    private fun browserRequest(client: HttpClient, endpoint: String, path: String, body: String, cookie: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI(endpoint + path))
            .header("Origin", endpoint)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(2))
            .POST(HttpRequest.BodyPublishers.ofString(body))
        if (cookie != null) request.header("Cookie", cookie)
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private class Fixture(val runtime: LocalRuntime, private val manager: SnapshotManager, val daemon: LiveDaemon) : AutoCloseable {
        fun createSession(): Session {
            val response = send(
                "POST", "/sessions",
                jObject("schema_version" to 1, "agent_label" to "test-agent", "client" to jObject("name" to "test", "version" to "1")),
                bootstrap = true,
            )
            assertEquals(200, response.status)
            val body = MiniJson.parse(response.body).asObject()
            return Session(body.requiredString("session_id"), body.requiredString("session_credential"))
        }

        fun send(method: String, path: String, body: JObject? = null, credential: String? = null, bootstrap: Boolean = false, origin: String? = null): Response {
            val connection = URL(daemon.endpoint + path).openConnection() as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            if (credential != null) {
                connection.setRequestProperty("Authorization", "Bearer $credential")
                connection.setRequestProperty("X-GraphHarness-Epoch", runtime.epoch)
            }
            if (bootstrap) connection.setRequestProperty("Authorization", "Bearer ${runtime.bootstrapCredential}")
            if (origin != null) connection.setRequestProperty("Origin", origin)
            if (body != null) {
                val bytes = body.stringify().toByteArray(StandardCharsets.UTF_8)
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val input = if (status in 200..299) connection.inputStream else connection.errorStream
            val text = input?.use { it.readBytes().toString(StandardCharsets.UTF_8) }.orEmpty()
            connection.disconnect()
            return Response(status, text)
        }

        override fun close() {
            daemon.close()
            manager.close()
        }
    }

    private data class Session(val id: String, val credential: String)
    private data class Response(val status: Int, val body: String)

    private companion object {
        val ownerOnlyDirectoryPermissions = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
    }
}
