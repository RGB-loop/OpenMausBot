// Where the offline snapshot lives (MOCA-296): one file per paired computer,
// `Application Support/snapshots/<connectionID>.json`.
//
// The app saves at most every few seconds after a hydrate or a batch, so
// the store's one job beyond reading and writing is staying out of the way:
// every write and read runs on its own serial queue, never the caller's
// thread, and a burst of saves collapses into one write of the newest. The
// 1.3.0 freeze was main-thread work; this must not add any.
//
// The file is protected until the first unlock — a notification can wake
// the app before then, and the snapshot should not be what fails — and kept
// out of device backups: it is a cache of another machine's data, and the
// next hydrate rebuilds it anyway.
import Foundation

/// The disk, as far as the store needs one. Injectable so tests can count
/// and fail operations without touching real file protection.
public protocol SnapshotFileSystem: Sendable {
    /// The bytes at `url`. Throws when there is no file or it cannot be read
    /// yet — before the first unlock, for instance.
    func read(_ url: URL) throws -> Data
    /// Replaces `url` in one step, creating its directory if needed, so a
    /// reader never sees half a file.
    func write(_ data: Data, to url: URL) throws
    /// Removes `url`. A file that does not exist is not an error.
    func remove(_ url: URL) throws
    /// Every entry directly inside `directory`; empty when it does not exist.
    func files(in directory: URL) throws -> [URL]
}

/// The device's disk: atomic writes, protected until first unlock, and
/// excluded from backup.
public struct ProtectedSnapshotFileSystem: SnapshotFileSystem {
    public init() {}

    public func read(_ url: URL) throws -> Data {
        try Data(contentsOf: url)
    }

    public func write(_ data: Data, to url: URL) throws {
        let manager = FileManager.default
        var directory = url.deletingLastPathComponent()
        try manager.createDirectory(
            at: directory,
            withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        try Self.excludeFromBackup(&directory)
        // `.atomic` writes a sibling and renames it over the old file.
        try data.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        // The rename made a new file, so the exclusion is set again every
        // time. A snapshot that could reach a backup is not kept.
        var file = url
        do {
            try Self.excludeFromBackup(&file)
        } catch {
            try? manager.removeItem(at: url)
            throw error
        }
    }

    public func remove(_ url: URL) throws {
        do {
            try FileManager.default.removeItem(at: url)
        } catch let error as CocoaError where error.code == .fileNoSuchFile {
            return
        }
    }

    public func files(in directory: URL) throws -> [URL] {
        guard FileManager.default.fileExists(atPath: directory.path) else { return [] }
        return try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
    }

    private static func excludeFromBackup(_ url: inout URL) throws {
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try url.setResourceValues(values)
    }
}

/// Saves, loads and wipes offline snapshots, one file per connection.
///
/// Saves are fire-and-forget and coalescing: each takes a ticket, and a
/// queued write runs only while its ticket is still the newest for that
/// computer. A wipe takes a ticket too, so a save that was already waiting
/// can never put a forgotten computer's data back. Loads run on the same
/// queue, after everything requested before them.
public final class SnapshotStore: @unchecked Sendable {
    public let directory: URL
    private let fileSystem: any SnapshotFileSystem
    private let queue: DispatchQueue
    private let lock = NSLock()
    // Guarded by `lock`.
    private var lastTicket = 0
    private var newestTicket: [String: Int] = [:]

    /// `queue` must be serial; it is injectable so tests can hold it.
    public init(
        directory: URL,
        fileSystem: any SnapshotFileSystem = ProtectedSnapshotFileSystem(),
        queue: DispatchQueue = DispatchQueue(label: "com.openmausbot.offline-snapshot", qos: .utility)
    ) {
        self.directory = directory
        self.fileSystem = fileSystem
        self.queue = queue
    }

    /// `Application Support/snapshots` — the directory the app hands the
    /// store. Tests pass a temporary one instead.
    public static func defaultDirectory(fileManager: FileManager = .default) throws -> URL {
        try fileManager
            .url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("snapshots", isDirectory: true)
    }

    public func fileURL(forConnection connectionId: String) -> URL {
        directory.appendingPathComponent(Self.fileName(forConnection: connectionId), isDirectory: false)
    }

    /// Connection ids are UUIDs, but the name is built so that no id, however
    /// spelled, can point outside the directory.
    static func fileName(forConnection connectionId: String) -> String {
        let safe = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        let name = connectionId.addingPercentEncoding(withAllowedCharacters: safe)
            ?? connectionId.utf8.map { String(format: "%02x", $0) }.joined()
        return name + ".json"
    }

