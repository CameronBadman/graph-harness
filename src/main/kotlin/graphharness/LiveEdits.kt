package graphharness

import java.util.UUID

internal class LiveEdits(
    private val manager: SnapshotManager,
    epoch: String,
    private val clock: () -> Long,
    authorize: (String) -> Unit,
    transition: (LeaseTransition) -> Unit,
    private val lookupLease: (String) -> EditLease?,
    private val onPlanned: (String, String, String, String) -> Unit,
    private val queueIndex: (String) -> Unit = manager::queueRefresh,
    beforeAtomicMove: (() -> Unit)? = null,
) {
    private val coordinator = EditCoordinator(manager.current().root, epoch, clock, authorize, transition, beforeAtomicMove)
    private val planLock = Any()
    private val plans = linkedMapOf<String, Plan>()
    private var retainedBytes = 0

    fun closeOwner(owner: String) {
        coordinator.closeOwner(owner)
        synchronized(planLock) {
            plans.values.filter { it.owner == owner }.map { it.id }.forEach(::removePlan)
        }
    }

    fun expire() {
        coordinator.expire()
        synchronized(planLock) { purge() }
    }

    fun cleanupFailedApply(owner: String, arguments: JObject) {
        val id = arguments.optionalString("lease_id") ?: return
        val generation = arguments.optionalString("generation") ?: return
        runCatching { coordinator.release(owner, id, generation) }
    }

    fun preview(id: String): JObject = synchronized(planLock) {
        purge()
        preview(plans[id] ?: throw LiveFailure("stale_plan", 404, "Edit preview is no longer retained."))
    }

    fun renewOwned(owner: String, leases: List<EditLease>) {
        leases.filter { it.owner == owner }.forEach { lease ->
            try { coordinator.renew(owner, lease.id, lease.generation) } catch (failure: EditCoordinatorFailure) {
                if (failure.code !in setOf("lease_expired", "not_owner")) throw failure
            }
        }
    }

    fun invoke(owner: String, name: String, arguments: JObject, committed: (JObject, String, String) -> Unit): JObject = try {
        when (name) {
            "acquire_edit_lease" -> {
                val file = arguments.requiredString("file")
                if (!file.endsWith(".java")) throw LiveFailure("unsupported_operation", 422, "Only indexed Java files can be reserved.")
                val hash = manager.current().fileHashes[file] ?: throw LiveFailure("unsupported_operation", 422, "Only indexed Java files can be reserved.")
                leaseJson(coordinator.acquire(owner, file, hash))
            }
            "renew_edit_lease" -> leaseJson(coordinator.renew(owner, arguments.requiredString("lease_id"), arguments.requiredString("generation")))
            "release_edit_lease" -> jObject("released" to coordinator.release(owner, arguments.requiredString("lease_id"), arguments.requiredString("generation")))
            "plan_edit" -> preview(prepare(owner, arguments))
            "apply_edit" -> apply(owner, arguments, committed)
            "replace_node_body" -> replaceNodeBody(owner, arguments, committed)
            else -> throw LiveFailure("unsupported_operation", 422, "Unsupported coordinated edit operation.")
        }
    } catch (failure: EditCoordinatorFailure) {
        val status = when (failure.code) {
            "lease_busy", "lease_expired", "stale_source" -> 409
            "not_owner" -> 403
            else -> 422
        }
        throw LiveFailure(failure.code, status, failure.message.orEmpty(), jValue(failure.details) as JObject)
    }

    private fun prepare(owner: String, arguments: JObject): Plan {
        val snapshot = manager.current()
        if (arguments.requiredString("snapshot_id") != snapshot.id) throw LiveFailure("stale_source", 409, "Refresh source before planning against a new snapshot.")
        val nodeId = arguments.requiredString("node_id")
        val target = snapshot.methodInfos[nodeId] ?: throw LiveFailure("unsupported_operation", 422, "A concrete Java method is required.")
        val source = snapshot.sourceFiles[target.file] ?: throw LiveFailure("stale_source", 409, "Source is unavailable; refresh first.")
        val body = SafeJavaEditor.plan(source, target, arguments.requiredString("expected_file_hash"), arguments.requiredString("new_body"), snapshot.sourceFiles)
        var currentPath = snapshot.root
        java.nio.file.Path.of(target.file).forEach { part ->
            currentPath = currentPath.resolve(part)
            if (java.nio.file.Files.isSymbolicLink(currentPath)) throw LiveFailure("unsupported_operation", 422, "Symlinked edit inputs are unsupported.")
        }
        if (!currentPath.toRealPath().startsWith(snapshot.root)) throw LiveFailure("unsupported_operation", 422, "Edit input escaped the repository.")
        val sourceOnDisk = readRetainedUtf8(currentPath, 1_048_576)
        if (sourceOnDisk.hash != source.hash) {
            queueIndex(target.file)
            throw LiveFailure("stale_source", 409, "Source changed before planning.")
        }
        val edit = Plan(UUID.randomUUID().toString(), owner, nodeId, snapshot.id, body, clock() + 120_000_000_000L)
        synchronized(planLock) {
            purge()
            if (plans.size >= 64 || retainedBytes + edit.size > 16 * 1024 * 1024) throw LiveFailure("plan_limit", 429, "Release old plans or wait for expiry before creating more.")
            plans[edit.id] = edit
            retainedBytes += edit.size
        }
        try { onPlanned(owner, nodeId, body.file, edit.id) }
        catch (failure: Exception) {
            synchronized(planLock) { removePlan(edit.id) }
            throw failure
        }
        return edit
    }

    private fun replaceNodeBody(owner: String, arguments: JObject, committed: (JObject, String, String) -> Unit): JObject {
        val edit = prepare(owner, arguments)
        var lease: EditLease? = null
        var result: JObject? = null
        var failure: Exception? = null
        try {
            lease = coordinator.acquire(owner, edit.body.file, edit.body.baseHash)
            result = apply(owner, jObject("edit_id" to edit.id, "lease_id" to lease.id, "generation" to lease.generation), committed, compact = true)
        } catch (error: Exception) {
            failure = error
            throw error
        } finally {
            lease?.let { acquired ->
                try {
                    coordinator.releaseAfterOperation(owner, acquired.id, acquired.generation)
                } catch (cleanup: Exception) {
                    failure?.addSuppressed(cleanup)
                    result = result?.let { JObject(LinkedHashMap(it.fields).apply {
                        put("lease_cleanup", JString("pending"))
                        put("diagnostic", JString("Write committed; reservation cleanup failed and will expire within its bounded lifetime."))
                    }) }
                }
            }
            if (failure != null) synchronized(planLock) { if (!edit.applied) removePlan(edit.id) }
        }
        return checkNotNull(result)
    }

    private fun preview(edit: Plan): JObject {
        val body = edit.body
        val before = body.preimageBytes().copyOfRange(body.bodyStartByte, body.bodyEndByte)
        val after = body.replacementUtf8()
        return jObject("edit_id" to edit.id, "node_id" to edit.nodeId, "file" to body.file, "snapshot_id" to edit.snapshotId,
            "base_hash" to body.baseHash, "new_hash" to sha256(body.updatedBytes()), "operation" to "replace_body",
            "byte_span" to jObject("start" to body.bodyStartByte, "end" to body.bodyEndByte),
            "preview" to jObject("before" to decodeUtf8(before).take(3000), "after" to decodeUtf8(after).take(3000),
                "truncated" to (decodeUtf8(before).length > 3000 || decodeUtf8(after).length > 3000)),
            "syntax_checked" to true, "project_tests_run" to false, "committed" to edit.applied,
            "expires_in_ms" to maxOf(0, (edit.expires - clock()) / 1_000_000))
    }

    private fun apply(owner: String, arguments: JObject, committed: (JObject, String, String) -> Unit, compact: Boolean = false): JObject {
        val edit = synchronized(planLock) {
            purge()
            val found = plans[arguments.requiredString("edit_id")] ?: throw LiveFailure("stale_plan", 409, "Edit plan expired or is unavailable.")
            if (found.owner != owner) throw LiveFailure("not_owner", 403, "Edit plan belongs to another session.")
            found
        }
        val leaseId = arguments.requiredString("lease_id")
        val lease = lookupLease(leaseId) ?: throw LiveFailure("lease_expired", 409, "Lease is unavailable.")
        if (lease.file != edit.body.file) throw LiveFailure("stale_plan", 409, "Lease must cover the plan's exact file.")
        if (edit.applied) throw LiveFailure("stale_plan", 409, "This plan was already applied; replay the original operation instead.")
        val updated = edit.body.updatedBytes()
        var result = if (compact) jObject("edit_id" to edit.id, "node_id" to edit.nodeId, "file" to edit.body.file, "committed" to true,
            "file_hash" to sha256(updated), "indexing" to "pending", "syntax_checked" to true, "project_tests_run" to false)
        else jObject("edit_id" to edit.id, "file" to edit.body.file, "plan_snapshot_id" to edit.snapshotId, "committed" to true,
            "file_hash" to sha256(updated), "indexing" to "pending", "project_tests_run" to false)
        val outcome = coordinator.commit(owner, leaseId, arguments.requiredString("generation"), edit.body.baseHash, updated) {
            synchronized(planLock) { edit.applied = true }
            committed(result, edit.nodeId, edit.body.file)
        }
        if (outcome.callbackError != null) result = withIndexingFailure(result, "Post-commit event recording failed; reread the file.")
        try { queueIndex(edit.body.file) } catch (_: Exception) { result = withIndexingFailure(result, "Write committed; indexing could not be queued.") }
        return result
    }

    private fun withIndexingFailure(result: JObject, message: String): JObject = JObject(LinkedHashMap(result.fields).apply {
        put("indexing", JString("failed")); put("diagnostic", JString(message))
    })

    private fun purge() { plans.values.filter { it.expires <= clock() }.map { it.id }.forEach(::removePlan) }
    private fun removePlan(id: String) { plans.remove(id)?.let { retainedBytes -= it.size } }

    private class Plan(val id: String, val owner: String, val nodeId: String, val snapshotId: String, val body: SafeJavaBodyPlan, val expires: Long) {
        val size = body.preimageBytes().size + body.updatedBytes().size + body.replacementUtf8().size
        @Volatile var applied = false
    }

    companion object {
        val toolNames = setOf("acquire_edit_lease", "renew_edit_lease", "release_edit_lease", "plan_edit", "apply_edit", "replace_node_body")
        fun definitions(): List<ToolDefinition> {
            fun definition(name: String, description: String, properties: Map<String, Any?>) = ToolDefinition(name, description,
                mapOf("type" to "object", "properties" to properties, "required" to properties.keys.toList(), "additionalProperties" to false))
            val string = mapOf("type" to "string")
            return listOf(
                definition("acquire_edit_lease", "Reserve one indexed Java file for this session. Nonblocking; a busy response names the holder. Maximum hold is 120 seconds.", mapOf("file" to string)),
                definition("renew_edit_lease", "Renew an owned file reservation, within its original maximum hold.", mapOf("lease_id" to string, "generation" to string)),
                definition("release_edit_lease", "Release an owned file reservation.", mapOf("lease_id" to string, "generation" to string)),
                definition("plan_edit", "Preview replacement of one parser-confirmed Java method body. Supply snapshot and file hash from fresh source. No write or project tests occur.", mapOf("node_id" to string, "snapshot_id" to string, "expected_file_hash" to string, "new_body" to string)),
                definition("apply_edit", "Atomically apply this session's single-file plan under its reservation. Checks current bytes and fencing; committed and indexing status are separate.", mapOf("edit_id" to string, "lease_id" to string, "generation" to string)),
                definition("replace_node_body", "Replace one parser-confirmed Java method body in one call. Supply fresh node, snapshot and file hash; acquire and release a brief exclusive file reservation internally. Existing reservations return lease_busy. Supply body statements only, at most 4096 characters. Returns commit status and hash; checks syntax, not project tests. Do not retry uncertain writes without fresh source.", mapOf("node_id" to string, "snapshot_id" to string, "expected_file_hash" to string, "new_body" to string)),
            )
        }
    }
}

internal fun leaseJson(lease: EditLease): JObject = jObject("file" to lease.file, "lease_id" to lease.id,
    "generation" to lease.generation, "daemon_epoch" to lease.epoch, "session_id" to lease.owner,
    "expected_file_hash" to lease.expectedHash, "expires_at" to lease.expiresUtc, "remaining_ms" to lease.remainingMs)
