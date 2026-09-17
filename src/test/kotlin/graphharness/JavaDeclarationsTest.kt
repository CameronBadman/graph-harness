package graphharness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JavaDeclarationsTest {
    @Test
    fun parsesNestedOverloadsWithoutBraceHeuristics() {
        val source = "package demo;\n" +
            "class Outer { class Inner {\n" +
            "  String one(String value) { return \"{ 😀 }\"; }\n" +
            "  String one(int value) { /* { } */ return \"\"\"\ntext { }\n\"\"\"; }\n" +
            "} }\n"
        val result = parseJavaDeclarations("Outer.java", source)
        assertTrue(result.types.any { it.qualifiedName == "demo.Outer.Inner" })
        val methods = result.methods.filter { it.parentQualifiedName == "demo.Outer.Inner" && it.simpleName == "one" }
        assertEquals(2, methods.size)
        assertEquals(2, methods.map { it.id }.toSet().size)
    }

    @Test
    fun parseErrorsProduceNoDeclarations() {
        val result = parseJavaDeclarations("Broken.java", "class Broken { void x( { }")
        assertTrue(result.types.isEmpty())
        assertTrue(result.methods.isEmpty())
    }
}
