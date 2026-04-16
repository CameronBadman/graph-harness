package graphharness

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant

class GraphHarnessServer(private val snapshotManager: SnapshotManager) {
    private val tools = listOf(
        tool(
            "get_edit_candidates",
            "Suggest likely edit targets and operations from a natural-language task description.",
            objectSchema(
                properties = mapOf(
                    "task" to stringSchema("Natural-language edit task."),
                    "limit" to intSchema("Maximum candidates to return.", minimum = 1, maximum = 10),
                ),
                required = listOf("task"),
            ),
        ),
        tool(
            "plan_edit",
            "Plan a targeted graph-guided edit and return a preview diff without writing to disk.",
            objectSchema(
                properties = mapOf(
                    "operation" to enumSchema("Edit operation to preview.", listOf("modify_method_body", "rename_node")),
                    "target_node_id" to stringSchema("Method node id to edit."),
                    "payload" to objectSchema(
                        properties = mapOf(
                            "new_body" to stringSchema("Replacement Java statements for the method body."),
                            "patch_mode" to enumSchema("Method-body patch mode.", listOf("replace_body", "insert_before", "insert_after", "replace_line")),
                            "anchor" to stringSchema("Anchor line fragment for patch modes that modify around an existing statement."),
                            "snippet" to stringSchema("Snippet to insert or use as replacement in patch modes."),
                            "new_name" to stringSchema("New method name for rename_node."),
                        ),
                    ),
                ),
                required = listOf("operation", "target_node_id", "payload"),
            ),
        ),
        tool(
            "apply_edit",
            "Apply a previously planned edit to disk and refresh the active snapshot.",
            objectSchema(
                properties = mapOf(
                    "edit_id" to stringSchema("Edit id returned by plan_edit."),
                ),
                required = listOf("edit_id"),
            ),
        ),
        tool("get_summary_map", "Return the compact topology-oriented summary for the project.", objectSchema()),
        tool(
            "get_cluster_detail",
            "Expand one cluster with its nodes and internal/external edges.",
            objectSchema(
                properties = mapOf(
                    "cluster_id" to stringSchema("Cluster id from get_summary_map."),
                ),
                required = listOf("cluster_id"),
            ),
        ),
        tool(
            "get_node_detail",
            "Inspect one node with its cluster, direct relationships, callers, callees, and hierarchy.",
            objectSchema(
                properties = mapOf(
                    "node_id" to stringSchema("Node id returned by search_graph or another traversal tool."),
                ),
                required = listOf("node_id"),
            ),
        ),
        tool(
            "get_call_paths",
            "Expand multi-hop call paths from a start node, optionally to a target node.",
            objectSchema(
                properties = mapOf(
                    "node_id" to stringSchema("Starting method node id."),
                    "target_node_id" to stringSchema("Optional destination method node id."),
                    "max_depth" to intSchema("Maximum call depth to explore.", minimum = 1, maximum = 8),
                ),
                required = listOf("node_id"),
            ),
        ),
        tool(
            "search_graph",
            "Search graph nodes by name, kind, annotation, or cluster.",
            objectSchema(
                properties = mapOf(
                    "query" to stringSchema("Fuzzy name match."),
                    "kind" to enumSchema("Optional node kind filter.", listOf("class", "interface", "method", "field", "enum", "annotation")),
                    "annotation" to stringSchema("Optional annotation filter."),
                    "cluster_id" to stringSchema("Optional cluster scope."),
                ),
            ),
        ),
        tool(
            "get_callers",
            "Return callers for a method node.",
            objectSchema(
                properties = mapOf(
                    "node_id" to stringSchema("Method node id."),
                    "depth" to intSchema("Traversal depth.", minimum = 1, maximum = 5),
                ),
                required = listOf("node_id"),
            ),
        ),
        tool(
            "get_callees",
            "Return callees for a method node.",
            objectSchema(
                properties = mapOf(
                    "node_id" to stringSchema("Method node id."),
                    "depth" to intSchema("Traversal depth.", minimum = 1, maximum = 5),
                ),
                required = listOf("node_id"),
            ),
        ),
        tool(
            "get_implementations",
            "Return likely concrete implementations for a method.",
            objectSchema(
                properties = mapOf(
                    "node_id" to stringSchema("Method node id."),
                ),
                required = listOf("node_id"),
            ),
        ),
        tool(
            "get_type_hierarchy",
            "Return ancestors and descendants for a type node.",
            objectSchema(
                properties = mapOf(
                    "node_id" to stringSchema("Type node id."),
                    "direction" to enumSchema("Hierarchy direction.", listOf("up", "down", "both")),
                ),
                required = listOf("node_id"),
            ),
        ),
        tool(
            "get_dependencies",
            "Return incoming or outgoing dependencies for a node.",
            objectSchema(
                properties = mapOf(
                    "node_id" to stringSchema("Node id."),
                    "direction" to enumSchema("Dependency direction.", listOf("incoming", "outgoing", "both")),
                ),
                required = listOf("node_id"),
            ),
        ),
        tool(
            "get_impact",
            "Return transitive affected nodes/files for a node change.",
            objectSchema(
                properties = mapOf(
                    "node_id" to stringSchema("Node id."),
                    "max_depth" to intSchema("Maximum dependency depth.", minimum = 1, maximum = 6),
                ),
                required = listOf("node_id"),
            ),
        ),
        tool(
            "get_source",
            "Return source for a node with optional context lines.",
            objectSchema(
                properties = mapOf(
                    "node_id" to stringSchema("Node id."),
                    "include_context" to intSchema("Extra surrounding source lines.", minimum = 0),
                ),
                required = listOf("node_id"),
            ),
        ),
        tool(
            "get_source_batch",
            "Return source for multiple node ids.",
            objectSchema(
                properties = mapOf(
                    "node_ids" to mapOf(
                        "type" to "array",
                        "description" to "List of node ids.",
                        "items" to mapOf("type" to "string"),
                        "minItems" to 1,
                    ),
                ),
                required = listOf("node_ids"),
            ),
        ),
    )