    // MARK: - Saving

    /// Write `snapshot` soon, unless a newer save or a wipe for the same
    /// computer arrives first. It is encoded on the store's queue.
    public func save(_ snapshot: StateSnapshot) {
        enqueue(connectionId: snapshot.connectionId) { try? snapshot.encoded() }
    }

    /// Build and write the snapshot of `state` on the store's queue, so the
    /// caller pays for a value copy and nothing else. The bytes written are
    /// the ones the cap was measured on, not a second encoding. A cached
    /// state builds nothing and so writes nothing.
    public func save(
        _ state: CompanionState,
        connectionId: String,
        serverEnvironmentId: String?,
        savedAt: Date = Date(),
        routines: [Routine] = [],
        routineRuns: [RoutineRun] = [],
        limits: StateSnapshot.Limits = .standard
    ) {
        enqueue(connectionId: connectionId) {
            state.encodedOfflineSnapshot(
                connectionId: connectionId,
                serverEnvironmentId: serverEnvironmentId,
                savedAt: savedAt,
                routines: routines,
                routineRuns: routineRuns,
                limits: limits
            )?.data
        }
    }

    /// `encode` runs on the queue and returns the file for `connectionId`,
    /// or nil to write nothing.
    private func enqueue(connectionId: String, _ encode: @escaping @Sendable () -> Data?) {
        let ticket = takeTicket(for: [connectionId])
        queue.async { [self] in
            guard isNewest(ticket, for: connectionId),
                  let data = encode(),
                  // Building can take a moment; a newer request may have
                  // arrived meanwhile, and its write is the one that counts.
                  isNewest(ticket, for: connectionId)
            else { return }
            // A failed write leaves the previous file; the next save retries.
            try? fileSystem.write(data, to: fileURL(forConnection: connectionId))
        }
    }

    // MARK: - Loading

    /// The saved copy for this computer, decoded off the caller's thread, or
    /// nil when there is none to show. A file written under another schema
    /// version, for another connection id or for another server identity —
    /// or one that does not decode — is refused and removed. A file that
    /// cannot be read yet is left alone: before the first unlock it is
    /// fine, just locked.
    public func load(connectionId: String, serverEnvironmentId: String?) async -> StateSnapshot? {
        await withCheckedContinuation { continuation in
            queue.async { [self] in
                continuation.resume(returning: loadNow(connectionId: connectionId, serverEnvironmentId: serverEnvironmentId))
            }
        }
    }

    private func loadNow(connectionId: String, serverEnvironmentId: String?) -> StateSnapshot? {
        let url = fileURL(forConnection: connectionId)
        guard let data = try? fileSystem.read(url) else { return nil }
        guard let snapshot = try? StateSnapshot.decoded(from: data),
              snapshot.schemaVersion == StateSnapshot.currentSchemaVersion,
              snapshot.connectionId == connectionId,
              snapshot.serverEnvironmentId == serverEnvironmentId
        else {
            try? fileSystem.remove(url)
            return nil
        }
        return snapshot
    }

    // MARK: - Wiping

    /// Forget one computer's copy: forget computer, pair again, a 401 that
    /// unpaired this phone, a changed server identity. Saves still waiting
    /// for it are dropped.
    public func wipe(connectionId: String) {
        _ = takeTicket(for: [connectionId])
        let url = fileURL(forConnection: connectionId)
        queue.async { [fileSystem] in
            try? fileSystem.remove(url)
        }
    }

    /// Forget every computer's copy, as sign-out does.
    public func wipeAll() {
        lock.lock()
        let known = Array(newestTicket.keys)
        lock.unlock()
        _ = takeTicket(for: known)
        queue.async { [fileSystem, directory] in
            for url in (try? fileSystem.files(in: directory)) ?? [] {
                try? fileSystem.remove(url)
            }
        }
    }

    /// Returns once everything requested before it has finished — for the
    /// app going to the background, and for tests.
    public func flush() async {
        await withCheckedContinuation { continuation in
            queue.async { continuation.resume() }
        }
    }

    // MARK: - Tickets

    private func takeTicket(for connectionIds: [String]) -> Int {
        lock.lock()
        defer { lock.unlock() }
        lastTicket += 1
        for connectionId in connectionIds {
            newestTicket[connectionId] = lastTicket
        }
        return lastTicket
    }

    private func isNewest(_ ticket: Int, for connectionId: String) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return newestTicket[connectionId] == ticket
    }
}
