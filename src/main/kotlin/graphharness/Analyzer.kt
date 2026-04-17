package graphharness

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardWatchEventKinds
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
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

private data class ValidationCommand(
    val validator: String,
    val command: List<String>,
    val environment: Map<String, String> = emptyMap(),
    val workingDirectory: Path? = null,
)

private data class ProcessRunResult(
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean,
)

private data class ValidationOutcome(
    val validator: ValidationCommand,
    val result: ProcessRunResult,
    val degraded: Boolean,
    val notes: List<String>,
)

private data class ValidationTarget(
    val root: Path,
    val label: String,
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
    val fileHashes: Map<String, String>,
    val packageCount: Int,
    val clusterStrategy: String = "package",
    val clusterProvenance: String = "package_name",
    val recomputeMode: String = "full",
    val epoch: Long = 0,
)

class SnapshotManager(private val projectRoot: Path) {
    private val activeSnapshot = AtomicReference<Snapshot>()
    private val snapshotHistory = ConcurrentHashMap<String, Snapshot>()
    private val snapshotOrder = ArrayDeque<String>()
    private val pendingEdits = ConcurrentHashMap<String, PendingEdit>()
    private val rebuildThreadIds = AtomicInteger(1)
    private val snapshotEpoch = AtomicLong(1)
    private val pendingRebuild = AtomicBoolean(false)
    private val dirtyFiles = ConcurrentHashMap.newKeySet<String>()
    private val requireJoern = readStrictJoernMode()
    private val clusterStrategy = readClusterStrategy()
    private val incrementalFileThreshold = 40
    private val rebuildExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable).apply {
            isDaemon = true
            name = "graphharness-rebuild-${rebuildThreadIds.getAndIncrement()}"
        }
    }

    init {
        val initial = buildSnapshot().copy(epoch = snapshotEpoch.get())
        recordSnapshot(initial)
        activeSnapshot.set(initial)
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
                        val watched = key.watchable() as? Path
                        if (watched != null) {
                            val abs = watched.resolve(ctx).normalize()
                            runCatching {
                                if (abs.startsWith(root)) {
                                    dirtyFiles += normalize(root.relativize(abs).invariantSeparatorsPathString)
                                }
                            }
                        }
                    }
                }
                key.reset()
                if (touchedJava) {
                    pendingRebuild.set(true)
                    rebuildExecutor.schedule({
                        rebuildFromDirty()
                    }, 800, TimeUnit.MILLISECONDS)
                }
            }
        }
        watcherThread.isDaemon = true
        watcherThread.name = "graphharness-watcher"
        watcherThread.start()
    }

    @Synchronized
    private fun recordSnapshot(snapshot: Snapshot) {
        snapshotHistory[snapshot.id] = snapshot
        snapshotOrder.remove(snapshot.id)
        snapshotOrder.addLast(snapshot.id)
        while (snapshotOrder.size > 12) {
            val expired = snapshotOrder.removeFirst()
            snapshotHistory.remove(expired)
        }
    }

    private fun installSnapshot(snapshot: Snapshot) {
        val withEpoch = snapshot.copy(epoch = snapshotEpoch.incrementAndGet())
        recordSnapshot(withEpoch)
        activeSnapshot.set(withEpoch)
        dirtyFiles.clear()
        pendingRebuild.set(false)
    }

    private fun snapshotById(snapshotId: String): Snapshot =
        snapshotHistory[snapshotId] ?: error("Unknown snapshot_id: $snapshotId")

    private fun rebuildFromDirty() {
        val changed = dirtyFiles.toSet()
        val snapshot = runCatching { buildSnapshot(changed) }
            .recoverCatching { buildSnapshot(emptySet()) }
            .getOrElse {
                pendingRebuild.set(false)
                return
            }
        installSnapshot(snapshot)
    }

    private fun buildSnapshot(changedFiles: Set<String> = emptySet()): Snapshot {
        val buildStart = System.nanoTime()
        val active = activeSnapshot.get()
        val changedJava = changedFiles
            .map { normalize(it) }
            .filter { it.endsWith(".java") }
            .toSet()

        if (active != null &&
            active.analysisEngine == "fallback-parser" &&
            changedJava.isNotEmpty() &&
            changedJava.size <= incrementalFileThreshold
        ) {
            return buildSnapshotLegacyIncremental(active, changedJava, elapsedMs(buildStart))
        }

        val javaFiles = Files.walk(projectRoot).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension == "java" }
                .asSequence()
                .toList()
        }

        val joernInstallation = detectJoernInstallation()
        val joernSnapshot = joernInstallation?.let { joern ->
            runCatching { buildSnapshotWithJoern(javaFiles, joern, elapsedMs(buildStart)) }
                .getOrElse { err ->
                    if (requireJoern) {
                        throw IllegalStateException("Joern backend required but failed: ${err.message}", err)
                    }
                    null
                }
        }
        if (joernSnapshot != null) {
            return joernSnapshot
        }
        if (requireJoern) {
            error("Joern backend is required (GRAPHHARNESS_REQUIRE_JOERN=true) but no usable Joern installation was found")
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
        val clusters = buildClusters(typeInfos, methodInfos, edges, nodeSummaries, clusterStrategy)
        val clusterProvenance = clusterProvenanceForStrategy(clusterStrategy)
        val sourceIndex = javaFiles.associateBy { normalize(projectRoot.relativize(it).invariantSeparatorsPathString) }
        val fileHashes = sourceIndex.mapValues { (_, path) -> sha256(readPathText(path)) }
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
            clusterNodes = buildClusterNodes(clusters, typeInfos, methodInfos, clusterStrategy),
            typeInfos = typeInfos,
            methodInfos = methodInfos,
            sourceIndex = sourceIndex,
            fileHashes = fileHashes,
            packageCount = typeInfos.values.map { it.packageName }.filter { it.isNotBlank() }.toSet().size,
            clusterStrategy = clusterStrategy,
            clusterProvenance = clusterProvenance,
            recomputeMode = "full",
        )
    }

    private fun buildSnapshotLegacyIncremental(
        previous: Snapshot,
        changedFiles: Set<String>,
        buildDurationMs: Long,
    ): Snapshot {
        val typeInfos = previous.typeInfos.toMutableMap()
        val methodInfos = previous.methodInfos.toMutableMap()
        val sourceIndex = previous.sourceIndex.toMutableMap()
        val fileHashes = previous.fileHashes.toMutableMap()

        changedFiles.forEach { relative ->
            val absPath = projectRoot.resolve(relative).normalize()
            typeInfos.entries.removeIf { it.value.file == relative }
            methodInfos.entries.removeIf { it.value.file == relative }

            if (!Files.exists(absPath) || !Files.isRegularFile(absPath) || absPath.extension != "java") {
                sourceIndex.remove(relative)
                fileHashes.remove(relative)
                return@forEach
            }

            val parsed = parseJavaFile(absPath, projectRoot)
            parsed.types.forEach { typeInfos[it.id] = it }
            parsed.methods.forEach { methodInfos[it.id] = it }
            sourceIndex[relative] = absPath
            fileHashes[relative] = sha256(readPathText(absPath))
        }

        val typesBySimpleName = typeInfos.values.groupBy { it.simpleName }
        val methodsBySimpleName = methodInfos.values.groupBy { it.simpleName }
        val typeByQualifiedName = typeInfos.values.associateBy { it.qualifiedName }
        val nodeSummaries = buildNodeSummaries(typeInfos, methodInfos)
        val callEdges = buildCallEdges(methodInfos, methodsBySimpleName)
        val typeEdges = buildTypeEdges(typeInfos, typeByQualifiedName, typesBySimpleName)
        val dependencyEdges = buildDependencyEdges(methodInfos, typeInfos, typesBySimpleName)
        val edges = (callEdges + typeEdges + dependencyEdges).distinct()
        val clusters = buildClusters(typeInfos, methodInfos, edges, nodeSummaries, clusterStrategy)
        val clusterProvenance = clusterProvenanceForStrategy(clusterStrategy)
        val generatedAt = Instant.now().toString()
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(
                buildString {
                    append("fallback-incremental")
                    append(generatedAt)
                    changedFiles.sorted().forEach { append(it) }
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
            clusterNodes = buildClusterNodes(clusters, typeInfos, methodInfos, clusterStrategy),
            typeInfos = typeInfos,
            methodInfos = methodInfos,
            sourceIndex = sourceIndex,
            fileHashes = fileHashes,
            packageCount = typeInfos.values.map { it.packageName }.filter { it.isNotBlank() }.toSet().size,
            clusterStrategy = clusterStrategy,
            clusterProvenance = clusterProvenance,
            recomputeMode = "incremental",
        )
    }

    private fun buildSnapshotWithJoern(javaFiles: List<Path>, joern: JoernInstallation, buildDurationMs: Long): Snapshot {
        val graph = joern.exportGraph(projectRoot)
        val typeInfos = graph.types.associateBy { it.id }
        val methodInfos = graph.methods.associateBy { it.id }
        val nodeSummaries = buildNodeSummaries(typeInfos, methodInfos)
        val graphEdges = (graph.callEdges + graph.typeEdges + graph.dependencyEdges).distinct()
        val clusters = buildClusters(typeInfos, methodInfos, graphEdges, nodeSummaries, clusterStrategy)
        val clusterProvenance = clusterProvenanceForStrategy(clusterStrategy)
        val sourceIndex = javaFiles.associateBy { normalize(projectRoot.relativize(it).invariantSeparatorsPathString) }
        val fileHashes = sourceIndex.mapValues { (_, path) -> sha256(readPathText(path)) }
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
            clusterNodes = buildClusterNodes(clusters, typeInfos, methodInfos, clusterStrategy),
            typeInfos = typeInfos,
            methodInfos = methodInfos,
            sourceIndex = sourceIndex,
            fileHashes = fileHashes,
            packageCount = typeInfos.values.map { it.packageName }.filter { it.isNotBlank() }.toSet().size,
            clusterStrategy = clusterStrategy,
            clusterProvenance = clusterProvenance,
            recomputeMode = "full",
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
        clusterStrategy: String,
    ): List<ClusterSummary> {
        val groupedNodeIds = mutableMapOf<String, MutableList<String>>()
        typeInfos.values.forEach { type ->
            val clusterId = clusterIdForType(type, clusterStrategy)
            groupedNodeIds.getOrPut(clusterId) { mutableListOf() }.add(type.id)
        }
        methodInfos.values.forEach { method ->
            val clusterId = clusterIdForMethod(method, typeInfos, clusterStrategy)
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
                description = clusterDescription(clusterId, clusterStrategy),
                node_count = ids.size,
                key_nodes = keyNodes,
                external_edges = externalEdges.size,
                bridge_nodes = bridgeNodes,
                strategy = clusterStrategy,
                provenance = clusterProvenanceForStrategy(clusterStrategy),
            )
        }
    }

    private fun buildClusterNodes(
        clusters: List<ClusterSummary>,
        typeInfos: Map<String, TypeInfo>,
        methodInfos: Map<String, MethodInfo>,
        clusterStrategy: String,
    ): Map<String, List<String>> {
        val result = mutableMapOf<String, MutableList<String>>()
        typeInfos.values.forEach { type ->
            result.getOrPut(clusterIdForType(type, clusterStrategy)) { mutableListOf() }.add(type.id)
        }
        methodInfos.values.forEach { method ->
            result.getOrPut(clusterIdForMethod(method, typeInfos, clusterStrategy)) { mutableListOf() }.add(method.id)
        }
        return clusters.associate { it.cluster_id to result.getOrDefault(it.cluster_id, mutableListOf()).sorted() }
    }

    private fun clusterIdForType(type: TypeInfo, strategy: String): String =
        when (strategy) {
            "package_tail" -> clusterIdForPackageTail(type.packageName)
            "module" -> clusterIdForModule(type.file)
            else -> clusterIdForPackage(type.packageName)
        }

    private fun clusterIdForMethod(method: MethodInfo, typeInfos: Map<String, TypeInfo>, strategy: String): String {
        val packageName = packageNameForMethod(method, typeInfos)
        return when (strategy) {
            "package_tail" -> clusterIdForPackageTail(packageName)
            "module" -> clusterIdForModule(method.file)
            else -> clusterIdForPackage(packageName)
        }
    }

    private fun clusterIdForPackageTail(packageName: String): String {
        if (packageName.isBlank()) return "cluster:default"
        return "cluster:domain:${packageName.substringAfterLast('.')}"
    }

    private fun clusterIdForModule(file: String): String {
        val normalized = normalize(file)
        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return "cluster:module:root"
        return when {
            segments.size >= 3 && segments[0] == "src" -> "cluster:module:${segments[0]}/${segments[1]}/${segments[2]}"
            else -> "cluster:module:${segments.first()}"
        }
    }

    private fun clusterProvenanceForStrategy(strategy: String): String =
        when (strategy) {
            "package_tail" -> "package_tail_segment"
            "module" -> "file_path_module_segment"
            else -> "package_name_prefix"
        }

    private fun clusterDescription(clusterId: String, strategy: String): String =
        when (strategy) {
            "package_tail" -> "Domain-oriented subsystem grouped by package tail: ${clusterId.removePrefix("cluster:domain:")}."
            "module" -> "Module-oriented subsystem grouped by source path: ${clusterId.removePrefix("cluster:module:")}."
            else -> "Package-oriented subsystem rooted at ${clusterId.removePrefix("cluster:")}."
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
            semantic_level = semanticLevelFor(snapshot),
            snapshot_state = snapshotRuntimeState(snapshot),
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
        )
    }

    private fun resolveBundleNode(task: String, snapshot: Snapshot, notes: MutableList<String>): NodeSummary? {
        val resolved = runCatching { resolveEditTarget(task, 4, snapshot) }.getOrNull()
        val resolvedCandidate = resolved?.resolved_candidate
        if (resolvedCandidate != null && !resolved.needs_disambiguation) {
            notes += "bundle_resolution=resolve_edit_target"
            return resolvedCandidate.node
        }
        if (resolved != null && resolved.needs_disambiguation) {
            notes += "bundle_resolution=resolve_edit_target_ambiguous"
        }

        val searchTerms = Regex("""[A-Za-z_]\w+""")
            .findAll(task)
            .map { it.value }
            .filter { it.length >= 4 }
            .toList()
        for (term in searchTerms) {
            val match = search(term, "method", null, null, snapshot).results.firstOrNull()
            if (match != null) {
                notes += "bundle_resolution=search_graph:$term"
                return match
            }
        }

        val fallback = summaryMap(snapshot).entrypoints.firstOrNull()?.let { snapshot.nodeSummaries[it.id] }
        if (fallback != null) {
            notes += "bundle_resolution=entrypoint_fallback"
        }
        return fallback
    }

    private fun estimateBundleTokenUsage(
        summaryMode: String,
        task: String?,
        chosenNode: NodeSummary?,
        clusters: List<ClusterSummary>,
        entrypoints: List<OrientationNode>,
        focusNodes: List<NodeSummary>,
        relationships: List<EdgeSummary>,
        impactFiles: List<String>,
        notes: List<String>,
    ): Int {
        val payload = buildMap<String, Any?> {
            put("summary_mode", summaryMode)
            put("task", task)
            put("chosen_node", chosenNode?.name)
            put("clusters", clusters)
            put("entrypoints", entrypoints)
            put("focus_nodes", focusNodes)
            put("relationships", relationships)
            put("impact_files", impactFiles)
            put("notes", notes)
        }
        return estimateTextTokens(graphHarnessJson.encode(payload).stringify())
    }

    private fun estimateTextTokens(text: String): Int = maxOf(1, (text.length / 4) + 1)

    private fun snapshotNodeFingerprint(node: NodeSummary): String {
        val simpleName = node.name.substringAfterLast('.')
        return "${node.kind}|${node.file}|${node.line_range.start}|$simpleName"
    }

    private fun readStrictJoernMode(): Boolean {
        val raw = System.getenv("GRAPHHARNESS_REQUIRE_JOERN")?.trim()?.lowercase() ?: return false
        return raw in setOf("1", "true", "yes", "on")
    }

    private fun readClusterStrategy(): String {
        val raw = System.getenv("GRAPHHARNESS_CLUSTER_STRATEGY")?.trim()?.lowercase() ?: "package"
        return when (raw) {
            "package", "package_tail", "module" -> raw
            else -> "package"
        }
    }

    private fun isApproximateBackend(snapshot: Snapshot): Boolean = snapshot.analysisEngine != "joern"

    private fun semanticLevelFor(snapshot: Snapshot): String = if (isApproximateBackend(snapshot)) "approximate" else "stable"

    private fun snapshotRuntimeState(snapshot: Snapshot = current()): SnapshotRuntimeState {
        val graphAgeMs = runCatching {
            Duration.between(Instant.parse(snapshot.generatedAt), Instant.now()).toMillis().coerceAtLeast(0)
        }.getOrDefault(0)
        val dirty = dirtyFiles.toList().sorted()
        val dirtyNodeEstimate = if (dirty.isEmpty()) 0 else snapshot.nodeSummaries.values.count { it.file in dirty }
        return SnapshotRuntimeState(
            snapshot_epoch = snapshot.epoch,
            graph_age_ms = graphAgeMs,
            pending_rebuild = pendingRebuild.get(),
            dirty_files = dirty,
            dirty_node_estimate = dirtyNodeEstimate,
            recompute_mode = snapshot.recomputeMode,
            incremental_supported = snapshot.analysisEngine == "fallback-parser",
        )
    }

    private fun requireDeterministicTool(toolName: String, snapshot: Snapshot) {
        if (!isApproximateBackend(snapshot)) return
        if (toolName in setOf("get_call_paths", "get_implementations", "get_impact")) {
            error("Tool $toolName is disabled for heuristic backend; run with Joern or enable GRAPHHARNESS_REQUIRE_JOERN=true")
        }
    }

    fun capabilities(snapshot: Snapshot = current()): CapabilitiesResult {
        val isApproximate = isApproximateBackend(snapshot)
        val disabledTools = if (isApproximate) listOf("get_call_paths", "get_implementations", "get_impact") else emptyList()
        val available = listOf(
            "get_capabilities",
            "build_context_bundle",
            "get_snapshot_delta",
            "get_summary_map",
            "get_cluster_detail",
            "search_graph",
            "get_node_detail",
            "get_call_paths",
            "get_callers",
            "get_callees",
            "get_implementations",
            "get_type_hierarchy",
            "get_dependencies",
            "get_impact",
            "get_source",
            "get_source_batch",
            "get_edit_candidates",
            "resolve_edit_target",
            "verify_candidate",
            "plan_edit",
            "rebase_edit_plan",
            "apply_edit",
            "validate_edit",
            "get_validation_targets",
            "get_agent_fitness",
            "get_cluster_fitness",
        ).filterNot { it in disabledTools }
        val guarantees = buildMap<String, String> {
            put("get_summary_map", "stable")
            put("search_graph", "stable")
            put("get_source", "stable")
            put("get_source_batch", "stable")
            put("plan_edit", "best_effort")
            put("rebase_edit_plan", "best_effort")
            put("apply_edit", "best_effort")
            put("validate_edit", "best_effort")
            put("get_snapshot_delta", "best_effort")
            put("get_dependencies", if (isApproximate) "approximate" else "best_effort")
            put("get_type_hierarchy", if (isApproximate) "approximate" else "best_effort")
            put("get_node_detail", if (isApproximate) "approximate" else "best_effort")
            put("get_callers", if (isApproximate) "approximate" else "best_effort")
            put("get_callees", if (isApproximate) "approximate" else "best_effort")
            put("get_impact", if (isApproximate) "disabled" else "best_effort")
            put("get_call_paths", if (isApproximate) "disabled" else "best_effort")
            put("get_implementations", if (isApproximate) "disabled" else "best_effort")
        }
        return CapabilitiesResult(
            languages = listOf("java"),
            analysis_engine = snapshot.analysisEngine,
            backend_mode = if (isApproximate) "heuristic" else "joern",
            semantic_level = semanticLevelFor(snapshot),
            engine_version = snapshot.engineVersion,
            available_tools = available,
            disabled_tools = disabledTools,
            edit_operations = listOf("modify_method_body", "rename_node"),
            validation_modes = listOf("auto", "compile", "test"),
            confidence_semantics = listOf(
                "analysis_confidence values are bounded scores for orchestration and never formal proof",
                "confidence below 0.65 should be treated as ambiguous and require disambiguation",
                "degraded validation means repo-aware validation failed or was blocked and fallback paths were attempted",
            ),
            tool_guarantees = guarantees,
            stability_guarantees = listOf(
                "snapshot_id and snapshot_epoch are stable for each installed snapshot",
                "node ids are stable only within the same snapshot epoch",
                "rebase_edit_plan can re-plan stale edits when snapshot changes",
            ),
            best_effort_guarantees = listOf(
                "snapshot deltas perform heuristic node rebinding when identifiers shift",
                "call/dependency edges may be incomplete for dynamic runtime behavior",
                "validation targeting is heuristic unless explicit tests are provided",
                "incremental recompute currently applies only to fallback backend and still rebuilds global edges",
            ),
            degraded_mode_flags = listOf("degraded", "attempted_validators", "summary_mode", "pending_rebuild"),
            failure_semantics = listOf(
                "stale_snapshot: planned edit snapshot no longer matches active snapshot",
                "approximate_backend: backend is heuristic, high-trust tools may be disabled",
                "validation_degraded: validation required fallback path",
                "unsupported_query: tool or operation unavailable in current backend mode",
            ),
            snapshot_semantics = "Node ids are snapshot-scoped. Responses include snapshot_id and snapshot_state. get_snapshot_delta compares retained snapshots and rebinding is heuristic.",
            clustering_semantics = "Clusters are strategy-driven and include provenance; strategy can be package, package_tail, or module.",
            cluster_strategy = snapshot.clusterStrategy,
            analysis_engine_capabilities = listOf(
                "joern-backed java analysis",
                "snapshot-based traversal",
                "graph-guided edit planning",
                "scratch-copy validation",
                "repo and cluster fitness scoring",
                "file-level invalidation with fallback incremental parser mode",
            ),
            build_context_bundle_supported = true,
            analysis_engine_first_backend = snapshot.analysisEngine == "joern",
            snapshot_state = snapshotRuntimeState(snapshot),
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
        )
    }

    fun snapshotDelta(oldSnapshotId: String, newSnapshotId: String): SnapshotDeltaResult {
        val oldSnapshot = snapshotById(oldSnapshotId)
        val newSnapshot = snapshotById(newSnapshotId)

        val addedNodes = newSnapshot.nodeSummaries
            .filterKeys { it !in oldSnapshot.nodeSummaries }
            .values
            .sortedBy { it.name }
        val removedNodes = oldSnapshot.nodeSummaries
            .filterKeys { it !in newSnapshot.nodeSummaries }
            .values
            .sortedBy { it.name }

        val oldFingerprints = oldSnapshot.nodeSummaries.values.groupBy(::snapshotNodeFingerprint)
        val newFingerprints = newSnapshot.nodeSummaries.values.groupBy(::snapshotNodeFingerprint)
        val changedNodes = mutableListOf<SnapshotDeltaNode>()
        oldFingerprints.keys.intersect(newFingerprints.keys).sorted().forEach { fingerprint ->
            val oldNodes = oldFingerprints[fingerprint].orEmpty()
            val newNodes = newFingerprints[fingerprint].orEmpty()
            val pairCount = min(oldNodes.size, newNodes.size)
            repeat(pairCount) { index ->
                val oldNode = oldNodes[index]
                val newNode = newNodes[index]
                if (oldNode.id == newNode.id && oldNode == newNode) return@repeat
                val changeTypes = buildList {
                    if (oldNode.id != newNode.id) add("node_id")
                    if (oldNode.name != newNode.name) add("name")
                    if (oldNode.signature != newNode.signature) add("signature")
                    if (oldNode.file != newNode.file) add("file")
                    if (oldNode.line_range != newNode.line_range) add("line_range")
                }
                if (changeTypes.isNotEmpty()) {
                    changedNodes += SnapshotDeltaNode(
                        old_node_id = oldNode.id,
                        new_node_id = newNode.id,
                        kind = newNode.kind,
                        old_name = oldNode.name,
                        new_name = newNode.name,
                        file = if (oldNode.file == newNode.file) newNode.file else "${oldNode.file} -> ${newNode.file}",
                        change_types = changeTypes,
                    )
                }
            }
        }

        val oldFiles = oldSnapshot.sourceIndex.keys.toSet()
        val newFiles = newSnapshot.sourceIndex.keys.toSet()
        val sharedFiles = oldFiles.intersect(newFiles)
        val changedFiles = sharedFiles.filter { file ->
            oldSnapshot.fileHashes[file] != newSnapshot.fileHashes[file]
        }.sorted()

        val oldEdges = oldSnapshot.edges.map { Triple(it.from, it.to, it.relationship) }.toSet()
        val newEdges = newSnapshot.edges.map { Triple(it.from, it.to, it.relationship) }.toSet()

        return SnapshotDeltaResult(
            old_snapshot_id = oldSnapshotId,
            new_snapshot_id = newSnapshotId,
            added_nodes = addedNodes,
            removed_nodes = removedNodes,
            changed_nodes = changedNodes.sortedBy { it.new_name ?: it.old_name ?: it.new_node_id ?: it.old_node_id },
            added_files = (newFiles - oldFiles).sorted(),
            removed_files = (oldFiles - newFiles).sorted(),
            changed_files = changedFiles,
            added_edge_count = (newEdges - oldEdges).size,
            removed_edge_count = (oldEdges - newEdges).size,
            analysis_engine = newSnapshot.analysisEngine,
            engine_version = newSnapshot.engineVersion,
            build_duration_ms = newSnapshot.buildDurationMs,
            semantic_level = semanticLevelFor(newSnapshot),
            rebinding_mode = "heuristic",
            snapshot_state = snapshotRuntimeState(newSnapshot),
            snapshot_id = newSnapshot.id,
            generated_at = newSnapshot.generatedAt,
        )
    }

    fun buildContextBundle(
        task: String? = null,
        nodeId: String? = null,
        tokenBudget: Int = 1800,
        snapshot: Snapshot = current(),
    ): ContextBundleResult {
        require(!task.isNullOrBlank() || !nodeId.isNullOrBlank()) { "Provide task or node_id" }
        val budget = tokenBudget.coerceIn(400, 12000)
        val notes = mutableListOf<String>()
        val summary = summaryMap(snapshot)
        val chosenNode = when {
            !nodeId.isNullOrBlank() -> snapshot.nodeSummaries[nodeId] ?: error("Unknown node_id: $nodeId")
            else -> resolveBundleNode(task!!.trim(), snapshot, notes)
        }
        val chosenNodeId = chosenNode?.id
        val focusIds = linkedSetOf<String>()
        val focusNodes = mutableListOf<NodeSummary>()
        val relationships = mutableListOf<EdgeSummary>()
        val impactFiles = mutableListOf<String>()
        val sourceSlices = mutableListOf<SourceBatchItem>()
        val clusters = mutableListOf<ClusterSummary>()
        val entrypoints = summary.entrypoints.take(3)

        if (chosenNode != null) {
            focusIds += chosenNode.id
            focusNodes += chosenNode
            val cluster = snapshot.clusters.firstOrNull { chosenNode.id in snapshot.clusterNodes[it.cluster_id].orEmpty() }
            if (cluster != null) clusters += cluster

            val detail = nodeDetail(chosenNode.id, snapshot)
            (listOf(chosenNode) + detail.callers.take(2).map { it.node } + detail.callees.take(3).map { it.node } + detail.implementations.take(2))
                .forEach { node ->
                    if (node.id != chosenNode.id && focusIds.add(node.id)) {
                        focusNodes += node
                    }
                }
            relationships += snapshot.edges
                .filter { it.from in focusIds && it.to in focusIds }
                .filter { it.relationship in setOf("calls", "implements", "extends", "uses_type") }
                .take(12)
            val impact = runCatching { impact(chosenNode.id, 2, snapshot) }.getOrNull()
            impactFiles += impact?.affected_files.orEmpty().take(6)
            notes += "chosen_node=${chosenNode.name}"
            if (impact != null) {
                notes += "impact_basis=${impact.analysis_basis.joinToString(",")}"
            } else {
                notes += "impact_basis=unavailable_for_backend"
            }
        } else {
            notes += "no_node_resolved"
        }

        if (clusters.isEmpty()) {
            clusters += summary.clusters.take(2)
        }

        val sourceBudget = budget - estimateBundleTokenUsage(
            summaryMode = summary.summary_mode,
            task = task,
            chosenNode = chosenNode,
            clusters = clusters,
            entrypoints = entrypoints,
            focusNodes = focusNodes,
            relationships = relationships,
            impactFiles = impactFiles,
            notes = notes,
        )
        var consumed = 0
        val orderedSourceIds = mutableListOf<String>().apply {
            chosenNodeId?.let { add(it) }
            addAll(focusNodes.map { it.id }.filter { it != chosenNodeId })
        }
        orderedSourceIds.forEach { id ->
            val source = source(id, 0, snapshot)
            val tokens = estimateTextTokens(source.source) + 24
            if (sourceSlices.isNotEmpty() && consumed + tokens > sourceBudget) return@forEach
            consumed += tokens
            sourceSlices += SourceBatchItem(id, source.source, source.file, source.line_range)
        }
        if (sourceSlices.size < orderedSourceIds.size) {
            notes += "source_slices_truncated_for_budget"
        }

        return ContextBundleResult(
            task = task,
            node_id = nodeId,
            chosen_node_id = chosenNodeId,
            token_budget = budget,
            summary_mode = summary.summary_mode,
            clusters = clusters.take(2),
            entrypoints = entrypoints,
            focus_nodes = focusNodes,
            relationships = relationships.distinctBy { Triple(it.from, it.to, it.relationship) },
            impact_files = impactFiles.distinct(),
            source_slices = sourceSlices,
            notes = notes,
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            semantic_level = semanticLevelFor(snapshot),
            snapshot_state = snapshotRuntimeState(snapshot),
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
        )
    }

    fun agentFitness(snapshot: Snapshot = current()): AgentFitnessResult {
        val report = buildFitnessReport(
            relevantMethods = snapshot.methodInfos.values.filter(::isAgentRelevantMethod),
            snapshot = snapshot,
            scopeLabel = null,
        )
        return AgentFitnessResult(
            overall_score = report.overall_score,
            subscores = report.subscores,
            metrics = report.metrics,
            issues = report.issues,
            recommended_actions = report.recommended_actions,
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
        )
    }

    fun clusterFitness(clusterId: String, snapshot: Snapshot = current()): ClusterFitnessResult {
        val cluster = snapshot.clusters.firstOrNull { it.cluster_id == clusterId }
            ?: error("Unknown cluster_id: $clusterId")
        val nodeIds = snapshot.clusterNodes[clusterId].orEmpty().toSet()
        val methods = snapshot.methodInfos.values.filter { it.id in nodeIds && isAgentRelevantMethod(it) }
        val report = buildFitnessReport(
            relevantMethods = methods,
            snapshot = snapshot,
            scopeLabel = cluster.label,
            allowedNodeIds = nodeIds,
        )
        return ClusterFitnessResult(
            cluster = cluster,
            overall_score = report.overall_score,
            subscores = report.subscores,
            metrics = report.metrics,
            issues = report.issues,
            recommended_actions = report.recommended_actions,
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
        )
    }

    fun validationTargets(
        nodeId: String? = null,
        editId: String? = null,
        snapshot: Snapshot = current(),
    ): ValidationTargetsResult {
        require(!nodeId.isNullOrBlank() || !editId.isNullOrBlank()) { "Provide node_id or edit_id" }
        val pending = editId?.let { pendingEdits[it] ?: error("Unknown edit_id: $it") }
        val resolvedNodeId = nodeId ?: pending?.targetNodeId
        require(!resolvedNodeId.isNullOrBlank()) { "Unable to resolve validation target node" }
        val targetNode = snapshot.nodeSummaries[resolvedNodeId]
        val impact = runCatching { impact(resolvedNodeId!!, 2, snapshot) }.getOrNull()
        val affectedFiles = listOfNotNull(targetNode?.file) + (pending?.affectedFiles ?: emptyList()) + impact?.affected_files.orEmpty()
        val distinctFiles = affectedFiles.distinct()
        val buildRoots = groupedBuildRoots(distinctFiles)
        val relatedTests = inferRelatedTests(resolvedNodeId, distinctFiles, snapshot)
        val validationItems = mutableListOf<ValidationTargetItem>()

        buildRoots.forEach { (root, files) ->
            val relative = projectRoot.relativize(root).toString().replace('\\', '/').ifBlank { "project_root" }
            validationItems += ValidationTargetItem(
                kind = "module",
                identifier = relative,
                file = null,
                confidence = if (root == projectRoot) 0.62 else 0.84,
                rationale = "Affected files map to this build root (${files.size} files).",
            )
        }
        relatedTests.forEach { test ->
            validationItems += ValidationTargetItem(
                kind = "test_file",
                identifier = test,
                file = test,
                confidence = 0.78,
                rationale = "Test name/layout matches affected production files or impacted classes.",
            )
        }

        val commandHints = buildValidationCommandHints(buildRoots.keys.toList(), relatedTests)

        return ValidationTargetsResult(
            target_node_id = resolvedNodeId,
            edit_id = editId,
            validation_targets = validationItems,
            command_hints = commandHints,
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            semantic_level = semanticLevelFor(snapshot),
            snapshot_state = snapshotRuntimeState(snapshot),
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
        )
    }

    private fun buildFitnessReport(
        relevantMethods: List<MethodInfo>,
        snapshot: Snapshot,
        scopeLabel: String?,
        allowedNodeIds: Set<String>? = null,
    ): AgentFitnessResult {
        val callEdges = snapshot.edges.filter { it.relationship == "calls" }
            .filter { edge ->
                allowedNodeIds == null || (edge.from in allowedNodeIds && edge.to in allowedNodeIds)
            }
        val outboundCounts = callEdges.groupingBy { it.from }.eachCount()
        val inboundCounts = callEdges.groupingBy { it.to }.eachCount()
        val avgFanOut = if (relevantMethods.isEmpty()) 0.0 else relevantMethods.map { outboundCounts.getOrDefault(it.id, 0) }.average()
        val avgInbound = if (relevantMethods.isEmpty()) 0.0 else relevantMethods.map { inboundCounts.getOrDefault(it.id, 0) }.average()
        val oversizedMethods = relevantMethods.filter { (it.loc >= 25) || (it.complexity >= 8) }
        val ambiguityIgnore = setOf("toString", "hashCode", "equals")
        val ambiguityGroups = relevantMethods
            .filter { it.visibility == "public" && it.simpleName !in ambiguityIgnore }
            .groupBy { it.simpleName }
            .filterValues { it.size > 1 }
        val ambiguousMethodCount = ambiguityGroups.values.sumOf { it.size }
        val externalEdgeRatio = when {
            scopeLabel != null -> {
                val cluster = snapshot.clusters.firstOrNull { it.label == scopeLabel || it.cluster_id == scopeLabel }
                cluster?.let { it.external_edges.toDouble() / it.node_count.coerceAtLeast(1) } ?: 0.0
            }
            else -> snapshot.clusters
                .takeIf { it.isNotEmpty() }
                ?.let { clusters -> clusters.sumOf { it.external_edges }.toDouble() / clusters.sumOf { it.node_count }.coerceAtLeast(1) }
                ?: 0.0
        }
        val sampleForImpact = relevantMethods
            .sortedWith(compareByDescending<MethodInfo> { inboundCounts.getOrDefault(it.id, 0) }.thenByDescending { entrypointPriority(it) })
            .take(8)
        val avgImpactRadius = if (sampleForImpact.isEmpty()) {
            0.0
        } else {
            sampleForImpact.map { impact(it.id, 2, snapshot).affected_files.count { file ->
                allowedNodeIds == null || snapshot.nodeSummaries.values.any { node -> node.id in allowedNodeIds && node.file == file }
            } }.average()
        }
        val buildRootRatios = sourceFileBuildRootStats(snapshot, relevantMethods.map { it.file }.distinct())
        val moduleScopedRatio = buildRootRatios["module_scoped_file_ratio"] ?: 0.0

        val navigability = scoreFitness(
            100.0
                - avgFanOut * 7.0
                - ambiguousMethodCount * 4.0
                - externalEdgeRatio * 12.0,
        )
        val editability = scoreFitness(
            100.0
                - oversizedMethods.size * 6.0
                - avgImpactRadius * 9.0
                - avgFanOut * 4.0,
        )
        val validationLocality = scoreFitness(
            45.0
                + moduleScopedRatio * 55.0
                - externalEdgeRatio * 10.0,
        )
        val couplingRisk = scoreFitness(
            100.0
                - externalEdgeRatio * 18.0
                - avgFanOut * 6.0
                - avgInbound * 3.0,
        )

        val issues = mutableListOf<FitnessIssue>()
        val actions = mutableListOf<FitnessAction>()

        oversizedMethods.sortedByDescending { it.loc + it.complexity * 3 }.take(3).forEach { method ->
            issues += FitnessIssue(
                severity = if ((method.loc >= 40) || (method.complexity >= 12)) "high" else "medium",
                title = "Oversized method",
                details = "${method.qualifiedName} has loc=${method.loc} and complexity=${method.complexity}, which makes targeted edits and impact reasoning less local.",
                node_id = method.id,
                file = method.file,
            )
            actions += FitnessAction(
                priority = "high",
                title = "Extract and localize ${method.simpleName}",
                rationale = "Splitting this method into smaller steps should improve edit targeting and reduce validation blast radius.",
                target_node_id = method.id,
                file = method.file,
            )
        }

        ambiguityGroups.entries.sortedByDescending { it.value.size }.take(3).forEach { (simpleName, methods) ->
            issues += FitnessIssue(
                severity = "medium",
                title = "Ambiguous method naming",
                details = "$simpleName appears in ${methods.size} agent-relevant methods across the repo, which raises edit-target ambiguity.",
                node_id = methods.first().id,
                file = methods.first().file,
            )
            actions += FitnessAction(
                priority = "medium",
                title = "Differentiate $simpleName variants",
                rationale = "More domain-specific method names reduce candidate ambiguity for graph-guided edits.",
                target_node_id = methods.first().id,
                file = methods.first().file,
            )
        }

        if (externalEdgeRatio > 0.75 && snapshot.clusters.isNotEmpty()) {
            val worstCluster = when {
                scopeLabel != null -> snapshot.clusters.firstOrNull { it.label == scopeLabel || it.cluster_id == scopeLabel }
                else -> snapshot.clusters.maxByOrNull { it.external_edges.toDouble() / it.node_count.coerceAtLeast(1) }
            }
            issues += FitnessIssue(
                severity = "medium",
                title = if (scopeLabel == null) "High cross-cluster coupling" else "Cluster boundary pressure",
                details = "External cluster edges per node are ${"%.2f".format(externalEdgeRatio)}, which weakens graph locality for agents.",
                file = worstCluster?.label,
            )
            actions += FitnessAction(
                priority = "medium",
                title = if (scopeLabel == null) "Reduce cluster boundary crossings" else "Reduce ${worstCluster?.label ?: "cluster"} boundary crossings",
                rationale = "Moving shared logic behind clearer interfaces should shrink impact radii and improve module-scoped validation.",
                file = worstCluster?.label,
            )
        }

        if (moduleScopedRatio < 0.25 && relevantMethods.size >= 10) {
            issues += FitnessIssue(
                severity = "low",
                title = "Low module-scoped validation coverage",
                details = "Only ${"%.0f".format(moduleScopedRatio * 100)}% of source files sit under a deeper build root than the project root.",
            )
            actions += FitnessAction(
                priority = "low",
                title = "Strengthen build-root locality",
                rationale = "Clearer submodule boundaries would let agent validation stay local more often.",
            )
        }

        val subscores = listOf(
            FitnessSubscore("navigability", navigability, "Lower fan-out and fewer ambiguous method names improve traversal confidence."),
            FitnessSubscore("editability", editability, "Smaller methods and narrower impact radii make graph-guided edits cheaper and safer."),
            FitnessSubscore("validation_locality", validationLocality, "Deeper build roots and lower coupling improve the odds of module-scoped validation."),
            FitnessSubscore("coupling_risk", couplingRisk, "Cross-cluster edges and high fan-in/fan-out widen the agent's blast radius."),
        )
        val overall = scoreFitness(subscores.map { it.score }.average())
        val metrics = linkedMapOf(
            "agent_relevant_method_count" to relevantMethods.size.toDouble(),
            "average_fan_out" to avgFanOut,
            "average_inbound_calls" to avgInbound,
            "average_impact_radius_files" to avgImpactRadius,
            "oversized_method_count" to oversizedMethods.size.toDouble(),
            "ambiguous_method_count" to ambiguousMethodCount.toDouble(),
            "external_edge_ratio" to externalEdgeRatio,
            "module_scoped_file_ratio" to moduleScopedRatio,
        )

        return AgentFitnessResult(
            overall_score = overall,
            subscores = subscores,
            metrics = metrics,
            issues = issues.take(6),
            recommended_actions = actions.distinctBy { it.title to it.target_node_id to it.file }.take(6),
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
            semantic_level = semanticLevelFor(snapshot),
            snapshot_state = snapshotRuntimeState(snapshot),
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
        return SearchResult(
            results = results,
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            semantic_level = semanticLevelFor(snapshot),
            snapshot_state = snapshotRuntimeState(snapshot),
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
        )
    }

    fun editCandidates(task: String, limit: Int, snapshot: Snapshot = current()): EditCandidatesResult {
        val normalizedTask = task.trim()
        val lowerTask = normalizedTask.lowercase()
        val requestedLimit = limit.coerceIn(1, 10)
        val methods = snapshot.methodInfos.values.filter { isAgentRelevantMethod(it) }
        val taskTerms = extractEditTaskTerms(normalizedTask)
        val preferredKinds = when {
            "repository" in lowerTask -> listOf("Repository")
            "controller" in lowerTask -> listOf("Controller")
            "service" in lowerTask -> listOf("Service")
            else -> emptyList()
        }
        val suggestedOperation = when {
            "rename" in lowerTask -> "rename_node"
            else -> "modify_method_body"
        }

        val candidates = methods
            .mapNotNull { method ->
                val node = snapshot.nodeSummaries[method.id] ?: return@mapNotNull null
                val nameLower = method.qualifiedName.lowercase()
                val simpleLower = method.simpleName.lowercase()
                val methodText = snapshot.sourceIndex[method.file]
                    ?.let(::readPathText)
                    ?.let { sourceSlice(it, method.lineRange) }
                    ?.lowercase()
                    .orEmpty()
                var score = 0
                val rationaleParts = mutableListOf<String>()

                taskTerms.forEach { term ->
                    when {
                        simpleLower == term -> {
                            score += 80
                            rationaleParts += "exact method-name match for '$term'"
                        }
                        simpleLower.contains(term) -> {
                            score += 45
                            rationaleParts += "method name contains '$term'"
                        }
                        nameLower.contains(term) -> {
                            score += 25
                            rationaleParts += "qualified name contains '$term'"
                        }
                    }
                }

                if (preferredKinds.any { it in method.parentQualifiedName }) {
                    score += 30
                    rationaleParts += "owner type matches requested subsystem"
                }
                payloadAnchor(lowerTask)?.let { anchor ->
                    if (methodText.contains(anchor.lowercase())) {
                        score += 120
                        rationaleParts += "method body contains requested anchor"
                    }
                }
                payloadSnippet(lowerTask)?.let { snippet ->
                    if (methodText.contains(snippet.lowercase())) {
                        score -= 20
                    }
                }
                if (suggestedOperation == "modify_method_body") {
                    score += entrypointPriority(method).coerceAtLeast(0)
                }
                if ("before" in lowerTask || "after" in lowerTask || "insert" in lowerTask || "replace" in lowerTask) {
                    score += 10
                }
                if (score <= 0) return@mapNotNull null

                val suggestedPayload = when (suggestedOperation) {
                    "rename_node" -> inferRenamePayload(normalizedTask, method)
                    else -> inferMethodPatchPayload(normalizedTask)
                }

                EditCandidate(
                    node = node,
                    suggested_operation = suggestedOperation,
                    rationale = rationaleParts.distinct().take(3).ifEmpty { listOf("name and subsystem heuristics matched the task") }.joinToString("; "),
                    suggested_payload = suggestedPayload,
                    score = score,
                    confidence = candidateConfidence(score, rationaleParts),
                )
            }
            .sortedWith(compareByDescending<EditCandidate> { it.score }.thenBy { it.node.name })
            .take(requestedLimit)

        return EditCandidatesResult(
            task = normalizedTask,
            candidates = candidates,
            needs_disambiguation = needsDisambiguation(candidates),
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            semantic_level = semanticLevelFor(snapshot),
            snapshot_state = snapshotRuntimeState(snapshot),
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
        )
    }

    fun verifyCandidate(
        task: String,
        nodeId: String,
        payload: EditRequestPayload,
        snapshot: Snapshot = current(),
    ): VerifyCandidateResult {
        val node = snapshot.nodeSummaries[nodeId] ?: error("Unknown node_id: $nodeId")
        val method = snapshot.methodInfos[nodeId] ?: error("verify_candidate currently supports method nodes only")
        val suggestedOperation = if (!payload.new_name.isNullOrBlank() || "rename" in task.lowercase()) "rename_node" else "modify_method_body"
        val sourceText = snapshot.sourceIndex[method.file]
            ?.let(::readPathText)
            ?.let { sourceSlice(it, method.lineRange) }
            .orEmpty()
        val anchor = payload.anchor
        val snippet = payload.snippet ?: payload.new_body
        val anchorPresent = anchor?.let { sourceText.contains(it) } ?: false
        val snippetPresent = snippet?.let { sourceText.contains(it) } ?: false
        val nameMatch = extractEditTaskTerms(task).any { term ->
            method.simpleName.contains(term, ignoreCase = true) || method.qualifiedName.contains(term, ignoreCase = true)
        }
        val notes = mutableListOf<String>()
        if (nameMatch) notes += "task terms match the method name"
        if (anchorPresent) notes += "anchor text exists in the candidate source"
        if (snippetPresent) notes += "snippet already exists in the candidate source"
        if (!anchorPresent && anchor != null) notes += "anchor text was not found in the candidate source"
        val confidence = when {
            anchorPresent && !snippetPresent -> 0.92
            anchorPresent -> 0.78
            nameMatch -> 0.56
            else -> 0.28
        }
        return VerifyCandidateResult(
            task = task,
            node = node,
            suggested_operation = suggestedOperation,
            suggested_payload = buildMap {
                payload.patch_mode?.let { put("patch_mode", it) }
                payload.anchor?.let { put("anchor", it) }
                payload.snippet?.let { put("snippet", it) }
                payload.new_name?.let { put("new_name", it) }
                payload.new_body?.let { put("new_body", it) }
            },
            anchor_present = anchorPresent,
            snippet_present = snippetPresent,
            name_match = nameMatch,
            confidence = confidence,
            verification_notes = notes,
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            semantic_level = semanticLevelFor(snapshot),
            snapshot_state = snapshotRuntimeState(snapshot),
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
        )
    }

    fun resolveEditTarget(
        task: String,
        limit: Int,
        snapshot: Snapshot = current(),
    ): ResolveEditTargetResult {
        val candidatesResult = editCandidates(task, limit, snapshot)
        if (candidatesResult.candidates.isEmpty()) {
            return ResolveEditTargetResult(
                task = task,
                needs_disambiguation = true,
                analysis_engine = snapshot.analysisEngine,
                engine_version = snapshot.engineVersion,
                build_duration_ms = snapshot.buildDurationMs,
                semantic_level = semanticLevelFor(snapshot),
                snapshot_state = snapshotRuntimeState(snapshot),
                snapshot_id = snapshot.id,
                generated_at = snapshot.generatedAt,
            )
        }

        val verified = candidatesResult.candidates.map { candidate ->
            verifyCandidate(
                task = task,
                nodeId = candidate.node.id,
                payload = EditRequestPayload(
                    patch_mode = candidate.suggested_payload["patch_mode"],
                    anchor = candidate.suggested_payload["anchor"],
                    snippet = candidate.suggested_payload["snippet"],
                    new_name = candidate.suggested_payload["new_name"],
                    new_body = candidate.suggested_payload["new_body"],
                ),
                snapshot = snapshot,
            )
        }

        val ranked = candidatesResult.candidates.zip(verified)
            .sortedWith(
                compareByDescending<Pair<EditCandidate, VerifyCandidateResult>> { it.second.anchor_present }
                    .thenByDescending { it.second.confidence }
                    .thenByDescending { it.first.confidence }
                    .thenByDescending { it.first.score },
            )

        val resolved = ranked.firstOrNull()
        val resolvedCandidate = resolved?.first
        val resolvedVerification = resolved?.second
        val needsDisambiguation = when {
            resolvedCandidate == null || resolvedVerification == null -> true
            resolvedVerification.confidence < 0.65 -> true
            ranked.size > 1 && ranked[0].second.confidence == ranked[1].second.confidence && ranked[0].second.anchor_present == ranked[1].second.anchor_present -> true
            else -> candidatesResult.needs_disambiguation
        }

        return ResolveEditTargetResult(
            task = task,
            resolved_candidate = resolvedCandidate,
            verification = resolvedVerification,
            rejected_candidates = ranked.drop(1).map { it.second },
            needs_disambiguation = needsDisambiguation,
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            semantic_level = semanticLevelFor(snapshot),
            snapshot_state = snapshotRuntimeState(snapshot),
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
        )
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
            semantic_level = semanticLevelFor(snapshot),
            snapshot_state = snapshotRuntimeState(snapshot),
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
        requireDeterministicTool("get_call_paths", snapshot)
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
            semantic_level = semanticLevelFor(snapshot),
            snapshot_state = snapshotRuntimeState(snapshot),
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
            semantic_level = semanticLevelFor(snapshot),
            snapshot_state = snapshotRuntimeState(snapshot),
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
            analysis_confidence = 0.62,
            known_blind_spots = DEFAULT_BLIND_SPOTS,
            latency_ms = elapsedMs(start),
        )
    }

    fun implementations(nodeId: String, snapshot: Snapshot = current()): TraversalResult {
        requireDeterministicTool("get_implementations", snapshot)
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
            semantic_level = semanticLevelFor(snapshot),
            snapshot_state = snapshotRuntimeState(snapshot),
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
            semantic_level = semanticLevelFor(snapshot),
            snapshot_state = snapshotRuntimeState(snapshot),
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
            semantic_level = semanticLevelFor(snapshot),
            snapshot_state = snapshotRuntimeState(snapshot),
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
            analysis_confidence = 0.66,
            known_blind_spots = DEFAULT_BLIND_SPOTS,
            latency_ms = elapsedMs(start),
        )
    }

    fun impact(nodeId: String, maxDepth: Int, snapshot: Snapshot = current()): ImpactResult {
        requireDeterministicTool("get_impact", snapshot)
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
            semantic_level = semanticLevelFor(snapshot),
            snapshot_state = snapshotRuntimeState(snapshot),
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
            semantic_level = semanticLevelFor(snapshot),
            snapshot_state = snapshotRuntimeState(snapshot),
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
        )
    }

    fun sourceBatch(nodeIds: List<String>, snapshot: Snapshot = current()): SourceBatchResult {
        val items = nodeIds.map { nodeId ->
            val item = source(nodeId, 0, snapshot)
            SourceBatchItem(nodeId, item.source, item.file, item.line_range)
        }
        return SourceBatchResult(
            sources = items,
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            semantic_level = semanticLevelFor(snapshot),
            snapshot_state = snapshotRuntimeState(snapshot),
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
        )
    }

    fun planEdit(
        operation: String,
        targetNodeId: String,
        payload: EditRequestPayload,
        snapshot: Snapshot = current(),
        rebasedFromEditId: String? = null,
    ): EditPlanResult {
        val validationErrors = mutableListOf<String>()
        if (operation !in setOf("modify_method_body", "rename_node")) {
            validationErrors += "Unsupported operation: $operation"
        }
        return when (operation) {
            "modify_method_body" -> planModifyMethodBody(targetNodeId, payload, snapshot, validationErrors, rebasedFromEditId)
            "rename_node" -> planRenameNode(targetNodeId, payload, snapshot, validationErrors, rebasedFromEditId)
            else -> {
                validationErrors += "Unsupported operation: $operation"
                EditPlanResult(
                    operation = operation,
                    target_node_id = targetNodeId,
                    rebased_from_edit_id = rebasedFromEditId,
                    diff = "",
                    affected_nodes = emptyList(),
                    affected_files = emptyList(),
                    validation_errors = validationErrors,
                    analysis_engine = snapshot.analysisEngine,
                    engine_version = snapshot.engineVersion,
                    build_duration_ms = snapshot.buildDurationMs,
                    snapshot_id = snapshot.id,
                    generated_at = snapshot.generatedAt,
                )
            }
        }
    }

    fun rebaseEditPlan(editId: String, snapshot: Snapshot = current()): EditPlanResult {
        val pending = pendingEdits[editId]
            ?: return EditPlanResult(
                operation = "unknown",
                target_node_id = "unknown",
                rebased_from_edit_id = editId,
                diff = "",
                affected_nodes = emptyList(),
                affected_files = emptyList(),
                validation_errors = listOf("Unknown edit_id: $editId"),
                analysis_engine = snapshot.analysisEngine,
                engine_version = snapshot.engineVersion,
                build_duration_ms = snapshot.buildDurationMs,
                snapshot_id = snapshot.id,
                generated_at = snapshot.generatedAt,
            )
        if (pending.applied) {
            return EditPlanResult(
                operation = pending.operation,
                target_node_id = pending.targetNodeId,
                rebased_from_edit_id = editId,
                diff = "",
                affected_nodes = pending.affectedNodeIds,
                affected_files = pending.affectedFiles,
                validation_errors = listOf("Cannot rebase an already applied edit: $editId"),
                analysis_engine = snapshot.analysisEngine,
                engine_version = snapshot.engineVersion,
                build_duration_ms = snapshot.buildDurationMs,
                snapshot_id = snapshot.id,
                generated_at = snapshot.generatedAt,
            )
        }
        return planEdit(
            operation = pending.operation,
            targetNodeId = pending.targetNodeId,
            payload = pending.payload,
            snapshot = snapshot,
            rebasedFromEditId = editId,
        )
    }

    private fun planModifyMethodBody(
        targetNodeId: String,
        payload: EditRequestPayload,
        snapshot: Snapshot,
        validationErrors: MutableList<String>,
        rebasedFromEditId: String?,
    ): EditPlanResult {
        val method = snapshot.methodInfos[targetNodeId]
        if (method == null) {
            validationErrors += "target_node_id must reference a method node"
        }
        val patchMode = payload.patch_mode ?: "replace_body"
        if (patchMode == "replace_body" && payload.new_body.isNullOrBlank()) {
            validationErrors += "payload.new_body must not be blank"
        }
        if (patchMode != "replace_body" && payload.snippet.isNullOrBlank()) {
            validationErrors += "payload.snippet must not be blank for patch mode $patchMode"
        }
        if (patchMode != "replace_body" && payload.anchor.isNullOrBlank()) {
            validationErrors += "payload.anchor must not be blank for patch mode $patchMode"
        }
        if (validationErrors.isNotEmpty()) {
            return EditPlanResult(
                operation = "modify_method_body",
                target_node_id = targetNodeId,
                rebased_from_edit_id = rebasedFromEditId,
                diff = "",
                affected_nodes = emptyList(),
                affected_files = emptyList(),
                validation_errors = validationErrors,
                analysis_engine = snapshot.analysisEngine,
                engine_version = snapshot.engineVersion,
                build_duration_ms = snapshot.buildDurationMs,
                snapshot_id = snapshot.id,
                generated_at = snapshot.generatedAt,
            )
        }

        val targetMethod = method!!
        val path = snapshot.sourceIndex[targetMethod.file] ?: error("Missing file for node_id: $targetNodeId")
        val fileSource = readPathText(path)
        val bodyRange = methodBodyRange(fileSource, targetMethod.lineRange.start, targetMethod.lineRange.end)
        if (bodyRange == null) {
            return EditPlanResult(
                operation = "modify_method_body",
                target_node_id = targetNodeId,
                rebased_from_edit_id = rebasedFromEditId,
                diff = "",
                affected_nodes = emptyList(),
                affected_files = listOf(targetMethod.file),
                validation_errors = listOf("Could not locate method body braces for target node"),
                analysis_engine = snapshot.analysisEngine,
                engine_version = snapshot.engineVersion,
                build_duration_ms = snapshot.buildDurationMs,
                snapshot_id = snapshot.id,
                generated_at = snapshot.generatedAt,
            )
        }

        val methodSource = source(targetNodeId, 0, snapshot)
        val newFileSource = runCatching { patchMethodBody(fileSource, bodyRange, payload) }.getOrElse { err ->
            return EditPlanResult(
                operation = "modify_method_body",
                target_node_id = targetNodeId,
                rebased_from_edit_id = rebasedFromEditId,
                diff = "",
                affected_nodes = emptyList(),
                affected_files = listOf(targetMethod.file),
                validation_errors = listOf(err.message ?: "Failed to patch method body"),
                analysis_engine = snapshot.analysisEngine,
                engine_version = snapshot.engineVersion,
                build_duration_ms = snapshot.buildDurationMs,
                snapshot_id = snapshot.id,
                generated_at = snapshot.generatedAt,
            )
        }
        val newMethodEnd = findBlockEndLine(newFileSource, targetMethod.lineRange.start) ?: targetMethod.lineRange.end
        val newMethodRange = SourceRange(targetMethod.lineRange.start, newMethodEnd)
        val newMethodSource = sourceSlice(newFileSource, newMethodRange)
        val diff = if (patchMode == "replace_body") {
            buildMethodDiff(targetMethod.file, targetMethod.lineRange, methodSource.source, newMethodSource)
        } else {
            buildAnchorPatchDiff(
                file = targetMethod.file,
                methodSource = methodSource.source,
                methodStartLine = targetMethod.lineRange.start,
                anchor = payload.anchor!!,
                snippet = payload.snippet!!,
                mode = patchMode,
            )
        }
        val impact = runCatching { impact(targetNodeId, 2, snapshot) }.getOrNull()
        val affectedNodeIds = (listOf(targetNodeId) + impact?.affected_nodes.orEmpty().map { it.id }).distinct()
        val affectedFiles = (listOf(targetMethod.file) + impact?.affected_files.orEmpty()).distinct()
        val editId = makeEditId()

        pendingEdits[editId] = PendingEdit(
            id = editId,
            operation = "modify_method_body",
            targetNodeId = targetNodeId,
            snapshotId = snapshot.id,
            payload = payload,
            fileEdits = listOf(
                PendingFileEdit(
                    file = targetMethod.file,
                    fileHash = sha256(fileSource),
                    newFileContent = newFileSource,
                ),
            ),
            affectedNodeIds = affectedNodeIds,
            affectedFiles = affectedFiles,
        )

        return EditPlanResult(
            edit_id = editId,
            operation = "modify_method_body",
            target_node_id = targetNodeId,
            rebased_from_edit_id = rebasedFromEditId,
            diff = diff,
            affected_nodes = affectedNodeIds,
            affected_files = affectedFiles,
            validation_errors = emptyList(),
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
        )
    }

    private fun planRenameNode(
        targetNodeId: String,
        payload: EditRequestPayload,
        snapshot: Snapshot,
        validationErrors: MutableList<String>,
        rebasedFromEditId: String?,
    ): EditPlanResult {
        val method = snapshot.methodInfos[targetNodeId]
        if (method == null) {
            validationErrors += "rename_node currently supports method nodes only"
        }
        val newName = payload.new_name?.trim().orEmpty()
        if (newName.isBlank()) {
            validationErrors += "payload.new_name must not be blank"
        }
        if (!Regex("""[A-Za-z_]\w*""").matches(newName)) {
            validationErrors += "payload.new_name must be a valid Java identifier"
        }
        if (validationErrors.isNotEmpty()) {
            return EditPlanResult(
                operation = "rename_node",
                target_node_id = targetNodeId,
                rebased_from_edit_id = rebasedFromEditId,
                diff = "",
                affected_nodes = emptyList(),
                affected_files = emptyList(),
                validation_errors = validationErrors,
                analysis_engine = snapshot.analysisEngine,
                engine_version = snapshot.engineVersion,
                build_duration_ms = snapshot.buildDurationMs,
                snapshot_id = snapshot.id,
                generated_at = snapshot.generatedAt,
            )
        }

        val targetMethod = method!!
        val renamePattern = Regex("""\b${Regex.escape(targetMethod.simpleName)}\s*\(""")
        val fileEdits = mutableListOf<PendingFileEdit>()
        val diffParts = mutableListOf<String>()

        snapshot.sourceIndex.forEach { (file, path) ->
            val original = readPathText(path)
            if (!renamePattern.containsMatchIn(original)) return@forEach
            val updated = renamePattern.replace(original) { match ->
                match.value.replace(targetMethod.simpleName, newName)
            }
            if (updated != original) {
                fileEdits += PendingFileEdit(file, sha256(original), updated)
                diffParts += buildRenameDiff(file, original, updated)
            }
        }

        if (fileEdits.isEmpty()) {
            return EditPlanResult(
                operation = "rename_node",
                target_node_id = targetNodeId,
                rebased_from_edit_id = rebasedFromEditId,
                diff = "",
                affected_nodes = emptyList(),
                affected_files = emptyList(),
                validation_errors = listOf("No rename candidates found for ${targetMethod.simpleName}"),
                analysis_engine = snapshot.analysisEngine,
                engine_version = snapshot.engineVersion,
                build_duration_ms = snapshot.buildDurationMs,
                snapshot_id = snapshot.id,
                generated_at = snapshot.generatedAt,
            )
        }

        val impact = runCatching { impact(targetNodeId, 2, snapshot) }.getOrNull()
        val editId = makeEditId()
        val affectedFiles = (fileEdits.map { it.file } + impact?.affected_files.orEmpty()).distinct()
        val affectedNodes = (listOf(targetNodeId) + impact?.affected_nodes.orEmpty().map { it.id }).distinct()
        pendingEdits[editId] = PendingEdit(
            id = editId,
            operation = "rename_node",
            targetNodeId = targetNodeId,
            snapshotId = snapshot.id,
            payload = payload.copy(new_name = newName),
            fileEdits = fileEdits,
            affectedNodeIds = affectedNodes,
            affectedFiles = affectedFiles,
        )

        return EditPlanResult(
            edit_id = editId,
            operation = "rename_node",
            target_node_id = targetNodeId,
            rebased_from_edit_id = rebasedFromEditId,
            diff = diffParts.joinToString("\n"),
            affected_nodes = affectedNodes,
            affected_files = affectedFiles,
            validation_errors = emptyList(),
            analysis_engine = snapshot.analysisEngine,
            engine_version = snapshot.engineVersion,
            build_duration_ms = snapshot.buildDurationMs,
            snapshot_id = snapshot.id,
            generated_at = snapshot.generatedAt,
        )
    }

    fun applyEdit(editId: String): EditApplyResult {
        val snapshot = current()
        val pending = pendingEdits[editId]
            ?: return EditApplyResult(
                success = false,
                edit_id = editId,
                updated_nodes = emptyList(),
                affected_files = emptyList(),
                validation_errors = listOf("Unknown edit_id: $editId"),
                analysis_engine = snapshot.analysisEngine,
                engine_version = snapshot.engineVersion,
                build_duration_ms = snapshot.buildDurationMs,
                snapshot_id = snapshot.id,
                generated_at = snapshot.generatedAt,
            )
        if (pending.applied) {
            return EditApplyResult(
                success = false,
                edit_id = editId,
                rebased_suggestion = null,
                updated_nodes = pending.affectedNodeIds.filter { it in snapshot.nodeSummaries },
                affected_files = pending.affectedFiles,
                validation_errors = listOf("Edit already applied: $editId"),
                analysis_engine = snapshot.analysisEngine,
                engine_version = snapshot.engineVersion,
                build_duration_ms = snapshot.buildDurationMs,
                snapshot_id = snapshot.id,
                generated_at = snapshot.generatedAt,
            )
        }
        if (pending.snapshotId != snapshot.id) {
            return EditApplyResult(
                success = false,
                edit_id = editId,
                rebased_suggestion = "Call rebase_edit_plan for edit_id=$editId before apply_edit",
                updated_nodes = emptyList(),
                affected_files = pending.affectedFiles,
                validation_errors = listOf("stale_snapshot: plan was created on snapshot ${pending.snapshotId}, active snapshot is ${snapshot.id}"),
                analysis_engine = snapshot.analysisEngine,
                engine_version = snapshot.engineVersion,
                build_duration_ms = snapshot.buildDurationMs,
                snapshot_id = snapshot.id,
                generated_at = snapshot.generatedAt,
            )
        }

        pending.fileEdits.forEach { fileEdit ->
            val path = snapshot.sourceIndex[fileEdit.file] ?: projectRoot.resolve(fileEdit.file).normalize()
            val currentContent = readPathText(path)
            if (sha256(currentContent) != fileEdit.fileHash) {
                return EditApplyResult(
                    success = false,
                    edit_id = editId,
                    rebased_suggestion = "Call rebase_edit_plan for edit_id=$editId before apply_edit",
                    updated_nodes = emptyList(),
                    affected_files = pending.affectedFiles,
                    validation_errors = listOf("stale_snapshot: source file changed since plan_edit"),
                    analysis_engine = snapshot.analysisEngine,
                    engine_version = snapshot.engineVersion,
                    build_duration_ms = snapshot.buildDurationMs,
                    snapshot_id = snapshot.id,
                    generated_at = snapshot.generatedAt,
                )
            }
        }

        pending.fileEdits.forEach { fileEdit ->
            val path = snapshot.sourceIndex[fileEdit.file] ?: projectRoot.resolve(fileEdit.file).normalize()
            Files.writeString(path, fileEdit.newFileContent)
        }
        val refreshed = buildSnapshot()
        installSnapshot(refreshed)
        pendingEdits[editId] = pending.copy(applied = true)

        return EditApplyResult(
            success = true,
            edit_id = editId,
            updated_nodes = pending.affectedNodeIds.filter { it in refreshed.nodeSummaries },
            affected_files = pending.affectedFiles,
            validation_errors = emptyList(),
            analysis_engine = refreshed.analysisEngine,
            engine_version = refreshed.engineVersion,
            build_duration_ms = refreshed.buildDurationMs,
            snapshot_id = refreshed.id,
            generated_at = refreshed.generatedAt,
        )
    }

    fun validateEdit(
        editId: String,
        mode: String = "auto",
        snapshot: Snapshot = current(),
    ): EditValidationResult {
        val pending = pendingEdits[editId]
            ?: return EditValidationResult(
                success = false,
                edit_id = editId,
                validation_mode = mode,
                validation_scope = "unknown",
                validation_target = "unknown",
                validator = "none",
                attempted_validators = emptyList(),
                degraded = false,
                command = emptyList(),
                exit_code = -1,
                duration_ms = 0,
                affected_files = emptyList(),
                output_excerpt = "",
                validation_errors = listOf("Unknown edit_id: $editId"),
                analysis_engine = snapshot.analysisEngine,
                engine_version = snapshot.engineVersion,
                build_duration_ms = snapshot.buildDurationMs,
                snapshot_id = snapshot.id,
                generated_at = snapshot.generatedAt,
            )

        val validationRoot = createValidationScratchRoot()
        val validationScope = if (pending.applied) "applied_workspace" else "planned_edit"
        return try {
            copyProjectTree(projectRoot, validationRoot)
            if (!pending.applied) {
                pending.fileEdits.forEach { fileEdit ->
                    val target = validationRoot.resolve(fileEdit.file).normalize()
                    target.parent?.createDirectories()
                    Files.writeString(target, fileEdit.newFileContent)
                }
            }

            val plannedTargets = validationTargets(editId = editId, snapshot = snapshot)
            val validationTarget = plannedTargets.validation_targets
                .firstOrNull { it.kind == "module" }
                ?.let { item -> validationTargetFromItem(validationRoot, item) }
                ?: determineValidationTarget(validationRoot, pending.fileEdits.map { it.file })
            val relatedTests = plannedTargets.validation_targets
                .filter { it.kind == "test_file" }
                .mapNotNull { it.file }
                .distinct()
            val selected = plannedTargets.command_hints
                .asSequence()
                .mapNotNull { commandHintToValidationCommand(validationRoot, it, mode) }
                .firstOrNull()
                ?: selectValidationCommand(validationRoot, validationTarget, mode, relatedTests)
            val start = System.nanoTime()
            val attempted = mutableListOf(selected.validator)
            val primaryResult = runValidationCommand(validationRoot, selected)
            val finalOutcome = if (shouldFallbackValidation(selected, primaryResult, mode, validationTarget.root)) {
                val fallback = ValidationCommand(
                    validator = "javac-syntax",
                    command = javacCommand(validationTarget.root),
                    workingDirectory = validationTarget.root,
                )
                attempted += fallback.validator
                val fallbackResult = runValidationCommand(validationRoot, fallback)
                if (!fallbackResult.timedOut && fallbackResult.exitCode == 0) {
                    ValidationOutcome(
                        validator = fallback,
                        result = fallbackResult,
                        degraded = true,
                        notes = listOf(
                            "Primary validation via ${selected.validator} was blocked or failed; javac syntax fallback passed",
                        ),
                    )
                } else {
                    ValidationOutcome(
                        validator = selected,
                        result = primaryResult,
                        degraded = true,
                        notes = buildFallbackNotes(selected, primaryResult, fallback, fallbackResult),
                    )
                }
            } else {
                ValidationOutcome(
                    validator = selected,
                    result = primaryResult,
                    degraded = false,
                    notes = emptyList(),
                )
            }
            val durationMs = elapsedMs(start)
            EditValidationResult(
                success = !finalOutcome.result.timedOut && finalOutcome.result.exitCode == 0,
                edit_id = editId,
                validation_mode = mode,
                validation_scope = validationScope,
                validation_target = validationTarget.label,
                validator = finalOutcome.validator.validator,
                attempted_validators = attempted,
                degraded = finalOutcome.degraded,
                command = finalOutcome.validator.command,
                exit_code = if (finalOutcome.result.timedOut) -1 else finalOutcome.result.exitCode,
                duration_ms = durationMs,
                affected_files = pending.affectedFiles,
                output_excerpt = summarizeValidationOutput(finalOutcome),
                validation_errors = buildValidationErrors(finalOutcome),
                analysis_engine = snapshot.analysisEngine,
                engine_version = snapshot.engineVersion,
                build_duration_ms = snapshot.buildDurationMs,
                snapshot_id = snapshot.id,
                generated_at = snapshot.generatedAt,
            )
        } finally {
            deleteRecursively(validationRoot)
        }
    }

    private fun createValidationScratchRoot(): Path =
        Files.createTempDirectory("graphharness-validate-")

    private fun validationTargetFromItem(root: Path, item: ValidationTargetItem): ValidationTarget {
        val label = when {
            item.kind == "module" && item.identifier != "project_root" && !item.identifier.startsWith("module:") -> "module:${item.identifier}"
            else -> item.identifier
        }
        val targetRoot = when {
            label == "project_root" -> root
            label.startsWith("module:") -> root.resolve(label.removePrefix("module:")).normalize()
            else -> root.resolve(label).normalize()
        }
        return ValidationTarget(targetRoot, label)
    }

    private fun determineValidationTarget(root: Path, touchedFiles: List<String>): ValidationTarget {
        val buildFiles = setOf("pom.xml", "build.gradle", "build.gradle.kts")
        val candidates = touchedFiles.mapNotNull { file ->
            var current: Path? = root.resolve(file).normalize().parent
            var best: Path? = null
            while (current != null && current.startsWith(root)) {
                if (best == null && buildFiles.any { Files.exists(current.resolve(it)) }) {
                    best = current
                }
                if (current == root) break
                current = current.parent
            }
            best
        }
        val chosen = when {
            candidates.isEmpty() -> root
            else -> deepestCommonPath(candidates) ?: candidates.minByOrNull { root.relativize(it).nameCount } ?: root
        }
        val relative = root.relativize(chosen).toString().replace('\\', '/')
        val label = if (relative.isBlank()) "project_root" else "module:$relative"
        return ValidationTarget(chosen, label)
    }

    private fun deepestCommonPath(paths: List<Path>): Path? {
        if (paths.isEmpty()) return null
        var current = paths.first()
        while (true) {
            if (paths.all { it.startsWith(current) }) {
                return current
            }
            current = current.parent ?: return null
        }
    }

    private fun sourceFileBuildRootStats(snapshot: Snapshot, files: List<String> = snapshot.sourceIndex.keys.toList()): Map<String, Double> {
        if (files.isEmpty()) {
            return mapOf("module_scoped_file_ratio" to 0.0, "build_root_count" to 0.0)
        }
        val buildFiles = setOf("pom.xml", "build.gradle", "build.gradle.kts")
        val roots = files.map { file ->
            var current: Path? = projectRoot.resolve(file).normalize().parent
            var best: Path = projectRoot
            while (current != null && current.startsWith(projectRoot)) {
                if (best == projectRoot && buildFiles.any { Files.exists(current.resolve(it)) }) {
                    best = current
                }
                if (current == projectRoot) break
                current = current.parent
            }
            best
        }
        val moduleScoped = roots.count { it != projectRoot }
        return mapOf(
            "module_scoped_file_ratio" to (moduleScoped.toDouble() / roots.size.coerceAtLeast(1)),
            "build_root_count" to roots.distinct().size.toDouble(),
        )
    }

    private fun scoreFitness(raw: Double): Int =
        raw.toInt().coerceIn(0, 100)

    private fun groupedBuildRoots(files: List<String>): Map<Path, List<String>> =
        files.groupBy { file ->
            var current: Path? = projectRoot.resolve(file).normalize().parent
            var best: Path = projectRoot
            val buildFiles = setOf("pom.xml", "build.gradle", "build.gradle.kts")
            while (current != null && current.startsWith(projectRoot)) {
                if (best == projectRoot && buildFiles.any { Files.exists(current.resolve(it)) }) {
                    best = current
                }
                if (current == projectRoot) break
                current = current.parent
            }
            best
        }

    private fun inferRelatedTests(nodeId: String, affectedFiles: List<String>, snapshot: Snapshot): List<String> {
        val node = snapshot.nodeSummaries[nodeId]
        val candidateNames = buildSet {
            node?.name?.substringAfterLast('.')?.let { add(it) }
            affectedFiles.forEach { file ->
                val stem = Path.of(file).fileName.toString().removeSuffix(".java")
                add(stem)
                add(stem.removeSuffix("Controller"))
                add(stem.removeSuffix("Service"))
                add(stem.removeSuffix("Repository"))
            }
        }.filter { it.isNotBlank() }
        return snapshot.sourceIndex.keys
            .filter { it.contains("/test/") || it.endsWith("Test.java") || it.endsWith("Tests.java") }
            .filter { testFile ->
                val testStem = Path.of(testFile).fileName.toString().removeSuffix(".java")
                candidateNames.any { name -> testStem.contains(name, ignoreCase = true) }
            }
            .sorted()
            .take(8)
    }

    private fun buildValidationCommandHints(buildRoots: List<Path>, relatedTests: List<String>): List<ValidationCommandHint> {
        val hints = mutableListOf<ValidationCommandHint>()
        buildRoots.distinct().forEach { root ->
            val relative = projectRoot.relativize(root).toString().replace('\\', '/')
            val workingDirectory = root.toString()
            val scopedTests = relatedTests.filter { projectRoot.resolve(it).normalize().startsWith(root) }
            val testSelectors = scopedTests.map { Path.of(it).fileName.toString().removeSuffix(".java") }.distinct()
            if (testSelectors.isNotEmpty() && Files.exists(root.resolve("mvnw"))) {
                hints += ValidationCommandHint(
                    label = if (relative.isBlank()) "targeted-test" else "targeted-test:$relative",
                    command = listOf("bash", "./mvnw", "-q", "-Dtest=${testSelectors.joinToString(",")}", "test"),
                    working_directory = workingDirectory,
                )
            } else if (testSelectors.isNotEmpty() && Files.exists(root.resolve("gradlew"))) {
                hints += ValidationCommandHint(
                    label = if (relative.isBlank()) "targeted-test" else "targeted-test:$relative",
                    command = listOf("bash", "./gradlew", "--no-daemon", "test") + testSelectors.flatMap { listOf("--tests", it) },
                    working_directory = workingDirectory,
                )
            } else if (Files.exists(root.resolve("mvnw"))) {
                hints += ValidationCommandHint(
                    label = if (relative.isBlank()) "module-test" else "module-test:$relative",
                    command = listOf("bash", "./mvnw", "-q", "test"),
                    working_directory = workingDirectory,
                )
            } else if (Files.exists(root.resolve("gradlew"))) {
                hints += ValidationCommandHint(
                    label = if (relative.isBlank()) "module-test" else "module-test:$relative",
                    command = listOf("bash", "./gradlew", "--no-daemon", "test"),
                    working_directory = workingDirectory,
                )
            } else if (Files.exists(root.resolve("pom.xml"))) {
                hints += ValidationCommandHint(
                    label = if (relative.isBlank()) "module-compile" else "module-compile:$relative",
                    command = listOf("mvn", "-q", "-DskipTests", "compile"),
                    working_directory = workingDirectory,
                )
            } else if (Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"))) {
                hints += ValidationCommandHint(
                    label = if (relative.isBlank()) "module-compile" else "module-compile:$relative",
                    command = listOf("gradle", "--no-daemon", "compileJava"),
                    working_directory = workingDirectory,
                )
            } else {
                hints += ValidationCommandHint(
                    label = if (relative.isBlank()) "module-syntax" else "module-syntax:$relative",
                    command = listOf("javac", "-proc:none", "<java-files>"),
                    working_directory = workingDirectory,
                )
            }
        }
        relatedTests.take(5).forEach { testFile ->
            hints += ValidationCommandHint(
                label = "likely-test:${Path.of(testFile).fileName}",
                command = listOf("run", testFile),
                working_directory = projectRoot.toString(),
            )
        }
        return hints.distinctBy { it.label to it.working_directory }
    }

    private fun copyProjectTree(sourceRoot: Path, targetRoot: Path) {
        val excludedNames = setOf(".git", ".gradle", "build", "target", ".idea")
        Files.walk(sourceRoot).use { paths ->
            paths.forEach { source ->
                val relative = sourceRoot.relativize(source)
                if (relative.nameCount > 0 && relative.any { it.toString() in excludedNames }) {
                    return@forEach
                }
                val target = if (relative.nameCount == 0) targetRoot else targetRoot.resolve(relative.toString())
                if (Files.isDirectory(source)) {
                    target.createDirectories()
                } else {
                    target.parent?.createDirectories()
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
                }
            }
        }
    }

    private fun commandHintToValidationCommand(
        validationRoot: Path,
        hint: ValidationCommandHint,
        mode: String,
    ): ValidationCommand? {
        val label = hint.label
        if (mode == "compile" && !label.contains("compile") && !label.contains("syntax")) return null
        if (mode == "test" && !label.contains("test")) return null
        if (mode == "auto" && !(label.contains("test") || label.contains("compile") || label.contains("syntax"))) return null
        if (label.startsWith("likely-test:")) return null
        if (label.startsWith("module-syntax")) return null
        val firstCommand = hint.command.firstOrNull()
        if (firstCommand == "mvn" && !hasExecutable("mvn")) return null
        if (firstCommand == "gradle" && !hasExecutable("gradle")) return null
        val workingDirectory = Path.of(hint.working_directory)
        val scratchWorkingDirectory = if (workingDirectory.startsWith(projectRoot)) {
            validationRoot.resolve(projectRoot.relativize(workingDirectory).toString()).normalize()
        } else {
            workingDirectory
        }
        val mavenRepo = validationRoot.resolve(".graphharness-m2").toString()
        val gradleUserHome = validationRoot.resolve(".graphharness-gradle").toString()
        val environment = when {
            hint.command.any { it.contains("mvnw") } || hint.command.firstOrNull() == "mvn" -> mapOf(
                "HOME" to validationRoot.toString(),
                "MAVEN_USER_HOME" to mavenRepo,
            )
            hint.command.any { it.contains("gradlew") } || hint.command.firstOrNull() == "gradle" -> mapOf(
                "GRADLE_USER_HOME" to gradleUserHome,
            )
            else -> emptyMap()
        }
        val validator = when {
            label.startsWith("targeted-test") && hint.command.any { it.contains("mvnw") } -> "maven-wrapper-targeted-test"
            label.startsWith("targeted-test") && hint.command.any { it.contains("gradlew") } -> "gradle-wrapper-targeted-test"
            label.startsWith("module-test") && hint.command.any { it.contains("mvnw") } -> "maven-wrapper-test"
            label.startsWith("module-test") && hint.command.any { it.contains("gradlew") } -> "gradle-wrapper-test"
            label.startsWith("module-compile") && hint.command.firstOrNull() == "mvn" -> "maven-compile"
            label.startsWith("module-compile") && hint.command.any { it.contains("mvnw") } -> "maven-wrapper-compile"
            label.startsWith("module-compile") && hint.command.firstOrNull() == "gradle" -> "gradle-compile"
            label.startsWith("module-compile") && hint.command.any { it.contains("gradlew") } -> "gradle-wrapper-compile"
            label.startsWith("module-syntax") -> "javac-syntax"
            else -> label
        }
        val command = if (validator == "javac-syntax") {
            javacCommand(scratchWorkingDirectory)
        } else {
            hint.command
        }
        return ValidationCommand(
            validator = validator,
            command = command,
            environment = environment,
            workingDirectory = scratchWorkingDirectory,
        )
    }

    private fun selectValidationCommand(root: Path, target: ValidationTarget, mode: String, relatedTests: List<String> = emptyList()): ValidationCommand {
        require(mode in setOf("auto", "compile", "test")) { "Unsupported validation mode: $mode" }
        val mavenRepo = root.resolve(".graphharness-m2").toString()
        val gradleUserHome = root.resolve(".graphharness-gradle").toString()
        val mavenEnv = mapOf(
            "HOME" to root.toString(),
            "MAVEN_USER_HOME" to mavenRepo,
        )
        val mvnw = target.root.resolve("mvnw")
        val gradlew = target.root.resolve("gradlew")
        val pom = target.root.resolve("pom.xml")
        val gradleKts = target.root.resolve("build.gradle.kts")
        val gradleGroovy = target.root.resolve("build.gradle")
        val rootMvnw = root.resolve("mvnw")
        val rootGradlew = root.resolve("gradlew")
        val rootSettings = listOf("settings.gradle", "settings.gradle.kts").any { Files.exists(root.resolve(it)) }
        val relativePom = root.relativize(target.root.resolve("pom.xml")).toString().replace('\\', '/')
        val gradleProjectPath = root.relativize(target.root).joinToString(":") { it.toString() }.let {
            if (it.isBlank()) "" else ":$it"
        }
        val targetedTests = relatedTests
            .filter { root.resolve(it).normalize().startsWith(target.root) }
            .map { Path.of(it).fileName.toString().removeSuffix(".java") }
            .distinct()

        if (Files.exists(mvnw) && mode != "compile" && targetedTests.isNotEmpty()) {
            return ValidationCommand(
                validator = "maven-wrapper-targeted-test",
                command = listOf("bash", "./mvnw", "-q", "-Dmaven.repo.local=$mavenRepo", "-Dtest=${targetedTests.joinToString(",")}", "test"),
                environment = mavenEnv,
                workingDirectory = target.root,
            )
        }
        if (Files.exists(mvnw) && mode != "compile") {
            return ValidationCommand(
                validator = "maven-wrapper-test",
                command = listOf("bash", "./mvnw", "-q", "-Dmaven.repo.local=$mavenRepo", "test"),
                environment = mavenEnv,
                workingDirectory = target.root,
            )
        }
        if (Files.exists(mvnw)) {
            return ValidationCommand(
                validator = "maven-wrapper-compile",
                command = listOf("bash", "./mvnw", "-q", "-Dmaven.repo.local=$mavenRepo", "-DskipTests", "compile"),
                environment = mavenEnv,
                workingDirectory = target.root,
            )
        }
        if (target.root != root && Files.exists(pom) && Files.exists(rootMvnw) && mode != "compile" && targetedTests.isNotEmpty()) {
            return ValidationCommand(
                validator = "maven-wrapper-targeted-test",
                command = listOf("bash", "./mvnw", "-q", "-Dmaven.repo.local=$mavenRepo", "-f", relativePom, "-Dtest=${targetedTests.joinToString(",")}", "test"),
                environment = mavenEnv,
                workingDirectory = root,
            )
        }
        if (target.root != root && Files.exists(pom) && Files.exists(rootMvnw) && mode != "compile") {
            return ValidationCommand(
                validator = "maven-wrapper-test",
                command = listOf("bash", "./mvnw", "-q", "-Dmaven.repo.local=$mavenRepo", "-f", relativePom, "test"),
                environment = mavenEnv,
                workingDirectory = root,
            )
        }
        if (target.root != root && Files.exists(pom) && Files.exists(rootMvnw)) {
            return ValidationCommand(
                validator = "maven-wrapper-compile",
                command = listOf("bash", "./mvnw", "-q", "-Dmaven.repo.local=$mavenRepo", "-f", relativePom, "-DskipTests", "compile"),
                environment = mavenEnv,
                workingDirectory = root,
            )
        }
        if (Files.exists(gradlew) && mode != "compile" && targetedTests.isNotEmpty()) {
            return ValidationCommand(
                validator = "gradle-wrapper-targeted-test",
                command = listOf("bash", "./gradlew", "--no-daemon", "test") + targetedTests.flatMap { listOf("--tests", it) },
                environment = mapOf("GRADLE_USER_HOME" to gradleUserHome),
                workingDirectory = target.root,
            )
        }
        if (Files.exists(gradlew) && mode != "compile") {
            return ValidationCommand(
                validator = "gradle-wrapper-test",
                command = listOf("bash", "./gradlew", "--no-daemon", "test"),
                environment = mapOf("GRADLE_USER_HOME" to gradleUserHome),
                workingDirectory = target.root,
            )
        }
        if (Files.exists(gradlew)) {
            return ValidationCommand(
                validator = "gradle-wrapper-compile",
                command = listOf("bash", "./gradlew", "--no-daemon", "compileJava"),
                environment = mapOf("GRADLE_USER_HOME" to gradleUserHome),
                workingDirectory = target.root,
            )
        }
        if (target.root != root && rootSettings && Files.exists(rootGradlew) && gradleProjectPath.isNotBlank() && mode != "compile" && targetedTests.isNotEmpty()) {
            return ValidationCommand(
                validator = "gradle-wrapper-targeted-test",
                command = listOf("bash", "./gradlew", "--no-daemon", "${gradleProjectPath}:test") + targetedTests.flatMap { listOf("--tests", it) },
                environment = mapOf("GRADLE_USER_HOME" to gradleUserHome),
                workingDirectory = root,
            )
        }
        if (target.root != root && rootSettings && Files.exists(rootGradlew) && gradleProjectPath.isNotBlank() && mode != "compile") {
            return ValidationCommand(
                validator = "gradle-wrapper-test",
                command = listOf("bash", "./gradlew", "--no-daemon", "${gradleProjectPath}:test"),
                environment = mapOf("GRADLE_USER_HOME" to gradleUserHome),
                workingDirectory = root,
            )
        }
        if (target.root != root && rootSettings && Files.exists(rootGradlew) && gradleProjectPath.isNotBlank()) {
            return ValidationCommand(
                validator = "gradle-wrapper-compile",
                command = listOf("bash", "./gradlew", "--no-daemon", "${gradleProjectPath}:compileJava"),
                environment = mapOf("GRADLE_USER_HOME" to gradleUserHome),
                workingDirectory = root,
            )
        }
        if (Files.exists(pom) && hasExecutable("mvn") && mode != "compile" && targetedTests.isNotEmpty()) {
            return ValidationCommand(
                validator = "maven-targeted-test",
                command = listOf("mvn", "-q", "-Dmaven.repo.local=$mavenRepo", "-Dtest=${targetedTests.joinToString(",")}", "test"),
                environment = mavenEnv,
                workingDirectory = target.root,
            )
        }
        if (Files.exists(pom) && hasExecutable("mvn") && mode != "compile") {
            return ValidationCommand(
                validator = "maven-test",
                command = listOf("mvn", "-q", "-Dmaven.repo.local=$mavenRepo", "test"),
                environment = mavenEnv,
                workingDirectory = target.root,
            )
        }
        if (Files.exists(pom) && hasExecutable("mvn")) {
            return ValidationCommand(
                validator = "maven-compile",
                command = listOf("mvn", "-q", "-Dmaven.repo.local=$mavenRepo", "-DskipTests", "compile"),
                environment = mavenEnv,
                workingDirectory = target.root,
            )
        }
        if ((Files.exists(gradleKts) || Files.exists(gradleGroovy)) && hasExecutable("gradle") && mode != "compile" && targetedTests.isNotEmpty()) {
            return ValidationCommand(
                validator = "gradle-targeted-test",
                command = listOf("gradle", "--no-daemon", "test") + targetedTests.flatMap { listOf("--tests", it) },
                environment = mapOf("GRADLE_USER_HOME" to gradleUserHome),
                workingDirectory = target.root,
            )
        }
        if ((Files.exists(gradleKts) || Files.exists(gradleGroovy)) && hasExecutable("gradle") && mode != "compile") {
            return ValidationCommand(
                validator = "gradle-test",
                command = listOf("gradle", "--no-daemon", "test"),
                environment = mapOf("GRADLE_USER_HOME" to gradleUserHome),
                workingDirectory = target.root,
            )
        }
        if ((Files.exists(gradleKts) || Files.exists(gradleGroovy)) && hasExecutable("gradle")) {
            return ValidationCommand(
                validator = "gradle-compile",
                command = listOf("gradle", "--no-daemon", "compileJava"),
                environment = mapOf("GRADLE_USER_HOME" to gradleUserHome),
                workingDirectory = target.root,
            )
        }
        return ValidationCommand(
            validator = "javac-syntax",
            command = javacCommand(target.root),
            workingDirectory = target.root,
        )
    }

    private fun hasExecutable(command: String): Boolean =
        runCatching {
            val process = ProcessBuilder(command, "--version")
                .redirectErrorStream(true)
                .start()
            try {
                process.waitFor(5, TimeUnit.SECONDS)
            } finally {
                process.destroy()
            }
        }.getOrDefault(false)

    private fun javacCommand(root: Path): List<String> {
        val javaFiles = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) && it.extension == "java" }
                .map { root.relativize(it).toString() }
                .sorted()
                .toList()
        }
        require(javaFiles.isNotEmpty()) { "No Java files found for validation" }
        return listOf("javac", "-proc:none") + javaFiles
    }

    private fun runValidationCommand(root: Path, validation: ValidationCommand): ProcessRunResult {
        val builder = ProcessBuilder(validation.command)
            .directory((validation.workingDirectory ?: root).toFile())
            .redirectErrorStream(true)
        builder.environment().putAll(validation.environment)
        val process = try {
            builder.start()
        } catch (error: IOException) {
            return ProcessRunResult(
                exitCode = -1,
                output = error.message ?: error::class.simpleName.orEmpty(),
                timedOut = false,
            )
        }
        val outputBuffer = ByteArrayOutputStream()
        val reader = Thread {
            process.inputStream.use { input ->
                input.copyTo(outputBuffer)
            }
        }
        reader.isDaemon = true
        reader.start()
        val completed = process.waitFor(120, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
        }
        reader.join(1000)
        return ProcessRunResult(
            exitCode = if (completed) process.exitValue() else -1,
            output = outputBuffer.toString(Charsets.UTF_8.name()),
            timedOut = !completed,
        )
    }

    private fun summarizeProcessOutput(output: String): String {
        val lines = output.lines()
            .filter { it.isNotBlank() }
            .takeLast(40)
        return lines.joinToString("\n").take(4000)
    }

    private fun shouldFallbackValidation(
        selected: ValidationCommand,
        result: ProcessRunResult,
        mode: String,
        root: Path,
    ): Boolean {
        if (mode != "auto") return false
        if (selected.validator == "javac-syntax") return false
        if (result.exitCode == 0 && !result.timedOut) return false
        val output = result.output.lowercase()
        if (result.timedOut) return true
        if ("failed to fetch" in output || "read-only file system" in output || "operation not permitted" in output) return true
        if ("could not resolve" in output || "unable to access" in output || "download" in output) return true
        return Files.exists(root.resolve("pom.xml")) || Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"))
    }

    private fun summarizeValidationOutput(outcome: ValidationOutcome): String {
        val sections = mutableListOf<String>()
        if (outcome.notes.isNotEmpty()) {
            sections += outcome.notes.joinToString("\n")
        }
        val processSummary = summarizeProcessOutput(outcome.result.output)
        if (processSummary.isNotBlank()) {
            sections += processSummary
        }
        return sections.joinToString("\n\n").take(4000)
    }

    private fun buildValidationErrors(outcome: ValidationOutcome): List<String> {
        if (outcome.result.timedOut) {
            return outcome.notes + listOf("Validation command timed out: ${outcome.validator.command.joinToString(" ")}")
        }
        if (outcome.result.exitCode == 0) {
            return outcome.notes
        }
        val summary = summarizeProcessOutput(outcome.result.output)
        return outcome.notes + listOf(
            buildString {
                append("Validation failed via ${outcome.validator.validator}")
                append(" (exit_code=").append(outcome.result.exitCode).append(')')
                if (summary.isNotBlank()) {
                    append(": ").append(summary.lineSequence().first())
                }
            },
        )
    }

    private fun buildFallbackNotes(
        primary: ValidationCommand,
        primaryResult: ProcessRunResult,
        fallback: ValidationCommand,
        fallbackResult: ProcessRunResult,
    ): List<String> {
        val primarySummary = summarizeProcessOutput(primaryResult.output).lineSequence().firstOrNull().orEmpty()
        val fallbackSummary = summarizeProcessOutput(fallbackResult.output).lineSequence().firstOrNull().orEmpty()
        return buildList {
            add(
                buildString {
                    append("Primary validation via ${primary.validator} failed")
                    if (primaryResult.timedOut) {
                        append(" due to timeout")
                    } else {
                        append(" with exit_code=").append(primaryResult.exitCode)
                    }
                    if (primarySummary.isNotBlank()) {
                        append(": ").append(primarySummary)
                    }
                },
            )
            add(
                buildString {
                    append("Fallback validation via ${fallback.validator} also failed")
                    if (fallbackResult.timedOut) {
                        append(" due to timeout")
                    } else {
                        append(" with exit_code=").append(fallbackResult.exitCode)
                    }
                    if (fallbackSummary.isNotBlank()) {
                        append(": ").append(fallbackSummary)
                    }
                },
            )
        }
    }

    private fun deleteRecursively(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
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
