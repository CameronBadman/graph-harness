package graphharness

import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

class RuntimeAlreadyActiveException : IllegalStateException("A local runtime is already active for this checkout")

class RuntimeDescriptor internal constructor(
    val schemaVersion: Int,
    val repositoryId: String,
    val root: Path,
    val endpoint: String,
    val daemonEpoch: String,
    val bootstrapCredential: String,
) {
    override fun toString(): String =
        "RuntimeDescriptor(schemaVersion=$schemaVersion, repositoryId=$repositoryId, root=$root, endpoint=$endpoint, daemonEpoch=$daemonEpoch, bootstrapCredential=<redacted>)"
}

class LocalRuntime private constructor(
    val repositoryId: String,
    val root: Path,
    val epoch: String,
    val bootstrapCredential: String,
    private val runtimeDirectory: Path,
    private val lockPath: Path,
    private val descriptorPath: Path,
    private val lockChannel: FileChannel,
    private val lock: FileLock,
) : AutoCloseable {
    fun publish(endpoint: String) {
        require(endpoint.isNotBlank() && endpoint.none { it == '\r' || it == '\n' }) { "Invalid local endpoint" }
        val descriptor = jObject(
            "schema_version" to SCHEMA_VERSION,
            "repository_id" to repositoryId,
            "root" to root.toString(),
            "endpoint" to endpoint,
            "daemon_epoch" to epoch,
            "bootstrap_credential" to bootstrapCredential,
        ).stringify().toByteArray(StandardCharsets.UTF_8)
        val temporary = Files.createTempFile(runtimeDirectory, ".${repositoryId}-", ".tmp")
        try {
            setOwnerOnlyFilePermissions(temporary)
            Files.write(temporary, descriptor, WRITE)
            try {
                Files.move(temporary, descriptorPath, ATOMIC_MOVE, REPLACE_EXISTING)
            } catch (err: AtomicMoveNotSupportedException) {
                throw IllegalStateException("Atomic descriptor publication is unavailable", err)
            }
            setOwnerOnlyFilePermissions(descriptorPath)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun close() {
        try {
            if (Files.exists(descriptorPath, NOFOLLOW_LINKS) && !Files.isSymbolicLink(descriptorPath)) {
                val descriptor = runCatching {
                    requireOwnerOnlyFile(descriptorPath)
                    readDescriptor(descriptorPath)
                }.getOrNull()
                if (descriptor?.daemonEpoch == epoch) Files.deleteIfExists(descriptorPath)
            }
        } finally {
            runCatching { lock.release() }
            runCatching { lockChannel.close() }
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private val ownerOnlyDirectoryPermissions = PosixFilePermissions.fromString("rwx------")
        private val ownerOnlyFilePermissions = PosixFilePermissions.fromString("rw-------")

        fun acquire(root: Path, runtimeDirectory: Path = defaultDirectory()): LocalRuntime {
            val canonicalRoot = try {
                root.toRealPath()
            } catch (err: Exception) {
                throw IllegalArgumentException("Checkout root must exist and be accessible", err)
            }
            if (!Files.isDirectory(canonicalRoot)) throw IllegalArgumentException("Checkout root must be a directory")
            val secureRuntimeDirectory = prepareRuntimeDirectory(runtimeDirectory)
            val repositoryId = repositoryId(canonicalRoot)
            val lockPath = secureRuntimeDirectory.resolve("$repositoryId.lock")
            val descriptorPath = secureRuntimeDirectory.resolve("$repositoryId.json")
            rejectSymlink(lockPath)
            val existingLock = Files.exists(lockPath, NOFOLLOW_LINKS)
            if (existingLock) requireOwnerOnlyFile(lockPath)
            val channel = try {
                FileChannel.open(lockPath, setOf(CREATE, WRITE, NOFOLLOW_LINKS), PosixFilePermissions.asFileAttribute(ownerOnlyFilePermissions))
            } catch (err: Exception) {
                throw IllegalStateException("Unable to open local runtime lock", err)
            }
            try {
                rejectSymlink(lockPath)
                if (!existingLock) requireOwnerOnlyFile(lockPath)
                val lock = try {
                    channel.tryLock()
                } catch (err: OverlappingFileLockException) {
                    null
                }
                if (lock == null) throw RuntimeAlreadyActiveException()
                return LocalRuntime(
                    repositoryId = repositoryId,
                    root = canonicalRoot,
                    epoch = randomToken(16),
                    bootstrapCredential = randomToken(32),
                    runtimeDirectory = secureRuntimeDirectory,
                    lockPath = lockPath,
                    descriptorPath = descriptorPath,
                    lockChannel = channel,
                    lock = lock,
                )
            } catch (err: Exception) {
                runCatching { channel.close() }
                throw err
            }
        }

        fun discover(root: Path, runtimeDirectory: Path = defaultDirectory()): RuntimeDescriptor {
            val canonicalRoot = try {
                root.toRealPath()
            } catch (err: Exception) {
                throw IllegalArgumentException("Checkout root must exist and be accessible", err)
            }
            val secureRuntimeDirectory = prepareRuntimeDirectory(runtimeDirectory)
            val repositoryId = repositoryId(canonicalRoot)
            val descriptorPath = secureRuntimeDirectory.resolve("$repositoryId.json")
            rejectSymlink(descriptorPath)
            if (!Files.isRegularFile(descriptorPath, NOFOLLOW_LINKS)) {
                throw NoSuchElementException("No local runtime descriptor for this checkout")
            }
            requireOwnerOnlyFile(descriptorPath)
            val descriptor = readDescriptor(descriptorPath)
            if (descriptor.schemaVersion != SCHEMA_VERSION || descriptor.repositoryId != repositoryId || descriptor.root != canonicalRoot) {
                throw IllegalStateException("Runtime descriptor does not match this checkout")
            }
            return descriptor
        }

        fun defaultDirectory(): Path {
            val runtimeBase = System.getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
            if (runtimeBase != null && isSecureDirectory(runtimeBase)) return runtimeBase.resolve("graphharness")
            return Paths.get(System.getProperty("user.home"), ".local", "state", "graphharness")
        }

        private fun prepareRuntimeDirectory(directory: Path): Path {
            val absolute = directory.toAbsolutePath().normalize()
            rejectSymlinkComponents(absolute)
            if (Files.exists(absolute, NOFOLLOW_LINKS)) rejectSymlink(absolute)
            if (!Files.exists(absolute, NOFOLLOW_LINKS)) {
                Files.createDirectories(absolute)
                setOwnerOnlyDirectoryPermissions(absolute)
            }
            if (!Files.isDirectory(absolute, NOFOLLOW_LINKS)) throw IllegalArgumentException("Runtime path must be a directory")
            requireOwnerOnlyDirectory(absolute)
            return absolute.toRealPath()
        }

        private fun readDescriptor(path: Path): RuntimeDescriptor {
            val objectValue = try {
                MiniJson.parse(Files.readString(path, StandardCharsets.UTF_8)).asObject()
            } catch (err: Exception) {
                throw IllegalStateException("Invalid runtime descriptor", err)
            }
            try {
                val endpoint = objectValue.requiredString("endpoint")
                validateEndpoint(endpoint)
                return RuntimeDescriptor(
                    schemaVersion = objectValue["schema_version"]?.asInt() ?: error("missing schema_version"),
                    repositoryId = objectValue.requiredString("repository_id"),
                    root = Paths.get(objectValue.requiredString("root")).toRealPath(),
                    endpoint = endpoint,
                    daemonEpoch = objectValue.requiredString("daemon_epoch"),
                    bootstrapCredential = objectValue.requiredString("bootstrap_credential"),
                )
            } catch (err: Exception) {
                throw IllegalStateException("Invalid runtime descriptor", err)
            }
        }

        private fun repositoryId(root: Path): String = MessageDigest.getInstance("SHA-256")
            .digest(root.toString().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        private fun randomToken(bytes: Int): String = ByteArray(bytes).also(SecureRandom()::nextBytes).let {
            Base64.getUrlEncoder().withoutPadding().encodeToString(it)
        }

        private fun validateEndpoint(endpoint: String) {
            val uri = try { URI(endpoint) } catch (_: Exception) { throw IllegalStateException("Invalid runtime descriptor") }
            if (uri.scheme != "http" || uri.host != "127.0.0.1" || uri.port !in 1..65535 || uri.userInfo != null || uri.path !in setOf("", "/") || uri.query != null || uri.fragment != null) {
                throw IllegalStateException("Invalid runtime descriptor")
            }
        }

        private fun rejectSymlink(path: Path) {
            if (Files.isSymbolicLink(path)) throw IllegalArgumentException("Symlinked runtime paths are not supported")
        }

        private fun rejectSymlinkComponents(path: Path) {
            var current = path.root ?: return
            path.iterator().forEach { component ->
                current = current.resolve(component)
                if (Files.exists(current, NOFOLLOW_LINKS)) rejectSymlink(current)
            }
        }

        private fun isSecureDirectory(path: Path): Boolean = runCatching {
            requireOwnerOnlyDirectory(path)
            true
        }.getOrDefault(false)

        private fun requireOwnerOnlyDirectory(path: Path) {
            val attributes = Files.readAttributes(path, java.nio.file.attribute.PosixFileAttributes::class.java, NOFOLLOW_LINKS)
            if (attributes.owner().name != System.getProperty("user.name") || attributes.permissions() != ownerOnlyDirectoryPermissions) {
                throw IllegalArgumentException("Runtime directory must be owner-only")
            }
        }

        private fun requireOwnerOnlyFile(path: Path) {
            val attributes = Files.readAttributes(path, java.nio.file.attribute.PosixFileAttributes::class.java, NOFOLLOW_LINKS)
            if (attributes.owner().name != System.getProperty("user.name") || attributes.permissions() != ownerOnlyFilePermissions) {
                throw IllegalArgumentException("Runtime descriptor must be owner-only")
            }
        }

        private fun setOwnerOnlyDirectoryPermissions(path: Path) {
            Files.setPosixFilePermissions(path, ownerOnlyDirectoryPermissions)
        }

        private fun setOwnerOnlyFilePermissions(path: Path) {
            Files.setPosixFilePermissions(path, ownerOnlyFilePermissions)
        }
    }
}
