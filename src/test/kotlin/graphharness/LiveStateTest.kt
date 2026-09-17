package graphharness

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LiveStateTest {
    @Test
    fun labelsCannotImpersonateAnAgentAndObserverCannotDispatchTools() {
        SnapshotManager(createTempDirectory("graphharness-state-test"), useJoern = false).use { manager ->
            val state = LiveState("repo", "epoch", manager)
            val first = state.createSession("same label", "agent")
            val second = state.createSession("same label", "agent")
            val observer = state.createSession("same label", "observer")
            assertNotEquals(first.id, second.id)
            assertNotEquals(first.credential, second.credential)
            assertEquals("session_expired", assertFailsWith<LiveFailure> { state.authenticate(first.id) }.code)
            assertEquals("forbidden", assertFailsWith<LiveFailure> {
                state.call(observer, "get_summary_map", emptyJsonObject(), "1")
            }.code)
            assertFalse(state.definitions().any { it.name in setOf("apply_edit", "validate_edit", "plan_edit") })
        }
    }

    @Test
    fun duplicateOperationsReplayBothSuccessAndFailureWithoutAnotherEvent() {
        SnapshotManager(createTempDirectory("graphharness-operation-test"), useJoern = false).use { manager ->
            val state = LiveState("repo", "epoch", manager)
            val session = state.createSession("agent", "agent")
            val args = jObject("query" to "absent")
            val original = state.call(session, "search_graph", args, "1")
            val cursor = state.cursor()
            assertEquals(original.stringify(), state.call(session, "search_graph", args, "1").stringify())
            assertEquals(cursor, state.cursor())
            assertEquals("operation_reused", assertFailsWith<LiveFailure> {
                state.call(session, "search_graph", jObject("query" to "different"), "1")
            }.code)
            val firstFailure = assertFailsWith<LiveFailure> { state.call(session, "apply_edit", emptyJsonObject(), "2") }
            val failedCursor = state.cursor()
            val secondFailure = assertFailsWith<LiveFailure> { state.call(session, "apply_edit", emptyJsonObject(), "2") }
            assertEquals(firstFailure.code, secondFailure.code)
            assertEquals(failedCursor, state.cursor())
            val other = state.createSession("agent", "agent")
            assertTrue(state.call(other, "search_graph", args, "1").fields.containsKey("result"))
        }
    }

    @Test
    fun expiryUsesMonotonicTimeAndEventRetentionRequiresExplicitReset() {
        SnapshotManager(createTempDirectory("graphharness-expiry-test"), useJoern = false).use { manager ->
            var now = 0L
            val state = LiveState("repo", "epoch", manager) { now }
            val old = state.createSession("old", "agent")
            now = 30_000_000_000L
            assertEquals("session_expired", assertFailsWith<LiveFailure> { state.authenticate(old.credential) }.code)
            state.expire()
            repeat(1001) { state.closeSession(state.createSession("agent", "agent")) }
            assertTrue(state.eventsAfter(0).first)
            val cursor = state.cursor()
            assertFalse(state.eventsAfter(cursor).first)
            assertTrue(state.eventsAfter(cursor).second.isEmpty())
            assertTrue(state.eventsAfter(cursor + 1).first)
        }
    }

    @Test
    fun capabilitiesDescribeOnlyAvailableReadToolsAndFailedRequestsCannotInflateEvents() {
        SnapshotManager(createTempDirectory("graphharness-capability-test"), useJoern = false).use { manager ->
            val state = LiveState("repo", "epoch", manager)
            val session = state.createSession("agent", "agent")
            val capabilities = state.call(session, "get_capabilities", emptyJsonObject(), "1").optionalObject("result")!!
            val available = (capabilities["available_tools"] as JArray).values.map { (it as JString).value }.toSet()
            assertFalse("apply_edit" in available)
            assertTrue(capabilities.optionalObject("tool_guarantees")!!.fields.keys.all { it in available })
            assertEquals("approximate", capabilities.optionalString("semantic_level"))
            assertEquals(emptyList(), (capabilities["edit_operations"] as JArray).values)
            assertFailsWith<LiveFailure> { state.call(session, "x".repeat(100_000), emptyJsonObject(), "2") }
            assertTrue(state.eventsAfter(0).second.all { it.second.toByteArray().size <= 16 * 1024 })
        }
    }

    @Test
    fun largeGraphRemainsAvailableWithinResponseBudgetWithoutDanglingEdges() {
        val root = createTempDirectory("graphharness-large-state-test")
        root.resolve("Large.java").writeText("class Large {\n" + (1..500).joinToString("\n") {
            "  void method${it}_${"x".repeat(900)}() {}"
        } + "\n}\n")
        SnapshotManager(root, useJoern = false).use { manager ->
            val state = LiveState("repo", "epoch", manager).state()
            assertTrue(state.stringify().toByteArray().size <= 1024 * 1024)
            val graph = state.optionalObject("snapshot")!!
            assertTrue(graph["omitted_node_count"]!!.asInt() > 0)
            val ids = (graph["nodes"] as JArray).values.map { it.asObject().requiredString("id") }.toSet()
            assertTrue(ids.isNotEmpty())
            (graph["edges"] as JArray).values.forEach { edge ->
                assertTrue(edge.asObject().requiredString("from") in ids)
                assertTrue(edge.asObject().requiredString("to") in ids)
            }
        }
    }

    @Test
    fun receiptEvictionCannotAllowReplayAndInspectionsKeepRequestIdentity() {
        SnapshotManager(createTempDirectory("graphharness-retention-test"), useJoern = false).use { manager ->
            val state = LiveState("repo", "epoch", manager)
            val agent = state.createSession("agent", "agent")
            val args = jObject("query" to "absent")
            repeat(257) { state.call(agent, "search_graph", args, (it + 1).toString()) }
            val cursor = state.cursor()
            assertEquals("operation_expired", assertFailsWith<LiveFailure> { state.call(agent, "search_graph", args, "1") }.code)
            assertEquals(cursor, state.cursor())
            val observer = state.createSession("browser", "observer")
            state.inspect(observer, "search_graph", args, "request-a")
            state.inspect(observer, "search_graph", args, "request-b")
            val inspections = state.eventsAfter(cursor).second.map { MiniJson.parse(it.second).asObject() }
                .filter { it.optionalString("event_type")?.startsWith("inspection_") == true }
            assertEquals(4, inspections.size)
            assertEquals(setOf("request-a", "request-b"), inspections.map { it.optionalString("request_id") }.toSet())
            assertEquals(2, inspections.map { it.optionalString("inspection_id") }.toSet().size)
        }
    }
}
