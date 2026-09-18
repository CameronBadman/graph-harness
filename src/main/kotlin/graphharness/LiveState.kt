package graphharness

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

internal fun liveSecret(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }
    .let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }

class LiveSession internal constructor(
    val id: String,
    val label: String,
    val role: String,
    internal val credential: String,
    internal var touchedNanos: Long,
) {
    internal var lastSeen = Instant.now().toString()
    internal var activeOperation: String? = null
    internal var highWater = 0L
    internal val receipts = linkedMapOf<String, LiveReceipt>()
    internal var receiptBytes = 0
}

internal data class LiveReceipt(val digest: String, val result: JsonValue?, val failure: LiveFailure?) {
    val size: Int get() = result?.stringify()?.toByteArray()?.size ?: 256
}

class LiveState(
    val repositoryId: String,
    val epoch: String,
    private val manager: SnapshotManager,
    private val coordinatedWrites: Boolean = false,
    private val clock: () -> Long = System::nanoTime,
) {
    private val lock = Any()
    private val dispatcher = GraphHarnessServer(manager)
    private val sessions = linkedMapOf<String, LiveSession>()
    private val closingSessions = linkedMapOf<String, LiveSession>()
    private val leases = linkedMapOf<String, EditLease>()
    private val validationAdmission = java.util.concurrent.Semaphore(1)
    private val validationThreads = mutableMapOf<String, Thread>()
    private var snapshot = manager.current()
    private var graph = graphView(snapshot)
    private var indexing = "idle"
    private var indexingFailure: String? = null
    private var nextEvent = 0L
    private val journal = ArrayDeque<Pair<Long, String>>()
    private var journalBytes = 0
    private val readTools = setOf(
        "get_capabilities", "get_summary_map", "get_cluster_detail", "search_graph", "get_node_detail",
        "get_call_paths", "get_callers", "get_callees", "get_implementations", "get_type_hierarchy",
        "get_dependencies", "get_impact", "get_source", "get_source_batch", "build_context_bundle",
        "get_snapshot_delta",
    )
    internal var beforeEditAtomicMove: (() -> Unit)? = null
    private val edits by lazy {
        LiveEdits(manager, epoch, clock, authorize = { owner -> synchronized(lock) {
            val session = sessions[owner] ?: throw LiveFailure("session_expired", 401, "Session expired.")
            requireLive(session)
            if (session.role != "agent") throw LiveFailure("forbidden", 403, "Agent credentials required.")
        } }, transition = { transition -> synchronized(lock) {
            val actor = transition.requester ?: transition.lease.owner
            append("lease_${transition.type.name.lowercase()}", sessions[actor] ?: closingSessions[actor],
                files = listOf(transition.lease.file), lease = transition.lease)
            when (transition.type) {
                LeaseTransitionType.ACQUIRED, LeaseTransitionType.RENEWED -> leases[transition.lease.id] = transition.lease
                LeaseTransitionType.RELEASED, LeaseTransitionType.EXPIRED -> leases.remove(transition.lease.id)
                LeaseTransitionType.DENIED -> Unit
            }
        } }, lookupLease = { id -> synchronized(lock) { leases[id] } }, onPlanned = { owner, node, file, edit -> synchronized(lock) {
            append("edit_planned", sessions[owner], nodes = listOf(node), files = listOf(file), editId = edit)
        } }, beforeAtomicMove = { beforeEditAtomicMove?.invoke() })
    }

    fun createSession(label: String, role: String): LiveSession = synchronized(lock) {
        require(role in setOf("agent", "observer"))
        if (label.isBlank() || label.length > 80 || label.any { it.isISOControl() }) {
            throw LiveFailure("invalid_request", 400, "A display label of 1–80 printable characters is required.")
        }
        if (sessions.size >= 64) throw LiveFailure("session_limit", 429, "Session limit reached.")
        LiveSession(UUID.randomUUID().toString(), label, role, liveSecret(), clock()).also {
            sessions[it.id] = it
            append("session_connected", it)
        }
    }

    fun authenticate(credential: String): LiveSession = synchronized(lock) {
        val session = sessions.values.firstOrNull {
            java.security.MessageDigest.isEqual(it.credential.toByteArray(), credential.toByteArray())
        } ?: throw LiveFailure("session_expired", 401, "Authenticate a current session.")
        requireLive(session)
        session
    }

    fun heartbeat(session: LiveSession) {
        val owned = synchronized(lock) {
            requireLive(session)
            session.touchedNanos = clock()
            session.lastSeen = Instant.now().toString()
            leases.values.filter { it.owner == session.id }
        }
        if (coordinatedWrites) edits.renewOwned(session.id, owned)
    }

    fun closeSession(session: LiveSession) {
        synchronized(lock) {
            if (sessions.remove(session.id) != null) {
                validationThreads[session.id]?.interrupt()
                closingSessions[session.id] = session
                append("session_disconnected", session)
            }
        }
        try { if (coordinatedWrites) edits.closeOwner(session.id) }
        finally { synchronized(lock) { closingSessions.remove(session.id) } }
    }

    fun expire() {
        val expired = synchronized(lock) {
            sessions.values.filter { clock() - it.touchedNanos >= 30_000_000_000L }.toList().also { old -> old.forEach {
                sessions.remove(it.id)
                validationThreads[it.id]?.interrupt()
                closingSessions[it.id] = it
                append("session_expired", it)
            } }
        }
        expired.forEach { session ->
            try { if (coordinatedWrites) edits.closeOwner(session.id) }
            finally { synchronized(lock) { closingSessions.remove(session.id) } }
        }
        if (coordinatedWrites) edits.expire()
    }

    fun publishSnapshot() {
        val latest = manager.current()
        val failure = manager.lastBuildFailure()
        val phase = if (failure != null) "failed" else if (manager.snapshotState().pending_rebuild) "pending" else "idle"
        val changed = synchronized(lock) { latest.epoch > snapshot.epoch }
        val prepared = if (changed) graphView(latest) else null
        synchronized(lock) {
            if (latest.epoch < snapshot.epoch) return
            if (latest.id != snapshot.id || phase != indexing || failure != indexingFailure) {
                if (latest.id != snapshot.id) graph = prepared ?: return
                snapshot = latest
                indexing = phase
                indexingFailure = failure
                append(if (phase == "failed") "indexing_failed" else "snapshot_updated", null)
            }
        }
    }

    fun definitions(): List<ToolDefinition> = dispatcher.toolDefinitions().filter {
        it.name in readTools && it.name in manager.capabilities().available_tools
    } + if (coordinatedWrites) LiveEdits.definitions() + validationDefinition() else emptyList()

    fun editPreview(session: LiveSession, id: String): JObject {
        synchronized(lock) { requireLive(session) }
        if (!coordinatedWrites) throw LiveFailure("unsupported_operation", 422, "Coordinated edits are disabled.")
        return envelope("result" to edits.preview(id))
    }

    private fun validationDefinition() = ToolDefinition("validate_project", "Run an explicitly selected validation command on a hashed working-tree copy in an offline Linux filesystem/process sandbox with resource limits. Mode is caller-declared; inspect command, exit code, scope, isolation limits and stale evidence. No fallback is counted as tests.",
        mapOf("type" to "object", "additionalProperties" to false, "required" to listOf("command", "mode"), "properties" to mapOf(
            "command" to mapOf("type" to "array", "items" to mapOf("type" to "string")),
            "mode" to mapOf("type" to "string", "enum" to listOf("test", "compile")))))

    fun inspect(session: LiveSession, name: String, arguments: JObject, requestId: String): JObject {
        if (name !in setOf("get_source", "search_graph", "get_node_detail")) {
            throw LiveFailure("unsupported_operation", 422, "This operation is unavailable for inspection.")
        }
        if (requestId.isBlank() || requestId.length > 80) throw LiveFailure("invalid_request", 400, "Invalid request_id.")
        val inspectionId = UUID.randomUUID().toString()
        val result = execute(session, name, arguments, null, inspectionId, requestId)
        return envelope("request_id" to requestId, "inspection_id" to inspectionId, "result" to result)
    }

    fun call(session: LiveSession, name: String, arguments: JObject, operationId: String): JObject {
        if (session.role != "agent") throw LiveFailure("forbidden", 403, "Agent credentials required.")
        val sequence = operationId.toLongOrNull()?.takeIf { it > 0 && it.toString() == operationId }
            ?: throw LiveFailure("invalid_request", 400, "operation_id must be a positive decimal integer.")
        val digest = sha256(canonicalJson(jObject("name" to name, "arguments" to arguments)))
        synchronized(lock) {
            requireLive(session)
            session.receipts[operationId]?.let { (previous, result, failure) ->
                if (previous != digest) throw LiveFailure("operation_reused", 409, "Operation payload differs from its original request.")
                if (failure != null) throw failure
                return envelope("operation_id" to operationId, "result" to result)
            }
            if (session.activeOperation != null) throw LiveFailure("operation_in_progress", 409, "One operation per session may run at a time.")
            if (sequence <= session.highWater) throw LiveFailure("operation_expired", 409, "This operation cannot be executed again.")
            session.highWater = sequence
            session.activeOperation = operationId
        }
        try {
            val result = execute(session, name, arguments, operationId, null, onCommitted = { result, node, file -> synchronized(lock) {
                remember(session, operationId, LiveReceipt(digest, result, null))
                indexing = "pending"
                append("edit_applied", session, operationId, name, listOf(node), listOf(file), editId = result.optionalString("edit_id"))
            } })
            synchronized(lock) {
                remember(session, operationId, LiveReceipt(digest, result, null))
            }
            return envelope("operation_id" to operationId, "result" to result)
        } catch (failure: LiveFailure) {
            synchronized(lock) {
                session.receipts[operationId]?.result?.let { committed ->
                    if ((committed as? JObject)?.get("committed") == JBoolean(true)) return envelope("operation_id" to operationId, "result" to committed)
                }
            }
            if (coordinatedWrites && name == "apply_edit") edits.cleanupFailedApply(session.id, arguments)
            synchronized(lock) { remember(session, operationId, LiveReceipt(digest, null, failure)) }
            throw failure
        } finally {
            synchronized(lock) { session.activeOperation = null }
        }
    }

    private fun execute(session: LiveSession, name: String, arguments: JObject, operationId: String?, inspectionId: String?, requestId: String? = null,
        onCommitted: (JObject, String, String) -> Unit = { _, _, _ -> }): JsonValue {
        val prefix = if (inspectionId == null) "tool" else "inspection"
        val started = clock()
        synchronized(lock) {
            requireLive(session)
            append("${prefix}_started", session, operationId, name, inspectionId = inspectionId, requestId = requestId)
        }
        try {
            val definition = definitions().firstOrNull { it.name == name }
                ?: throw LiveFailure("unsupported_operation", 422, "This tool is unavailable for the current backend or daemon mode.")
            validateArguments(arguments, definition.inputSchema)
            val result = if (coordinatedWrites && name in LiveEdits.toolNames) {
                edits.invoke(session.id, name, arguments, onCommitted)
            } else if (coordinatedWrites && name == "validate_project") {
                validateProject(session, arguments, operationId)
            } else if (name == "get_capabilities") {
                val original = dispatcher.invokeTool(name, arguments).asObject()
                val available = definitions().map { it.name }.toSet()
                JObject(LinkedHashMap(original.fields).apply {
                    put("available_tools", jValue(available.toList()))
                    put("disabled_tools", jValue(dispatcher.toolDefinitions().map { it.name }.filter { it !in available }))
                    put("semantic_level", JString(if (manager.current().analysisEngine == "joern") "best_effort" else "approximate"))
                    put("tool_guarantees", JObject(LinkedHashMap(original.optionalObject("tool_guarantees")!!.fields.filterKeys { it in available })))
                    put("stability_guarantees", jValue(listOf("Snapshot identity and source hashes identify the returned version; source requests reject disk drift.")))
                    put("best_effort_guarantees", jValue(listOf("Call and dependency edges may be incomplete or unresolved.", "Snapshot delta correspondence is heuristic.")))
                    put("analysis_engine_capabilities", jValue(listOf("Java structural navigation", "snapshot-based traversal", "retained source retrieval")))
                    put("edit_operations", jValue(if (coordinatedWrites) listOf("replace_body") else emptyList<String>()))
                    put("validation_modes", jValue(if (coordinatedWrites) listOf("test", "compile") else emptyList<String>()))
                    put("coordinated_writes", JBoolean(coordinatedWrites))
                })
            } else dispatcher.invokeTool(name, arguments)
            if (result.stringify().toByteArray().size > 64 * 1024) {
                throw LiveFailure("result_limit", 413, "Result exceeds 64 KiB; narrow the request.")
            }
            if (name !in setOf("apply_edit", "replace_node_body")) publishSnapshot()
            val references = resultReferences(result)
            val nodeIds = if (name == "get_source") listOfNotNull(arguments.optionalString("node_id")) else references.first
            synchronized(lock) {
                append("${prefix}_completed", session, operationId, name, nodeIds, references.second,
                    (clock() - started) / 1_000_000, inspectionId, (result as? JObject)?.optionalString("snapshot_id"), requestId)
            }
            return result
        } catch (failure: Exception) {
            synchronized(lock) {
                val committed = session.receipts[operationId]?.result as? JObject
                if (committed?.get("committed") == JBoolean(true)) {
                    append("${prefix}_completed", session, operationId, name, duration = (clock() - started) / 1_000_000)
                    return JObject(LinkedHashMap(committed.fields).apply { put("diagnostic", JString("Write committed; later processing failed. Refresh current source.")) })
                }
                append("${prefix}_failed", session, operationId, name, duration = (clock() - started) / 1_000_000, inspectionId = inspectionId, requestId = requestId,
                    errorCode = (failure as? LiveFailure)?.code ?: "tool_failed")
                if (name in setOf("plan_edit", "apply_edit", "replace_node_body") && coordinatedWrites) append("edit_rejected", session, operationId, name,
                    errorCode = (failure as? LiveFailure)?.code ?: "tool_failed")
            }
            if (failure is LiveFailure) throw failure
            val stale = failure.message?.startsWith("stale_source:") == true
            throw LiveFailure(if (stale) "stale_source" else "tool_failed", if (stale) 409 else 422,
                if (stale) "Source changed; wait for indexing and refresh." else "Tool could not complete for these arguments.")
        }
    }

    private fun validateProject(session: LiveSession, arguments: JObject, operationId: String?): JObject {
        val command = (arguments["command"] as JArray).values.map { (it as JString).value }
        if (!validationAdmission.tryAcquire()) throw LiveFailure("validation_busy", 409, "Another validation is running; retry after it completes.")
        try {
            synchronized(lock) {
                requireLive(session)
                validationThreads[session.id] = Thread.currentThread()
                append("validation_started", session, operationId, "validate_project")
            }
            val evidence = ValidationRunner(manager.current().root).run(command, arguments.requiredString("mode"))
            val passed = evidence.exitCode == 0 && !evidence.timedOut
            synchronized(lock) { append(if (passed) "validation_completed" else "validation_failed", session, operationId, "validate_project") }
            return jObject("manifest_id" to evidence.manifestId, "input_count" to evidence.inputSummary.fileCount,
                "input_bytes" to evidence.inputSummary.totalBytes, "command" to evidence.actualCommand,
                "declared_mode" to evidence.mode, "scope" to evidence.scope, "exit_code" to (evidence.exitCode ?: JNull),
                "command_passed" to passed, "stale" to evidence.stale, "timed_out" to evidence.timedOut,
                "offline_requested" to evidence.offlineRequested, "network_namespace" to evidence.networkIsolated,
                "filesystem_namespace" to evidence.filesystemIsolated, "resource_limits" to evidence.resourceLimits,
                "isolation_limitations" to evidence.isolationLimitations,
                "output_tail" to evidence.outputTail.takeLast(6000), "output_truncated" to (evidence.outputTail.length > 6000),
                "excluded_input_count" to evidence.excludedInputDiagnostics.size, "omitted_input_count" to evidence.omittedInputDiagnostics.size,
                "excluded_inputs" to evidence.excludedInputDiagnostics.take(32).map { mapOf("path" to it.path, "reason" to it.reason) },
                "omitted_inputs" to evidence.omittedInputDiagnostics.take(32).map { mapOf("path" to it.path, "reason" to it.reason) },
                "toolchain_scope" to evidence.toolchainMountScope)
        } catch (failure: ValidationFailure) {
            synchronized(lock) { append(if (failure.code == "validation_cancelled") "validation_cancelled" else "validation_failed", session, operationId, "validate_project", errorCode = failure.code) }
            throw LiveFailure(failure.code, 422, "Validation could not complete for the captured inputs.")
        } finally {
            synchronized(lock) { validationThreads.remove(session.id) }
            validationAdmission.release()
        }
    }

    private fun graphView(value: Snapshot): JObject {
        val nodes = mutableListOf<JObject>()
        val visibleIds = hashSetOf<String>()
        var bytes = 0
        for (node in value.nodeSummaries.values) {
            val encoded = jObject("id" to node.id, "kind" to node.kind, "language" to node.language, "name" to node.name,
                "qualified_name" to node.qualified_name, "file" to node.file, "parent" to (node.parent ?: JNull),
                "byte_span" to (node.byte_span ?: JNull), "line_range" to node.line_range, "file_hash" to value.fileHashes[node.file],
                "provenance" to (node.provenance ?: value.analysisEngine))
            val size = encoded.stringify().toByteArray().size + 1
            if (nodes.size >= 10_000 || bytes + size > 512 * 1024) continue
            bytes += size
            nodes += encoded
            visibleIds += node.id
        }
        val edges = mutableListOf<JObject>()
        for (edge in value.edges) {
            if (edge.from !in visibleIds || edge.to !in visibleIds) continue
            val encoded = jObject("from" to edge.from, "to" to edge.to, "relationship" to edge.relationship,
                "provenance" to (edge.provenance ?: value.analysisEngine), "resolution" to (edge.resolution ?: "best_effort"))
            val size = encoded.stringify().toByteArray().size + 1
            if (edges.size >= 20_000 || bytes + size > 760 * 1024) continue
            bytes += size
            edges += encoded
        }
        return jObject("snapshot_id" to value.id, "generated_at" to value.generatedAt,
            "analysis_engine" to value.analysisEngine, "semantic_level" to if (value.analysisEngine == "joern") "best_effort" else "approximate",
            "language_adapters" to value.adapterInfo,
            "omitted_file_count" to value.omittedFileCount, "omitted_node_count" to maxOf(0, value.nodeSummaries.size - nodes.size),
            "nodes" to nodes, "omitted_edge_count" to value.edges.size - edges.size, "edges" to edges)
    }

    fun state(): JObject = synchronized(lock) {
        envelope(
            "repository_id" to repositoryId, "repository_name" to snapshot.root.fileName.toString(),
            "cursor" to nextEvent.toString(),
            "snapshot" to JObject(LinkedHashMap(graph.fields).apply {
                put("indexing", JString(indexing))
                put("diagnostics", jValue(listOfNotNull(if (snapshot.nodeSummaries.values.any { it.byte_span == null }) "Some nodes lack parser-confirmed byte spans; inspect their backend provenance." else null, indexingFailure) +
                    snapshot.sourceDiagnostics.take(32).map { "${it.file}: ${it.reason}".take(200) }))
                put("omitted_diagnostic_count", jValue(maxOf(0, snapshot.sourceDiagnostics.size - 32)))
            }),
            "sessions" to sessions.values.map { jObject("session_id" to it.id, "agent_label" to it.label, "role" to it.role,
                "connection" to "connected", "last_seen" to it.lastSeen, "active_operation_id" to (it.activeOperation ?: JNull)) },
            "leases" to leases.values.map(::leaseJson),
            "capabilities" to jObject("languages" to (listOf("java") + snapshot.adapterInfo.filterValues { it.available }.keys).distinct(),
                "language_adapters" to snapshot.adapterInfo, "coordinated_writes" to coordinatedWrites,
                "disabled_tools" to if (coordinatedWrites) listOf("validate_edit", "rename_node") else listOf("apply_edit", "plan_edit", "replace_node_body", "validate_edit", "rename_node")),
        )
    }

    fun eventsAfter(cursor: Long): Pair<Boolean, List<Pair<Long, String>>> = synchronized(lock) {
        val first = journal.firstOrNull()?.first ?: nextEvent + 1
        if (cursor < first - 1 || cursor > nextEvent || cursor < 0) true to emptyList()
        else false to journal.filter { it.first > cursor }
    }

    fun cursor(): Long = synchronized(lock) { nextEvent }

    fun envelope(vararg pairs: Pair<String, Any?>): JObject = jObject("schema_version" to 1, "daemon_epoch" to epoch, *pairs)

    private fun remember(session: LiveSession, operationId: String, receipt: LiveReceipt) {
        session.receipts.put(operationId, receipt)?.let { session.receiptBytes -= it.size }
        session.receiptBytes += receipt.size
        while (session.receipts.size > 256 || session.receiptBytes > 8 * 1024 * 1024) {
            session.receiptBytes -= session.receipts.remove(session.receipts.keys.first())!!.size
        }
    }

    private fun requireLive(session: LiveSession) {
        if (sessions[session.id] !== session || clock() - session.touchedNanos >= 30_000_000_000L) {
            throw LiveFailure("session_expired", 401, "Authenticate a current session.")
        }
    }

    private fun append(type: String, session: LiveSession?, operationId: String? = null, tool: String? = null,
        nodes: List<String> = emptyList(), files: List<String> = emptyList(), duration: Long? = null,
        inspectionId: String? = null, snapshotId: String? = null, requestId: String? = null, lease: EditLease? = null, errorCode: String? = null, editId: String? = null) {
        val id = ++nextEvent
        var keptNodes = nodes.take(64)
        var keptFiles = files.take(32)
        fun event() = envelope("event_id" to id.toString(), "repository_id" to repositoryId, "timestamp" to Instant.now().toString(),
            "session_id" to (session?.id ?: JNull), "agent_label" to (session?.label ?: JNull), "role" to (session?.role ?: JNull),
            "operation_id" to (operationId ?: JNull), "inspection_id" to inspectionId, "request_id" to requestId, "event_type" to type, "tool_name" to tool?.take(80),
            "snapshot_id" to (snapshotId ?: snapshot.id), "node_ids" to keptNodes, "file_paths" to keptFiles,
            "lease" to lease?.let(::leaseJson),
            "edit_id" to editId,
            "error" to errorCode?.let { jObject("code" to it) },
            "omitted_node_count" to nodes.size - keptNodes.size, "omitted_file_count" to files.size - keptFiles.size,
            "status" to type.substringAfterLast('_'), "duration_ms" to duration)
        var encoded = event().stringify()
        while (encoded.toByteArray().size > 16 * 1024 && (keptNodes.isNotEmpty() || keptFiles.isNotEmpty())) {
            if (keptFiles.isNotEmpty()) keptFiles = keptFiles.dropLast(1) else keptNodes = keptNodes.dropLast(1)
            encoded = event().stringify()
        }
        journal.addLast(id to encoded)
        journalBytes += encoded.toByteArray().size
        while (journal.size > 2000 || journalBytes > 4 * 1024 * 1024) {
            journalBytes -= journal.removeFirst().second.toByteArray().size
        }
    }

    private fun resultReferences(result: JsonValue): Pair<List<String>, List<String>> {
        val nodes = linkedSetOf<String>()
        val files = linkedSetOf<String>()
        fun walk(value: JsonValue) {
            when (value) {
                is JObject -> {
                    (value["node_id"] as? JString)?.value?.let(nodes::add)
                    if (value["kind"] is JString) (value["id"] as? JString)?.value?.let(nodes::add)
                    (value["file"] as? JString)?.value?.let(files::add)
                    value.fields.filterKeys { it !in setOf("source", "task") }.values.forEach(::walk)
                }
                is JArray -> value.values.forEach(::walk)
                else -> Unit
            }
        }
        walk(result)
        return nodes.toList() to files.toList()
    }
}

