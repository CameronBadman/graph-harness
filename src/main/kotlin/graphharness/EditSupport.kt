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

data class EditValidationResult(
    val success: Boolean,
    val edit_id: String,
    val validation_mode: String,
    val validation_scope: String,
    val validation_target: String,
    val validator: String,
    val attempted_validators: List<String>,
    val degraded: Boolean,
    val command: List<String>,
    val exit_code: Int,
    val duration_ms: Long,
    val affected_files: List<String>,
    val output_excerpt: String,
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
    val fileEdits: List<PendingFileEdit>,
    val affectedNodeIds: List<String>,
    val affectedFiles: List<String>,
    val applied: Boolean = false,
)

data class PendingFileEdit(
    val file: String,
    val fileHash: String,
    val newFileContent: String,
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
    val bodyIndent = inferBodyIndent(existingSource, bodyRange, methodLineIndent)
    val normalizedBody = normalizeBodySource(newBody, bodyIndent)
    return before + normalizedBody + after
}

fun inferBodyIndent(existingSource: String, bodyRange: IntRange, methodLineIndent: String): String {
    val body = methodBodyText(existingSource, bodyRange)
    val existingIndent = body.lines()
        .firstOrNull { it.trim().isNotEmpty() }
        ?.let(::indentOf)
    if (!existingIndent.isNullOrEmpty()) return existingIndent
    return if ('\t' in methodLineIndent) {
        methodLineIndent + "\t"
    } else {
        methodLineIndent + "    "
    }
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
    var prefix = 0
    while (prefix < oldLines.size && prefix < newLines.size && oldLines[prefix] == newLines[prefix]) {
        prefix++
    }

    var oldSuffix = oldLines.size - 1
    var newSuffix = newLines.size - 1
    while (oldSuffix >= prefix && newSuffix >= prefix && oldLines[oldSuffix] == newLines[newSuffix]) {
        oldSuffix--
        newSuffix--
    }

    val contextBefore = if (prefix > 0) 1 else 0
    val contextAfter = if (oldSuffix + 1 < oldLines.size) 1 else 0
    val oldStartIndex = (prefix - contextBefore).coerceAtLeast(0)
    val newStartIndex = oldStartIndex
    val oldEndExclusive = (oldSuffix + 1 + contextAfter).coerceAtMost(oldLines.size)
    val newEndExclusive = (newSuffix + 1 + contextAfter).coerceAtMost(newLines.size)
    val oldHunk = oldLines.subList(oldStartIndex, oldEndExclusive)
    val newHunk = newLines.subList(newStartIndex, newEndExclusive)

    return buildString {
        append("--- a/").append(file).append('\n')
        append("+++ b/").append(file).append('\n')
        append("@@ -").append(methodRange.start + oldStartIndex).append(',').append(oldHunk.size)
            .append(" +").append(methodRange.start + newStartIndex).append(',').append(newHunk.size)
            .append(" @@\n")
        oldHunk.forEachIndexed { index, line ->
            val absolute = oldStartIndex + index
            val newAbsolute = newStartIndex + index
            val oldInChange = absolute in prefix..oldSuffix
            val newInChange = newAbsolute in prefix..newSuffix
            when {
                !oldInChange && !newInChange && index < newHunk.size && line == newHunk[index] -> append(' ').append(line).append('\n')
                oldInChange -> append('-').append(line).append('\n')
                else -> append(' ').append(line).append('\n')
            }
        }
        if (newHunk.isNotEmpty()) {
            newHunk.forEachIndexed { index, line ->
                val absolute = newStartIndex + index
                val oldAbsolute = oldStartIndex + index
                val newInChange = absolute in prefix..newSuffix
                val oldMatches = oldAbsolute < oldHunk.size + oldStartIndex && index < oldHunk.size && line == oldHunk[index]
                if (newInChange && !oldMatches) {
                    append('+').append(line).append('\n')
                }
            }
        }
    }.trimEnd()
}

fun readPathText(path: Path): String = Files.readString(path)

fun methodBodyText(existingSource: String, bodyRange: IntRange): String =
    existingSource.substring(bodyRange.first, bodyRange.last + 1)

fun patchMethodBody(existingSource: String, bodyRange: IntRange, payload: EditRequestPayload): String {
    val currentBody = methodBodyText(existingSource, bodyRange)
    val mode = payload.patch_mode ?: "replace_body"
    return when (mode) {
        "replace_body" -> renderMethodBody(existingSource, bodyRange, payload.new_body.orEmpty())
        "insert_before", "insert_after", "replace_line" -> {
            val anchor = payload.anchor ?: error("payload.anchor is required for patch_mode=$mode")
            val snippet = payload.snippet ?: error("payload.snippet is required for patch_mode=$mode")
            val patchedBody = applyBodyPatch(currentBody, anchor, snippet, mode)
            renderMethodBody(existingSource, bodyRange, patchedBody)
        }
        else -> error("Unsupported patch_mode: $mode")
    }
}

