package graphharness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SafeJavaEditTest {
    @Test
    fun replacesOnlyTheParserConfirmedBodyAndPreservesCrLfUnicodeAndBraces() {
        val text = listOf(
            "package demo;",
            "public class Ticket { // 😀",
            "  int reserve(int amount) {",
            "    // } 😀",
            "    String value = \"{\";",
            "    char marker = '}';",
            "    String block = \"\"\"",
            "      { text block }",
            "      \"\"\";",
            "    return amount;",
            "  }",
            "}",
            "",
        ).joinToString("\r\n")
        val source = RetainedSource.fromBytes(text.toByteArray())
        val target = method("Ticket.java", "demo.Ticket", "reserve", listOf("int"), 3, 11)

        val plan = SafeJavaEditor.plan(source, target, source.hash, "return Math.addExact(amount, 1);")
        val updated = plan.updatedBytes()
        val before = source.bytes()
        assertTrue(plan.bodyStartByte > 0)
        assertTrue(plan.bodyEndByte < before.size)
        assertTrue(before.copyOfRange(0, plan.bodyStartByte).contentEquals(updated.copyOfRange(0, plan.bodyStartByte)))
        val suffixStart = plan.bodyStartByte + plan.replacementUtf8().size
        assertTrue(before.copyOfRange(plan.bodyEndByte, before.size).contentEquals(updated.copyOfRange(suffixStart, updated.size)))
        val updatedText = decodeUtf8(updated)
        assertTrue(updatedText.contains("Math.addExact"))
        assertTrue(updatedText.contains("\r\n"))
        assertTrue(updatedText.contains("😀"))
    }

    @Test
    fun selectsTheExactOverloadAndRejectsMismatchedMetadata() {
        val text = """
            package demo;
            public class Ticket {
              int reserve(int amount) {
                return amount;
              }
              int reserve(String amount) {
                return amount.length();
              }
            }
        """.trimIndent() + "\n"
        val source = RetainedSource.fromBytes(text.toByteArray())
        val plan = SafeJavaEditor.plan(source, method("Ticket.java", "demo.Ticket", "reserve", listOf("String"), 6, 8), source.hash, "return 7;")
        val updated = decodeUtf8(plan.updatedBytes())
        assertTrue(updated.contains("return amount;"))
        assertTrue(updated.contains("return 7;"))
        val failure = assertFailsWith<LiveFailure> {
            SafeJavaEditor.plan(source, method("Ticket.java", "demo.Ticket", "reserve", listOf("long"), 3, 5), source.hash, "return 1;")
        }
        assertEquals("target_not_found", failure.code)
    }

    @Test
    fun rejectsStaleAndInvalidJavaWithoutProducingAPlan() {
        val valid = "package demo;\npublic class Ticket {\n  int reserve() {\n    return 1;\n  }\n}\n"
        val source = RetainedSource.fromBytes(valid.toByteArray())
        val target = method("Ticket.java", "demo.Ticket", "reserve", emptyList(), 3, 5)
        val stale = assertFailsWith<LiveFailure> { SafeJavaEditor.plan(source, target, "other", "return 2;") }
        assertEquals("stale_source", stale.code)
        val invalid = RetainedSource.fromBytes("package demo; class Ticket { int reserve( { }".toByteArray())
        val parse = assertFailsWith<LiveFailure> { SafeJavaEditor.plan(invalid, target, invalid.hash, "return 2;") }
        assertEquals("parse_error", parse.code)
        val replacement = assertFailsWith<LiveFailure> { SafeJavaEditor.plan(source, target, source.hash, "return (") }
        assertEquals("replacement_parse_error", replacement.code)
    }

    @Test
    fun rejectsReplacementThatEscapesIntoAnotherDeclarationAndInvalidUnicode() {
        val source = RetainedSource.fromBytes("package demo;\nclass Ticket {\n  int reserve() { return 1; }\n}\n".toByteArray())
        val target = method("Ticket.java", "demo.Ticket", "reserve", emptyList(), 3, 3)
        assertFailsWith<LiveFailure> { SafeJavaEditor.plan(source, target, source.hash, "return 2; } int injected() { return 3;") }
        assertEquals("invalid_encoding", assertFailsWith<LiveFailure> {
            SafeJavaEditor.plan(source, target, source.hash, "return 1; // \uD800")
        }.code)
        val constructor = target.copy(simpleName = "<init>")
        assertEquals("unsupported_operation", assertFailsWith<LiveFailure> {
            SafeJavaEditor.plan(source, constructor, source.hash, "")
        }.code)
    }

    @Test
    fun plansAgainstActualPreferredBackendMetadata() {
        val root = kotlin.io.path.createTempDirectory("graphharness-preferred-plan")
        val original = listOf(
            "package demo;",
            "public class Counter { // 😀",
            " public int value() {",
            "  return 1;",
            " }",
            " public int value(int amount) { return amount; }",
            " public int value(long amount) { return (int) amount; }",
            " static class Nested {",
            "  int value() { return 3; }",
            " }",
            "}", "",
        ).joinToString("\r\n")
        java.nio.file.Files.writeString(root.resolve("Counter.java"), original)
        SnapshotManager(root).use { manager ->
            val snapshot = manager.current()
            if (detectJoernInstallation() != null) assertEquals("joern", snapshot.analysisEngine)
            val targets = snapshot.methodInfos.values.filter { it.simpleName == "value" }
            assertEquals(4, targets.size)
            for (target in targets) {
                val source = snapshot.sourceFiles[target.file]!!
                val plan = try { SafeJavaEditor.plan(source, target, source.hash, "return 2;") } catch (failure: LiveFailure) {
                    throw AssertionError("Backend ${snapshot.analysisEngine}: parent=${target.parentQualifiedName}, name=${target.simpleName}, params=${target.parameterTypes}, range=${target.lineRange}", failure)
                }
                val updated = plan.updatedBytes()
                val before = source.bytes()
                assertTrue(decodeUtf8(updated).contains("return 2;"))
                assertTrue(decodeUtf8(updated).contains("😀\r\n"))
                assertTrue(before.copyOfRange(0, plan.bodyStartByte).contentEquals(updated.copyOfRange(0, plan.bodyStartByte)))
                val suffixStart = plan.bodyStartByte + plan.replacementUtf8().size
                assertTrue(before.copyOfRange(plan.bodyEndByte, before.size).contentEquals(updated.copyOfRange(suffixStart, updated.size)))
            }
        }
    }

    private fun method(file: String, parent: String, name: String, params: List<String>, start: Int, end: Int): MethodInfo = MethodInfo(
        id = "method:$parent.$name:${params.joinToString("|")}:int",
        parentTypeId = "type:$parent",
        parentQualifiedName = parent,
        simpleName = name,
        qualifiedName = "$parent.$name",
        signature = "",
        file = file,
        lineRange = SourceRange(start, end),
        visibility = "public",
        annotations = emptyList(),
        complexity = 1,
        loc = end - start + 1,
        returnType = "int",
        parameterTypes = params,
        body = "",
        callTokens = emptyList(),
        typeRefs = emptySet(),
    )
}
