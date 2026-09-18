package graphharness

import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.MethodTree
import com.sun.source.util.JavacTask
import java.net.URI
import java.nio.charset.StandardCharsets
import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.SimpleJavaFileObject
import javax.tools.ToolProvider

internal class JavaTypeDeclarations private constructor(
    private val aliases: Map<String, Set<String>>,
    val complete: Boolean,
) {
    fun canonical(name: String): String? = aliases[name]?.singleOrNull()
    fun contains(name: String): Boolean = name in aliases

    companion object {
        fun fromSources(sources: Map<String, RetainedSource>): JavaTypeDeclarations {
            val java = sources.filterKeys { it.endsWith(".java") }
            if (java.isEmpty()) return JavaTypeDeclarations(emptyMap(), true)
            val bounded = java.size <= 512 && java.values.sumOf { it.size.toLong() } <= 8 * 1024 * 1024
            if (!bounded) return JavaTypeDeclarations(emptyMap(), false)
            val compiler = ToolProvider.getSystemJavaCompiler() ?: return JavaTypeDeclarations(emptyMap(), false)
            val diagnostics = DiagnosticCollector<JavaFileObject>()
            val inputs = java.map { (file, source) ->
                object : SimpleJavaFileObject(URI("string", null, "/$file", null), JavaFileObject.Kind.SOURCE) {
                    override fun getCharContent(ignoreEncodingErrors: Boolean): CharSequence = source.text()
                }
            }
            return compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8).use { manager ->
                val task = compiler.getTask(null, manager, diagnostics, listOf("-proc:none"), null, inputs) as JavacTask
                val units = runCatching { task.parse().toList() }.getOrNull() ?: return@use JavaTypeDeclarations(emptyMap(), false)
                fromUnits(units, units.size == java.size && diagnostics.diagnostics.none { it.kind == Diagnostic.Kind.ERROR })
            }
        }

        fun fromUnits(units: List<CompilationUnitTree>, complete: Boolean = true): JavaTypeDeclarations {
            val aliases = linkedMapOf<String, MutableSet<String>>()
            for (unit in units) {
                val pkg = unit.packageName?.toString().orEmpty()
                fun visit(node: ClassTree, parents: List<String>) {
                    val name = node.simpleName.toString()
                    if (name.isEmpty()) return
                    val names = parents + name
                    val canonical = (listOf(pkg) + names).filter(String::isNotEmpty).joinToString(".")
                    val binary = listOf(pkg, names.joinToString("$")).filter(String::isNotEmpty).joinToString(".")
                    for (alias in setOf(canonical, binary)) aliases.getOrPut(alias) { linkedSetOf() }.add(canonical)
                    node.members.filterIsInstance<ClassTree>().forEach { visit(it, names) }
                }
                unit.typeDecls.filterIsInstance<ClassTree>().forEach { visit(it, emptyList()) }
            }
            return JavaTypeDeclarations(aliases, complete)
        }
    }
}

