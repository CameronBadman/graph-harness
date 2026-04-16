package graphharness

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

data class EditPlanResult(
    val edit_id: String? = null,
    val operation: String,
    val target_node_id: String,
    val diff: String,
    val affected_nodes: List<String>,
    val affected_files: List<String>,
    val validation_errors: List<String>,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
)

data class EditApplyResult(
    val success: Boolean,
    val edit_id: String,
    val updated_nodes: List<String>,
    val affected_files: List<String>,
    val validation_errors: List<String>,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
)

data class PendingEdit(
    val id: String,
    val operation: String,
    val targetNodeId: String,
    val snapshotId: String,
    val file: String,
    val fileHash: String,
    val newFileContent: String,
    val affectedNodeIds: List<String>,
    val affectedFiles: List<String>,
)

fun sha256(text: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

fun makeEditId(): String = UUID.randomUUID().toString()

fun indentOf(line: String): String = line.takeWhile { it == ' ' || it == '\t' }

fun methodBodyRange(source: String, methodStartLine: Int, methodEndLine: Int): IntRange? {
    val startOffset = lineStartOffset(source, methodStartLine)
    val totalLines = source.lineSequence().count()
    val endOffsetExclusive = if (methodEndLine + 1 <= totalLines) {
        lineStartOffset(source, methodEndLine + 1)
    } else {
        source.length
    }
    val scoped = source.substring(startOffset, endOffsetExclusive)
    val localOpen = scoped.indexOf('{')
    if (localOpen < 0) return null
    val openIndex = startOffset + localOpen
    var depth = 0
    for (i in openIndex until minOf(endOffsetExclusive, source.length)) {
        when (source[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) {
                    return (openIndex + 1)..<i
                }
            }
        }
    }
    return null
}

fun renderMethodBody(existingSource: String, bodyRange: IntRange, newBody: String): String {
    val before = existingSource.substring(0, bodyRange.first)
    val after = existingSource.substring(bodyRange.last + 1)
    val lineStart = existingSource.lastIndexOf('\n', maxOf(0, bodyRange.first - 1)).let { if (it < 0) 0 else it + 1 }
    val methodLineIndent = indentOf(existingSource.substring(lineStart, bodyRange.first))
    val bodyIndent = methodLineIndent + "    "
    val normalizedBody = normalizeBodySource(newBody, bodyIndent)
    return before + normalizedBody + after
}

fun normalizeBodySource(newBody: String, bodyIndent: String): String {
    val trimmed = newBody.trim('\n', '\r')
    if (trimmed.isBlank()) {
        return "\n"
    }
    val rawLines = trimmed.lines()
    val minimumIndent = rawLines
        .filter { it.isNotBlank() }
        .minOfOrNull { indentOf(it).length }
        ?: 0
    val normalizedLines = rawLines.map { line ->
        val content = if (line.length >= minimumIndent) line.drop(minimumIndent) else line.trimStart()
        if (content.isBlank()) "" else bodyIndent + content
    }
    return "\n" + normalizedLines.joinToString("\n") + "\n"
}

fun buildMethodDiff(
    file: String,
    methodRange: SourceRange,
    oldSource: String,
    newSource: String,
): String {
    val oldLines = oldSource.lines()
    val newLines = newSource.lines()
    return buildString {
        append("--- a/").append(file).append('\n')
        append("+++ b/").append(file).append('\n')
        append("@@ -").append(methodRange.start).append(',').append(oldLines.size)
            .append(" +").append(methodRange.start).append(',').append(newLines.size)
            .append(" @@\n")
        oldLines.forEach { append('-').append(it).append('\n') }
        newLines.forEach { append('+').append(it).append('\n') }
    }.trimEnd()
}

fun readPathText(path: Path): String = Files.readString(path)
