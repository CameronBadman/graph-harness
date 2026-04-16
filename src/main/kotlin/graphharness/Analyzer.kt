package graphharness

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.security.MessageDigest
import java.time.Instant
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
        val summaryMode = summaryModeFor(snapshot)
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

}
