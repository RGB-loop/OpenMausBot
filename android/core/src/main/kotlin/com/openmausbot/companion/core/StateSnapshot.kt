package com.openmausbot.companion.core

import kotlinx.serialization.Serializable

/**
 * The last sync, kept on the phone so it can be shown offline (MOCA-296).
 *
 * [CompanionState] lives only in memory, so a cold launch without the
 * computer was an empty app, and every launch was empty until hydrate landed.
 * This is the data half of keeping a copy: what is kept, how it is bounded,
 * and how a display-only state is rebuilt from it. Writing and reading the
 * file is [SnapshotStore]; when to do either is Session's call. The same
 * shape as `StateSnapshot` on iOS.
 *
 * Kept, per paired computer: the roster (bots, rooms, sections, pins, the
 * thread list with titles, unread flags and activity times), the latest page
 * of the active branch of each thread this phone opened, and routines with
 * their recent runs. Never kept: credentials of any kind, screenshot or
 * attachment bytes, Live call state, anything mid-flight (live text, edits,
 * held sends), and the stream cursor — a cached view is never resumed, only
 * replaced by the next hydrate.
 */
@Serializable
data class StateSnapshot(
    /**
     * Bumped whenever the encoded shape changes meaning. A file written under
     * any other version is refused on load rather than migrated: it is a
     * cache, and the next hydrate writes a fresh one. No default on purpose,
     * so an encoder that leaves defaults out still writes it.
     */
    val schemaVersion: Int,
    /** The saved connection this copy belongs to. A load for another id is refused, whatever the file is called. */
    val connectionId: String,
    /**
     * The server's identity when the connection pairs with one directly, null
     * for a desktop sidecar. A different identity at the same address is a
     * different computer, so its copy is never shown.
     */
    val serverEnvironmentId: String? = null,
    /** Epoch milliseconds: the "last updated" the offline banner shows. */
    val savedAt: Long,
    /** The roster, as rows that have no room for a transcript. */
    val bots: List<CachedBot> = emptyList(),
    val rooms: List<CachedRoom> = emptyList(),
    /** The latest page of each opened thread, by thread id, most recently active first. */
    val threads: Map<String, CachedThread> = emptyMap(),
    val routines: List<Routine> = emptyList(),
    val routineRuns: List<RoutineRun> = emptyList(),
) {
    /**
     * The bounds a snapshot is built under. Injectable so tests can reach them
     * with small fixtures; the app always uses [STANDARD].
     */
    data class Limits(
        /** The page the app already loads when a thread opens. */
        val messagesPerThread: Int,
        val threads: Int,
        val routineRuns: Int,
        /** The encoded file, all of it. */
        val maxBytes: Int,
    ) {
        companion object {
            val STANDARD = Limits(
                messagesPerThread = 50,
                threads = 100,
                routineRuns = 100,
                maxBytes = 5 * 1_024 * 1_024,
            )
        }
    }

    /**
     * A bot as the roster keeps it: [Bot] field for field, less the record's
     * own transcript. A hydrate leaves a page of messages on each record,
     * screenshots and all; the only copy of a transcript the cache keeps is
     * the bounded page in [threads]. Spelled out rather than reusing the wire
     * type for the same reason as [CachedMessage]: a heavy field the wire
     * gains later has to be added here on purpose, so no snapshot, however it
     * is made, can carry one to disk.
     */
    @Serializable
    data class CachedBot(
        val id: String,
        val threadId: String,
        val name: String,
        val title: String,
        val description: String,
        val notifications: Boolean,
        val color: String,
        val unread: Boolean,
        val modelSelection: ModelSelection,
        val createdAt: Double,
        val avatarUrl: String? = null,
        val avatarCrop: AvatarCrop? = null,
        val busy: Boolean? = null,
        val activity: String? = null,
        val waitingOnTeammate: Boolean? = null,
        val pinned: Boolean? = null,
        val hidden: Boolean? = null,
        val section: String? = null,
        val chiefOfStaff: Boolean? = null,
        val approvalMode: String? = null,
        val autoApprove: Boolean? = null,
        val alwaysAllow: List<String>? = null,
        val computer: String? = null,
        val cloudBackend: String? = null,
        val speakReplies: Boolean? = null,
        val voice: String? = null,
        val mascotExpression: String? = null,
        val mascotBody: String? = null,
        val tasks: List<CachedTask>? = null,
        val activeLeafId: String? = null,
        val projects: List<BotProject>? = null,
    ) {
        constructor(bot: Bot) : this(
            id = bot.id,
            threadId = bot.threadId,
            name = bot.name,
            title = bot.title,
            description = bot.description,
            notifications = bot.notifications,
            color = bot.color,
            unread = bot.unread,
            modelSelection = bot.modelSelection,
            createdAt = bot.createdAt,
            avatarUrl = bot.avatarUrl,
            avatarCrop = bot.avatarCrop,
            busy = bot.busy,
            activity = bot.activity,
            waitingOnTeammate = bot.waitingOnTeammate,
            pinned = bot.pinned,
            hidden = bot.hidden,
            section = bot.section,
            chiefOfStaff = bot.chiefOfStaff,
            approvalMode = bot.approvalMode,
            autoApprove = bot.autoApprove,
            alwaysAllow = bot.alwaysAllow,
            computer = bot.computer,
            cloudBackend = bot.cloudBackend,
            speakReplies = bot.speakReplies,
            voice = bot.voice,
            mascotExpression = bot.mascotExpression,
            mascotBody = bot.mascotBody,
            tasks = bot.tasks?.map(::CachedTask),
            activeLeafId = bot.activeLeafId,
            projects = bot.projects,
        )

        /** The record as a hydrate would leave it, with no transcript on it. */
        val bot: Bot
            get() = Bot(
                id = id,
                threadId = threadId,
                name = name,
                title = title,
                description = description,
                notifications = notifications,
                color = color,
                unread = unread,
                modelSelection = modelSelection,
                createdAt = createdAt,
                avatarUrl = avatarUrl,
                avatarCrop = avatarCrop,
                busy = busy,
                activity = activity,
                waitingOnTeammate = waitingOnTeammate,
                pinned = pinned,
                hidden = hidden,
                section = section,
                chiefOfStaff = chiefOfStaff,
                approvalMode = approvalMode,
                autoApprove = autoApprove,
                alwaysAllow = alwaysAllow,
                computer = computer,
                cloudBackend = cloudBackend,
                speakReplies = speakReplies,
                voice = voice,
                mascotExpression = mascotExpression,
                mascotBody = mascotBody,
                tasks = tasks?.map(CachedTask::task),
                activeLeafId = activeLeafId,
                projects = projects,
            )
    }

    /** A channel or DM as the roster keeps it: [Room] field for field, less its transcript (see [CachedBot]). */
    @Serializable
    data class CachedRoom(
        val id: String,
        val threadId: String,
        val name: String,
        val memberIds: List<String>,
        val defaultResponder: GroupResponder,
        val bulletin: String,
        val unread: Boolean,
        val createdAt: Double,
        val dm: Boolean? = null,
        val section: String? = null,
        val busyBotId: String? = null,
        val working: Boolean? = null,
        val tasks: List<CachedTask>? = null,
    ) {
        constructor(room: Room) : this(
            id = room.id,
            threadId = room.threadId,
            name = room.name,
            memberIds = room.memberIds,
            defaultResponder = room.defaultResponder,
            bulletin = room.bulletin,
            unread = room.unread,
            createdAt = room.createdAt,
            dm = room.dm,
            section = room.section,
            busyBotId = room.busyBotId,
            working = room.working,
            tasks = room.tasks?.map(::CachedTask),
        )

        val room: Room
            get() = Room(
                id = id,
                threadId = threadId,
                name = name,
                memberIds = memberIds,
                defaultResponder = defaultResponder,
                bulletin = bulletin,
                unread = unread,
                createdAt = createdAt,
                dm = dm,
                section = section,
                busyBotId = busyBotId,
                working = working,
                tasks = tasks?.map(CachedTask::task),
            )
    }

    /** A row of a thread list: [BotTask] field for field, spelled out so a field it gains later is kept only on purpose. */
    @Serializable
    data class CachedTask(
        val threadId: String,
        val title: String,
        val createdAt: Double,
        val modelSelection: ModelSelection? = null,
        val activity: String? = null,
        val busy: Boolean? = null,
        val waitingOnTeammate: Boolean? = null,
        val unread: Boolean? = null,
        val approvalMode: String? = null,
        val autoApprove: Boolean? = null,
        val alwaysAllow: List<String>? = null,
        val projectId: String? = null,
        val openedBy: ThreadOpener? = null,
        val closedBy: ThreadCloser? = null,
        val archivedAt: Double? = null,
        val routineRunId: String? = null,
        val pinned: Boolean? = null,
        val updatedAt: Double? = null,
        val snoozedUntil: Double? = null,
    ) {
        constructor(task: BotTask) : this(
            threadId = task.threadId,
            title = task.title,
            createdAt = task.createdAt,
            modelSelection = task.modelSelection,
            activity = task.activity,
            busy = task.busy,
            waitingOnTeammate = task.waitingOnTeammate,
            unread = task.unread,
            approvalMode = task.approvalMode,
            autoApprove = task.autoApprove,
            alwaysAllow = task.alwaysAllow,
            projectId = task.projectId,
            openedBy = task.openedBy,
            closedBy = task.closedBy,
            archivedAt = task.archivedAt,
            routineRunId = task.routineRunId,
            pinned = task.pinned,
            updatedAt = task.updatedAt,
            snoozedUntil = task.snoozedUntil,
        )

        val task: BotTask
            get() = BotTask(
                threadId = threadId,
                title = title,
                createdAt = createdAt,
                modelSelection = modelSelection,
                activity = activity,
                busy = busy,
                waitingOnTeammate = waitingOnTeammate,
                unread = unread,
                approvalMode = approvalMode,
                autoApprove = autoApprove,
                alwaysAllow = alwaysAllow,
                projectId = projectId,
                openedBy = openedBy,
                closedBy = closedBy,
                archivedAt = archivedAt,
                routineRunId = routineRunId,
                pinned = pinned,
                updatedAt = updatedAt,
                snoozedUntil = snoozedUntil,
            )

        /** [BotTask.listStamp]: the time the thread list sorts by. */
        internal val listStamp: Double
            get() = updatedAt ?: createdAt
    }

    /** One opened thread as it last looked: its active branch, newest last. */
    @Serializable
    data class CachedThread(
        val messages: List<CachedMessage> = emptyList(),
        /** More transcript above this page — on the computer, or trimmed here to stay inside the bounds. */
        val hasMore: Boolean,
        val activeLeafId: String? = null,
    )

    /**
     * A transcript line as the cache keeps it: [Message] field for field, less
     * the inline screenshot. Spelled out rather than reusing the wire type, so
     * a heavy field the wire gains later does not quietly start landing on
     * disk; it has to be added here, and the version bumped.
     */
    @Serializable
    data class CachedMessage(
        val id: String,
        val role: Message.Role,
        val kind: Message.Kind,
        val at: Double,
        val text: String? = null,
        /** Kept so a saved ask still reads as one. It is never answered from the cache: it may have expired or been answered elsewhere. */
        val card: OptionCard? = null,
        val tool: ToolActivity? = null,
        val threadRef: ThreadRef? = null,
        val compaction: Compaction? = null,
        val routineRun: RoutineRunCard? = null,
        val parentId: String? = null,
        val from: Sender? = null,
        val reactions: List<Reaction>? = null,
        val comm: CommChip? = null,
        val hasImage: Boolean? = null,
        val mime: String? = null,
        /** Kinds, names, MIME types and server paths, so file cards still render. The bytes stay on the computer. */
        val attachments: List<MessageImageAttachment>? = null,
        val steered: Boolean? = null,
        val queueId: String? = null,
        val turnId: String? = null,
        val turnTerminal: Boolean? = null,
        val via: String? = null,
    ) {
        constructor(message: Message) : this(
            id = message.id,
            role = message.role,
            kind = message.kind,
            at = message.at,
            text = message.text,
            card = message.card,
            tool = message.tool,
            threadRef = message.threadRef,
            compaction = message.compaction,
            routineRun = message.routineRun,
            parentId = message.parentId,
            from = message.from,
            reactions = message.reactions,
            comm = message.comm,
            // The server's own rule for a frame without its pixels (`slimMessage`):
            // the row fetches them from the computer when it shows one.
            hasImage = if (message.png != null) true else message.hasImage,
            mime = message.mime,
            // A path that carries its bytes inline is not a path.
            attachments = message.attachments?.map { if (isInlineData(it.path)) it.copy(path = null) else it },
            steered = message.steered,
            queueId = message.queueId,
            turnId = message.turnId,
            turnTerminal = message.turnTerminal,
            via = message.via,
        )

        val message: Message
            get() = Message(
                id = id,
                role = role,
                kind = kind,
                at = at,
                text = text,
                card = card,
                tool = tool,
                threadRef = threadRef,
                compaction = compaction,
                routineRun = routineRun,
                parentId = parentId,
                from = from,
                reactions = reactions,
                comm = comm,
                hasImage = hasImage,
                mime = mime,
                attachments = attachments,
                steered = steered,
                queueId = queueId,
                turnId = turnId,
                turnTerminal = turnTerminal,
                via = via,
            )
    }

    /** The bytes [SnapshotStore] writes. The byte cap is measured on exactly this encoding. */
    fun encoded(): ByteArray = CompanionJson.encodeToString(serializer(), this).encodeToByteArray()

    /** Every thread the roster lists: each bot's and room's own, and their tasks. */
    internal val rosterThreadIds: Set<String>
        get() = buildSet {
            bots.forEach { bot ->
                add(bot.threadId)
                bot.tasks.orEmpty().forEach { add(it.threadId) }
            }
            rooms.forEach { room ->
                add(room.threadId)
                room.tasks.orEmpty().forEach { add(it.threadId) }
            }
        }

    /**
     * Only when the roster alone outgrows the cap, which takes a thread list
     * in the thousands: the oldest routine runs go first, then the least
     * recently active rows of the thread list. A bot's or room's own thread is
     * never dropped — it is the roster row itself.
     */
    internal fun shedRoster(maxBytes: Int, activity: Map<String, Double>): EncodedSnapshot {
        val bytes = encoded()
        var total = bytes.size
        if (total <= maxBytes) return EncodedSnapshot(this, bytes)
        // Array elements cost their own bytes plus a comma.
        fun cost(element: String): Int = element.encodeToByteArray().size + 1

        val droppedRuns = mutableSetOf<Int>()
        for (index in routineRuns.indices.sortedWith { a, b -> runOrder(routineRuns[a], routineRuns[b]) }) {
            if (total <= maxBytes) break
            total -= cost(CompanionJson.encodeToString(RoutineRun.serializer(), routineRuns[index]))
            droppedRuns += index
        }

        val owners = (bots.map(CachedBot::threadId) + rooms.map(CachedRoom::threadId)).toSet()
        val listed = (bots.flatMap { it.tasks.orEmpty() } + rooms.flatMap { it.tasks.orEmpty() })
            .filter { it.threadId !in owners }
            .sortedBy { activity[it.threadId] ?: it.listStamp }
        val droppedThreads = mutableSetOf<String>()
        for (task in listed) {
            if (total <= maxBytes) break
            total -= cost(CompanionJson.encodeToString(CachedTask.serializer(), task))
            droppedThreads += task.threadId
        }

        fun List<CachedTask>?.keeping() = this?.filter { it.threadId !in droppedThreads }
        val shed = copy(
            routineRuns = routineRuns.filterIndexed { index, _ -> index !in droppedRuns },
            bots = if (droppedThreads.isEmpty()) bots else bots.map { it.copy(tasks = it.tasks.keeping()) },
            rooms = if (droppedThreads.isEmpty()) rooms else rooms.map { it.copy(tasks = it.tasks.keeping()) },
        )
        return EncodedSnapshot(shed, shed.encoded())
    }

    /**
     * Adds cached threads most recently active first and stops at the first
     * one that would take the file past [maxBytes], so whatever is left out
     * is always the least recently active.
     */
    internal fun fill(
        ordered: List<Pair<String, CachedThread>>,
        maxBytes: Int,
        base: EncodedSnapshot,
    ): EncodedSnapshot {
        // `,"threads":{` and `}` around the entries.
        var total = base.bytes.size + 13
        var kept = 0
        for ((threadId, thread) in ordered) {
            // `"id":{…},` — the key, its quotes, the colon and a comma.
            val cost = CompanionJson.encodeToString(threadId).encodeToByteArray().size + 2 +
                CompanionJson.encodeToString(CachedThread.serializer(), thread).encodeToByteArray().size
            if (total + cost - 1 > maxBytes) break
            total += cost
            kept += 1
        }
        // The estimate is exact for compact JSON; the encoded file is still the real measure.
        while (kept > 0) {
            val candidate = copy(threads = ordered.take(kept).toMap(LinkedHashMap()))
            val bytes = candidate.encoded()
            if (bytes.size <= maxBytes) return EncodedSnapshot(candidate, bytes)
            kept -= 1
        }
        return base
    }

    companion object {
        const val SCHEMA_VERSION = 1

        fun decode(bytes: ByteArray): StateSnapshot =
            CompanionJson.decodeFromString(serializer(), bytes.decodeToString())

        /** The [limit] most recent runs, in the order they arrived. */
        internal fun recentRuns(runs: List<RoutineRun>, limit: Int): List<RoutineRun> {
            if (runs.size <= limit) return runs
            val kept = runs.indices
                .sortedWith { a, b -> runOrder(runs[b], runs[a]) }
                .take(maxOf(0, limit))
                .sorted()
            return kept.map(runs::get)
        }

        /** Older first: by scheduled time, then by when the run was made. */
        private fun runOrder(lhs: RoutineRun, rhs: RoutineRun): Int =
            compareValuesBy(lhs, rhs, RoutineRun::scheduledFor, RoutineRun::createdAt)
    }
}