internal class JavaParameterTypes(
    private val unit: CompilationUnitTree,
    private val declarations: JavaTypeDeclarations,
) {
    private val packageName = unit.packageName?.toString().orEmpty()
    private val localDeclarations = JavaTypeDeclarations.fromUnits(listOf(unit))

    fun matches(method: MethodTree, classes: List<ClassTree>, expected: List<String>): Boolean {
        if (method.parameters.size != expected.size) return false
        val variables = (classes.flatMap { it.typeParameters } + method.typeParameters).map { it.name.toString() }.toSet()
        val scope = classes.indices.reversed().map { depth ->
            (listOf(packageName) + classes.take(depth + 1).map { it.simpleName.toString() }).filter(String::isNotEmpty).joinToString(".")
        }
        if (classes.any { it.simpleName.isEmpty() }) return false
        val inherits = classes.any { it.extendsClause != null || it.implementsClause.isNotEmpty() }
        val importsByName = unit.imports.filter { !it.isStatic && !it.qualifiedIdentifier.toString().endsWith(".*") }
            .map { it.qualifiedIdentifier.toString() }.groupBy { it.substringAfterLast('.') }
        if (importsByName.values.any { it.distinct().size > 1 }) return false
        if (unit.typeDecls.filterIsInstance<ClassTree>().any { declaration ->
            val canonical = listOf(packageName, declaration.simpleName.toString()).filter(String::isNotEmpty).joinToString(".")
            importsByName[declaration.simpleName.toString()].orEmpty().any { it != canonical }
        }) return false
        fun contains(name: String) = declarations.contains(name) || localDeclarations.contains(name)
        fun known(name: String) = if (declarations.contains(name)) declarations.canonical(name) else localDeclarations.canonical(name)
        fun named(name: String, backend: Boolean): String? {
            if (name in primitives) return name
            if (backend && '.' in name && contains(name)) return known(name)
            val head = name.substringBefore('.')
            val tail = name.removePrefix(head)
            if (head in variables) return if (tail.isEmpty()) "type-variable:$head" else null
            fun append(base: String): String? {
                val qualified = base + tail
                return if (contains(qualified)) known(qualified) else qualified
            }
            for (owner in scope) {
                val member = "$owner.$head"
                if (localDeclarations.contains(member)) return localDeclarations.canonical(member)?.let(::append)
                if (owner.substringAfterLast('.') == head) return append(owner)
            }
            if (inherits && !backend) return null
            val imports = unit.imports.filter { !it.isStatic && !it.qualifiedIdentifier.toString().endsWith(".*") }
                .map { it.qualifiedIdentifier.toString() }.filter { it.substringAfterLast('.') == head }.toSet()
            val ownTopLevel = unit.typeDecls.filterIsInstance<ClassTree>().any { it.simpleName.toString() == head }
            val inPackage = listOf(packageName, head).filter(String::isNotEmpty).joinToString(".")
            if (ownTopLevel) {
                if (imports.any { it != inPackage }) return null
                return localDeclarations.canonical(inPackage)?.let(::append)
            }
            if (imports.size > 1) return null
            if (imports.size == 1) return append(imports.single())
            if (unit.imports.any { it.isStatic && (it.qualifiedIdentifier.toString().endsWith(".*") || it.qualifiedIdentifier.toString().substringAfterLast('.') == head) }) return null
            if (contains(inPackage)) return known(inPackage)?.let(::append)
            if ('.' in name) return if (contains(name)) known(name) else name
            if (!declarations.complete || unit.imports.any { it.qualifiedIdentifier.toString().endsWith(".*") }) return null
            val lang = "java.lang.$head"
            return runCatching { Class.forName(lang, false, ClassLoader.getPlatformClassLoader()) }.getOrNull()?.let { append(lang) }
        }
        fun canonical(type: ParameterType, backend: Boolean): ParameterType? {
            val name = if (type.name == "?") "?" else named(type.name, backend) ?: return null
            val arguments = type.arguments?.map { canonical(it, backend) ?: return null }
            return type.copy(name = name, arguments = arguments)
        }
        fun explicit(type: ParameterType): Boolean =
            (type.name in primitives || type.name == "?" || '.' in type.name) && type.arguments.orEmpty().all(::explicit)
        val uncertainImports = unit.imports.any { it.isStatic || it.qualifiedIdentifier.toString().endsWith(".*") }
        return method.parameters.zip(expected).all { (parameter, metadata) ->
            val source = ParameterType.parse(parameter.type.toString()) ?: return@all false
            val target = ParameterType.parse(metadata) ?: return@all false
            if (!uncertainImports && source == target && explicit(source)) return@all true
            val resolved = canonical(source, false) ?: return@all false
            resolved == canonical(target, true)
        }
    }

    private data class ParameterType(val name: String, val arguments: List<ParameterType>?, val dimensions: Int = 0, val bound: String? = null) {
        companion object {
            fun parse(text: String): ParameterType? = runCatching {
                val input = text.replace("...", "[]")
                var position = 0
                fun space() { while (position < input.length && input[position].isWhitespace()) position++ }
                fun consume(token: String): Boolean {
                    space()
                    if (!input.startsWith(token, position)) return false
                    position += token.length
                    return true
                }
                fun identifier(): String {
                    space()
                    val start = position
                    check(position < input.length && Character.isJavaIdentifierStart(input.codePointAt(position)))
                    position += Character.charCount(input.codePointAt(position))
                    while (position < input.length && Character.isJavaIdentifierPart(input.codePointAt(position))) position += Character.charCount(input.codePointAt(position))
                    return input.substring(start, position)
                }
                fun type(): ParameterType {
                    if (consume("?")) {
                        space()
                        if (position == input.length || input[position] in ",>") return ParameterType("?", null)
                        val bound = identifier()
                        check(bound in setOf("extends", "super"))
                        return ParameterType("?", listOf(type()), bound = bound)
                    }
                    var name = identifier()
                    while (consume(".")) name += "." + identifier()
                    val arguments: List<ParameterType>? = if (consume("<")) buildList {
                        add(type())
                        while (consume(",")) add(type())
                        check(consume(">"))
                    } else null
                    var dimensions = 0
                    while (consume("[")) { check(consume("]")); dimensions++ }
                    return ParameterType(name, arguments, dimensions)
                }
                val result = type()
                space()
                check(position == input.length)
                result
            }.getOrNull()
        }
    }

    private companion object {
        val primitives = setOf("boolean", "byte", "char", "short", "int", "long", "float", "double")
    }
}
