// The offline snapshot (MOCA-296): what the phone keeps of the last sync,
// how it is bounded, and what it must never hold. Every claim here is about
// the encoded file or the state rebuilt from it, because those are the two
// things the app will actually show and write.
import XCTest
@testable import CompanionCore

final class StateSnapshotTests: XCTestCase {
    private let savedAt = Date(timeIntervalSince1970: 1_700_000_000)

    // MARK: - Round trip

    func testRoundTripRebuildsTheRosterPagesAndRoutinesAsACachedState() throws {
        var scout = bot("scout", threadId: "scout-main", tasks: [
            task("scout-main", updatedAt: 50),
            task("scout-report", title: "Weekly report", updatedAt: 40, unread: true, pinned: true),
        ])
        scout.section = "Research"
        scout.pinned = true
        scout.projects = [BotProject(id: "launch", name: "Launch", emoji: "🚀")]
        scout.activeLeafId = "m2"
        var chief = bot("chief", threadId: "chief-main")
        chief.chiefOfStaff = true
        var room = Room(
            id: "room", threadId: "room-main", name: "Standup", memberIds: ["scout", "chief"],
            defaultResponder: GroupResponder(kind: "all"), bulletin: "", unread: true, createdAt: 5
        )
        room.section = "Research"

        var state = CompanionState()
        state.bots = [scout, chief]
        state.rooms = [room]
        state.merge(page([message("m1", at: 10), message("m2", at: 20, parentId: "m1", role: .bot)], hasMore: true),
                    intoThread: "scout-main")
        state.activeLeafIds["scout-main"] = "m2"
        state.merge(page([message("r1", at: 30)]), intoThread: "scout-report")
        state.merge(page([message("g1", at: 15)]), intoThread: "room-main")
        state.cursor = "stream:9"
        let routines = [routine("daily")]
        let runs = [run("run-1", routineId: "daily", scheduledFor: 100)]

        let snapshot = try XCTUnwrap(state.offlineSnapshot(
            connectionId: "computer-1", serverEnvironmentId: "env-1", savedAt: savedAt,
            routines: routines, routineRuns: runs
        ))
        let decoded = try StateSnapshot.decoded(from: snapshot.encoded())
        XCTAssertEqual(decoded, snapshot)
        XCTAssertEqual(decoded.schemaVersion, StateSnapshot.currentSchemaVersion)
        XCTAssertEqual(decoded.connectionId, "computer-1")
        XCTAssertEqual(decoded.serverEnvironmentId, "env-1")
        XCTAssertEqual(decoded.savedAt, savedAt)
        XCTAssertEqual(decoded.routines, routines)
        XCTAssertEqual(decoded.routineRuns, runs)

        let rebuilt = CompanionState(snapshot: decoded)
        XCTAssertTrue(rebuilt.isCached)
        XCTAssertEqual(rebuilt.cachedAt, savedAt)
        XCTAssertNil(rebuilt.cursor, "A cached view never resumes the stream.")
        XCTAssertEqual(rebuilt.bots, state.bots)
        XCTAssertEqual(rebuilt.rooms, state.rooms)
        XCTAssertEqual(rebuilt.sidebarSections, state.sidebarSections)
        XCTAssertEqual(rebuilt.pinnedBots, state.pinnedBots)
        XCTAssertEqual(rebuilt.unsectionedChief, state.unsectionedChief)
        XCTAssertEqual(rebuilt.unreadCount, state.unreadCount)
        XCTAssertEqual(rebuilt.bot(forThread: "scout-report")?.unread, true)
        for threadId in ["scout-main", "scout-report", "room-main"] {
            XCTAssertEqual(rebuilt.visibleTranscript(forThread: threadId), state.visibleTranscript(forThread: threadId))
            XCTAssertEqual(rebuilt.hasMore[threadId], state.hasMore[threadId])
            XCTAssertTrue(rebuilt.hasLoadedPage(forThread: threadId))
        }
        XCTAssertEqual(rebuilt.activeLeafIds["scout-main"], "m2")
        // A thread never opened has no page, exactly as after a hydrate.
        XCTAssertEqual(rebuilt.transcript(forThread: "chief-main"), [])
        XCTAssertFalse(rebuilt.hasLoadedPage(forThread: "chief-main"))
    }