/** A snapshot and the exact bytes the store writes for it. */
internal class EncodedSnapshot(val snapshot: StateSnapshot, val bytes: ByteArray)

/**
 * The copy of this state worth keeping for when the computer is out of
 * reach, inside [limits]: whole threads are dropped least recently active
 * first until the encoded file fits.
 *
 * Pure, but it encodes to measure, so it belongs off the main thread —
 * [SnapshotStore.save] runs it on the store's writer, and the state being
 * immutable, handing it over is the value copy.
 *
 * Routines are not part of the fold — the screens that show them load their
 * own — so the caller passes the latest it holds. Nothing here takes a
 * [Connection]: the id and server identity are all a snapshot needs, and the
 * rest of a connection has no business on disk.
 *
 * Null for a state that is itself a cached copy: writing it back would
 * restamp old data as a new sync.
 */
fun CompanionState.offlineSnapshot(
    connectionId: String,
    serverEnvironmentId: String?,
    savedAt: Long = System.currentTimeMillis(),
    routines: List<Routine> = emptyList(),
    routineRuns: List<RoutineRun> = emptyList(),
    limits: StateSnapshot.Limits = StateSnapshot.Limits.STANDARD,
): StateSnapshot? =
    encodedOfflineSnapshot(connectionId, serverEnvironmentId, savedAt, routines, routineRuns, limits)?.snapshot

