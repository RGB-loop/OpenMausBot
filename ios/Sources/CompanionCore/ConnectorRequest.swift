// A bot's in-chat request to connect an app — "Connect to GitHub" — and the
// small decisions the phone's card makes about it.
//
// The computer stores the request as a transcript message of kind
// "connector" (shared/wire.ts `ConnectorCardData`) and patches it as the
// person signs in. Desktop draws it as src/components/ConnectorCard.tsx; the
// phone's card mirrors that card's states and buttons, which is why the
// decisions live here, where they can be tested without a view.
//
// Not to be confused with `ConnectorCard`, the Connected Apps catalog entry
// in Settings: that one lists an app anyone could connect; this one is one
// bot, paused in one thread, waiting for one app.
import Foundation

public struct ConnectorRequest: Codable, Hashable, Sendable {
    /// Where the request stands. The computer moves it; the phone only reads.
    public enum Status: String, Codable, Sendable {
        case required, authorizing, connected, failed
        /// A state from a newer computer. Decoded rather than thrown, so one
        /// card cannot fail the decode of the whole thread it arrived in.
        case unknown

        public init(from decoder: any Decoder) throws {
            let raw = try decoder.singleValueContainer().decode(String.self)
            self = Status(rawValue: raw) ?? .unknown
        }
    }

    /// The Composio toolkit slug. The computer validates it on every action;
    /// the phone never sends it — every action is addressed by message id.
    public var slug: String?
    public var label: String?
    public var description: String?
    public var status: Status?
    /// Cards made by one bot request resume together once all connect.
    public var resumeKey: String?
    /// The account name, when the bot asked for a second account.
    public var alias: String?
    public var error: String?
    public var dismissed: Bool?
    public var resumed: Bool?

    public init(
        slug: String? = nil,
        label: String? = nil,
        description: String? = nil,
        status: Status? = nil,
        resumeKey: String? = nil,
        alias: String? = nil,
        error: String? = nil,
        dismissed: Bool? = nil,
        resumed: Bool? = nil
    ) {
        self.slug = slug
        self.label = label
        self.description = description
        self.status = status
        self.resumeKey = resumeKey
        self.alias = alias
        self.error = error
        self.dismissed = dismissed
        self.resumed = resumed
    }

    /// What the card calls the app: the computer's label, else its slug.
    public var displayName: String {
        if let label = label?.trimmingCharacters(in: .whitespacesAndNewlines), !label.isEmpty { return label }
        if let slug = slug?.trimmingCharacters(in: .whitespacesAndNewlines), !slug.isEmpty { return slug }
        return "App"
    }

    /// The square badge: the name's first letter, as desktop draws it.
    public var initial: String {
        displayName.first.map { String($0).uppercased() } ?? "?"
    }

    /// Still waiting on the person: not connected and not set aside.
    public var isPending: Bool {
        dismissed != true && status != .connected
    }

    /// The card asks the computer for the sign-in result only while a
    /// sign-in page is open somewhere. The computer has no poller of its
    /// own: a status check is what flips the card to connected and resumes
    /// the bot's turn.
    public var pollsStatus: Bool {
        dismissed != true && status == .authorizing
    }
}

/// The card's states, as desktop's ConnectorCard draws them. The view reads
/// this rather than the status, so the two platforms and the test agree.
public struct ConnectorRequestPresentation: Equatable, Sendable {
    public enum PrimaryAction: Equatable, Sendable {
        /// Ask the computer for a sign-in page and open it.
        case connect
        /// The same, after a failure.
        case tryAgain
        /// Reopen the page that is already waiting, or a fresh one.
        case openAgain
        /// Every app is connected; start the paused turn again.
        case continueTask
    }

    public enum Footer: Equatable, Sendable {
        /// "Requested by your bot"
        case requested
        /// "Waiting for sign-in…", with a spinner
        case waiting
        /// "Ready to use"
        case readyToUse
    }

    public enum Body: Equatable, Sendable {
        /// The computer's description of why the bot needs the app.
        case description
        /// "Connected securely. Continue the paused task when you're ready."
        case paused
        /// "Connected securely. Your bot is continuing the task."
        case resumed
    }

    public var primary: PrimaryAction?
    /// The "Not now" control, which dismisses the request.
    public var offersNotNow: Bool
    public var footer: Footer
    public var body: Body
    /// "Sign in or enter the app key on the secure connection page — never in chat."
    public var showsSignInHint: Bool
    /// The "Connected" pill beside the name.
    public var showsConnectedBadge: Bool
    /// The quiet "Continuing" receipt where the button was.
    public var showsContinuing: Bool

