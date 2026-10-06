import Foundation
import XCTest
@testable import CompanionCore

/// Every type an offline snapshot file can hold, field by field (MOCA-296).
///
/// StateSnapshotTests already holds the roster rows and messages to their
/// wire types. This goes one level further: every type nested inside them —
/// a routine, a card, a thread opener — is listed here with the stored
/// properties someone has looked at and agreed may sit on the phone's disk.
/// A field added to any of them fails `testEveryTypeInTheFileHasReviewedFields`
/// until it is added below on purpose, so a secret-bearing field cannot reach
/// the file silently. A new nested type fails `testEveryNestedTypeIsReviewed`.
///
/// Names are read with Mirror, which lists every stored property whether or
/// not the sample sets it; see StateSnapshotTests.fieldNames for why not the
/// Codable keys.
final class SnapshotFieldGuardTests: XCTestCase {
    /// Agreed fields per type. Before adding one, ask whether it is a
    /// credential, bytes of an attachment or screen, live call state or
    /// anything in flight — none of those belong in the snapshot, and a
    /// heavy one belongs in StateSnapshot's cached rows as a left-out field.
    private static let reviewed: [String: Set<String>] = [
        "StateSnapshot": [
            "schemaVersion", "connectionId", "serverEnvironmentId", "savedAt", "bots", "rooms", "threads",
            "routines", "routineRuns",
        ],
        "CachedBot": [
            "id", "threadId", "name", "title", "description", "notifications", "color", "avatarUrl", "avatarCrop",
            "unread", "modelSelection", "createdAt", "busy", "activity", "waitingOnTeammate", "pinned", "hidden",
            "section", "chiefOfStaff", "approvalMode", "autoApprove", "alwaysAllow", "computer", "cloudBackend",
            "speakReplies", "voice", "mascotExpression", "mascotBody", "tasks", "projects", "activeLeafId",
        ],
        "CachedRoom": [
            "id", "threadId", "name", "memberIds", "defaultResponder", "bulletin", "unread", "createdAt", "dm",
            "section", "busyBotId", "working", "tasks",
        ],
        "CachedTask": [
            "threadId", "title", "createdAt", "modelSelection", "busy", "activity", "waitingOnTeammate", "unread",
            "approvalMode", "autoApprove", "alwaysAllow", "projectId", "openedBy", "closedBy", "snoozedUntil",
            "archivedAt", "routineRunId", "pinned", "updatedAt",
        ],
        "CachedThread": ["messages", "hasMore", "activeLeafId"],
        "CachedMessage": [
            "id", "role", "kind", "at", "text", "turnId", "turnTerminal", "card", "secret", "tool", "threadRef",
            "compaction", "routineRun", "parentId", "queueId", "steered", "from", "via", "reactions", "comm",
            "hasImage", "mime", "attachments", "connector",
        ],
        // A connect-an-app card: no link or key, only what the card shows.
        "ConnectorRequest": [
            "slug", "label", "description", "status", "resumeKey", "alias", "error", "dismissed", "resumed",
        ],
        "ModelSelection": ["instanceId", "model", "effort"],
        "BotProject": ["id", "name", "emoji"],
        "GroupResponder": ["kind", "botId"],
        "ThreadOpener": ["botId", "name", "delegationId", "at"],
        "ThreadCloser": ["botId", "name", "at"],
        "Routine": [
            "id", "name", "prompt", "botId", "runOn", "enabled", "schedule", "durationMinutes", "timeoutMinutes",
            "nextRunAt", "createdAt", "updatedAt",
        ],
        "RoutineSchedule": ["type", "at", "time", "weekdays", "everyMinutes", "anchorAt"],
        "RoutineRun": [
            "id", "routineId", "routineName", "prompt", "durationMinutes", "timeoutMinutes", "botId", "runOn",
            "scheduledFor", "status", "manual", "triggerSource", "threadId", "startedAt", "finishedAt", "output",
            "error", "createdAt", "seenAt",
        ],
        "OptionCard": [
            "title", "subtitle", "options", "answered", "dismissed", "requestId", "tool", "held", "allowKey",
            "skillRequest", "questionRequest", "answeredText", "expired", "requestType", "heldCode",
            "outboundRequest", "teamMemoryRequest",
        ],
        "SkillRequestCardData": [
            "version", "requestId", "botId", "threadId", "stagedId", "action", "name", "gist", "source", "preview",
            "sha256", "warnings", "createdAt",
        ],
        "QuestionRequestCardData": ["version", "questions", "origin"],
        "AskQuestion": ["question", "header", "multiSelect", "options"],
        "AskQuestionOption": ["label", "detail"],
        "OutboundRequest": ["tool", "app", "calls"],
        "OutboundCall": ["app", "label"],
        "TeamMemoryRequest": ["section", "entryId", "kind"],
        "SecretRequestCardData": [
            "target", "label", "description", "placeholder", "helpUrl", "requestKey", "provided", "dismissed",
            "resumed", "error",
        ],
        "ToolActivity": ["name", "ok", "spoken", "setup", "claudeUpdate", "output"],
        "ThreadRef": ["botId", "threadId", "title"],
        "Compaction": ["summary", "tokensBefore"],
        "RoutineRunCard": [
            "runId", "routineId", "routineName", "scheduledFor", "status", "deferredAt", "goalStatus",
            "executionThreadId", "summary", "error",
        ],
        "Sender": ["botId", "name", "color"],
        "Reaction": ["emoji", "by"],
        "CommChip": ["groupId", "withBotId", "withName", "withColor"],
        "MessageImageAttachment": ["kind", "path", "mime", "name", "durationMs"],
    ]

