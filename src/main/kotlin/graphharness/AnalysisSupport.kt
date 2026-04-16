package graphharness

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.min

val callPattern = Regex("""\b([A-Za-z_]\w*)\s*\(""")
val bodyTypePattern = Regex("""\b([A-Z][A-Za-z0-9_]*)\b""")
val JAVA_KEYWORDS = setOf(
    "if", "for", "while", "switch", "catch", "return", "new", "throw", "this", "super", "try", "do", "else", "synchronized",
)
val DEFAULT_BLIND_SPOTS = listOf(
    "reflection",
    "dependency injection wiring",
    "dynamic proxies",
    "generated sources",
    "runtime-only dispatch",
)

fun summaryModeFor(snapshot: Snapshot): String = when {
    snapshot.sourceIndex.size <= 10 && snapshot.methodInfos.size <= 20 -> "compact"
    snapshot.sourceIndex.size <= 120 && snapshot.methodInfos.size <= 400 -> "standard"
    else -> "expanded"
}

fun summarizedClusters(snapshot: Snapshot, summaryMode: String): List<ClusterSummary> {
    if (summaryMode == "compact") return emptyList()

    val clusterLimit = when (summaryMode) {
        "standard" -> 2
        else -> 5
    }
    val keyNodeLimit = when (summaryMode) {
        "standard" -> 2
        else -> 4
    }

    return snapshot.clusters
        .sortedWith(
            compareByDescending<ClusterSummary> { it.external_edges + it.node_count }
                .thenBy { it.label },
        )
        .take(clusterLimit)
        .map { cluster ->
            cluster.copy(
                key_nodes = cluster.key_nodes.take(keyNodeLimit),
                bridge_nodes = if (summaryMode == "expanded") cluster.bridge_nodes.take(3) else emptyList(),
            )
        }
}

fun NodeSummary.toOrientationNode(): OrientationNode =
    OrientationNode(
        id = id,
        kind = kind,
        name = name,
        file = file,
    )

fun MethodInfo.toOrientationNode(): OrientationNode =
    OrientationNode(
        id = id,
        kind = "method",
        name = qualifiedName,
        file = file,
    )

fun resolveType(
    raw: String,
    qualified: Map<String, TypeInfo>,
    simple: Map<String, List<TypeInfo>>,
): TypeInfo? {
    return qualified[raw] ?: simple[raw.substringAfterLast('.')].orEmpty().firstOrNull()
}

fun clusterIdForPackage(packageName: String): String {
    if (packageName.isBlank()) return "cluster:default"
    val parts = packageName.split(".")
    return "cluster:" + parts.take(minOf(5, parts.size)).joinToString(".")
}

fun packageNameForMethod(method: MethodInfo, typeInfos: Map<String, TypeInfo>): String {
    val fromType = typeInfos[method.parentTypeId]?.packageName?.takeUnless { it.isBlank() }
    if (fromType != null) return fromType

    val parentType = method.parentQualifiedName.ifBlank { method.qualifiedName.substringBeforeLast('.', "") }
    val fromParent = parentType.substringBeforeLast('.', "").takeUnless { it.isBlank() }
    if (fromParent != null) return fromParent

    return method.qualifiedName
        .substringBeforeLast('.', "")
        .substringBeforeLast('.', "")
        .takeUnless { it.isBlank() }
        .orEmpty()
}

fun isAgentRelevantMethod(method: MethodInfo): Boolean =
    !isSyntheticLikeMethod(method) && method.simpleName != "<clinit>"

fun entrypointPriority(method: MethodInfo): Int {
    var score = 0
    if (method.parentQualifiedName.contains("Controller")) score += 80
    if (method.parentQualifiedName.contains("Service")) score += 50
    if (method.annotations.any { it in setOf("@GetMapping", "@PostMapping", "@PutMapping", "@DeleteMapping", "@PatchMapping", "@RequestMapping") }) {
        score += 80
    }
    if (method.simpleName.startsWith("process") || method.simpleName.startsWith("create") || method.simpleName.startsWith("update") || method.simpleName.startsWith("find")) {
        score += 20
    }
    if (method.parentQualifiedName.contains("Repository")) score -= 40
    if (method.simpleName.startsWith("get") || method.simpleName.startsWith("set")) score -= 30
    return score
}

fun isSyntheticLikeMethod(method: MethodInfo): Boolean =
    (method.simpleName.startsWith("<") && method.simpleName.contains(">")) || "<lambda>" in method.simpleName

