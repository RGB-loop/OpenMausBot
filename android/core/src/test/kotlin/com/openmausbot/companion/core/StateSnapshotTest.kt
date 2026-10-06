package com.openmausbot.companion.core

import java.util.Base64
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal fun snapshotBot(
    id: String,
    threadId: String = "$id-thread",
    createdAt: Double = 1.0,
    tasks: List<BotTask>? = null,
    activeLeafId: String? = null,
    pinned: Boolean? = null,
    section: String? = null,
    unread: Boolean = false,
) = Bot(
    id = id,
    threadId = threadId,
    name = "Bot $id",
    title = "Helper",
    description = "Does things",
    notifications = true,
    color = "#336699",
    unread = unread,
    modelSelection = ModelSelection("claude", "opus"),
    createdAt = createdAt,
    pinned = pinned,
    section = section,
    tasks = tasks,
    activeLeafId = activeLeafId,
)

internal fun snapshotRoom(id: String, threadId: String = "$id-thread", createdAt: Double = 1.0) = Room(
    id = id,
    threadId = threadId,
    name = "Room $id",
    memberIds = listOf("a", "b"),
    defaultResponder = GroupResponder("auto"),
    bulletin = "",
    unread = true,
    createdAt = createdAt,
    section = "Team",
)

internal fun snapshotTask(threadId: String, updatedAt: Double, title: String = "Thread $threadId") =
    BotTask(threadId = threadId, title = title, createdAt = 1.0, unread = false, updatedAt = updatedAt)

internal fun snapshotLine(id: String, at: Double, parentId: String? = null, text: String = "line $id") = Message(
    id = id,
    role = if (at.toInt() % 2 == 0) Message.Role.USER else Message.Role.BOT,
    kind = Message.Kind.TEXT,
    at = at,
    text = text,
    parentId = parentId,
)

/** [count] messages, each the reply to the one before. */
internal fun snapshotChain(prefix: String, count: Int, startAt: Double = 1.0, text: (Int) -> String = { "line $it" }) =
    (1..count).map { index ->
        snapshotLine("$prefix-$index", startAt + index, if (index == 1) null else "$prefix-${index - 1}", text(index))
    }

internal fun snapshotRun(id: String, scheduledFor: Double, output: String? = null) = RoutineRun(
    id = id,
    routineId = "routine-1",
    routineName = "Morning brief",
    botId = "scout",
    runOn = "maus",
    scheduledFor = scheduledFor,
    status = "completed",
    manual = false,
    output = output,
    createdAt = scheduledFor,
)

internal val snapshotRoutine = Routine(
    id = "routine-1",
    name = "Morning brief",
    prompt = "Summarise the inbox.",
    botId = "scout",
    runOn = "maus",
    enabled = true,
    schedule = RoutineSchedule.daily("08:00", listOf(1, 2, 3, 4, 5)),
    durationMinutes = 30,
    createdAt = 1.0,
    updatedAt = 2.0,
)

class StateSnapshotTest {
    private val limits = StateSnapshot.Limits.STANDARD

    /**
     * A bot with one opened thread per task, thread `t<i>` last active at `i`:
     * its row's stamp, which every message predates.
     */
    private fun manyThreads(count: Int, messagesEach: Int = 1, text: (Int) -> String = { "line $it" }): CompanionState {
        val tasks = (1..count).map { snapshotTask("t$it", updatedAt = it.toDouble()) }
        val bot = snapshotBot("scout", threadId = "t$count", tasks = tasks)
        return CompanionState(
            bots = listOf(bot),
            messages = (1..count).associate { index ->
                "t$index" to snapshotChain("t$index", messagesEach, startAt = -1_000.0, text = text)
            },
            hasMore = (1..count).associate { "t$it" to false },
        )
    }

    private fun CompanionState.snapshot(
        limits: StateSnapshot.Limits = StateSnapshot.Limits.STANDARD,
        routines: List<Routine> = emptyList(),
        routineRuns: List<RoutineRun> = emptyList(),
    ): StateSnapshot = assertNotNull(
        offlineSnapshot(
            connectionId = "mac-1",
            serverEnvironmentId = "env-1",
            savedAt = 1_700_000_000_000,
            routines = routines,
            routineRuns = routineRuns,
            limits = limits,
        ),
    )

    // MARK: - Round trip

