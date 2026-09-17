package graphharness

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class EditCoordinatorTest {
    @Test
    fun simultaneousAcquisitionHasOneWinnerAndDifferentFilesRemainAvailable() {
        val fixture = fixture()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val winners = mutableListOf<String>()
        repeat(2) { number ->
            thread {
                start.await()
                runCatching { fixture.coordinator.acquire("owner-$number", "A.java", fixture.hash("A.java")) }
                    .onSuccess { synchronized(winners) { winners += it.owner } }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(1, winners.size)
        assertEquals("owner-other", fixture.coordinator.acquire("owner-other", "B.java", fixture.hash("B.java")).owner)
        assertTrue(fixture.transitions.any { it.type == LeaseTransitionType.DENIED })
    }

    @Test
    fun foreignReleaseAndStaleGenerationAreRejectedWhileReleaseIsIdempotent() {
        val fixture = fixture()
        val lease = fixture.coordinator.acquire("owner-a", "A.java", fixture.hash("A.java"))
        assertEquals("not_owner", assertFailsWith<EditCoordinatorFailure> {
            fixture.coordinator.release("owner-b", lease.id, lease.generation)
        }.code)
        assertEquals("lease_expired", assertFailsWith<EditCoordinatorFailure> {
            fixture.coordinator.release("owner-a", lease.id, "999")
        }.code)
        assertTrue(fixture.coordinator.release("owner-a", lease.id, lease.generation))
        assertTrue(fixture.coordinator.release("owner-a", lease.id, lease.generation))
        assertEquals("not_owner", assertFailsWith<EditCoordinatorFailure> {
            fixture.coordinator.release("owner-b", lease.id, lease.generation)
        }.code)
        assertEquals("lease_expired", assertFailsWith<EditCoordinatorFailure> {
            fixture.coordinator.release("owner-a", lease.id, "999")
        }.code)
        assertEquals("lease_expired", assertFailsWith<EditCoordinatorFailure> {
            fixture.coordinator.release("owner-a", "unknown", "1")
        }.code)
    }

    @Test
    fun expiryAndFencingRejectOldOwnerAfterReassignment() {
        val fixture = fixture()
        val old = fixture.coordinator.acquire("owner-a", "A.java", fixture.hash("A.java"))
        fixture.clock.advanceSeconds(31)
        fixture.coordinator.expire()
        val replacement = fixture.coordinator.acquire("owner-b", "A.java", fixture.hash("A.java"))
        assertNotEquals(old.generation, replacement.generation)
        assertEquals("lease_expired", assertFailsWith<EditCoordinatorFailure> {
            fixture.coordinator.commit("owner-a", old.id, old.generation, old.expectedHash, "class A {}".toByteArray()) {}
        }.code)
        assertEquals("lease_expired", assertFailsWith<EditCoordinatorFailure> {
            fixture.coordinator.renew("owner-a", old.id, old.generation)
        }.code)
        assertTrue(fixture.transitions.any { it.type == LeaseTransitionType.EXPIRED })
    }

    @Test
    fun renewExtendsTheLeaseWithoutExceedingItsMaximumHold() {
        val fixture = fixture()
        val lease = fixture.coordinator.acquire("owner-a", "A.java", fixture.hash("A.java"))
        fixture.clock.advanceSeconds(20)
        assertEquals(30_000L, fixture.coordinator.renew("owner-a", lease.id, lease.generation).remainingMs)
        repeat(4) {
            fixture.clock.advanceSeconds(20)
            fixture.coordinator.renew("owner-a", lease.id, lease.generation)
        }
        fixture.clock.advanceSeconds(10)
        assertEquals(10_000L, fixture.coordinator.renew("owner-a", lease.id, lease.generation).remainingMs)
        fixture.clock.advanceSeconds(11)
        assertEquals("lease_expired", assertFailsWith<EditCoordinatorFailure> {
            fixture.coordinator.renew("owner-a", lease.id, lease.generation)
        }.code)
    }

    @Test
    fun commitRejectsExternalChangesAndPreservesBytesAndModeOnAtomicCommit() {
        val fixture = fixture()
        val target = fixture.root.resolve("A.java")
        val lease = fixture.coordinator.acquire("owner-a", "A.java", fixture.hash("A.java"))
        Files.writeString(target, "class External {}\n")
        assertEquals("stale_source", assertFailsWith<EditCoordinatorFailure> {
            fixture.coordinator.commit("owner-a", lease.id, lease.generation, lease.expectedHash, "class Updated {}\n".toByteArray()) {}
        }.code)
        fixture.coordinator.release("owner-a", lease.id, lease.generation)

        val fresh = fixture.coordinator.acquire("owner-a", "A.java", fixture.hash("A.java"))
        val mode = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        Files.setPosixFilePermissions(target, mode)
        val callback = CountDownLatch(1)
        val outcome = fixture.coordinator.commit("owner-a", fresh.id, fresh.generation, fresh.expectedHash, "class Updated {}\n".toByteArray()) { callback.countDown() }
        assertTrue(outcome.committed)
        assertEquals("class Updated {}\n", Files.readString(target))
        assertEquals(mode, Files.getPosixFilePermissions(target))
        assertEquals(0, callback.count)
    }

    @Test
    fun escapingAndSymbolicLinkFilesAreRejected() {
        val fixture = fixture()
        assertEquals("invalid_request", assertFailsWith<EditCoordinatorFailure> {
            fixture.coordinator.acquire("owner-a", "../outside", "hash")
        }.code)
        val link = fixture.root.resolve("Link.java")
        Files.createSymbolicLink(link, fixture.root.resolve("A.java"))
        assertEquals("invalid_request", assertFailsWith<EditCoordinatorFailure> {
            fixture.coordinator.acquire("owner-a", "Link.java", fixture.hash("A.java"))
        }.code)
    }

    @Test
    fun expiryBeforeCommitFailsButExpiryAfterCommitEntryAllowsTheMove() {
        val before = fixture()
        val expired = before.coordinator.acquire("owner-a", "A.java", before.hash("A.java"))
        before.clock.advanceSeconds(31)
        assertEquals("lease_expired", assertFailsWith<EditCoordinatorFailure> {
            before.coordinator.commit("owner-a", expired.id, expired.generation, expired.expectedHash, "class Updated {}\n".toByteArray()) {}
        }.code)

        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val after = fixture(beforeAtomicMove = { entered.countDown(); proceed.await(2, TimeUnit.SECONDS) })
        val lease = after.coordinator.acquire("owner-a", "A.java", after.hash("A.java"))
        val result = arrayOfNulls<CommitOutcome>(1)
        val commit = thread {
            result[0] = after.coordinator.commit("owner-a", lease.id, lease.generation, lease.expectedHash, "class Updated {}\n".toByteArray()) {}
        }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        after.clock.advanceSeconds(31)
        val expire = thread { after.coordinator.expire() }
        proceed.countDown()
        commit.join(2_000)
        expire.join(2_000)
        assertFalse(commit.isAlive)
        assertFalse(expire.isAlive)
        assertTrue(result[0]?.committed == true)
        assertEquals("class Updated {}\n", Files.readString(after.root.resolve("A.java")))
        assertEquals("owner-b", after.coordinator.acquire("owner-b", "A.java", after.hash("A.java")).owner)
    }

    @Test
    fun committedWriteSurvivesCallbackFailure() {
        val fixture = fixture()
        val lease = fixture.coordinator.acquire("owner-a", "A.java", fixture.hash("A.java"))
        val outcome = fixture.coordinator.commit("owner-a", lease.id, lease.generation, lease.expectedHash, "class Updated {}\n".toByteArray()) {
            error("journal unavailable")
        }
        assertTrue(outcome.committed)
        assertEquals("journal unavailable", outcome.callbackError)
        assertEquals("class Updated {}\n", Files.readString(fixture.root.resolve("A.java")))
    }

    @Test
    fun transitionPublicationFailureRollsBackTheLeaseMutation() {
        val failure = AtomicBoolean()
        val fixture = fixture(transitionHook = { transition ->
            if (failure.get() && transition.type in setOf(LeaseTransitionType.RENEWED, LeaseTransitionType.RELEASED)) error("state unavailable")
        })
        val lease = fixture.coordinator.acquire("owner-a", "A.java", fixture.hash("A.java"))
        failure.set(true)
        assertFailsWith<IllegalStateException> { fixture.coordinator.renew("owner-a", lease.id, lease.generation) }
        assertFailsWith<IllegalStateException> { fixture.coordinator.release("owner-a", lease.id, lease.generation) }
        assertFalse(fixture.transitions.any { it.type in setOf(LeaseTransitionType.RENEWED, LeaseTransitionType.RELEASED) })
        failure.set(false)
        assertTrue(fixture.coordinator.renew("owner-a", lease.id, lease.generation).remainingMs > 0)
        assertTrue(fixture.coordinator.release("owner-a", lease.id, lease.generation))
    }

    @Test
    fun activeLeaseAdmissionIsBounded() {
        val fixture = fixture()
        repeat(129) { Files.writeString(fixture.root.resolve("F$it.java"), "class F$it {}\n") }
        repeat(128) { index ->
            fixture.coordinator.acquire("owner-$index", "F$index.java", fixture.hash("F$index.java"))
        }
        assertEquals("lease_limit", assertFailsWith<EditCoordinatorFailure> {
            fixture.coordinator.acquire("owner-over", "F128.java", fixture.hash("F128.java"))
        }.code)
    }

    private fun fixture(beforeAtomicMove: (() -> Unit)? = null, transitionHook: ((LeaseTransition) -> Unit)? = null): Fixture {
        val root = createTempDirectory("graphharness-edit-coordinator")
        Files.writeString(root.resolve("A.java"), "class A {}\n")
        Files.writeString(root.resolve("B.java"), "class B {}\n")
        val clock = TestClock()
        val transitions = mutableListOf<LeaseTransition>()
        val coordinator = EditCoordinator(root, "epoch", clock::now, authorize = { owner ->
            if (owner == "expired") throw EditCoordinatorFailure("session_expired", "Session expired.")
        }, onTransition = { transition ->
            transitionHook?.invoke(transition)
            synchronized(transitions) { transitions += transition }
        }, beforeAtomicMove = beforeAtomicMove)
        return Fixture(root, clock, transitions, coordinator)
    }

    private class Fixture(val root: Path, val clock: TestClock, val transitions: MutableList<LeaseTransition>, val coordinator: EditCoordinator) {
        fun hash(file: String): String = sha256(Files.readAllBytes(root.resolve(file)))
    }

    private class TestClock {
        private val nanos = AtomicLong()
        fun now(): Long = nanos.get()
        fun advanceSeconds(seconds: Long) { nanos.addAndGet(seconds * 1_000_000_000L) }
    }
}
