import Foundation
import XCTest
@testable import CompanionCore

private final class ConnectorCardStub: URLProtocol {
    static var responseBody = Data()
    static var statusCode = 200
    static var captured: [URLRequest] = []
    static var capturedBodies: [Data?] = []

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.captured.append(request)
        Self.capturedBodies.append(Self.readBody(from: request))
        let response = HTTPURLResponse(
            url: request.url!, statusCode: Self.statusCode, httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/json"]
        )!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Self.responseBody)
        client?.urlProtocolDidFinishLoading(self)
    }

    override func stopLoading() {}

    private static func readBody(from request: URLRequest) -> Data? {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return nil }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1_024)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count <= 0 { break }
            data.append(buffer, count: count)
        }
        return data
    }
}

final class ConnectorRequestTests: XCTestCase {
    private var session: URLSession!
    private var client: CompanionClient!

    override func setUp() {
        super.setUp()
        ConnectorCardStub.responseBody = Data("{}".utf8)
        ConnectorCardStub.statusCode = 200
        ConnectorCardStub.captured = []
        ConnectorCardStub.capturedBodies = []
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [ConnectorCardStub.self]
        session = URLSession(configuration: configuration)
        client = CompanionClient(
            connection: Connection(name: "Test", host: "127.0.0.1", port: 8810),
            token: "paired-token",
            session: session
        )
    }

    override func tearDown() {
        session?.invalidateAndCancel()
        session = nil
        client = nil
        super.tearDown()
    }

    // MARK: - Decoding

    private func fixtureMessages() throws -> [String: Message] {
        let url = try XCTUnwrap(Bundle.module.url(forResource: "connector-cards", withExtension: "json", subdirectory: "Fixtures"))
        let page = try JSONDecoder().decode(ThreadPage.self, from: Data(contentsOf: url))
        return Dictionary(uniqueKeysWithValues: page.messages.map { ($0.id, $0) })
    }

    func testDecodesConnectionCardsInEveryState() throws {
        let messages = try fixtureMessages()
        XCTAssertEqual(messages.count, 6, "one unreadable card must not lose the page")
        XCTAssertTrue(messages.values.allSatisfy { $0.kind == .connector })

        let required = try XCTUnwrap(messages["conn-required"]?.connector)
        XCTAssertEqual(required.slug, "github")
        XCTAssertEqual(required.label, "GitHub")
        XCTAssertEqual(required.status, .required)
        XCTAssertEqual(required.resumeKey, "resume-fixture-123")
        XCTAssertNil(messages["conn-required"]?.from, "a direct chat's card acts for the chat's bot")

        let authorizing = try XCTUnwrap(messages["conn-authorizing"])
        XCTAssertEqual(authorizing.connector?.status, .authorizing)
        XCTAssertEqual(authorizing.connector?.alias, "work")
        XCTAssertEqual(authorizing.from?.botId, "bot-2")
        XCTAssertEqual(messages["conn-failed"]?.connector?.error, "Connection EXPIRED")
        XCTAssertEqual(messages["conn-connected"]?.connector?.resumed, true)
        XCTAssertEqual(messages["conn-dismissed"]?.connector?.dismissed, true)
        XCTAssertEqual(messages["conn-newer"]?.connector?.status, .unknown)
    }

    func testAConnectorKindIsNoLongerUnknown() throws {
        let json = #"{"id":"c","role":"bot","kind":"connector","at":1,"connector":{"label":"GitHub"}}"#
        let message = try JSONDecoder().decode(Message.self, from: Data(json.utf8))
        XCTAssertEqual(message.kind, .connector)
        XCTAssertEqual(message.connector?.displayName, "GitHub")
        XCTAssertEqual(message.connector?.initial, "G")
        XCTAssertTrue(try XCTUnwrap(message.connector).isPending, "no status reads as still required")
    }

    // MARK: - Which buttons for which status

