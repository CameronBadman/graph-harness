package graphharness

import kotlin.io.path.Path
import kotlin.io.path.exists
import java.nio.file.Files
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.CountDownLatch

fun main(args: Array<String>) {
    val runtimeDirectory = System.getenv("GRAPHHARNESS_RUNTIME_DIR")?.let { Path(it) } ?: LocalRuntime.defaultDirectory()
    when (args.firstOrNull()) {
        "codex-instructions" -> {
            require(args.size == 1) { "Usage: graphharness codex-instructions" }
            println(NavigationProfile.codexInstructions)
            return
        }
        "daemon" -> {
            val arguments = args.drop(1).filter { it != "--allow-edits" }
            val allowEdits = "--allow-edits" in args
            require(arguments.size in 1..2) { "Usage: graphharness daemon ROOT [UI_DIRECTORY] [--allow-edits]" }
            val root = Path(arguments[0]).toRealPath()
            val ui = arguments.getOrNull(1)?.let { Path(it) } ?: defaultUiDirectory()
            require(Files.isRegularFile(ui.resolve("index.html"))) { "Build the UI with npm ci && npm run build in ui/ first." }
            LocalRuntime.acquire(root, runtimeDirectory).use { runtime ->
                SnapshotManager(root).use { manager ->
                    LiveDaemon(manager, runtime, ui, coordinatedWrites = allowEdits).use { daemon ->
                        val stop = CountDownLatch(1)
                        val shutdown = Thread { daemon.close(); manager.close(); stop.countDown() }
                        Runtime.getRuntime().addShutdownHook(shutdown)
                        System.err.println("GraphHarness Live: ${daemon.endpoint} (${if (allowEdits) "coordinated Java body edits" else "read-only"})")
                        try { stop.await() } finally { runCatching { Runtime.getRuntime().removeShutdownHook(shutdown) } }
                    }
                }
            }
            return
        }
        "bridge" -> {
            val options = bridgeOptions(args.drop(1))
            LiveBridge(Path(options.root), runtimeDirectory, options.agentLabel,
                navigationProfile = options.navigation, responseFormat = options.responseFormat,
                nodeEdits = options.nodeEdits).use { it.run(System.`in`, System.out) }
            return
        }
        "authorize-browser" -> {
            require(args.size == 3) { "Usage: graphharness authorize-browser ROOT PAIRING_CODE" }
            val descriptor = LocalRuntime.discover(Path(args[1]), runtimeDirectory)
            val request = HttpRequest.newBuilder(URI.create(descriptor.endpoint + "/observer/approve"))
                .timeout(Duration.ofSeconds(5))
                .header("Authorization", "Bearer ${descriptor.bootstrapCredential}")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jObject("schema_version" to 1, "pairing_code" to args[2]).stringify()))
                .build()
            val response = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
                .send(request, HttpResponse.BodyHandlers.ofString())
            require(response.statusCode() == 200) { "Browser pairing failed; check the code and its expiration." }
            println("Browser approved for ${descriptor.root.fileName} at ${descriptor.endpoint}")
            return
        }
    }
    val mode = args.firstOrNull()?.takeIf { it in setOf("stdio", "legacy-stdio") }
    val rootArgument = if (mode == null) args.firstOrNull() else args.getOrNull(1)
    require(args.size <= if (mode == null) 1 else 2) { "Usage: graphharness [stdio|legacy-stdio] [project-root]" }
    val projectRoot = rootArgument?.let { Path(it) } ?: Path(".")
    require(projectRoot.exists()) { "Project root does not exist: $projectRoot" }

    SnapshotManager(projectRoot.toRealPath()).use { snapshotManager ->
        val transport = if (mode == "legacy-stdio") McpTransport.CONTENT_LENGTH else McpTransport.NEWLINE
        GraphHarnessServer(snapshotManager, transport).run(System.`in`, System.out)
    }
}

internal data class BridgeOptions(val root: String, val agentLabel: String, val navigation: Boolean, val responseFormat: String, val nodeEdits: Boolean)

internal fun bridgeOptions(args: List<String>): BridgeOptions {
    val positional = mutableListOf<String>()
    var navigation = false
    var nodeEdits = false
    var responseFormat = "navigation-v1"
    var index = 0
    while (index < args.size) {
        when (val argument = args[index++]) {
            "--navigation" -> navigation = true
            "--node-edits" -> nodeEdits = true
            "--response-format" -> {
                require(index < args.size) { "--response-format requires navigation-v1 or source-v1" }
                responseFormat = args[index++]
                require(responseFormat in setOf("navigation-v1", "source-v1")) { "Unsupported response format: $responseFormat" }
            }
            else -> {
                require(!argument.startsWith("--")) { "Unknown bridge option: $argument" }
                positional += argument
            }
        }
    }
    require(positional.size in 1..2) { "Usage: graphharness bridge ROOT [AGENT_LABEL] [--navigation] [--response-format source-v1] [--node-edits]" }
    require(navigation || responseFormat == "navigation-v1") { "--response-format source-v1 requires --navigation" }
    require(navigation || !nodeEdits) { "--node-edits requires --navigation" }
    return BridgeOptions(positional[0], positional.getOrNull(1) ?: "Coding agent", navigation, responseFormat, nodeEdits)
}

private fun defaultUiDirectory(): java.nio.file.Path {
    val codeLocation = java.nio.file.Path.of(GraphHarnessServer::class.java.protectionDomain.codeSource.location.toURI())
    if (Files.isRegularFile(codeLocation)) return codeLocation.parent.parent.resolve("ui")
    val development = generateSequence(codeLocation) { it.parent }.take(6)
        .firstOrNull { Files.isDirectory(it.resolve("src/main/kotlin/graphharness")) }
    return (development ?: codeLocation).resolve("ui/dist")
}
