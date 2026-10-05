// The last sync, kept on the phone so it can be shown offline (MOCA-296).
//
// `CompanionState` lives only in memory, so a cold launch without the
// computer was an empty app, and every launch was empty until hydrate
// landed. This is the data half of keeping a copy: what is kept, how it is
// bounded, and how a display-only state is rebuilt from it. Writing and
// reading the file is `SnapshotStore`; when to do either is Session's call.
//
// Kept, per paired computer: the roster (bots, rooms, sections, pins, the
// thread list with titles, unread flags and activity times), the latest
// page of the active branch of each thread this phone opened, and routines
// with their recent runs. Never kept: credentials of any kind, screenshot
// or attachment bytes, Live call state, anything mid-flight (live text,
// edits, held sends), and the stream cursor — a cached view is never
// resumed, only replaced by the next hydrate.
import Foundation

public struct StateSnapshot: Codable, Equatable, Sendable {
    /// Bumped whenever the encoded shape changes meaning. A file written
    /// under any other version is refused on load rather than migrated: it
    /// is a cache, and the next hydrate writes a fresh one.
    public static let currentSchemaVersion = 1

    public var schemaVersion: Int
    /// The saved connection this copy belongs to. A load for another id is
    /// refused, whatever the file is called.
    public var connectionId: String
    /// The server's identity when the connection pairs with one directly,
    /// nil for a desktop sidecar. A different identity at the same address
    /// is a different computer, so its copy is never shown.
    public var serverEnvironmentId: String?
    public var savedAt: Date
    /// The roster, as rows that have no room for a transcript.
    public var bots: [CachedBot]
    public var rooms: [CachedRoom]
    /// The latest page of each opened thread, by thread id.
    public var threads: [String: CachedThread]
    public var routines: [Routine]
    public var routineRuns: [RoutineRun]

    /// The bounds a snapshot is built under. Injectable so tests can reach
    /// them with small fixtures; the app always uses `standard`.
    public struct Limits: Equatable, Sendable {
        /// The page the app already loads when a thread opens.
        public var messagesPerThread: Int
        public var threads: Int
        public var routineRuns: Int
        /// The encoded file, all of it.
        public var maxBytes: Int

        public init(messagesPerThread: Int, threads: Int, routineRuns: Int, maxBytes: Int) {
            self.messagesPerThread = messagesPerThread
            self.threads = threads
            self.routineRuns = routineRuns
            self.maxBytes = maxBytes
        }

        public static let standard = Limits(
            messagesPerThread: 50, threads: 100, routineRuns: 100, maxBytes: 5 * 1_024 * 1_024
        )
    }

    // MARK: Roster rows
    //
    // The roster rows below keep the property names, and so the JSON keys,
    // of the wire types they copy, in the same order. A file the first
    // schema-1 build wrote — `Bot` and `Room` with their transcripts set to
    // nil — reads back as these rows unchanged, so the version stays 1.

    /// A bot as the roster keeps it: `Bot` field for field, less the
    /// record's own transcript. A hydrate leaves a page of messages on each
    /// record, screenshots and all; the only copy of a transcript the cache
    /// keeps is the bounded page in `threads`. Spelled out rather than
    /// reusing the wire type for the same reason as `CachedMessage`: a heavy
    /// field the wire gains later has to be added here on purpose, so no
    /// snapshot, however it is made, can carry one to disk. StateSnapshotTests
    /// fails when `Bot` gains a field this row neither keeps nor leaves out
    /// by name.
    public struct CachedBot: Codable, Equatable, Sendable {
        public var id: String
        public var threadId: String
        public var name: String
        public var title: String
        public var description: String
        public var notifications: Bool
        public var color: String
        public var avatarUrl: String?
        public var avatarCrop: AvatarCrop?
        public var unread: Bool
        public var modelSelection: ModelSelection
        public var createdAt: Double
        public var busy: Bool?
        public var activity: String?
        public var waitingOnTeammate: Bool?
        public var pinned: Bool?
        public var hidden: Bool?
        public var section: String?
        public var chiefOfStaff: Bool?
        public var approvalMode: String?
        public var autoApprove: Bool?
        public var alwaysAllow: [String]?
        public var computer: String?
        public var cloudBackend: String?
        public var speakReplies: Bool?
        public var voice: String?
        public var mascotExpression: String?
        public var mascotBody: String?
        public var tasks: [CachedTask]?
        public var projects: [BotProject]?
        public var activeLeafId: String?

