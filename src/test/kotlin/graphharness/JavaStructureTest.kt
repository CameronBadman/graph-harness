package graphharness

import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class JavaStructureTest {
    @Test
    fun anonymousSameNameMethodCannotOverwriteItsEnclosingMethodSpan() {
        val root = createTempDirectory("graphharness-java-anonymous")
        val declaration = "int outer() { return new Object() { int outer() { return 1; } }.hashCode(); }"
        val text = "class Example {\n  $declaration\n}\n"
        root.resolve("Example spaced.java").writeBytes(text.toByteArray())
        SnapshotManager(root, useJoern = false).use { manager ->
            val snapshot = enrichJavaStructure(manager.current())
            val node = snapshot.nodeSummaries.values.single { it.kind == "method" && it.name.endsWith(".outer") }
            val span = assertNotNull(node.byte_span)
            assertEquals(declaration, decodeUtf8(text.toByteArray().copyOfRange(span.start, span.end)))
            assertTrue(snapshot.edges.all { it.from in snapshot.nodeSummaries && it.to in snapshot.nodeSummaries })
        }
    }

    @Test
    fun javacOverlayAddsExactSpansAndContainmentWithoutChangingMethodIds() {
        val root = createTempDirectory("graphharness-java-structure")
        val source = "package demo;\r\n" +
            "class Outer {\r\n" +
            "  class Inner {\r\n" +
            "    String one(String value) { return \"😀 { }\"; }\r\n" +
            "    String one(int value) { /* { } */ return \"\"\"\r\ntext { }\r\n\"\"\"; }\r\n" +
            "  }\r\n" +
            "}\r\n"
        root.resolve("Outer.java").writeBytes(source.toByteArray(StandardCharsets.UTF_8))
        SnapshotManager(root, useJoern = false).use { manager ->
            val before = manager.current()
            val enriched = enrichJavaStructure(before)
            val methods = before.methodInfos.values.filter { it.file == "Outer.java" && it.simpleName == "one" }
            assertTrue(methods.size == 2)
            methods.forEach { method ->
                assertNotNull(enriched.nodeSummaries[method.id]?.byte_span, "${method.parentQualifiedName} ${method.parameterTypes} ${method.lineRange}")
                assertTrue(enriched.edges.any { it.to == method.id && it.relationship == "contains" && it.provenance == "javac_parser" })
            }
            assertTrue(enriched.nodeSummaries.values.any { it.kind == "file" && it.file == "Outer.java" })
        }
    }

    @Test
    fun parserFailureAddsDiagnosticWithoutStructuralClaims() {
        val root = createTempDirectory("graphharness-java-structure-error")
        root.resolve("Broken.java").writeBytes("class Broken { void x( { }".toByteArray(StandardCharsets.UTF_8))
        SnapshotManager(root, useJoern = false).use { manager ->
            val enriched = enrichJavaStructure(manager.current())
            assertTrue(enriched.sourceDiagnostics.any { it.file == "Broken.java" && it.reason == "java_parse_error" })
            assertTrue(enriched.edges.none { it.file == "Broken.java" && it.provenance == "javac_parser" })
        }
    }
}