    func testPresentationFollowsDesktopsCard() throws {
        let messages = try fixtureMessages()
        func presentation(_ id: String) throws -> ConnectorRequestPresentation? {
            ConnectorRequestPresentation.of(try XCTUnwrap(messages[id]?.connector))
        }

        let required = try XCTUnwrap(try presentation("conn-required"))
        XCTAssertEqual(required.primary, .connect)
        XCTAssertTrue(required.offersNotNow)
        XCTAssertEqual(required.footer, .requested)
        XCTAssertTrue(required.showsSignInHint)
        XCTAssertFalse(required.showsConnectedBadge)

        let authorizing = try XCTUnwrap(try presentation("conn-authorizing"))
        XCTAssertEqual(authorizing.primary, .openAgain)
        XCTAssertTrue(authorizing.offersNotNow)
        XCTAssertEqual(authorizing.footer, .waiting)

        let failed = try XCTUnwrap(try presentation("conn-failed"))
        XCTAssertEqual(failed.primary, .tryAgain)
        XCTAssertTrue(failed.offersNotNow)

        let resumed = try XCTUnwrap(try presentation("conn-connected"))
        XCTAssertNil(resumed.primary)
        XCTAssertFalse(resumed.offersNotNow)
        XCTAssertTrue(resumed.showsContinuing)
        XCTAssertEqual(resumed.body, .resumed)
        XCTAssertEqual(resumed.footer, .readyToUse)

        var paused = try XCTUnwrap(messages["conn-connected"]?.connector)
        paused.resumed = false
        let waitingToContinue = try XCTUnwrap(ConnectorRequestPresentation.of(paused))
        XCTAssertEqual(waitingToContinue.primary, .continueTask)
        XCTAssertEqual(waitingToContinue.body, .paused)
        XCTAssertTrue(waitingToContinue.showsConnectedBadge)
        XCTAssertFalse(waitingToContinue.showsContinuing)

        XCTAssertNil(try presentation("conn-dismissed"), "a request set aside is not drawn, as on desktop")

        let newer = try XCTUnwrap(try presentation("conn-newer"))
        XCTAssertNil(newer.primary, "a state this build does not know offers nothing to tap")
        XCTAssertFalse(newer.offersNotNow)
    }

    func testPollsOnlyWhileASignInPageIsOpen() throws {
        let messages = try fixtureMessages()
        XCTAssertTrue(try XCTUnwrap(messages["conn-authorizing"]?.connector).pollsStatus)
        for id in ["conn-required", "conn-failed", "conn-connected", "conn-newer"] {
            XCTAssertFalse(try XCTUnwrap(messages[id]?.connector).pollsStatus, id)
        }
        var dismissed = try XCTUnwrap(messages["conn-authorizing"]?.connector)
        dismissed.dismissed = true
        XCTAssertFalse(dismissed.pollsStatus)
        XCTAssertEqual(ConnectorRequestPolling.maximumChecks, 75)
        XCTAssertEqual(ConnectorRequestPolling.interval, .seconds(4))
    }

    func testReusesASignInLinkForTenMinutesOnly() throws {
        let opened = Date(timeIntervalSince1970: 1_000)
        let link = ConnectorAuthorizationLink(url: try XCTUnwrap(URL(string: "https://connect.composio.dev/x")), createdAt: opened)
        XCTAssertEqual(link.reusable(now: opened), link.url)
        XCTAssertEqual(link.reusable(now: opened.addingTimeInterval(599)), link.url)
        XCTAssertNil(link.reusable(now: opened.addingTimeInterval(600)))
        XCTAssertNil(link.reusable(now: opened.addingTimeInterval(-1)), "a clock that went backwards is not trusted")
    }

    // MARK: - Owner, preview and voice

    func testActsForTheChatsBotOrTheRoomMemberThatAsked() throws {
        let messages = try fixtureMessages()
        let bot = try JSONDecoder().decode(Bot.self, from: Data("""
        {"id":"bot-1","threadId":"t1","name":"Pepper","title":"","description":"","notifications":true,
         "color":"blue","unread":false,"modelSelection":{"instanceId":"engine","model":"default"},"createdAt":1}
        """.utf8))
        let room = try JSONDecoder().decode(Room.self, from: Data("""
        {"id":"room-1","threadId":"rt","name":"Team","memberIds":["bot-2"],
         "defaultResponder":{"kind":"first"},"bulletin":"","unread":false,"createdAt":1}
        """.utf8))
        XCTAssertEqual(Chat.bot(bot).connectorOwner(of: try XCTUnwrap(messages["conn-required"])), "bot-1")
        XCTAssertEqual(Chat.room(room).connectorOwner(of: try XCTUnwrap(messages["conn-authorizing"])), "bot-2")
        XCTAssertNil(Chat.room(room).connectorOwner(of: try XCTUnwrap(messages["conn-required"])), "never guess a member")
    }

    func testRosterAndWalkieSayWhatTheBotIsWaitingOn() throws {
        let messages = try fixtureMessages()
        XCTAssertEqual(previewText(of: try XCTUnwrap(messages["conn-required"])), "Connect GitHub to continue.")
        XCTAssertEqual(previewText(of: try XCTUnwrap(messages["conn-connected"])), "Linear")
        XCTAssertEqual(previewText(of: try XCTUnwrap(messages["conn-newer"])), "Jira")

        let required = try XCTUnwrap(messages["conn-required"])
        XCTAssertEqual(
            Walkie.settledReply(transcript: [required], baseline: [], busy: false),
            "It needs you to connect GitHub. Open the chat to connect it."
        )
        let connected = try XCTUnwrap(messages["conn-connected"])
        XCTAssertNil(Walkie.settledReply(transcript: [connected], baseline: [], busy: false))
        XCTAssertEqual(
            Walkie.settledReply(transcript: [required], baseline: [], busy: true),
            "It needs you to connect GitHub. Open the chat to connect it."
        )
        XCTAssertNil(Walkie.settledReply(transcript: [connected], baseline: [], busy: true))
        XCTAssertNil(Walkie.settledReply(transcript: [required], baseline: [required.id], busy: true))
    }

