package graphharness

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LiveEditsTest {
    @Test
    fun planApplyReplayAndUnrelatedSnapshotChangePreserveTheSingleFileContract() {
        val root = fixture()
        SnapshotManager(root, useJoern = false).use { manager ->
            val state = LiveState("repo", "epoch", manager, coordinatedWrites = true)
            val agent = state.createSession("agent", "agent")
            var operation = 0
            fun call(name: String, arguments: JObject) = state.call(agent, name, arguments, (++operation).toString()).optionalObject("result")!!
            val snapshot = manager.current()
            val target = snapshot.methodInfos.values.single { it.simpleName == "value" && it.file == "A.java" }
            val planned = call("plan_edit", jObject("node_id" to target.id, "snapshot_id" to snapshot.id,
                "expected_file_hash" to snapshot.fileHashes["A.java"], "new_body" to "return 2;"))
            root.resolve("B.java").writeText("class B {\n int other() { return 9; }\n}\n")
            manager.refresh()
            assertNotEquals(snapshot.id, manager.current().id)
            val lease = call("acquire_edit_lease", jObject("file" to "A.java"))
            val args = jObject("edit_id" to planned.requiredString("edit_id"), "lease_id" to lease.requiredString("lease_id"), "generation" to lease.requiredString("generation"))
            val applied = call("apply_edit", args)
            assertEquals(JBoolean(true), applied["committed"])
            assertTrue(Files.readString(root.resolve("A.java")).contains("return 2;"))
            val committedBytes = Files.readAllBytes(root.resolve("A.java"))
            val replay = state.call(agent, "apply_edit", args, operation.toString()).optionalObject("result")!!
            assertEquals(applied, replay)
            assertTrue(committedBytes.contentEquals(Files.readAllBytes(root.resolve("A.java"))))
            val appliedEvents = state.eventsAfter(0).second.map { MiniJson.parse(it.second).asObject() }.filter { it.optionalString("event_type") == "edit_applied" }
            assertEquals(1, appliedEvents.size)
            call("release_edit_lease", jObject("lease_id" to lease.requiredString("lease_id"), "generation" to lease.requiredString("generation")))
            assertTrue((state.state()["leases"] as JArray).values.isEmpty())
        }
    }

    @Test
    fun sameHashOnDifferentFilesCannotRetargetAPlanAndForeignSessionCannotApplyIt() {
        val root = fixture()
        SnapshotManager(root, useJoern = false).use { manager ->
            val state = LiveState("repo", "epoch", manager, coordinatedWrites = true)
            val a = state.createSession("same label", "agent")
            val b = state.createSession("same label", "agent")
            val snapshot = manager.current()
            val target = snapshot.methodInfos.values.first { it.file == "A.java" && it.simpleName == "value" }
            val planned = state.call(a, "plan_edit", jObject("node_id" to target.id, "snapshot_id" to snapshot.id,
                "expected_file_hash" to snapshot.fileHashes["A.java"], "new_body" to "return 2;"), "1").optionalObject("result")!!
            Files.copy(root.resolve("A.java"), root.resolve("Same.java"))
            manager.refresh()
            val wrong = state.call(a, "acquire_edit_lease", jObject("file" to "Same.java"), "2").optionalObject("result")!!
            val args = jObject("edit_id" to planned.requiredString("edit_id"), "lease_id" to wrong.requiredString("lease_id"), "generation" to wrong.requiredString("generation"))
            assertEquals("not_owner", assertFailsWith<LiveFailure> { state.call(b, "apply_edit", args, "1") }.code)
            assertEquals("stale_plan", assertFailsWith<LiveFailure> { state.call(a, "apply_edit", args, "3") }.code)
            assertTrue(Files.readAllBytes(root.resolve("A.java")).contentEquals(Files.readAllBytes(root.resolve("Same.java"))))
        }
    }

    @Test
    fun concurrentSessionsGetOneReservationAndClosingTheHolderReleasesIt() {
        val root = fixture()
        SnapshotManager(root, useJoern = false).use { manager ->
            val state = LiveState("repo", "epoch", manager, coordinatedWrites = true)
            val agents = listOf(state.createSession("a", "agent"), state.createSession("b", "agent"))
            val start = CountDownLatch(1)
            val outcomes = arrayOfNulls<Any>(2)
            val threads = agents.mapIndexed { index, session -> thread {
                start.await()
                outcomes[index] = runCatching { state.call(session, "acquire_edit_lease", jObject("file" to "A.java"), "1") }.fold({ it }, { it })
            } }
            start.countDown()
            threads.forEach { it.join(5000); assertTrue(!it.isAlive) }
            assertEquals(1, outcomes.count { it is JObject })
            val loser = outcomes.indexOfFirst { it is LiveFailure }
            assertEquals("lease_busy", (outcomes[loser] as LiveFailure).code)
            assertTrue((outcomes[loser] as LiveFailure).details.optionalString("owner") != null)
            val winner = 1 - loser
            state.closeSession(agents[winner])
            val granted = state.call(agents[loser], "acquire_edit_lease", jObject("file" to "A.java"), "2")
            assertTrue(granted.optionalObject("result")!!.requiredString("lease_id").isNotEmpty())
        }
    }

    @Test
    fun successfulCommitReportsIndexingFailureSeparately() {
        val root = fixture()
        SnapshotManager(root, useJoern = false).use { manager ->
            val leases = mutableMapOf<String, EditLease>()
            val service = LiveEdits(manager, "epoch", System::nanoTime, {}, { change -> leases[change.lease.id] = change.lease },
                { leases[it] }, { _, _, _, _ -> }, queueIndex = { error("indexing failed") })
            val snapshot = manager.current()
            val target = snapshot.methodInfos.values.single { it.file == "A.java" && it.simpleName == "value" }
            val plan = service.invoke("a", "plan_edit", jObject("node_id" to target.id, "snapshot_id" to snapshot.id,
                "expected_file_hash" to snapshot.fileHashes["A.java"], "new_body" to "return 2;")) { _, _, _ -> }
            val lease = service.invoke("a", "acquire_edit_lease", jObject("file" to "A.java")) { _, _, _ -> }
            var recorded = false
            val applied = service.invoke("a", "apply_edit", jObject("edit_id" to plan.requiredString("edit_id"),
                "lease_id" to lease.requiredString("lease_id"), "generation" to lease.requiredString("generation"))) { result, _, _ -> recorded = result["committed"] == JBoolean(true) }
            assertTrue(recorded)
            assertEquals(JBoolean(true), applied["committed"])
            assertEquals("failed", applied.optionalString("indexing"))
            assertTrue(Files.readString(root.resolve("A.java")).contains("return 2;"))
        }
    }

    @Test
    fun unrelatedFailuresKeepReservationsAndHeartbeatRenewsWithinTheMaximum() {
        val root = fixture()
        SnapshotManager(root, useJoern = false).use { manager ->
            var now = 0L
            val state = LiveState("repo", "epoch", manager, coordinatedWrites = true) { now }
            val a = state.createSession("a", "agent")
            val b = state.createSession("b", "agent")
            val lease = state.call(a, "acquire_edit_lease", jObject("file" to "A.java"), "1").optionalObject("result")!!
            state.call(b, "acquire_edit_lease", jObject("file" to "B.java"), "1")
            assertEquals("invalid_request", assertFailsWith<LiveFailure> {
                state.call(a, "search_graph", jObject("unexpected" to "bad"), "2")
            }.code)
            assertEquals("lease_busy", assertFailsWith<LiveFailure> {
                state.call(a, "acquire_edit_lease", jObject("file" to "B.java"), "3")
            }.code)
            assertEquals(2, (state.state()["leases"] as JArray).values.size)
            now = 20_000_000_000L
            state.heartbeat(a)
            now = 40_000_000_000L
            state.heartbeat(a)
            state.expire()
            val owned = (state.state()["leases"] as JArray).values.single().asObject()
            assertEquals(lease.requiredString("lease_id"), owned.requiredString("lease_id"))
            assertEquals(a.id, owned.requiredString("session_id"))
            state.closeSession(a)
            assertTrue((state.state()["leases"] as JArray).values.isEmpty())
        }
    }

    private fun fixture(): java.nio.file.Path = createTempDirectory("graphharness-live-edits").also { root ->
        root.resolve("A.java").writeText("class A {\n int value() { return 1; }\n}\n")
        root.resolve("B.java").writeText("class B {\n int other() { return 3; }\n}\n")
    }
}