    /// Types with no fields of their own to review: values, and enums that
    /// encode as one string.
    private static let leaves: Set<String> = [
        "String", "Int", "Int64", "Double", "Bool", "Date",
        "AvatarCrop", "Role", "Kind",
        // ConnectorRequest.Status: required, authorizing, connected, failed.
        "Status",
    ]

    func testEveryTypeInTheFileHasReviewedFields() throws {
        let samples = try Self.samples()
        XCTAssertEqual(Set(samples.map(Self.typeName)), Set(Self.reviewed.keys), "One sample per reviewed type.")
        for sample in samples {
            let name = Self.typeName(sample)
            XCTAssertEqual(
                Self.fieldNames(sample), Self.reviewed[name],
                "\(name) changed. Decide whether each new field may be kept on the phone's disk, then update `reviewed`."
            )
        }
    }

    func testEveryNestedTypeIsReviewed() throws {
        for sample in try Self.samples() {
            for child in Mirror(reflecting: sample).children {
                for referenced in Self.referencedTypes(of: child.value) {
                    XCTAssertTrue(
                        Self.reviewed[referenced] != nil || Self.leaves.contains(referenced),
                        "\(Self.typeName(sample)).\(child.label ?? "?") holds \(referenced), which no one has reviewed for the snapshot."
                    )
                }
            }
        }
    }

    // MARK: - Reading types

    private static func typeName(_ value: Any) -> String {
        String(describing: type(of: value))
    }

    private static func fieldNames(_ value: Any) -> Set<String> {
        Set(Mirror(reflecting: value).children.compactMap(\.label))
    }

    /// The named types inside a property's declared type, with Optional,
    /// Array and Dictionary unwrapped: `Optional<Array<AskQuestion>>` is
    /// AskQuestion. Read off the declared type, so a nil property counts.
    private static func referencedTypes(of value: Any) -> Set<String> {
        let described = String(describing: type(of: value))
        let names = described
            .split(whereSeparator: { !($0.isLetter || $0.isNumber || $0 == "_") })
            .map(String.init)
        return Set(names).subtracting(["Optional", "Array", "Dictionary", "Set"])
    }

    // MARK: - One of each