fun methodMatchesContract(candidate: MethodInfo, target: MethodInfo): Boolean {
    if (candidate.id == target.id) return false
    if (candidate.simpleName != target.simpleName) return false
    if (candidate.parameterTypes != target.parameterTypes) return false
    if (candidate.returnType != target.returnType) return false
    return true
}

fun descendantTypeIds(typeId: String, snapshot: Snapshot): Set<String> {
    val result = mutableSetOf<String>()
    val queue = ArrayDeque<String>()
    queue.add(typeId)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        snapshot.edges
            .filter { it.to == current && (it.relationship == "extends" || it.relationship == "implements") }
            .forEach { edge ->
                if (result.add(edge.from)) {
                    queue.add(edge.from)
                }
            }
    }
    return result
}

fun ancestorTypeIds(typeId: String, snapshot: Snapshot): Set<String> {
    val result = mutableSetOf<String>()
    val queue = ArrayDeque<String>()
    queue.add(typeId)
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        snapshot.edges
            .filter { it.from == current && (it.relationship == "extends" || it.relationship == "implements") }
            .forEach { edge ->
                if (result.add(edge.to)) {
                    queue.add(edge.to)
                }
            }
    }
    return result
}

fun implementationRelationship(candidateTypeId: String, targetTypeId: String, snapshot: Snapshot): String {
    val directEdge = snapshot.edges.firstOrNull {
        it.from == candidateTypeId && it.to == targetTypeId &&
            (it.relationship == "implements" || it.relationship == "extends")
    }
    val targetIsInterface = typeLooksLikeInterface(targetTypeId, snapshot)
    return when (directEdge?.relationship) {
        "implements" -> "implements"
        "extends" -> if (targetIsInterface) "implements" else "overrides"
        null -> if (targetIsInterface) "implements" else "overrides"
        else -> "overrides"
    }
}

fun typeLooksLikeInterface(typeId: String, snapshot: Snapshot): Boolean {
    val type = snapshot.typeInfos[typeId] ?: return false
    if (type.kind == "interface") return true
    val path = snapshot.sourceIndex[type.file] ?: return false
    return runCatching {
        val source = Files.readString(path)
        Regex("""\binterface\s+${Regex.escape(type.simpleName)}\b""").containsMatchIn(source)
    }.getOrDefault(false)
}

fun methodParentLooksLikeInterface(method: MethodInfo, snapshot: Snapshot): Boolean {
    if (typeLooksLikeInterface(method.parentTypeId, snapshot)) return true
    val parentQualifiedName = method.parentQualifiedName.ifBlank { method.qualifiedName.substringBeforeLast('.', "") }
    return declarationLooksLikeInterface(parentQualifiedName, method.file, snapshot)
}

fun declarationLooksLikeInterface(parentQualifiedName: String, file: String?, snapshot: Snapshot): Boolean {
    val actualFile = file ?: return false
    val path = snapshot.sourceIndex[actualFile] ?: return false
    val parentSimpleName = parentQualifiedName.substringAfterLast('.')
    return runCatching {
        val source = Files.readString(path)
        Regex("""\binterface\s+${Regex.escape(parentSimpleName)}\b""").containsMatchIn(source)
    }.getOrDefault(false)
}

fun impactNeighbors(nodeId: String, snapshot: Snapshot, basis: MutableSet<String>): Set<String> {
    val neighbors = linkedSetOf<String>()
    snapshot.edges.filter { it.relationship == "calls" && it.to == nodeId }.forEach { edge ->
        neighbors += edge.from
        basis += "incoming_calls"
    }
    snapshot.edges.filter { it.relationship == "uses_type" && it.to == nodeId }.forEach { edge ->
        neighbors += edge.from
        basis += "incoming_type_usage"
    }
    snapshot.edges.filter { (it.relationship == "extends" || it.relationship == "implements") && it.to == nodeId }.forEach { edge ->
        neighbors += edge.from
        basis += "type_descendants"
    }
    if (nodeId in snapshot.methodInfos) {
        neighbors += implementationNeighborIds(nodeId, snapshot)
        if (neighbors.any { it in snapshot.methodInfos }) {
            basis += "method_implementations"
        }
    }
    return neighbors
}

