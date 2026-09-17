package graphharness

import java.nio.file.Files
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidationRunnerTest {
    @Test
    fun capturesModifiedUntrackedBuildAndLockInputsButExcludesSecrets() {
        val root = createTempDirectory("graphharness-validation-inputs")
        root.resolve("build.gradle.kts").writeText("plugins {}\n")
        root.resolve("gradle.lockfile").writeText("lock\n")
        root.resolve("src").createDirectories()
        root.resolve("src/Main.java").writeText("class Main {}\n")
        root.resolve("untracked.txt").writeText("working tree\n")
        root.resolve(".env").writeText("TOP_SECRET=value\n")
        root.resolve(".npmrc").writeText("//registry.example/:_authToken=secret\n")
        git(root, "init")
        git(root, "add", "build.gradle.kts", "gradle.lockfile", "src/Main.java")
        root.resolve("src/Main.java").writeText("class Main { int value = 1; }\n")

        val evidence = ValidationRunner(root).run(listOf("sh", "-c", "test -f build.gradle.kts && test -f gradle.lockfile && test -f untracked.txt && test ! -e .env && test ! -e .npmrc && printf complete"))

        assertEquals(0, evidence.exitCode)
        assertEquals("complete", evidence.outputTail)
        assertTrue(evidence.inputFileHashes.containsKey("build.gradle.kts"))
        assertTrue(evidence.inputFileHashes.containsKey("gradle.lockfile"))
        assertTrue(evidence.inputFileHashes.containsKey("untracked.txt"))
        assertTrue(evidence.excludedInputDiagnostics.any { it.path == ".env" })
        assertTrue(evidence.excludedInputDiagnostics.any { it.path == ".npmrc" })
        assertFalse(evidence.stale)
        assertTrue(evidence.offlineRequested)
        assertEquals("incomplete_captured_working_tree_copy", evidence.scope)
    }

    @Test
    fun sourceChangeDuringCaptureFailsBeforeCommandRuns() {
        val root = createTempDirectory("graphharness-validation-drift")
        val source = root.resolve("Source.java")
        source.writeText("class Source {}\n")
        val runner = ValidationRunner(root, object : ValidationCaptureHook {
            override fun afterCapture(manifestId: String) {
                source.writeText("class Source { int changed; }\n")
            }
        })

        val failure = assertFailsWith<ValidationFailure> {
            runner.run(listOf("sh", "-c", "touch should-not-run"))
        }

        assertEquals("validation_inputs_changed", failure.code)
    }

    @Test
    fun inputChangedAfterVerifiedCaptureMarksExecutionEvidenceStale() {
        val root = createTempDirectory("graphharness-validation-stale")
        val source = root.resolve("Source.java")
        source.writeText("class Source {}\n")
        val captured = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runner = ValidationRunner(root, object : ValidationCaptureHook {
            override fun afterVerifiedCapture(manifestId: String) {
                captured.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
            }
        })
        var evidence: ImmutableValidationEvidence? = null
        val run = thread { evidence = runner.run(listOf("sh", "-c", "printf ran")) }
        assertTrue(captured.await(5, TimeUnit.SECONDS))
        source.writeText("class Source { int changed; }\n")
        release.countDown()
        run.join(10_000)

        assertFalse(run.isAlive)
        assertEquals("ran", evidence?.outputTail)
        assertTrue(evidence?.stale == true)
    }

    @Test
    fun changedEarlierInputAfterItsCopyIsDetectedByTheFullSourceManifest() {
        val root = createTempDirectory("graphharness-validation-full-manifest")
        val first = root.resolve("A.txt")
        first.writeText("first\n")
        root.resolve("B.txt").writeText("second\n")
        val runner = ValidationRunner(root, object : ValidationCaptureHook {
            override fun afterCopyBeforeSourceManifest(manifestId: String) {
                first.writeText("changed after copy\n")
            }
        })

        val failure = assertFailsWith<ValidationFailure> { runner.run(listOf("sh", "-c", "true")) }

        assertEquals("validation_inputs_changed", failure.code)
    }

    @Test
    fun addedInputAfterCaptureMakesCompletedEvidenceStale() {
        val root = createTempDirectory("graphharness-validation-added-input")
        root.resolve("A.txt").writeText("first\n")
        val runner = ValidationRunner(root, object : ValidationCaptureHook {
            override fun afterVerifiedCapture(manifestId: String) {
                root.resolve("Added.txt").writeText("new\n")
            }
        })

        val evidence = runner.run(listOf("sh", "-c", "printf ran"))

        assertTrue(evidence.stale)
    }

    @Test
    fun capturesActualExitCodeAndBoundsOutputTail() {
        val root = createTempDirectory("graphharness-validation-command")
        root.resolve("Input.txt").writeText("input\n")
        val evidence = ValidationRunner(root).run(listOf("sh", "-c", "head -c 70000 /dev/zero | tr '\\0' x; printf final; exit 7"), "compile")

        assertEquals(7, evidence.exitCode)
        assertFalse(evidence.timedOut)
        assertTrue(evidence.outputTail.endsWith("final"))
        assertTrue(evidence.outputTail.toByteArray().size <= 64 * 1024)
        assertEquals("compile", evidence.mode)
        assertFalse(evidence.inputsChangedDuringCapture)
        assertTrue(evidence.networkIsolated)
    }

    @Test
    fun rejectsSymlinkInputRatherThanFollowingItOutsideRoot() {
        val root = createTempDirectory("graphharness-validation-symlink")
        val outside = createTempDirectory("graphharness-validation-outside").resolve("outside.txt")
        outside.writeText("outside\n")
        Files.createSymbolicLink(root.resolve("escape.txt"), outside)

        val failure = assertFailsWith<ValidationFailure> {
            ValidationRunner(root).run(listOf("sh", "-c", "true"))
        }

        assertEquals("validation_symlink_input", failure.code)
    }

    @Test
    fun validationCannotReachHostLoopbackAndTimeoutIsReported() {
        val root = createTempDirectory("graphharness-validation-network")
        root.resolve("Input.txt").writeText("input\n")
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { listener ->
            val command = listOf("python3", "-c", "import socket\ntry:\n socket.create_connection(('127.0.0.1', ${listener.localPort}), timeout=1)\nexcept OSError:\n print('isolated')\nelse:\n raise SystemExit(7)")
            val evidence = ValidationRunner(root).run(command)
            assertEquals(0, evidence.exitCode)
            assertTrue(evidence.networkIsolated)
            assertEquals("isolated\n", evidence.outputTail)
        }
        val timeout = ValidationRunner(root, timeout = java.time.Duration.ofMillis(100)).run(listOf("sh", "-c", "sleep 10"))
        assertTrue(timeout.timedOut)
        assertEquals(null, timeout.exitCode)
    }

    @Test
    fun validationCannotReadWriteOriginalRootHostFilesOrUnixSockets() {
        val root = createTempDirectory("graphharness-validation-filesystem")
        root.resolve("Input.txt").writeText("input\n")
        val hostDirectory = createTempDirectory("graphharness-validation-host")
        val hostFile = hostDirectory.resolve("private.txt")
        hostFile.writeText("private\n")
        val socket = hostDirectory.resolve("host.sock")
        val command = listOf(
            "sh", "-c",
            "test ! -e '${root}' && test ! -e '${hostFile}' && test ! -S '${socket}' && ! touch '${hostFile}' 2>/dev/null && printf isolated",
        )

        val evidence = ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
            listener.bind(UnixDomainSocketAddress.of(socket))
            ValidationRunner(root).run(command)
        }

        assertEquals(0, evidence.exitCode)
        assertEquals("isolated", evidence.outputTail)
        assertEquals("private\n", Files.readString(hostFile))
    }

    @Test
    fun validationHasAnEnforcedAddressSpaceLimit() {
        val root = createTempDirectory("graphharness-validation-resource")
        root.resolve("Input.txt").writeText("input\n")
        val command = listOf(
            "python3", "-c",
            "import resource\nlimit = resource.getrlimit(resource.RLIMIT_AS)[0]\ntry:\n bytearray(limit + 1)\nexcept MemoryError:\n print('memory_limited')\nelse:\n raise SystemExit(7)",
        )

        val evidence = ValidationRunner(root).run(command)

        assertEquals(0, evidence.exitCode)
        assertEquals("memory_limited\n", evidence.outputTail)
    }

    @Test
    fun validationHasAnEnforcedProcessCountLimit() {
        val root = createTempDirectory("graphharness-validation-processes")
        root.resolve("Input.txt").writeText("input\n")
        val command = listOf(
            "python3", "-c",
            "import subprocess\nchildren = []\ntry:\n for _ in range(96):\n  try:\n   children.append(subprocess.Popen(['/usr/bin/sleep', '10']))\n  except OSError:\n   print('process_limited')\n   break\n else:\n  raise SystemExit(7)\nfinally:\n for child in children:\n  child.terminate()\n for child in children:\n  child.wait()",
        )

        val evidence = ValidationRunner(root).run(command)

        assertEquals(0, evidence.exitCode)
        assertEquals("process_limited\n", evidence.outputTail)
        assertEquals(64L, evidence.resourceLimits["process_count"])
    }

    private fun git(root: java.nio.file.Path, vararg command: String) {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
    }
}
