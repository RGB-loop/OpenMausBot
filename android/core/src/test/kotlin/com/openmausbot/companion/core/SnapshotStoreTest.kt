package com.openmausbot.companion.core

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotStoreTest {
    /** Blobs in memory, with every operation and the thread it ran on recorded. */
    private class MemoryStorage : SnapshotStorage {
        val blobs = LinkedHashMap<String, ByteArray>()
        val writes = mutableListOf<Pair<String, StateSnapshot>>()
        val threads = mutableListOf<String>()
        var unreadable = false
        var beforeWrite: () -> Unit = {}
        val written = mutableListOf<ByteArray>()

        override fun read(name: String): ByteArray? = synchronized(this) {
            threads += Thread.currentThread().name
            if (unreadable) throw IOException("locked")
            blobs[name]
        }

        override fun write(name: String, bytes: ByteArray) {
            beforeWrite()
            synchronized(this) {
                threads += Thread.currentThread().name
                writes += name to StateSnapshot.decode(bytes)
                written += bytes
                blobs[name] = bytes
            }
        }

        override fun delete(name: String) {
            synchronized(this) { blobs.remove(name) }
        }

        override fun deleteAll() {
            synchronized(this) { blobs.clear() }
        }
    }

    /** A transcript that notes the thread every read of it runs on. */
    private class RecordingTranscript(
        private val lines: List<Message>,
        private val threads: MutableList<String>,
    ) : AbstractList<Message>() {
        override val size: Int
            get() = record { lines.size }

        override fun get(index: Int): Message = record { lines[index] }

        private fun <T> record(read: () -> T): T {
            synchronized(threads) { threads += Thread.currentThread().name }
            return read()
        }
    }

    private val directories = mutableListOf<File>()

    @AfterTest
    fun removeDirectories() {
        directories.forEach(File::deleteRecursively)
    }

    private fun temporaryDirectory(): File =
        Files.createTempDirectory("snapshots").toFile().also(directories::add)

    private fun state(text: String = "hello") = CompanionState(
        bots = listOf(snapshotBot("scout", activeLeafId = "a-2")),
        messages = mapOf("scout-thread" to snapshotChain("a", 2) { text }),
        hasMore = mapOf("scout-thread" to false),
    )

    private fun snapshot(
        connectionId: String = "mac-1",
        serverEnvironmentId: String? = "env-1",
        savedAt: Long = 1_000,
        text: String = "hello",
    ): StateSnapshot = assertNotNull(state(text).offlineSnapshot(connectionId, serverEnvironmentId, savedAt))

    private fun TestScope.store(storage: SnapshotStorage) =
        SnapshotStore(storage, backgroundScope, StandardTestDispatcher(testScheduler))

    // MARK: - Saving and loading

    @Test
    fun `a saved state loads back as the same snapshot`() = runTest {
        val directory = temporaryDirectory()
        val store = store(PlainFileSnapshotStorage(directory))

        store.save(state(), "mac-1", "env-1", savedAt = 1_000, routines = listOf(snapshotRoutine))
        val loaded = store.load("mac-1", "env-1")

        val expected = state().offlineSnapshot("mac-1", "env-1", 1_000, routines = listOf(snapshotRoutine))
        assertEquals(expected, loaded)
        assertEquals(listOf("mac-1.json"), directory.list()?.toList())
        assertEquals(loaded, store.load(Connection(id = "mac-1", name = "Mac", host = "mac.local", port = 8810, serverEnvironmentId = "env-1")))
    }

    @Test
    fun `a computer with no copy loads nothing`() = runTest {
        val store = store(MemoryStorage())

        assertNull(store.load("mac-1", null))
    }

    @Test
    fun `the store keeps the snapshot inside its limits`() = runTest {
        val storage = MemoryStorage()
        val store = store(storage)
        val big = CompanionState(
            bots = listOf(snapshotBot("scout", tasks = (1..10).map { snapshotTask("t$it", it.toDouble()) })),
            messages = (1..10).associate { "t$it" to snapshotChain("t$it", 1, startAt = -10.0) { "z".repeat(10_000) } },
            hasMore = (1..10).associate { "t$it" to false },
        )
        val limits = StateSnapshot.Limits.STANDARD.copy(maxBytes = 40_000)

        store.save(big, "mac-1", null, limits = limits)
        store.flush()

        assertTrue(storage.blobs.getValue("mac-1").size <= 40_000)
        assertEquals(listOf("t10", "t9", "t8"), storage.writes.single().second.threads.keys.toList())
    }

    @Test
    fun `a snapshot made by hand still carries no record transcript to disk`() = runTest {
        val storage = MemoryStorage()
        val store = store(storage)
        val pixels = Base64.getEncoder().encodeToString("RECORD-PIXELS-".repeat(40).toByteArray())
        fun shot(id: String) = Message(id = id, role = Message.Role.BOT, kind = Message.Kind.SCREEN, at = 1.0, png = pixels)
        // A hydrated roster: every record still holds its own page of messages.
        val bots = listOf(snapshotBot("scout").copy(messages = listOf(shot("bot-shot")), hasMore = true))
        val rooms = listOf(snapshotRoom("team").copy(messages = listOf(shot("room-shot")), hasMore = true))

        // The only way onto a snapshot is the roster type, which has no field for them.
        store.save(
            snapshot().copy(
                bots = bots.map { StateSnapshot.CachedBot(it) },
                rooms = rooms.map { StateSnapshot.CachedRoom(it) },
            ),
        )
        store.flush()

        val text = storage.written.single().decodeToString()
        listOf(pixels, "bot-shot", "room-shot").forEach { assertFalse(it in text, "found $it") }
        val roster = Json.parseToJsonElement(text).jsonObject
        (roster.getValue("bots").jsonArray + roster.getValue("rooms").jsonArray).forEach { row ->
            assertFalse("messages" in row.jsonObject.keys, row.toString())
            assertFalse("hasMore" in row.jsonObject.keys, row.toString())
        }
    }

    @Test
    fun `a cached state writes nothing`() = runTest {
        val storage = MemoryStorage()
        val store = store(storage)

        store.save(CompanionState(snapshot()), "mac-1", "env-1")
        store.flush()

        assertTrue(storage.writes.isEmpty())
    }

    // MARK: - Refusing a copy

    @Test
    fun `a load refuses another schema version and removes it`() = runTest {
        val storage = MemoryStorage()
        storage.blobs["mac-1"] = snapshot().copy(schemaVersion = StateSnapshot.SCHEMA_VERSION + 1).encoded()
        val store = store(storage)

        assertNull(store.load("mac-1", "env-1"))
        assertFalse("mac-1" in storage.blobs)
    }

    @Test
    fun `a load refuses a copy saved for another connection`() = runTest {
        val storage = MemoryStorage()
        storage.blobs["mac-1"] = snapshot(connectionId = "mac-2").encoded()
        val store = store(storage)

        assertNull(store.load("mac-1", "env-1"))
        assertFalse("mac-1" in storage.blobs)
    }

    @Test
    fun `a load refuses another server identity at the same address`() = runTest {
        val storage = MemoryStorage()
        val store = store(storage)
        store.save(snapshot(serverEnvironmentId = "env-1"))

        assertNull(store.load("mac-1", "env-2"))
        assertFalse("mac-1" in storage.blobs)

        store.save(snapshot(serverEnvironmentId = null))
        assertNull(store.load("mac-1", "env-1"))
    }

    @Test
    fun `a copy that does not decode is removed`() = runTest {
        val storage = MemoryStorage()
        storage.blobs["mac-1"] = "{not json".toByteArray()
        val store = store(storage)

        assertNull(store.load("mac-1", "env-1"))
        assertFalse("mac-1" in storage.blobs)
    }

    @Test
    fun `a copy that cannot be read yet is kept for later`() = runTest {
        val storage = MemoryStorage()
        val store = store(storage)
        store.save(snapshot())
        store.flush()

        storage.unreadable = true
        assertNull(store.load("mac-1", "env-1"))
        assertTrue("mac-1" in storage.blobs)

        storage.unreadable = false
        assertEquals(snapshot(), store.load("mac-1", "env-1"))
    }

    // MARK: - Wiping

    @Test
    fun `wipe forgets one computer and wipeAll every one`() = runTest {
        val directory = temporaryDirectory()
        val store = store(PlainFileSnapshotStorage(directory))
        store.save(snapshot(connectionId = "mac-1"))
        store.save(snapshot(connectionId = "mac-2"))
        store.flush()

        store.wipe("mac-1")

        assertNull(store.load("mac-1", "env-1"))
        assertEquals(snapshot(connectionId = "mac-2"), store.load("mac-2", "env-1"))

        store.wipeAll()

        assertNull(store.load("mac-2", "env-1"))
        assertEquals(emptyList(), directory.list()?.toList())
    }

    @Test
    fun `a wipe drops a save that was still waiting`() = runTest {
        val storage = MemoryStorage()
        val store = store(storage)

        store.save(snapshot(text = "before"))
        store.wipe("mac-1")
        store.flush()

        assertTrue(storage.writes.isEmpty())
        assertNull(store.load("mac-1", "env-1"))

        // A save after the wipe is a new sync, and it counts.
        store.save(snapshot(text = "after"))
        assertEquals(snapshot(text = "after"), store.load("mac-1", "env-1"))
    }

    @Test
    fun `a wipe still waiting when the scope ends still happens`() = runTest {
        val storage = MemoryStorage()
        storage.blobs["mac-1"] = snapshot().encoded()
        val storeScope = CoroutineScope(SupervisorJob())
        val store = SnapshotStore(storage, storeScope, StandardTestDispatcher(testScheduler))
        // The scope ends while the writer is busy with another computer's save.
        storage.beforeWrite = { storeScope.cancel() }

        store.save(snapshot(connectionId = "mac-2"))
        store.wipe("mac-1")
        store.save(snapshot(connectionId = "mac-1", text = "after"))
        advanceUntilIdle()

        assertFalse("mac-1" in storage.blobs)
        assertEquals(listOf("mac-2"), storage.writes.map { it.first })
    }

    @Test
    fun `a wipe after the scope ended still happens`() = runTest {
        val storage = MemoryStorage()
        storage.blobs["mac-1"] = snapshot().encoded()
        storage.blobs["mac-2"] = snapshot(connectionId = "mac-2").encoded()
        val storeScope = CoroutineScope(SupervisorJob())
        val store = SnapshotStore(storage, storeScope, StandardTestDispatcher(testScheduler))
        storeScope.cancel()
        advanceUntilIdle()

        store.wipe("mac-1")
        advanceUntilIdle()
        assertEquals(setOf("mac-2"), storage.blobs.keys)

        store.wipeAll()
        advanceUntilIdle()
        assertTrue(storage.blobs.isEmpty())
    }

    @Test
    fun `wipeAll drops every save that was still waiting`() = runTest {
        val storage = MemoryStorage()
        val store = store(storage)

        store.save(snapshot(connectionId = "mac-1"))
        store.save(snapshot(connectionId = "mac-2"))
        store.wipeAll()
        store.flush()

        assertTrue(storage.writes.isEmpty())
        assertTrue(storage.blobs.isEmpty())
    }

    // MARK: - Coalescing

    @Test
    fun `a burst of saves writes only the newest`() = runTest {
        val storage = MemoryStorage()
        val store = store(storage)

        (1..5).forEach { store.save(snapshot(savedAt = it.toLong())) }
        store.flush()

        assertEquals(listOf(5L), storage.writes.map { it.second.savedAt })
        assertEquals(5L, store.load("mac-1", "env-1")?.savedAt)
    }

    @Test
    fun `computers coalesce separately`() = runTest {
        val storage = MemoryStorage()
        val store = store(storage)

        store.save(snapshot(connectionId = "mac-1", savedAt = 1))
        store.save(snapshot(connectionId = "mac-2", savedAt = 2))
        store.save(snapshot(connectionId = "mac-1", savedAt = 3))
        store.flush()

        assertEquals(listOf("mac-2" to 2L, "mac-1" to 3L), storage.writes.map { it.first to it.second.savedAt })
    }

    @Test
    fun `saves behind a write in flight collapse into the newest`() {
        val storage = MemoryStorage()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var first = true
        storage.beforeWrite = {
            if (first) {
                first = false
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
        withWriterThread(storage) { store ->
            store.save(snapshot(savedAt = 1))
            assertTrue(started.await(5, TimeUnit.SECONDS))
            (2..5).forEach { store.save(snapshot(savedAt = it.toLong())) }
            release.countDown()
            runBlocking { store.flush() }

            assertEquals(listOf(1L, 5L), storage.writes.map { it.second.savedAt })
        }
    }

    // MARK: - Threads

    @Test
    fun `building, saving and loading run on the writer, never the caller`() {
        val storage = MemoryStorage()
        // Building is the expensive part — it encodes, more than once near the
        // cap — so it is the transcript reads that must happen on the writer,
        // not just the disk.
        val reads = mutableListOf<String>()
        val state = state().let { it.copy(messages = it.messages.mapValues { (_, lines) -> RecordingTranscript(lines, reads) }) }
        withWriterThread(storage) { store ->
            store.save(state, "mac-1", "env-1", savedAt = 1)
            val loaded = runBlocking { store.load("mac-1", "env-1") }

            assertNotNull(loaded)
            assertEquals(2, loaded.threads.getValue("scout-thread").messages.size)
            // Debug builds of coroutines append " @coroutine#n" to the name.
            assertEquals(2, storage.threads.size)
            assertTrue(storage.threads.all { it.startsWith(WRITER) }, storage.threads.toString())
            val buildThreads = synchronized(reads) { reads.toList() }
            assertTrue(buildThreads.isNotEmpty())
            assertTrue(buildThreads.all { it.startsWith(WRITER) }, buildThreads.toString())
            assertFalse(Thread.currentThread().name.startsWith(WRITER))
        }
    }

    @Test
    fun `a store whose scope ended answers with no copy instead of hanging`() {
        val storage = MemoryStorage()
        storage.blobs["mac-1"] = snapshot().encoded()
        val executor = Executors.newSingleThreadExecutor()
        val scope = CoroutineScope(SupervisorJob())
        val store = SnapshotStore(storage, scope, executor.asCoroutineDispatcher())
        scope.cancel()

        runBlocking {
            store.flush()
            assertNull(store.load("mac-1", "env-1"))
        }
        executor.shutdownNow()
    }

    // MARK: - Files

    @Test
    fun `file names never leave the directory`() = runTest {
        val directory = temporaryDirectory()
        val store = store(PlainFileSnapshotStorage(directory))
        val hostile = "../../escape/ü"

        assertEquals("%2E%2E%2F%2E%2E%2Fescape%2F%C3%BC", SnapshotStore.fileName(hostile))
        assertEquals("123e4567-e89b-12d3-a456-426614174000", SnapshotStore.fileName("123e4567-e89b-12d3-a456-426614174000"))

        store.save(snapshot(connectionId = hostile))
        assertEquals(snapshot(connectionId = hostile), store.load(hostile, "env-1"))
        assertEquals(listOf("${SnapshotStore.fileName(hostile)}.json"), directory.list()?.toList())
        assertFalse(File(directory.parentFile, "escape").exists())
    }

    @Test
    fun `plain file storage replaces a file whole and leaves nothing behind`() {
        val directory = File(temporaryDirectory(), "snapshots")
        val storage = PlainFileSnapshotStorage(directory)

        assertNull(storage.read("mac-1"))
        storage.write("mac-1", byteArrayOf(1, 2, 3))
        storage.write("mac-1", byteArrayOf(4, 5))

        assertContentEquals(byteArrayOf(4, 5), storage.read("mac-1"))
        assertEquals(listOf("mac-1.json"), directory.list()?.toList())

        storage.delete("mac-1")
        storage.delete("mac-1")
        assertNull(storage.read("mac-1"))

        storage.write("mac-1", byteArrayOf(1))
        storage.write("mac-2", byteArrayOf(2))
        storage.deleteAll()
        assertEquals(emptyList(), directory.list()?.toList())
    }

    /** A store on a real writer thread named [WRITER], so tests can see where work runs. */
    private fun withWriterThread(storage: SnapshotStorage, body: (SnapshotStore) -> Unit) {
        val executor = Executors.newSingleThreadExecutor { Thread(it, WRITER) }
        val dispatcher = executor.asCoroutineDispatcher()
        val scope = CoroutineScope(SupervisorJob())
        try {
            body(SnapshotStore(storage, scope, dispatcher))
        } finally {
            scope.cancel()
            dispatcher.close()
        }
    }

    private companion object {
        const val WRITER = "snapshot-writer"
    }
}