fun implementationNeighborIds(nodeId: String, snapshot: Snapshot): Set<String> {
    val target = snapshot.methodInfos[nodeId] ?: return emptySet()
    val relatedTypeIds = buildSet {
        add(target.parentTypeId)
        addAll(descendantTypeIds(target.parentTypeId, snapshot))
        addAll(ancestorTypeIds(target.parentTypeId, snapshot))
    }
    return snapshot.methodInfos.values
        .asSequence()
        .filter { methodMatchesContract(it, target) }
        .filter { it.parentTypeId in relatedTypeIds }
        .filter { isAgentRelevantMethod(it) }
        .map { it.id }
        .toSet()
}

fun clusterKeyNodeAllowed(node: NodeSummary, methodInfos: Map<String, MethodInfo>): Boolean {
    if (node.kind != "method") return true
    val method = methodInfos[node.id] ?: return true
    return isAgentRelevantMethod(method)
}

fun clusterNodeScore(node: NodeSummary, edges: List<EdgeSummary>): Int {
    val inboundCalls = edges.count { it.relationship == "calls" && it.to == node.id }
    val inboundTypeUsage = edges.count { it.relationship == "uses_type" && it.to == node.id }
    return (node.complexity ?: 0) * 10 + (node.loc ?: 0) + inboundCalls * 5 + inboundTypeUsage * 3
}

fun normalizedCallPath(nodes: List<NodeSummary>, edges: List<EdgeSummary>, snapshot: Snapshot): CallPath {
    var trimmedNodes = nodes
    var trimmedEdges = edges
    while (trimmedNodes.size > 1) {
        val last = trimmedNodes.last()
        val method = snapshot.methodInfos[last.id]
        if (method != null && isSyntheticLikeMethod(method)) {
            trimmedNodes = trimmedNodes.dropLast(1)
            trimmedEdges = trimmedEdges.dropLast(1)
            continue
        }
        break
    }
    return CallPath(trimmedNodes, trimmedEdges)
}

fun impactScore(node: NodeSummary, snapshot: Snapshot): Int {
    val inboundCalls = snapshot.edges.count { it.relationship == "calls" && it.to == node.id }
    val inboundTypeUsage = snapshot.edges.count { it.relationship == "uses_type" && it.to == node.id }
    val descendants = snapshot.edges.count { (it.relationship == "extends" || it.relationship == "implements") && it.to == node.id }
    return inboundCalls * 5 + inboundTypeUsage * 3 + descendants * 4 + (node.complexity ?: 0) * 2 + (node.loc ?: 0)
}

fun closingLine(source: String, openBraceIdx: Int): Int {
    var depth = 0
    for (i in openBraceIdx until source.length) {
        when (source[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) {
                    return source.substring(0, i).count { it == '\n' } + 1
                }
            }
        }
    }
    return source.count { it == '\n' } + 1
}

fun signatureOf(parameterTypes: List<String>, returnType: String?): String =
    "${parameterTypes.joinToString("|")}:${returnType.orEmpty()}"

fun extractAnnotations(block: String): List<String> =
    Regex("""@([A-Za-z_]\w*)""").findAll(block).map { "@${it.groupValues[1]}" }.toList()

fun extractVisibility(signature: String): String = when {
    "public" in signature -> "public"
    "protected" in signature -> "protected"
    "private" in signature -> "private"
    else -> "package"
}

fun computeComplexity(body: String): Int {
    val branches = listOf(" if ", " for ", " while ", " case ", " catch ", "&&", "||", "?:")
    return 1 + branches.sumOf { token -> Regex(Regex.escape(token.trim())).findAll(body).count() }
}

fun normalize(value: String): String = value.replace('\\', '/')

fun elapsedMs(start: Long): Long = (System.nanoTime() - start) / 1_000_000

fun refineJoernRanges(graph: JoernGraphData, projectRoot: Path): JoernGraphData {
    val fileLines = mutableMapOf<String, List<String>>()
    val fileSources = mutableMapOf<String, String>()

    fun load(file: String): Pair<List<String>, String>? {
        val path = projectRoot.resolve(file).normalize()
        if (!Files.isRegularFile(path)) return null
        val source = fileSources.getOrPut(file) { Files.readString(path) }
        val lines = fileLines.getOrPut(file) { source.lines() }
        return lines to source
    }

    val refinedTypes = graph.types.map { type ->
        val fileData = load(type.file) ?: return@map type
        val endLine = findBlockEndLine(fileData.second, type.lineRange.start)
        type.copy(lineRange = SourceRange(type.lineRange.start, endLine ?: type.lineRange.end))
    }

    val refinedMethods = graph.methods.map { method ->
        val fileData = load(method.file) ?: return@map method
        val endLine = findBlockEndLine(fileData.second, method.lineRange.start)
        val finalEnd = endLine ?: method.lineRange.end
        method.copy(
            lineRange = SourceRange(method.lineRange.start, finalEnd),
            loc = (finalEnd - method.lineRange.start + 1).coerceAtLeast(1),
        )
    }

    return graph.copy(types = refinedTypes, methods = refinedMethods)
}

