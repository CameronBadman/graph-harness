package graphharness

import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

    @Test
    fun plansAndAppliesMethodBodyEdits() {
        val root = createTempDirectory("graphharness-edit-test")
        val file = root.resolve("PaymentService.java")
        file.writeText(
            """
            package com.app.payment;

            public class PaymentService {
                public PaymentResult processPayment(Order order) {
                    return charge(order);
                }

                private PaymentResult charge(Order order) {
                    return new PaymentResult();
                }
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
        val processNode = manager.search("processPayment", "method", null, null).results.first()
        val plan = manager.planEdit(
            operation = "modify_method_body",
            targetNodeId = processNode.id,
            payload = EditRequestPayload(
                new_body = """
                validateOrder(order);
                return charge(order);
                """.trimIndent(),
            ),
        )

        assertTrue(plan.validation_errors.isEmpty())
        assertNotNull(plan.edit_id)
        assertTrue(plan.diff.contains("validateOrder(order);"))

        val apply = manager.applyEdit(plan.edit_id)
        assertTrue(apply.success)
        assertTrue(file.readText().contains("validateOrder(order);"))
        val refreshedSource = manager.source(processNode.id, 0)
        assertTrue(refreshedSource.source.contains("validateOrder(order);"))
    }

    @Test
    fun plansSmallerAnchorBasedMethodBodyEdits() {
        val root = createTempDirectory("graphharness-patch-test")
        val file = root.resolve("PaymentService.java")
        file.writeText(
            """
            package com.app.payment;

            public class PaymentService {
                public PaymentResult processPayment(Order order) {
                    owner.addPet(pet);
                    return charge(order);
                }

                private PaymentResult charge(Order order) {
                    return new PaymentResult();
                }
            }
            """.trimIndent(),
        )
        root.resolve("PaymentResult.java").writeText("package com.app.payment;\npublic class PaymentResult {}\n")
        root.resolve("Order.java").writeText("package com.app.payment;\npublic class Order {}\n")

        val manager = SnapshotManager(root)
        val processNode = manager.search("processPayment", "method", null, null).results.first()
        val plan = manager.planEdit(
            operation = "modify_method_body",
            targetNodeId = processNode.id,
            payload = EditRequestPayload(
                patch_mode = "insert_before",
                anchor = "owner.addPet(pet);",
                snippet = "pet.setOwner(owner);",
            ),
        )

        assertTrue(plan.validation_errors.isEmpty())
        assertTrue(plan.diff.contains("+        pet.setOwner(owner);"))
        assertTrue(!plan.diff.contains("-    public PaymentResult processPayment"))
    }

    @Test
    fun plansAndAppliesMethodRenames() {
        val root = createTempDirectory("graphharness-rename-test")
        val service = root.resolve("PaymentService.java")
        service.writeText(
            """
            package com.app.payment;

            public class PaymentService {
                public PaymentResult processPayment(Order order) {
                    return charge(order);
                }

                public PaymentResult charge(Order order) {
                    return new PaymentResult();
                }
            }
            """.trimIndent(),
        )
        val controller = root.resolve("CheckoutController.java")
        controller.writeText(
            """
            package com.app.payment;

            public class CheckoutController {
                public PaymentResult submit(Order order) {
                    PaymentService service = new PaymentService();
                    return service.charge(order);
                }
            }
            """.trimIndent(),
        )
        root.resolve("PaymentResult.java").writeText("package com.app.payment;\npublic class PaymentResult {}\n")
        root.resolve("Order.java").writeText("package com.app.payment;\npublic class Order {}\n")

        val manager = SnapshotManager(root)
        val chargeNode = manager.search("PaymentService.charge", "method", null, null).results.first()
        val plan = manager.planEdit(
            operation = "rename_node",
            targetNodeId = chargeNode.id,
            payload = EditRequestPayload(new_name = "chargePayment"),
        )

        assertTrue(plan.validation_errors.isEmpty())
        assertTrue(plan.affected_files.size >= 2)
        assertTrue(plan.diff.contains("chargePayment"))

        val apply = manager.applyEdit(plan.edit_id!!)
        assertTrue(apply.success)
        assertTrue(service.readText().contains("chargePayment(Order order)"))
        assertTrue(controller.readText().contains("service.chargePayment(order)"))
    }

    @Test
    fun suggestsEditCandidatesFromNaturalLanguageTasks() {
        val root = createTempDirectory("graphharness-candidate-test")
        root.resolve("OwnerRepository.java").writeText(
            """
            package com.app.payment;

            public interface OwnerRepository {
                Owner findById(int id);
            }
            """.trimIndent(),
        )
        root.resolve("PetController.java").writeText(
            """
            package com.app.payment;

            public class PetController {
                public void processCreationForm(Owner owner, Pet pet) {
                    owner.addPet(pet);
                }
            }
            """.trimIndent(),
        )
        root.resolve("Owner.java").writeText("package com.app.payment;\npublic class Owner { public void addPet(Pet pet) {} }\n")
        root.resolve("Pet.java").writeText("package com.app.payment;\npublic class Pet {}\n")

        val manager = SnapshotManager(root)
        val renameCandidates = manager.editCandidates("Rename method findById to findOwnerById in the repository", 3)
        assertTrue(renameCandidates.candidates.isNotEmpty())
        assertTrue(renameCandidates.candidates.first().suggested_operation == "rename_node")
        assertTrue(renameCandidates.candidates.first().node.name.endsWith("OwnerRepository.findById"))
        assertTrue(renameCandidates.candidates.first().suggested_payload["new_name"] == "findOwnerById")

        val patchCandidates = manager.editCandidates("Insert \"pet.setOwner(owner);\" before \"owner.addPet(pet);\" in processCreationForm", 3)
        assertTrue(patchCandidates.candidates.isNotEmpty())
        assertTrue(patchCandidates.candidates.first().suggested_operation == "modify_method_body")
        assertTrue(patchCandidates.candidates.first().node.name.endsWith("PetController.processCreationForm"))
        assertTrue(patchCandidates.candidates.first().suggested_payload["patch_mode"] == "insert_before")
    }

    @Test
    fun verifiesCandidatesAndFlagsDisambiguation() {
        val root = createTempDirectory("graphharness-verify-test")
        root.resolve("OwnerController.java").writeText(
            """
            package com.app.payment;

            public class OwnerController {
                public void processCreationForm(Owner owner) {
                    save(owner);
                }
            }
            """.trimIndent(),
        )
        root.resolve("PetController.java").writeText(
            """
            package com.app.payment;

            public class PetController {
                public void processCreationForm(Owner owner, Pet pet) {
                    owner.addPet(pet);
                }
            }
            """.trimIndent(),
        )
        root.resolve("Owner.java").writeText("package com.app.payment;\npublic class Owner { public void addPet(Pet pet) {} }\n")
        root.resolve("Pet.java").writeText("package com.app.payment;\npublic class Pet {}\n")

        val manager = SnapshotManager(root)
        val candidates = manager.editCandidates("Insert \"pet.setOwner(owner);\" before \"owner.addPet(pet);\" in processCreationForm", 3)
        assertTrue(!candidates.needs_disambiguation)
        val top = candidates.candidates.first()
        assertTrue(top.confidence >= 0.65)

        val verified = manager.verifyCandidate(
            task = "Insert \"pet.setOwner(owner);\" before \"owner.addPet(pet);\" in processCreationForm",
            nodeId = top.node.id,
            payload = EditRequestPayload(
                patch_mode = "insert_before",
                anchor = "owner.addPet(pet);",
                snippet = "pet.setOwner(owner);",
            ),
        )
        assertTrue(verified.anchor_present)
        assertTrue(!verified.snippet_present)
        assertTrue(verified.confidence >= 0.78)

        val wrongNode = manager.search("OwnerController.processCreationForm", "method", null, null).results.first()
        val rejected = manager.verifyCandidate(
            task = "Insert \"pet.setOwner(owner);\" before \"owner.addPet(pet);\" in processCreationForm",
            nodeId = wrongNode.id,
            payload = EditRequestPayload(
                patch_mode = "insert_before",
                anchor = "owner.addPet(pet);",
                snippet = "pet.setOwner(owner);",
            ),
        )
        assertTrue(!rejected.anchor_present)
        assertTrue(rejected.confidence < verified.confidence)
    }
}