        public init(_ bot: Bot) {
            id = bot.id
            threadId = bot.threadId
            name = bot.name
            title = bot.title
            description = bot.description
            notifications = bot.notifications
            color = bot.color
            avatarUrl = bot.avatarUrl
            avatarCrop = bot.avatarCrop
            unread = bot.unread
            modelSelection = bot.modelSelection
            createdAt = bot.createdAt
            busy = bot.busy
            activity = bot.activity
            waitingOnTeammate = bot.waitingOnTeammate
            pinned = bot.pinned
            hidden = bot.hidden
            section = bot.section
            chiefOfStaff = bot.chiefOfStaff
            approvalMode = bot.approvalMode
            autoApprove = bot.autoApprove
            alwaysAllow = bot.alwaysAllow
            computer = bot.computer
            cloudBackend = bot.cloudBackend
            speakReplies = bot.speakReplies
            voice = bot.voice
            mascotExpression = bot.mascotExpression
            mascotBody = bot.mascotBody
            tasks = bot.tasks?.map(CachedTask.init)
            projects = bot.projects
            activeLeafId = bot.activeLeafId
        }

        /// The record as a hydrate would leave it, with no transcript on it.
        public var bot: Bot {
            var bot = Bot(
                id: id, threadId: threadId, name: name, title: title, description: description,
                notifications: notifications, color: color, unread: unread,
                modelSelection: modelSelection, createdAt: createdAt
            )
            bot.avatarUrl = avatarUrl
            bot.avatarCrop = avatarCrop
            bot.busy = busy
            bot.activity = activity
            bot.waitingOnTeammate = waitingOnTeammate
            bot.pinned = pinned
            bot.hidden = hidden
            bot.section = section
            bot.chiefOfStaff = chiefOfStaff
            bot.approvalMode = approvalMode
            bot.autoApprove = autoApprove
            bot.alwaysAllow = alwaysAllow
            bot.computer = computer
            bot.cloudBackend = cloudBackend
            bot.speakReplies = speakReplies
            bot.voice = voice
            bot.mascotExpression = mascotExpression
            bot.mascotBody = mascotBody
            bot.tasks = tasks?.map(\.task)
            bot.projects = projects
            bot.activeLeafId = activeLeafId
            return bot
        }
    }

    /// A channel or DM as the roster keeps it: `Room` field for field, less
    /// its transcript (see `CachedBot`).
    public struct CachedRoom: Codable, Equatable, Sendable {
        public var id: String
        public var threadId: String
        public var name: String
        public var memberIds: [String]
        public var defaultResponder: GroupResponder
        public var bulletin: String
        public var unread: Bool
        public var createdAt: Double
        public var dm: Bool?
        public var section: String?
        public var busyBotId: String?
        public var working: Bool?
        public var tasks: [CachedTask]?

        public init(_ room: Room) {
            id = room.id
            threadId = room.threadId
            name = room.name
            memberIds = room.memberIds
            defaultResponder = room.defaultResponder
            bulletin = room.bulletin
            unread = room.unread
            createdAt = room.createdAt
            dm = room.dm
            section = room.section
            busyBotId = room.busyBotId
            working = room.working
            tasks = room.tasks?.map(CachedTask.init)
        }

        /// The record as a hydrate would leave it, with no transcript on it.
        public var room: Room {
            var room = Room(
                id: id, threadId: threadId, name: name, memberIds: memberIds,
                defaultResponder: defaultResponder, bulletin: bulletin, unread: unread, createdAt: createdAt
            )
            room.dm = dm
            room.section = section
            room.busyBotId = busyBotId
            room.working = working
            room.tasks = tasks?.map(\.task)
            return room
        }
    }