fun findBlockEndLine(source: String, startLine: Int): Int? {
    if (startLine < 1) return null
    val startOffset = lineStartOffset(source, startLine)
    if (startOffset >= source.length) return null
    val openBrace = source.indexOf('{', startOffset).takeIf { it >= 0 } ?: return null
    return closingLineForSource(source, openBrace)
}

fun lineStartOffset(source: String, lineNumber: Int): Int {
    if (lineNumber <= 1) return 0
    var line = 1
    var index = 0
    while (index < source.length && line < lineNumber) {
        if (source[index] == '\n') {
            line++
        }
        index++
    }
    return index
}

fun closingLineForSource(source: String, openBraceIdx: Int): Int {
    var depth = 0
    for (i in openBraceIdx until source.length) {
        when (source[i]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) {
                    return source.substring(0, i).count { it == '\n' } + 1
                }
            }
        }
    }
    return source.count { it == '\n' } + 1
}

fun sourceSlice(fileSource: String, lineRange: SourceRange): String {
    val lines = fileSource.lines()
    val start = lineRange.start.coerceAtLeast(1)
    val end = lineRange.end.coerceAtMost(lines.size)
    return lines.subList(start - 1, end).joinToString("\n")
}

fun extractEditTaskTerms(task: String): List<String> {
    val quoted = Regex("""["']([A-Za-z_]\w*)["']""").findAll(task).map { it.groupValues[1].lowercase() }.toList()
    val camel = Regex("""\b[A-Za-z_]\w*\b""").findAll(task)
        .map { it.value }
        .filter { token ->
            token.any(Char::isUpperCase) ||
                token in setOf("rename", "insert", "replace", "save", "find", "process", "validate", "charge")
        }
        .map { it.lowercase() }
        .toList()
    return (quoted + camel).distinct().filter { it.length >= 3 }
}

fun inferRenamePayload(task: String, method: MethodInfo): Map<String, String> {
    val explicit = Regex("""rename(?:\s+method)?\s+\w+\s+to\s+([A-Za-z_]\w*)""").find(task)?.groupValues?.get(1)
        ?: Regex("""to\s+["']?([A-Za-z_]\w*)["']?""").find(task)?.groupValues?.get(1)
    return listOfNotNull(explicit?.let { "new_name" to it }).toMap()
}

fun inferMethodPatchPayload(task: String): Map<String, String> {
    val payload = linkedMapOf<String, String>()
    when {
        Regex("""insert\b[\s\S]*\bbefore\b""", RegexOption.IGNORE_CASE).containsMatchIn(task) -> payload["patch_mode"] = "insert_before"
        Regex("""insert\b[\s\S]*\bafter\b""", RegexOption.IGNORE_CASE).containsMatchIn(task) -> payload["patch_mode"] = "insert_after"
        "replace line" in task || "replace statement" in task -> payload["patch_mode"] = "replace_line"
    }
    payloadAnchor(task)?.let { payload["anchor"] = it }
    payloadSnippet(task)?.let { payload["snippet"] = it }
    return payload
}

fun candidateConfidence(score: Int, rationaleParts: List<String>): Double {
    val rationaleBoost = rationaleParts.size.coerceAtMost(3) * 0.08
    return ((score.coerceAtMost(320) / 320.0) + rationaleBoost).coerceIn(0.15, 0.99)
}

fun needsDisambiguation(candidates: List<EditCandidate>): Boolean {
    if (candidates.isEmpty()) return true
    if (candidates.first().confidence < 0.65) return true
    if (candidates.size > 1 && (candidates.first().score - candidates[1].score) < 35) return true
    return false
}

fun payloadAnchor(task: String): String? =
    Regex("""before\s+["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(task)?.groupValues?.get(1)
        ?: Regex("""after\s+["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(task)?.groupValues?.get(1)

fun payloadSnippet(task: String): String? =
    Regex("""insert\s+["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(task)?.groupValues?.get(1)
        ?: Regex("""replace(?:\s+line|\s+statement)?\s+["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(task)?.groupValues?.get(1)
