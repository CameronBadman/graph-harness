package graphharness

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.math.min
import kotlin.streams.asSequence

data class TypeInfo(
    val id: String,
    val kind: String,
    val packageName: String,
    val simpleName: String,
    val qualifiedName: String,
    val file: String,
    val lineRange: SourceRange,
    val visibility: String,
    val annotations: List<String>,
    val extendsType: String?,
    val implementsTypes: List<String>,
)

data class MethodInfo(
    val id: String,
    val parentTypeId: String,
    val parentQualifiedName: String,
    val simpleName: String,
    val qualifiedName: String,
    val signature: String,
    val file: String,
    val lineRange: SourceRange,
    val visibility: String,
    val annotations: List<String>,
    val complexity: Int,
    val loc: Int,
    val returnType: String?,
    val parameterTypes: List<String>,
    val body: String,
    val callTokens: List<Pair<String, Int>>,
    val typeRefs: Set<String>,
)

data class FileParseResult(
    val types: List<TypeInfo>,
    val methods: List<MethodInfo>,
)

private data class MethodImplementationMatch(
    val node: NodeSummary,
    val relationship: String,
)

data class Snapshot(
    val id: String,
    val generatedAt: String,
    val root: Path,
    val analysisEngine: String,
    val engineVersion: String?,
    val buildDurationMs: Long,
    val nodeSummaries: Map<String, NodeSummary>,
    val edges: List<EdgeSummary>,
    val clusters: List<ClusterSummary>,
    val clusterNodes: Map<String, List<String>>,
    val typeInfos: Map<String, TypeInfo>,
    val methodInfos: Map<String, MethodInfo>,
    val sourceIndex: Map<String, Path>,
    val packageCount: Int,
)

class SnapshotManager(private val projectRoot: Path) {
    private val activeSnapshot = AtomicReference(buildSnapshot())
    private val rebuildExecutor = Executors.newSingleThreadScheduledExecutor()

    init {
        startWatcher()
    }

    fun current(): Snapshot = activeSnapshot.get()

    private fun startWatcher() {
        val root = projectRoot
        if (!Files.isDirectory(root)) {
            return
        }

        val watchService = FileSystems.getDefault().newWatchService()
        Files.walk(root).use { paths ->
            paths.filter { Files.isDirectory(it) }
                .forEach {
                    runCatching {
                        it.register(
                            watchService,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_DELETE,
                            StandardWatchEventKinds.ENTRY_MODIFY,
                        )
                    }
                }
        }

        val watcherThread = Thread {
            while (!Thread.currentThread().isInterrupted) {
                val key = runCatching { watchService.take() }.getOrNull() ?: break
                var touchedJava = false
                key.pollEvents().forEach { event ->
                    val ctx = event.context()?.toString() ?: return@forEach
                    if (ctx.endsWith(".java")) {
                        touchedJava = true
                    }
                }
                key.reset()
                if (touchedJava) {
                    rebuildExecutor.schedule({
                        runCatching { activeSnapshot.set(buildSnapshot()) }
                    }, 800, TimeUnit.MILLISECONDS)
                }
            }
        }
        watcherThread.isDaemon = true
        watcherThread.name = "graphharness-watcher"
        watcherThread.start()
    }

    private fun buildSnapshot(): Snapshot {
        val buildStart = System.nanoTime()
        val javaFiles = Files.walk(projectRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension == "java" }
                .asSequence()
                .toList()
        }

        val joernSnapshot = detectJoernInstallation()?.let { joern ->
            runCatching { buildSnapshotWithJoern(javaFiles, joern, elapsedMs(buildStart)) }.getOrNull()
        }
        if (joernSnapshot != null) {
            return joernSnapshot
        }