    /// A row of a thread list: `BotTask` field for field, spelled out so a
    /// field it gains later is kept only on purpose.
    public struct CachedTask: Codable, Equatable, Sendable {
        public var threadId: String
        public var title: String
        public var createdAt: Double
        public var modelSelection: ModelSelection?
        public var busy: Bool?
        public var activity: String?
        public var waitingOnTeammate: Bool?
        public var unread: Bool?
        public var approvalMode: String?
        public var autoApprove: Bool?
        public var alwaysAllow: [String]?
        public var projectId: String?
        public var openedBy: ThreadOpener?
        public var closedBy: ThreadCloser?
        public var snoozedUntil: Double?
        public var archivedAt: Double?
        public var routineRunId: String?
        public var pinned: Bool?
        public var updatedAt: Double?

        public init(_ task: BotTask) {
            threadId = task.threadId
            title = task.title
            createdAt = task.createdAt
            modelSelection = task.modelSelection
            busy = task.busy
            activity = task.activity
            waitingOnTeammate = task.waitingOnTeammate
            unread = task.unread
            approvalMode = task.approvalMode
            autoApprove = task.autoApprove
            alwaysAllow = task.alwaysAllow
            projectId = task.projectId
            openedBy = task.openedBy
            closedBy = task.closedBy
            snoozedUntil = task.snoozedUntil
            archivedAt = task.archivedAt
            routineRunId = task.routineRunId
            pinned = task.pinned
            updatedAt = task.updatedAt
        }

        public var task: BotTask {
            var task = BotTask(threadId: threadId, title: title, createdAt: createdAt)
            task.modelSelection = modelSelection
            task.busy = busy
            task.activity = activity
            task.waitingOnTeammate = waitingOnTeammate
            task.unread = unread
            task.approvalMode = approvalMode
            task.autoApprove = autoApprove
            task.alwaysAllow = alwaysAllow
            task.projectId = projectId
            task.openedBy = openedBy
            task.closedBy = closedBy
            task.snoozedUntil = snoozedUntil
            task.archivedAt = archivedAt
            task.routineRunId = routineRunId
            task.pinned = pinned
            task.updatedAt = updatedAt
            return task
        }

        /// `BotTask.listStamp`: the time the thread list sorts by.
        var listStamp: Double { updatedAt ?? createdAt }
    }

    /// One opened thread as it last looked: its active branch, newest last.
    public struct CachedThread: Codable, Equatable, Sendable {
        public var messages: [CachedMessage]
        /// More transcript above this page — on the computer, or trimmed
        /// here to stay inside the bounds.
        public var hasMore: Bool
        public var activeLeafId: String?
    }

    /// A transcript line as the cache keeps it: `Message` field for field,
    /// less the inline screenshot. Spelled out rather than reusing the wire
    /// type, so a heavy field the wire gains later does not quietly start
    /// landing on disk; it has to be added here, and the version bumped.
    public struct CachedMessage: Codable, Equatable, Sendable {
        public var id: String
        public var role: Message.Role
        public var kind: Message.Kind
        public var at: Double
        public var text: String?
        public var turnId: String?
        public var turnTerminal: Bool?
        /// Kept so a saved ask still reads as one. Session never offers to
        /// answer it: it may have expired or been answered elsewhere.
        public var card: OptionCard?
        /// Identifiers and display copy only; the credential itself never
        /// reaches the phone's transcript.
        public var secret: SecretRequestCardData?
        public var tool: ToolActivity?
        public var threadRef: ThreadRef?
        public var compaction: Compaction?
        public var routineRun: RoutineRunCard?
        public var parentId: String?
        public var queueId: String?
        public var steered: Bool?
        public var from: Sender?
        public var via: String?
        public var reactions: [Reaction]?
        public var comm: CommChip?
        public var hasImage: Bool?
        public var mime: String?
        /// Kinds, names, MIME types and server paths, so file cards still
        /// render. The bytes stay on the computer.
        public var attachments: [MessageImageAttachment]?

