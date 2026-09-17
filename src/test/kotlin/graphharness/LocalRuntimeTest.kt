package graphharness

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalRuntimeTest {
    @Test
    fun aliasesOfOneRootCannotAcquireIndependentRuntimes() {
        val root = createTempDirectory("graphharness-runtime-root")
        val runtimeDirectory = secureDirectory()
        val alias = root.parent.resolve("${root.fileName}-alias")
        Files.createSymbolicLink(alias, root)

        val runtime = LocalRuntime.acquire(root, runtimeDirectory)
        try {
            assertFailsWith<RuntimeAlreadyActiveException> {
                LocalRuntime.acquire(alias, runtimeDirectory)
            }
        } finally {
            runtime.close()
        }
    }

    @Test
    fun distinctRootsHaveDistinctRuntimes() {
        val runtimeDirectory = secureDirectory()
        val first = LocalRuntime.acquire(createTempDirectory("graphharness-runtime-first"), runtimeDirectory)
        val second = LocalRuntime.acquire(createTempDirectory("graphharness-runtime-second"), runtimeDirectory)
        try {
            assertFalse(first.repositoryId == second.repositoryId)
            assertFalse(first.epoch == second.epoch)
        } finally {
            second.close()
            first.close()
        }
    }

    @Test
    fun closeDoesNotRemoveDescriptorPublishedByAnotherEpoch() {
        val runtimeDirectory = secureDirectory()
        val runtime = LocalRuntime.acquire(createTempDirectory("graphharness-runtime-root"), runtimeDirectory)
        runtime.publish("http://127.0.0.1:4312")
        val descriptorPath = descriptorPath(runtimeDirectory)
        Files.writeString(descriptorPath, Files.readString(descriptorPath).replace(runtime.epoch, "new-epoch"))
        Files.setPosixFilePermissions(descriptorPath, ownerOnlyFilePermissions)

        runtime.close()

        assertTrue(Files.exists(descriptorPath))
        assertEquals("new-epoch", LocalRuntime.discover(runtime.root, runtimeDirectory).daemonEpoch)
    }

    @Test
    fun insecureAndSymlinkedRuntimeDirectoriesAreRejected() {
        val root = createTempDirectory("graphharness-runtime-root")
        val insecure = createTempDirectory("graphharness-runtime-insecure")
        Files.setPosixFilePermissions(insecure, PosixFilePermission.values().toSet())
        assertFailsWith<IllegalArgumentException> { LocalRuntime.acquire(root, insecure) }

        val secure = secureDirectory()
        val symlink = secure.parent.resolve("${secure.fileName}-symlink")
        Files.createSymbolicLink(symlink, secure)
        assertFailsWith<IllegalArgumentException> { LocalRuntime.acquire(root, symlink) }
    }

    @Test
    fun descriptorIsOwnerOnlyAndSymlinkedDescriptorsAreRejected() {
        val runtimeDirectory = secureDirectory()
        val runtime = LocalRuntime.acquire(createTempDirectory("graphharness-runtime-root"), runtimeDirectory)
        runtime.publish("http://127.0.0.1:4312")
        val descriptorPath = descriptorPath(runtimeDirectory)
        try {
            assertEquals(ownerOnlyFilePermissions, Files.getPosixFilePermissions(descriptorPath))
            Files.delete(descriptorPath)
            val target = runtimeDirectory.resolve("descriptor-target")
            Files.writeString(target, "{}")
            Files.setPosixFilePermissions(target, ownerOnlyFilePermissions)
            Files.createSymbolicLink(descriptorPath, target)
            assertFailsWith<IllegalArgumentException> { LocalRuntime.discover(runtime.root, runtimeDirectory) }
        } finally {
            runtime.close()
        }
    }

    @Test
    fun discoveryRejectsDescriptorEndpointsOutsideLoopbackHttp() {
        val runtimeDirectory = secureDirectory()
        val runtime = LocalRuntime.acquire(createTempDirectory("graphharness-runtime-root"), runtimeDirectory)
        runtime.publish("http://127.0.0.1:4312")
        val descriptorPath = descriptorPath(runtimeDirectory)
        try {
            Files.writeString(descriptorPath, Files.readString(descriptorPath).replace("http://127.0.0.1:4312", "http://example.com:4312"))
            Files.setPosixFilePermissions(descriptorPath, ownerOnlyFilePermissions)
            assertFailsWith<IllegalStateException> { LocalRuntime.discover(runtime.root, runtimeDirectory) }
        } finally {
            runtime.close()
        }
    }

    private fun secureDirectory(): Path = createTempDirectory("graphharness-runtime-state").also {
        Files.setPosixFilePermissions(it, ownerOnlyDirectoryPermissions)
    }

    private fun descriptorPath(runtimeDirectory: Path): Path = Files.list(runtimeDirectory).use { paths ->
        paths.filter { it.fileName.toString().endsWith(".json") }.findFirst().orElseThrow()
    }

    private companion object {
        val ownerOnlyDirectoryPermissions = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        val ownerOnlyFilePermissions = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }
}
