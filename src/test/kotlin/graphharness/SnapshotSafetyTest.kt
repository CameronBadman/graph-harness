package graphharness

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SnapshotSafetyTest {
    @Test
    fun sourceIsReadFromTheExactUtf8BytesCapturedByItsSnapshot() {
        val root = createTempDirectory("graphharness-source-bytes")
        val file = root.resolve("Ticket.java")
        val original = "package demo;\r\npublic class Ticket {\r\n  String emoji() {\r\n    return \"😀\";\r\n  }\r\n}\r\n"
        file.writeBytes(original.toByteArray(StandardCharsets.UTF_8))

        SnapshotManager(root).use { manager ->
            val node = manager.search("emoji", "method", null, null).results.single()
            val result = manager.source(node.id, 0)

            assertTrue(result.source.contains("😀"))
            assertTrue(result.source.contains("\r\n"))
            assertEquals(sha256(original.toByteArray(StandardCharsets.UTF_8)), result.file_hash)
            assertEquals(result.file_hash, node.file_hash)
            val span = kotlin.test.assertNotNull(node.byte_span)
            assertEquals(result.source, decodeUtf8(original.toByteArray().copyOfRange(span.start, span.end)))
        }
    }

    @Test
    fun sourceRejectsAChangedFileInsteadOfApplyingOldLinesToIt() {
        val root = createTempDirectory("graphharness-stale-source")
        val file = root.resolve("Ticket.java")
        file.writeBytes(
            """
            package demo;
            public class Ticket {
                int count() {
                    return 1;
                }
            }
            """.trimIndent().plus("\n").toByteArray(),
        )

        SnapshotManager(root).use { manager ->
            val node = manager.search("count", "method", null, null).results.single()
            file.writeBytes("package demo;\npublic class Ticket { int added() { return 2; } }\n".toByteArray())

            val error = assertFailsWith<IllegalStateException> { manager.source(node.id, 0) }
            assertTrue(error.message.orEmpty().startsWith("stale_source:"))
        }
    }

    @Test
    fun aDirtyGenerationArrivingAfterCapturePublishesWithoutManualRefresh() {
        val root = createTempDirectory("graphharness-generation-race")
        val file = root.resolve("Ticket.java")
        file.writeBytes(
            """
            package demo;
            public class Ticket {
                int count() {
                    return 1;
                }
            }
            """.trimIndent().plus("\n").toByteArray(),
        )
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        val followUpInstalled = CountDownLatch(1)
        var installs = 0
        val hook = object : SnapshotBuildHook {
            override fun afterImmutableCapture(generation: Long) {
                captured.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }

            override fun afterInstall(snapshot: Snapshot) {
                installs++
                if (installs == 2) followUpInstalled.countDown()
            }
        }

        SnapshotManager(root, hook, useJoern = false).use { manager ->
            val first = manager.current().id
            val originalNode = manager.search("count", "method", null, null).results.single()
            val failure = AtomicReference<Throwable?>(null)
            val rebuild = thread(start = true) {
                runCatching { manager.refresh() }.onFailure(failure::set)
            }
            assertTrue(captured.await(5, TimeUnit.SECONDS))
            file.writeBytes(
                """
                package demo;
                public class Ticket {
                    int changed() {
                        return 2;
                    }
                }
                """.trimIndent().plus("\n").toByteArray(),
            )
            val stale = assertFailsWith<IllegalStateException> {
                manager.source(originalNode.id, 0)
            }
            assertTrue(stale.message.orEmpty().startsWith("stale_source:"))
            release.countDown()
            rebuild.join(30_000)
            assertTrue(!rebuild.isAlive)
            assertEquals(null, failure.get())
            assertTrue(followUpInstalled.await(5, TimeUnit.SECONDS))
            assertTrue(manager.current().id != first)
            assertTrue(manager.search("changed", "method", null, null).results.isNotEmpty())
        }
    }

    @Test
    fun captureRespectsGitIgnoreKeepsTrackedSourceAndReportsBoundedOmissions() {
        val root = createTempDirectory("graphharness-source-admission")
        root.resolve(".gitignore").writeBytes("Visible.java\nHidden.java\nignored/\n".toByteArray())
        val visible = root.resolve("Visible.java")
        val hidden = root.resolve("Hidden.java")
        val oversize = root.resolve("Oversize.java")
        val trackedNested = root.resolve("ignored").resolve("Tracked.java")
        val ignoredNested = root.resolve("ignored").resolve("Ignored.java")
        visible.writeBytes("package demo;\npublic class Visible {}\n".toByteArray())
        hidden.writeBytes("package demo;\npublic class Hidden {}\n".toByteArray())
        oversize.writeBytes("package demo;\npublic class Oversize { String value = \"long value\"; }\n".toByteArray())
        trackedNested.parent.createDirectories()
        trackedNested.writeBytes("package demo;\npublic class Tracked {}\n".toByteArray())
        ignoredNested.writeBytes("package demo;\npublic class Ignored {}\n".toByteArray())
        runCommand(root, "git", "init")
        runCommand(root, "git", "add", "-f", "Visible.java")
        runCommand(root, "git", "add", "-f", "ignored/Tracked.java")

        SnapshotManager(
            root,
            useJoern = false,
            sourceAdmissionLimits = SourceAdmissionLimits(maxFileBytes = 48),
        ).use { manager ->
            val snapshot = manager.current()
            assertTrue(snapshot.sourceIndex.containsKey("Visible.java"))
            assertTrue(snapshot.sourceIndex.containsKey("ignored/Tracked.java"))
            assertTrue(!snapshot.sourceIndex.containsKey("Hidden.java"))
            assertTrue(!snapshot.sourceIndex.containsKey("ignored/Ignored.java"))
            assertTrue(!snapshot.sourceIndex.containsKey("Oversize.java"))
            assertTrue(snapshot.sourceDiagnostics.any { it.file == "Hidden.java" && it.reason == "ignored" })
            assertTrue(snapshot.sourceDiagnostics.any { it.file == "Oversize.java" && it.reason == "oversize" })
        }
    }

    @Test
    fun sourceReadRejectsAFileReplacedBySymlinkOutsideTheRoot() {
        val root = createTempDirectory("graphharness-source-symlink")
        val file = root.resolve("Ticket.java")
        file.writeBytes(
            """
            package demo;
            public class Ticket {
                int count() {
                    return 1;
                }
            }
            """.trimIndent().plus("\n").toByteArray(),
        )
        val outside = createTempDirectory("graphharness-outside").resolve("Outside.java")
        outside.writeBytes("package outside;\npublic class Outside {}\n".toByteArray())

        SnapshotManager(root, useJoern = false).use { manager ->
            val node = manager.search("count", "method", null, null).results.single()
            Files.delete(file)
            Files.createSymbolicLink(file, outside)

            val error = assertFailsWith<IllegalStateException> { manager.source(node.id, 0) }
            assertTrue(error.message.orEmpty().startsWith("stale_source:"))
        }
    }

    private fun runCommand(root: java.nio.file.Path, vararg command: String) {
        val process = ProcessBuilder(command.toList())
            .directory(root.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }
}
