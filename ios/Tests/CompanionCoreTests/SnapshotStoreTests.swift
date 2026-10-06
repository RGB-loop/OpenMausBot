// The offline snapshot on disk (MOCA-296): one file per computer, written
// off the caller's thread, coalesced, refused when it belongs to another
// version, connection or server, and gone when the computer is forgotten.
import XCTest
@testable import CompanionCore

final class SnapshotStoreTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("offline-snapshot-\(UUID().uuidString)", isDirectory: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    // MARK: - Saving and loading

    func testSaveThenLoadRoundTripsOneFilePerComputer() async throws {
        let store = SnapshotStore(directory: directory)
        let first = snapshot("computer-1", environment: "env-1")
        let second = snapshot("computer-2", at: 2)
        store.save(first)
        store.save(second)

        let loadedFirst = await store.load(connectionId: "computer-1", serverEnvironmentId: "env-1")
        let loadedSecond = await store.load(connectionId: "computer-2", serverEnvironmentId: nil)
        XCTAssertEqual(loadedFirst, first)
        XCTAssertEqual(loadedSecond, second)
        XCTAssertEqual(Set(try files()), ["computer-1.json", "computer-2.json"], "Atomic writes leave no stray temp files.")
        let missing = await store.load(connectionId: "computer-3", serverEnvironmentId: nil)
        XCTAssertNil(missing, "No file is no cache — today's first-run behaviour.")
    }

    func testTheFileIsExcludedFromBackup() async throws {
        let store = SnapshotStore(directory: directory)
        store.save(snapshot("computer-1"))
        await store.flush()
        let file = store.fileURL(forConnection: "computer-1")
        XCTAssertEqual(try file.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
        XCTAssertEqual(try directory.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)

        // Every write replaces the file, so the exclusion is set again each time.
        store.save(snapshot("computer-1", at: 2))
        await store.flush()
        XCTAssertEqual(try file.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup, true)
    }

    func testTheDefaultLocationIsApplicationSupportSnapshots() throws {
        let url = try SnapshotStore.defaultDirectory()
        XCTAssertEqual(url.lastPathComponent, "snapshots")
        XCTAssertEqual(url.deletingLastPathComponent().lastPathComponent, "Application Support")
    }

    func testSavingAStateBuildsItsSnapshotAndACachedStateWritesNothing() async throws {
        let files = RecordingFileSystem()
        let store = SnapshotStore(directory: directory, fileSystem: files)
        var state = CompanionState()
        state.bots = [Self.bot]
        state.cursor = "stream:4"
        store.save(state, connectionId: "computer-1", serverEnvironmentId: nil, savedAt: Self.savedAt)
        let loaded = await store.load(connectionId: "computer-1", serverEnvironmentId: nil)
        let saved = try XCTUnwrap(loaded)
        XCTAssertEqual(saved.bots.map(\.id), ["scout"])
        XCTAssertEqual(saved.savedAt, Self.savedAt)

        store.save(CompanionState(snapshot: saved), connectionId: "computer-1", serverEnvironmentId: nil,
                   savedAt: Self.savedAt.addingTimeInterval(60))
        await store.flush()
        XCTAssertEqual(files.writes.count, 1, "A cached state is never written back as a new sync.")
        let reloaded = await store.load(connectionId: "computer-1", serverEnvironmentId: nil)
        XCTAssertEqual(reloaded?.savedAt, Self.savedAt)
    }

    func testASnapshotMadeByHandStillCarriesNoRecordTranscriptToDisk() async throws {
        let store = SnapshotStore(directory: directory)
        let pixels = Data(String(repeating: "RECORD-PIXELS-", count: 40).utf8).base64EncodedString()
        func shot(_ id: String) -> Message {
            var message = Message(id: id, role: .bot, kind: .screen, at: 1)
            message.png = pixels
            return message
        }
        // A hydrated roster: every record still holds its own page of messages.
        var scout = Self.bot
        scout.messages = [shot("bot-shot")]
        scout.hasMore = true
        var room = Room(
            id: "team", threadId: "team-main", name: "Team", memberIds: ["scout"],
            defaultResponder: GroupResponder(kind: "all"), bulletin: "", unread: false, createdAt: 0
        )
        room.messages = [shot("room-shot")]
        room.hasMore = true

        // The only way onto a snapshot is the roster row, which has no field for them.
        var made = snapshot("computer-1")
        made.bots = [.init(scout)]
        made.rooms = [.init(room)]
        store.save(made)
        await store.flush()

        let data = try Data(contentsOf: store.fileURL(forConnection: "computer-1"))
        let text = String(decoding: data, as: UTF8.self)
        for needle in [pixels, "bot-shot", "room-shot"] {
            XCTAssertFalse(text.contains(needle), "found \(needle)")
        }
        let file = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let rows = try XCTUnwrap(file["bots"] as? [[String: Any]]) + XCTUnwrap(file["rooms"] as? [[String: Any]])
        XCTAssertEqual(rows.count, 2)
        for row in rows {
            XCTAssertNil(row["messages"], "\(row)")
            XCTAssertNil(row["hasMore"], "\(row)")
        }
    }

    // MARK: - Refusing a file

    func testASchemaVersionMismatchIsRefusedAndRemoved() async throws {
        let store = SnapshotStore(directory: directory)
        var future = snapshot("computer-1")
        future.schemaVersion = StateSnapshot.currentSchemaVersion + 1
        store.save(future)
        let loaded = await store.load(connectionId: "computer-1", serverEnvironmentId: nil)
        XCTAssertNil(loaded)
        XCTAssertEqual(try files(), [], "A cache this build cannot use is not kept.")
    }

    func testAConnectionIdMismatchIsRefused() async throws {
        let store = SnapshotStore(directory: directory)
        // Another computer's copy under this computer's name.
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try snapshot("computer-2").encoded().write(to: store.fileURL(forConnection: "computer-1"))
        let loaded = await store.load(connectionId: "computer-1", serverEnvironmentId: nil)
        XCTAssertNil(loaded)
        XCTAssertEqual(try files(), [])
    }

    func testAServerIdentityChangeIsRefused() async throws {
        let store = SnapshotStore(directory: directory)
        store.save(snapshot("computer-1", environment: "env-1"))
        let elsewhere = await store.load(connectionId: "computer-1", serverEnvironmentId: "env-2")
        XCTAssertNil(elsewhere, "A different environment at the same address is a different computer.")
        let afterwards = await store.load(connectionId: "computer-1", serverEnvironmentId: "env-1")
        XCTAssertNil(afterwards, "The refused copy is gone, not waiting to be shown later.")
    }

    func testAnUndecodableFileIsRefused() async throws {
        let store = SnapshotStore(directory: directory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        try Data("not a snapshot".utf8).write(to: store.fileURL(forConnection: "computer-1"))
        let loaded = await store.load(connectionId: "computer-1", serverEnvironmentId: nil)
        XCTAssertNil(loaded)
        XCTAssertEqual(try files(), [])
    }

    func testAFileThatCannotBeReadYetIsLeftAlone() async throws {
        // Before the first unlock a protected file exists but cannot be read.
        let files = RecordingFileSystem()
        let store = SnapshotStore(directory: directory, fileSystem: files)
        store.save(snapshot("computer-1"))
        await store.flush()
        files.failReads = true
        let locked = await store.load(connectionId: "computer-1", serverEnvironmentId: nil)
        XCTAssertNil(locked)
        XCTAssertTrue(files.removals.isEmpty)
        files.failReads = false
        let unlocked = await store.load(connectionId: "computer-1", serverEnvironmentId: nil)
        XCTAssertEqual(unlocked, snapshot("computer-1"))
    }

    // MARK: - Wiping

    func testWipeForgetsOneComputerAndWipeAllForgetsEvery() async throws {
        let store = SnapshotStore(directory: directory)
        store.save(snapshot("computer-1"))
        store.save(snapshot("computer-2"))
        store.wipe(connectionId: "computer-1")
        let wiped = await store.load(connectionId: "computer-1", serverEnvironmentId: nil)
        let kept = await store.load(connectionId: "computer-2", serverEnvironmentId: nil)
        XCTAssertNil(wiped)
        XCTAssertEqual(kept, snapshot("computer-2"))

        store.save(snapshot("computer-3"))
        store.wipeAll()
        await store.flush()
        XCTAssertEqual(try files(), [])
        let afterAll = await store.load(connectionId: "computer-2", serverEnvironmentId: nil)
        XCTAssertNil(afterAll)

        // Wiping nothing is not an error.
        store.wipe(connectionId: "never-paired")
        store.wipeAll()
        await store.flush()
    }

    func testAWipeDropsASaveThatWasStillWaiting() async throws {
        let queue = DispatchQueue(label: "snapshot-test")
        let files = RecordingFileSystem()
        let store = SnapshotStore(directory: directory, fileSystem: files, queue: queue)
        queue.suspend()
        store.save(snapshot("computer-1"))
        store.save(snapshot("computer-2"))
        store.wipe(connectionId: "computer-1")
        store.wipeAll()
        queue.resume()
        await store.flush()
        XCTAssertTrue(files.writes.isEmpty, "A forgotten computer's data never comes back from the queue.")
        XCTAssertEqual(try self.files(), [])
    }

    func testASaveAfterAWipeStillLands() async throws {
        let queue = DispatchQueue(label: "snapshot-test")
        let store = SnapshotStore(directory: directory, queue: queue)
        queue.suspend()
        store.save(snapshot("computer-1", at: 1))
        store.wipe(connectionId: "computer-1")
        store.save(snapshot("computer-1", at: 2))
        queue.resume()
        let loaded = await store.load(connectionId: "computer-1", serverEnvironmentId: nil)
        XCTAssertEqual(loaded, snapshot("computer-1", at: 2), "Pair again, then sync: the new copy stays.")
    }

    // MARK: - Staying off the caller's thread

    func testABurstOfSavesCoalescesIntoOneWriteOfTheNewest() async throws {
        let queue = DispatchQueue(label: "snapshot-test")
        let files = RecordingFileSystem()
        let store = SnapshotStore(directory: directory, fileSystem: files, queue: queue)
        queue.suspend()
        for second in 1...5 {
            store.save(snapshot("computer-1", at: Double(second)))
        }
        store.save(snapshot("computer-2", at: 9))
        queue.resume()
        await store.flush()

        XCTAssertEqual(files.writes.map(\.lastPathComponent).sorted(), ["computer-1.json", "computer-2.json"])
        let latest = await store.load(connectionId: "computer-1", serverEnvironmentId: nil)
        XCTAssertEqual(latest, snapshot("computer-1", at: 5))
    }

    func testWritesAndReadsNeverRunOnTheCallersThread() throws {
        XCTAssertTrue(Thread.isMainThread)
        let files = RecordingFileSystem()
        let store = SnapshotStore(directory: directory, fileSystem: files)
        var state = CompanionState()
        state.bots = [Self.bot]
        store.save(state, connectionId: "computer-1", serverEnvironmentId: nil, savedAt: Self.savedAt)
        let loaded = expectation(description: "loaded off the main thread")
        Task {
            let snapshot = await store.load(connectionId: "computer-1", serverEnvironmentId: nil)
            XCTAssertEqual(snapshot?.bots.map(\.id), ["scout"])
            loaded.fulfill()
        }
        wait(for: [loaded], timeout: 10)
        XCTAssertEqual(files.writes.count, 1)
        XCTAssertEqual(files.reads.count, 1)
        XCTAssertFalse(files.touchedMainThread)
    }

    // MARK: - File names

    func testNoConnectionIdCanNameAFileOutsideTheDirectory() async throws {
        let store = SnapshotStore(directory: directory)
        for id in ["../../escape", "a/b", "..", "", "spaces and ünicode"] {
            let url = store.fileURL(forConnection: id)
            XCTAssertEqual(url.deletingLastPathComponent().standardizedFileURL.path, directory.standardizedFileURL.path, id)
            XCTAssertFalse(url.lastPathComponent.contains("/"), id)
        }
        store.save(snapshot("../../escape"))
        let loaded = await store.load(connectionId: "../../escape", serverEnvironmentId: nil)
        XCTAssertEqual(loaded?.connectionId, "../../escape")
        XCTAssertEqual(try files().count, 1)
    }

    // MARK: - Helpers

    private static let savedAt = Date(timeIntervalSince1970: 1_700_000_000)

    private static let bot = Bot(
        id: "scout", threadId: "scout-main", name: "Scout", title: "Researcher",
        description: "", notifications: true, color: "green", unread: false,
        modelSelection: ModelSelection(instanceId: "engine", model: "default"), createdAt: 0
    )

    private func snapshot(_ connectionId: String, environment: String? = nil, at seconds: Double = 0) -> StateSnapshot {
        var message = Message(id: "m1", role: .user, kind: .text, at: 1)
        message.text = "hello"
        return StateSnapshot(
            schemaVersion: StateSnapshot.currentSchemaVersion,
            connectionId: connectionId,
            serverEnvironmentId: environment,
            savedAt: Self.savedAt.addingTimeInterval(seconds),
            bots: [.init(Self.bot)],
            rooms: [],
            threads: ["scout-main": .init(messages: [.init(message)], hasMore: false, activeLeafId: "m1")],
            routines: [],
            routineRuns: []
        )
    }

    private func files() throws -> [String] {
        guard FileManager.default.fileExists(atPath: directory.path) else { return [] }
        return try FileManager.default.contentsOfDirectory(atPath: directory.path)
    }
}

/// The real disk, with every operation counted and reads that can be made
/// to fail the way a protected file does before the first unlock.
private final class RecordingFileSystem: SnapshotFileSystem, @unchecked Sendable {
    private let disk = ProtectedSnapshotFileSystem()
    private let lock = NSLock()
    private var _writes: [URL] = []
    private var _reads: [URL] = []
    private var _removals: [URL] = []
    private var _touchedMainThread = false
    private var _failReads = false

    var writes: [URL] { locked { _writes } }
    var reads: [URL] { locked { _reads } }
    var removals: [URL] { locked { _removals } }
    var touchedMainThread: Bool { locked { _touchedMainThread } }
    var failReads: Bool {
        get { locked { _failReads } }
        set { locked { _failReads = newValue } }
    }

    func read(_ url: URL) throws -> Data {
        let fail = locked { () -> Bool in
            _reads.append(url)
            _touchedMainThread = _touchedMainThread || Thread.isMainThread
            return _failReads
        }
        if fail { throw CocoaError(.fileReadNoPermission) }
        return try disk.read(url)
    }

    func write(_ data: Data, to url: URL) throws {
        locked {
            _writes.append(url)
            _touchedMainThread = _touchedMainThread || Thread.isMainThread
        }
        try disk.write(data, to: url)
    }

    func remove(_ url: URL) throws {
        locked {
            _removals.append(url)
            _touchedMainThread = _touchedMainThread || Thread.isMainThread
        }
        try disk.remove(url)
    }

    func files(in directory: URL) throws -> [URL] {
        try disk.files(in: directory)
    }

    private func locked<T>(_ body: () throws -> T) rethrows -> T {
        lock.lock()
        defer { lock.unlock() }
        return try body()
    }
}