        public init(_ message: Message) {
            id = message.id
            role = message.role
            kind = message.kind
            at = message.at
            text = message.text
            turnId = message.turnId
            turnTerminal = message.turnTerminal
            card = message.card
            secret = message.secret
            tool = message.tool
            threadRef = message.threadRef
            compaction = message.compaction
            routineRun = message.routineRun
            parentId = message.parentId
            queueId = message.queueId
            steered = message.steered
            from = message.from
            via = message.via
            reactions = message.reactions
            comm = message.comm
            // The server's own rule for a slimmed screen message: the pixels
            // stay behind `/messages/:id/image`, and the row says so, or the
            // cached row could never fetch them.
            hasImage = message.png != nil ? true : message.hasImage
            mime = message.mime
            attachments = message.attachments?.map { attachment in
                // A path that carries its bytes inline is not a path.
                var kept = attachment
                if kept.path?.trimmingCharacters(in: .whitespacesAndNewlines)
                    .range(of: "data:", options: [.anchored, .caseInsensitive]) != nil {
                    kept.path = nil
                }
                return kept
            }
        }

        public var message: Message {
            var message = Message(id: id, role: role, kind: kind, at: at)
            message.text = text
            message.turnId = turnId
            message.turnTerminal = turnTerminal
            message.card = card
            message.secret = secret
            message.tool = tool
            message.threadRef = threadRef
            message.compaction = compaction
            message.routineRun = routineRun
            message.parentId = parentId
            message.queueId = queueId
            message.steered = steered
            message.from = from
            message.via = via
            message.reactions = reactions
            message.comm = comm
            message.hasImage = hasImage
            message.mime = mime
            message.attachments = attachments
            return message
        }
    }
}

// MARK: - Encoding

extension StateSnapshot {
    /// The bytes `SnapshotStore` writes. The byte cap is measured on exactly
    /// this encoding.
    public func encoded() throws -> Data {
        try Self.makeEncoder().encode(self)
    }

    public static func decoded(from data: Data) throws -> StateSnapshot {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        return try decoder.decode(StateSnapshot.self, from: data)
    }