internal fun CompanionState.encodedOfflineSnapshot(
    connectionId: String,
    serverEnvironmentId: String?,
    savedAt: Long,
    routines: List<Routine>,
    routineRuns: List<RoutineRun>,
    limits: StateSnapshot.Limits,
): EncodedSnapshot? {
    if (isCached) return null
    val activity = threadActivity()
    val roster = StateSnapshot(
        schemaVersion = StateSnapshot.SCHEMA_VERSION,
        connectionId = connectionId,
        serverEnvironmentId = serverEnvironmentId,
        savedAt = savedAt,
        // Transcripts live in `threads`, one bounded page each; the copy a
        // hydrate leaves on the record has no field to land in.
        bots = bots.map { StateSnapshot.CachedBot(it) },
        rooms = rooms.map { StateSnapshot.CachedRoom(it) },
        routines = routines,
        routineRuns = StateSnapshot.recentRuns(routineRuns, limits.routineRuns),
    ).shedRoster(limits.maxBytes, activity)

    // Opened means a page was fetched: a live tail alone is not a page, and
    // showing it as the thread would hide everything above it.
    val listed = roster.snapshot.rosterThreadIds
    val settled = copy(pendingEdits = emptyMap())
    val opened = activity.entries
        .filter { (threadId, _) -> threadId in listed && hasLoadedPage(threadId) }
        .sortedWith(compareByDescending<Map.Entry<String, Double>> { it.value }.thenBy { it.key })
        .take(maxOf(0, limits.threads))
        .map { (threadId, _) -> threadId to settled.cachedThread(threadId, limits.messagesPerThread) }
    return roster.snapshot.fill(opened, limits.maxBytes, roster)
}