    @Test
    fun `a hydrated fleet round trips through the encoded bytes`() {
        val live = CompanionState().hydrate(decodeFixture<Fleet>("bots-paged"))
        val snapshot = live.snapshot(routines = listOf(snapshotRoutine), routineRuns = listOf(snapshotRun("run-1", 5.0)))

        val decoded = StateSnapshot.decode(snapshot.encoded())
        assertEquals(snapshot, decoded)

        val cached = CompanionState(decoded)
        assertEquals(live.bots.map { it.copy(messages = null, hasMore = null) }, cached.bots)
        assertEquals(live.rooms.map { it.copy(messages = null, hasMore = null) }, cached.rooms)
        assertEquals(live.sidebarSections.map(SidebarSection::name), cached.sidebarSections.map(SidebarSection::name))
        assertEquals(live.unreadCount, cached.unreadCount)
        assertTrue(live.hasMore.isNotEmpty())
        live.hasMore.keys.forEach { threadId ->
            assertEquals(live.visibleTranscript(threadId), cached.visibleTranscript(threadId), threadId)
            assertEquals(live.hasMore[threadId], cached.hasMore[threadId], threadId)
            assertTrue(cached.hasLoadedPage(threadId))
        }
        assertEquals(listOf(snapshotRoutine), decoded.routines)
        assertEquals(listOf(snapshotRun("run-1", 5.0)), decoded.routineRuns)
        assertEquals("env-1", decoded.serverEnvironmentId)
        assertEquals(1_700_000_000_000, decoded.savedAt)
    }

    @Test
    fun `the roster keeps sections pins unread and thread rows`() {
        val tasks = listOf(
            snapshotTask("scout-a", updatedAt = 10.0, title = "Flights").copy(unread = true, pinned = true),
            snapshotTask("scout-b", updatedAt = 20.0, title = "Hotels"),
        )
        val live = CompanionState(
            bots = listOf(
                snapshotBot("scout", threadId = "scout-a", tasks = tasks, section = "Travel", unread = true),
                snapshotBot("pinned", pinned = true),
            ),
            rooms = listOf(snapshotRoom("team")),
        )

        val cached = CompanionState(StateSnapshot.decode(live.snapshot().encoded()))

        assertEquals(listOf("Travel", "Team"), cached.sidebarSections.map(SidebarSection::name))
        assertEquals(listOf("pinned"), cached.pinnedBots.map(Bot::id))
        assertEquals(live.unreadCount, cached.unreadCount)
        assertEquals(tasks, cached.bot("scout")?.tasks)
    }

    @Test
    fun `every message field survives except the screenshot pixels`() {
        val full = Message(
            id = "m1",
            role = Message.Role.BOT,
            kind = Message.Kind.SCREEN,
            at = 42.5,
            text = "Here is the page",
            card = OptionCard(title = "Run it?", subtitle = "shell", options = listOf("Allow", "Deny"), requestId = "req-1", tool = "Bash"),
            tool = ToolActivity(name = "browser", ok = true, output = "done"),
            threadRef = ThreadRef("scout", "t2", "Flights"),
            compaction = Compaction(summary = "Earlier", tokensBefore = 1200),
            routineRun = RoutineRunCard(runId = "run-1", routineName = "Brief", status = "completed"),
            parentId = "m0",
            from = Sender("scout", "Scout", "#fff"),
            reactions = listOf(Reaction("👍", "me")),
            comm = CommChip("g1", "b2", "Echo", "#000"),
            png = Base64.getEncoder().encodeToString(ByteArray(64) { it.toByte() }),
            mime = "image/png",
            attachments = listOf(MessageImageAttachment(kind = "file", path = "/api/attachments/a.pdf", mime = "application/pdf", name = "a.pdf")),
            steered = true,
            queueId = "q1",
            turnId = "turn-1",
            turnTerminal = true,
            via = "call",
        )

        val restored = StateSnapshot.CachedMessage(full).message

        assertEquals(full.copy(png = null, hasImage = true), restored)
    }

    // MARK: - The cached state

