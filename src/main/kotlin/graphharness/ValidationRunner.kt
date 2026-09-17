package graphharness

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.time.Duration
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

data class ValidationInputSummary(
    val fileCount: Int,
    val totalBytes: Long,
    val maxFileBytes: Long,
)

data class ValidationInputDiagnostic(
    val path: String,
    val reason: String,
)

data class ImmutableValidationEvidence(
    val manifestId: String,
    val inputFileHashes: Map<String, String>,
    val inputSummary: ValidationInputSummary,
    val actualCommand: List<String>,
    val exitCode: Int?,
    val outputTail: String,
    val timedOut: Boolean,
    val stale: Boolean,
    val inputsChangedDuringCapture: Boolean,
    val scope: String,
    val mode: String,
    val offlineRequested: Boolean,
    val networkIsolated: Boolean,
    val filesystemIsolated: Boolean,
    val resourceLimits: Map<String, Long>,
    val isolationLimitations: List<String>,
    val toolchainMountScope: List<String>,
    val omittedInputDiagnostics: List<ValidationInputDiagnostic>,
    val excludedInputDiagnostics: List<ValidationInputDiagnostic>,
)

class ValidationFailure(val code: String, message: String) : IllegalStateException("$code: $message")

interface ValidationCaptureHook {
    fun afterCapture(manifestId: String) {}
    fun afterCopyBeforeSourceManifest(manifestId: String) {}
    fun afterVerifiedCapture(manifestId: String) {}
}