fun applyBodyPatch(currentBody: String, anchor: String, snippet: String, mode: String): String {
    val lines = currentBody.lines().toMutableList()
    val anchorIndex = lines.indexOfFirst { it.contains(anchor) }
    require(anchorIndex >= 0) { "Anchor not found in method body: $anchor" }
    val snippetLines = snippet.lines()
    when (mode) {
        "insert_before" -> lines.addAll(anchorIndex, snippetLines)
        "insert_after" -> lines.addAll(anchorIndex + 1, snippetLines)
        "replace_line" -> {
            lines.removeAt(anchorIndex)
            lines.addAll(anchorIndex, snippetLines)
        }
    }
    return lines.joinToString("\n")
}

fun buildRenameDiff(file: String, oldContent: String, newContent: String): String {
    val oldLines = oldContent.lines()
    val newLines = newContent.lines()
    val changed = oldLines.indices.filter { index -> index < newLines.size && oldLines[index] != newLines[index] }
    if (changed.isEmpty()) return ""
    val groups = mutableListOf<MutableList<Int>>()
    changed.forEach { index ->
        val current = groups.lastOrNull()
        if (current == null || index > current.last() + 2) {
            groups += mutableListOf(index)
        } else {
            current += index
        }
    }

    return buildString {
        append("--- a/").append(file).append('\n')
        append("+++ b/").append(file).append('\n')
        groups.forEach { group ->
            val start = (group.first() - 1).coerceAtLeast(0)
            val end = (group.last() + 1).coerceAtMost(oldLines.lastIndex)
            val oldHunk = oldLines.subList(start, end + 1)
            val newHunk = newLines.subList(start, end + 1)
            append("@@ -").append(start + 1).append(',').append(oldHunk.size)
                .append(" +").append(start + 1).append(',').append(newHunk.size)
                .append(" @@\n")
            oldHunk.forEachIndexed { index, line ->
                val absolute = start + index
                if (absolute in group) append('-').append(line).append('\n')
                else append(' ').append(line).append('\n')
            }
            newHunk.forEachIndexed { index, line ->
                val absolute = start + index
                if (absolute in group) append('+').append(line).append('\n')
            }
        }
    }.trimEnd()
}

fun buildAnchorPatchDiff(
    file: String,
    methodSource: String,
    methodStartLine: Int,
    anchor: String,
    snippet: String,
    mode: String,
): String {
    val lines = methodSource.lines()
    val anchorIndex = lines.indexOfFirst { it.contains(anchor) }
    require(anchorIndex >= 0) { "Anchor not found in method source: $anchor" }
    val contextStart = (anchorIndex - 1).coerceAtLeast(0)
    val contextEnd = (anchorIndex + 1).coerceAtMost(lines.lastIndex)
    val oldHunk = lines.subList(contextStart, contextEnd + 1)
    val snippetLines = snippet.lines()
    val newHunk = mutableListOf<String>()
    oldHunk.forEachIndexed { index, line ->
        val absolute = contextStart + index
        when {
            absolute == anchorIndex && mode == "insert_before" -> {
                newHunk += snippetLines.map { indentOf(line) + it.trimStart() }
                newHunk += line
            }
            absolute == anchorIndex && mode == "insert_after" -> {
                newHunk += line
                newHunk += snippetLines.map { indentOf(line) + it.trimStart() }
            }
            absolute == anchorIndex && mode == "replace_line" -> {
                newHunk += snippetLines.map { indentOf(line) + it.trimStart() }
            }
            else -> newHunk += line
        }
    }
    return buildString {
        append("--- a/").append(file).append('\n')
        append("+++ b/").append(file).append('\n')
        append("@@ -").append(methodStartLine + contextStart).append(',').append(oldHunk.size)
            .append(" +").append(methodStartLine + contextStart).append(',').append(newHunk.size)
            .append(" @@\n")
        oldHunk.forEachIndexed { index, line ->
            if (contextStart + index == anchorIndex) append('-').append(line).append('\n')
            else append(' ').append(line).append('\n')
        }
        newHunk.forEach { line ->
            if (oldHunk.contains(line) && line != oldHunk.getOrNull(anchorIndex - contextStart)) append(' ').append(line).append('\n')
            else if (!oldHunk.contains(line) || mode != "replace_line") append('+').append(line).append('\n')
        }
    }.trimEnd()
}