    @Test
    fun `a rebuilt state is marked cached and carries nothing live`() {
        val live = CompanionState(
            bots = listOf(snapshotBot("scout", activeLeafId = "a-3")),
            messages = mapOf("scout-thread" to snapshotChain("a", 3)),
            hasMore = mapOf("scout-thread" to false),
            cursor = "session-1:41",
            streaming = mapOf("scout-thread" to "typing"),
            reasoning = mapOf("scout-thread" to "thinking"),
            screens = mapOf("scout" to ScreenFrame("AAAA", "image/png")),
            pendingQueued = mapOf("scout-thread" to listOf(QueuedSend("q1", "later"))),
            pendingEdits = mapOf("scout-thread" to PendingEdit("a-2", "edited")),
            notifications = listOf(NotificationFrame("approval", "scout", "Scout", "scout-thread", "Approve?", "Run")),
            liveCall = LiveCallState("call-1", "scout", "scout-thread", "ios", startedAt = 1.0, status = LiveCallStatus.LIVE),
        )

        val cached = CompanionState(live.snapshot())

        assertTrue(cached.isCached)
        assertEquals(1_700_000_000_000, cached.cachedAt)
        assertNull(cached.cursor)
        assertTrue(cached.streaming.isEmpty())
        assertTrue(cached.reasoning.isEmpty())
        assertTrue(cached.screens.isEmpty())
        assertTrue(cached.pendingQueued.isEmpty())
        assertTrue(cached.pendingEdits.isEmpty())
        assertTrue(cached.notifications.isEmpty())
        assertNull(cached.liveCall)
        assertEquals(listOf("a-1", "a-2", "a-3"), cached.visibleTranscript("scout-thread").map(Message::id))
        assertFalse(live.isCached)
    }

    @Test
    fun `a hydrate replaces the cached state wholesale`() {
        val cached = CompanionState(
            CompanionState(
                bots = listOf(snapshotBot("gone")),
                messages = mapOf("gone-thread" to snapshotChain("g", 2)),
                hasMore = mapOf("gone-thread" to false),
            ).snapshot(),
        )
        val fleet = decodeFixture<Fleet>("bots-paged")

        val live = cached.hydrate(fleet)

        assertFalse(live.isCached)
        assertNull(live.cachedAt)
        assertNull(live.bot("gone"))
        assertTrue(live.transcript("gone-thread").isEmpty())
        assertEquals(CompanionState().hydrate(fleet), live)
    }

    @Test
    fun `canAct is false while showing the cache and true after hydrate`() {
        val live = CompanionState().hydrate(decodeFixture<Fleet>("bots-paged"))
        assertTrue(live.canAct)

        val cached = CompanionState(live.snapshot())
        assertFalse(cached.canAct)

        assertTrue(cached.hydrate(decodeFixture<Fleet>("bots-paged")).canAct)
    }

    @Test
    fun `a cached state is never written back as a new snapshot`() {
        val cached = CompanionState(manyThreads(3).snapshot())

        assertNull(cached.offlineSnapshot("mac-1", "env-1", savedAt = 2_000_000_000_000))
    }

    @Test
    fun `a saved approval still reads as one`() {
        val card = OptionCard(title = "Run tests?", subtitle = "Bash", options = listOf("Allow", "Deny"), requestId = "r1", tool = "Bash")
        val ask = Message(id = "ask", role = Message.Role.BOT, kind = Message.Kind.OPTIONS, at = 3.0, card = card, parentId = "a-2")
        val live = CompanionState(
            bots = listOf(snapshotBot("scout", activeLeafId = "ask")),
            messages = mapOf("scout-thread" to snapshotChain("a", 2) + ask),
            hasMore = mapOf("scout-thread" to false),
        )

        val cached = CompanionState(StateSnapshot.decode(live.snapshot().encoded()))

        assertEquals(listOf("ask"), cached.pendingApprovals.map { it.message.id })
    }

    // MARK: - Bounds

    @Test
    fun `a thread keeps the newest fifty messages of its active branch`() {
        val main = snapshotChain("m", 80)
        // An abandoned fork off m-70: it is not on the branch the phone shows.
        val fork = listOf(
            snapshotLine("fork-1", 100.0, parentId = "m-70"),
            snapshotLine("fork-2", 101.0, parentId = "fork-1"),
        )
        val live = CompanionState(
            bots = listOf(snapshotBot("scout", activeLeafId = "m-80")),
            messages = mapOf("scout-thread" to main + fork),
            hasMore = mapOf("scout-thread" to false),
            activeLeafIds = mapOf("scout-thread" to "m-80"),
        )

        val thread = assertNotNull(live.snapshot().threads["scout-thread"])

        assertEquals((31..80).map { "m-$it" }, thread.messages.map(StateSnapshot.CachedMessage::id))
        assertTrue(thread.hasMore)
        assertEquals("m-80", thread.activeLeafId)
        val cached = CompanionState(live.snapshot())
        assertEquals(live.visibleTranscript("scout-thread").takeLast(50), cached.visibleTranscript("scout-thread"))
    }