    func testAHydratedFleetReadsTheSameOffline() throws {
        var state = CompanionState()
        state.hydrate(try fixtureFleet("updates-fleet"))
        let snapshot = try XCTUnwrap(state.offlineSnapshot(connectionId: "computer-1", serverEnvironmentId: nil, savedAt: savedAt))
        let rebuilt = CompanionState(snapshot: try StateSnapshot.decoded(from: snapshot.encoded()))

        // The roster rows are the hydrated ones, less the transcript copy a
        // hydrate leaves on each record.
        XCTAssertEqual(rebuilt.bots.map(\.id), state.bots.map(\.id))
        XCTAssertTrue(rebuilt.bots.allSatisfy { $0.messages == nil && $0.hasMore == nil })
        XCTAssertEqual(rebuilt.bots.map { $0.tasks ?? [] }, state.bots.map { $0.tasks ?? [] })
        XCTAssertEqual(rebuilt.unreadCount, state.unreadCount)
        let live = state.updates(detail: .full)
        let cached = rebuilt.updates(detail: .full)
        XCTAssertEqual(cached.map(\.id), live.map(\.id))
        XCTAssertEqual(cached.map(\.kind), live.map(\.kind))
        XCTAssertEqual(cached.map(\.card), live.map(\.card), "A saved ask still reads as one.")
        XCTAssertEqual(rebuilt.pendingApprovals.map(\.message.id), state.pendingApprovals.map(\.message.id))
        for threadId in state.messages.keys where state.hasLoadedPage(forThread: threadId) {
            XCTAssertEqual(rebuilt.visibleTranscript(forThread: threadId), state.visibleTranscript(forThread: threadId))
        }
    }

    // MARK: - What a thread keeps

    func testAThreadKeepsTheLatestFiftyOfItsActiveBranchAndOnlyOpenedThreadsAreKept() throws {
        var state = CompanionState()
        state.bots = [bot("scout", threadId: "main", tasks: [task("main", updatedAt: 1), task("tail-only", updatedAt: 2)])]
        var chain = (0..<60).map { index in
            message("m\(index)", at: Double(index), parentId: index == 0 ? nil : "m\(index - 1)")
        }
        chain.append(message("fork", at: 1_000, parentId: "m30"))
        state.merge(page(chain), intoThread: "main")
        state.activeLeafIds["main"] = "m59"
        state.pendingEdits["main"] = PendingEdit(sourceId: "m55", text: "an edit still in flight")
        state.apply(.message(threadId: "tail-only", message: message("live", at: 3)))

        let snapshot = try XCTUnwrap(state.offlineSnapshot(connectionId: "computer-1", serverEnvironmentId: nil, savedAt: savedAt))
        let thread = try XCTUnwrap(snapshot.threads["main"])
        let latest = (10..<60).map { "m\($0)" }
        XCTAssertEqual(thread.messages.map(\.id), latest, "The other branch and the edit's stand-in are not the page.")
        XCTAssertTrue(thread.hasMore, "Trimming to the page leaves more above it.")
        XCTAssertEqual(thread.activeLeafId, "m59")
        XCTAssertNil(snapshot.threads["tail-only"], "A live tail is not a page anyone opened.")

        let rebuilt = CompanionState(snapshot: snapshot)
        XCTAssertEqual(rebuilt.visibleTranscript(forThread: "main").map(\.id), latest)
        XCTAssertEqual(rebuilt.hasMore["main"], true)
        XCTAssertTrue(rebuilt.pendingEdits.isEmpty)
    }