    static func makeEncoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        // Escaped slashes only make the file bigger and the size estimate
        // in `fill` wrong.
        encoder.outputFormatting = [.withoutEscapingSlashes]
        return encoder
    }

    /// Every thread the roster lists: each bot's and room's own, and their
    /// tasks.
    var rosterThreadIds: Set<String> {
        var ids = Set<String>()
        for bot in bots {
            ids.insert(bot.threadId)
            ids.formUnion((bot.tasks ?? []).map(\.threadId))
        }
        for room in rooms {
            ids.insert(room.threadId)
            ids.formUnion((room.tasks ?? []).map(\.threadId))
        }
        return ids
    }

    fileprivate func byteCount() -> Int {
        (try? encoded().count) ?? .max
    }

    /// Adds cached threads most recently active first and stops at the
    /// first one that would take the file past `maxBytes`, so whatever is
    /// left out is always the least recently active. `rosterBytes` is the
    /// encoded size before any thread is added.
    ///
    /// Returns the encoded file the cap was checked on — exactly the bytes
    /// a save writes, so it never encodes the file again — or nil when the
    /// snapshot does not encode.
    fileprivate mutating func fill(
        _ ordered: [(threadId: String, thread: CachedThread)],
        toFit maxBytes: Int,
        rosterBytes: Int
    ) -> Data? {
        let encoder = Self.makeEncoder()
        var total = rosterBytes
        var kept = 0
        for entry in ordered {
            guard let size = try? encoder.encode(entry.thread).count else { break }
            // `"id":{…},` — the key, its quotes, the colon and a comma.
            let cost = entry.threadId.utf8.count + 4 + size
            guard total <= maxBytes - cost else { break }
            total += cost
            kept += 1
        }
        threads = Dictionary(ordered.prefix(kept).map { ($0.threadId, $0.thread) }, uniquingKeysWith: { first, _ in first })
        // The estimate ignores escaping inside keys; the encoded file is the
        // real measure.
        var data = try? encoded()
        while kept > 0, (data?.count ?? .max) > maxBytes {
            kept -= 1
            threads.removeValue(forKey: ordered[kept].threadId)
            data = try? encoded()
        }
        return data
    }

    /// Only when the roster alone outgrows the cap, which takes a thread
    /// list in the thousands: the oldest routine runs go first, then the
    /// least recently active rows of the thread list. A bot's or room's own
    /// thread is never dropped — it is the roster row itself.
    ///
    /// Returns the roster's encoded size as it is left, for `fill`.
    fileprivate mutating func shedRoster(toFit maxBytes: Int, activity: [String: Double]) -> Int {
        var total = byteCount()
        guard total > maxBytes else { return total }
        let encoder = Self.makeEncoder()
        // Array elements cost their own bytes plus a comma, exactly.
        func cost<T: Encodable>(_ value: T) -> Int { ((try? encoder.encode(value).count) ?? 0) + 1 }

        var droppedRuns = Set<Int>()
        for index in routineRuns.indices.sorted(by: { Self.runIsOlder(routineRuns[$0], routineRuns[$1]) })
        where total > maxBytes {
            total -= cost(routineRuns[index])
            droppedRuns.insert(index)
        }
        routineRuns = routineRuns.enumerated().filter { !droppedRuns.contains($0.offset) }.map(\.element)

        let owners = Set(bots.map(\.threadId) + rooms.map(\.threadId))
        let listed = (bots.flatMap { $0.tasks ?? [] } + rooms.flatMap { $0.tasks ?? [] })
            .filter { !owners.contains($0.threadId) }
            .sorted { (activity[$0.threadId] ?? $0.listStamp) < (activity[$1.threadId] ?? $1.listStamp) }
        var droppedThreads = Set<String>()
        for task in listed where total > maxBytes {
            total -= cost(task)
            droppedThreads.insert(task.threadId)
        }
        if !droppedThreads.isEmpty {
            for index in bots.indices {
                bots[index].tasks = bots[index].tasks?.filter { !droppedThreads.contains($0.threadId) }
            }
            for index in rooms.indices {
                rooms[index].tasks = rooms[index].tasks?.filter { !droppedThreads.contains($0.threadId) }
            }
        }
        // `total` was an estimate; measure what is left.
        return byteCount()
    }

    /// The `limit` most recent runs, in the order they arrived.
    static func recentRuns(_ runs: [RoutineRun], limit: Int) -> [RoutineRun] {
        guard runs.count > limit else { return runs }
        let kept = runs.indices
            .sorted { runIsOlder(runs[$1], runs[$0]) }
            .prefix(max(0, limit))
            .sorted()
        return kept.map { runs[$0] }
    }

    private static func runIsOlder(_ lhs: RoutineRun, _ rhs: RoutineRun) -> Bool {
        lhs.scheduledFor == rhs.scheduledFor ? lhs.createdAt < rhs.createdAt : lhs.scheduledFor < rhs.scheduledFor
    }
}

// MARK: - Building and rebuilding

extension CompanionState {
    /// The copy of this state worth keeping for when the computer is out of
    /// reach, inside `limits`: whole threads are dropped least recently
    /// active first until the encoded file fits.
    ///
    /// Routines are not part of the fold — the screens that show them load
    /// their own — so the caller passes the latest it holds. Nothing here
    /// takes a `Connection`: the id and server identity are all a snapshot
    /// needs, and the rest of a connection has no business on disk.
    ///
    /// Returns nil for a state that is itself a cached copy. Writing it back
    /// would restamp old data as a new sync.
    ///
    /// Not for the main thread: the cap is measured by encoding, up to the
    /// whole file more than once. Call it off the main thread, or hand the
    /// state to `SnapshotStore.save(_:connectionId:serverEnvironmentId:…)`,
    /// which builds and writes on its own queue.
    public func offlineSnapshot(
        connectionId: String,
        serverEnvironmentId: String?,
        savedAt: Date = Date(),
        routines: [Routine] = [],
        routineRuns: [RoutineRun] = [],
        limits: StateSnapshot.Limits = .standard
    ) -> StateSnapshot? {
        encodedOfflineSnapshot(
            connectionId: connectionId,
            serverEnvironmentId: serverEnvironmentId,
            savedAt: savedAt,
            routines: routines,
            routineRuns: routineRuns,
            limits: limits
        )?.snapshot
    }