    @Test
    fun `a short thread keeps the computer's own hasMore`() {
        val live = CompanionState(
            bots = listOf(snapshotBot("a"), snapshotBot("b")),
            messages = mapOf("a-thread" to snapshotChain("a", 10), "b-thread" to snapshotChain("b", 10)),
            hasMore = mapOf("a-thread" to true, "b-thread" to false),
        )

        val threads = live.snapshot().threads

        assertEquals(true, threads["a-thread"]?.hasMore)
        assertEquals(false, threads["b-thread"]?.hasMore)
    }

    @Test
    fun `an edit in flight is not saved`() {
        val live = CompanionState(
            bots = listOf(snapshotBot("scout", activeLeafId = "a-4")),
            messages = mapOf("scout-thread" to snapshotChain("a", 4)),
            hasMore = mapOf("scout-thread" to false),
            pendingEdits = mapOf("scout-thread" to PendingEdit("a-2", "the edit")),
        )

        val saved = live.snapshot().threads.getValue("scout-thread").messages

        assertEquals(listOf("a-1", "a-2", "a-3", "a-4"), saved.map(StateSnapshot.CachedMessage::id))
        assertFalse(saved.any { it.text == "the edit" })
    }

    @Test
    fun `only the hundred most recently active opened threads are kept`() {
        val live = manyThreads(130)

        val snapshot = live.snapshot()

        assertEquals((31..130).map { "t$it" }.toSet(), snapshot.threads.keys)
        assertEquals((130 downTo 31).map { "t$it" }, snapshot.threads.keys.toList())
        // The thread list itself is the roster: every row stays.
        assertEquals(130, snapshot.bots.single().tasks?.size)
    }

    @Test
    fun `activity is the newer of the list stamp and the newest message`() {
        val bot = snapshotBot(
            "scout",
            threadId = "old-messages",
            tasks = listOf(snapshotTask("old-messages", updatedAt = 500.0), snapshotTask("new-messages", updatedAt = 10.0)),
        )
        val live = CompanionState(
            bots = listOf(bot),
            messages = mapOf(
                "old-messages" to snapshotChain("o", 2, startAt = 0.0),
                "new-messages" to snapshotChain("n", 2, startAt = 300.0),
            ),
            hasMore = mapOf("old-messages" to false, "new-messages" to false),
        )
        val one = limits.copy(threads = 1)

        assertEquals(setOf("old-messages"), live.snapshot(one).threads.keys)

        val newer = live.copy(messages = live.messages + ("new-messages" to snapshotChain("n", 2, startAt = 900.0)))
        assertEquals(setOf("new-messages"), newer.snapshot(one).threads.keys)
    }

    @Test
    fun `only threads with a fetched page on the roster are kept`() {
        val live = CompanionState(
            bots = listOf(snapshotBot("scout", tasks = listOf(snapshotTask("scout-thread", 1.0), snapshotTask("tail-only", 2.0)))),
            messages = mapOf(
                "scout-thread" to snapshotChain("a", 2),
                // A live tail, no page: showing it would hide everything above it.
                "tail-only" to snapshotChain("t", 2),
                "orphan" to snapshotChain("o", 2),
            ),
            hasMore = mapOf("scout-thread" to false, "orphan" to false),
        )

        assertEquals(setOf("scout-thread"), live.snapshot().threads.keys)
    }

    @Test
    fun `the byte cap drops the least recently active threads first`() {
        val live = manyThreads(20) { "x".repeat(20_000) }
        val capped = limits.copy(maxBytes = 200_000)

        val snapshot = live.snapshot(capped)

        val kept = snapshot.threads.keys.toList()
        assertTrue(kept.isNotEmpty())
        assertTrue(kept.size < 20)
        // A prefix of the activity order: everything newer than any kept thread is kept.
        assertEquals((20 downTo 21 - kept.size).map { "t$it" }, kept)
        assertTrue(snapshot.encoded().size <= capped.maxBytes)
        // And it is the most that fits: one more thread would not.
        val next = "t${20 - kept.size}"
        val oneMore = snapshot.copy(threads = snapshot.threads + (next to live.snapshot().threads.getValue(next)))
        assertTrue(oneMore.encoded().size > capped.maxBytes)
        assertEquals(live.bots.single().copy(messages = null, hasMore = null), snapshot.bots.single().bot)
    }

