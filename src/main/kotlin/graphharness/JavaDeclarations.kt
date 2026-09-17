package graphharness

import com.sun.source.tree.ClassTree
import com.sun.source.tree.MethodTree
import com.sun.source.util.JavacTask
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees
import java.net.URI
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

fun parseJavaDeclarations(file: String, source: String): FileParseResult {
    val compiler = ToolProvider.getSystemJavaCompiler() ?: return FileParseResult(emptyList(), emptyList())
    val diagnostics = DiagnosticCollector<JavaFileObject>()
    val input = object : SimpleJavaFileObject(URI("string", null, "/$file", null), JavaFileObject.Kind.SOURCE) {
        override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source
    }
    val parsed = compiler.getStandardFileManager(diagnostics, null, null).use { manager ->
        val task = compiler.getTask(null, manager, diagnostics, listOf("-proc:none"), null, listOf(input)) as JavacTask
        val units = runCatching { task.parse().toList() }.getOrNull() ?: return@use null
        if (units.size != 1 || diagnostics.diagnostics.any { it.kind == Diagnostic.Kind.ERROR }) null else units.single() to Trees.instance(task).sourcePositions
    } ?: return FileParseResult(emptyList(), emptyList())
    val (unit, positions) = parsed
    val packageName = unit.packageName?.toString().orEmpty()
    val types = mutableListOf<TypeInfo>()
    val methods = mutableListOf<MethodInfo>()
    val names = mutableListOf<String>()
    val ids = mutableListOf<String>()
    object : TreePathScanner<Unit, Unit>() {
        override fun visitClass(node: ClassTree, unused: Unit?) {
            val start = positions.getStartPosition(unit, node).toInt()
            val end = positions.getEndPosition(unit, node).toInt()
            if (start < 0 || end <= start || node.simpleName.isEmpty()) return
            val simple = node.simpleName.toString()
            val qualified = (listOf(packageName) + names + simple).filter { it.isNotBlank() }.joinToString(".")
            val kind = when (node.kind.name) {
                "INTERFACE" -> "interface"
                "ENUM" -> "enum"
                "ANNOTATION_TYPE" -> "annotation"
                else -> "class"
            }
            val range = SourceRange(unit.lineMap.getLineNumber(start.toLong()).toInt(), unit.lineMap.getLineNumber((end - 1).toLong()).toInt())
            val type = TypeInfo(
                id = "type:$qualified", kind = kind, packageName = packageName, simpleName = simple,
                qualifiedName = qualified, file = file, lineRange = range,
                visibility = visibilityOf(node.modifiers.flags.map { it.name }.toSet()),
                annotations = node.modifiers.annotations.map { "@${it.annotationType}" },
                extendsType = node.extendsClause?.toString(), implementsTypes = node.implementsClause.map { it.toString() },
            )
            types += type
            names += simple
            ids += type.id
            super.visitClass(node, unused)
            names.removeLast()
            ids.removeLast()
        }

        override fun visitMethod(node: MethodTree, unused: Unit?) {
            val parentId = ids.lastOrNull() ?: return
            if (node.body == null || node.returnType == null) return
            val start = positions.getStartPosition(unit, node).toInt()
            val end = positions.getEndPosition(unit, node).toInt()
            if (start < 0 || end <= start) return
            val parent = (listOf(packageName) + names).filter { it.isNotBlank() }.joinToString(".")
            val parameters = node.parameters.map { it.type.toString() }
            val returnType = node.returnType.toString()
            val qualified = "$parent.${node.name}"
            val range = SourceRange(unit.lineMap.getLineNumber(start.toLong()).toInt(), unit.lineMap.getLineNumber((end - 1).toLong()).toInt())
            val body = source.substring(start, end)
            val calls = callPattern.findAll(body).map { match ->
                match.groupValues[1] to (range.start + body.substring(0, match.range.first).count { it == '\n' })
            }.filterNot { it.first in JAVA_KEYWORDS || it.first == node.name.toString() }.toList()
            val refs = (parameters + listOf(returnType) + bodyTypePattern.findAll(body).map { it.groupValues[1] })
                .filter { it.isNotBlank() && it.first().isUpperCase() }.toSet()
            methods += MethodInfo(
                id = "method:$qualified:${signatureOf(parameters, returnType)}", parentTypeId = parentId,
                parentQualifiedName = parent, simpleName = node.name.toString(), qualifiedName = qualified,
                signature = "(${parameters.joinToString(", ")}) -> $returnType", file = file, lineRange = range,
                visibility = visibilityOf(node.modifiers.flags.map { it.name }.toSet()),
                annotations = node.modifiers.annotations.map { "@${it.annotationType}" }, complexity = computeComplexity(body),
                loc = range.end - range.start + 1, returnType = returnType, parameterTypes = parameters,
                body = body, callTokens = calls, typeRefs = refs,
            )
        }
    }.scan(unit, Unit)
    return FileParseResult(types, methods)
}

private fun visibilityOf(flags: Set<String>): String = when {
    "PUBLIC" in flags -> "public"
    "PROTECTED" in flags -> "protected"
    "PRIVATE" in flags -> "private"
    else -> "package"
}