    func testKeepsTheHundredMostRecentlyActiveThreads() throws {
        var state = CompanionState()
        state.bots = [bot("scout", threadId: "t0", tasks: (0..<120).map { task("t\($0)", updatedAt: Double($0)) })]
        for index in 0..<120 {
            state.merge(page([message("m\(index)", at: Double(index))]), intoThread: "t\(index)")
        }
        // An old row whose newest message is the newest of all is recent.
        state.merge(page([message("late", at: 500)]), intoThread: "t3")

        let snapshot = try XCTUnwrap(state.offlineSnapshot(connectionId: "computer-1", serverEnvironmentId: nil, savedAt: savedAt))
        XCTAssertEqual(snapshot.threads.count, 100)
        XCTAssertEqual(Set(snapshot.threads.keys), Set(["t3"] + (21..<120).map { "t\($0)" }))
        XCTAssertEqual(snapshot.bots.first?.tasks?.count, 120, "The thread list is the roster and keeps every row.")
    }

    func testTheByteCapDropsWholeThreadsLeastRecentlyActiveFirst() throws {
        var state = CompanionState()
        state.bots = [bot("scout", threadId: "t0", tasks: (0..<6).map { task("t\($0)", updatedAt: Double($0)) })]
        for index in 0..<6 {
            state.merge(page([message("m\(index)", at: Double(index), text: String(repeating: "x", count: 1_000))]),
                        intoThread: "t\(index)")
        }
        let limits = StateSnapshot.Limits(messagesPerThread: 50, threads: 100, routineRuns: 100, maxBytes: 4_500)

        let snapshot = try XCTUnwrap(state.offlineSnapshot(
            connectionId: "computer-1", serverEnvironmentId: nil, savedAt: savedAt, limits: limits
        ))
        XCTAssertLessThanOrEqual(try snapshot.encoded().count, limits.maxBytes)
        let kept = snapshot.threads.count
        XCTAssertGreaterThan(kept, 0)
        XCTAssertLessThan(kept, 6)
        XCTAssertEqual(Set(snapshot.threads.keys), Set((6 - kept..<6).map { "t\($0)" }))
        // Each kept thread is whole; the cap never cuts into a page.
        XCTAssertTrue(snapshot.threads.values.allSatisfy { $0.messages.count == 1 })
    }

    func testTheStandardCapHoldsAFiveMegabyteFileThatRoundTrips() throws {
        // Ten megabytes of transcript: a hundred opened threads, a full page
        // each, two kilobytes a message.
        var state = CompanionState()
        state.bots = [bot("scout", threadId: "t0", tasks: (0..<100).map { task("t\($0)", updatedAt: Double($0)) })]
        let body = String(repeating: "word ", count: 400)
        for thread in 0..<100 {
            let page = (0..<50).map { index in
                message("t\(thread)-\(index)", at: Double(thread * 100 + index), text: body)
            }
            state.merge(self.page(page), intoThread: "t\(thread)")
        }

        let snapshot = try XCTUnwrap(state.offlineSnapshot(connectionId: "computer-1", serverEnvironmentId: nil, savedAt: savedAt))
        let data = try snapshot.encoded()
        XCTAssertLessThanOrEqual(data.count, StateSnapshot.Limits.standard.maxBytes)
        XCTAssertGreaterThan(data.count, 4 * 1_024 * 1_024, "The cap is filled, not undershot.")
        let kept = snapshot.threads.count
        XCTAssertEqual(Set(snapshot.threads.keys), Set((100 - kept..<100).map { "t\($0)" }))
        XCTAssertEqual(try StateSnapshot.decoded(from: data), snapshot)
    }