    @Test
    fun `the standard cap keeps a full snapshot near five megabytes`() {
        // 100 threads of 50 messages of ~1.5 KB: well over the cap.
        val live = manyThreads(100, messagesEach = 50) { "y".repeat(1_500) }

        val snapshot = live.snapshot()
        val size = snapshot.encoded().size

        assertTrue(size <= 5 * 1_024 * 1_024, "encoded $size bytes")
        assertTrue(size > 5 * 1_024 * 1_024 - 100_000, "encoded only $size bytes")
        assertEquals((100 downTo 101 - snapshot.threads.size).map { "t$it" }, snapshot.threads.keys.toList())
        snapshot.threads.values.forEach { assertEquals(50, it.messages.size) }
    }

    @Test
    fun `routine runs keep the hundred most recent in the order they arrived`() {
        // Newest first, the way the computer sends them, with one late arrival.
        val runs = (150 downTo 1).map { snapshotRun("run-$it", it.toDouble()) } + snapshotRun("late", 149.5)

        val kept = CompanionState().snapshot(routineRuns = runs).routineRuns

        assertEquals(100, kept.size)
        val expected = runs.sortedByDescending(RoutineRun::scheduledFor).take(100).toSet()
        assertEquals(expected, kept.toSet())
        assertEquals(runs.filter { it in expected }, kept)
    }

    @Test
    fun `a roster too big for the cap sheds old runs then the quietest thread rows but never a bot`() {
        val tasks = (1..300).map { snapshotTask("row-$it", updatedAt = it.toDouble(), title = "t".repeat(1_000)) }
        val bot = snapshotBot("scout", threadId = "row-300", tasks = tasks)
        val runs = (1..50).map { snapshotRun("run-$it", it.toDouble(), output = "o".repeat(1_000)) }
        val live = CompanionState(
            bots = listOf(bot, snapshotBot("echo")),
            messages = mapOf("row-300" to snapshotChain("a", 2)),
            hasMore = mapOf("row-300" to false),
        )
        val capped = limits.copy(maxBytes = 120_000)

        val snapshot = live.snapshot(capped, routineRuns = runs)

        assertTrue(snapshot.encoded().size <= capped.maxBytes, "encoded ${snapshot.encoded().size}")
        assertTrue(snapshot.routineRuns.isEmpty())
        val rows = snapshot.bots.first().tasks.orEmpty().map(StateSnapshot.CachedTask::threadId)
        assertTrue(rows.size in 1 until 300)
        // The quietest rows went; the bot's own thread stayed.
        assertEquals((301 - rows.size..300).map { "row-$it" }, rows)
        assertEquals(listOf("scout", "echo"), snapshot.bots.map(StateSnapshot.CachedBot::id))
    }

    // MARK: - Exclusions