    /// `offlineSnapshot`, with the encoded file its cap was measured on, so
    /// a save writes those bytes rather than encoding them again. `data` is
    /// nil only when the snapshot does not encode.
    func encodedOfflineSnapshot(
        connectionId: String,
        serverEnvironmentId: String?,
        savedAt: Date,
        routines: [Routine],
        routineRuns: [RoutineRun],
        limits: StateSnapshot.Limits
    ) -> (snapshot: StateSnapshot, data: Data?)? {
        guard !isCached else { return nil }
        let activity = threadActivity()
        var snapshot = StateSnapshot(
            schemaVersion: StateSnapshot.currentSchemaVersion,
            connectionId: connectionId,
            serverEnvironmentId: serverEnvironmentId,
            savedAt: savedAt,
            // Transcripts live in `threads`, one bounded page each; the copy
            // a hydrate leaves on the record has no field to land in.
            bots: bots.map(StateSnapshot.CachedBot.init),
            rooms: rooms.map(StateSnapshot.CachedRoom.init),
            threads: [:],
            routines: routines,
            routineRuns: StateSnapshot.recentRuns(routineRuns, limit: limits.routineRuns)
        )
        let rosterBytes = snapshot.shedRoster(toFit: limits.maxBytes, activity: activity)

        // Opened means a page was fetched: a live tail alone is not a page,
        // and showing it as the thread would hide everything above it.
        let listed = snapshot.rosterThreadIds
        let opened = activity
            .filter { listed.contains($0.key) && hasLoadedPage(forThread: $0.key) }
            .sorted { $0.value == $1.value ? $0.key < $1.key : $0.value > $1.value }
            .prefix(max(0, limits.threads))
            .map { (threadId: $0.key, thread: cachedThread($0.key, messageLimit: limits.messagesPerThread)) }
        let data = snapshot.fill(opened, toFit: limits.maxBytes, rosterBytes: rosterBytes)
        return (snapshot, data)
    }

    /// A display-only state rebuilt from a snapshot, laid out the way a
    /// hydrate lays out the same roster, and marked cached. It has no
    /// cursor, so the stream can only begin with a cold hydrate — which
    /// replaces it.
    public init(snapshot: StateSnapshot) {
        self.init()
        bots = snapshot.bots.map(\.bot)
        rooms = snapshot.rooms.map(\.room)
        for bot in snapshot.bots {
            messages[bot.threadId] = []
            activeLeafIds[bot.threadId] = bot.activeLeafId
        }
        for room in snapshot.rooms {
            messages[room.threadId] = []
        }
        for (threadId, thread) in snapshot.threads {
            messages[threadId] = thread.messages.map(\.message)
            hasMore[threadId] = thread.hasMore
            if let leaf = thread.activeLeafId { activeLeafIds[threadId] = leaf }
        }
        cachedAt = snapshot.savedAt
    }

    /// When each thread on the roster last moved: the newest of its list
    /// stamp and the newest message held for it.
    private func threadActivity() -> [String: Double] {
        var stamps: [String: Double] = [:]
        func note(_ threadId: String, _ at: Double) {
            stamps[threadId] = max(stamps[threadId] ?? -.infinity, at.isFinite ? at : -.infinity)
        }
        for bot in bots {
            note(bot.threadId, bot.createdAt)
            for task in bot.tasks ?? [] { note(task.threadId, task.listStamp) }
        }
        for room in rooms {
            note(room.threadId, room.createdAt)
            for task in room.tasks ?? [] { note(task.threadId, task.listStamp) }
        }
        for (threadId, transcript) in messages where stamps[threadId] != nil {
            for message in transcript { note(threadId, message.at) }
        }
        return stamps
    }

    /// The thread as it reads on screen, less any edit in flight: its active
    /// branch, newest `messageLimit` messages.
    private func cachedThread(_ threadId: String, messageLimit: Int) -> StateSnapshot.CachedThread {
        let branch = activeBranch(forThread: threadId)
        let page = branch.suffix(max(0, messageLimit))
        return StateSnapshot.CachedThread(
            messages: page.map(StateSnapshot.CachedMessage.init),
            hasMore: hasMore[threadId] == true || page.count < branch.count,
            activeLeafId: activeLeafIds[threadId]
        )
    }
}
