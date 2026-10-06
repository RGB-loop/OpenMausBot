package com.openmausbot.companion.core

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * When Session reads, shows, keeps and forgets the last sync (MOCA-296), and
 * that nothing a person taps on the saved copy leaves the phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionOfflineCopyTest {
    private val mac = Connection(id = "c1", name = "Mac", host = "192.168.1.2", port = 8810)
    private val savedAt = 1_700_000_000_000L

    private fun saved(connectionId: String = "c1", serverEnvironmentId: String? = null) = StateSnapshot(
        schemaVersion = StateSnapshot.SCHEMA_VERSION,
        connectionId = connectionId,
        serverEnvironmentId = serverEnvironmentId,
        savedAt = savedAt,
        bots = listOf(StateSnapshot.CachedBot(snapshotBot("scout"))),
        threads = mapOf(
            "scout-thread" to StateSnapshot.CachedThread(
                messages = listOf(StateSnapshot.CachedMessage(snapshotLine("a", 1.0))),
                hasMore = false,
            ),
        ),
        routines = listOf(snapshotRoutine),
        routineRuns = listOf(snapshotRun("run-1", 1.0, output = "done")),
    )

    private val liveFleet = Fleet(listOf(snapshotBot("live")), emptyList())

    // MARK: - Reading

    @Test
    fun `the saved copy is read before the stream starts and shown cached`() = runTest {
        val f = fixture()
        f.storage.seed(saved())

        f.session.awaitRestored()
        f.session.connect()
        advanceUntilIdle()

        val state = f.session.state.value
        assertTrue(state.isCached)
        assertFalse(state.canAct)
        assertEquals(savedAt, state.cachedAt)
        assertEquals(listOf("scout"), state.bots.map(Bot::id))
        assertEquals(listOf("a"), state.transcript("scout-thread").map(Message::id))
        assertEquals(Session.Status.Connecting, f.session.status.value)
        assertTrue(f.order.indexOf("read") in 0 until f.order.indexOf("stream"), "${f.order}")
        // The routine screens read the saved routines too, without a request.
        val routines = f.session.loadRoutines()
        assertEquals(listOf(snapshotRoutine), routines.routines)
        assertEquals(listOf("run-1"), routines.runs.map(RoutineRun::id))
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun `a first launch with nothing saved starts empty as before`() = runTest {
        val f = fixture()

        f.session.awaitRestored()
        f.session.connect()
        advanceUntilIdle()

        assertFalse(f.session.state.value.isCached)
        assertTrue(f.session.state.value.bots.isEmpty())
    }

    @Test
    fun `a copy for another computer or server is never shown`() = runTest {
        val f = fixture()
        f.storage.seed(saved(serverEnvironmentId = "other-env"))

        f.session.awaitRestored()
        advanceUntilIdle()

        assertFalse(f.session.state.value.isCached)
    }

    @Test
    fun `a live hydrate replaces the copy and is saved at once`() = runTest {
        val f = fixture(events = { _ ->
            flow {
                delay(1_000)
                emit(StreamFrame(Frame.Hello(cursor = "s:1", resumed = false), seq = 1))
                awaitCancellation()
            }
        })
        f.storage.seed(saved())

        f.session.awaitRestored()
        f.session.connect()
        advanceTimeBy(500)
        assertTrue(f.session.state.value.isCached)

        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(f.session.state.value.isCached)
        assertTrue(f.session.state.value.canAct)
        assertEquals(listOf("live"), f.session.state.value.bots.map(Bot::id))
        assertEquals(Session.Status.Live, f.session.status.value)
        advanceUntilIdle()
        val written = f.storage.writes.single()
        assertEquals(listOf("live"), written.bots.map(StateSnapshot.CachedBot::id))
        assertTrue(written.savedAt > savedAt)
        // The connection's device token never reaches the file.
        val bytes = f.storage.blobs.getValue(SnapshotStore.fileName("c1")).decodeToString()
        assertFalse("device-token" in bytes)
        assertTrue("c1" in bytes)
    }

    @Test
    fun `a copy that finishes loading after the hydrate is dropped`() = runTest {
        val held = HeldDispatcher()
        val f = fixture(
            snapshotDispatcher = held,
            events = { _ ->
                flow {
                    emit(StreamFrame(Frame.Hello(cursor = "s:1", resumed = false), seq = 1))
                    awaitCancellation()
                }
            },
        )
        f.storage.seed(saved())

        f.session.awaitRestored()
        f.session.connect()
        advanceUntilIdle()
        assertEquals(1, held.waiting, "the copy should still be being rebuilt")
        assertEquals(listOf("live"), f.session.state.value.bots.map(Bot::id))

        held.release()
        advanceUntilIdle()

        assertFalse(f.session.state.value.isCached)
        assertEquals(listOf("live"), f.session.state.value.bots.map(Bot::id))
    }

    // MARK: - Writing

    @Test
    fun `stream batches save at most once every five seconds`() = runTest {
        val f = fixture(events = { _ ->
            flow {
                emit(StreamFrame(Frame.Hello(cursor = "s:1", resumed = false), seq = 1))
                // A frame every 100 ms for seven seconds.
                for (index in 1..70) {
                    delay(100)
                    emit(StreamFrame(Frame.Bot(snapshotBot("live").copy(activity = "step $index")), seq = index + 1))
                }
                awaitCancellation()
            }
        })

        f.session.awaitRestored()
        f.session.connect()
        runCurrent()
        assertEquals(1, f.storage.writes.size, "the hydrate is saved straight away")

        advanceTimeBy(4_900)
        runCurrent()
        assertEquals(1, f.storage.writes.size, "fifty frames, no save yet")

        advanceTimeBy(300)
        runCurrent()
        assertEquals(2, f.storage.writes.size)

        advanceUntilIdle()
        // Frames ran until 7 s: one more save at about 10 s, then nothing.
        assertEquals(3, f.storage.writes.size)
        assertEquals("step 70", f.storage.writes.last().bots.single().activity)
    }

    @Test
    fun `nothing is saved while the copy is on screen`() = runTest {
        val f = fixture(events = { _ ->
            flow {
                // A resumed hello leaves the copy in place; frames then apply to it.
                emit(StreamFrame(Frame.Hello(cursor = "s:1", resumed = true), seq = 1))
                emit(StreamFrame(Frame.Bot(snapshotBot("scout").copy(activity = "moved")), seq = 2))
                awaitCancellation()
            }
        })
        f.storage.seed(saved())

        f.session.awaitRestored()
        advanceUntilIdle()
        f.session.connect()
        advanceUntilIdle()
        assertTrue(f.session.state.value.isCached)
        assertEquals(Session.Status.Live, f.session.status.value)

        f.session.saveOfflineCopy()
        advanceUntilIdle()

        assertTrue(f.storage.writes.isEmpty())
    }

    @Test
    fun `routines loaded live ride along with the next save`() = runTest {
        val f = fixture(
            events = { _ ->
                flow {
                    emit(StreamFrame(Frame.Hello(cursor = "s:1", resumed = false), seq = 1))
                    awaitCancellation()
                }
            },
            respond = { request ->
                if (request.url.encodedPath == "/api/routines") {
                    200 to """{"routines":[],"runs":[{"id":"run-9","routineId":"r","routineName":"Brief","botId":"live","runOn":"maus","scheduledFor":9,"status":"completed","manual":true,"output":"${"o".repeat(5_000)}","createdAt":9}]}"""
                } else null
            },
        )

        f.session.awaitRestored()
        f.session.connect()
        advanceUntilIdle()
        val loaded = f.session.loadRoutines()
        assertEquals(listOf("run-9"), loaded.runs.map(RoutineRun::id))
        f.session.saveOfflineCopy()
        advanceUntilIdle()

        val run = f.storage.writes.last().routineRuns.single()
        assertEquals("run-9", run.id)
        assertEquals(4_000, run.output?.length)
    }

    // MARK: - Wiping

    @Test
    fun `forgetting the computer wipes its copy and clears the screen`() = runTest {
        val f = fixture()
        f.storage.seed(saved())
        f.session.awaitRestored()
        advanceUntilIdle()
        assertTrue(f.session.state.value.isCached)

        f.session.forgetConnection("c1")
        advanceUntilIdle()

        assertFalse(f.storage.has("c1"))
        assertFalse(f.session.state.value.isCached)
        assertTrue(f.session.state.value.bots.isEmpty())
    }

    @Test
    fun `pair again wipes the copy`() = runTest {
        val f = fixture()
        f.storage.seed(saved())
        f.session.awaitRestored()
        advanceUntilIdle()

        f.session.pairAgainAndAwait()
        advanceUntilIdle()

        assertFalse(f.storage.has("c1"))
        assertFalse(f.session.state.value.isCached)
    }

    @Test
    fun `signing out wipes every copy`() = runTest {
        val f = fixture()
        f.storage.seed(saved())
        f.storage.seed(saved(connectionId = "orphan"))
        f.session.awaitRestored()
        advanceUntilIdle()

        f.session.signOutAndAwait()
        advanceUntilIdle()

        assertTrue(f.storage.blobs.isEmpty())
        assertFalse(f.session.state.value.isCached)
    }

    @Test
    fun `a 401 wipes the copy and the cached screen`() = runTest {
        val f = fixture(events = { _ -> flow { throw APIError.Status(401) } })
        f.storage.seed(saved())
        f.session.awaitRestored()
        advanceUntilIdle()
        assertTrue(f.session.state.value.isCached)

        f.session.connect()
        advanceUntilIdle()

        assertEquals(Session.Status.Unauthorized, f.session.status.value)
        assertFalse(f.storage.has("c1"))
        assertFalse(f.session.state.value.isCached)
        assertTrue(f.session.state.value.bots.isEmpty())
    }

    @Test
    fun `a fresh pairing wipes what an earlier pairing with that computer left`() = runTest {
        val f = fixture(
            pair = { connection, _, _, _ ->
                PairingOutcome(
                    PairResponse(token = "new-token", device = PairedDevice("d1", "Pixel", 1.0, 1.0), serverName = "Mac"),
                    connection,
                )
            },
        )
        f.storage.seed(saved())
        f.session.awaitRestored()
        advanceUntilIdle()
        assertTrue(f.session.state.value.isCached)

        f.session.pair(mac.copy(id = "fresh-invite"), "123456")
        advanceUntilIdle()

        assertFalse(f.storage.has("c1"), "the re-paired computer keeps its id, and loses its old copy")
        assertFalse(f.session.state.value.isCached)
    }

    @Test
    fun `a different server at the saved address wipes the copy`() = runBlocking<Unit> {
        val requests = CopyOnWriteArrayList<Request>()
        val http = interceptingClient(requests) { 200 to """{"environmentId":"new-env","label":"mini"}""" }
        val saved = requireNotNull(Connection.parse("https://mini.example")).copy(id = "c1", serverEnvironmentId = "old-env")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = MemoryStorage().apply { seed(saved(serverEnvironmentId = "old-env")) }
            val store = SnapshotStore(storage, scope, Dispatchers.IO)
            val session = Session(
                scope = scope,
                connectionStore = RegistryStore(saved),
                tokenStore = Tokens("c1"),
                onboardingStore = InMemoryOnboardingStore(),
                deviceNameProvider = { "Pixel" },
                httpClient = http,
                eventsFn = { _, _, _ -> flow { awaitCancellation() } },
                snapshotStore = store,
            )
            session.awaitRestored()
            session.connect()
            withTimeout(5_000) { session.status.first { it == Session.Status.Unauthorized } }
            store.flush()

            assertFalse(storage.has("c1"))
            assertFalse(session.state.value.isCached)
            assertTrue(requests.all { it.header("Authorization") == null })
        } finally {
            scope.cancel()
        }
    }

    // MARK: - Nothing leaves the phone

    @Test
    fun `no write reaches the computer while the copy is on screen`() = runTest {
        val f = fixture()
        f.storage.seed(saved())
        f.session.awaitRestored()
        advanceUntilIdle()
        assertTrue(f.session.state.value.isCached)
        val session = f.session
        val bot = session.state.value.bots.single()
        val chat = Chat.BotChat(bot)
        val card = OptionCard(title = "Run it?", subtitle = "", options = listOf("Allow", "Deny"), requestId = "req-1", tool = "Bash", allowKey = "Bash")
        val task = BotTask(threadId = bot.threadId, title = "Main", createdAt = 1.0)
        val message = session.state.value.transcript(bot.threadId).single()

        session.send("hello", chat)
        assertFalse(session.send("hello", emptyList(), chat))
        session.answer(chat, card, "Allow")
        session.answer(bot.threadId, "req-1", "an answer", isPermission = false)
        session.alwaysAllow(bot, card)
        session.interrupt(bot)
        session.interrupt(chat)
        session.markRead(chat)
        session.react(message, bot.threadId, "👍")
        session.edit(message, bot, "edited")
        session.switchVersion(message, bot)
        assertNull(session.createTask(bot, "New"))
        assertFalse(session.renameTask(task, bot, "Renamed"))
        assertFalse(session.pinTask(task, chat, true))
        assertFalse(session.archiveTask(task, bot, 1.0))
        assertNull(session.deleteTask(task, bot))
        assertFalse(session.snoozeTask(task, bot, 1L))
        assertNull(session.createBot())
        assertNull(session.createRoom("Team", listOf(bot.id)))
        assertNull(session.assignSection("Work", listOf(bot.id)))
        assertNull(session.updateProfile(BotProfilePatch(name = "Renamed"), bot))
        assertNull(session.updateModel(ModelSelection("claude", "sonnet"), bot))
        assertNull(session.generateAvatar("a cat", bot))
        assertNull(session.uploadAvatar(byteArrayOf(1), "image/png", bot, AvatarCrop.CIRCLE))
        assertNull(session.switchVoiceProvider(VoiceProvider.SYSTEM))
        assertNull(session.authorizeConnector("github", null))
        assertNull(session.saveRoutine(RoutineInput("Brief", "Summarise", bot.id, schedule = snapshotRoutine.schedule), null))
        assertNull(session.setRoutineEnabled(snapshotRoutine, false))
        assertNull(session.runRoutine(snapshotRoutine))
        assertFalse(session.deleteRoutine(snapshotRoutine))
        assertFalse(session.cancelQueued(QueuedSend("q1", "held"), chat))
        assertTrue(session.updateClaude("claude") is ClaudeUpdateResult.Failed)
        assertFailsWith<APIError> { session.startLiveCall(bot.id, bot.threadId, "sdp") }
        assertFailsWith<APIError> { session.updateLiveSettings(LiveSettingsPatch(voice = "v")) }
        assertFailsWith<APIError> { session.cloudDesktop(bot) }
        assertFailsWith<IllegalStateException> { session.withShareClient("c1") { it.health() } }
        advanceUntilIdle()

        assertEquals(emptyList(), f.requests.map { "${it.method} ${it.url.encodedPath}" })
        // Still the copy: nothing pretended to happen.
        assertTrue(session.state.value.isCached)
        assertTrue(session.state.value.pendingEdits.isEmpty())
    }

    // MARK: - Fixture

    private class Fixture(
        val session: Session,
        val storage: MemoryStorage,
        val requests: List<Request>,
        val order: List<String>,
    )

    private fun TestScope.fixture(
        events: (Int) -> Flow<StreamFrame> = { flow { awaitCancellation() } },
        snapshotDispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        respond: (Request) -> Pair<Int, String>? = { null },
        pair: suspend (Connection, String, String, String) -> PairingOutcome = { _, _, _, _ -> error("pair not expected") },
    ): Fixture {
        val order = CopyOnWriteArrayList<String>()
        val requests = CopyOnWriteArrayList<Request>()
        val storage = MemoryStorage(order)
        val http = interceptingClient(requests) { request -> respond(request) ?: (404 to "{}") }
        // Not backgroundScope: advanceUntilIdle leaves background work alone,
        // and the store's writer and the session's launches are the point here.
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val store = SnapshotStore(storage, scope, StandardTestDispatcher(testScheduler))
        var streams = 0
        val session = Session(
            scope = scope,
            connectionStore = RegistryStore(mac),
            tokenStore = Tokens(mac.id),
            onboardingStore = InMemoryOnboardingStore(),
            deviceNameProvider = { "Pixel" },
            httpClient = http,
            clientFactory = { connection, token -> CompanionClient(connection, token, http) },
            pairFn = pair,
            eventsFn = { _, _, _ ->
                streams += 1
                order += "stream"
                events(streams)
            },
            hydrateFn = { _, _ -> liveFleet },
            instancesFn = { emptyList() },
            liveCallFn = { null },
            snapshotStore = store,
            snapshotDispatcher = snapshotDispatcher,
        )
        return Fixture(session, storage, requests, order)
    }

    private fun interceptingClient(
        requests: MutableList<Request>,
        respond: (Request) -> Pair<Int, String>,
    ): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        requests += request
        val (status, body) = respond(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    /** Snapshot blobs in memory; reads are noted in [order]. */
    private class MemoryStorage(private val order: MutableList<String> = mutableListOf()) : SnapshotStorage {
        val blobs = LinkedHashMap<String, ByteArray>()
        val writes = CopyOnWriteArrayList<StateSnapshot>()

        fun seed(snapshot: StateSnapshot) = synchronized(this) {
            blobs[SnapshotStore.fileName(snapshot.connectionId)] = snapshot.encoded()
        }

        fun has(connectionId: String): Boolean = synchronized(this) { SnapshotStore.fileName(connectionId) in blobs }

        override fun read(name: String): ByteArray? = synchronized(this) {
            order += "read"
            blobs[name]
        }

        override fun write(name: String, bytes: ByteArray) = synchronized(this) {
            writes += StateSnapshot.decode(bytes)
            blobs[name] = bytes
        }

        override fun delete(name: String) {
            synchronized(this) { blobs.remove(name) }
        }

        override fun deleteAll() {
            synchronized(this) { blobs.clear() }
        }
    }

    /** Holds every block it is given until [release]. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val blocks = mutableListOf<Runnable>()
        val waiting: Int get() = synchronized(blocks) { blocks.size }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            synchronized(blocks) { blocks += block }
        }

        fun release() {
            val ready = synchronized(blocks) { blocks.toList().also { blocks.clear() } }
            ready.forEach(Runnable::run)
        }
    }

    private class RegistryStore(initial: Connection) : ConnectionStore {
        private var registry = ConnectionRegistry(listOf(initial), initial.id)
        override suspend fun load(): Connection? = registry.activeConnection
        override suspend fun save(connection: Connection) {
            registry = registry.upsert(connection)
        }
        override suspend fun clear() {
            registry = ConnectionRegistry()
        }
        override suspend fun loadRegistry() = ConnectionRegistryRestore(registry, migratedLegacyConnection = false)
        override suspend fun saveRegistry(registry: ConnectionRegistry) {
            this.registry = registry.normalized()
        }
    }

    private class Tokens(vararg ids: String) : TokenStore {
        private val values = ids.associateWith { "device-token" }.toMutableMap()
        override suspend fun save(connectionId: String, token: String) {
            values[connectionId] = token
        }
        override suspend fun read(connectionId: String): TokenStore.ReadResult =
            values[connectionId]?.let { TokenStore.ReadResult.Found(it) } ?: TokenStore.ReadResult.Missing
        override suspend fun remove(connectionId: String) {
            values.remove(connectionId)
        }
    }
}
