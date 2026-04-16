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
    val new_body: String? = null,
    val patch_mode: String? = null,
    val anchor: String? = null,
    val snippet: String? = null,
    val placement: String? = null,
    val new_name: String? = null,
)

data class EditCandidate(
    val node: NodeSummary,
    val suggested_operation: String,
    val rationale: String,
    val suggested_payload: Map<String, String> = emptyMap(),
    val score: Int,
    val confidence: Double,
)

data class EditCandidatesResult(
    val task: String,
    val candidates: List<EditCandidate>,
    val needs_disambiguation: Boolean,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
)

data class VerifyCandidateResult(
    val task: String,
    val node: NodeSummary,
    val suggested_operation: String,
    val suggested_payload: Map<String, String>,
    val anchor_present: Boolean,
    val snippet_present: Boolean,
    val name_match: Boolean,
    val confidence: Double,
    val verification_notes: List<String>,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
)

data class ResolveEditTargetResult(
    val task: String,
    val resolved_candidate: EditCandidate? = null,
    val verification: VerifyCandidateResult? = null,
    val rejected_candidates: List<VerifyCandidateResult> = emptyList(),
    val needs_disambiguation: Boolean,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
)

data class ValidationRequestPayload(
    val mode: String? = null,
)

data class FitnessSubscore(
    val name: String,
    val score: Int,
    val rationale: String,
)

data class FitnessIssue(
    val severity: String,
    val title: String,
    val details: String,
    val node_id: String? = null,
    val file: String? = null,
)

data class FitnessAction(
    val priority: String,
    val title: String,
    val rationale: String,
    val target_node_id: String? = null,
    val file: String? = null,
)

data class AgentFitnessResult(
    val overall_score: Int,
    val subscores: List<FitnessSubscore>,
    val metrics: Map<String, Double>,
    val issues: List<FitnessIssue>,
    val recommended_actions: List<FitnessAction>,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
)

data class ClusterFitnessResult(
    val cluster: ClusterSummary,
    val overall_score: Int,
    val subscores: List<FitnessSubscore>,
    val metrics: Map<String, Double>,
    val issues: List<FitnessIssue>,
    val recommended_actions: List<FitnessAction>,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
)

data class ValidationTargetItem(
    val kind: String,
    val identifier: String,
    val file: String? = null,
    val confidence: Double,
    val rationale: String,
)

data class ValidationCommandHint(
    val label: String,
    val command: List<String>,
    val working_directory: String,
)

data class ValidationTargetsResult(
    val target_node_id: String? = null,
    val edit_id: String? = null,
    val validation_targets: List<ValidationTargetItem>,
    val command_hints: List<ValidationCommandHint>,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
)

data class CapabilitiesResult(
    val languages: List<String>,
    val analysis_engine: String,
    val engine_version: String? = null,
    val available_tools: List<String>,
    val edit_operations: List<String>,
    val validation_modes: List<String>,
    val confidence_semantics: List<String>,
    val degraded_mode_flags: List<String>,
    val snapshot_semantics: String,
    val analysis_engine_capabilities: List<String>,
    val build_context_bundle_supported: Boolean,
    val analysis_engine_first_backend: Boolean,
    val snapshot_id: String,
    val generated_at: String,
)

data class ContextBundleResult(
    val task: String? = null,
    val node_id: String? = null,
    val chosen_node_id: String? = null,
    val token_budget: Int,
    val summary_mode: String,
    val clusters: List<ClusterSummary>,
    val entrypoints: List<OrientationNode>,
    val focus_nodes: List<NodeSummary>,
    val relationships: List<EdgeSummary>,
    val impact_files: List<String>,
    val source_slices: List<SourceBatchItem>,
    val notes: List<String>,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
)

data class SnapshotDeltaNode(
    val old_node_id: String? = null,
    val new_node_id: String? = null,
    val kind: String,
    val old_name: String? = null,
    val new_name: String? = null,
    val file: String? = null,
    val change_types: List<String> = emptyList(),
)

data class SnapshotDeltaResult(
    val old_snapshot_id: String,
    val new_snapshot_id: String,
    val added_nodes: List<NodeSummary>,
    val removed_nodes: List<NodeSummary>,
    val changed_nodes: List<SnapshotDeltaNode>,
    val added_files: List<String>,
    val removed_files: List<String>,
    val changed_files: List<String>,
    val added_edge_count: Int,
    val removed_edge_count: Int,
    val analysis_engine: String,
    val engine_version: String? = null,
    val build_duration_ms: Long,
    val snapshot_id: String,
    val generated_at: String,
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: Map<String, Any?>,
)
