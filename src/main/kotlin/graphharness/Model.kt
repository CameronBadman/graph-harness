package graphharness

data class SourceRange(val start: Int, val end: Int)

data class NodeSummary(
    val id: String,
    val kind: String,
    val name: String,
    val signature: String? = null,
    val file: String,
    val line_range: SourceRange,
    val visibility: String = "package",
    val annotations: List<String> = emptyList(),
    val complexity: Int? = null,
    val loc: Int? = null,
)

data class EdgeSummary(
    val from: String,
    val to: String,
    val relationship: String,
    val file: String? = null,
    val line: Int? = null,
)

data class ClusterSummary(
    val cluster_id: String,
    val label: String,
    val description: String,
    val node_count: Int,
    val key_nodes: List<String>,
    val external_edges: Int,
    val bridge_nodes: List<String>,
)

data class ProjectSummary(
    val root: String,
    val total_files: Int,
    val total_packages: Int,
    val total_types: Int,
    val total_methods: Int,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
)

data class OrientationNode(
    val id: String,
    val kind: String,
    val name: String,
    val file: String,
)

data class HotspotSummary(
    val node: OrientationNode,
    val score: Int,
)

data class SummaryMapResult(
    val project: ProjectSummary,
    val clusters: List<ClusterSummary>,
    val bridge_nodes: List<OrientationNode>,
    val entrypoints: List<OrientationNode>,
    val hotspots: List<HotspotSummary>,
    val summary_mode: String,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
)

data class ClusterDetailResult(
    val cluster: ClusterSummary,
    val nodes: List<NodeSummary>,
    val internal_edges: List<EdgeSummary>,
    val external_edges: List<EdgeSummary>,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
)

data class NodeWithCallSite(
    val node: NodeSummary,
    val call_site: CallSite? = null,
    val relationship: String? = null,
)

data class CallSite(
    val file: String,
    val line: Int,
)

data class TraversalResult(
    val items: List<NodeWithCallSite>,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
    val analysis_confidence: Double,
    val known_blind_spots: List<String>,
    val latency_ms: Long,
)

data class DependencyResultItem(
    val node: NodeSummary,
    val relationship: String,
)

data class DependencyResult(
    val dependencies: List<DependencyResultItem>,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
    val analysis_confidence: Double,
    val known_blind_spots: List<String>,
    val latency_ms: Long,
)

data class ImpactResult(
    val affected_nodes: List<NodeSummary>,
    val affected_files: List<String>,
    val risk_score: Double,
    val analysis_confidence: Double,
    val known_blind_spots: List<String>,
    val analysis_basis: List<String>,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
    val latency_ms: Long,
)

data class SourceResult(
    val source: String,
    val file: String,
    val line_range: SourceRange,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
)

data class SourceBatchItem(
    val node_id: String,
    val source: String,
    val file: String,
    val line_range: SourceRange,
)

data class SourceBatchResult(
    val sources: List<SourceBatchItem>,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
)

data class SearchResult(
    val results: List<NodeSummary>,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
)

data class TypeHierarchyResult(
    val ancestors: List<NodeSummary>,
    val descendants: List<NodeSummary>,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
    val analysis_confidence: Double,
    val known_blind_spots: List<String>,
)

data class NodeDetailResult(
    val node: NodeSummary,
    val cluster: ClusterSummary? = null,
    val incoming_dependencies: List<DependencyResultItem>,
    val outgoing_dependencies: List<DependencyResultItem>,
    val callers: List<NodeWithCallSite>,
    val callees: List<NodeWithCallSite>,
    val implementations: List<NodeSummary>,
    val implementation_relationships: Map<String, String> = emptyMap(),
    val ancestors: List<NodeSummary>,
    val descendants: List<NodeSummary>,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
    val analysis_confidence: Double,
    val known_blind_spots: List<String>,
    val latency_ms: Long,
)

data class CallPath(
    val nodes: List<NodeSummary>,
    val edges: List<EdgeSummary>,
)

data class CallPathsResult(
    val paths: List<CallPath>,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
    val analysis_confidence: Double,
    val known_blind_spots: List<String>,
    val latency_ms: Long,
)

data class EditRequestPayload(
    val new_body: String,
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>,
)
