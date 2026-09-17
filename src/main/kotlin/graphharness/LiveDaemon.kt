package graphharness

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LiveDaemon(
    manager: SnapshotManager,
    private val runtime: LocalRuntime,
    private val uiDirectory: Path,
    port: Int = 0,
    coordinatedWrites: Boolean = false,
) : AutoCloseable {
    private val state = LiveState(runtime.repositoryId, runtime.epoch, manager, coordinatedWrites)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
    private val executor = ThreadPoolExecutor(16, 16, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue(32), ThreadPoolExecutor.AbortPolicy())
    private val maintenance = Executors.newSingleThreadScheduledExecutor()
    private val streamCount = AtomicInteger()
    private val pairs = linkedMapOf<String, Pairing>()
    private val pairRequests = ArrayDeque<Long>()
    private val pairLock = Any()
    private val origin: String
    val endpoint: String

    init {
        server.executor = executor
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
        endpoint = "http://127.0.0.1:${server.address.port}"
        origin = endpoint
        runtime.publish(endpoint)
        maintenance.scheduleAtFixedRate({ runCatching { state.expire(); state.publishSnapshot() } }, 250, 250, TimeUnit.MILLISECONDS)
    }

    override fun close() {
        server.stop(0)
        executor.shutdownNow()
        maintenance.shutdownNow()
        runtime.close()
    }

    private fun handle(exchange: HttpExchange) {
        try {
            requireHost(exchange)
            val path = exchange.requestURI.path
            when {
                path == "/" || path.startsWith("/assets/") -> static(exchange)
                path == "/sessions" && exchange.requestMethod == "POST" -> createSession(exchange)
                path == "/sessions/heartbeat" && exchange.requestMethod == "POST" -> heartbeat(exchange)
                path == "/sessions/current" && exchange.requestMethod == "DELETE" -> closeSession(exchange)
                path == "/tools" && exchange.requestMethod == "GET" -> tools(exchange)
                path == "/tools/call" && exchange.requestMethod == "POST" -> call(exchange)
                path == "/state" && exchange.requestMethod == "GET" -> state(exchange)
                path == "/events" && exchange.requestMethod == "GET" -> events(exchange)
                path == "/inspect" && exchange.requestMethod == "POST" -> inspect(exchange)
                path.startsWith("/edits/") && exchange.requestMethod == "GET" -> editPreview(exchange)
                path == "/observer/pair" && exchange.requestMethod == "POST" -> pair(exchange)
                path == "/observer/approve" && exchange.requestMethod == "POST" -> approve(exchange)
                path == "/observer/claim" && exchange.requestMethod == "POST" -> claim(exchange)
                else -> failure(exchange, LiveFailure("not_found", 404, "Route not found."))
            }
        } catch (failure: LiveFailure) {
            failure(exchange, failure)
        } catch (_: Exception) {
            failure(exchange, LiveFailure("invalid_request", 400, "Invalid request."))
        } finally {
            if (exchange.responseCode == -1) exchange.close()
        }
    }

    private fun createSession(exchange: HttpExchange) {
        requireBootstrap(exchange)
        val body = body(exchange)
        requireSchema(body)
        val label = body.optionalString("agent_label") ?: throw LiveFailure("invalid_request", 400, "agent_label is required.")
        val client = body.optionalObject("client") ?: throw LiveFailure("invalid_request", 400, "client is required.")
        if (client.optionalString("name") == null || client.optionalString("version") == null) {
            throw LiveFailure("invalid_request", 400, "client name and version are required.")
        }
        val session = state.createSession(label, "agent")
        json(exchange, 200, state.envelope(
            "session_id" to session.id,
            "session_credential" to session.credential,
            "role" to "agent",
            "heartbeat_interval_ms" to 10_000,
            "expires_in_ms" to 30_000,
        ))
    }

    private fun heartbeat(exchange: HttpExchange) {
        val session = requireSession(exchange, setOf("agent", "observer"))
        state.heartbeat(session)
        json(exchange, 200, state.envelope("ok" to true))
    }

    private fun closeSession(exchange: HttpExchange) {
        state.closeSession(requireSession(exchange, setOf("agent", "observer")))
        json(exchange, 200, state.envelope("ok" to true))
    }

    private fun tools(exchange: HttpExchange) {
        requireSession(exchange, setOf("agent"))
        json(exchange, 200, state.envelope("tools" to state.definitions()))
    }

    private fun call(exchange: HttpExchange) {
        val session = requireSession(exchange, setOf("agent"))
        val body = body(exchange)
        requireSchema(body)
        val name = body.optionalString("name") ?: throw LiveFailure("invalid_request", 400, "name is required.")
        val operationId = body.optionalString("operation_id") ?: throw LiveFailure("invalid_request", 400, "operation_id is required.")
        val arguments = body.optionalObject("arguments") ?: throw LiveFailure("invalid_request", 400, "arguments is required.")
        try {
            json(exchange, 200, state.call(session, name, arguments, operationId))
        } catch (failure: LiveFailure) {
            failure(exchange, failure, operationId = operationId)
        }
    }

    private fun state(exchange: HttpExchange) {
        requireSession(exchange, setOf("agent", "observer"), allowCookieWithoutEpoch = true)
        state.expire()
        state.publishSnapshot()
        json(exchange, 200, state.state())
    }

    private fun inspect(exchange: HttpExchange) {
        val session = requireSession(exchange, setOf("agent", "observer"))
        val body = body(exchange)
        requireSchema(body)
        val name = body.optionalString("name") ?: throw LiveFailure("invalid_request", 400, "name is required.")
        val requestId = body.optionalString("request_id") ?: throw LiveFailure("invalid_request", 400, "request_id is required.")
        val arguments = body.optionalObject("arguments") ?: throw LiveFailure("invalid_request", 400, "arguments is required.")
        try {
            json(exchange, 200, state.inspect(session, name, arguments, requestId))
        } catch (failure: LiveFailure) {
            failure(exchange, failure, requestId = requestId)
        }
    }

    private fun editPreview(exchange: HttpExchange) {
        val session = requireSession(exchange, setOf("agent", "observer"))
        val id = exchange.requestURI.path.removePrefix("/edits/")
        if (id.length > 80 || '/' in id) throw LiveFailure("invalid_request", 400, "Invalid edit ID.")
        json(exchange, 200, state.editPreview(session, id))
    }

    private fun pair(exchange: HttpExchange) {
        requireBrowserOrigin(exchange)
        body(exchange)
        val pairing = synchronized(pairLock) {
            purgePairs()
            if (pairRequests.size >= 5) throw LiveFailure("pair_limit", 429, "Pairing rate limit reached.")
            if (pairs.size >= 8) throw LiveFailure("pair_limit", 429, "Too many pending browser pairs.")
            pairRequests.addLast(System.nanoTime())
            Pairing(pairCode(), liveSecret(), System.nanoTime() + 60_000_000_000L).also { pairs[it.code] = it }
        }
        json(exchange, 200, state.envelope("pairing_code" to pairing.code, "expires_in_ms" to 60_000),
            "Set-Cookie" to "gh_pair=${pairing.cookie}; HttpOnly; SameSite=Strict; Path=/; Max-Age=60")
    }

    private fun approve(exchange: HttpExchange) {
        requireBootstrap(exchange)
        val body = body(exchange)
        requireSchema(body)
        val code = body.optionalString("pairing_code") ?: throw LiveFailure("invalid_request", 400, "pairing_code is required.")
        synchronized(pairLock) {
            purgePairs()
            val pairing = pairs[code] ?: throw LiveFailure("pairing_expired", 404, "Pairing code expired.")
            pairing.approved = true
        }
        json(exchange, 200, state.envelope("ok" to true))
    }

    private fun claim(exchange: HttpExchange) {
        requireBrowserOrigin(exchange)
        body(exchange)
        val cookie = cookie(exchange, "gh_pair") ?: throw LiveFailure("forbidden", 403, "Pairing cookie required.")
        val pairing = synchronized(pairLock) {
            purgePairs()
            pairs.values.firstOrNull { constantTimeEquals(it.cookie, cookie) }
                ?: throw LiveFailure("pairing_expired", 404, "Pairing expired.")
        }
        synchronized(pairLock) {
            val now = System.nanoTime()
            if (pairing.lastClaimNanos != Long.MIN_VALUE && now - pairing.lastClaimNanos < 1_000_000_000L) {
                throw LiveFailure("claim_rate_limit", 429, "Wait before polling pairing status again.")
            }
            pairing.lastClaimNanos = now
        }
        if (!pairing.approved) {
            json(exchange, 202, state.envelope("status" to "pending"))
            return
        }
        val observer = synchronized(pairLock) {
            if (!pairing.approved || pairs.remove(pairing.code) == null) throw LiveFailure("pairing_expired", 404, "Pairing expired.")
            state.createSession("Browser observer", "observer")
        }
        json(exchange, 200, state.envelope("role" to "observer"),
            "Set-Cookie" to "gh_observer=${observer.credential}; HttpOnly; SameSite=Strict; Path=/; Max-Age=1800",
            "Set-Cookie" to "gh_pair=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0")
    }

    private fun events(exchange: HttpExchange) {
        val session = requireSession(exchange, setOf("agent", "observer"))
        val requested = query(exchange.requestURI, "after")?.toLongOrNull() ?: 0L
        val epoch = exchange.requestHeaders.getFirst("X-GraphHarness-Epoch")
        if (epoch != runtime.epoch) throw LiveFailure("daemon_restarted", 409, "Reconnect to the current daemon.")
        exchange.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
        if (streamCount.incrementAndGet() > 8) {
            streamCount.decrementAndGet()
            throw LiveFailure("stream_limit", 429, "Too many event streams.")
        }
        try {
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { output ->
                var cursor = requested
                var heartbeatAt = System.nanoTime()
                while (!Thread.currentThread().isInterrupted) {
                    state.authenticate(session.credential)
                    val (reset, events) = state.eventsAfter(cursor)
                    if (reset) {
                        sse(output, "reset", state.envelope("reason" to "cursor_expired", "cursor" to state.cursor().toString()).stringify())
                        return
                    }
                    if (events.size > 128 || events.sumOf { it.second.toByteArray(StandardCharsets.UTF_8).size } > 256 * 1024) {
                        sse(output, "reset", state.envelope("reason" to "slow_consumer", "cursor" to state.cursor().toString()).stringify())
                        return
                    }
                    events.forEach { (id, event) ->
                        sse(output, "activity", event, "${runtime.epoch}:$id")
                        cursor = id
                    }
                    if (System.nanoTime() - heartbeatAt >= 10_000_000_000L) {
                        output.write(": keepalive\n\n".toByteArray(StandardCharsets.UTF_8))
                        output.flush()
                        heartbeatAt = System.nanoTime()
                    }
                    Thread.sleep(100)
                }
            }
        } finally {
            streamCount.decrementAndGet()
        }
    }

    private fun static(exchange: HttpExchange) {
        if (exchange.requestMethod != "GET") throw LiveFailure("not_found", 404, "Route not found.")
        val root = try { uiDirectory.toRealPath() } catch (_: Exception) { throw LiveFailure("not_found", 404, "UI assets are unavailable.") }
        val requested = if (exchange.requestURI.path == "/") root.resolve("index.html") else root.resolve(exchange.requestURI.path.removePrefix("/"))
        val target = try { requested.normalize().toRealPath() } catch (_: Exception) { throw LiveFailure("not_found", 404, "Asset not found.") }
        if (!target.startsWith(root) || !Files.isRegularFile(target, NOFOLLOW_LINKS)) throw LiveFailure("not_found", 404, "Asset not found.")
        if (Files.size(target) > MAX_STATIC_BYTES) throw LiveFailure("asset_limit", 413, "Asset exceeds the configured limit.")
        val bytes = Files.readAllBytes(target)
        exchange.responseHeaders.add("Content-Type", contentType(target.fileName.toString()))
        exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun requireSession(exchange: HttpExchange, roles: Set<String>, allowCookieWithoutEpoch: Boolean = false): LiveSession {
        rejectForeignOrigin(exchange)
        val bearer = bearer(exchange)
        val cookie = cookie(exchange, "gh_observer")
        val credential = bearer ?: cookie ?: throw LiveFailure("forbidden", 403, "Credentials required.")
        if (bearer == null && cookie != null) requireCookieOrigin(exchange)
        val epoch = exchange.requestHeaders.getFirst("X-GraphHarness-Epoch")
        if (epoch != null && epoch != runtime.epoch) throw LiveFailure("daemon_restarted", 409, "Reconnect to the current daemon.")
        if (epoch == null && !(allowCookieWithoutEpoch && bearer == null && cookie != null)) {
            throw LiveFailure("daemon_restarted", 409, "Reconnect to the current daemon.")
        }
        val session = state.authenticate(credential)
        if (session.role !in roles) throw LiveFailure("forbidden", 403, "Role is not permitted for this route.")
        return session
    }

    private fun requireBootstrap(exchange: HttpExchange) {
        rejectForeignOrigin(exchange)
        val credential = bearer(exchange) ?: throw LiveFailure("forbidden", 403, "Bootstrap credentials required.")
        if (!constantTimeEquals(credential, runtime.bootstrapCredential)) throw LiveFailure("forbidden", 403, "Bootstrap credentials required.")
    }

    private fun requireHost(exchange: HttpExchange) {
        if (exchange.requestHeaders.getFirst("Host") != "127.0.0.1:${server.address.port}") {
            throw LiveFailure("forbidden", 403, "Host is not permitted.")
        }
    }

    private fun requireBrowserOrigin(exchange: HttpExchange) {
        if (exchange.requestHeaders.getFirst("Origin") != origin) throw LiveFailure("forbidden", 403, "Origin is not permitted.")
    }

    private fun rejectForeignOrigin(exchange: HttpExchange) {
        val originHeader = exchange.requestHeaders.getFirst("Origin")
        if (originHeader != null && originHeader != origin) throw LiveFailure("forbidden", 403, "Origin is not permitted.")
    }

    private fun requireCookieOrigin(exchange: HttpExchange) {
        val requestOrigin = exchange.requestHeaders.getFirst("Origin")
        if (exchange.requestMethod !in setOf("GET", "HEAD")) {
            if (requestOrigin != origin) throw LiveFailure("forbidden", 403, "Origin is not permitted.")
        } else if (requestOrigin != origin && exchange.requestHeaders.getFirst("Sec-Fetch-Site") != "same-origin") {
            throw LiveFailure("forbidden", 403, "Origin is not permitted.")
        }
    }

    private fun body(exchange: HttpExchange): JObject {
        val declared = exchange.requestHeaders.getFirst("Content-Length")?.toLongOrNull()
        if (declared != null && (declared < 0 || declared > MAX_BODY_BYTES)) throw LiveFailure("request_limit", 413, "Request exceeds 1 MiB.")
        val bytes = exchange.requestBody.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                if (output.size() + count > MAX_BODY_BYTES) throw LiveFailure("request_limit", 413, "Request exceeds 1 MiB.")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (preparseDepth(bytes) > 16) throw LiveFailure("request_limit", 413, "JSON nesting exceeds the limit.")
        val value = try { MiniJson.parse(decodeUtf8(bytes)) } catch (_: Exception) {
            throw LiveFailure("invalid_request", 400, "Malformed JSON request.")
        }
        if (jsonDepth(value) > 16) throw LiveFailure("request_limit", 413, "JSON nesting exceeds the limit.")
        return value as? JObject ?: throw LiveFailure("invalid_request", 400, "JSON object required.")
    }

    private fun requireSchema(body: JObject) {
        if ((body["schema_version"] as? JNumber)?.raw != "1") throw LiveFailure("invalid_request", 400, "schema_version 1 is required.")
    }

    private fun json(exchange: HttpExchange, status: Int, payload: JsonValue, vararg headers: Pair<String, String>) {
        val bytes = payload.stringify().toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > MAX_RESPONSE_BYTES) {
            json(exchange, 413, state.envelope("error" to jObject("code" to "result_limit", "message" to "Response exceeds the configured limit.")))
            return
        }
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.responseHeaders.add("Cache-Control", "no-store")
        exchange.responseHeaders.add("X-Content-Type-Options", "nosniff")
        headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun failure(exchange: HttpExchange, failure: LiveFailure, operationId: String? = null, requestId: String? = null) {
        if (exchange.responseCode != -1) return
        json(exchange, failure.httpStatus, state.envelope(
            "operation_id" to operationId,
            "request_id" to requestId,
            "error" to jObject("code" to failure.code, "message" to failure.message.orEmpty(), "details" to JObject(LinkedHashMap(failure.details.fields).apply { put("http_status", jValue(failure.httpStatus)) })),
        ))
    }

    private fun sse(output: OutputStream, event: String, data: String, id: String? = null) {
        if (id != null) output.write("id: $id\n".toByteArray(StandardCharsets.UTF_8))
        output.write("event: $event\n".toByteArray(StandardCharsets.UTF_8))
        output.write("data: $data\n\n".toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun pairCode(): String = buildString {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        repeat(10) { append(alphabet[SecureRandom().nextInt(alphabet.length)]) }
    }

    private fun purgePairs() {
        val now = System.nanoTime()
        pairs.entries.removeIf { it.value.expiresNanos <= now }
        while (pairRequests.firstOrNull()?.let { now - it >= 60_000_000_000L } == true) pairRequests.removeFirst()
    }

    private fun bearer(exchange: HttpExchange): String? = exchange.requestHeaders.getFirst("Authorization")
        ?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }

    private fun cookie(exchange: HttpExchange, name: String): String? = exchange.requestHeaders.getFirst("Cookie")
        ?.split(';')?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.removePrefix("$name=")

    private fun query(uri: URI, name: String): String? = uri.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")

    private fun jsonDepth(value: JsonValue): Int = when (value) {
        is JObject -> 1 + (value.fields.values.maxOfOrNull(::jsonDepth) ?: 0)
        is JArray -> 1 + (value.values.maxOfOrNull(::jsonDepth) ?: 0)
        else -> 1
    }

    private fun preparseDepth(bytes: ByteArray): Int {
        var depth = 0
        var maxDepth = 0
        var quoted = false
        var escaped = false
        bytes.forEach { byte ->
            val character = byte.toInt().toChar()
            if (quoted) {
                if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; maxDepth = maxOf(maxDepth, depth) }
                '}', ']' -> depth--
            }
        }
        return maxDepth
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        throw LiveFailure("invalid_request", 400, "Malformed UTF-8 request.")
    }

    private fun contentType(name: String): String = when {
        name.endsWith(".js") -> "text/javascript; charset=utf-8"
        name.endsWith(".css") -> "text/css; charset=utf-8"
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".json") -> "application/json; charset=utf-8"
        else -> "text/html; charset=utf-8"
    }

    private fun constantTimeEquals(left: String, right: String): Boolean = java.security.MessageDigest.isEqual(
        left.toByteArray(StandardCharsets.UTF_8), right.toByteArray(StandardCharsets.UTF_8),
    )

    private data class Pairing(
        val code: String,
        val cookie: String,
        val expiresNanos: Long,
        var approved: Boolean = false,
        var lastClaimNanos: Long = Long.MIN_VALUE,
    )

    private companion object {
        const val MAX_BODY_BYTES = 1_048_576
        const val MAX_RESPONSE_BYTES = 1_048_576
        const val MAX_STATIC_BYTES = 1_048_576
    }
}
