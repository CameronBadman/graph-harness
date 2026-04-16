package graphharness

import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalyzerTest {
    @Test
    fun buildsSummaryAndTraversalDataForSimpleJavaProject() {
        val root = createTempDirectory("graphharness-test")
        root.resolve("PaymentService.java").writeText(
            """
            package com.app.payment;

            public class PaymentService implements PaymentProcessor {
                public PaymentResult processPayment(Order order) {
                    validateOrder(order);
                    return charge(order);
                }

                public PaymentResult charge(Order order) {
                    return new PaymentResult();
                }

                private void validateOrder(Order order) {
                }
            }
            """.trimIndent(),
        )
        root.resolve("PaymentProcessor.java").writeText(
            """
            package com.app.payment;

            public interface PaymentProcessor {
                PaymentResult charge(Order order);
            }
            """.trimIndent(),
        )
        root.resolve("PaymentResult.java").writeText(
            """
            package com.app.payment;
            public class PaymentResult {}
            """.trimIndent(),
        )
        root.resolve("Order.java").writeText(
            """
            package com.app.payment;
            public class Order {}
            """.trimIndent(),
        )

        val manager = SnapshotManager(root)
        val summary = manager.summaryMap()

        assertEquals(4, summary.project.total_files)
        assertTrue(summary.clusters.isNotEmpty())
        assertTrue(summary.clusters.none { it.cluster_id == "cluster:default" })
        assertEquals("compact", summary.summary_mode)
        assertTrue(summary.clusters.isEmpty())
        assertTrue(summary.bridge_nodes.isEmpty())
        assertTrue(summary.entrypoints.none { it.name.contains(".<init>") })
        assertTrue(summary.hotspots.none { it.node.name.contains(".<init>") })

        val processNode = manager.search("processPayment", "method", null, null).results.first()
        val callees = manager.callees(processNode.id, 1)
        assertTrue(callees.items.any { it.node.name.endsWith(".charge") })
        assertTrue(callees.items.any { it.node.name.endsWith(".validateOrder") })

        val interfaceCharge = manager.search("PaymentProcessor.charge", "method", null, null).results.first()
        val implementations = manager.implementations(interfaceCharge.id)
        assertTrue(implementations.items.any { it.node.name == "com.app.payment.PaymentService.charge" })
        assertTrue(implementations.items.any { it.relationship == "implements" })

        val impact = manager.impact(processNode.id, 2)
        assertTrue(impact.affected_files.isNotEmpty())
        assertTrue(impact.analysis_basis.contains("incoming_calls"))

        val submitNode = manager.search("submit", "method", null, null).results.first()
        val paths = manager.callPaths(submitNode.id, 3)
        assertTrue(paths.paths.none { path -> path.nodes.any { it.name.contains(".<init>") } })
    }
}