/**
 * A display-only state rebuilt from a snapshot, laid out the way a hydrate
 * lays out the same roster, and marked cached ([CompanionState.cachedAt]). It
 * has no cursor, so the stream can only begin with a cold hydrate — which
 * replaces it. Routines are read from the snapshot itself.
 */
fun CompanionState(snapshot: StateSnapshot): CompanionState {
    val messages = LinkedHashMap<String, List<Message>>()
    val hasMore = LinkedHashMap<String, Boolean>()
    val activeLeafIds = LinkedHashMap<String, String?>()
    snapshot.bots.forEach { bot ->
        messages[bot.threadId] = emptyList()
        activeLeafIds[bot.threadId] = bot.activeLeafId
    }
    snapshot.rooms.forEach { room -> messages[room.threadId] = emptyList() }
    snapshot.threads.forEach { (threadId, thread) ->
        messages[threadId] = thread.messages.map(StateSnapshot.CachedMessage::message)
        hasMore[threadId] = thread.hasMore
        thread.activeLeafId?.let { activeLeafIds[threadId] = it }
    }
    return CompanionState(
        bots = snapshot.bots.map(StateSnapshot.CachedBot::bot),
        rooms = snapshot.rooms.map(StateSnapshot.CachedRoom::room),
        messages = messages,
        hasMore = hasMore,
        activeLeafIds = activeLeafIds,
        cachedAt = snapshot.savedAt,
    )
}