    // MARK: - Requests

    func testBuildsOnlyRoutesTheComputerWouldMatch() {
        XCTAssertEqual(
            ConnectorRequestAction.authorize.path(botId: "bot_1-a", messageId: "m-2"),
            "/api/bots/bot_1-a/connector-cards/m-2/authorize"
        )
        XCTAssertEqual(ConnectorRequestAction.status.method, "GET")
        XCTAssertEqual(ConnectorRequestAction.dismiss.method, "POST")
        for bad in ["", "a/b", "../x", "café", "a b", "a?b", String(repeating: "a", count: 201)] {
            XCTAssertNil(ConnectorRequestAction.resume.path(botId: bad, messageId: "m"), bad)
            XCTAssertNil(ConnectorRequestAction.resume.path(botId: "b", messageId: bad), bad)
        }
    }

    func testAuthorizePostsTheThreadAndReturnsTheHttpsLink() async throws {
        ConnectorCardStub.responseBody = Data(#"{"url":"https://connect.composio.dev/link/abc"}"#.utf8)

        let url = try await client.authorizeConnectorRequest(botId: "bot-1", messageId: "msg-1", threadId: "thread-1")

        XCTAssertEqual(url.absoluteString, "https://connect.composio.dev/link/abc")
        let request = try XCTUnwrap(ConnectorCardStub.captured.last)
        XCTAssertEqual(request.httpMethod, "POST")
        XCTAssertEqual(request.url?.path, "/api/bots/bot-1/connector-cards/msg-1/authorize")
        XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer paired-token")
        let body = try XCTUnwrap(ConnectorCardStub.capturedBodies.last ?? nil)
        XCTAssertEqual(try JSONSerialization.jsonObject(with: body) as? [String: String], ["threadId": "thread-1"])
    }

    func testRefusesALinkThatIsNotHttps() async {
        for link in ["http://connect.composio.dev/x", "javascript:alert(1)", "https:///nohost", "not a url"] {
            ConnectorCardStub.responseBody = Data(#"{"url":"\#(link)"}"#.utf8)
            do {
                _ = try await client.authorizeConnectorRequest(botId: "b", messageId: "m", threadId: "t")
                XCTFail("opened \(link)")
            } catch APIError.badURL {
            } catch {
                XCTFail("\(link): \(error)")
            }
        }
    }

    func testRefusesAnUnsafeIdBeforeSendingAnything() async {
        do {
            _ = try await client.connectorRequestStatus(botId: "../bots", messageId: "m", threadId: "t")
            XCTFail("sent an unsafe id")
        } catch APIError.badURL {
        } catch {
            XCTFail("\(error)")
        }
        XCTAssertTrue(ConnectorCardStub.captured.isEmpty)
    }

    func testStatusIsAGetWithTheThreadInTheQuery() async throws {
        ConnectorCardStub.responseBody = Data(#"{"connected":true,"pending":false,"status":"ACTIVE"}"#.utf8)

        let status = try await client.connectorRequestStatus(botId: "bot-1", messageId: "msg-1", threadId: "thread 1")

        XCTAssertTrue(status.connected)
        XCTAssertEqual(status.status, "ACTIVE")
        let request = try XCTUnwrap(ConnectorCardStub.captured.last)
        XCTAssertEqual(request.httpMethod, "GET")
        XCTAssertEqual(request.url?.path, "/api/bots/bot-1/connector-cards/msg-1/status")
        XCTAssertEqual(URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems,
                       [URLQueryItem(name: "threadId", value: "thread 1")])
    }

    func testResumeAndDismissPostTheThreadAndSurfaceTheComputersRefusal() async throws {
        try await client.dismissConnectorRequest(botId: "bot-1", messageId: "msg-1", threadId: "thread-1")
        XCTAssertEqual(ConnectorCardStub.captured.last?.url?.path, "/api/bots/bot-1/connector-cards/msg-1/dismiss")

        ConnectorCardStub.statusCode = 409
        ConnectorCardStub.responseBody = Data(#"{"error":"finish connecting every requested app first"}"#.utf8)
        do {
            try await client.resumeConnectorRequest(botId: "bot-1", messageId: "msg-1", threadId: "thread-1")
            XCTFail("a refused resume must throw")
        } catch {
            XCTAssertEqual(error.localizedDescription, "finish connecting every requested app first")
        }
        XCTAssertEqual(ConnectorCardStub.captured.last?.url?.path, "/api/bots/bot-1/connector-cards/msg-1/resume")
        let body = try XCTUnwrap(ConnectorCardStub.capturedBodies.last ?? nil)
        XCTAssertEqual(try JSONSerialization.jsonObject(with: body) as? [String: String], ["threadId": "thread-1"])
    }
}