    @Test
    fun `nothing secret heavy or live reaches the encoded bytes`() {
        // The stream cursor resumes this phone's session; treat it as a credential.
        val token = "omb_dev_9f8e7d6c5b4a39281706f5e4d3c2b1a0"
        val pixels = Base64.getEncoder().encodeToString("SCREEN-PIXELS-".repeat(40).toByteArray())
        val recordPixels = Base64.getEncoder().encodeToString("RECORD-PIXELS-".repeat(40).toByteArray())
        val attachmentBytes = Base64.getEncoder().encodeToString("ATTACHED-FILE-BYTES".repeat(20).toByteArray())
        // A hydrate leaves each record's own messages on it; a screenshot there
        // must not ride the roster onto disk either.
        fun recordShot(id: String) = Message(id = id, role = Message.Role.BOT, kind = Message.Kind.SCREEN, at = 1.0, png = recordPixels)
        val screen = Message(
            id = "shot",
            role = Message.Role.BOT,
            kind = Message.Kind.SCREEN,
            at = 3.0,
            png = pixels,
            mime = "image/png",
            parentId = "a-2",
        )
        val file = Message(
            id = "file",
            role = Message.Role.BOT,
            kind = Message.Kind.TEXT,
            at = 4.0,
            text = "Here is the report",
            parentId = "shot",
            attachments = listOf(
                MessageImageAttachment(kind = "file", path = "data:application/pdf;base64,$attachmentBytes", mime = "application/pdf", name = "report.pdf"),
            ),
        )
        val live = CompanionState(
            bots = listOf(snapshotBot("scout", activeLeafId = "file").copy(messages = listOf(recordShot("bot-shot")), hasMore = false)),
            rooms = listOf(snapshotRoom("team").copy(messages = listOf(recordShot("room-shot")), hasMore = true)),
            messages = mapOf("scout-thread" to snapshotChain("a", 2) + screen + file),
            hasMore = mapOf("scout-thread" to false),
            cursor = "$token:41",
            streaming = mapOf("scout-thread" to "streaming $token"),
            reasoning = mapOf("scout-thread" to "reasoning $token"),
            screens = mapOf("scout" to ScreenFrame(pixels, "image/png")),
            pendingQueued = mapOf("scout-thread" to listOf(QueuedSend("queue-$token", "held $token"))),
            pendingEdits = mapOf("scout-thread" to PendingEdit("a-2", "edit $token")),
            notifications = listOf(NotificationFrame("approval", "scout", "Scout", "scout-thread", "Approve", "body $token")),
            liveCall = LiveCallState("call-$token", "scout", "scout-thread", "ios", voice = "voice-$token", startedAt = 1.0, status = LiveCallStatus.LIVE),
        )

        val snapshot = assertNotNull(live.offlineSnapshot("mac-1", "env-1", savedAt = 5))
        val text = snapshot.encoded().decodeToString()

        listOf(token, pixels, recordPixels, attachmentBytes, "data:application/pdf", "bot-shot", "room-shot").forEach {
            assertFalse(it in text, "found $it")
        }
        val keys = Json.parseToJsonElement(text).jsonObject.keys
        listOf("cursor", "streaming", "reasoning", "screens", "pendingQueued", "pendingEdits", "notifications", "liveCall")
            .forEach { assertFalse(it in keys, "found key $it") }
        // What is kept is still there: the ids, the words, and enough of the
        // screenshot and the file for their rows to render.
        assertTrue("mac-1" in text && "env-1" in text && "Here is the report" in text)
        val saved = snapshot.threads.getValue("scout-thread").messages.associateBy(StateSnapshot.CachedMessage::id)
        assertEquals(true, saved.getValue("shot").hasImage)
        assertEquals("image/png", saved.getValue("shot").mime)
        val attachment = saved.getValue("file").attachments.orEmpty().single()
        assertEquals(MessageImageAttachment(kind = "file", path = null, mime = "application/pdf", name = "report.pdf"), attachment)
    }

    @Test
    fun `every roster field survives except the record's own transcript`() {
        val task = BotTask(
            threadId = "t1",
            title = "Flights",
            createdAt = 1.0,
            modelSelection = ModelSelection("claude", "opus", effort = "high"),
            activity = "Searching",
            busy = true,
            waitingOnTeammate = true,
            unread = true,
            approvalMode = "custom",
            autoApprove = false,
            alwaysAllow = listOf("Bash"),
            projectId = "p1",
            openedBy = ThreadOpener("echo", "Echo", "d1", 2.0),
            closedBy = ThreadCloser("echo", "Echo", 3.0),
            archivedAt = 0.0,
            routineRunId = "run-1",
            pinned = true,
            updatedAt = 4.0,
            snoozedUntil = 0.0,
        )
        val bot = snapshotBot("scout", tasks = listOf(task), activeLeafId = "a-2", pinned = true, section = "Travel").copy(
            avatarUrl = "/api/attachments/scout.png",
            avatarCrop = AvatarCrop.CIRCLE,
            busy = true,
            activity = "Working",
            waitingOnTeammate = false,
            hidden = false,
            chiefOfStaff = true,
            approvalMode = "ask",
            autoApprove = false,
            alwaysAllow = listOf("Read"),
            computer = "maus",
            cloudBackend = "e2b",
            speakReplies = true,
            voice = "ash",
            mascotExpression = "happy",
            mascotBody = "cursor",
            projects = listOf(BotProject("p1", "Trips", "🧳")),
            messages = snapshotChain("a", 2),
            hasMore = true,
        )
        val room = snapshotRoom("team").copy(
            dm = false,
            busyBotId = "scout",
            working = true,
            tasks = listOf(task),
            messages = snapshotChain("r", 2),
            hasMore = false,
        )

        assertEquals(bot.copy(messages = null, hasMore = null), StateSnapshot.CachedBot(bot).bot)
        assertEquals(room.copy(messages = null, hasMore = null), StateSnapshot.CachedRoom(room).room)
        assertEquals(task, StateSnapshot.CachedTask(task).task)
    }