/** When each thread on the roster last moved: the newest of its list stamp and the newest message held for it. */
private fun CompanionState.threadActivity(): Map<String, Double> {
    val stamps = LinkedHashMap<String, Double>()
    fun note(threadId: String, at: Double) {
        val value = if (at.isFinite()) at else Double.NEGATIVE_INFINITY
        stamps[threadId] = maxOf(stamps[threadId] ?: Double.NEGATIVE_INFINITY, value)
    }
    bots.forEach { bot ->
        note(bot.threadId, bot.createdAt)
        bot.tasks.orEmpty().forEach { note(it.threadId, it.listStamp) }
    }
    rooms.forEach { room ->
        note(room.threadId, room.createdAt)
        room.tasks.orEmpty().forEach { note(it.threadId, it.listStamp) }
    }
    messages.forEach { (threadId, transcript) ->
        if (threadId in stamps) transcript.forEach { note(threadId, it.at) }
    }
    return stamps
}

/**
 * The thread as it reads on screen, less any edit in flight (the caller
 * clears them first): its active branch, newest [messageLimit] messages.
 */
private fun CompanionState.cachedThread(threadId: String, messageLimit: Int): StateSnapshot.CachedThread {
    val branch = visibleTranscript(threadId)
    val page = branch.takeLast(maxOf(0, messageLimit))
    return StateSnapshot.CachedThread(
        messages = page.map { StateSnapshot.CachedMessage(it) },
        hasMore = hasMore[threadId] == true || page.size < branch.size,
        activeLeafId = activeLeafIds[threadId],
    )
}

/** A `data:` URI is the bytes themselves, not a reference to them. */
private fun isInlineData(value: String?): Boolean =
    value?.trimStart()?.startsWith("data:", ignoreCase = true) == true
