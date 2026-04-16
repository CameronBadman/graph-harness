package graphharness

import kotlin.io.path.createTempDirectory
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.io.path.setPosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.nio.file.attribute.PosixFilePermission

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
        root.resolve("CheckoutController.java").writeText(
            """
            package com.app.payment;

            public class CheckoutController {
                public PaymentResult submit(Order order) {
                    PaymentService service = new PaymentService();
                    return service.processPayment(order);
                }
            }
            """.trimIndent(),
        )

        val manager = SnapshotManager(root)
        val summary = manager.summaryMap()

        assertEquals(5, summary.project.total_files)
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

        val fitness = manager.agentFitness()
        assertTrue(fitness.overall_score in 0..100)
        assertTrue(fitness.subscores.any { it.name == "navigability" })
        assertTrue(fitness.metrics["agent_relevant_method_count"] ?: 0.0 >= 1.0)
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
    fun validatesPlannedAndAppliedEditsOnScratchCopies() {
        val root = createTempDirectory("graphharness-validate-test")
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
        root.resolve("PaymentResult.java").writeText("package com.app.payment;\npublic class PaymentResult {}\n")
        root.resolve("Order.java").writeText("package com.app.payment;\npublic class Order {}\n")

        val manager = SnapshotManager(root)
        val processNode = manager.search("processPayment", "method", null, null).results.first()
        val plan = manager.planEdit(
            operation = "modify_method_body",
            targetNodeId = processNode.id,
            payload = EditRequestPayload(
                patch_mode = "insert_before",
                anchor = "return charge(order);",
                snippet = "order.toString();",
            ),
        )

        val pendingValidation = manager.validateEdit(plan.edit_id!!, "compile")
        assertTrue(pendingValidation.success)
        assertEquals("planned_edit", pendingValidation.validation_scope)
        assertEquals("project_root", pendingValidation.validation_target)
        assertEquals("javac-syntax", pendingValidation.validator)

        val apply = manager.applyEdit(plan.edit_id)
        assertTrue(apply.success)

        val appliedValidation = manager.validateEdit(plan.edit_id, "compile")
        assertTrue(appliedValidation.success)
        assertEquals("applied_workspace", appliedValidation.validation_scope)
        assertEquals("project_root", appliedValidation.validation_target)
        assertEquals("javac-syntax", appliedValidation.validator)
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

    @Test
    fun resolvesEditTargetUsingVerification() {
        val root = createTempDirectory("graphharness-resolve-test")
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
        val resolved = manager.resolveEditTarget(
            task = "Insert \"pet.setOwner(owner);\" before \"owner.addPet(pet);\" in processCreationForm",
            limit = 3,
        )

        assertTrue(!resolved.needs_disambiguation)
        assertNotNull(resolved.resolved_candidate)
        assertTrue(resolved.resolved_candidate.node.name.endsWith("PetController.processCreationForm"))
        assertNotNull(resolved.verification)
        assertTrue(resolved.verification.anchor_present)
    }

    @Test
    fun fallsBackToSyntaxValidationWhenRepoAwareValidationIsBlocked() {
        val root = createTempDirectory("graphharness-validate-fallback")
        val service = root.resolve("PaymentService.java")
        service.writeText(
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
        root.resolve("PaymentResult.java").writeText("package com.app.payment;\npublic class PaymentResult {}\n")
        root.resolve("Order.java").writeText("package com.app.payment;\npublic class Order {}\n")
        root.resolve("pom.xml").writeText("<project></project>\n")
        val mvnw = root.resolve("mvnw")
        mvnw.writeText(
            """
            #!/usr/bin/env bash
            echo "wget: Failed to fetch https://repo.maven.apache.org/example.zip"
            exit 1
            """.trimIndent(),
        )
        runCatching {
            mvnw.setPosixFilePermissions(
                setOf(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE,
                ),
            )
        }

        val manager = SnapshotManager(root)
        val processNode = manager.search("processPayment", "method", null, null).results.first()
        val plan = manager.planEdit(
            operation = "modify_method_body",
            targetNodeId = processNode.id,
            payload = EditRequestPayload(
                patch_mode = "insert_before",
                anchor = "return charge(order);",
                snippet = "order.toString();",
            ),
        )

        val validation = manager.validateEdit(plan.edit_id!!, "auto")
        assertTrue(validation.success)
        assertEquals("project_root", validation.validation_target)
        assertEquals("javac-syntax", validation.validator)
        assertTrue(validation.degraded)
        assertEquals(listOf("maven-wrapper-test", "javac-syntax"), validation.attempted_validators)
    }

    @Test
    fun scopesValidationToNearestModuleForTouchedFiles() {
        val root = createTempDirectory("graphharness-module-validate")
        root.resolve("pom.xml").writeText("<project></project>\n")
        val moduleDir = root.resolve("payments")
        moduleDir.resolve("pom.xml").parent?.createDirectories()
        moduleDir.resolve("pom.xml").writeText("<project></project>\n")
        val service = moduleDir.resolve("PaymentService.java")
        service.writeText(
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
        moduleDir.resolve("PaymentResult.java").writeText("package com.app.payment;\npublic class PaymentResult {}\n")
        moduleDir.resolve("Order.java").writeText("package com.app.payment;\npublic class Order {}\n")

        val manager = SnapshotManager(root)
        val processNode = manager.search("processPayment", "method", null, null).results.first()
        val plan = manager.planEdit(
            operation = "modify_method_body",
            targetNodeId = processNode.id,
            payload = EditRequestPayload(
                patch_mode = "insert_before",
                anchor = "return charge(order);",
                snippet = "order.toString();",
            ),
        )

        val validation = manager.validateEdit(plan.edit_id!!, "compile")
        assertTrue(validation.success)
        assertEquals("module:payments", validation.validation_target)
        assertEquals("javac-syntax", validation.validator)
        assertEquals(listOf("javac-syntax"), validation.attempted_validators)
    }

    @Test
    fun reportsAgentFitnessIssuesForTangledCode() {
        val root = createTempDirectory("graphharness-fitness-test")
        root.resolve("BigService.java").writeText(
            """
            package com.app.payment;

            public class BigService {
                public PaymentResult processPayment(Order order) {
                    validateOrder(order);
                    audit(order);
                    enrich(order);
                    reserve(order);
                    authorize(order);
                    capture(order);
                    notifyOps(order);
                    publish(order);
                    archive(order);
                    return charge(order);
                }

                public PaymentResult charge(Order order) {
                    return new PaymentResult();
                }

                public void processRefund(Order order) {
                    validateOrder(order);
                    audit(order);
                    archive(order);
                }

                private void validateOrder(Order order) {}
                private void audit(Order order) {}
                private void enrich(Order order) {}
                private void reserve(Order order) {}
                private void authorize(Order order) {}
                private void capture(Order order) {}
                private void notifyOps(Order order) {}
                private void publish(Order order) {}
                private void archive(Order order) {}
            }
            """.trimIndent(),
        )
        root.resolve("CheckoutController.java").writeText(
            """
            package com.app.payment;

            public class CheckoutController {
                public PaymentResult submit(Order order) {
                    return new BigService().processPayment(order);
                }
            }
            """.trimIndent(),
        )
        root.resolve("RefundController.java").writeText(
            """
            package com.app.payment;

            public class RefundController {
                public void processRefund(Order order) {
                    new BigService().processRefund(order);
                }
            }
            """.trimIndent(),
        )
        root.resolve("PaymentResult.java").writeText("package com.app.payment;\npublic class PaymentResult {}\n")
        root.resolve("Order.java").writeText("package com.app.payment;\npublic class Order {}\n")

        val manager = SnapshotManager(root)
        val fitness = manager.agentFitness()

        assertTrue(fitness.issues.isNotEmpty())
        assertTrue(fitness.recommended_actions.isNotEmpty())
        assertTrue(fitness.issues.any { it.title.contains("Ambiguous") || it.title.contains("Oversized") })
        assertTrue(fitness.subscores.any { it.name == "editability" && it.score < 100 })
    }
}