    func testARosterOverTheCapShedsTheOldestRunsThenTheOldestRowsButNeverABotsOwnThread() throws {
        let title = String(repeating: "t", count: 200)
        var tasks = [task("main", updatedAt: 0)]
        tasks += (1...50).map { task("row\($0)", title: title, updatedAt: Double($0)) }
        var state = CompanionState()
        state.bots = [bot("scout", threadId: "main", tasks: tasks)]
        let runs = (0..<20).map { index in
            var run = self.run("run\(index)", routineId: "daily", scheduledFor: Double(index))
            run.output = String(repeating: "o", count: 500)
            return run
        }
        func snapshot(maxBytes: Int) throws -> StateSnapshot {
            let limits = StateSnapshot.Limits(messagesPerThread: 50, threads: 100, routineRuns: 100, maxBytes: maxBytes)
            return try XCTUnwrap(state.offlineSnapshot(
                connectionId: "computer-1", serverEnvironmentId: nil, savedAt: savedAt,
                routines: [routine("daily")], routineRuns: runs, limits: limits
            ))
        }
        let full = try snapshot(maxBytes: .max)
        let fullSize = try full.encoded().count

        // A little over: some runs go, oldest first, and every row stays.
        let trimmedRuns = try snapshot(maxBytes: fullSize - 1_000)
        XCTAssertLessThanOrEqual(try trimmedRuns.encoded().count, fullSize - 1_000)
        XCTAssertEqual(trimmedRuns.bots.first?.tasks?.count, 51)
        let keptRuns = trimmedRuns.routineRuns.count
        XCTAssertGreaterThan(keptRuns, 0)
        XCTAssertLessThan(keptRuns, 20)
        XCTAssertEqual(trimmedRuns.routineRuns.map(\.id), (20 - keptRuns..<20).map { "run\($0)" })

        // Far over: every run goes, then the oldest rows — never the bot's own.
        let maxBytes = fullSize - 20_000
        let trimmedRows = try snapshot(maxBytes: maxBytes)
        XCTAssertLessThanOrEqual(try trimmedRows.encoded().count, maxBytes)
        XCTAssertTrue(trimmedRows.routineRuns.isEmpty)
        XCTAssertEqual(trimmedRows.routines, [routine("daily")], "Routines are the roster too; only runs are history.")
        let rows = try XCTUnwrap(trimmedRows.bots.first?.tasks).map(\.threadId)
        XCTAssertTrue(rows.contains("main"))
        XCTAssertLessThan(rows.count, 51)
        let keptRows = rows.count - 1
        XCTAssertGreaterThan(keptRows, 0)
        XCTAssertEqual(Set(rows), Set(["main"] + (51 - keptRows...50).map { "row\($0)" }))
    }

    func testRecentRunsKeepsTheNewestInTheirOriginalOrder() {
        let runs = [run("b", routineId: "r", scheduledFor: 2), run("a", routineId: "r", scheduledFor: 1),
                    run("d", routineId: "r", scheduledFor: 4), run("c", routineId: "r", scheduledFor: 3)]
        XCTAssertEqual(StateSnapshot.recentRuns(runs, limit: 2).map(\.id), ["d", "c"])
        XCTAssertEqual(StateSnapshot.recentRuns(runs, limit: 10).map(\.id), ["b", "a", "d", "c"])
        XCTAssertEqual(StateSnapshot.recentRuns(runs, limit: 0).map(\.id), [])
    }

    // MARK: - What is never kept

