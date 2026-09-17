package graphharness

import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.MethodTree
import com.sun.source.util.JavacTask
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

class SafeJavaBodyPlan(
    val file: String,
    val baseHash: String,
    val bodyStartByte: Int,
    val bodyEndByte: Int,
    replacementUtf8: ByteArray,
    preimageBytes: ByteArray,
    updatedBytes: ByteArray,
) {
    private val replacement = replacementUtf8.copyOf()
    private val preimage = preimageBytes.copyOf()
    private val updated = updatedBytes.copyOf()

    fun replacementUtf8(): ByteArray = replacement.copyOf()
    fun preimageBytes(): ByteArray = preimage.copyOf()
    fun updatedBytes(): ByteArray = updated.copyOf()
}

object SafeJavaEditor {
    fun plan(
        source: RetainedSource,
        target: MethodInfo,
        expectedHash: String,
        newBody: String,
    ): SafeJavaBodyPlan {
        if (source.hash != expectedHash) fail("stale_source", 409, "Source bytes changed before planning.")
        if (target.simpleName == "<init>" || target.simpleName == "<clinit>") {
            fail("unsupported_operation", 422, "Constructor and initializer edits are unavailable.")
        }
        runCatching {
            StandardCharsets.UTF_8.newEncoder().encode(java.nio.CharBuffer.wrap(newBody))
        }.getOrElse { fail("invalid_encoding", 422, "Replacement must contain valid Unicode text.") }
        val sourceText = source.text()
        val parsed = parse(sourceText, "parse_error")
        val candidate = selectCandidate(parsed.unit, parsed.positions, target)
        val body = candidate.body ?: fail("unsupported_body_span", 422, "The method does not have a concrete body.")
        val start = parsed.positions.getStartPosition(parsed.unit, body)
        val end = parsed.positions.getEndPosition(parsed.unit, body)
        if (start < 0 || end <= start || end > sourceText.length || sourceText[start.toInt()] != '{' || sourceText[(end - 1).toInt()] != '}') {
            fail("unsupported_body_span", 422, "The compiler did not provide a usable method body span.")
        }
        val boundaries = Utf8Boundaries(sourceText)
        val bodyStart = boundaries.byteOffset(start.toInt() + 1)
        val bodyEnd = boundaries.byteOffset(end.toInt() - 1)
        if (bodyEnd < bodyStart) fail("unsupported_body_span", 422, "The method body span is invalid.")
        val rendered = renderBody(sourceText, start.toInt(), newBody)
        val replacement = rendered.toByteArray(StandardCharsets.UTF_8)
        val original = source.bytes()
        val updated = ByteArray(bodyStart + replacement.size + original.size - bodyEnd)
        original.copyInto(updated, 0, 0, bodyStart)
        replacement.copyInto(updated, bodyStart)
        original.copyInto(updated, bodyStart + replacement.size, bodyEnd, original.size)
        val reparsed = parse(decodeUtf8(updated), "replacement_parse_error")
        val expectedEnd = start + rendered.length + 2
        val updatedTarget = target.copy(lineRange = SourceRange(target.lineRange.start, reparsed.unit.lineMap.getLineNumber(expectedEnd - 1).toInt()))
        val updatedMethod = selectCandidate(reparsed.unit, reparsed.positions, updatedTarget)
        if (reparsed.positions.getStartPosition(reparsed.unit, updatedMethod.body) != start ||
            reparsed.positions.getEndPosition(reparsed.unit, updatedMethod.body) != expectedEnd) {
            fail("scope_changed", 422, "Replacement must stay within the selected method body.")
        }
        return SafeJavaBodyPlan(target.file, source.hash, bodyStart, bodyEnd, replacement, original, updated)
    }

