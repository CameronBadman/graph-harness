package graphharness

import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContextRetrievalTest {
    @Test
    fun qualifiedMethodWinsOverConstructorAndSameNamedOwner() {
        val root = createTempDirectory("graphharness-qualified-context")
        root.resolve("FeePolicy.java").writeText(
            """
            package checkout;
            public class FeePolicy {
                public FeePolicy() {}
                public int adjust(int amount) { return amount + 17; }
                public int audit(int amount) { return amount; }
            }
            """.trimIndent(),
        )
        root.resolve("OtherPolicy.java").writeText(
            "package checkout; public class OtherPolicy { public int adjust(int amount) { return amount - 3; } }",
        )

        SnapshotManager(root, useJoern = false).use { manager ->
            val bundle = manager.buildContextBundle(task = "Correct FeePolicy.adjust for the threshold boundary")
            val target = manager.search("FeePolicy.adjust", "method", null, null).results.single()
            assertEquals(target.id, bundle.chosen_node_id)
            assertTrue(bundle.source_slices.any { it.node_id == target.id && "amount + 17" in it.source })
            assertFalse(bundle.focus_nodes.any { "<init>" in it.name })
        }
    }

    @Test
    fun pathQualifiedFunctionTargetsReturnBothRequestedLanguages() {
        val root = createTempDirectory("graphharness-path-context")
        root.resolve("web").createDirectories()
        root.resolve("jobs").createDirectories()
        root.resolve("web/fees.ts").writeText("export function resolve() { return 'browser'; }\n")
        root.resolve("jobs/fees.py").writeText("def resolve():\n    return 'worker'\n")
        root.resolve("unrelated.py").writeText("def resolve():\n    return 'unrelated'\n")

        SnapshotManager(root, useJoern = false).use { manager ->
            val bundle = manager.buildContextBundle(
                task = "Compare web/fees.ts:resolve and jobs/fees.py:resolve and report their return values",
                tokenBudget = 1800,
            )
            val functionSources = bundle.source_slices.filter { item ->
                manager.current().nodeSummaries.getValue(item.node_id).kind == "function"
            }
            assertEquals(setOf("web/fees.ts", "jobs/fees.py"), functionSources.map { it.file }.toSet())
            assertFalse(bundle.source_slices.any { it.file == "unrelated.py" })
            assertTrue(functionSources.any { "'browser'" in it.source })
            assertTrue(functionSources.any { "'worker'" in it.source })
        }
    }

    @Test
    fun searchMatchesFilePathWithoutDroppingKindFilter() {
        val root = createTempDirectory("graphharness-search-context")
        root.resolve("billing").createDirectories()
        root.resolve("billing/fees.ts").writeText("export function resolve() { return 1; }\n")
        root.resolve("other.ts").writeText("export function resolve() { return 2; }\n")

        SnapshotManager(root, useJoern = false).use { manager ->
            val result = manager.search("fees", "function", null, null).results
            assertEquals(1, result.size)
            assertEquals("billing/fees.ts", result.single().file)
            assertEquals("function", result.single().kind)
        }
    }

    @Test
    fun duplicateNamesAndOverloadsRequireDisambiguation() {
        val root = createTempDirectory("graphharness-ambiguous-context")
        root.resolve("Decoder.java").writeText(
            """
            public class Decoder {
                public int read(int value) { return value; }
                public String read(String value) { return value; }
            }
            """.trimIndent(),
        )
        SnapshotManager(root, useJoern = false).use { manager ->
            val bundle = manager.buildContextBundle(task = "Inspect Decoder.read")
            assertNull(bundle.chosen_node_id)
            assertTrue(bundle.notes.contains("bundle_resolution=ambiguous"))
            assertEquals(2, bundle.focus_nodes.count { it.name == "Decoder.read" })
            assertTrue(bundle.source_slices.isEmpty())
        }
    }

    @Test
    fun unknownTaskDoesNotInventAnEntrypointTarget() {
        val root = createTempDirectory("graphharness-unknown-context")
        root.resolve("Service.java").writeText("public class Service { public int serve() { return 1; } }")
        SnapshotManager(root, useJoern = false).use { manager ->
            val bundle = manager.buildContextBundle(task = "Investigate the nonexistentSymbol")
            assertNull(bundle.chosen_node_id)
            assertTrue(bundle.source_slices.isEmpty())
            assertTrue(bundle.notes.contains("no_node_resolved"))
        }
    }

    @Test
    fun duplicateBasenamesDoNotBecomeAnImplicitMultiTargetRequest() {
        val root = createTempDirectory("graphharness-basename-context")
        for (directory in listOf("alpha", "beta")) {
            root.resolve(directory).createDirectories()
            root.resolve("$directory/worker.py").writeText("def go():\n    return '$directory'\n")
        }
        SnapshotManager(root, useJoern = false).use { manager ->
            for (task in listOf("Inspect go in worker.py", "Inspect worker.py")) {
                val bundle = manager.buildContextBundle(task = task)
                assertNull(bundle.chosen_node_id)
                assertTrue(bundle.notes.contains("bundle_resolution=ambiguous"))
                assertTrue(bundle.source_slices.isEmpty())
            }
            val explicit = manager.buildContextBundle(task = "Compare go in alpha/worker.py and beta/worker.py")
            assertEquals(setOf("alpha/worker.py", "beta/worker.py"), explicit.source_slices.map { it.file }.toSet())
        }
    }

    @Test
    fun javaFileReferenceReturnsSourceButUnknownQualifiedMemberDoesNot() {
        val root = createTempDirectory("graphharness-file-reference")
        root.resolve("Worker.java").writeText("public class Worker { public int run() { return 42; } }")
        SnapshotManager(root, useJoern = false).use { manager ->
            val fileBundle = manager.buildContextBundle(task = "Inspect Worker.java")
            assertEquals("Worker.java", fileBundle.focus_nodes.first().file)
            assertEquals("file", fileBundle.focus_nodes.first().kind)
            assertEquals("public class Worker { public int run() { return 42; } }", fileBundle.source_slices.single().source)
            assertEquals(fileBundle.focus_nodes.first().file_hash, fileBundle.source_slices.single().file_hash)

            val unknown = manager.buildContextBundle(task = "Inspect Worker.missing in Worker.java")
            assertNull(unknown.chosen_node_id)
            assertTrue(unknown.source_slices.isEmpty())
            assertTrue(unknown.notes.contains("some_explicit_targets_unresolved"))
        }
    }
}
