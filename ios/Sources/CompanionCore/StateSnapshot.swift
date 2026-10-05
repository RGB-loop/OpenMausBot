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
    /// The roster with every transcript stripped off.
    public var bots: [Bot]
    public var rooms: [Room]
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
            hasImage = message.hasImage
            mime = message.mime
            attachments = message.attachments?.map { attachment in
                // A path that carries its bytes inline is not a path.
                var kept = attachment
                if kept.path?.range(of: "data:", options: [.anchored, .caseInsensitive]) != nil {
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
    /// left out is always the least recently active.
    fileprivate mutating func fill(_ ordered: [(threadId: String, thread: CachedThread)], toFit maxBytes: Int) {
        let encoder = Self.makeEncoder()
        var total = byteCount()
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
        while kept > 0, byteCount() > maxBytes {
            kept -= 1
            threads.removeValue(forKey: ordered[kept].threadId)
        }
    }

    /// Only when the roster alone outgrows the cap, which takes a thread
    /// list in the thousands: the oldest routine runs go first, then the
    /// least recently active rows of the thread list. A bot's or room's own
    /// thread is never dropped — it is the roster row itself.
    fileprivate mutating func shedRoster(toFit maxBytes: Int, activity: [String: Double]) {
        var total = byteCount()
        guard total > maxBytes else { return }
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
        guard !droppedThreads.isEmpty else { return }
        for index in bots.indices {
            bots[index].tasks = bots[index].tasks?.filter { !droppedThreads.contains($0.threadId) }
        }
        for index in rooms.indices {
            rooms[index].tasks = rooms[index].tasks?.filter { !droppedThreads.contains($0.threadId) }
        }
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
    public func offlineSnapshot(
        connectionId: String,
        serverEnvironmentId: String?,
        savedAt: Date = Date(),
        routines: [Routine] = [],
        routineRuns: [RoutineRun] = [],
        limits: StateSnapshot.Limits = .standard
    ) -> StateSnapshot? {
        guard !isCached else { return nil }
        let activity = threadActivity()
        var snapshot = StateSnapshot(
            schemaVersion: StateSnapshot.currentSchemaVersion,
            connectionId: connectionId,
            serverEnvironmentId: serverEnvironmentId,
            savedAt: savedAt,
            // Transcripts live in `threads`, one bounded page each; the copy
            // a hydrate leaves on the record would be a second, unbounded one.
            bots: bots.map { bot in
                var bot = bot
                bot.messages = nil
                bot.hasMore = nil
                return bot
            },
            rooms: rooms.map { room in
                var room = room
                room.messages = nil
                room.hasMore = nil
                return room
            },
            threads: [:],
            routines: routines,
            routineRuns: StateSnapshot.recentRuns(routineRuns, limit: limits.routineRuns)
        )
        snapshot.shedRoster(toFit: limits.maxBytes, activity: activity)

        // Opened means a page was fetched: a live tail alone is not a page,
        // and showing it as the thread would hide everything above it.
        let listed = snapshot.rosterThreadIds
        let opened = activity
            .filter { listed.contains($0.key) && hasLoadedPage(forThread: $0.key) }
            .sorted { $0.value == $1.value ? $0.key < $1.key : $0.value > $1.value }
            .prefix(max(0, limits.threads))
            .map { (threadId: $0.key, thread: cachedThread($0.key, messageLimit: limits.messagesPerThread)) }
        snapshot.fill(opened, toFit: limits.maxBytes)
        return snapshot
    }

    /// A display-only state rebuilt from a snapshot, laid out the way a
    /// hydrate lays out the same roster, and marked cached. It has no
    /// cursor, so the stream can only begin with a cold hydrate — which
    /// replaces it.
    public init(snapshot: StateSnapshot) {
        self.init()
        bots = snapshot.bots
        rooms = snapshot.rooms
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
