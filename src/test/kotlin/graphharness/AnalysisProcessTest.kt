package graphharness

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnalysisProcessTest {
    @Test
    fun boundsFailingOutputAndStopsAnOverdueProcess() {
        val root = createTempDirectory("graphharness-process-limit")
        val outputFailure = assertFailsWith<IllegalArgumentException> {
            runCommand(listOf("sh", "-c", "head -c 2097152 /dev/zero; exit 1"), root, 5000)
        }
        assertTrue(outputFailure.message!!.length < 1024 * 1024 + 100)
        val before = System.nanoTime()
        assertFailsWith<IllegalStateException> { runCommand(listOf("sh", "-c", "sleep 30"), root, 100) }
        assertTrue((System.nanoTime() - before) / 1_000_000 < 5000)
    }
}
