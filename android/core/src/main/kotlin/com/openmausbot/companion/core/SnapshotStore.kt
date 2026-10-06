package com.openmausbot.companion.core

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The disk, as far as [SnapshotStore] needs one: one named blob per paired
 * computer. Blocking; the store only calls it from its own writer.
 *
 * Injectable because `:core` is plain JVM. The app supplies the device's
 * storage — `filesDir/snapshots/<name>.json`, encrypted with a Keystore-backed
 * AEAD key and excluded from Auto Backup — and tests use
 * [PlainFileSnapshotStorage] over a temporary directory.
 */
interface SnapshotStorage {
    /**
     * The bytes saved under [name], or null when there are none. Throws when
     * they cannot be read yet — a key that is not available before the first
     * unlock, for instance — which is not the same as having none.
     */
    fun read(name: String): ByteArray?

    /** Replaces [name] in one step, creating its directory if needed, so a reader never sees half a file. */
    fun write(name: String, bytes: ByteArray)

    /** Removes [name]. A blob that does not exist is not an error. */
    fun delete(name: String)

    /** Removes every snapshot. */
    fun deleteAll()
}

/**
 * Snapshot files in [directory], `<name>.json`, written to a sibling and
 * renamed over the old file. Not encrypted: on a device it only ever sits
 * under the app's encrypting [SnapshotStorage].
 */
class PlainFileSnapshotStorage(private val directory: File) : SnapshotStorage {
    override fun read(name: String): ByteArray? {
        val file = file(name)
        return if (file.isFile) file.readBytes() else null
    }

    override fun write(name: String, bytes: ByteArray) {
        val target = file(name)
        directory.mkdirs()
        val sibling = File(directory, ".$name.${UUID.randomUUID()}$TEMPORARY_SUFFIX")
        try {
            FileOutputStream(sibling).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                sibling.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            sibling.delete()
        }
    }

    override fun delete(name: String) {
        file(name).delete()
    }

    override fun deleteAll() {
        directory.listFiles()?.forEach { file ->
            if (file.isFile && (file.name.endsWith(SUFFIX) || file.name.endsWith(TEMPORARY_SUFFIX))) file.delete()
        }
    }

    private fun file(name: String): File {
        require(name.all { it in SnapshotStore.FILE_NAME_CHARACTERS || it == '%' }) { "Not a snapshot name: $name" }
        return File(directory, "$name$SUFFIX")
    }

    private companion object {
        const val SUFFIX = ".json"
        const val TEMPORARY_SUFFIX = ".tmp"
    }
}

/**
 * Saves, loads and wipes offline snapshots, one per connection (MOCA-296).
 * The same contract as `SnapshotStore` on iOS.
 *
 * The app saves at most every few seconds after a hydrate or a batch, so the
 * store's one job beyond reading and writing is staying out of the way: every
 * write, wipe and read runs in order on its own writer coroutine on
 * [dispatcher], never the caller's thread. The 1.3.0 freeze was main-thread
 * work; this must not add any.
 *
 * Saves are fire-and-forget and coalescing: each takes a ticket, and a queued
 * write runs only while its ticket is still the newest for that computer, so
 * a burst of saves writes the one already in flight and the newest. A wipe
 * takes a ticket too, so a save that was already waiting can never put a
 * forgotten computer's data back. Loads run after everything requested before
 * them.
 *
 * The writer lives as long as [scope]. Once the scope ends nothing more is
 * written and every load answers with no copy, but a wipe still happens,
 * whether it was waiting in line or asked for afterwards: forgetting a
 * computer must not depend on the store outliving the request.
 */
