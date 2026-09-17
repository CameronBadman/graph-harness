package graphharness

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

internal data class StructuralDefinition(
    val identity: String,
    val parentIdentity: String?,
    val kind: String,
    val name: String,
    val qualifiedName: String,
    val startByte: Int,
    val endByte: Int,
    val startLine: Int,
    val endLine: Int,
)

internal data class StructuralFileResult(
    val file: String,
    val language: String,
    val parser: String,
    val version: String?,
    val available: Boolean,
    val diagnostics: List<String>,
    val definitions: List<StructuralDefinition>,
)

internal class StructuralAdapters(private val parserRoot: Path = locateParserRoot()) {
    private val cache = mutableMapOf<String, StructuralFileResult>()

    fun analyze(file: String, source: RetainedSource): StructuralFileResult? {
        val language = languageFor(file) ?: return null
        val key = "$language:$file:${source.hash}"
        return synchronized(cache) { cache[key] ?: invoke(language, file, source).also { cache[key] = it } }
    }

    fun retain(files: Map<String, RetainedSource>) {
        val valid = files.mapNotNull { (file, source) -> languageFor(file)?.let { "$it:$file:${source.hash}" } }.toSet()
        synchronized(cache) { cache.keys.retainAll(valid) }
    }

    private fun invoke(language: String, file: String, source: RetainedSource): StructuralFileResult {
        val helper = when (language) {
            "python" -> parserRoot.resolve("python_ast_parser.py")
            else -> parserRoot.resolve("typescript_compiler_parser.cjs")
        }
        if (!Files.isRegularFile(helper)) {
            return unavailable(language, "structural parser helper is unavailable", file)
        }
        val command = when (language) {
            "python" -> listOf(System.getenv("GRAPHHARNESS_PYTHON") ?: "python3", helper.toString())
            else -> listOf(System.getenv("GRAPHHARNESS_NODE") ?: "node", helper.toString())
        }
        val request = linkedMapOf<String, Any?>(
            "path" to file,
            "source" to source.text(),
            "hash" to source.hash,
        )
        if (language != "python") {
            locateTypeScript()?.let { request["typescriptPath"] = it.toString() }
        }
        return runCatching {
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            try {
                val writeFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
                val output = java.util.concurrent.atomic.AtomicReference<ByteArray?>(null)
                val readFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
                val payload = jObject(*request.entries.map { it.key to it.value }.toTypedArray()).stringify().toByteArray(StandardCharsets.UTF_8)
                val writer = thread(isDaemon = true) {
                    runCatching { process.outputStream.use { it.write(payload) } }.onFailure(writeFailure::set)
                }
                val reader = thread(isDaemon = true) {
                    runCatching { readBounded(process.inputStream, 2 * 1024 * 1024) }
                        .onSuccess(output::set)
                        .onFailure(readFailure::set)
                }
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    terminate(process)
                    return unavailable(language, "structural parser timed out", file)
                }
                writer.join(500)
                reader.join(500)
                writeFailure.get()?.let { return unavailable(language, "structural parser input failed", file) }
                readFailure.get()?.let { return unavailable(language, "structural parser output exceeded limit", file) }
                if (process.exitValue() != 0) return unavailable(language, "structural parser exited with status ${process.exitValue()}", file)
                decode(language, file, source, output.get()?.toString(StandardCharsets.UTF_8) ?: return unavailable(language, "structural parser produced no output", file))
            } finally {
                if (process.isAlive) terminate(process)
            }
        }.getOrElse { unavailable(language, "structural parser failed", file) }
    }

    private fun decode(language: String, file: String, source: RetainedSource, output: String): StructuralFileResult {
        val objectValue = MiniJson.parse(output) as? JObject ?: return unavailable(language, "structural parser returned invalid JSON", file)
        fun string(name: String): String? = (objectValue.fields[name] as? JString)?.value
        if (string("hash") != source.hash || string("actualHash") != source.hash) {
            return unavailable(language, "structural parser hash mismatch", file)
        }
        val diagnostics = (objectValue.fields["diagnostics"] as? JArray)?.values.orEmpty().mapNotNull { value ->
            (value as? JObject)?.fields?.get("message") as? JString
        }.map { it.value }.take(100)
        val definitions = (objectValue.fields["definitions"] as? JArray)?.values.orEmpty().mapNotNull { value ->
            val fields = (value as? JObject)?.fields ?: return@mapNotNull null
            fun required(name: String) = (fields[name] as? JString)?.value
            fun number(name: String) = (fields[name] as? JNumber)?.raw?.toIntOrNull()
            val identity = required("identity") ?: return@mapNotNull null
            val kind = required("kind") ?: return@mapNotNull null
            val name = required("name") ?: return@mapNotNull null
            val qualified = required("qualifiedName") ?: return@mapNotNull null
            val start = number("startByte") ?: return@mapNotNull null
            val end = number("endByte") ?: return@mapNotNull null
            val startLine = number("startLine") ?: return@mapNotNull null
            val endLine = number("endLine") ?: return@mapNotNull null
            if (start < 0 || end < start || end > source.size || startLine < 1 || endLine < startLine) return@mapNotNull null
            StructuralDefinition(identity, required("parentIdentity"), normalizeKind(kind), name, qualified, start, end, startLine, endLine)
        }.take(10_000)
        return StructuralFileResult(file, language, languageParser(language), string("parserVersion"), true, diagnostics, definitions)
    }

    private fun unavailable(language: String, message: String, file: String) =
        StructuralFileResult(file, language, languageParser(language), null, false, listOf(message), emptyList())

    fun adapterInfo(results: Collection<StructuralFileResult>): Map<String, LanguageAdapterInfo> =
        results.groupBy { it.language }.mapValues { (_, files) ->
            val available = files.any { it.available }
            LanguageAdapterInfo(
                language = files.first().language,
                parser = files.first().parser,
                version = files.mapNotNull { it.version }.distinct().singleOrNull(),
                available = available,
                capabilities = if (available) STRUCTURAL_CAPABILITIES else emptyList(),
                diagnostics = files.flatMap { it.diagnostics }.distinct().take(20),
            )
        }

    companion object {
        val STRUCTURAL_CAPABILITIES = listOf("search", "source", "node_detail", "context", "containment")
        fun languageFor(file: String): String? = when (file.substringAfterLast('.', "").lowercase()) {
            "ts", "tsx" -> "typescript"
            "js", "jsx" -> "javascript"
            "py" -> "python"
            else -> null
        }
        private fun languageParser(language: String) = if (language == "python") "python-ast" else "typescript-compiler-api"
        private fun normalizeKind(kind: String): String = when (kind) {
            "ClassDeclaration" -> "class"
            "FunctionDeclaration", "async_function" -> "function"
            "InterfaceDeclaration" -> "interface"
            "EnumDeclaration" -> "enum"
            "TypeAliasDeclaration" -> "type_alias"
            "MethodDeclaration" -> "method"
            "PropertyDeclaration" -> "field"
            "VariableDeclaration" -> "variable"
            else -> kind
        }
        private fun locateParserRoot(): Path {
            System.getenv("GRAPHHARNESS_PARSERS_DIR")?.let { return Path.of(it).toAbsolutePath().normalize() }
            val codeSource = Path.of(StructuralAdapters::class.java.protectionDomain.codeSource.location.toURI())
            if (Files.isRegularFile(codeSource)) return codeSource.parent.parent.resolve("parsers").normalize()
            val development = generateSequence(codeSource) { it.parent }.take(6)
                .firstOrNull { Files.isDirectory(it.resolve("src/main/kotlin/graphharness")) }
            return (development ?: codeSource).resolve("parsers").normalize()
        }
        private fun locateTypeScript(): Path? {
            val configured = System.getenv("GRAPHHARNESS_TYPESCRIPT_PATH")?.let(Path::of)
            val bundled = locateParserRoot().resolve("node_modules/typescript/lib/typescript.js")
            return listOfNotNull(configured, bundled).firstOrNull { Files.isRegularFile(it) }?.toAbsolutePath()?.normalize()
        }
        private fun readBounded(input: java.io.InputStream, limit: Int): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) return output.toByteArray()
                if (output.size() + count > limit) error("structural parser output exceeds limit")
                output.write(buffer, 0, count)
            }
        }
        private fun terminate(process: Process) {
            process.toHandle().descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
        }
    }
}