    private fun selectCandidate(
        unit: CompilationUnitTree,
        positions: com.sun.source.util.SourcePositions,
        target: MethodInfo,
    ): MethodTree {
        val packageName = unit.packageName?.toString().orEmpty()
        val candidates = mutableListOf<MethodTree>()
        val classNames = mutableListOf<String>()
        object : TreePathScanner<Unit, Unit>() {
            override fun visitClass(node: ClassTree, unused: Unit?) {
                classNames += node.simpleName.toString()
                super.visitClass(node, unused)
                classNames.removeLast()
            }

            override fun visitMethod(node: MethodTree, unused: Unit?) {
                val parent = (listOf(packageName) + classNames).filter { it.isNotBlank() }.joinToString(".")
                val binaryParent = listOf(packageName, classNames.joinToString("$")).filter { it.isNotBlank() }.joinToString(".")
                val start = positions.getStartPosition(unit, node)
                val end = positions.getEndPosition(unit, node)
                val linesMatch = start >= 0 && end >= start &&
                    unit.lineMap.getLineNumber(start) == target.lineRange.start.toLong() &&
                    unit.lineMap.getLineNumber((end - 1).coerceAtLeast(start)) == target.lineRange.end.toLong()
                val parametersMatch = node.parameters.map { normalizeType(it.type.toString()) } == target.parameterTypes.map(::normalizeType)
                if (node.body != null && node.returnType != null && target.parentQualifiedName in setOf(parent, binaryParent) && node.name.toString() == target.simpleName && parametersMatch && linesMatch) {
                    candidates += node
                }
                super.visitMethod(node, unused)
            }
        }.scan(unit, Unit)
        if (candidates.isEmpty()) fail("target_not_found", 422, "No parser-confirmed method matches the requested node.")
        if (candidates.size != 1) fail("ambiguous_target", 422, "More than one parser-confirmed method matches the requested node.")
        return candidates.single()
    }

    private fun normalizeType(value: String): String = value.filterNot(Char::isWhitespace)

    private fun renderBody(source: String, openBrace: Int, newBody: String): String {
        val lineEnding = if ("\r\n" in source) "\r\n" else "\n"
        val content = newBody.trim('\r', '\n')
        val lineStart = source.lastIndexOf('\n', openBrace).let { if (it < 0) 0 else it + 1 }
        val baseIndent = source.substring(lineStart, openBrace).takeWhile { it == ' ' || it == '\t' }
        if (content.isBlank()) return lineEnding + baseIndent
        val indent = baseIndent + "    "
        val normalized = content.replace("\r\n", "\n").split('\n')
        val minimum = normalized.filter { it.isNotBlank() }.minOfOrNull { it.takeWhile { c -> c == ' ' || c == '\t' }.length } ?: 0
        return lineEnding + normalized.joinToString(lineEnding) { line -> if (line.isBlank()) "" else indent + line.drop(minimum) } + lineEnding + baseIndent
    }

    private fun parse(text: String, code: String): ParsedUnit {
        val compiler = ToolProvider.getSystemJavaCompiler() ?: fail("parser_unavailable", 422, "The JDK compiler is unavailable.")
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        val input = object : SimpleJavaFileObject(URI.create("string:///SafeEdit.java"), JavaFileObject.Kind.SOURCE) {
            override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = text
        }
        return compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8).use { fileManager ->
            val task = compiler.getTask(null, fileManager, diagnostics, listOf("-proc:none"), null, listOf(input)) as JavacTask
            val units = runCatching { task.parse().toList() }.getOrElse { fail(code, 422, "Java parsing failed.") }
            if (diagnostics.diagnostics.any { it.kind == Diagnostic.Kind.ERROR } || units.size != 1) {
                fail(code, 422, "Java parsing reported an error.")
            }
            ParsedUnit(units.single(), Trees.instance(task).sourcePositions)
        }
    }

    private data class ParsedUnit(
        val unit: CompilationUnitTree,
        val positions: com.sun.source.util.SourcePositions,
    )

    private class Utf8Boundaries(source: String) {
        private val offsets = IntArray(source.length + 1) { -1 }

        init {
            var chars = 0
            var bytes = 0
            offsets[0] = 0
            while (chars < source.length) {
                val count = if (Character.isHighSurrogate(source[chars]) && chars + 1 < source.length && Character.isLowSurrogate(source[chars + 1])) 2 else 1
                bytes += source.substring(chars, chars + count).toByteArray(StandardCharsets.UTF_8).size
                chars += count
                offsets[chars] = bytes
            }
        }

        fun byteOffset(index: Int): Int = offsets.getOrNull(index)?.takeIf { it >= 0 }
            ?: fail("unsupported_body_span", 422, "A parser position does not align to a UTF-8 boundary.")
    }

    private fun fail(code: String, status: Int, message: String): Nothing = throw LiveFailure(code, status, message)
}