    fun run(input: InputStream, output: OutputStream) {
        val stream = BufferedInputStream(input)
        while (true) {
            val payload = readFrame(stream) ?: break
            val request = MiniJson.parse(payload).asObject()
            val id = request["id"]
            val method = request.requiredString("method")
            val params = request.optionalObject("params") ?: emptyJsonObject()

            val response = runCatching { dispatch(method, params, id) }.getOrElse { err ->
                buildError(id, -32000, err.message ?: err::class.simpleName.orEmpty())
            }
            if (response != null) {
                writeResponse(output, response)
            }
        }
    }

    private fun dispatch(method: String, params: JObject, id: JsonValue?): JObject? {
        return when (method) {
            "initialize" -> buildResult(
                id,
                graphHarnessJson.encode(
                    mapOf(
                        "protocolVersion" to "2024-11-05",
                        "serverInfo" to mapOf("name" to "graphharness", "version" to "0.1.0"),
                        "capabilities" to mapOf("tools" to emptyMap<String, Any?>()),
                    ),
                ),
            )

            "notifications/initialized" -> null
            "tools/list" -> buildResult(id, graphHarnessJson.encode(mapOf("tools" to tools)))
            "tools/call" -> {
                val toolName = params.requiredString("name")
                val arguments = params.optionalObject("arguments") ?: emptyJsonObject()
                val result = invokeTool(toolName, arguments)
                buildResult(
                    id,
                    graphHarnessJson.encode(
                        mapOf(
                            "content" to listOf(
                                mapOf(
                                    "type" to "text",
                                    "text" to result.stringify(),
                                ),
                            ),
                            "structuredContent" to result,
                            "isError" to false,
                        ),
                    ),
                )
            }

            else -> buildResult(id, invokeTool(method, params))
        }
    }