    func testNothingSecretHeavyOrInFlightReachesTheFile() throws {
        // Everything the connection carries besides its id stays off disk,
        // and the device token lives only in Keychain.
        let token = "omb_sess_TOKEN-SENTINEL"
        let connection = Connection(
            id: "computer-1", name: "Mac", host: "host-sentinel.local", port: 8787,
            secretPublicKey: "PUBLICKEY-SENTINEL", companionDeviceId: "DEVICE-SENTINEL",
            serverEnvironmentId: "env-1", serverScopes: ["admin"]
        )
        let screenshot = Data("SCREENSHOT-SENTINEL".utf8).base64EncodedString()
        let inlineBytes = Data("ATTACHMENT-SENTINEL".utf8).base64EncodedString()
        let frame = Data("FRAME-SENTINEL".utf8).base64EncodedString()

        var state = CompanionState()
        state.bots = [bot("scout", threadId: "main")]
        var screen = message("screen", at: 2, role: .bot, kind: .screen)
        screen.png = screenshot
        screen.mime = "image/png"
        var reply = message("reply", at: 3, role: .bot)
        reply.attachments = [
            MessageImageAttachment(kind: "file", path: "report-1.pdf", mime: "application/pdf", name: "Q3 report.pdf"),
            MessageImageAttachment(kind: "image", path: "data:image/png;base64,\(inlineBytes)", mime: "image/png"),
        ]
        state.merge(page([message("ask", at: 1), screen, reply]), intoThread: "main")
        state.cursor = "CURSOR-SENTINEL:42"
        state.screens["scout"] = ScreenFrame(png: frame, mime: "image/png")
        state.liveCall = LiveCallState(
            callId: "CALL-SENTINEL", botId: "scout", threadId: "main", client: "ios",
            voice: "VOICE-SENTINEL", startedAt: 1, status: .live
        )
        state.streaming["main"] = "STREAMING-SENTINEL"
        state.reasoning["main"] = "REASONING-SENTINEL"
        state.pendingEdits["main"] = PendingEdit(sourceId: "ask", text: "EDIT-SENTINEL")
        state.rememberQueued(QueuedSend(queueId: "QUEUE-SENTINEL", text: "HELD-SENTINEL"), threadId: "main")
        state.notifications = [NotificationFrame(
            kind: "done", botId: "scout", botName: "Scout", threadId: "main", title: "NOTIFY-SENTINEL", body: ""
        )]

        let snapshot = try XCTUnwrap(state.offlineSnapshot(
            connectionId: connection.id, serverEnvironmentId: connection.serverEnvironmentId, savedAt: savedAt
        ))
        let text = try XCTUnwrap(String(data: try snapshot.encoded(), encoding: .utf8))
        for sentinel in [
            token, "host-sentinel", "PUBLICKEY-SENTINEL", "DEVICE-SENTINEL",
            screenshot, inlineBytes, frame, "SENTINEL:42", "CALL-SENTINEL", "VOICE-SENTINEL",
            "STREAMING-SENTINEL", "REASONING-SENTINEL", "EDIT-SENTINEL", "QUEUE-SENTINEL", "HELD-SENTINEL",
            "NOTIFY-SENTINEL", "\"png\"", "cursor", "liveCall",
        ] {
            XCTAssertFalse(text.contains(sentinel), "\(sentinel) reached the snapshot")
        }
        // Names and metadata stay, so the file card still renders.
        XCTAssertTrue(text.contains("Q3 report.pdf"))
        XCTAssertTrue(text.contains("report-1.pdf"))

        let rebuilt = CompanionState(snapshot: snapshot)
        let transcript = rebuilt.transcript(forThread: "main")
        let cachedScreen = try XCTUnwrap(transcript.first { $0.id == "screen" })
        XCTAssertNil(cachedScreen.png)
        XCTAssertEqual(cachedScreen.mime, "image/png")
        let attachments = try XCTUnwrap(transcript.first { $0.id == "reply" }?.attachments)
        XCTAssertEqual(attachments.map(\.kind), ["file", "image"])
        XCTAssertEqual(attachments.map(\.path), ["report-1.pdf", nil])
        XCTAssertEqual(rebuilt.transcript(forThread: "main").first { $0.id == "reply" }?.attachedFiles.map(\.name), ["Q3 report.pdf"])
        XCTAssertNil(rebuilt.cursor)
        XCTAssertNil(rebuilt.liveCall)
        XCTAssertTrue(rebuilt.screens.isEmpty)
        XCTAssertTrue(rebuilt.streaming.isEmpty)
        XCTAssertTrue(rebuilt.reasoning.isEmpty)
        XCTAssertTrue(rebuilt.pendingEdits.isEmpty)
        XCTAssertTrue(rebuilt.pendingQueued.isEmpty)
        XCTAssertTrue(rebuilt.notifications.isEmpty)
    }

    // MARK: - Cached, then live

    func testACachedStateIsNeverWrittenBackAsANewSync() throws {
        let cached = CompanionState(snapshot: try liveSnapshot())
        XCTAssertNil(cached.offlineSnapshot(connectionId: "computer-1", serverEnvironmentId: nil, savedAt: savedAt.addingTimeInterval(60)))
    }

