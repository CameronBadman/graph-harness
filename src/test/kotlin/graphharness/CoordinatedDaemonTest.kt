package graphharness

import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoordinatedDaemonTest {
    @Test
    fun bridgeHeartbeatRenewsReservationAndEofReleasesIt() {
        fixture().use { fixture ->
            val toBridge = PipedOutputStream()
            val input = PipedInputStream(toBridge)
            val output = PipedOutputStream()
            val fromBridge = PipedInputStream(output).bufferedReader()
            val bridge = LiveBridge(fixture.root, fixture.runtimeDirectory, "bridge agent")
            val runner = thread { bridge.run(input, output) }
            fun send(message: JObject) { toBridge.write((message.stringify() + "\n").toByteArray()); toBridge.flush() }
            send(jObject("jsonrpc" to "2.0", "id" to 1, "method" to "initialize", "params" to jObject("protocolVersion" to "2025-06-18")))
            assertTrue(MiniJson.parse(fromBridge.readLine()).asObject().fields.containsKey("result"))
            send(jObject("jsonrpc" to "2.0", "method" to "notifications/initialized"))
            send(jObject("jsonrpc" to "2.0", "id" to 2, "method" to "tools/call", "params" to jObject("name" to "acquire_edit_lease", "arguments" to jObject("file" to "A.java"))))
            val acquired = MiniJson.parse(fromBridge.readLine()).asObject().optionalObject("result")!!.optionalObject("structuredContent")!!
            val initialExpiry = acquired.requiredString("expires_at")
            try {
                val deadline = System.nanoTime() + 15_000_000_000L
                var renewed = false
                while (System.nanoTime() < deadline && !renewed) {
                    val state = fixture.state()
                    val lease = (state["leases"] as JArray).values.single().asObject()
                    renewed = lease.requiredString("expires_at") > initialExpiry
                    if (!renewed) Thread.sleep(100)
                }
                assertTrue(renewed, "The real bridge heartbeat did not renew its lease")
            } finally {
                toBridge.close()
                runner.join(5000)
                bridge.close()
                fromBridge.close()
            }
            assertFalse(runner.isAlive)
            assertTrue((fixture.state()["leases"] as JArray).values.isEmpty())
        }
    }

    @Test
    fun staleEpochCannotWriteAndAuthenticatedReaderCanReadActualPlanPreview() {
        fixture().use { fixture ->
            val current = fixture.manager.current()
            val target = current.methodInfos.values.single { it.simpleName == "value" }
            val plan = fixture.call("1", "plan_edit", jObject("node_id" to target.id, "snapshot_id" to current.id,
                "expected_file_hash" to current.fileHashes["A.java"], "new_body" to "return 2;")).optionalObject("result")!!
            val lease = fixture.call("2", "acquire_edit_lease", jObject("file" to "A.java")).optionalObject("result")!!
            val request = jObject("schema_version" to 1, "operation_id" to "3", "name" to "apply_edit", "arguments" to jObject("edit_id" to plan.requiredString("edit_id"),
                "lease_id" to lease.requiredString("lease_id"), "generation" to lease.requiredString("generation")))
            val denied = fixture.request("POST", "/tools/call", request, epoch = "old-epoch")
            assertEquals(409, denied.statusCode())
            assertEquals("daemon_restarted", MiniJson.parse(denied.body()).asObject().optionalObject("error")!!.requiredString("code"))
            assertTrue(Files.readString(fixture.root.resolve("A.java")).contains("return 1;"))
            val preview = fixture.request("GET", "/edits/" + plan.requiredString("edit_id"))
            assertEquals(200, preview.statusCode())
            val body = MiniJson.parse(preview.body()).asObject().optionalObject("result")!!.optionalObject("preview")!!
            assertTrue(body.requiredString("before").contains("return 1;"))
            assertTrue(body.requiredString("after").contains("return 2;"))
        }
    }

    private fun fixture(): Fixture {
        val root = createTempDirectory("graphharness-coordinated-http")
        root.resolve("A.java").writeText("class A {\n int value() { return 1; }\n}\n")
        val runtimeDirectory = createTempDirectory("graphharness-coordinated-runtime")
        val runtime = LocalRuntime.acquire(root, runtimeDirectory)
        val manager = SnapshotManager(root, useJoern = false)
        val ui = createTempDirectory("graphharness-coordinated-ui")
        ui.resolve("index.html").writeText("<main>GraphHarness</main>")
        return Fixture(root, runtimeDirectory, runtime, manager, LiveDaemon(manager, runtime, ui, coordinatedWrites = true))
    }

    private class Fixture(val root: java.nio.file.Path, val runtimeDirectory: java.nio.file.Path, val runtime: LocalRuntime,
        val manager: SnapshotManager, val daemon: LiveDaemon) : AutoCloseable {
        private val client = HttpClient.newHttpClient()
        private val credential: String = run {
            val response = client.send(HttpRequest.newBuilder(URI(daemon.endpoint + "/sessions"))
                .header("Authorization", "Bearer ${runtime.bootstrapCredential}")
                .POST(HttpRequest.BodyPublishers.ofString(jObject("schema_version" to 1, "agent_label" to "integration reader",
                    "client" to jObject("name" to "integration-test", "version" to "1")).stringify())).build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode())
            MiniJson.parse(response.body()).asObject().requiredString("session_credential")
        }
        fun request(method: String, path: String, body: JObject? = null, epoch: String = runtime.epoch): HttpResponse<String> = client.send(
            HttpRequest.newBuilder(URI(daemon.endpoint + path)).header("Authorization", "Bearer $credential").header("X-GraphHarness-Epoch", epoch)
                .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.stringify()) } ?: HttpRequest.BodyPublishers.noBody())
                .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString())
        fun state(): JObject = MiniJson.parse(request("GET", "/state").body()).asObject()
        fun call(operation: String, name: String, args: JObject): JObject {
            val response = request("POST", "/tools/call", jObject("schema_version" to 1, "operation_id" to operation, "name" to name, "arguments" to args))
            assertEquals(200, response.statusCode(), response.body())
            return MiniJson.parse(response.body()).asObject()
        }
        override fun close() { daemon.close(); manager.close() }
    }
}