    private fun invokeTool(toolName: String, arguments: JObject): JsonValue {
        return when (toolName) {
            "get_edit_candidates" -> graphHarnessJson.encode(
                snapshotManager.editCandidates(
                    task = arguments.requiredString("task"),
                    limit = arguments.optionalInt("limit") ?: 5,
                ),
            )

            "plan_edit" -> {
                val payload = arguments.optionalObject("payload") ?: emptyJsonObject()
                graphHarnessJson.encode(
                    snapshotManager.planEdit(
                        operation = arguments.requiredString("operation"),
                        targetNodeId = arguments.requiredString("target_node_id"),
                        payload = EditRequestPayload(
                            new_body = payload.optionalString("new_body"),
                            patch_mode = payload.optionalString("patch_mode"),
                            anchor = payload.optionalString("anchor"),
                            snippet = payload.optionalString("snippet"),
                            placement = payload.optionalString("placement"),
                            new_name = payload.optionalString("new_name"),
                        ),
                    ),
                )
            }

            "apply_edit" -> graphHarnessJson.encode(
                snapshotManager.applyEdit(arguments.requiredString("edit_id")),
            )

            "get_summary_map" -> graphHarnessJson.encode(snapshotManager.summaryMap())
            "get_cluster_detail" -> graphHarnessJson.encode(snapshotManager.clusterDetail(arguments.requiredString("cluster_id")))
            "get_node_detail" -> graphHarnessJson.encode(snapshotManager.nodeDetail(arguments.requiredString("node_id")))
            "get_call_paths" -> graphHarnessJson.encode(
                snapshotManager.callPaths(
                    nodeId = arguments.requiredString("node_id"),
                    maxDepth = arguments.optionalInt("max_depth") ?: 3,
                    targetNodeId = arguments.optionalString("target_node_id"),
                ),
            )
            "search_graph" -> graphHarnessJson.encode(
                snapshotManager.search(
                    query = arguments.optionalString("query").orEmpty(),
                    kind = arguments.optionalString("kind"),
                    annotation = arguments.optionalString("annotation"),
                    clusterId = arguments.optionalString("cluster_id"),
                ),
            )

            "get_callers" -> graphHarnessJson.encode(
                snapshotManager.callers(arguments.requiredString("node_id"), arguments.optionalInt("depth") ?: 1),
            )

            "get_callees" -> graphHarnessJson.encode(
                snapshotManager.callees(arguments.requiredString("node_id"), arguments.optionalInt("depth") ?: 1),
            )

            "get_implementations" -> graphHarnessJson.encode(
                snapshotManager.implementations(arguments.requiredString("node_id")),
            )

            "get_type_hierarchy" -> graphHarnessJson.encode(
                snapshotManager.typeHierarchy(
                    nodeId = arguments.requiredString("node_id"),
                    direction = arguments.optionalString("direction") ?: "both",
                ),
            )

            "get_dependencies" -> graphHarnessJson.encode(
                snapshotManager.dependencies(
                    nodeId = arguments.requiredString("node_id"),
                    direction = arguments.optionalString("direction") ?: "both",
                ),
            )

            "get_impact" -> graphHarnessJson.encode(
                snapshotManager.impact(
                    nodeId = arguments.requiredString("node_id"),
                    maxDepth = arguments.optionalInt("max_depth") ?: 3,
                ),
            )

            "get_source" -> graphHarnessJson.encode(
                snapshotManager.source(
                    nodeId = arguments.requiredString("node_id"),
                    includeContext = arguments.optionalInt("include_context") ?: 0,
                ),
            )

            "get_source_batch" -> {
                val nodeIds = arguments.requiredArray("node_ids").values.map { it.asString() }
                graphHarnessJson.encode(snapshotManager.sourceBatch(nodeIds))
            }

            else -> error("Unknown tool: $toolName")
        }
    }

    private fun tool(name: String, description: String, inputSchema: Map<String, Any?>): ToolDefinition {
        return ToolDefinition(
            name = name,
            description = description,
            inputSchema = inputSchema,
        )
    }

    private fun buildResult(id: JsonValue?, result: JsonValue): JObject = jObject(
        "jsonrpc" to "2.0",
        "id" to id,
        "result" to result,
    )

    private fun buildError(id: JsonValue?, code: Int, message: String): JObject = jObject(
        "jsonrpc" to "2.0",
        "id" to id,
        "error" to mapOf(
            "code" to code,
            "message" to message,
            "data" to mapOf("generated_at" to Instant.now().toString()),
        ),
    )

