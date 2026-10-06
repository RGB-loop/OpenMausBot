import Foundation
import XCTest
@testable import CompanionCore

/// Counts what reaches the wire; answers every request with an empty fleet.
private final class GateRequestStub: URLProtocol {
    private static let lock = NSLock()
    nonisolated(unsafe) private static var seen: [String] = []

    static var requests: [String] {
        lock.lock()
        defer { lock.unlock() }
        return seen
    }

    static func reset() {
        lock.lock()
        seen = []
        lock.unlock()
    }

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lock.lock()
        Self.seen.append("\(request.httpMethod ?? "GET") \(request.url?.path ?? "")")
        Self.lock.unlock()
        let response = HTTPURLResponse(
            url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(#"{"bots":[],"groups":[],"hits":[]}"#.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}
}

/// While the phone shows its last sync (MOCA-296) nothing but a read may
/// leave it, whichever path asks.
final class OfflineWriteGateTests: XCTestCase {
    private var session: URLSession!
    private var gate: OfflineWriteGate!
    private var client: CompanionClient!

    override func setUp() {
        super.setUp()
        GateRequestStub.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [GateRequestStub.self]
        session = URLSession(configuration: configuration)
        gate = OfflineWriteGate()
        client = CompanionClient(
            connection: Connection(name: "Mac", host: "127.0.0.1", port: 8810),
            token: "paired-token",
            session: session,
            writeGate: gate
        )
    }

    override func tearDown() {
        session.invalidateAndCancel()
        session = nil
        client = nil
        gate = nil
        super.tearDown()
    }

    func testAShutGateRefusesEveryChangeBeforeItIsSent() async throws {
        gate.set(shut: true)
        let card = try JSONDecoder().decode(
            OptionCard.self,
            from: Data(#"{"title":"Run tests?","subtitle":"","options":["Allow","Deny"],"requestId":"req-1"}"#.utf8)
        )
        var refused = 0
        func expectRefusal(_ name: String, _ call: () async throws -> Void) async {
            do {
                try await call()
                XCTFail("\(name) went through a shut gate")
            } catch let APIError.transport(message) {
                XCTAssertEqual(message, OfflineWriteGate.refusal, name)
                refused += 1
            } catch {
                XCTFail("\(name): \(error)")
            }
        }
        await expectRefusal("send") { _ = try await self.client.send(text: "hi", toBot: "scout") }
        await expectRefusal("respond") {
            _ = try await self.client.respond(threadId: "t1", requestId: card.requestId!, behavior: "allow")
        }
        await expectRefusal("interrupt") { try await self.client.interrupt(botId: "scout") }
        await expectRefusal("run routine") { _ = try await self.client.runRoutine(id: "r1") }
        await expectRefusal("delete routine") { try await self.client.deleteRoutine(id: "r1") }
        await expectRefusal("rename thread") { try await self.client.renameTask(botId: "scout", threadId: "t1", title: "x") }
        await expectRefusal("mark read") { try await self.client.markRead(botId: "scout") }
        XCTAssertEqual(refused, 7)
        XCTAssertEqual(GateRequestStub.requests, [], "Nothing reached the wire.")
    }

    func testAShutGateStillLetsReadsThrough() async throws {
        gate.set(shut: true)
        _ = try await client.fleet()
        _ = try await client.search("flights")
        XCTAssertEqual(GateRequestStub.requests, ["GET /api/bots", "GET /api/search"])
    }

    func testAnOpenGateSendsChanges() async throws {
        gate.set(shut: true)
        gate.set(shut: false)
        try await client.interrupt(botId: "scout")
        XCTAssertEqual(GateRequestStub.requests, ["POST /api/bots/scout/interrupt"])
    }

    func testAClientWithoutAGateIsUnchanged() async throws {
        let ungated = CompanionClient(
            connection: Connection(name: "Mac", host: "127.0.0.1", port: 8810),
            token: "paired-token",
            session: session
        )
        try await ungated.interrupt(botId: "scout")
        XCTAssertEqual(GateRequestStub.requests, ["POST /api/bots/scout/interrupt"])
    }
}
