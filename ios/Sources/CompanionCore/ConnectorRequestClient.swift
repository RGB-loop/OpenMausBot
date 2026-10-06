// The four calls an in-chat connection card makes. See ConnectorRequest.swift
// for the card itself; the computer's side is the
// `/api/bots/:botId/connector-cards/:messageId/:action` route in
// server/index.ts.
import Foundation

private struct ConnectorRequestAuthorization: Decodable {
    let url: String
}

extension CompanionClient {
    /// Ask the computer for this card's sign-in page. The computer marks the
    /// card authorizing as it answers; the link itself is never stored.
    /// Only an https link with a host is returned — anything else would be
    /// opening a page the phone cannot vouch for.
    public func authorizeConnectorRequest(botId: String, messageId: String, threadId: String) async throws -> URL {
        let data = try await connectorRequest(.authorize, botId: botId, messageId: messageId, threadId: threadId)
        guard let body = try? JSONDecoder().decode(ConnectorRequestAuthorization.self, from: data),
              let url = URL(string: body.url),
              url.scheme?.lowercased() == "https",
              url.host?.isEmpty == false
        else { throw APIError.badURL }
        return url
    }

    /// Whether sign-in finished. This call is also what tells the computer:
    /// it flips the card to connected and, once every app in the request is
    /// connected, resumes the bot's turn.
    public func connectorRequestStatus(botId: String, messageId: String, threadId: String) async throws -> ConnectorRequestStatus {
        let data = try await connectorRequest(.status, botId: botId, messageId: messageId, threadId: threadId)
        guard let status = try? JSONDecoder().decode(ConnectorRequestStatus.self, from: data) else {
            throw APIError.transport("The computer sent something this app couldn't read.")
        }
        return status
    }

    /// Start the paused task again once everything it asked for is connected.
    public func resumeConnectorRequest(botId: String, messageId: String, threadId: String) async throws {
        _ = try await connectorRequest(.resume, botId: botId, messageId: messageId, threadId: threadId)
    }

    /// "Not now": set the request aside. The bot stays paused.
    public func dismissConnectorRequest(botId: String, messageId: String, threadId: String) async throws {
        _ = try await connectorRequest(.dismiss, botId: botId, messageId: messageId, threadId: threadId)
    }

    private func connectorRequest(
        _ action: ConnectorRequestAction,
        botId: String,
        messageId: String,
        threadId: String
    ) async throws -> Data {
        guard let path = action.path(botId: botId, messageId: messageId), !threadId.isEmpty else {
            throw APIError.badURL
        }
        let request = action.method == "GET"
            ? try makeRequest("GET", path, query: [URLQueryItem(name: "threadId", value: threadId)])
            : try makeRequest("POST", path, body: ["threadId": threadId])
        let (data, response) = try await perform(request)
        try Self.check(response, data)
        return data
    }
}