internal fun canonicalJson(value: JsonValue): String = when (value) {
    is JObject -> value.fields.toSortedMap().entries.joinToString(",", "{", "}") { JString(it.key).stringify() + ":" + canonicalJson(it.value) }
    is JArray -> value.values.joinToString(",", "[", "]", transform = ::canonicalJson)
    else -> value.stringify()
}

internal fun validateArguments(arguments: JObject, schema: Map<String, Any?>) {
    val properties = schema["properties"] as? Map<*, *> ?: emptyMap<Any, Any>()
    val required = schema["required"] as? List<*> ?: emptyList<Any>()
    if (arguments.fields.keys.any { it !in properties } || required.any { it !in arguments.fields }) {
        throw LiveFailure("invalid_request", 400, "Missing or unsupported tool arguments.")
    }
    arguments.fields.forEach { (key, value) ->
        val rule = properties[key] as? Map<*, *> ?: emptyMap<Any, Any>()
        val valid = when (rule["type"]) {
            "string" -> value is JString && value.value.length <= 4096 && ((rule["enum"] as? List<*>)?.contains(value.value) != false)
            "integer" -> value is JNumber && value.raw.toLongOrNull()?.let { n ->
                n >= ((rule["minimum"] as? Number)?.toLong() ?: Long.MIN_VALUE) && n <= ((rule["maximum"] as? Number)?.toLong() ?: Long.MAX_VALUE)
            } == true
            "array" -> value is JArray && value.values.size in 1..200 && value.values.all { it is JString && it.value.length <= 4096 }
            "object" -> value is JObject
            else -> false
        }
        if (!valid) throw LiveFailure("invalid_request", 400, "Invalid tool argument: $key")
    }
}