    func testACachedStateHasNoCursorAndCannotAdvanceOne() throws {
        var cached = CompanionState(snapshot: try liveSnapshot())
        XCTAssertNil(cached.cursor)
        cached.advance(to: 7)
        XCTAssertNil(cached.cursor, "Only a cold hydrate's cursor can start the stream.")
    }

    func testHydrateReplacesTheCachedStateWholesaleAndMakesItLiveAgain() throws {
        var cached = CompanionState(snapshot: try liveSnapshot())
        XCTAssertTrue(cached.isCached)
        XCTAssertNotNil(cached.messages["ghost-main"])

        let fleet = try fixtureFleet("bots-paged")
        XCTAssertTrue(cached.hydrate(fleet, ifCursorMatches: nil), "No cursor: the cold hydrate always applies.")
        XCTAssertFalse(cached.isCached)
        XCTAssertNil(cached.cachedAt)
        XCTAssertNil(cached.cursor, "Hydrate never invents a cursor; Session commits the hello's.")
        XCTAssertEqual(cached.bots.map(\.id), fleet.bots.map(\.id))
        XCTAssertNil(cached.messages["ghost-main"], "Nothing cached merges into the live state.")
        XCTAssertNotNil(cached.offlineSnapshot(connectionId: "computer-1", serverEnvironmentId: nil, savedAt: savedAt))
    }

    // MARK: - Helpers

    private func liveSnapshot() throws -> StateSnapshot {
        var state = CompanionState()
        state.bots = [bot("ghost", threadId: "ghost-main")]
        state.merge(page([message("g1", at: 1)]), intoThread: "ghost-main")
        state.cursor = "stream:3"
        return try XCTUnwrap(state.offlineSnapshot(connectionId: "computer-1", serverEnvironmentId: nil, savedAt: savedAt))
    }

    private func fixtureFleet(_ name: String) throws -> Fleet {
        let url = try XCTUnwrap(
            Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures")
                ?? Bundle.module.url(forResource: name, withExtension: "json")
        )
        return try JSONDecoder().decode(Fleet.self, from: try Data(contentsOf: url))
    }

    private func bot(_ id: String, threadId: String, tasks: [BotTask]? = nil) -> Bot {
        Bot(
            id: id, threadId: threadId, name: id.capitalized, title: "Researcher",
            description: "", notifications: true, color: "green", unread: false,
            modelSelection: ModelSelection(instanceId: "engine", model: "default"), createdAt: 0,
            tasks: tasks
        )
    }

    private func task(
        _ threadId: String, title: String? = nil, updatedAt: Double,
        unread: Bool? = nil, pinned: Bool? = nil
    ) -> BotTask {
        var task = BotTask(threadId: threadId, title: title ?? threadId, createdAt: 0)
        task.updatedAt = updatedAt
        task.unread = unread
        task.pinned = pinned
        return task
    }

    private func message(
        _ id: String, at: Double, parentId: String? = nil,
        role: Message.Role = .user, kind: Message.Kind = .text, text: String = "hello"
    ) -> Message {
        var message = Message(id: id, role: role, kind: kind, at: at)
        message.text = text
        message.parentId = parentId
        return message
    }

    private func page(_ messages: [Message], hasMore: Bool = false) -> ThreadPage {
        ThreadPage(messages: messages, hasMore: hasMore)
    }

    private func routine(_ id: String) -> Routine {
        Routine(
            id: id, name: "Brief", prompt: "Summarize", botId: "scout", runOn: "maus", enabled: true,
            schedule: .daily(time: "09:00", weekdays: [1, 2, 3, 4, 5]), durationMinutes: 30,
            timeoutMinutes: nil, nextRunAt: 2_000, createdAt: 1, updatedAt: 1
        )
    }

    private func run(_ id: String, routineId: String, scheduledFor: Double) -> RoutineRun {
        RoutineRun(
            id: id, routineId: routineId, routineName: "Brief", botId: "scout", runOn: "maus",
            scheduledFor: scheduledFor, status: "completed", manual: false, createdAt: scheduledFor
        )
    }
}
