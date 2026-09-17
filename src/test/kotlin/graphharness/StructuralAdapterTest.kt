package graphharness

import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StructuralAdapterTest {
    @Test
    fun mixedStructuralNodesRetainExactUtf8RangesAndContainment() {
        val root = createTempDirectory("graphharness-structural")
        val expectedTsMethod = "run(): string {\r\n    return \"😀\";\r\n  }"
        val ts = "export class Café {\r\n  $expectedTsMethod\r\n}\r\n"
        val expectedPyMethod = "def put(self):\r\n        return '😀'"
        val py = "class Queue:\r\n    $expectedPyMethod\r\n"
        root.resolve("ticket.ts").writeBytes(ts.toByteArray(StandardCharsets.UTF_8))
        root.resolve("queue.py").writeBytes(py.toByteArray(StandardCharsets.UTF_8))

        SnapshotManager(root, useJoern = false).use { manager ->
            val tsMethod = manager.search("run", "method", null, null).results.single { it.language == "typescript" }
            val pyMethod = manager.search("put", "function", null, null).results.single { it.language == "python" }
            val tsSource = manager.source(tsMethod.id, 0)
            val pySource = manager.source(pyMethod.id, 0)

            assertEquals("typescript", tsSource.language)
            assertEquals("python", pySource.language)
            assertEquals(expectedTsMethod, tsSource.source)
            assertEquals(expectedPyMethod, pySource.source)
            assertEquals(tsMethod.file_hash, tsSource.file_hash)
            assertNotNull(tsMethod.byte_span)
            assertTrue(manager.nodeDetail(tsMethod.id).incoming_dependencies.any { it.relationship == "contains" })
            assertEquals(listOf("java", "python", "typescript"), manager.capabilities().languages)
            assertEquals(mapOf("typescript" to 2, "python" to 2), manager.summaryMap().project.structural_nodes_by_language)
            assertFailsWith<LiveFailure> { manager.callers(tsMethod.id, 1) }
            assertTrue(manager.buildContextBundle(nodeId = pyMethod.id).notes.contains("structural_context_only"))
        }
    }

    @Test
    fun syntaxErrorsProduceDiagnosticsWithoutInventingDefinitions() {
        val root = createTempDirectory("graphharness-structural-syntax")
        root.resolve("broken.py").writeBytes("def bad(:\n".toByteArray(StandardCharsets.UTF_8))
        root.resolve("broken.ts").writeBytes("export class {\n".toByteArray(StandardCharsets.UTF_8))

        SnapshotManager(root, useJoern = false).use { manager ->
            assertTrue(manager.search("bad", null, null, null).results.isEmpty())
            assertTrue(manager.current().adapterInfo.getValue("python").diagnostics.isNotEmpty())
            assertTrue(manager.current().adapterInfo.getValue("typescript").diagnostics.isNotEmpty())
        }
    }

    @Test
    fun duplicateNestedDeclarationsRemainDistinctAcrossTsJsAndPython() {
        val adapters = StructuralAdapters()
        val ts = RetainedSource.fromBytes("class A { f() {} f() {} }".toByteArray(StandardCharsets.UTF_8))
        val js = RetainedSource.fromBytes("function work() { return 1 }".toByteArray(StandardCharsets.UTF_8))
        val py = RetainedSource.fromBytes("def outer():\n    def inner():\n        return 1\ndef outer():\n    def inner():\n        return 2\n".toByteArray(StandardCharsets.UTF_8))

        val tsResult = adapters.analyze("duplicate.ts", ts)!!
        val jsResult = adapters.analyze("worker.js", js)!!
        val pyResult = adapters.analyze("nested.py", py)!!

        assertTrue(tsResult.available)
        assertEquals(2, tsResult.definitions.count { it.name == "f" })
        assertEquals(2, tsResult.definitions.filter { it.name == "f" }.map { it.identity }.toSet().size)
        assertEquals("javascript", jsResult.language)
        assertEquals("python", pyResult.language)
        assertEquals(4, pyResult.definitions.size)
        assertEquals(4, pyResult.definitions.map { it.identity }.toSet().size)
    }
}