    @Test
    fun `a field the wire gains is kept or left out on purpose`() {
        // Each cached type lists every field of its wire type but these. A new
        // wire field fails here until it is added to the cached type or, if
        // it is heavy, secret or live, to this list.
        val leftOut = mapOf(
            "Message" to setOf("png"),
            "Bot" to setOf("messages", "hasMore"),
            "Room" to setOf("messages", "hasMore"),
            "BotTask" to emptySet(),
        )
        val pairs = listOf(
            Triple("Message", Message.serializer().descriptor, StateSnapshot.CachedMessage.serializer().descriptor),
            Triple("Bot", Bot.serializer().descriptor, StateSnapshot.CachedBot.serializer().descriptor),
            Triple("Room", Room.serializer().descriptor, StateSnapshot.CachedRoom.serializer().descriptor),
            Triple("BotTask", BotTask.serializer().descriptor, StateSnapshot.CachedTask.serializer().descriptor),
        )

        pairs.forEach { (name, wire, cached) ->
            val wireFields = (0 until wire.elementsCount).map(wire::getElementName).toSet()
            val cachedFields = (0 until cached.elementsCount).map(cached::getElementName).toSet()
            assertEquals(wireFields - leftOut.getValue(name), cachedFields, name)
        }
    }

    @Test
    fun `an attachment that is a server path keeps it`() {
        val attachment = MessageImageAttachment(kind = "image", path = "/api/attachments/photo.png", mime = "image/png")
        val message = snapshotLine("a", 1.0).copy(attachments = listOf(attachment))

        assertEquals(listOf(attachment), StateSnapshot.CachedMessage(message).attachments)
    }

    @Test
    fun `a saved run keeps at most four thousand characters of output and error`() {
        val long = snapshotRun("long", 2.0, output = "o".repeat(4_500)).copy(error = "e".repeat(4_001))
        val short = snapshotRun("short", 1.0, output = "fine")
        // A surrogate pair straddling the cut is not split in half.
        val emoji = snapshotRun("emoji", 3.0, output = "a".repeat(3_999) + "\uD83D\uDE00" + "tail")

        val kept = CompanionState().snapshot(routineRuns = listOf(long, short, emoji)).routineRuns.associateBy(RoutineRun::id)

        assertEquals(4_000, limits.routineRunOutputChars)
        assertEquals("o".repeat(4_000), kept.getValue("long").output)
        assertEquals("e".repeat(4_000), kept.getValue("long").error)
        assertEquals(short, kept.getValue("short"))
        assertEquals("a".repeat(3_999), kept.getValue("emoji").output)
        // Everything else about the run is untouched.
        assertEquals(long.copy(output = null, error = null), kept.getValue("long").copy(output = null, error = null))
    }

    @Test
    fun `every type the snapshot writes lists its fields on purpose`() {
        // Every class reachable from the file, by serial name, with the fields
        // it may write. A field added to any of them, or a new type reachable
        // from them, fails here until someone decides it belongs on disk.
        val cached = mapOf(
            "com.openmausbot.companion.core.StateSnapshot" to setOf(
                "schemaVersion", "connectionId", "serverEnvironmentId", "savedAt", "bots", "rooms", "threads", "routines", "routineRuns",
            ),
            "com.openmausbot.companion.core.StateSnapshot.CachedThread" to setOf("messages", "hasMore", "activeLeafId"),
            // Pinned field for field against their wire types in the test above.
            "com.openmausbot.companion.core.StateSnapshot.CachedBot" to StateSnapshot.CachedBot.serializer().descriptor.names(),
            "com.openmausbot.companion.core.StateSnapshot.CachedRoom" to StateSnapshot.CachedRoom.serializer().descriptor.names(),
            "com.openmausbot.companion.core.StateSnapshot.CachedTask" to StateSnapshot.CachedTask.serializer().descriptor.names(),
            "com.openmausbot.companion.core.StateSnapshot.CachedMessage" to StateSnapshot.CachedMessage.serializer().descriptor.names(),
        )
        val walked = reachableClasses(StateSnapshot.serializer().descriptor)

        cached.forEach { (name, fields) -> assertEquals(fields, walked[name], name) }
        val nested = walked - cached.keys
        assertEquals(
            NESTED_FIELDS.keys.sorted(),
            nested.keys.sorted(),
            "the types the snapshot writes changed: " + nested.entries.joinToString("; ") { "${it.key}=${it.value.sorted()}" },
        )
        NESTED_FIELDS.forEach { (name, fields) -> assertEquals(fields, nested[name], name) }
    }

    private fun SerialDescriptor.names(): Set<String> = (0 until elementsCount).map(::getElementName).toSet()