    private static func samples() throws -> [Any] {
        func decode<T: Decodable>(_ type: T.Type, _ json: String) throws -> T {
            try JSONDecoder().decode(T.self, from: Data(json.utf8))
        }
        let selection = ModelSelection(instanceId: "engine", model: "default")
        var task = BotTask(threadId: "t1", title: "Flights", createdAt: 1)
        task.modelSelection = selection
        let bot = Bot(
            id: "scout", threadId: "t1", name: "Scout", title: "Researcher", description: "", notifications: true,
            color: "green", unread: false, modelSelection: selection, createdAt: 0, tasks: [task]
        )
        let room = Room(
            id: "team", threadId: "team-main", name: "Team", memberIds: ["scout"],
            defaultResponder: GroupResponder(kind: "bot", botId: "scout"), bulletin: "", unread: false, createdAt: 0
        )
        let message = Message(id: "m1", role: .user, kind: .text, at: 1)
        let cachedMessage = StateSnapshot.CachedMessage(message)
        let thread = StateSnapshot.CachedThread(messages: [cachedMessage], hasMore: false, activeLeafId: "m1")
        let routine = try decode(Routine.self, #"""
        {"id":"r1","name":"Brief","prompt":"Summarize","botId":"scout","runOn":"maus","enabled":true,
         "schedule":{"type":"daily","time":"09:00"},"durationMinutes":30,"createdAt":1,"updatedAt":1}
        """#)
        let run = try decode(RoutineRun.self, #"""
        {"id":"run1","routineId":"r1","routineName":"Brief","botId":"scout","runOn":"maus","scheduledFor":1,
         "status":"completed","manual":false,"createdAt":1}
        """#)
        let snapshot = StateSnapshot(
            schemaVersion: StateSnapshot.currentSchemaVersion, connectionId: "computer-1",
            serverEnvironmentId: nil, savedAt: Date(timeIntervalSince1970: 0),
            bots: [.init(bot)], rooms: [.init(room)], threads: ["t1": thread],
            routines: [routine], routineRuns: [run]
        )
        return [
            snapshot,
            StateSnapshot.CachedBot(bot),
            StateSnapshot.CachedRoom(room),
            StateSnapshot.CachedTask(task),
            thread,
            cachedMessage,
            selection,
            BotProject(id: "p1", name: "Trips", emoji: nil),
            GroupResponder(kind: "bot", botId: "scout"),
            ThreadOpener(botId: "echo", name: "Echo", delegationId: nil, at: 1),
            ThreadCloser(botId: "echo", name: "Echo", at: 2),
            routine,
            routine.schedule,
            run,
            try decode(OptionCard.self, #"{"title":"Run tests?","subtitle":"","options":["Allow","Deny"]}"#),
            try decode(SkillRequestCardData.self, #"""
            {"version":1,"requestId":"q","botId":"scout","threadId":"t1","stagedId":"s","action":"create",
             "name":"n","gist":"g","warnings":[],"createdAt":1}
            """#),
            try decode(QuestionRequestCardData.self, #"{"version":1,"questions":[]}"#),
            try decode(AskQuestion.self, #"{"question":"Which?","options":[{"label":"A"}]}"#),
            try decode(AskQuestionOption.self, #"{"label":"A"}"#),
            try decode(OutboundRequest.self, #"{}"#),
            try decode(OutboundCall.self, #"{"label":"Send"}"#),
            try decode(ConnectorRequest.self, #"{"slug":"github","label":"GitHub","status":"required"}"#),
            try decode(TeamMemoryRequest.self, #"{}"#),
            try decode(SecretRequestCardData.self, #"{}"#),
            try decode(ToolActivity.self, #"{"name":"Bash"}"#),
            try decode(ThreadRef.self, #"{"botId":"scout","threadId":"t1","title":"Flights"}"#),
            Compaction(summary: "s", tokensBefore: 1),
            try decode(RoutineRunCard.self, #"{"runId":"run1","routineId":"r1","routineName":"Brief","status":"completed"}"#),
            try decode(Sender.self, #"{"botId":"scout","name":"Scout","color":"green"}"#),
            try decode(Reaction.self, #"{"emoji":"👍","by":"me"}"#),
            try decode(CommChip.self, #"{"groupId":"g","withBotId":"echo","withName":"Echo","withColor":"blue"}"#),
            try decode(MessageImageAttachment.self, #"{"kind":"file"}"#),
        ]
    }
}
