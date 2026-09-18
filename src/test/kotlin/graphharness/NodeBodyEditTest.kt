package graphharness

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeBodyEditTest {
    @Test
    fun oneCallCommitsExactlyOncePreservesOverloadsAndRetainsOnlyTheUiPreview() {
        val root = fixture()
        SnapshotManager(root, useJoern = false).use { manager ->
            val state = LiveState("repo", "epoch", manager, coordinatedWrites = true)
            val agent = state.createSession("agent", "agent")
            val args = arguments(manager, "return x + 7;")
            val before = root.resolve("A.java").readText()
            val mode = Files.getPosixFilePermissions(root.resolve("A.java"))
            val originalSnapshot = state.state().optionalObject("snapshot")!!.requiredString("snapshot_id")
            val receipt = state.call(agent, "replace_node_body", args, "1").optionalObject("result")!!
            val after = root.resolve("A.java").readText()
            assertTrue(after.startsWith(before.substringBefore(" return x; ")))
            assertTrue(after.endsWith(before.substringAfter(" return x; ")))
            assertTrue(after.contains("return x + 7;"))
            assertTrue(after.contains("int value() { return 1; }"))
            assertFalse(after.replace("\r\n", "").contains('\n'))
            assertEquals(mode, Files.getPosixFilePermissions(root.resolve("A.java")))
            assertEquals(setOf("edit_id", "node_id", "file", "committed", "file_hash", "indexing", "syntax_checked", "project_tests_run"), receipt.fields.keys)
            assertEquals(JBoolean(true), receipt["committed"])
            assertEquals(JBoolean(true), receipt["syntax_checked"])
            assertEquals(JBoolean(false), receipt["project_tests_run"])
            assertEquals(sha256(Files.readAllBytes(root.resolve("A.java"))), receipt.requiredString("file_hash"))
            assertEquals(originalSnapshot, state.state().optionalObject("snapshot")!!.requiredString("snapshot_id"))
            assertTrue((state.state()["leases"] as JArray).values.isEmpty())
            val observer = state.createSession("observer", "observer")
            val preview = state.editPreview(observer, receipt.requiredString("edit_id")).optionalObject("result")!!
            assertEquals(JBoolean(true), preview["committed"])
            assertTrue(preview.optionalObject("preview")!!.requiredString("before").contains("return x;"))
            assertTrue(preview.optionalObject("preview")!!.requiredString("after").contains("return x + 7;"))
            val cursor = state.cursor()
            assertEquals(receipt, state.call(agent, "replace_node_body", args, "1").optionalObject("result"))
            assertEquals(cursor, state.cursor())
            assertEquals("operation_reused", assertFailsWith<LiveFailure> {
                state.call(agent, "replace_node_body", arguments(manager, "return x + 8;"), "1")
            }.code)
            val events = state.eventsAfter(0).second.map { MiniJson.parse(it.second).asObject() }
            val applied = events.filter { it.optionalString("event_type") == "edit_applied" }.single()
            assertEquals(agent.id, applied.requiredString("session_id"))
            assertEquals("replace_node_body", applied.requiredString("tool_name"))
            assertEquals(listOf(args["node_id"]), (applied["node_ids"] as JArray).values)
            assertEquals(1, events.count { it.optionalString("event_type") == "lease_acquired" })
            assertEquals(1, events.count { it.optionalString("event_type") == "lease_released" })
            assertEquals(after, root.resolve("A.java").readText())
        }
    }

    @Test
    fun foreignAndSameOwnerReservationsAreDeniedWithoutReleasingOrRenewingThem() {
        val root = fixture()
        SnapshotManager(root, useJoern = false).use { manager ->
            val state = LiveState("repo", "epoch", manager, coordinatedWrites = true)
            val holder = state.createSession("same label", "agent")
            val other = state.createSession("same label", "agent")
            val before = root.resolve("A.java").readText()
            val lease = state.call(holder, "acquire_edit_lease", jObject("file" to "A.java"), "1").optionalObject("result")!!
            val args = arguments(manager, "return x + 2;")
            for ((session, operation) in listOf(holder to "2", other to "1")) {
                val failure = assertFailsWith<LiveFailure> { state.call(session, "replace_node_body", args, operation) }
                assertEquals("lease_busy", failure.code)
                assertEquals(holder.id, failure.details.requiredString("owner"))
                assertEquals(lease, (state.state()["leases"] as JArray).values.single())
            }
            val types = state.eventsAfter(0).second.map { MiniJson.parse(it.second).asObject().optionalString("event_type") }
            assertEquals(2, types.count { it == "edit_rejected" })
            assertFalse(types.any { it in setOf("lease_released", "lease_renewed", "edit_applied") })
            assertEquals(before, root.resolve("A.java").readText())
        }
    }

    @Test
    fun successfulNodeEditReleasesOnlyItsOwnLeaseAndPreservesAnotherFileReservation() {
        val root = fixture()
        SnapshotManager(root, useJoern = false).use { manager ->
            val state = LiveState("repo", "epoch", manager, coordinatedWrites = true, clock = { 0L })
            val agent = state.createSession("agent", "agent")
            val otherLease = state.call(agent, "acquire_edit_lease", jObject("file" to "B.java"), "1").optionalObject("result")!!
            val result = state.call(agent, "replace_node_body", arguments(manager, "return x + 2;"), "2").optionalObject("result")!!
            assertEquals(JBoolean(true), result["committed"])
            assertEquals(otherLease, (state.state()["leases"] as JArray).values.single())
        }
    }

    @Test
    fun repeatedBusyNodeEditsDoNotExhaustPlanRetentionOrModifyTheExistingLease() {
        val root = fixture()
        SnapshotManager(root, useJoern = false).use { manager ->
            val state = LiveState("repo", "epoch", manager, coordinatedWrites = true, clock = { 0L })
            val owner = state.createSession("owner", "agent")
            val requester = state.createSession("requester", "agent")
            val lease = state.call(owner, "acquire_edit_lease", jObject("file" to "A.java"), "1").optionalObject("result")!!
            val args = arguments(manager, "return x + 2;")
            repeat(70) { index ->
                assertEquals("lease_busy", assertFailsWith<LiveFailure> {
                    state.call(requester, "replace_node_body", args, (index + 1).toString())
                }.code)
                assertEquals(lease, (state.state()["leases"] as JArray).values.single())
            }
            state.call(owner, "release_edit_lease", jObject("lease_id" to lease.requiredString("lease_id"), "generation" to lease.requiredString("generation")), "2")
            val result = state.call(requester, "replace_node_body", args, "71").optionalObject("result")!!
            assertEquals(JBoolean(true), result["committed"])
            assertTrue(root.resolve("A.java").readText().contains("return x + 2;"))
            assertTrue((state.state()["leases"] as JArray).values.isEmpty())
        }
    }

    @Test
    fun staleInvalidOversizedAndUnsupportedRequestsCannotAcquireOrWrite() {
        val root = fixture()
        SnapshotManager(root, useJoern = false).use { manager ->
            val state = LiveState("repo", "epoch", manager, coordinatedWrites = true)
            val agent = state.createSession("agent", "agent")
            val original = root.resolve("A.java").readText()
            val valid = arguments(manager, "return x + 2;")
            fun changed(key: String, value: String) = JObject(LinkedHashMap(valid.fields).apply { put(key, JString(value)) })
            val cases = listOf(
                changed("snapshot_id", "old") to "stale_source",
                changed("expected_file_hash", "old") to "stale_source",
                changed("node_id", "typescript:unknown") to "unsupported_operation",
                changed("node_id", "type:A") to "unsupported_operation",
                changed("new_body", "return (; ") to "replacement_parse_error",
                changed("new_body", " ".repeat(4097)) to "invalid_request",
                changed("new_body", "} int injected() { return 2; } void another() {") to "target_not_found",
            )
            cases.forEachIndexed { index, (args, code) ->
                assertEquals(code, assertFailsWith<LiveFailure> {
                    state.call(agent, "replace_node_body", args, (index + 1).toString())
                }.code)
            }
            assertFalse(state.eventsAfter(0).second.any { MiniJson.parse(it.second).asObject().optionalString("event_type") == "lease_acquired" })
            assertEquals(original, root.resolve("A.java").readText())
            assertFalse(LiveState("readonly", "epoch", manager).definitions().any { it.name == "replace_node_body" })
        }
    }

    @Test
    fun sourceDriftBetweenPlanningReservationAndCommitIsRejectedAndOwnLeaseIsCleaned() {
        for (afterReservation in listOf(false, true)) {
            val root = fixture()
            SnapshotManager(root, useJoern = false).use { manager ->
                val external = root.resolve("A.java").readText().replace("return x;", "return x + 99;")
                val service = service(manager,
                    planned = { if (!afterReservation) root.resolve("A.java").writeText(external) },
                    transition = { if (afterReservation && it.type == LeaseTransitionType.ACQUIRED) root.resolve("A.java").writeText(external) })
                assertEquals("stale_source", assertFailsWith<LiveFailure> {
                    service.edits.invoke("agent", "replace_node_body", arguments(manager, "return x + 2;")) { _, _, _ -> error("must not commit") }
                }.code)
                assertEquals(external, root.resolve("A.java").readText())
                assertTrue(service.leases.isEmpty())
            }
        }
    }

    @Test
    fun postCommitIndexEventAndReleaseFailuresDoNotRelabelTheWrite() {
        for (failureStage in listOf("index", "event", "release")) {
            val root = fixture()
            SnapshotManager(root, useJoern = false).use { manager ->
                val service = service(manager,
                    queue = { if (failureStage == "index") error("index unavailable") },
                    transition = { if (failureStage == "release" && it.type == LeaseTransitionType.RELEASED) error("lease journal unavailable") })
                val receipt = service.edits.invoke("agent", "replace_node_body", arguments(manager, "return x + 2;")) { _, _, _ ->
                    if (failureStage == "event") error("edit journal unavailable")
                }
                assertEquals(JBoolean(true), receipt["committed"])
                assertTrue(root.resolve("A.java").readText().contains("return x + 2;"))
                assertTrue(receipt.optionalString("diagnostic") != null)
                if (failureStage == "release") {
                    assertEquals("pending", receipt.optionalString("lease_cleanup"))
                    assertTrue(service.leases.values.single().remainingMs <= 30_000)
                } else {
                    assertEquals("failed", receipt.optionalString("indexing"))
                    assertTrue(service.leases.isEmpty())
                }
            }
        }
    }

    @Test
    fun cleanupFailureCannotMaskAnEarlierStaleSourceFailure() {
        val root = fixture()
        SnapshotManager(root, useJoern = false).use { manager ->
            val service = service(manager, transition = {
                if (it.type == LeaseTransitionType.ACQUIRED) root.resolve("A.java").writeText("class External {}\n")
                if (it.type == LeaseTransitionType.RELEASED) error("cleanup unavailable")
            })
            assertEquals("stale_source", assertFailsWith<LiveFailure> {
                service.edits.invoke("agent", "replace_node_body", arguments(manager, "return x + 2;")) { _, _, _ -> error("must not commit") }
            }.code)
            assertEquals("class External {}\n", root.resolve("A.java").readText())
            assertEquals(1, service.leases.size)
        }
    }

    @Test
    fun expiryOrDisconnectBeforeCommitRejectsAndStillReleasesTheInternallyAcquiredLease() {
        for (expiry in listOf(false, true)) {
            val root = fixture()
            SnapshotManager(root, useJoern = false).use { manager ->
                val now = AtomicLong()
                var connected = true
                val service = service(manager, clock = now::get, authorize = {
                    if (!connected || now.get() >= 30_000_000_000L) throw LiveFailure("session_expired", 401, "Session expired.")
                }, transition = {
                    if (it.type == LeaseTransitionType.ACQUIRED) {
                        if (expiry) now.set(30_000_000_000L) else connected = false
                    }
                })
                val original = root.resolve("A.java").readText()
                assertEquals("session_expired", assertFailsWith<LiveFailure> {
                    service.edits.invoke("agent", "replace_node_body", arguments(manager, "return x + 2;")) { _, _, _ -> error("must not commit") }
                }.code)
                assertEquals(original, root.resolve("A.java").readText())
                assertTrue(service.leases.isEmpty())
            }
        }
    }

    @Test
    fun expiryInsideTheAtomicSectionAllowsCommitAndReleasesWithoutSessionAuthorization() {
        val root = fixture()
        SnapshotManager(root, useJoern = false).use { manager ->
            val now = AtomicLong()
            val state = LiveState("repo", "epoch", manager, coordinatedWrites = true, clock = now::get)
            val agent = state.createSession("agent", "agent")
            state.beforeEditAtomicMove = { now.set(31_000_000_000L) }
            val result = state.call(agent, "replace_node_body", arguments(manager, "return x + 2;"), "1").optionalObject("result")!!
            assertEquals(JBoolean(true), result["committed"])
            assertTrue(root.resolve("A.java").readText().contains("return x + 2;"))
            assertTrue((state.state()["leases"] as JArray).values.isEmpty())
            assertEquals("session_expired", assertFailsWith<LiveFailure> { state.authenticate(agent.credential) }.code)
        }
    }

    @Test
    fun disconnectInsideTheAtomicSectionAllowsCommitThenCompletesCleanup() {
        val root = fixture()
        SnapshotManager(root, useJoern = false).use { manager ->
            val state = LiveState("repo", "epoch", manager, coordinatedWrites = true)
            val agent = state.createSession("agent", "agent")
            val entered = CountDownLatch(1)
            val proceed = CountDownLatch(1)
            state.beforeEditAtomicMove = { entered.countDown(); check(proceed.await(5, TimeUnit.SECONDS)) }
            val outcome = AtomicReference<Result<JObject>>()
            val args = arguments(manager, "return x + 2;")
            val writer = thread { outcome.set(runCatching { state.call(agent, "replace_node_body", args, "1") }) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closer = thread { state.closeSession(agent) }
            try {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while ((state.state()["sessions"] as JArray).values.isNotEmpty() && System.nanoTime() < deadline) Thread.sleep(1)
                assertTrue((state.state()["sessions"] as JArray).values.isEmpty())
            } finally { proceed.countDown() }
            writer.join(5000)
            closer.join(5000)
            assertFalse(writer.isAlive)
            assertFalse(closer.isAlive)
            assertEquals(JBoolean(true), outcome.get().getOrThrow().optionalObject("result")!!["committed"])
            assertTrue(root.resolve("A.java").readText().contains("return x + 2;"))
            assertTrue((state.state()["leases"] as JArray).values.isEmpty())
        }
    }

    @Test
    fun receiptEvictionCannotReexecuteTheNodeWrite() {
        val root = fixture()
        SnapshotManager(root, useJoern = false).use { manager ->
            val state = LiveState("repo", "epoch", manager, coordinatedWrites = true)
            val agent = state.createSession("agent", "agent")
            val args = arguments(manager, "return x + 2;")
            state.call(agent, "replace_node_body", args, "1")
            for (id in 2..258) state.call(agent, "search_graph", jObject("query" to "absent"), id.toString())
            val before = root.resolve("A.java").readText()
            val cursor = state.cursor()
            assertEquals("operation_expired", assertFailsWith<LiveFailure> { state.call(agent, "replace_node_body", args, "1") }.code)
            assertEquals(cursor, state.cursor())
            assertEquals(before, root.resolve("A.java").readText())
            assertEquals(1, state.eventsAfter(0).second.count { MiniJson.parse(it.second).asObject().optionalString("event_type") == "edit_applied" })
        }
    }

    private fun arguments(manager: SnapshotManager, body: String): JObject {
        val snapshot = manager.current()
        val method = snapshot.methodInfos.values.single { it.file == "A.java" && it.simpleName == "value" && it.parameterTypes == listOf("int") }
        return jObject("node_id" to method.id, "snapshot_id" to snapshot.id, "expected_file_hash" to snapshot.fileHashes[method.file], "new_body" to body)
    }

    private fun fixture(): Path = createTempDirectory("graphharness-node-edit").also { root ->
        root.resolve("A.java").writeText("class A {\r\n // Astral text: 🚀 and braces: { }\r\n A() {}\r\n int value() { return 1; }\r\n int value(int x) { return x; }\r\n}\r\n")
        root.resolve("B.java").writeText("class B { int other() { return 3; } }\n")
    }

    private fun service(manager: SnapshotManager, clock: () -> Long = System::nanoTime, authorize: (String) -> Unit = {},
        planned: () -> Unit = {}, transition: (LeaseTransition) -> Unit = {}, queue: (String) -> Unit = {}): Service {
        val leases = linkedMapOf<String, EditLease>()
        val edits = LiveEdits(manager, "epoch", clock, authorize, { change ->
            transition(change)
            when (change.type) {
                LeaseTransitionType.ACQUIRED, LeaseTransitionType.RENEWED -> leases[change.lease.id] = change.lease
                LeaseTransitionType.RELEASED, LeaseTransitionType.EXPIRED -> leases.remove(change.lease.id)
                LeaseTransitionType.DENIED -> Unit
            }
        }, { leases[it] }, { _, _, _, _ -> planned() }, queueIndex = queue)
        return Service(edits, leases)
    }

    private data class Service(val edits: LiveEdits, val leases: Map<String, EditLease>)
}
