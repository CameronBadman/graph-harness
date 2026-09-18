package graphharness

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JavaParameterTypesTest {
    @Test
    fun samePackageAndExplicitImportsMatchQualifiedMetadataWithoutMatchingOtherPackages() {
        fixture(mapOf(
            "demo/Policy.java" to "package demo; class Policy { int value(Request request) { return 1; } }",
            "demo/Request.java" to "package demo; class Request {}",
            "other/Request.java" to "package other; class Request {}",
        )) { snapshot ->
            checkMatch(snapshot, "demo/Policy.java", listOf("demo.Request"))
            checkMatch(snapshot, "demo/Policy.java", listOf("other.Request"), accepted = false)
        }
        fixture(mapOf(
            "demo/Policy.java" to "package demo; import other.Request; class Policy { int value(Request request) { return 1; } }",
            "demo/Request.java" to "package demo; class Request {}",
            "other/Request.java" to "package other; public class Request {}",
        )) { snapshot ->
            checkMatch(snapshot, "demo/Policy.java", listOf("other.Request"))
            checkMatch(snapshot, "demo/Policy.java", listOf("demo.Request"), accepted = false)
        }
    }

    @Test
    fun declaredNestedAndBinaryNamesResolveWithoutInventingUndeclaredAliases() {
        fixture(mapOf(
            "demo/Policy.java" to "package demo; class Policy { int value(Outer.Inner request) { return 1; } }",
            "demo/Outer.java" to "package demo; class Outer { static class Inner {} }",
        )) { snapshot ->
            checkMatch(snapshot, "demo/Policy.java", listOf("demo.Outer.Inner"))
            checkMatch(snapshot, "demo/Policy.java", listOf("demo.Outer\$Inner"))
            checkMatch(snapshot, "demo/Policy.java", listOf("other.Outer\$Inner"), accepted = false)
        }
        fixture(mapOf("demo/Policy.java" to "package demo; import external.Outer; class Policy { int value(Outer.Inner request) { return 1; } }")) { snapshot ->
            checkMatch(snapshot, "demo/Policy.java", listOf("external.Outer.Inner"))
            checkMatch(snapshot, "demo/Policy.java", listOf("external.Outer\$Inner"), accepted = false)
        }
    }

    @Test
    fun arraysAndVarargsPreserveRankAndPreserveUnicodeAndCrLfOutsideTheEditedBody() {
        fixture(mapOf(
            "demo/Policy.java" to "package demo;\r\nclass Policy { // 🚀\r\n int value(Request[][] requests, Request... remaining) { return 1; }\r\n}\r\n",
            "demo/Request.java" to "package demo; class Request {}",
        )) { snapshot ->
            checkMatch(snapshot, "demo/Policy.java", listOf("demo.Request[][]", "demo.Request[]"))
            checkMatch(snapshot, "demo/Policy.java", listOf("demo.Request[][]", "demo.Request..."))
            checkMatch(snapshot, "demo/Policy.java", listOf("demo.Request[]", "demo.Request[]"), accepted = false)
            checkMatch(snapshot, "demo/Policy.java", listOf("demo.Request[][]"), accepted = false)
        }
    }

    @Test
    fun sameLineOverloadsRemainDistinctByFullPackageIdentity() {
        fixture(mapOf("demo/Policy.java" to "package demo; class Policy { int value(a.Value request) { return 1; } int value(b.Value request) { return 2; } }")) { snapshot ->
            val target = snapshot.methodInfos.values.single { it.parameterTypes == listOf("b.Value") }
            val source = snapshot.sourceFiles.getValue(target.file)
            val plan = SafeJavaEditor.plan(source, target, source.hash, "return 7;", snapshot.sourceFiles)
            val updated = decodeUtf8(plan.updatedBytes())
            assertTrue(updated.contains("value(a.Value request) { return 1; }"))
            assertFalse(updated.contains("return 2;"))
            assertTrue(updated.contains("return 7;"))
            val alteredOwner = target.copy(parentQualifiedName = "other.Policy")
            assertEquals("target_not_found", assertFailsWith<LiveFailure> {
                SafeJavaEditor.plan(source, alteredOwner, source.hash, "return 7;", snapshot.sourceFiles)
            }.code)
            assertEquals("stale_source", assertFailsWith<LiveFailure> {
                SafeJavaEditor.plan(source, target, "stale", "return 7;", snapshot.sourceFiles)
            }.code)
        }
    }

    @Test
    fun nestedTypesAndTypeVariablesShadowImportsAndPackageTypes() {
        fixture(mapOf(
            "demo/Policy.java" to "package demo; import other.Request; class Policy { static class Request {} int value(Request request) { return 1; } }",
            "other/Request.java" to "package other; public class Request {}",
            "demo/Request.java" to "package demo; class Request {}",
        )) { snapshot ->
            checkMatch(snapshot, "demo/Policy.java", listOf("demo.Policy.Request"))
            checkMatch(snapshot, "demo/Policy.java", listOf("other.Request"), accepted = false)
            checkMatch(snapshot, "demo/Policy.java", listOf("demo.Request"), accepted = false)
        }
        fixture(mapOf(
            "demo/Policy.java" to "package demo; class Policy { <Request> int value(Request request) { return 1; } }",
            "demo/Request.java" to "package demo; class Request {}",
        )) { snapshot ->
            checkMatch(snapshot, "demo/Policy.java", listOf("Request"))
            checkMatch(snapshot, "demo/Policy.java", listOf("demo.Request"), accepted = false)
            checkMatch(snapshot, "demo/Policy.java", listOf("java.lang.Object"), accepted = false)
        }
    }

    @Test
    fun genericStructureIsPreservedAndErasedOrDifferentlyBoundMetadataIsRejected() {
        fixture(mapOf(
            "demo/Policy.java" to "package demo; import java.util.List; class Policy<T> { int value(List<T> values, List<? extends Request[]> bounded) { return 1; } }",
            "demo/Request.java" to "package demo; class Request {}",
        )) { snapshot ->
            checkMatch(snapshot, "demo/Policy.java", listOf("java.util.List<T>", "java.util.List<? extends demo.Request[]>"))
            checkMatch(snapshot, "demo/Policy.java", listOf("java.util.List", "java.util.List"), accepted = false)
            checkMatch(snapshot, "demo/Policy.java", listOf("java.util.List<java.lang.Object>", "java.util.List<? extends demo.Request[]>"), accepted = false)
            checkMatch(snapshot, "demo/Policy.java", listOf("java.util.List<T>", "java.util.List<? super demo.Request[]>"), accepted = false)
        }
    }

    @Test
    fun ambiguousOrUnresolvedImportsAndInheritedMembersFailClosed() {
        for (imports in listOf("import a.*; import b.*;", "import a.Value; import b.Value;", "import static a.Container.*;")) {
            fixture(mapOf(
                "demo/Policy.java" to "package demo; $imports class Policy { int value(Value request) { return 1; } }",
                "a/Value.java" to "package a; public class Value {}",
                "b/Value.java" to "package b; public class Value {}",
            )) { snapshot -> checkMatch(snapshot, "demo/Policy.java", listOf("a.Value"), accepted = false) }
        }
        fixture(mapOf(
            "demo/Policy.java" to "package demo; class Policy extends Base { int value(Value request) { return 1; } }",
            "demo/Base.java" to "package demo; class Base { static class Value {} }",
            "demo/Value.java" to "package demo; class Value {}",
        )) { snapshot ->
            checkMatch(snapshot, "demo/Policy.java", listOf("demo.Value"), accepted = false)
            checkMatch(snapshot, "demo/Policy.java", listOf("demo.Base.Value"), accepted = false)
        }
    }

    @Test
    fun exactQualifiedParameterStructureRemainsAvailableInClassesWithSuperclasses() {
        fixture(mapOf(
            "demo/Policy.java" to "package demo; class Policy extends Base { int value(java.lang.String[] request) { return 1; } }",
            "demo/Base.java" to "package demo; class Base {}",
        )) { snapshot ->
            checkMatch(snapshot, "demo/Policy.java", listOf("java.lang.String[]"))
            checkMatch(snapshot, "demo/Policy.java", listOf("other.String[]"), accepted = false)
        }
    }

    @Test
    fun localDeclarationsDoNotBecomePackageAliasesAndLiteralDollarNamesRemainDistinct() {
        fixture(mapOf(
            "demo/Policy.java" to "package demo; class Policy { void other() { class Request {} } int value(Request request) { return 1; } }",
        )) { snapshot -> checkMatch(snapshot, "demo/Policy.java", listOf("demo.Policy.Request"), accepted = false) }
        fixture(mapOf(
            "demo/Policy.java" to "package demo; class Policy { int value(Outer\$Inner request) { return 1; } }",
            "demo/OuterDollar.java" to "package demo; class Outer\$Inner {}",
        )) { snapshot ->
            checkMatch(snapshot, "demo/Policy.java", listOf("demo.Outer\$Inner"))
            checkMatch(snapshot, "demo/Policy.java", listOf("demo.Outer.Inner"), accepted = false)
        }
    }

    @Test
    fun incompleteDeclarationInventoriesKeepPrimitiveAndLocallyDeclaredTypesPredictable() {
        fixture(mapOf("demo/Policy.java" to "package demo; class Policy { static class Request {} int value(int count, Request request) { return 1; } }")) { snapshot ->
            val target = snapshot.methodInfos.values.single { it.simpleName == "value" }.copy(parameterTypes = listOf("int", "demo.Policy.Request"))
            val source = snapshot.sourceFiles.getValue(target.file)
            val tooMany = snapshot.sourceFiles + (1..512).associate { "extra/C$it.java" to RetainedSource.fromBytes("class C$it {}".toByteArray()) }
            val invalid = snapshot.sourceFiles + ("extra/Broken.java" to RetainedSource.fromBytes("class Broken {".toByteArray()))
            val tooLarge = snapshot.sourceFiles + ("extra/Large.java" to RetainedSource.fromBytes(("/*" + " ".repeat(8 * 1024 * 1024) + "*/ class Large {}").toByteArray()))
            for (sources in listOf(tooMany, invalid, tooLarge)) {
                val plan = SafeJavaEditor.plan(source, target, source.hash, "return 7;", sources)
                assertTrue(decodeUtf8(plan.updatedBytes()).contains("return 7;"))
            }
        }
    }

    private fun checkMatch(snapshot: Snapshot, file: String, parameters: List<String>, accepted: Boolean = true) {
        val target = snapshot.methodInfos.values.single { it.file == file && it.simpleName == "value" }.copy(parameterTypes = parameters)
        val source = snapshot.sourceFiles.getValue(file)
        val modified = snapshot.copy(methodInfos = snapshot.methodInfos + (target.id to target), nodeSummaries = snapshot.nodeSummaries.mapValues { (id, node) ->
            if (id == target.id) node.copy(byte_span = null, provenance = "backend") else node
        })
        val enriched = enrichJavaStructure(modified)
        if (accepted) {
            assertNotNull(enriched.nodeSummaries.getValue(target.id).byte_span, "Missing span for $parameters")
            val plan = SafeJavaEditor.plan(source, target, source.hash, "return 7;", snapshot.sourceFiles)
            val before = source.bytes()
            val after = plan.updatedBytes()
            assertTrue(before.copyOfRange(0, plan.bodyStartByte).contentEquals(after.copyOfRange(0, plan.bodyStartByte)))
            assertTrue(before.copyOfRange(plan.bodyEndByte, before.size).contentEquals(after.copyOfRange(plan.bodyStartByte + plan.replacementUtf8().size, after.size)))
            assertTrue(decodeUtf8(after).contains("return 7;"))
            if ("\r\n" in source.text()) assertFalse(decodeUtf8(after).replace("\r\n", "").contains('\n'))
        } else {
            assertNull(enriched.nodeSummaries.getValue(target.id).byte_span, "Unexpected span for $parameters")
            assertEquals("target_not_found", assertFailsWith<LiveFailure> {
                SafeJavaEditor.plan(source, target, source.hash, "return 7;", snapshot.sourceFiles)
            }.code)
        }
    }

    private fun fixture(files: Map<String, String>, body: (Snapshot) -> Unit) {
        val root = createTempDirectory("graphharness-parameter-types")
        for ((name, source) in files) {
            val path = root.resolve(name)
            Files.createDirectories(path.parent)
            path.writeText(source)
        }
        SnapshotManager(root, useJoern = false).use { manager -> body(manager.current()) }
    }
}