    /// nil when the card is not drawn at all: desktop hides a request the
    /// person set aside, and so does the phone.
    public static func of(_ request: ConnectorRequest) -> ConnectorRequestPresentation? {
        if request.dismissed == true { return nil }
        switch request.status ?? .required {
        case .connected:
            let resumed = request.resumed == true
            return ConnectorRequestPresentation(
                primary: resumed ? nil : .continueTask,
                offersNotNow: false,
                footer: .readyToUse,
                body: resumed ? .resumed : .paused,
                showsSignInHint: false,
                showsConnectedBadge: true,
                showsContinuing: resumed
            )
        case .authorizing:
            return pending(primary: .openAgain, footer: .waiting)
        case .failed:
            return pending(primary: .tryAgain, footer: .requested)
        case .required:
            return pending(primary: .connect, footer: .requested)
        case .unknown:
            // A state this build cannot act on: say what the bot wants and
            // offer nothing that might do the wrong thing.
            return ConnectorRequestPresentation(
                primary: nil, offersNotNow: false, footer: .requested, body: .description,
                showsSignInHint: false, showsConnectedBadge: false, showsContinuing: false
            )
        }
    }

    private static func pending(primary: PrimaryAction, footer: Footer) -> ConnectorRequestPresentation {
        ConnectorRequestPresentation(
            primary: primary, offersNotNow: true, footer: footer, body: .description,
            showsSignInHint: true, showsConnectedBadge: false, showsContinuing: false
        )
    }
}

/// The four routes a card acts through. Each is scoped to the bot that asked
/// and the transcript message it asked in; the thread rides in the body (or,
/// for status, the query). The companion's allowlist admits exactly these.
public enum ConnectorRequestAction: String, CaseIterable, Sendable {
    case authorize, status, resume, dismiss

    public var method: String { self == .status ? "GET" : "POST" }

    /// `/api/bots/:botId/connector-cards/:messageId/:action`, or nil when
    /// either id is not one the computer's `[\w-]+` route would match —
    /// such an id is refused here rather than sent as a confusing 404.
    public func path(botId: String, messageId: String) -> String? {
        guard Self.validID(botId), Self.validID(messageId) else { return nil }
        return "/api/bots/\(botId)/connector-cards/\(messageId)/\(rawValue)"
    }

    /// JavaScript `\w` is ASCII: letters, digits and underscore, plus hyphen.
    static func validID(_ value: String) -> Bool {
        !value.isEmpty && value.utf8.count <= 200 && value.utf8.allSatisfy {
            (48...57).contains($0) || (65...90).contains($0) || (97...122).contains($0) || $0 == 95 || $0 == 45
        }
    }
}

/// What a status check said.
public struct ConnectorRequestStatus: Codable, Hashable, Sendable {
    public var connected: Bool
    public var pending: Bool?
    public var status: String?

    public init(connected: Bool, pending: Bool? = nil, status: String? = nil) {
        self.connected = connected
        self.pending = pending
        self.status = status
    }
}

/// How often and how long a card asks whether sign-in finished: every four
/// seconds, 75 times — five minutes, desktop's numbers.
public enum ConnectorRequestPolling {
    public static let interval: Duration = .seconds(4)
    public static let maximumChecks = 75
}

/// The sign-in page the card last opened. Composio links expire after ten
/// minutes, and reopening the same page must not restart that clock, so the
/// card reuses the link rather than asking for a new one each time. Kept in
/// memory only — the link is never written to the transcript or disk.
public struct ConnectorAuthorizationLink: Equatable, Sendable {
    public static let lifetime: TimeInterval = 10 * 60

    public var url: URL
    public var createdAt: Date

    public init(url: URL, createdAt: Date) {
        self.url = url
        self.createdAt = createdAt
    }

    /// The link while it is still usable, else nil.
    public func reusable(now: Date = Date()) -> URL? {
        let age = now.timeIntervalSince(createdAt)
        return age >= 0 && age < Self.lifetime ? url : nil
    }
}

extension Chat {
    /// The bot a connection card acts for: the chat's bot, or in a room the
    /// member that asked. nil when a room card names no member — nothing can
    /// be done with it, and guessing a member would act for the wrong bot.
    public func connectorOwner(of message: Message) -> String? {
        switch self {
        case let .bot(bot): return bot.id
        case .room: return message.from?.botId
        }
    }
}

extension Message {
    /// The line a roster row and Walkie use for a connection card. A request
    /// still waiting reads as the computer's own sentence ("Connect GitHub to
    /// continue."), a settled one as the app it was about.
    var connectorPreviewLine: String {
        guard let connector else { return text ?? "" }
        if connector.isPending, let text, !text.isEmpty { return text }
        return connector.displayName
    }
}