    private fun readFrame(input: BufferedInputStream): String? {
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readAsciiLine(input) ?: return null
            if (line.isBlank()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        val length = headers["content-length"]?.toIntOrNull() ?: return null
        val bytes = input.readNBytes(length)
        return bytes.toString(StandardCharsets.UTF_8)
    }

    private fun writeResponse(output: OutputStream, payload: JObject) {
        val body = payload.stringify().toByteArray(StandardCharsets.UTF_8)
        output.write("Content-Length: ${body.size}\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(body)
        output.flush()
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val buffer = ArrayList<Byte>()
        while (true) {
            val read = input.read()
            if (read == -1) {
                return if (buffer.isEmpty()) null else buffer.toByteArray().toString(StandardCharsets.US_ASCII)
            }
            if (read == '\n'.code) break
            if (read != '\r'.code) {
                buffer += read.toByte()
            }
        }
        return buffer.toByteArray().toString(StandardCharsets.US_ASCII)
    }
}

internal class JsonCodec {
    fun encode(value: Any?): JsonValue = when (value) {
        null -> JNull
        is JsonValue -> value
        is String -> JString(value)
        is Int -> JNumber(value.toString())
        is Long -> JNumber(value.toString())
        is Double -> JNumber(value.toString())
        is Float -> JNumber(value.toString())
        is Boolean -> JBoolean(value)
        is List<*> -> JArray(value.map(::encode))
        is Set<*> -> JArray(value.map(::encode))
        is Map<*, *> -> JObject(linkedMapOf<String, JsonValue>().apply {
            value.forEach { (k, v) -> put(k.toString(), encode(v)) }
        })

        is SourceRange -> jObject("start" to value.start, "end" to value.end)
        is NodeSummary -> jObject(
            "id" to value.id,
            "kind" to value.kind,
            "name" to value.name,
            "signature" to value.signature,
            "file" to value.file,
            "line_range" to value.line_range,
            "visibility" to value.visibility,
            "annotations" to value.annotations,
            "complexity" to value.complexity,
            "loc" to value.loc,
        )

        is EdgeSummary -> jObject(
            "from" to value.from,
            "to" to value.to,
            "relationship" to value.relationship,
            "file" to value.file,
            "line" to value.line,
        )

        is ClusterSummary -> jObject(
            "cluster_id" to value.cluster_id,
            "label" to value.label,
            "description" to value.description,
            "node_count" to value.node_count,
            "key_nodes" to value.key_nodes,
            "external_edges" to value.external_edges,
            "bridge_nodes" to value.bridge_nodes,
        )

        is ProjectSummary -> jObject(
            "root" to value.root,
            "total_files" to value.total_files,
            "total_packages" to value.total_packages,
            "total_types" to value.total_types,
            "total_methods" to value.total_methods,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
        )

        is OrientationNode -> jObject(
            "id" to value.id,
            "kind" to value.kind,
            "name" to value.name,
            "file" to value.file,
        )

        is HotspotSummary -> jObject("node" to value.node, "score" to value.score)
        is EditPlanResult -> jObject(
            "edit_id" to value.edit_id,
            "operation" to value.operation,
            "target_node_id" to value.target_node_id,
            "diff" to value.diff,
            "affected_nodes" to value.affected_nodes,
            "affected_files" to value.affected_files,
            "validation_errors" to value.validation_errors,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
        )
        is EditCandidate -> jObject(
            "node" to value.node,
            "suggested_operation" to value.suggested_operation,
            "rationale" to value.rationale,
            "suggested_payload" to value.suggested_payload,
            "score" to value.score,
        )
        is EditCandidatesResult -> jObject(
            "task" to value.task,
            "candidates" to value.candidates,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
        )
        is EditApplyResult -> jObject(
            "success" to value.success,
            "edit_id" to value.edit_id,
            "updated_nodes" to value.updated_nodes,
            "affected_files" to value.affected_files,
            "validation_errors" to value.validation_errors,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
        )
        is SummaryMapResult -> jObject(
            "project" to value.project,
            "clusters" to value.clusters,
            "bridge_nodes" to value.bridge_nodes,
            "entrypoints" to value.entrypoints,
            "hotspots" to value.hotspots,
            "summary_mode" to value.summary_mode,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
        )

        is ClusterDetailResult -> jObject(
            "cluster" to value.cluster,
            "nodes" to value.nodes,
            "internal_edges" to value.internal_edges,
            "external_edges" to value.external_edges,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
        )

        is CallSite -> jObject("file" to value.file, "line" to value.line)
        is NodeWithCallSite -> jObject("node" to value.node, "call_site" to value.call_site, "relationship" to value.relationship)
        is TraversalResult -> jObject(
            "items" to value.items,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
            "analysis_confidence" to value.analysis_confidence,
            "known_blind_spots" to value.known_blind_spots,
            "latency_ms" to value.latency_ms,
        )

        is CallPath -> jObject(
            "nodes" to value.nodes,
            "edges" to value.edges,
        )

        is CallPathsResult -> jObject(
            "paths" to value.paths,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
            "analysis_confidence" to value.analysis_confidence,
            "known_blind_spots" to value.known_blind_spots,
            "latency_ms" to value.latency_ms,
        )

        is DependencyResultItem -> jObject("node" to value.node, "relationship" to value.relationship)
        is DependencyResult -> jObject(
            "dependencies" to value.dependencies,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
            "analysis_confidence" to value.analysis_confidence,
            "known_blind_spots" to value.known_blind_spots,
            "latency_ms" to value.latency_ms,
        )

        is ImpactResult -> jObject(
            "affected_nodes" to value.affected_nodes,
            "affected_files" to value.affected_files,
            "risk_score" to value.risk_score,
            "analysis_confidence" to value.analysis_confidence,
            "known_blind_spots" to value.known_blind_spots,
            "analysis_basis" to value.analysis_basis,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
            "latency_ms" to value.latency_ms,
        )

        is SourceResult -> jObject(
            "source" to value.source,
            "file" to value.file,
            "line_range" to value.line_range,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
        )

        is SourceBatchItem -> jObject(
            "node_id" to value.node_id,
            "source" to value.source,
            "file" to value.file,
            "line_range" to value.line_range,
        )

        is SourceBatchResult -> jObject(
            "sources" to value.sources,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
        )

        is SearchResult -> jObject(
            "results" to value.results,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
        )

        is TypeHierarchyResult -> jObject(
            "ancestors" to value.ancestors,
            "descendants" to value.descendants,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
            "analysis_confidence" to value.analysis_confidence,
            "known_blind_spots" to value.known_blind_spots,
        )

        is NodeDetailResult -> jObject(
            "node" to value.node,
            "cluster" to value.cluster,
            "incoming_dependencies" to value.incoming_dependencies,
            "outgoing_dependencies" to value.outgoing_dependencies,
            "callers" to value.callers,
            "callees" to value.callees,
            "implementations" to value.implementations,
            "implementation_relationships" to value.implementation_relationships,
            "ancestors" to value.ancestors,
            "descendants" to value.descendants,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
            "analysis_confidence" to value.analysis_confidence,
            "known_blind_spots" to value.known_blind_spots,
            "latency_ms" to value.latency_ms,
        )

        is ToolDefinition -> jObject(
            "name" to value.name,
            "description" to value.description,
            "inputSchema" to value.inputSchema,
        )

        else -> error("Unsupported JSON encoding type: ${value::class.qualifiedName}")
    }
}

internal val graphHarnessJson = JsonCodec()

private fun objectSchema(
    properties: Map<String, Any?> = emptyMap(),
    required: List<String> = emptyList(),
): Map<String, Any?> = buildMap {
    put("type", "object")
    put("properties", properties)
    put("additionalProperties", false)
    if (required.isNotEmpty()) {
        put("required", required)
    }
}

private fun stringSchema(description: String): Map<String, Any?> =
    mapOf("type" to "string", "description" to description)

private fun enumSchema(description: String, values: List<String>): Map<String, Any?> =
    mapOf("type" to "string", "description" to description, "enum" to values)

private fun intSchema(description: String, minimum: Int? = null, maximum: Int? = null): Map<String, Any?> =
    buildMap {
        put("type", "integer")
        put("description", description)
        if (minimum != null) put("minimum", minimum)
        if (maximum != null) put("maximum", maximum)
    }
