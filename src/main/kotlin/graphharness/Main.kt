package graphharness

import kotlin.io.path.Path
import kotlin.io.path.exists

fun main(args: Array<String>) {
    val projectRoot = args.firstOrNull()?.let { Path(it) } ?: Path(".")
    require(projectRoot.exists()) { "Project root does not exist: $projectRoot" }

    val snapshotManager = SnapshotManager(projectRoot.toAbsolutePath().normalize())
    val server = GraphHarnessServer(snapshotManager)
    server.run(System.`in`, System.out)
}