class ValidationRunner(
    root: Path,
    private val captureHook: ValidationCaptureHook? = null,
    private val timeout: Duration = Duration.ofSeconds(120),
) {
    private val root = try {
        root.toRealPath()
    } catch (error: Exception) {
        throw ValidationFailure("invalid_validation_root", "root is inaccessible: ${error.message}")
    }

    fun run(command: List<String>, mode: String = "test"): ImmutableValidationEvidence {
        if (command.isEmpty() || command.any { it.isEmpty() }) {
            throw ValidationFailure("invalid_validation_command", "command must contain non-empty arguments")
        }
        val capture = captureInputs()
        val scratch = createTempDirectory("graphharness-validation-")
        try {
            captureHook?.afterCapture(capture.manifestId)
            copyAndVerify(capture, scratch)
            captureHook?.afterCopyBeforeSourceManifest(capture.manifestId)
            if (captureInputs().manifestId != capture.manifestId) {
                throw ValidationFailure("validation_inputs_changed", "source manifest changed during capture")
            }
            captureHook?.afterVerifiedCapture(capture.manifestId)
            val result = execute(command, scratch)
            val stale = runCatching { captureInputs().manifestId != capture.manifestId }.getOrDefault(true)
            return ImmutableValidationEvidence(
                manifestId = capture.manifestId,
                inputFileHashes = Collections.unmodifiableMap(LinkedHashMap(capture.inputs.associate { it.relative to it.hash })),
                inputSummary = ValidationInputSummary(capture.inputs.size, capture.inputs.sumOf { it.bytes.size.toLong() }, capture.inputs.maxOfOrNull { it.bytes.size.toLong() } ?: 0),
                actualCommand = Collections.unmodifiableList(command.toList()),
                exitCode = result.exitCode,
                outputTail = result.outputTail,
                timedOut = result.timedOut,
                stale = stale,
                inputsChangedDuringCapture = false,
                scope = if (capture.excluded.isEmpty()) "captured_working_tree_copy" else "incomplete_captured_working_tree_copy",
                mode = mode,
                offlineRequested = true,
                networkIsolated = true,
                filesystemIsolated = true,
                resourceLimits = Collections.unmodifiableMap(linkedMapOf(
                    "address_space_bytes" to MAX_ADDRESS_SPACE,
                    "cpu_seconds" to MAX_CPU_SECONDS.toLong(),
                    "process_count" to MAX_PROCESSES.toLong(),
                    "open_files" to MAX_OPEN_FILES.toLong(),
                    "output_file_bytes" to MAX_OUTPUT_FILE_BYTES,
                )),
                isolationLimitations = Collections.unmodifiableList(listOf(
                    "Resource limits are inherited per process; no delegated writable cgroup is available for aggregate tree memory or CPU control.",
                    "The source manifest does not pin read-only toolchain binaries or external dependency caches.",
                )),
                toolchainMountScope = Collections.unmodifiableList(result.toolchainMountScope),
                omittedInputDiagnostics = Collections.unmodifiableList(capture.omitted.toList()),
                excludedInputDiagnostics = Collections.unmodifiableList(capture.excluded.toList()),
            )
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    private fun captureInputs(): Capture {
        val omitted = mutableListOf<ValidationInputDiagnostic>()
        val excluded = mutableListOf<ValidationInputDiagnostic>()
        val candidates = gitCandidates() ?: walkCandidates(omitted)
        val inputs = mutableListOf<Input>()
        var total = 0L
        for (relative in candidates.sorted()) {
            val resolved = confinedPath(relative)
            if (isSecret(relative)) {
                excluded += ValidationInputDiagnostic(relative, "secret_environment_input")
                continue
            }
            if (Files.isSymbolicLink(resolved)) throw ValidationFailure("validation_symlink_input", relative)
            if (!Files.isRegularFile(resolved, NOFOLLOW_LINKS)) {
                omitted += ValidationInputDiagnostic(relative, "not_regular_file")
                continue
            }
            val size = Files.size(resolved)
            if (size > MAX_FILE_BYTES) throw ValidationFailure("validation_file_too_large", relative)
            if (inputs.size >= MAX_INPUT_COUNT) throw ValidationFailure("validation_input_count_exceeded", MAX_INPUT_COUNT.toString())
            val bytes = readBounded(resolved, MAX_FILE_BYTES)
            total += bytes.size
            if (total > MAX_TOTAL_BYTES) throw ValidationFailure("validation_input_total_exceeded", MAX_TOTAL_BYTES.toString())
            val mode = fileMode(resolved)
            inputs += Input(relative, bytes, sha256(bytes), mode)
        }
        val manifest = inputs.sortedBy { it.relative }.joinToString("\n") { "${it.relative}\u0000${it.hash}\u0000${it.bytes.size}\u0000${modeText(it.mode)}" }
        return Capture(inputs, sha256(manifest), omitted.sortedBy { it.path }, excluded.sortedBy { it.path })
    }

    private fun copyAndVerify(capture: Capture, scratch: Path) {
        for (input in capture.inputs) {
            if (currentHash(input.relative) != input.hash) throw ValidationFailure("validation_inputs_changed", input.relative)
            val target = scratch.resolve(input.relative).normalize()
            if (!target.startsWith(scratch)) throw ValidationFailure("validation_input_outside_root", input.relative)
            Files.createDirectories(target.parent)
            Files.write(target, input.bytes)
            setMode(target, input.mode)
            if (sha256(readBounded(target, MAX_FILE_BYTES)) != input.hash || modeText(fileMode(target)) != modeText(input.mode)) {
                throw ValidationFailure("validation_inputs_changed", input.relative)
            }
        }
        if (copyManifest(capture.inputs, scratch) != capture.manifestId) {
            throw ValidationFailure("validation_inputs_changed", "copy manifest differs from source")
        }
    }

    private fun currentHash(relative: String): String? {
        val candidate = runCatching { confinedPath(relative) }.getOrNull() ?: return null
        if (!Files.isRegularFile(candidate, NOFOLLOW_LINKS)) return null
        return runCatching { sha256(readBounded(candidate, MAX_FILE_BYTES)) }.getOrNull()
    }

    private fun gitCandidates(): Set<String>? {
        val process = runCatching {
            ProcessBuilder("git", "-C", root.toString(), "ls-files", "--cached", "--others", "--exclude-standard", "-z")
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return null
        val output = AtomicReference<ByteArray?>()
        val failure = AtomicReference<Throwable?>()
        val reader = Thread {
            runCatching { readBounded(process.inputStream, MAX_GIT_OUTPUT_BYTES) }
                .onSuccess(output::set)
                .onFailure(failure::set)
        }.also { it.start() }
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            terminateTree(process.toHandle())
            process.inputStream.close()
            reader.join(5_000)
            throw ValidationFailure("validation_input_enumeration_timed_out", "git ls-files")
        }
        reader.join(5_000)
        if (reader.isAlive) {
            process.inputStream.close()
            reader.join(1_000)
            throw ValidationFailure("validation_input_enumeration_timed_out", "git ls-files output")
        }
        val readerFailure = failure.get()
        if (readerFailure is ValidationFailure) throw readerFailure
        if (readerFailure != null || process.exitValue() != 0) return null
        val bytes = output.get() ?: return null
        return bytes.toString(Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }.toSet()
    }

    private fun walkCandidates(omitted: MutableList<ValidationInputDiagnostic>): Set<String> {
        val candidates = linkedSetOf<String>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (dir != root && Files.isSymbolicLink(dir)) {
                    throw ValidationFailure("validation_symlink_input", root.relativize(dir).toString())
                }
                if (dir != root && ignoredDirectory(root.relativize(dir).toString())) return FileVisitResult.SKIP_SUBTREE
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val relative = root.relativize(file).toString().replace(file.fileSystem.separator, "/")
                if (Files.isSymbolicLink(file)) throw ValidationFailure("validation_symlink_input", relative)
                if (attrs.isRegularFile) candidates += relative else omitted += ValidationInputDiagnostic(relative, "not_regular_file")
                return FileVisitResult.CONTINUE
            }
        })
        return candidates
    }

    private fun execute(command: List<String>, scratch: Path): ProcessResult {
        val home = scratch.resolve(".home")
        val temp = scratch.resolve(".tmp")
        Files.createDirectories(home)
        Files.createDirectories(temp)
        val sandbox = sandboxCommand(command, scratch)
        verifySandbox(sandbox.probeCommand)
        val builder = ProcessBuilder(sandbox.command).directory(scratch.toFile()).redirectErrorStream(true)
        val process = try {
            builder.start()
        } catch (error: Exception) {
            throw ValidationFailure("validation_blocked", "command could not start: ${error.javaClass.simpleName}")
        }
        val tail = OutputTail(MAX_OUTPUT_BYTES)
        val input = process.inputStream
        val reader = Thread { runCatching { input.use(tail::drain) } }.also { it.isDaemon = true; it.start() }
        try {
            val completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)
            if (!completed) terminateTree(process.toHandle())
            reader.join(5_000)
            if (reader.isAlive) {
                input.close()
                reader.join(1_000)
            }
            return ProcessResult(if (completed) process.exitValue() else null, tail.text(), !completed, sandbox.toolchainMountScope)
        } catch (interrupted: InterruptedException) {
            terminateTree(process.toHandle())
            Thread.currentThread().interrupt()
            throw ValidationFailure("validation_cancelled", "Validation was interrupted.")
        } finally {
            if (process.isAlive) terminateTree(process.toHandle())
            runCatching { input.close() }
        }
    }

    private fun sandboxCommand(command: List<String>, scratch: Path): SandboxCommand {
        if (!Files.isExecutable(BWRAP) || !Files.isExecutable(PRLIMIT)) {
            throw ValidationFailure("validation_blocked", "bubblewrap and prlimit are required for validation isolation")
        }
        val javaHome = System.getenv("JAVA_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it).toRealPath() }
        val kotlinHome = System.getenv("KOTLIN_HOME")?.takeIf { it.isNotBlank() }?.let { Path.of(it).toRealPath() }
        val binds = mutableListOf<String>()
        val mountScope = mutableListOf("/usr:read-only")
        binds += listOf("--ro-bind", "/usr", "/usr", "--symlink", "usr/bin", "/bin", "--symlink", "usr/lib", "/lib")
        if (Files.exists(Path.of("/usr/lib64"))) binds += listOf("--symlink", "usr/lib64", "/lib64")
        listOfNotNull(javaHome, kotlinHome).distinct().forEach { home ->
            if (!home.isAbsolute || home.startsWith(root) || home.startsWith(Path.of(System.getProperty("user.home")))) {
                throw ValidationFailure("validation_blocked", "toolchain location cannot be safely mounted")
            }
            val parent = home.parent ?: throw ValidationFailure("validation_blocked", "toolchain location has no parent")
            binds += listOf("--dir", parent.toString(), "--ro-bind", home.toString(), home.toString())
            mountScope += "${home}:read-only"
        }
        val path = buildList {
            javaHome?.resolve("bin")?.takeIf { Files.isDirectory(it) }?.let { add(it.toString()) }
            kotlinHome?.resolve("bin")?.takeIf { Files.isDirectory(it) }?.let { add(it.toString()) }
            add("/usr/bin")
            add("/bin")
        }.joinToString(":")
        val base = mutableListOf<String>()
        base += listOf(
            BWRAP.toString(), "--unshare-all", "--uid", SANDBOX_UID.toString(), "--gid", SANDBOX_GID.toString(), "--new-session", "--die-with-parent", "--clearenv",
            "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp", "--tmpfs", "/home",
            "--bind", scratch.toString(), "/workspace", "--chdir", "/workspace",
            "--setenv", "PATH", path, "--setenv", "HOME", "/home/validation",
            "--setenv", "TMPDIR", "/tmp", "--setenv", "TMP", "/tmp", "--setenv", "TEMP", "/tmp",
        )
        javaHome?.let {
            base += listOf("--setenv", "JAVA_HOME", it.toString())
            base += listOf("--setenv", "JAVA_TOOL_OPTIONS", JAVA_TOOL_OPTIONS)
        }
        kotlinHome?.let { base += listOf("--setenv", "KOTLIN_HOME", it.toString()) }
        base += binds
        val limits = listOf(
            PRLIMIT.toString(), "--as=$MAX_ADDRESS_SPACE:$MAX_ADDRESS_SPACE", "--cpu=$MAX_CPU_SECONDS:$MAX_CPU_SECONDS",
            "--nproc=$MAX_PROCESSES:$MAX_PROCESSES", "--nofile=$MAX_OPEN_FILES:$MAX_OPEN_FILES",
            "--fsize=$MAX_OUTPUT_FILE_BYTES:$MAX_OUTPUT_FILE_BYTES", "--core=0:0", "--",
        )
        return SandboxCommand(base + listOf("--") + limits + command, base + listOf("--") + limits + listOf("/usr/bin/sh", "-c", "test -d /workspace && test ! -e /run"), mountScope)
    }

    private fun verifySandbox(command: List<String>) {
        val process = runCatching { ProcessBuilder(command).redirectErrorStream(true).start() }.getOrElse {
            throw ValidationFailure("validation_blocked", "isolation wrapper could not start")
        }
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            terminateTree(process.toHandle())
            throw ValidationFailure("validation_blocked", "isolation wrapper timed out")
        }
        if (process.exitValue() != 0) throw ValidationFailure("validation_blocked", "isolation wrapper is unavailable")
    }

    private fun confinedPath(relative: String): Path {
        val relativePath = Path.of(relative)
        if (relativePath.isAbsolute()) throw ValidationFailure("validation_input_outside_root", relative)
        var current = root
        for (part in relativePath) {
            current = current.resolve(part.toString()).normalize()
            if (!current.startsWith(root)) throw ValidationFailure("validation_input_outside_root", relative)
            if (Files.isSymbolicLink(current)) throw ValidationFailure("validation_symlink_input", relative)
        }
        return current
    }

    private fun readBounded(path: Path, limit: Long): ByteArray = Files.newInputStream(path, NOFOLLOW_LINKS).use { readBounded(it, limit) }

    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw ValidationFailure("validation_file_too_large", limit.toString())
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun copyManifest(inputs: List<Input>, scratch: Path): String {
        val manifest = inputs.sortedBy { it.relative }.joinToString("\n") { input ->
            val target = scratch.resolve(input.relative).normalize()
            "${input.relative}\u0000${sha256(readBounded(target, MAX_FILE_BYTES))}\u0000${Files.size(target)}\u0000${modeText(fileMode(target))}"
        }
        return sha256(manifest)
    }

    private fun fileMode(path: Path): Set<PosixFilePermission>? = runCatching { Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) }.getOrNull()

    private fun setMode(path: Path, mode: Set<PosixFilePermission>?) {
        if (mode != null) runCatching { Files.setPosixFilePermissions(path, mode) }
    }

    private fun modeText(mode: Set<PosixFilePermission>?): String = mode.orEmpty().sortedBy { it.name }.joinToString(",") { it.name }

    private fun ignoredDirectory(relative: String): Boolean = relative.split('/', '\\').any { it in IGNORED_DIRECTORIES }

    private fun isSecret(relative: String): Boolean {
        val path = relative.lowercase()
        val name = path.substringAfterLast('/')
        return name == ".env" || name.startsWith(".env.") || name in SECRET_FILE_NAMES ||
            name.endsWith(".pem") || name.endsWith(".key") || name.endsWith(".p12") || name.endsWith(".pfx") ||
            name.contains("credential") || name.contains("secret") || name.contains("token") || name.contains("password") ||
            path.startsWith(".ssh/") || path.startsWith(".aws/") || path.startsWith(".gnupg/") ||
            path.startsWith(".config/gcloud/") || path.startsWith(".config/gh/")
    }

    private fun terminateTree(handle: ProcessHandle) {
        handle.descendants().forEach { it.destroyForcibly() }
        handle.destroyForcibly()
        runCatching { handle.onExit().get(5, TimeUnit.SECONDS) }
    }

    private data class Input(val relative: String, val bytes: ByteArray, val hash: String, val mode: Set<PosixFilePermission>?)
    private data class Capture(val inputs: List<Input>, val manifestId: String, val omitted: List<ValidationInputDiagnostic>, val excluded: List<ValidationInputDiagnostic>)
    private data class ProcessResult(val exitCode: Int?, val outputTail: String, val timedOut: Boolean, val toolchainMountScope: List<String>)
    private data class SandboxCommand(val command: List<String>, val probeCommand: List<String>, val toolchainMountScope: List<String>)

    private class OutputTail(private val limit: Int) {
        private val bytes = ByteArrayOutputStream()
        fun drain(input: InputStream) {
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                append(buffer, count)
            }
        }
        private fun append(source: ByteArray, count: Int) = synchronized(this) {
            if (count >= limit) {
                bytes.reset()
                bytes.write(source, count - limit, limit)
            } else {
                val overflow = bytes.size() + count - limit
                if (overflow > 0) {
                    val retained = bytes.toByteArray().copyOfRange(overflow, bytes.size())
                    bytes.reset()
                    bytes.write(retained)
                }
                bytes.write(source, 0, count)
            }
        }
        fun text(): String = synchronized(this) {
            var text = bytes.toString(Charsets.UTF_8)
            while (text.toByteArray(Charsets.UTF_8).size > limit) text = text.drop(1)
            text
        }
    }

    private companion object {
        const val MAX_INPUT_COUNT = 10_000
        const val MAX_FILE_BYTES = 1L * 1024 * 1024
        const val MAX_TOTAL_BYTES = 128L * 1024 * 1024
        const val MAX_OUTPUT_BYTES = 64 * 1024
        const val MAX_GIT_OUTPUT_BYTES = 4L * 1024 * 1024
        const val MAX_ADDRESS_SPACE = 4L * 1024 * 1024 * 1024
        const val MAX_CPU_SECONDS = 110
        const val MAX_PROCESSES = 64
        const val MAX_OPEN_FILES = 1_024
        const val MAX_OUTPUT_FILE_BYTES = 128L * 1024 * 1024
        val BWRAP: Path = Path.of("/usr/bin/bwrap")
        val PRLIMIT: Path = Path.of("/usr/bin/prlimit")
        const val SANDBOX_UID = 65534
        const val SANDBOX_GID = 65534
        const val JAVA_TOOL_OPTIONS = "-Xmx256m -XX:ReservedCodeCacheSize=64m -XX:CompressedClassSpaceSize=64m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC"
        val SECRET_FILE_NAMES = setOf(".npmrc", ".netrc", ".pypirc", "settings.xml", "gradle.properties", "credentials", "credentials.json", "id_rsa", "id_ed25519")
        val IGNORED_DIRECTORIES = setOf(".git", "build", "dist", "vendor", "node_modules", "cache", "caches", ".gradle")
    }
}