class SnapshotStore(
    private val storage: SnapshotStorage,
    scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private sealed interface Operation
    private class Write(val ticket: Long, val connectionId: String, val build: () -> EncodedSnapshot?) : Operation
    private class Remove(val connectionId: String) : Operation
    private data object RemoveAll : Operation
    private class Load(
        val connectionId: String,
        val serverEnvironmentId: String?,
        val reply: CompletableDeferred<StateSnapshot?>,
    ) : Operation
    private class Barrier(val reply: CompletableDeferred<Unit>) : Operation

    private val operations = Channel<Operation>(Channel.UNLIMITED)
    private val lock = Any()

    // Guarded by `lock`.
    private var lastTicket = 0L
    private val newestTicket = HashMap<String, Long>()

    init {
        scope.launch(dispatcher) {
            for (operation in operations) {
                if (!isActive) {
                    drain(operation)
                    continue
                }
                try {
                    run(operation)
                } catch (error: Exception) {
                    // A failed write leaves the previous file; the next save retries.
                    if (error is CancellationException) throw error
                } finally {
                    // Whatever happened, nobody is left waiting on it.
                    abandon(operation)
                }
            }
        }.invokeOnCompletion { cause ->
            // A store whose scope ended answers every waiting load with no copy
            // and still runs every waiting wipe.
            operations.close(cause)
            while (true) drain(operations.tryReceive().getOrNull() ?: break)
        }
    }

    // MARK: - Saving

    /** Write [snapshot] soon, unless a newer save or a wipe for the same computer arrives first. */
    fun save(snapshot: StateSnapshot) {
        enqueue(snapshot.connectionId) { EncodedSnapshot(snapshot, snapshot.encoded()) }
    }

    /**
     * Build and write the snapshot of [state] on the store's writer, so the
     * caller pays for nothing: the state is immutable, so handing it over is
     * the value copy. A cached state builds nothing and so writes nothing.
     */
    fun save(
        state: CompanionState,
        connectionId: String,
        serverEnvironmentId: String?,
        savedAt: Long = System.currentTimeMillis(),
        routines: List<Routine> = emptyList(),
        routineRuns: List<RoutineRun> = emptyList(),
        limits: StateSnapshot.Limits = StateSnapshot.Limits.STANDARD,
    ) {
        enqueue(connectionId) {
            state.encodedOfflineSnapshot(connectionId, serverEnvironmentId, savedAt, routines, routineRuns, limits)
        }
    }

    private fun enqueue(connectionId: String, build: () -> EncodedSnapshot?) {
        val ticket = takeTicket(listOf(connectionId))
        operations.trySend(Write(ticket, connectionId, build))
    }

    // MARK: - Loading

    /**
     * The saved copy for this computer, decoded off the caller's thread, or
     * null when there is none to show. A copy written under another schema
     * version, for another connection id or for another server identity — or
     * one that does not decode — is refused and removed. One that cannot be
     * read yet is left alone: before the first unlock it is fine, just locked.
     */
    suspend fun load(connectionId: String, serverEnvironmentId: String?): StateSnapshot? {
        val reply = CompletableDeferred<StateSnapshot?>()
        if (operations.trySend(Load(connectionId, serverEnvironmentId, reply)).isFailure) return null
        return reply.await()
    }

    suspend fun load(connection: Connection): StateSnapshot? =
        load(connection.id, connection.serverEnvironmentId)

    // MARK: - Wiping

    /**
     * Forget one computer's copy: forget computer, pair again, a 401 that
     * unpaired this phone, a changed server identity. Saves still waiting for
     * it are dropped.
     */
    fun wipe(connectionId: String) {
        takeTicket(listOf(connectionId))
        remove(Remove(connectionId))
    }

    /** Forget every computer's copy, as sign-out does. */
    fun wipeAll() {
        val known = synchronized(lock) { newestTicket.keys.toList() }
        takeTicket(known)
        remove(RemoveAll)
    }

    private fun remove(operation: Operation) {
        if (operations.trySend(operation).isSuccess) return
        // The writer ended with its scope, so nothing can write after this;
        // the removal runs on its own, still off the caller's thread.
        CoroutineScope(dispatcher).launch { drain(operation) }
    }

    /** Returns once everything requested before it has finished — for the app going to the background, and for tests. */
    suspend fun flush() {
        val reply = CompletableDeferred<Unit>()
        if (operations.trySend(Barrier(reply)).isFailure) return
        reply.await()
    }

    // MARK: - The writer

    private fun run(operation: Operation) {
        when (operation) {
            is Write -> {
                if (!isNewest(operation.ticket, operation.connectionId)) return
                val encoded = operation.build() ?: return
                // Building can take a moment; a newer request may have arrived
                // meanwhile, and its write is the one that counts.
                if (encoded.snapshot.connectionId != operation.connectionId ||
                    !isNewest(operation.ticket, operation.connectionId)
                ) {
                    return
                }
                storage.write(fileName(operation.connectionId), encoded.bytes)
            }
            is Remove -> storage.delete(fileName(operation.connectionId))
            RemoveAll -> storage.deleteAll()
            is Load -> operation.reply.complete(loadNow(operation.connectionId, operation.serverEnvironmentId))
            is Barrier -> operation.reply.complete(Unit)
        }
    }

    private fun loadNow(connectionId: String, serverEnvironmentId: String?): StateSnapshot? {
        val name = fileName(connectionId)
        val bytes = try {
            storage.read(name)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        } ?: return null
        val snapshot = runCatching { StateSnapshot.decode(bytes) }.getOrNull()?.takeIf {
            it.schemaVersion == StateSnapshot.SCHEMA_VERSION &&
                it.connectionId == connectionId &&
                it.serverEnvironmentId == serverEnvironmentId
        }
        if (snapshot == null) runCatching { storage.delete(name) }
        return snapshot
    }

    /**
     * Nobody waits on a write or a wipe; a load or a flush not answered yet is
     * answered with nothing. A no-op for one that already was.
     */
    private fun abandon(operation: Operation) {
        when (operation) {
            is Load -> operation.reply.complete(null)
            is Barrier -> operation.reply.complete(Unit)
            is Write, is Remove, RemoveAll -> Unit
        }
    }

    /**
     * An operation the writer will not run because its scope ended: a write
     * is dropped and a waiter answered with nothing, but a wipe still
     * happens. Never throws — it also runs inside a completion handler.
     */
    private fun drain(operation: Operation) {
        when (operation) {
            is Remove, RemoveAll -> runCatching { run(operation) }
            is Write, is Load, is Barrier -> abandon(operation)
        }
    }

    // MARK: - Tickets

    private fun takeTicket(connectionIds: List<String>): Long = synchronized(lock) {
        lastTicket += 1
        connectionIds.forEach { newestTicket[it] = lastTicket }
        lastTicket
    }

    private fun isNewest(ticket: Long, connectionId: String): Boolean =
        synchronized(lock) { newestTicket[connectionId] == ticket }

    companion object {
        internal const val FILE_NAME_CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

        /**
         * Connection ids are UUIDs, but the name is built so that no id,
         * however spelled, can point outside the directory: anything but
         * letters, digits, `-` and `_` is percent-encoded.
         */
        fun fileName(connectionId: String): String = buildString {
            connectionId.encodeToByteArray().forEach { byte ->
                val character = (byte.toInt() and 0xFF).toChar()
                if (byte >= 0 && character in FILE_NAME_CHARACTERS) {
                    append(character)
                } else {
                    append('%').append("%02X".format(byte.toInt() and 0xFF))
                }
            }
        }
    }
}
