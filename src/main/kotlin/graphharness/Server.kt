package graphharness

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant

class GraphHarnessServer(private val snapshotManager: SnapshotManager) {
    private val tools = listOf(
        tool(
            "get_capabilities",
            "Return an explicit capability handshake for languages, engines, edit operations, validation modes, and degraded-mode semantics.",
            objectSchema(),
        ),
        tool(
            "build_context_bundle",
            "Build a task-oriented context bundle with graph facts and the minimum relevant source slices for prompt packing.",
            objectSchema(
                properties = mapOf(
                    "task" to stringSchema("Natural-language task to orient around."),
                    "node_id" to stringSchema("Optional focus node id to build the bundle around."),
                    "token_budget" to intSchema("Approximate token budget for the bundle.", minimum = 400, maximum = 12000),
                ),
            ),
        ),
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
            "resolve_edit_target",
            "Resolve a natural-language edit task into the best verified candidate and report rejected alternatives.",
            objectSchema(
                properties = mapOf(
                    "task" to stringSchema("Natural-language edit task."),
                    "limit" to intSchema("Maximum candidates to inspect before resolving.", minimum = 1, maximum = 10),
                ),
                required = listOf("task"),
            ),
        ),
        tool(
            "verify_candidate",
            "Cheaply verify whether a candidate node actually matches the requested edit task before planning the edit.",
            objectSchema(
                properties = mapOf(
                    "task" to stringSchema("Natural-language edit task."),
                    "node_id" to stringSchema("Candidate node id to verify."),
                    "payload" to objectSchema(
                        properties = mapOf(
                            "new_body" to stringSchema("Replacement Java statements for the method body."),
                            "patch_mode" to enumSchema("Method-body patch mode.", listOf("replace_body", "insert_before", "insert_after", "replace_line")),
                            "anchor" to stringSchema("Anchor line fragment for patch verification."),
                            "snippet" to stringSchema("Snippet to insert or use as replacement."),
                            "new_name" to stringSchema("New method name for rename verification."),
                        ),
                    ),
                ),
                required = listOf("task", "node_id"),
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
        tool(
            "validate_edit",
            "Validate a planned or already-applied edit on a scratch copy using repo-aware Java build/test checks.",
            objectSchema(
                properties = mapOf(
                    "edit_id" to stringSchema("Edit id returned by plan_edit."),
                    "mode" to enumSchema("Validation depth.", listOf("auto", "compile", "test")),
                ),
                required = listOf("edit_id"),
            ),
        ),
        tool(
            "get_agent_fitness",
            "Return a structural fitness report for how well this repo supports graph-guided agent workflows.",
            objectSchema(),
        ),
        tool(
            "get_cluster_fitness",
            "Return a structural fitness report for one cluster/subsystem.",
            objectSchema(
                properties = mapOf(
                    "cluster_id" to stringSchema("Cluster id from get_summary_map."),
                ),
                required = listOf("cluster_id"),
            ),
        ),
        tool(
            "get_validation_targets",
            "Suggest likely validation modules, tests, and command shapes for a node or planned edit.",
            objectSchema(
                properties = mapOf(
                    "node_id" to stringSchema("Node id to validate around."),
                    "edit_id" to stringSchema("Optional edit id from plan_edit."),
                ),
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

            "resolve_edit_target" -> graphHarnessJson.encode(
                snapshotManager.resolveEditTarget(
                    task = arguments.requiredString("task"),
                    limit = arguments.optionalInt("limit") ?: 5,
                ),
            )

            "verify_candidate" -> {
                val payload = arguments.optionalObject("payload") ?: emptyJsonObject()
                graphHarnessJson.encode(
                    snapshotManager.verifyCandidate(
                        task = arguments.requiredString("task"),
                        nodeId = arguments.requiredString("node_id"),
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

            "validate_edit" -> graphHarnessJson.encode(
                snapshotManager.validateEdit(
                    editId = arguments.requiredString("edit_id"),
                    mode = arguments.optionalString("mode") ?: "auto",
                ),
            )
            "get_capabilities" -> graphHarnessJson.encode(
                snapshotManager.capabilities(),
            )
            "build_context_bundle" -> graphHarnessJson.encode(
                snapshotManager.buildContextBundle(
                    task = arguments.optionalString("task"),
                    nodeId = arguments.optionalString("node_id"),
                    tokenBudget = arguments.optionalInt("token_budget") ?: 1800,
                ),
            )

            "get_agent_fitness" -> graphHarnessJson.encode(
                snapshotManager.agentFitness(),
            )
            "get_cluster_fitness" -> graphHarnessJson.encode(
                snapshotManager.clusterFitness(arguments.requiredString("cluster_id")),
            )
            "get_validation_targets" -> graphHarnessJson.encode(
                snapshotManager.validationTargets(
                    nodeId = arguments.optionalString("node_id"),
                    editId = arguments.optionalString("edit_id"),
                ),
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
            "needs_disambiguation" to value.needs_disambiguation,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
        )
        is ResolveEditTargetResult -> jObject(
            "task" to value.task,
            "resolved_candidate" to value.resolved_candidate,
            "verification" to value.verification,
            "rejected_candidates" to value.rejected_candidates,
            "needs_disambiguation" to value.needs_disambiguation,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
        )
        is VerifyCandidateResult -> jObject(
            "task" to value.task,
            "node" to value.node,
            "suggested_operation" to value.suggested_operation,
            "suggested_payload" to value.suggested_payload,
            "anchor_present" to value.anchor_present,
            "snippet_present" to value.snippet_present,
            "name_match" to value.name_match,
            "confidence" to value.confidence,
            "verification_notes" to value.verification_notes,
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
        is EditValidationResult -> jObject(
            "success" to value.success,
            "edit_id" to value.edit_id,
            "validation_mode" to value.validation_mode,
            "validation_scope" to value.validation_scope,
            "validation_target" to value.validation_target,
            "validator" to value.validator,
            "attempted_validators" to value.attempted_validators,
            "degraded" to value.degraded,
            "command" to value.command,
            "exit_code" to value.exit_code,
            "duration_ms" to value.duration_ms,
            "affected_files" to value.affected_files,
            "output_excerpt" to value.output_excerpt,
            "validation_errors" to value.validation_errors,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
        )
        is FitnessSubscore -> jObject(
            "name" to value.name,
            "score" to value.score,
            "rationale" to value.rationale,
        )
        is FitnessIssue -> jObject(
            "severity" to value.severity,
            "title" to value.title,
            "details" to value.details,
            "node_id" to value.node_id,
            "file" to value.file,
        )
        is FitnessAction -> jObject(
            "priority" to value.priority,
            "title" to value.title,
            "rationale" to value.rationale,
            "target_node_id" to value.target_node_id,
            "file" to value.file,
        )
        is AgentFitnessResult -> jObject(
            "overall_score" to value.overall_score,
            "subscores" to value.subscores,
            "metrics" to value.metrics,
            "issues" to value.issues,
            "recommended_actions" to value.recommended_actions,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
        )
        is ClusterFitnessResult -> jObject(
            "cluster" to value.cluster,
            "overall_score" to value.overall_score,
            "subscores" to value.subscores,
            "metrics" to value.metrics,
            "issues" to value.issues,
            "recommended_actions" to value.recommended_actions,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
        )
        is ValidationTargetItem -> jObject(
            "kind" to value.kind,
            "identifier" to value.identifier,
            "file" to value.file,
            "confidence" to value.confidence,
            "rationale" to value.rationale,
        )
        is ValidationCommandHint -> jObject(
            "label" to value.label,
            "command" to value.command,
            "working_directory" to value.working_directory,
        )
        is ValidationTargetsResult -> jObject(
            "target_node_id" to value.target_node_id,
            "edit_id" to value.edit_id,
            "validation_targets" to value.validation_targets,
            "command_hints" to value.command_hints,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "build_duration_ms" to value.build_duration_ms,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
        )
        is CapabilitiesResult -> jObject(
            "languages" to value.languages,
            "analysis_engine" to value.analysis_engine,
            "engine_version" to value.engine_version,
            "available_tools" to value.available_tools,
            "edit_operations" to value.edit_operations,
            "validation_modes" to value.validation_modes,
            "confidence_semantics" to value.confidence_semantics,
            "degraded_mode_flags" to value.degraded_mode_flags,
            "snapshot_semantics" to value.snapshot_semantics,
            "analysis_engine_capabilities" to value.analysis_engine_capabilities,
            "build_context_bundle_supported" to value.build_context_bundle_supported,
            "analysis_engine_first_backend" to value.analysis_engine_first_backend,
            "snapshot_id" to value.snapshot_id,
            "generated_at" to value.generated_at,
        )
        is ContextBundleResult -> jObject(
            "task" to value.task,
            "node_id" to value.node_id,
            "chosen_node_id" to value.chosen_node_id,
            "token_budget" to value.token_budget,
            "summary_mode" to value.summary_mode,
            "clusters" to value.clusters,
            "entrypoints" to value.entrypoints,
            "focus_nodes" to value.focus_nodes,
            "relationships" to value.relationships,
            "impact_files" to value.impact_files,
            "source_slices" to value.source_slices,
            "notes" to value.notes,
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