        return buildSnapshotLegacy(javaFiles, elapsedMs(buildStart))
    }

    private fun buildSnapshotLegacy(javaFiles: List<Path>, buildDurationMs: Long): Snapshot {
        val parsedFiles = javaFiles.map { path -> path to parseJavaFile(path, projectRoot) }.toMap()
        val typeInfos = parsedFiles.values.flatMap { it.types }.associateBy { it.id }
        val methodInfos = parsedFiles.values.flatMap { it.methods }.associateBy { it.id }

        val typesBySimpleName = typeInfos.values.groupBy { it.simpleName }
        val methodsBySimpleName = methodInfos.values.groupBy { it.simpleName }
        val typeByQualifiedName = typeInfos.values.associateBy { it.qualifiedName }

        val nodeSummaries = buildNodeSummaries(typeInfos, methodInfos)
        val callEdges = buildCallEdges(methodInfos, methodsBySimpleName)
        val typeEdges = buildTypeEdges(typeInfos, typeByQualifiedName, typesBySimpleName)
        val dependencyEdges = buildDependencyEdges(methodInfos, typeInfos, typesBySimpleName)
        val edges = (callEdges + typeEdges + dependencyEdges).distinct()
        val clusters = buildClusters(typeInfos, methodInfos, edges, nodeSummaries)
        val sourceIndex = javaFiles.associateBy { normalize(projectRoot.relativize(it).invariantSeparatorsPathString) }
        val generatedAt = Instant.now().toString()

        val hash = MessageDigest.getInstance("SHA-256")
            .digest(
                buildString {
                    append(generatedAt)
                    javaFiles.sortedBy { it.toString() }.forEach { append(it.toString()) }
                }.toByteArray()
            )
            .joinToString("") { "%02x".format(it) }

        return Snapshot(
            id = hash.take(16),
            generatedAt = generatedAt,
            root = projectRoot,
            analysisEngine = "fallback-parser",
            engineVersion = null,
            buildDurationMs = buildDurationMs,
            nodeSummaries = nodeSummaries,
            edges = edges,
            clusters = clusters,
            clusterNodes = buildClusterNodes(clusters, typeInfos, methodInfos),
            typeInfos = typeInfos,
            methodInfos = methodInfos,
            sourceIndex = sourceIndex,
            packageCount = typeInfos.values.map { it.packageName }.filter { it.isNotBlank() }.toSet().size,
        )
    }

    private fun buildSnapshotWithJoern(javaFiles: List<Path>, joern: JoernInstallation, buildDurationMs: Long): Snapshot {
        val graph = joern.exportGraph(projectRoot)
        val typeInfos = graph.types.associateBy { it.id }
        val methodInfos = graph.methods.associateBy { it.id }
        val nodeSummaries = buildNodeSummaries(typeInfos, methodInfos)
        val graphEdges = (graph.callEdges + graph.typeEdges + graph.dependencyEdges).distinct()
        val clusters = buildClusters(typeInfos, methodInfos, graphEdges, nodeSummaries)
        val sourceIndex = javaFiles.associateBy { normalize(projectRoot.relativize(it).invariantSeparatorsPathString) }
        val generatedAt = Instant.now().toString()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(
                buildString {
                    append("joern")
                    append(generatedAt)
                    javaFiles.sortedBy { it.toString() }.forEach { append(it.toString()) }
                }.toByteArray()
            )
            .joinToString("") { "%02x".format(it) }

        return Snapshot(
            id = hash.take(16),
            generatedAt = generatedAt,
            root = projectRoot,
            analysisEngine = "joern",
            engineVersion = joern.version,
            buildDurationMs = buildDurationMs,
            nodeSummaries = nodeSummaries,
            edges = graphEdges,
            clusters = clusters,
            clusterNodes = buildClusterNodes(clusters, typeInfos, methodInfos),
            typeInfos = typeInfos,
            methodInfos = methodInfos,
            sourceIndex = sourceIndex,
            packageCount = typeInfos.values.map { it.packageName }.filter { it.isNotBlank() }.toSet().size,
        )
    }

    private fun buildNodeSummaries(
        typeInfos: Map<String, TypeInfo>,
        methodInfos: Map<String, MethodInfo>,
    ): Map<String, NodeSummary> {
        val result = linkedMapOf<String, NodeSummary>()
        typeInfos.values.forEach { type ->
            result[type.id] = NodeSummary(
                id = type.id,
                kind = type.kind,
                name = type.qualifiedName,
                file = type.file,
                line_range = type.lineRange,
                visibility = type.visibility,
                annotations = type.annotations,
            )
        }
        methodInfos.values.forEach { method ->
            result[method.id] = NodeSummary(
                id = method.id,
                kind = "method",
                name = method.qualifiedName,
                signature = method.signature,
                file = method.file,
                line_range = method.lineRange,
                visibility = method.visibility,
                annotations = method.annotations,
                complexity = method.complexity,
                loc = method.loc,
            )
        }
        return result
    }

    private fun buildCallEdges(
        methodInfos: Map<String, MethodInfo>,
        methodsBySimpleName: Map<String, List<MethodInfo>>,
    ): List<EdgeSummary> {
        val edges = mutableListOf<EdgeSummary>()
        methodInfos.values.forEach { method ->
            method.callTokens.forEach { (token, line) ->
                methodsBySimpleName[token]
                    ?.filter { it.id != method.id }
                    ?.take(5)
                    ?.forEach { target ->
                        edges += EdgeSummary(
                            from = method.id,
                            to = target.id,
                            relationship = "calls",
                            file = method.file,
                            line = line,
                        )
                    }
            }
        }
        return edges
    }

    private fun buildTypeEdges(
        typeInfos: Map<String, TypeInfo>,
        typeByQualifiedName: Map<String, TypeInfo>,
        typesBySimpleName: Map<String, List<TypeInfo>>,
    ): List<EdgeSummary> {
        val edges = mutableListOf<EdgeSummary>()
        typeInfos.values.forEach { type ->
            listOfNotNull(type.extendsType).forEach { targetName ->
                resolveType(targetName, typeByQualifiedName, typesBySimpleName)?.let { target ->
                    edges += EdgeSummary(type.id, target.id, "extends")
                }
            }
            type.implementsTypes.forEach { targetName ->
                resolveType(targetName, typeByQualifiedName, typesBySimpleName)?.let { target ->
                    edges += EdgeSummary(type.id, target.id, "implements")
                }
            }
        }
        return edges
    }

    private fun buildDependencyEdges(
        methodInfos: Map<String, MethodInfo>,
        typeInfos: Map<String, TypeInfo>,
        typesBySimpleName: Map<String, List<TypeInfo>>,
    ): List<EdgeSummary> {
        val typeByQualifiedName = typeInfos.values.associateBy { it.qualifiedName }
        val edges = mutableListOf<EdgeSummary>()
        methodInfos.values.forEach { method ->
            method.typeRefs.forEach { ref ->
                resolveType(ref, typeByQualifiedName, typesBySimpleName)?.let { target ->
                    edges += EdgeSummary(method.id, target.id, "uses_type")
                }
            }
        }
        return edges
    }

    private fun buildClusters(
        typeInfos: Map<String, TypeInfo>,
        methodInfos: Map<String, MethodInfo>,
        edges: List<EdgeSummary>,
        nodeSummaries: Map<String, NodeSummary>,
    ): List<ClusterSummary> {
        val groupedNodeIds = mutableMapOf<String, MutableList<String>>()
        typeInfos.values.forEach { type ->
            val clusterId = clusterIdForPackage(type.packageName)
            groupedNodeIds.getOrPut(clusterId) { mutableListOf() }.add(type.id)
        }
        methodInfos.values.forEach { method ->
            val pkg = packageNameForMethod(method, typeInfos)
            val clusterId = clusterIdForPackage(pkg)
            groupedNodeIds.getOrPut(clusterId) { mutableListOf() }.add(method.id)
        }

        return groupedNodeIds.entries.sortedBy { it.key }.map { (clusterId, ids) ->
            val clusterSet = ids.toSet()
            val internalEdges = edges.filter { it.from in clusterSet && it.to in clusterSet }
            val externalEdges = edges.filter { (it.from in clusterSet) xor (it.to in clusterSet) }
            val bridgeNodes = externalEdges.flatMap { listOf(it.from, it.to) }.filter { it in clusterSet }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(5).map { it.key }
            val keyNodes = ids.mapNotNull { nodeSummaries[it] }
                .filter { clusterKeyNodeAllowed(it, methodInfos) }
                .sortedByDescending { clusterNodeScore(it, edges) }
                .take(5)
                .map { it.id }
            ClusterSummary(
                cluster_id = clusterId,
                label = clusterId.removePrefix("cluster:"),
                description = "Package-oriented subsystem rooted at ${clusterId.removePrefix("cluster:")}.",
                node_count = ids.size,
                key_nodes = keyNodes,
                external_edges = externalEdges.size,
                bridge_nodes = bridgeNodes,
            )
        }
    }

    private fun buildClusterNodes(
        clusters: List<ClusterSummary>,
        typeInfos: Map<String, TypeInfo>,
        methodInfos: Map<String, MethodInfo>,
    ): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        typeInfos.values.forEach { type ->
            result.getOrPut(clusterIdForPackage(type.packageName)) { mutableListOf() }.add(type.id)
        }
        methodInfos.values.forEach { method ->
            val packageName = packageNameForMethod(method, typeInfos)
            result.getOrPut(clusterIdForPackage(packageName)) { mutableListOf() }.add(method.id)
        }
        return clusters.associate { it.cluster_id to result.getOrDefault(it.cluster_id, mutableListOf()).sorted() }
    }

    fun summaryMap(snapshot: Snapshot = current()): SummaryMapResult {
        val summaryMode = when {
            snapshot.sourceIndex.size <= 10 && snapshot.methodInfos.size <= 20 -> "compact"
            snapshot.sourceIndex.size <= 120 && snapshot.methodInfos.size <= 400 -> "standard"
            else -> "expanded"
        }
        val bridgeNodes = snapshot.clusters.flatMap { it.bridge_nodes }.distinct()
            .mapNotNull { snapshot.nodeSummaries[it]?.toOrientationNode() }
            .take(if (summaryMode == "expanded") 6 else 0)
        val callCounts = snapshot.edges.filter { it.relationship == "calls" }
            .groupingBy { it.from }
            .eachCount()
        val inboundCallCounts = snapshot.edges.filter { it.relationship == "calls" }
            .groupingBy { it.to }
            .eachCount()
        val entrypointLimit = when (summaryMode) {
            "compact" -> 4
            "standard" -> 4
            else -> 8
        }
        val entrypoints = snapshot.methodInfos.values
            .asSequence()
            .filter { it.visibility == "public" && isAgentRelevantMethod(it) }
            .sortedWith(
                compareByDescending<MethodInfo> { entrypointPriority(it) }
                    .thenByDescending { inboundCallCounts.getOrDefault(it.id, 0) }
                    .thenByDescending { callCounts.getOrDefault(it.id, 0) }
                    .thenByDescending { it.loc }
                    .thenBy { it.qualifiedName },
            )
            .take(entrypointLimit)
            .map { it.toOrientationNode() }
            .toList()
        val hotspotLimit = when (summaryMode) {
            "compact" -> 4
            "standard" -> 0
            else -> 8
        }
        val hotspots = snapshot.methodInfos.values
            .asSequence()
            .filter { isAgentRelevantMethod(it) }
            .sortedWith(
                compareByDescending<MethodInfo> { it.complexity * 10 + it.loc + inboundCallCounts.getOrDefault(it.id, 0) * 5 }
                    .thenBy { it.qualifiedName },
            )
            .take(hotspotLimit)
            .map { method ->
                HotspotSummary(
                    node = method.toOrientationNode(),
                    score = method.complexity * 10 + method.loc + inboundCallCounts.getOrDefault(method.id, 0) * 5,
                )
            }
            .toList()
        val clusters = summarizedClusters(snapshot, summaryMode)

        return SummaryMapResult(
            project = ProjectSummary(
                root = snapshot.root.toAbsolutePath().normalize().toString(),
                total_files = snapshot.sourceIndex.size,
                total_packages = snapshot.packageCount,
                total_types = snapshot.typeInfos.size,
                total_methods = snapshot.methodInfos.size,
                analysis_engine = snapshot.analysisEngine,
                engine_version = snapshot.engineVersion,
                build_duration_ms = snapshot.buildDurationMs,
                snapshot_id = snapshot.id,
                generated_at = snapshot.generatedAt,
            ),
            clusters = clusters,
            bridge_nodes = bridgeNodes,
            entrypoints = entrypoints,
            hotspots = hotspots,
            summary_mode = summaryMode,
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
        )
    }

    private fun summarizedClusters(snapshot: Snapshot, summaryMode: String): List<ClusterSummary> {
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

    private fun NodeSummary.toOrientationNode(): OrientationNode =
        OrientationNode(
            id = id,
            kind = kind,
            name = name,
            file = file,
        )

    private fun MethodInfo.toOrientationNode(): OrientationNode =
        OrientationNode(
            id = id,
            kind = "method",
            name = qualifiedName,
            file = file,
        )

    fun clusterDetail(clusterId: String, snapshot: Snapshot = current()): ClusterDetailResult {
        val cluster = snapshot.clusters.firstOrNull { it.cluster_id == clusterId }
            ?: error("Unknown cluster_id: $clusterId")
        val nodes = snapshot.clusterNodes[clusterId].orEmpty().mapNotNull { snapshot.nodeSummaries[it] }
        val nodeSet = nodes.map { it.id }.toSet()
        return ClusterDetailResult(
            cluster = cluster,
            nodes = nodes.sortedBy { it.name },
            internal_edges = snapshot.edges.filter { it.from in nodeSet && it.to in nodeSet },
            external_edges = snapshot.edges.filter { (it.from in nodeSet) xor (it.to in nodeSet) },
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
        )
    }

    fun search(
        query: String,
        kind: String?,
        annotation: String?,
        clusterId: String?,
        snapshot: Snapshot = current(),
    ): SearchResult {
        val allowed = clusterId?.let { snapshot.clusterNodes[it].orEmpty().toSet() }
        val results = snapshot.nodeSummaries.values.filter { node ->
            val clusterMatch = allowed == null || node.id in allowed
            val queryMatch = query.isBlank() || node.name.contains(query, ignoreCase = true)
            val kindMatch = kind == null || node.kind == kind
            val annotationMatch = annotation == null || node.annotations.any { it.contains(annotation, ignoreCase = true) }
            clusterMatch && queryMatch && kindMatch && annotationMatch
        }.sortedBy { it.name }
        return SearchResult(results, snapshot.analysisEngine, snapshot.engineVersion, snapshot.buildDurationMs, snapshot.id, snapshot.generatedAt)
    }

    fun nodeDetail(nodeId: String, snapshot: Snapshot = current()): NodeDetailResult {
        val start = System.nanoTime()
        val node = snapshot.nodeSummaries[nodeId] ?: error("Unknown node_id: $nodeId")
        val cluster = snapshot.clusters.firstOrNull { cluster ->
            nodeId in snapshot.clusterNodes[cluster.cluster_id].orEmpty()
        }

        val incomingDependencies = snapshot.edges.filter { it.to == nodeId }
            .mapNotNull { edge ->
                snapshot.nodeSummaries[edge.from]?.let { DependencyResultItem(it, edge.relationship) }
            }
            .distinctBy { "${it.node.id}:${it.relationship}" }
            .sortedBy { it.node.name }

        val outgoingDependencies = snapshot.edges.filter { it.from == nodeId }
            .mapNotNull { edge ->
                snapshot.nodeSummaries[edge.to]?.let { DependencyResultItem(it, edge.relationship) }
            }
            .distinctBy { "${it.node.id}:${it.relationship}" }
            .sortedBy { it.node.name }

        val callers = snapshot.edges.filter { it.relationship == "calls" && it.to == nodeId }
            .mapNotNull { edge ->
                snapshot.nodeSummaries[edge.from]?.let {
                    NodeWithCallSite(it, if (edge.file != null && edge.line != null) CallSite(edge.file, edge.line) else null)
                }
            }
            .sortedBy { it.node.name }

        val callees = snapshot.edges.filter { it.relationship == "calls" && it.from == nodeId }
            .mapNotNull { edge ->
                snapshot.nodeSummaries[edge.to]?.let {
                    NodeWithCallSite(it, if (edge.file != null && edge.line != null) CallSite(edge.file, edge.line) else null)
                }
            }
            .sortedBy { it.node.name }

        val implementations = if (node.kind == "method") {
            resolvedImplementationMatches(nodeId, snapshot)
        } else {
            emptyList()
        }

        val ancestors = if (nodeId in snapshot.typeInfos) {
            snapshot.edges.filter { it.from == nodeId && (it.relationship == "extends" || it.relationship == "implements") }
                .mapNotNull { snapshot.nodeSummaries[it.to] }
                .sortedBy { it.name }
        } else {
            emptyList()
        }

        val descendants = if (nodeId in snapshot.typeInfos) {
            snapshot.edges.filter { it.to == nodeId && (it.relationship == "extends" || it.relationship == "implements") }
                .mapNotNull { snapshot.nodeSummaries[it.from] }
                .sortedBy { it.name }
        } else {
            emptyList()
        }

        return NodeDetailResult(
            node = node,
            cluster = cluster,
            incoming_dependencies = incomingDependencies,
            outgoing_dependencies = outgoingDependencies,
            callers = callers,
            callees = callees,
            implementations = implementations.map { it.node },
            implementation_relationships = implementations.associate { it.node.id to it.relationship },
            ancestors = ancestors,
            descendants = descendants,
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
            analysis_confidence = 0.68,
            known_blind_spots = DEFAULT_BLIND_SPOTS,
            latency_ms = elapsedMs(start),
        )
    }

    fun callPaths(
        nodeId: String,
        maxDepth: Int,
        targetNodeId: String? = null,
        snapshot: Snapshot = current(),
    ): CallPathsResult {
        val start = System.nanoTime()
        require(nodeId in snapshot.nodeSummaries) { "Unknown node_id: $nodeId" }
        if (targetNodeId != null) {
            require(targetNodeId in snapshot.nodeSummaries) { "Unknown target_node_id: $targetNodeId" }
        }

        val limit = maxDepth.coerceIn(1, 8)
        val results = mutableListOf<CallPath>()
        val seen = mutableSetOf<String>()

        fun dfs(current: String, pathNodes: List<String>, pathEdges: List<EdgeSummary>, depth: Int) {
            if (depth > limit) return
            if (targetNodeId != null && current == targetNodeId && pathEdges.isNotEmpty()) {
                results += normalizedCallPath(
                    nodes = pathNodes.mapNotNull { snapshot.nodeSummaries[it] },
                    edges = pathEdges,
                    snapshot = snapshot,
                )
                return
            }

            val outgoing = snapshot.edges.filter { it.relationship == "calls" && it.from == current }
            if (outgoing.isEmpty() && pathEdges.isNotEmpty() && targetNodeId == null) {
                results += normalizedCallPath(
                    nodes = pathNodes.mapNotNull { snapshot.nodeSummaries[it] },
                    edges = pathEdges,
                    snapshot = snapshot,
                )
                return
            }

            if (depth == limit) {
                if (pathEdges.isNotEmpty()) {
                    results += normalizedCallPath(
                        nodes = pathNodes.mapNotNull { snapshot.nodeSummaries[it] },
                        edges = pathEdges,
                        snapshot = snapshot,
                    )
                }
                return
            }

            outgoing.forEach { edge ->
                if (edge.to in pathNodes) return@forEach
                dfs(
                    current = edge.to,
                    pathNodes = pathNodes + edge.to,
                    pathEdges = pathEdges + edge,
                    depth = depth + 1,
                )
            }
        }

        dfs(nodeId, listOf(nodeId), emptyList(), 0)

        val deduped = results
            .filter { it.nodes.size > 1 && it.edges.isNotEmpty() }
            .filter { path ->
                val key = path.nodes.joinToString("->") { it.id }
                seen.add(key)
            }
            .sortedBy { path -> path.nodes.joinToString("->") { it.name } }

        return CallPathsResult(
            paths = deduped,
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
            analysis_confidence = 0.64,
            known_blind_spots = DEFAULT_BLIND_SPOTS,
            latency_ms = elapsedMs(start),
        )
    }

    fun callers(nodeId: String, depth: Int, snapshot: Snapshot = current()): TraversalResult =
        traverse(nodeId, depth, snapshot, incoming = true, relationship = "calls")

    fun callees(nodeId: String, depth: Int, snapshot: Snapshot = current()): TraversalResult =
        traverse(nodeId, depth, snapshot, incoming = false, relationship = "calls")

    private fun traverse(
        nodeId: String,
        depth: Int,
        snapshot: Snapshot,
        incoming: Boolean,
        relationship: String,
    ): TraversalResult {
        val start = System.nanoTime()
        val maxDepth = depth.coerceIn(1, 5)
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        val results = mutableListOf<NodeWithCallSite>()
        queue.add(nodeId to 0)
        visited += nodeId

        while (queue.isNotEmpty()) {
            val (current, currentDepth) = queue.removeFirst()
            if (currentDepth >= maxDepth) continue
            val neighbors = snapshot.edges.filter {
                it.relationship == relationship &&
                    if (incoming) it.to == current else it.from == current
            }
            neighbors.forEach { edge ->
                val nextId = if (incoming) edge.from else edge.to
                if (visited.add(nextId)) {
                    snapshot.nodeSummaries[nextId]?.let { node ->
                        results += NodeWithCallSite(
                            node = node,
                            call_site = if (edge.file != null && edge.line != null) CallSite(edge.file, edge.line) else null,
                        )
                        queue.add(nextId to (currentDepth + 1))
                    }
                }
            }
        }

        return TraversalResult(
            items = results.sortedBy { it.node.name },
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
            analysis_confidence = 0.62,
            known_blind_spots = DEFAULT_BLIND_SPOTS,
            latency_ms = elapsedMs(start),
        )
    }

    fun implementations(nodeId: String, snapshot: Snapshot = current()): TraversalResult {
        val start = System.nanoTime()
        require(nodeId in snapshot.nodeSummaries) { "Unknown node_id: $nodeId" }
        val results = resolvedImplementationMatches(nodeId, snapshot).map {
            NodeWithCallSite(node = it.node, relationship = it.relationship)
        }
        return TraversalResult(
            items = results.sortedBy { it.node.name },
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
            analysis_confidence = 0.72,
            known_blind_spots = DEFAULT_BLIND_SPOTS,
            latency_ms = elapsedMs(start),
        )
    }

    fun typeHierarchy(nodeId: String, direction: String, snapshot: Snapshot = current()): TypeHierarchyResult {
        val type = snapshot.typeInfos[nodeId] ?: error("Type hierarchy requires a class or interface node_id")
        val ancestors = mutableListOf<NodeSummary>()
        val descendants = mutableListOf<NodeSummary>()
        if (direction == "up" || direction == "both") {
            snapshot.edges.filter { it.from == type.id && (it.relationship == "extends" || it.relationship == "implements") }
                .forEach { edge -> snapshot.nodeSummaries[edge.to]?.let { ancestors += it } }
        }
        if (direction == "down" || direction == "both") {
            snapshot.edges.filter { it.to == type.id && (it.relationship == "extends" || it.relationship == "implements") }
                .forEach { edge -> snapshot.nodeSummaries[edge.from]?.let { descendants += it } }
        }
        return TypeHierarchyResult(
            ancestors = ancestors.sortedBy { it.name },
            descendants = descendants.sortedBy { it.name },
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
            analysis_confidence = 0.7,
            known_blind_spots = DEFAULT_BLIND_SPOTS,
        )
    }

    fun dependencies(nodeId: String, direction: String, snapshot: Snapshot = current()): DependencyResult {
        val start = System.nanoTime()
        val edges = snapshot.edges.filter {
            when (direction) {
                "incoming" -> it.to == nodeId
                "outgoing" -> it.from == nodeId
                else -> it.from == nodeId || it.to == nodeId
            }
        }
        val deps = edges.mapNotNull { edge ->
            val target = if (edge.from == nodeId) edge.to else edge.from
            snapshot.nodeSummaries[target]?.let { DependencyResultItem(it, edge.relationship) }
        }.distinctBy { "${it.node.id}:${it.relationship}" }

        return DependencyResult(
            dependencies = deps.sortedBy { it.node.name },
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
            analysis_confidence = 0.66,
            known_blind_spots = DEFAULT_BLIND_SPOTS,
            latency_ms = elapsedMs(start),
        )
    }

    fun impact(nodeId: String, maxDepth: Int, snapshot: Snapshot = current()): ImpactResult {
        val start = System.nanoTime()
        val depth = maxDepth.coerceIn(1, 6)
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        val affected = mutableListOf<NodeSummary>()
        val basis = linkedSetOf<String>()
        queue.add(nodeId to 0)
        visited += nodeId

        while (queue.isNotEmpty()) {
            val (current, currentDepth) = queue.removeFirst()
            if (currentDepth >= depth) continue
            val neighbors = impactNeighbors(current, snapshot, basis)
            neighbors.forEach { next ->
                if (visited.add(next)) {
                    snapshot.nodeSummaries[next]?.let { affected += it }
                    queue.add(next to (currentDepth + 1))
                }
            }
        }

        val affectedFiles = affected.map { it.file }.distinct().sorted()
        val inboundCallers = snapshot.edges.count { it.relationship == "calls" && it.to == nodeId }
        val implementationCount = if (nodeId in snapshot.methodInfos) resolvedImplementationMatches(nodeId, snapshot).size else 0
        val typeDependents = snapshot.edges.count { (it.relationship == "extends" || it.relationship == "implements" || it.relationship == "uses_type") && it.to == nodeId }
        val riskScore = min(1.0, (affected.size + inboundCallers * 2 + implementationCount * 3 + typeDependents) / 25.0)
        return ImpactResult(
            affected_nodes = affected
                .distinctBy { it.id }
                .sortedWith(compareByDescending<NodeSummary> { impactScore(it, snapshot) }.thenBy { it.name }),
            affected_files = affectedFiles,
            risk_score = riskScore,
            analysis_confidence = 0.74,
            known_blind_spots = DEFAULT_BLIND_SPOTS,
            analysis_basis = basis.toList(),
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
            latency_ms = elapsedMs(start),
        )
    }

    fun source(nodeId: String, includeContext: Int, snapshot: Snapshot = current()): SourceResult {
        val node = snapshot.nodeSummaries[nodeId] ?: error("Unknown node_id: $nodeId")
        val path = snapshot.sourceIndex[node.file] ?: error("Missing file for node_id: $nodeId")
        val lines = Files.readAllLines(path)
        val startLine = (node.line_range.start - includeContext).coerceAtLeast(1)
        val endLine = (node.line_range.end + includeContext).coerceAtMost(lines.size)
        val source = lines.subList(startLine - 1, endLine).joinToString("\n")
        return SourceResult(
            source = source,
            file = node.file,
            line_range = SourceRange(startLine, endLine),
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
        )
    }

    fun sourceBatch(nodeIds: List<String>, snapshot: Snapshot = current()): SourceBatchResult {
        val items = nodeIds.map { nodeId ->
            val item = source(nodeId, 0, snapshot)
            SourceBatchItem(nodeId, item.source, item.file, item.line_range)
        }
        return SourceBatchResult(items, snapshot.analysisEngine, snapshot.engineVersion, snapshot.buildDurationMs, snapshot.id, snapshot.generatedAt)
    }

    private fun parseJavaFile(path: Path, root: Path): FileParseResult {
        val lines = Files.readAllLines(path)
        val source = lines.joinToString("\n")
        val file = normalize(root.relativize(path).invariantSeparatorsPathString)
        val packageName = Regex("""(?m)^\s*package\s+([a-zA-Z0-9_.]+)\s*;""")
            .find(source)?.groupValues?.get(1).orEmpty()

        val typePattern = Regex(
            """(?m)^(\s*(?:@\w+(?:\([^)]*\))?\s*)*)(?:public|protected|private)?\s*(abstract\s+)?(class|interface|enum)\s+([A-Za-z_]\w*)(?:\s+extends\s+([A-Za-z0-9_$.]+))?(?:\s+implements\s+([A-Za-z0-9_$.,\s]+))?""",
        )

        val methodPattern = Regex(
            """(?m)^(\s*(?:@\w+(?:\([^)]*\))?\s*)*)(\s*(?:public|protected|private))?\s*(?:static\s+|final\s+|abstract\s+|synchronized\s+)*([A-Za-z0-9_<>\[\].?]+)\s+([A-Za-z_]\w*)\s*\(([^)]*)\)\s*(?:throws\s+[A-Za-z0-9_.,\s]+)?\s*\{""",
        )

        val types = mutableListOf<TypeInfo>()
        val methods = mutableListOf<MethodInfo>()

        typePattern.findAll(source).forEach { match ->
            val typeStart = source.substring(0, match.range.first).count { it == '\n' } + 1
            val openBraceIdx = source.indexOf('{', match.range.last)
            val endLine = if (openBraceIdx >= 0) closingLine(source, openBraceIdx) else typeStart
            val annotations = extractAnnotations(match.groupValues[1])
            val kind = match.groupValues[3]
            val simpleName = match.groupValues[4]
            val qualifiedName = listOf(packageName, simpleName).filter { it.isNotBlank() }.joinToString(".")
            types += TypeInfo(
                id = "type:$qualifiedName",
                kind = kind,
                packageName = packageName,
                simpleName = simpleName,
                qualifiedName = qualifiedName,
                file = file,
                lineRange = SourceRange(typeStart, endLine),
                visibility = extractVisibility(match.value),
                annotations = annotations,
                extendsType = match.groupValues[5].ifBlank { null },
                implementsTypes = match.groupValues[6].split(",").map { it.trim() }.filter { it.isNotBlank() },
            )
        }

        val type = types.firstOrNull()
        methodPattern.findAll(source).forEach { match ->
            val methodStart = source.substring(0, match.range.first).count { it == '\n' } + 1
            val openBraceIdx = source.indexOf('{', match.range.last)
            if (openBraceIdx < 0) return@forEach
            val closeLine = closingLine(source, openBraceIdx)
            val body = source.lines().subList(methodStart - 1, closeLine).joinToString("\n")
            val annotations = extractAnnotations(match.groupValues[1])
            val visibility = extractVisibility(match.value)
            val returnType = match.groupValues[3]
            val name = match.groupValues[4]
            val parameters = match.groupValues[5]
            val parameterTypes = parameters.split(",").mapNotNull { param ->
                val parts = param.trim().split(Regex("""\s+"""))
                if (parts.size >= 2) parts.dropLast(1).joinToString(" ") else null
            }
            val parentType = type ?: return@forEach
            val qualifiedName = "${parentType.qualifiedName}.$name"
            val callTokens = callPattern.findAll(body).map { token ->
                val tokenLine = body.substring(0, token.range.first).count { it == '\n' } + methodStart
                token.groupValues[1] to tokenLine
            }.filterNot { it.first in JAVA_KEYWORDS || it.first == name }.toList()
            val typeRefs = (parameterTypes + listOf(returnType) + bodyTypePattern.findAll(body).map { it.groupValues[1] }.toList())
                .filter { it.isNotBlank() && it.first().isUpperCase() }
                .toSet()
            methods += MethodInfo(
                id = "method:$qualifiedName:${signatureOf(parameterTypes, returnType)}",
                parentTypeId = parentType.id,
                parentQualifiedName = parentType.qualifiedName,
                simpleName = name,
                qualifiedName = qualifiedName,
                signature = "(${parameterTypes.joinToString(", ")}) -> ${returnType.ifBlank { "void" }}",
                file = file,
                lineRange = SourceRange(methodStart, closeLine),
                visibility = visibility,
                annotations = annotations,
                complexity = computeComplexity(body),
                loc = closeLine - methodStart + 1,
                returnType = returnType,
                parameterTypes = parameterTypes,
                body = body,
                callTokens = callTokens,
                typeRefs = typeRefs,
            )
        }

        return FileParseResult(types, methods)
    }

    private fun resolveType(
        raw: String,
        qualified: Map<String, TypeInfo>,
        simple: Map<String, List<TypeInfo>>,
    ): TypeInfo? {
        return qualified[raw] ?: simple[raw.substringAfterLast('.')].orEmpty().firstOrNull()
    }

    private fun clusterIdForPackage(packageName: String): String {
        if (packageName.isBlank()) return "cluster:default"
        val parts = packageName.split(".")
        return "cluster:" + parts.take(minOf(5, parts.size)).joinToString(".")
    }

    private fun packageNameForMethod(method: MethodInfo, typeInfos: Map<String, TypeInfo>): String {
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

    private fun isAgentRelevantMethod(method: MethodInfo): Boolean =
        !isSyntheticLikeMethod(method) && method.simpleName != "<clinit>"

    private fun entrypointPriority(method: MethodInfo): Int {
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

    private fun isSyntheticLikeMethod(method: MethodInfo): Boolean =
        method.simpleName.startsWith("<") && method.simpleName.endsWith(">")

    private fun methodMatchesContract(candidate: MethodInfo, target: MethodInfo): Boolean {
        if (candidate.id == target.id) return false
        if (candidate.simpleName != target.simpleName) return false
        if (candidate.parameterTypes != target.parameterTypes) return false
        if (candidate.returnType != target.returnType) return false
        return true
    }

    private fun descendantTypeIds(typeId: String, snapshot: Snapshot): Set<String> {
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

    private fun ancestorTypeIds(typeId: String, snapshot: Snapshot): Set<String> {
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

    private fun resolvedImplementationMatches(nodeId: String, snapshot: Snapshot): List<MethodImplementationMatch> {
        val target = snapshot.methodInfos[nodeId] ?: return emptyList()
        val targetParentIsInterface =
            methodParentLooksLikeInterface(target, snapshot) ||
                declarationLooksLikeInterface(target.qualifiedName.substringBeforeLast('.', ""), snapshot.nodeSummaries[nodeId]?.file, snapshot)
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
            .mapNotNull { method ->
                snapshot.nodeSummaries[method.id]?.let { node ->
                    MethodImplementationMatch(
                        node = node,
                        relationship = if (targetParentIsInterface) {
                            "implements"
                        } else {
                            implementationRelationship(method.parentTypeId, target.parentTypeId, snapshot)
                        },
                    )
                }
            }
            .sortedBy { it.node.name }
            .toList()
    }

    private fun implementationRelationship(candidateTypeId: String, targetTypeId: String, snapshot: Snapshot): String {
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

    private fun typeLooksLikeInterface(typeId: String, snapshot: Snapshot): Boolean {
        val type = snapshot.typeInfos[typeId] ?: return false
        if (type.kind == "interface") return true
        val path = snapshot.sourceIndex[type.file] ?: return false
        return runCatching {
            val source = Files.readString(path)
            Regex("""\binterface\s+${Regex.escape(type.simpleName)}\b""").containsMatchIn(source)
        }.getOrDefault(false)
    }

    private fun methodParentLooksLikeInterface(method: MethodInfo, snapshot: Snapshot): Boolean {
        if (typeLooksLikeInterface(method.parentTypeId, snapshot)) return true
        val parentQualifiedName = method.parentQualifiedName.ifBlank { method.qualifiedName.substringBeforeLast('.', "") }
        return declarationLooksLikeInterface(parentQualifiedName, method.file, snapshot)
    }

    private fun declarationLooksLikeInterface(parentQualifiedName: String, file: String?, snapshot: Snapshot): Boolean {
        val actualFile = file ?: return false
        val path = snapshot.sourceIndex[actualFile] ?: return false
        val parentSimpleName = parentQualifiedName.substringAfterLast('.')
        return runCatching {
            val source = Files.readString(path)
            Regex("""\binterface\s+${Regex.escape(parentSimpleName)}\b""").containsMatchIn(source)
        }.getOrDefault(false)
    }

    private fun impactNeighbors(nodeId: String, snapshot: Snapshot, basis: MutableSet<String>): Set<String> {
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
            resolvedImplementationMatches(nodeId, snapshot).forEach {
                neighbors += it.node.id
            }
            if (neighbors.any { it in snapshot.methodInfos }) {
                basis += "method_implementations"
            }
        }
        return neighbors
    }

    private fun clusterKeyNodeAllowed(node: NodeSummary, methodInfos: Map<String, MethodInfo>): Boolean {
        if (node.kind != "method") return true
        val method = methodInfos[node.id] ?: return true
        return isAgentRelevantMethod(method)
    }

    private fun clusterNodeScore(node: NodeSummary, edges: List<EdgeSummary>): Int {
        val inboundCalls = edges.count { it.relationship == "calls" && it.to == node.id }
        val inboundTypeUsage = edges.count { it.relationship == "uses_type" && it.to == node.id }
        return (node.complexity ?: 0) * 10 + (node.loc ?: 0) + inboundCalls * 5 + inboundTypeUsage * 3
    }

    private fun normalizedCallPath(nodes: List<NodeSummary>, edges: List<EdgeSummary>, snapshot: Snapshot): CallPath {
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

    private fun impactScore(node: NodeSummary, snapshot: Snapshot): Int {
        val inboundCalls = snapshot.edges.count { it.relationship == "calls" && it.to == node.id }
        val inboundTypeUsage = snapshot.edges.count { it.relationship == "uses_type" && it.to == node.id }
        val descendants = snapshot.edges.count { (it.relationship == "extends" || it.relationship == "implements") && it.to == node.id }
        return inboundCalls * 5 + inboundTypeUsage * 3 + descendants * 4 + (node.complexity ?: 0) * 2 + (node.loc ?: 0)
    }

    private fun closingLine(source: String, openBraceIdx: Int): Int {
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
}

private data class JoernGraphData(
    val types: List<TypeInfo>,
    val methods: List<MethodInfo>,
    val callEdges: List<EdgeSummary>,
    val typeEdges: List<EdgeSummary>,
    val dependencyEdges: List<EdgeSummary>,
)

private data class JoernInstallation(
    val joern: Path,
    val javasrc2cpg: Path,
    val version: String?,
)

private fun detectJoernInstallation(): JoernInstallation? {
    val candidates: List<Path> = listOfNotNull(
        System.getenv("JOERN_HOME")?.let { Paths.get(it) },
        System.getenv("GRAPHHARNESS_JOERN_HOME")?.let { Paths.get(it) },
        Paths.get(System.getProperty("user.home"), ".local", "share", "graphharness", "joern"),
        Paths.get(System.getProperty("user.home"), "bin", "joern"),
    )

    for (home in candidates) {
        val directJoern: Path = home.resolve("joern")
        val directParser: Path = home.resolve("javasrc2cpg")
        if (Files.isExecutable(directJoern) && Files.isExecutable(directParser)) {
            return JoernInstallation(directJoern, directParser, detectJoernVersion(home))
        }
        val nestedJoern: Path = home.resolve("joern-cli").resolve("joern")
        val nestedParser: Path = home.resolve("joern-cli").resolve("javasrc2cpg")
        if (Files.isExecutable(nestedJoern) && Files.isExecutable(nestedParser)) {
            return JoernInstallation(nestedJoern, nestedParser, detectJoernVersion(home.resolve("joern-cli")))
        }
    }

    return findExecutableOnPath("joern")?.let { joern ->
        val parser = findExecutableOnPath("javasrc2cpg") ?: return null
        JoernInstallation(joern, parser, detectJoernVersion(parser.parent))
    }
}

private fun detectJoernVersion(home: Path?): String? =
    runCatching {
        if (home == null) return null
        val normalizedHome = if (home.fileName?.toString() == "joern-cli") home else home.resolve("joern-cli")
        val cpgVersion = normalizedHome.resolve("schema-extender").resolve("cpg-version")
        val consoleVersion = Files.list(normalizedHome.resolve("lib")).use { paths ->
            paths.map { it.fileName.toString() }
                .filter { it.startsWith("io.joern.console-") && it.endsWith(".jar") }
                .findFirst()
                .orElse(null)
        }
        val joernVersion = consoleVersion
            ?.removePrefix("io.joern.console-")
            ?.removeSuffix(".jar")
            ?.ifBlank { null }
        val cpg = if (Files.isRegularFile(cpgVersion)) Files.readString(cpgVersion).trim().ifBlank { null } else null
        listOfNotNull(joernVersion?.let { "joern-$it" }, cpg?.let { "cpg-$it" }).joinToString(" / ").ifBlank { null }
    }.getOrNull()

private fun findExecutableOnPath(name: String): Path? {
    val path = System.getenv("PATH") ?: return null
    return path.split(':')
        .map { Paths.get(it).resolve(name) }
        .firstOrNull { Files.isExecutable(it) }
}

private fun JoernInstallation.exportGraph(projectRoot: Path): JoernGraphData {
    val workDir = Files.createTempDirectory("graphharness-joern")
    val cpgFile = workDir.resolve("cpg.bin")
    val queryFile = workDir.resolve("snapshot.sc")
    val outputFile = workDir.resolve("snapshot.tsv")
    Files.writeString(queryFile, JOERN_SNAPSHOT_SCRIPT)

    runCommand(
        listOf(
            javasrc2cpg.toString(),
            projectRoot.toAbsolutePath().normalize().toString(),
            "--output",
            cpgFile.toString(),
            "--enable-file-content",
        ),
        workDir,
    )

    runCommand(
        listOf(
            joern.toString(),
            "--script",
            queryFile.toString(),
            "--param",
            "cpgFile=${cpgFile}",
            "--param",
            "outFile=${outputFile}",
        ),
        workDir,
    )
    return parseJoernSnapshot(outputFile, projectRoot)
}

private fun runCommand(command: List<String>, workDir: Path) {
    val process = ProcessBuilder(command)
        .directory(workDir.toFile())
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText()
    val exit = process.waitFor()
    require(exit == 0) {
        "Command failed (${command.joinToString(" ")}): $output"
    }
}

private fun parseJoernSnapshot(outputFile: Path, projectRoot: Path): JoernGraphData {
    val types = mutableListOf<TypeInfo>()
    val methods = mutableListOf<MethodInfo>()
    val callEdges = mutableListOf<EdgeSummary>()
    val typeEdges = mutableListOf<EdgeSummary>()
    val dependencyEdges = mutableListOf<EdgeSummary>()
    val methodIdByFullName = mutableMapOf<String, String>()
    val typeIdByQualifiedName = mutableMapOf<String, String>()
    val pendingCalls = mutableListOf<PendingCall>()
    val pendingTypeEdges = mutableListOf<PendingTypeEdge>()
    val pendingTypeRefs = mutableListOf<PendingTypeRef>()

    Files.readAllLines(outputFile).forEach { line ->
        if (line.isBlank()) return@forEach
        val parts = line.split('\t')
        when (parts[0]) {
            "TYPE" -> {
                val qualifiedName = decodeField(parts[1])
                val type = TypeInfo(
                    id = "type:$qualifiedName",
                    kind = decodeField(parts[3]).ifBlank { "class" },
                    packageName = decodeField(parts[2]),
                    simpleName = decodeField(parts[4]),
                    qualifiedName = qualifiedName,
                    file = decodeField(parts[5]),
                    lineRange = SourceRange(parts[6].toIntOrNull() ?: 1, parts[7].toIntOrNull() ?: (parts[6].toIntOrNull() ?: 1)),
                    visibility = decodeField(parts[8]).ifBlank { "package" },
                    annotations = decodeCsv(parts[9]),
                    extendsType = decodeField(parts[10]).ifBlank { null },
                    implementsTypes = decodeCsv(parts[11]),
                )
                types += type
                typeIdByQualifiedName[type.qualifiedName] = type.id
                type.extendsType?.let { pendingTypeEdges += PendingTypeEdge(type.id, it, "extends") }
                type.implementsTypes.forEach { pendingTypeEdges += PendingTypeEdge(type.id, it, "implements") }
            }

            "METHOD" -> {
                val fullName = decodeField(parts[2])
                val qualifiedName = fullName.substringBefore(':').ifBlank {
                    listOf(decodeField(parts[1]), decodeField(parts[3])).filter { it.isNotBlank() }.joinToString(".")
                }
                val parentQualifiedName = decodeField(parts[1]).ifBlank { qualifiedName.substringBeforeLast('.', "") }
                val signature = decodeField(parts[4])
                val parameterTypes = decodeCsv(parts[11])
                val returnType = decodeField(parts[12]).ifBlank { null }
                val method = MethodInfo(
                    id = "method:$qualifiedName:${signatureOf(parameterTypes, returnType)}",
                    parentTypeId = "type:$parentQualifiedName",
                    parentQualifiedName = parentQualifiedName,
                    simpleName = decodeField(parts[3]),
                    qualifiedName = qualifiedName,
                    signature = signature,
                    file = decodeField(parts[5]),
                    lineRange = SourceRange(parts[6].toIntOrNull() ?: 1, parts[7].toIntOrNull() ?: (parts[6].toIntOrNull() ?: 1)),
                    visibility = decodeField(parts[8]).ifBlank { "package" },
                    annotations = decodeCsv(parts[9]),
                    complexity = parts[10].toIntOrNull() ?: 1,
                    loc = ((parts[7].toIntOrNull() ?: 1) - (parts[6].toIntOrNull() ?: 1) + 1).coerceAtLeast(1),
                    returnType = returnType,
                    parameterTypes = parameterTypes,
                    body = "",
                    callTokens = emptyList(),
                    typeRefs = (parameterTypes + listOfNotNull(returnType)).filter { it.isNotBlank() }.toSet(),
                )
                methods += method
                methodIdByFullName[fullName] = method.id
                (parameterTypes + listOfNotNull(returnType)).forEach { pendingTypeRefs += PendingTypeRef(method.id, it) }
            }

            "CALL" -> {
                pendingCalls += PendingCall(
                    sourceQualifiedName = decodeField(parts[1]),
                    targetQualifiedName = decodeField(parts[2]),
                    file = decodeField(parts[3]).ifBlank { null },
                    line = parts[4].toIntOrNull(),
                )
            }
        }
    }

    pendingCalls.forEach { call ->
        val source = methodIdByFullName[call.sourceQualifiedName] ?: return@forEach
        val target = methodIdByFullName[call.targetQualifiedName] ?: return@forEach
        callEdges += EdgeSummary(source, target, "calls", call.file, call.line)
    }

    pendingTypeEdges.forEach { edge ->
        val target = typeIdByQualifiedName[edge.targetQualifiedName] ?: return@forEach
        val sourceType = types.firstOrNull { it.id == edge.fromId }
        val targetType = types.firstOrNull { it.id == target }
        val relationship = if (
            edge.relationship == "extends" &&
            sourceType?.kind != "interface" &&
            targetType?.kind == "interface"
        ) {
            "implements"
        } else {
            edge.relationship
        }
        typeEdges += EdgeSummary(edge.fromId, target, relationship)
    }

    pendingTypeRefs.forEach { ref ->
        val target = typeIdByQualifiedName[ref.targetQualifiedName] ?: return@forEach
        dependencyEdges += EdgeSummary(ref.methodId, target, "uses_type")
    }

    return refineJoernRanges(
        JoernGraphData(types, methods, callEdges, typeEdges, dependencyEdges),
        projectRoot,
    )
}

private data class PendingCall(
    val sourceQualifiedName: String,
    val targetQualifiedName: String,
    val file: String?,
    val line: Int?,
)

private data class PendingTypeEdge(
    val fromId: String,
    val targetQualifiedName: String,
    val relationship: String,
)

private data class PendingTypeRef(
    val methodId: String,
    val targetQualifiedName: String,
)

private val callPattern = Regex("""\b([A-Za-z_]\w*)\s*\(""")
private val bodyTypePattern = Regex("""\b([A-Z][A-Za-z0-9_]*)\b""")
private val JAVA_KEYWORDS = setOf(
    "if", "for", "while", "switch", "catch", "return", "new", "throw", "this", "super", "try", "do", "else", "synchronized",
)
private val DEFAULT_BLIND_SPOTS = listOf(
    "reflection",
    "dependency injection wiring",
    "dynamic proxies",
    "generated sources",
    "runtime-only dispatch",
)

private fun signatureOf(parameterTypes: List<String>, returnType: String?): String =
    "${parameterTypes.joinToString("|")}:${returnType.orEmpty()}"

private fun extractAnnotations(block: String): List<String> =
    Regex("""@([A-Za-z_]\w*)""").findAll(block).map { "@${it.groupValues[1]}" }.toList()

private fun extractVisibility(signature: String): String = when {
    "public" in signature -> "public"
    "protected" in signature -> "protected"
    "private" in signature -> "private"
    else -> "package"
}

private fun computeComplexity(body: String): Int {
    val branches = listOf(" if ", " for ", " while ", " case ", " catch ", "&&", "||", "?:")
    return 1 + branches.sumOf { token -> Regex(Regex.escape(token.trim())).findAll(body).count() }
}

private fun normalize(value: String): String = value.replace('\\', '/')

private fun elapsedMs(start: Long): Long = (System.nanoTime() - start) / 1_000_000

private fun encodeField(value: String): String =
    Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))

private fun decodeField(value: String): String =
    String(Base64.getDecoder().decode(value), Charsets.UTF_8)

private fun decodeCsv(value: String): List<String> =
    if (value.isBlank()) emptyList() else decodeField(value).split("||").filter { it.isNotBlank() }

private fun refineJoernRanges(graph: JoernGraphData, projectRoot: Path): JoernGraphData {
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

private fun findBlockEndLine(source: String, startLine: Int): Int? {
    if (startLine < 1) return null
    val startOffset = lineStartOffset(source, startLine)
    if (startOffset >= source.length) return null
    val openBrace = source.indexOf('{', startOffset).takeIf { it >= 0 } ?: return null
    return closingLineForSource(source, openBrace)
}

private fun lineStartOffset(source: String, lineNumber: Int): Int {
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

private fun closingLineForSource(source: String, openBraceIdx: Int): Int {
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

private val JOERN_SNAPSHOT_SCRIPT = """
import java.io.PrintWriter
import java.util.Base64

def enc(value: String): String =
  Base64.getEncoder.encodeToString(Option(value).getOrElse("").getBytes("UTF-8"))

def encList(values: Iterable[String]): String =
  enc(values.filter(_ != null).mkString("||"))

@main def exec(cpgFile: String, outFile: String): Unit = {
  importCpg(cpgFile)
  val out = new PrintWriter(outFile)

  cpg.typeDecl.filterNot(_.isExternal).l.foreach { td =>
    val fullName = Option(td.fullName).map(_.toString).getOrElse("")
    val packageName = fullName.split('.').dropRight(1).mkString(".")
    val kind =
      if (Option(td.code).map(_.toString).exists(_.contains(" interface "))) "interface"
      else if (Option(td.code).map(_.toString).exists(_.contains(" enum "))) "enum"
      else "class"
    val simpleName = Option(td.name).map(_.toString).getOrElse(fullName.split('.').lastOption.getOrElse(""))
    val visibility = td.modifier.modifierType.l.find(Set("PUBLIC", "PROTECTED", "PRIVATE")).map(_.toLowerCase).getOrElse("package")
    val annotations = td.annotation.name.l.map("@" + _)
    val inherits = td.inheritsFromTypeFullName.l
    val extendsType = inherits.headOption.getOrElse("")
    val implementsTypes = if (inherits.size > 1) inherits.drop(1) else Nil
    out.println(
      List(
        "TYPE",
        enc(fullName),
        enc(packageName),
        enc(kind),
        enc(simpleName),
        enc(Option(td.filename).map(_.toString).getOrElse("")),
        td.lineNumber.getOrElse(1).toString,
        td.ast.lineNumber.l.maxOption.getOrElse(td.lineNumber.getOrElse(1)).toString,
        enc(visibility),
        encList(annotations),
        enc(extendsType),
        encList(implementsTypes)
      ).mkString("\t")
    )
  }

  cpg.method.filterNot(_.isExternal).l.foreach { m =>
    val parent = Option(m.typeDecl.fullName).map(_.toString).getOrElse("")
    val fullName = Option(m.fullName).map(_.toString).getOrElse("")
    val methodName = Option(m.name).map(_.toString).getOrElse("")
    val visibility = m.modifier.modifierType.l.find(Set("PUBLIC", "PROTECTED", "PRIVATE")).map(_.toLowerCase).getOrElse("package")
    val annotations = m.annotation.name.l.map("@" + _)
    val params = m.parameter.l
      .filterNot(p => Option(p.name).map(_.toString).contains("this"))
      .flatMap(p => Option(p.typeFullName).map(_.toString))
      .filterNot(_ == null)
    val returnType = Option(m.methodReturn.typeFullName).map(_.toString).getOrElse("")
    val signature = s"(${'$'}{params.mkString(", ")}) -> ${'$'}{if (returnType.nonEmpty) returnType else "void"}"
    val code = Option(m.code).map(_.toString).getOrElse("")
    val complexity = 1 + List("if", "for", "while", "case", "catch").count(keyword => code.contains(keyword))
    out.println(
      List(
        "METHOD",
        enc(parent),
        enc(fullName),
        enc(methodName),
        enc(signature),
        enc(Option(m.filename).map(_.toString).getOrElse("")),
        m.lineNumber.getOrElse(1).toString,
        m.ast.lineNumber.l.maxOption.getOrElse(m.lineNumber.getOrElse(1)).toString,
        enc(visibility),
        encList(annotations),
        complexity.toString,
        encList(params),
        enc(returnType)
      ).mkString("\t")
    )
  }

  cpg.call.l.foreach { call =>
    val src = Option(call.method.fullName).map(_.toString).getOrElse("")
    val dst = Option(call.methodFullName).map(_.toString).getOrElse("")
    if (src.nonEmpty && dst.nonEmpty && !dst.startsWith("<operator>")) {
      out.println(
        List(
          "CALL",
          enc(src),
          enc(dst),
          enc(Option(call.method.filename).map(_.toString).getOrElse("")),
          call.lineNumber.getOrElse(0).toString
        ).mkString("\t")
      )
    }
  }

  out.close()
}
""".trimIndent()
