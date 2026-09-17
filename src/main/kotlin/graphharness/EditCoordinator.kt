package graphharness

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class EditLease(
    val file: String,
    val id: String,
    val generation: String,
    val epoch: String,
    val owner: String,
    val expectedHash: String,
    val expiresUtc: String,
    val remainingMs: Long,
)

enum class LeaseTransitionType { ACQUIRED, DENIED, RENEWED, RELEASED, EXPIRED }

data class LeaseTransition(
    val type: LeaseTransitionType,
    val lease: EditLease,
    val requester: String? = null,
)

data class CommitOutcome(val committed: Boolean, val callbackError: String? = null)

class EditCoordinatorFailure(
    val code: String,
    message: String,
    val details: Map<String, String> = emptyMap(),
) : RuntimeException(message)

class EditCoordinator(
    root: Path,
    private val epoch: String,
    private val clock: () -> Long = System::nanoTime,
    private val authorize: (ownerId: String) -> Unit,
    private val onTransition: (LeaseTransition) -> Unit,
    private val beforeAtomicMove: (() -> Unit)? = null,
) {
    private val root = root.toRealPath(NOFOLLOW_LINKS)
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val leases = ConcurrentHashMap<String, InternalLease>()
    private val leaseFiles = ConcurrentHashMap<String, String>()
    private val tombstones = ConcurrentHashMap<String, Tombstone>()
    private val tombstoneOrder = ConcurrentLinkedQueue<TombstoneKey>()
    private val tombstoneLock = Any()
    private val admission = Semaphore(MAX_ACTIVE_LEASES, true)
    private val generations = AtomicLong()

    fun acquire(owner: String, file: String, expectedHash: String): EditLease {
        val key = key(file)
        return lockFor(key).withLock {
            authorize(owner)
            expireLocked(key)
            val existing = leases[key]
            if (existing != null) {
                val view = view(existing)
                onTransition(LeaseTransition(LeaseTransitionType.DENIED, view, owner))
                throw failure("lease_busy", "File is reserved by another session.", view, mapOf("file" to key))
            }
            if (!admission.tryAcquire()) throw EditCoordinatorFailure("lease_limit", "Too many active file leases.")
            var admitted = false
            try {
            val target = target(key)
            if (sha256(Files.readAllBytes(target)) != expectedHash) {
                throw EditCoordinatorFailure("stale_source", "File changed before reservation.", mapOf("file" to key))
            }
            val now = clock()
            val lease = InternalLease(key, UUID.randomUUID().toString(), generations.incrementAndGet().toString(), owner, expectedHash, now, now + TTL_NANOS, now + MAX_HOLD_NANOS)
            leases[key] = lease
            leaseFiles[lease.id] = key
            val view = view(lease)
            try {
                onTransition(LeaseTransition(LeaseTransitionType.ACQUIRED, view))
            } catch (failure: Exception) {
                removeActive(key, lease)
                throw failure
            }
            admitted = true
            view
            } finally {
                if (!admitted) admission.release()
            }
        }
    }

    fun renew(owner: String, leaseId: String, generation: String): EditLease = withLease(owner, leaseId, generation) { key, lease ->
        val now = clock()
        if (now >= lease.expiresNanos) {
            removeForTransition(key, lease, LeaseTransitionType.EXPIRED)
            throw expired(lease)
        }
        val previousExpiry = lease.expiresNanos
        lease.expiresNanos = minOf(now + TTL_NANOS, lease.maximumNanos)
        val view = view(lease)
        try {
            onTransition(LeaseTransition(LeaseTransitionType.RENEWED, view))
        } catch (failure: Exception) {
            lease.expiresNanos = previousExpiry
            throw failure
        }
        view
    }

    fun release(owner: String, leaseId: String, generation: String): Boolean {
        val key = leaseFiles[leaseId] ?: return released(owner, leaseId, generation)
        return lockFor(key).withLock {
            val lease = leases[key] ?: return@withLock released(owner, leaseId, generation)
            if (lease.id != leaseId) return@withLock released(owner, leaseId, generation)
            authorize(owner)
            if (lease.owner != owner) throw EditCoordinatorFailure("not_owner", "Lease belongs to another session.", mapOf("file" to lease.file, "lease_id" to lease.id, "generation" to lease.generation))
            if (lease.generation != generation) throw EditCoordinatorFailure("lease_expired", "Lease fencing generation is no longer current.", mapOf("file" to lease.file, "lease_id" to lease.id, "generation" to lease.generation))
            if (clock() >= lease.expiresNanos) {
                removeForTransition(key, lease, LeaseTransitionType.EXPIRED)
                throw expired(lease)
            }
            removeForTransition(key, lease, LeaseTransitionType.RELEASED, tombstone = true)
            true
        }
    }

    fun closeOwner(owner: String) {
        leases.entries.filter { it.value.owner == owner }.forEach { (key, _) ->
            lockFor(key).withLock {
                val lease = leases[key] ?: return@withLock
                if (lease.owner == owner) {
                    removeForTransition(key, lease, LeaseTransitionType.RELEASED, tombstone = true)
                }
            }
        }
    }

    fun expire() {
        leases.keys.forEach { key -> lockFor(key).withLock { expireLocked(key) } }
    }

    fun commit(owner: String, leaseId: String, generation: String, baseHash: String, updatedBytes: ByteArray, onCommitted: () -> Unit): CommitOutcome =
        withLease(owner, leaseId, generation) { key, lease ->
            if (updatedBytes.size > MAX_FILE_BYTES) throw EditCoordinatorFailure("request_limit", "Updated file exceeds 1 MiB.", mapOf("file" to key))
            validateLiveLease(key, lease, owner, leaseId, generation)
            if (lease.expectedHash != baseHash) throw EditCoordinatorFailure("stale_source", "Edit base hash does not match the reserved source.", mapOf("file" to key))
            val destination = target(key)
            if (sha256(Files.readAllBytes(destination)) != baseHash) throw EditCoordinatorFailure("stale_source", "File changed before commit.", mapOf("file" to key))
            val permissions = permissions(destination)
            val staged = Files.createTempFile(destination.parent, ".graphharness-", ".tmp")
            try {
                Files.write(staged, updatedBytes)
                Files.setPosixFilePermissions(staged, permissions)
                if (sha256(Files.readAllBytes(destination)) != baseHash) throw EditCoordinatorFailure("stale_source", "File changed before commit.", mapOf("file" to key))
                validateLiveLease(key, lease, owner, leaseId, generation)
                beforeAtomicMove?.invoke()
                try {
                    Files.move(staged, destination, ATOMIC_MOVE, REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    throw EditCoordinatorFailure("unsupported_operation", "Atomic replacement is unavailable for this file.", mapOf("file" to key))
                }
            } catch (failure: Exception) {
                runCatching { Files.deleteIfExists(staged) }
                throw failure
            }
            val callbackFailure = runCatching { onCommitted() }.exceptionOrNull()
            val callbackError = callbackFailure?.message ?: callbackFailure?.javaClass?.simpleName
            CommitOutcome(true, callbackError)
        }

    private fun <T> withLease(owner: String, leaseId: String, generation: String, block: (String, InternalLease) -> T): T {
        val key = leaseFiles[leaseId]
            ?: throw EditCoordinatorFailure("lease_expired", "Lease is no longer active.", mapOf("lease_id" to leaseId, "generation" to generation))
        return lockFor(key).withLock {
            val lease = leases[key] ?: throw EditCoordinatorFailure("lease_expired", "Lease is no longer active.", mapOf("lease_id" to leaseId, "generation" to generation))
            authorize(owner)
            if (lease.owner != owner) throw EditCoordinatorFailure("not_owner", "Lease belongs to another session.", mapOf("file" to lease.file, "lease_id" to lease.id, "generation" to lease.generation))
            if (lease.id != leaseId || lease.generation != generation) throw EditCoordinatorFailure("lease_expired", "Lease fencing generation is no longer current.", mapOf("file" to lease.file, "lease_id" to lease.id, "generation" to lease.generation))
            if (clock() >= lease.expiresNanos) {
                removeForTransition(key, lease, LeaseTransitionType.EXPIRED)
                throw expired(lease)
            }
            block(key, lease)
        }
    }

    private fun validateLiveLease(key: String, lease: InternalLease, owner: String, leaseId: String, generation: String) {
        authorize(owner)
        if (leases[key] !== lease || lease.id != leaseId || lease.generation != generation) throw EditCoordinatorFailure("lease_expired", "Lease fencing generation is no longer current.", mapOf("file" to lease.file, "lease_id" to lease.id, "generation" to lease.generation))
        if (clock() >= lease.expiresNanos) {
            removeForTransition(key, lease, LeaseTransitionType.EXPIRED)
            throw expired(lease)
        }
    }

    private fun expireLocked(key: String) {
        val lease = leases[key] ?: return
        if (clock() >= lease.expiresNanos) removeForTransition(key, lease, LeaseTransitionType.EXPIRED)
    }

    private fun removeForTransition(key: String, lease: InternalLease, type: LeaseTransitionType, tombstone: Boolean = false) {
        if (!removeActive(key, lease)) return
        try {
            onTransition(LeaseTransition(type, view(lease)))
        } catch (failure: Exception) {
            leases[key] = lease
            leaseFiles[lease.id] = key
            throw failure
        }
        if (tombstone) rememberReleased(lease)
        admission.release()
    }

    private fun removeActive(key: String, lease: InternalLease): Boolean {
        if (!leases.remove(key, lease)) return false
        leaseFiles.remove(lease.id, key)
        return true
    }

    private fun released(owner: String, leaseId: String, generation: String): Boolean {
        val initial = synchronized(tombstoneLock) {
            purgeTombstonesLocked()
            tombstones[leaseId]
                ?: throw EditCoordinatorFailure("lease_expired", "Lease is no longer active.", mapOf("lease_id" to leaseId, "generation" to generation))
        }
        return lockFor(initial.file).withLock {
            authorize(owner)
            synchronized(tombstoneLock) {
                purgeTombstonesLocked()
                val tombstone = tombstones[leaseId]
                    ?: throw EditCoordinatorFailure("lease_expired", "Lease is no longer active.", mapOf("lease_id" to leaseId, "generation" to generation))
            if (tombstone.owner != owner) throw EditCoordinatorFailure("not_owner", "Lease belongs to another session.", mapOf("lease_id" to leaseId, "generation" to tombstone.generation))
            if (tombstone.generation != generation) throw EditCoordinatorFailure("lease_expired", "Lease fencing generation is no longer current.", mapOf("lease_id" to leaseId, "generation" to tombstone.generation))
            true
            }
        }
    }

    private fun rememberReleased(lease: InternalLease) {
        synchronized(tombstoneLock) {
            val tombstone = Tombstone(lease.id, lease.file, lease.owner, lease.generation, clock() + TOMBSTONE_NANOS)
            tombstones[lease.id] = tombstone
            tombstoneOrder.add(TombstoneKey(lease.id, tombstone.generation))
            purgeTombstonesLocked()
        }
    }

    private fun purgeTombstonesLocked() {
        val now = clock()
        while (true) {
            val next = tombstoneOrder.peek() ?: return
            val tombstone = tombstones[next.id]
            if (tombstone == null || tombstone.generation != next.generation || tombstone.expiresNanos <= now || tombstones.size > MAX_TOMBSTONES) {
                tombstoneOrder.poll()
                if (tombstone != null && tombstone.generation == next.generation && (tombstone.expiresNanos <= now || tombstones.size > MAX_TOMBSTONES)) tombstones.remove(next.id, tombstone)
            } else return
        }
    }

    private fun expired(lease: InternalLease) = EditCoordinatorFailure("lease_expired", "Lease expired.", mapOf("file" to lease.file, "lease_id" to lease.id, "generation" to lease.generation))

    private fun failure(code: String, message: String, lease: EditLease, extra: Map<String, String>) = EditCoordinatorFailure(code, message, extra + mapOf("lease_id" to lease.id, "generation" to lease.generation, "owner" to lease.owner, "expires_utc" to lease.expiresUtc, "remaining_ms" to lease.remainingMs.toString()))

    private fun key(file: String): String {
        if (file.isBlank() || file.length > 4096 || file.any { it.isISOControl() || it == '\\' }) throw EditCoordinatorFailure("invalid_request", "File path is invalid.")
        val candidate = Path.of(file)
        if (candidate.isAbsolute || candidate.any { it.toString() == ".." }) throw EditCoordinatorFailure("invalid_request", "File path escapes the repository.")
        return candidate.normalize().toString().replace('\\', '/')
    }

    private fun target(key: String): Path {
        var current = root
        Path.of(key).forEach { part ->
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) throw EditCoordinatorFailure("invalid_request", "Symbolic links are unsupported.", mapOf("file" to key))
        }
        if (!current.normalize().startsWith(root) || !Files.isRegularFile(current, NOFOLLOW_LINKS)) throw EditCoordinatorFailure("invalid_request", "File is not a regular repository file.", mapOf("file" to key))
        if (Files.size(current) > MAX_FILE_BYTES) throw EditCoordinatorFailure("request_limit", "File exceeds 1 MiB.", mapOf("file" to key))
        return current
    }

    private fun permissions(file: Path): Set<PosixFilePermission> = try {
        Files.getPosixFilePermissions(file, NOFOLLOW_LINKS)
    } catch (_: UnsupportedOperationException) {
        throw EditCoordinatorFailure("unsupported_operation", "POSIX file permissions are required.")
    }

    private fun lockFor(key: String): ReentrantLock = locks.computeIfAbsent(key) { ReentrantLock() }

    private fun view(lease: InternalLease): EditLease {
        val remaining = maxOf(0, (lease.expiresNanos - clock()) / 1_000_000)
        return EditLease(lease.file, lease.id, lease.generation, epoch, lease.owner, lease.expectedHash, Instant.now().plusMillis(remaining).toString(), remaining)
    }

    private data class InternalLease(
        val file: String,
        val id: String,
        val generation: String,
        val owner: String,
        val expectedHash: String,
        val acquiredNanos: Long,
        var expiresNanos: Long,
        val maximumNanos: Long,
    )

    private data class Tombstone(val id: String, val file: String, val owner: String, val generation: String, val expiresNanos: Long)
    private data class TombstoneKey(val id: String, val generation: String)

    private companion object {
        const val MAX_FILE_BYTES = 1_048_576
        const val TTL_NANOS = 30_000_000_000L
        const val MAX_HOLD_NANOS = 120_000_000_000L
        const val TOMBSTONE_NANOS = 120_000_000_000L
        const val MAX_TOMBSTONES = 1_024
        const val MAX_ACTIVE_LEASES = 128
    }
}
