package graphharness

import kotlin.io.path.Path
import kotlin.io.path.exists

fun main(args: Array<String>) {
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