    /** Every class-shaped descriptor under [root], by serial name. Lists, maps and nullables are looked through. */
    private fun reachableClasses(root: SerialDescriptor): Map<String, Set<String>> {
        val found = LinkedHashMap<String, Set<String>>()
        fun visit(descriptor: SerialDescriptor) {
            when (descriptor.kind) {
                StructureKind.CLASS, StructureKind.OBJECT, is PolymorphicKind -> {
                    val name = descriptor.serialName.removeSuffix("?")
                    if (name in found) return
                    found[name] = descriptor.names()
                    (0 until descriptor.elementsCount).forEach { visit(descriptor.getElementDescriptor(it)) }
                }
                StructureKind.LIST, StructureKind.MAP ->
                    (0 until descriptor.elementsCount).forEach { visit(descriptor.getElementDescriptor(it)) }
                else -> Unit
            }
        }
        visit(root)
        return found
    }

    private companion object {
        /**
         * Reviewed for MOCA-296: nothing here is a credential, a byte payload
         * or live state. Attachment paths are server paths (inline `data:`
         * ones are dropped when a message is cached); a tool's `output` and a
         * run's `output` are transcript text, the latter capped per run.
         */
        val NESTED_FIELDS: Map<String, Set<String>> = mapOf(
            "com.openmausbot.companion.core.ModelSelection" to setOf("effort", "instanceId", "model"),
            "com.openmausbot.companion.core.ThreadOpener" to setOf("at", "botId", "delegationId", "name"),
            "com.openmausbot.companion.core.ThreadCloser" to setOf("at", "botId", "name"),
            "com.openmausbot.companion.core.BotProject" to setOf("emoji", "id", "name"),
            "com.openmausbot.companion.core.GroupResponder" to setOf("botId", "kind"),
            "com.openmausbot.companion.core.OptionCard" to setOf("allowKey", "answered", "answeredText", "dismissed", "expired", "held", "heldCode", "options", "outboundRequest", "questionRequest", "requestId", "requestType", "skillRequest", "subtitle", "teamMemoryRequest", "title", "tool"),
            "com.openmausbot.companion.core.SkillRequestCardData" to setOf("action", "botId", "createdAt", "gist", "name", "preview", "requestId", "sha256", "source", "stagedId", "threadId", "version", "warnings"),
            "com.openmausbot.companion.core.QuestionRequestCardData" to setOf("origin", "questions", "version"),
            "com.openmausbot.companion.core.AskQuestion" to setOf("header", "multiSelect", "options", "question"),
            "com.openmausbot.companion.core.AskQuestionOption" to setOf("description", "label"),
            "com.openmausbot.companion.core.OutboundRequest" to setOf("app", "calls", "tool"),
            "com.openmausbot.companion.core.OutboundCall" to setOf("app", "label"),
            "com.openmausbot.companion.core.TeamMemoryRequest" to setOf("entryId", "kind", "section"),
            "com.openmausbot.companion.core.ToolActivity" to setOf("claudeUpdate", "name", "ok", "output", "setup", "spoken"),
            "com.openmausbot.companion.core.ThreadRef" to setOf("botId", "threadId", "title"),
            "com.openmausbot.companion.core.Compaction" to setOf("summary", "tokensBefore"),
            "com.openmausbot.companion.core.RoutineRunCard" to setOf("deferredAt", "error", "executionThreadId", "goalStatus", "routineId", "routineName", "runId", "scheduledFor", "status", "summary"),
            "com.openmausbot.companion.core.Sender" to setOf("botId", "color", "name"),
            "com.openmausbot.companion.core.Reaction" to setOf("by", "emoji"),
            "com.openmausbot.companion.core.CommChip" to setOf("groupId", "withBotId", "withColor", "withName"),
            "com.openmausbot.companion.core.MessageImageAttachment" to setOf("durationMs", "kind", "mime", "name", "path"),
            "com.openmausbot.companion.core.Routine" to setOf("botId", "createdAt", "durationMinutes", "enabled", "id", "name", "nextRunAt", "prompt", "runOn", "schedule", "timeoutMinutes", "updatedAt"),
            "com.openmausbot.companion.core.RoutineSchedule" to setOf("anchorAt", "at", "everyMinutes", "time", "type", "weekdays"),
            "com.openmausbot.companion.core.RoutineRun" to setOf("botId", "createdAt", "durationMinutes", "error", "finishedAt", "id", "manual", "output", "prompt", "routineId", "routineName", "runOn", "scheduledFor", "seenAt", "startedAt", "status", "threadId", "timeoutMinutes", "triggerSource"),
        )
    }
}
