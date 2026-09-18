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

fun enrichJavaStructure(snapshot: Snapshot): Snapshot {
    val nodes = snapshot.nodeSummaries.toMutableMap()
    val edges = snapshot.edges.filterNot { it.provenance == "javac_parser" }.toMutableList()
    val diagnostics = snapshot.sourceDiagnostics.toMutableList()
    val declarations = JavaTypeDeclarations.fromSources(snapshot.sourceFiles)
    snapshot.sourceFiles.filterKeys { it.endsWith(".java") }.forEach { (file, source) ->
        val fileId = "java:file:$file"
        nodes[fileId] = NodeSummary(
            id = fileId,
            kind = "file",
            name = file.substringAfterLast('/'),
            file = file,
            line_range = SourceRange(1, source.text().count { it == '\n' } + 1),
            file_hash = source.hash,
            language = "java",
            qualified_name = file,
            byte_span = ByteSpan(0, source.size),
            provenance = "javac_parser",
        )
        val parsed = parseJavaStructure(file, source.text())
        if (parsed == null) {
            diagnostics += SourceAdmissionDiagnostic(file, "java_parse_error")
            return@forEach
        }
        val boundaries = JavaUtf8Boundaries(source.text())
        val packageName = parsed.unit.packageName?.toString().orEmpty()
        val classes = mutableListOf<String>()
        val classTrees = mutableListOf<ClassTree>()
        val classIds = mutableListOf<String>()
        val parameterTypes = JavaParameterTypes(parsed.unit, declarations)
        object : TreePathScanner<Unit, Unit>() {
            override fun visitClass(node: ClassTree, unused: Unit?) {
                val start = parsed.positions.getStartPosition(parsed.unit, node).toInt()
                val end = parsed.positions.getEndPosition(parsed.unit, node).toInt()
                if (start < 0 || end <= start) return
                val simple = node.simpleName.toString()
                if (simple.isEmpty()) return
                val dotName = (listOf(packageName) + classes + simple).filter { it.isNotBlank() }.joinToString(".")
                val binaryName = listOf(packageName, (classes + simple).joinToString("$")).filter { it.isNotBlank() }.joinToString(".")
                val range = SourceRange(parsed.unit.lineMap.getLineNumber(start.toLong()).toInt(), parsed.unit.lineMap.getLineNumber((end - 1).toLong()).toInt())
                val candidates = snapshot.nodeSummaries.values.filter {
                    it.language == "java" && it.kind in setOf("class", "interface", "enum", "annotation") &&
                        it.file == file && it.qualified_name in setOf(dotName, binaryName) && it.line_range == range
                }
                val id = candidates.singleOrNull()?.id
                if (id != null) {
                    nodes[id] = nodes.getValue(id).copy(
                        parent = classIds.lastOrNull()?.takeIf { it.isNotBlank() } ?: fileId,
                        byte_span = ByteSpan(boundaries.byteOffset(start), boundaries.byteOffset(end)),
                        provenance = "javac_parser",
                    )
                    edges += EdgeSummary(classIds.lastOrNull()?.takeIf { it.isNotBlank() } ?: fileId, id, "contains", file, range.start, "javac_parser", "exact")
                }
                classes += simple
                classTrees += node
                classIds += id ?: ""
                super.visitClass(node, unused)
                classes.removeLast()
                classTrees.removeLast()
                classIds.removeLast()
            }

            override fun visitMethod(node: MethodTree, unused: Unit?) {
                val start = parsed.positions.getStartPosition(parsed.unit, node).toInt()
                val end = parsed.positions.getEndPosition(parsed.unit, node).toInt()
                if (start >= 0 && end > start) {
                    val range = SourceRange(parsed.unit.lineMap.getLineNumber(start.toLong()).toInt(), parsed.unit.lineMap.getLineNumber((end - 1).toLong()).toInt())
                    val parent = (listOf(packageName) + classes).filter { it.isNotBlank() }.joinToString(".")
                    val binaryParent = listOf(packageName, classes.joinToString("$")).filter { it.isNotBlank() }.joinToString(".")
                    val candidates = snapshot.methodInfos.values.filter { method ->
                        method.file == file && method.simpleName == node.name.toString() && method.parentQualifiedName in setOf(parent, binaryParent) &&
                            method.lineRange == range && parameterTypes.matches(node, classTrees, method.parameterTypes)
                    }
                    val id = candidates.singleOrNull()?.id
                    if (id != null) {
                        nodes[id] = nodes.getValue(id).copy(
                            parent = classIds.lastOrNull()?.takeIf { it.isNotBlank() } ?: fileId,
                            byte_span = ByteSpan(boundaries.byteOffset(start), boundaries.byteOffset(end)),
                            provenance = "javac_parser",
                        )
                        edges += EdgeSummary(classIds.lastOrNull()?.takeIf { it.isNotBlank() } ?: fileId, id, "contains", file, range.start, "javac_parser", "exact")
                    }
                }
                super.visitMethod(node, unused)
            }
        }.scan(parsed.unit, Unit)
    }
    val adapterInfo = snapshot.adapterInfo.toMutableMap()
    adapterInfo["java"]?.let { info ->
        adapterInfo["java"] = info.copy(capabilities = (info.capabilities + "containment").distinct())
    }
    return snapshot.copy(nodeSummaries = nodes, edges = edges.distinct(), sourceDiagnostics = diagnostics.distinct(), adapterInfo = adapterInfo)
}

private data class JavaParsed(val unit: CompilationUnitTree, val positions: com.sun.source.util.SourcePositions)

private fun parseJavaStructure(file: String, text: String): JavaParsed? {
    val compiler = ToolProvider.getSystemJavaCompiler() ?: return null
    val diagnostics = DiagnosticCollector<JavaFileObject>()
    val input = object : SimpleJavaFileObject(URI("string", null, "/$file", null), JavaFileObject.Kind.SOURCE) {
        override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = text
    }
    return compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8).use { manager ->
        val task = compiler.getTask(null, manager, diagnostics, listOf("-proc:none"), null, listOf(input)) as JavacTask
        val units = runCatching { task.parse().toList() }.getOrNull() ?: return@use null
        if (units.size != 1 || diagnostics.diagnostics.any { it.kind == Diagnostic.Kind.ERROR }) null
        else JavaParsed(units.single(), Trees.instance(task).sourcePositions)
    }
}

private class JavaUtf8Boundaries(source: String) {
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
    fun byteOffset(index: Int): Int = offsets.getOrNull(index)?.takeIf { it >= 0 } ?: error("javac position was not a UTF-8 boundary")
}
