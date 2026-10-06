import CompanionCore
import SwiftUI
import UIKit

/// The reason a card's call never left the phone.
enum ConnectorRequestCallError: Error {
    case noComputer
}

/// Sign-in links this run of the app has opened, so "Open again" reopens the
/// same page for its ten minutes instead of starting a new sign-in. Memory
/// only: a link is never written to disk or the transcript.
@MainActor
final class ConnectorLinkMemory {
    static let shared = ConnectorLinkMemory()
    private var links: [String: ConnectorAuthorizationLink] = [:]

    func link(for key: String) -> URL? {
        guard let link = links[key] else { return nil }
        if let url = link.reusable() { return url }
        links[key] = nil
        return nil
    }

    func remember(_ url: URL, for key: String) {
        links[key] = ConnectorAuthorizationLink(url: url, createdAt: Date())
    }

    func forget(_ key: String) { links[key] = nil }
}

/// A bot paused on "Connect to GitHub", as desktop's ConnectorCard draws it:
/// the app, why the bot wants it, and one button for where the request
/// stands. Sign-in happens on the app's own page in the system browser —
/// never in chat — and the card asks the computer whether it finished while
/// that page is open (the computer does not poll on its own; asking is what
/// flips the card and resumes the bot).
struct ConnectorRequestCardView: View {
    let chat: Chat
    let message: Message
    let request: ConnectorRequest
    let presentation: ConnectorRequestPresentation
    @EnvironmentObject private var session: Session
    @Environment(\.scenePhase) private var scenePhase
    @State private var busy = false
    @State private var actionGeneration = 0
    @State private var failure: Failure?

    private enum Failure: Equatable {
        case invalidLink
        case couldNotOpen
        case noComputer
        case computer(String)

        var text: Text {
            switch self {
            case .invalidLink: return Text("The connection page link wasn't valid. Try again.")
            case .couldNotOpen: return Text("The authorization page could not be opened. Try again after checking your browser restrictions.")
            case .noComputer: return Text("No computer connected")
            case let .computer(message): return Text(verbatim: message)
            }
        }
    }

    /// What polling restarts on: a patch that moves the status, or the card
    /// being set aside, ends the old loop and decides afresh.
    private struct PollKey: Equatable {
        let status: ConnectorRequest.Status?
        let dismissed: Bool?
    }

    private var tint: Color { MausPalette.color(message.from?.color ?? chat.color) }
    private var requester: String { message.from?.name ?? chat.name }
    private var linkKey: String { "\(session.connection?.id ?? ""):\(chat.threadId):\(message.id)" }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            heading
                .padding(14)
            Divider()
            footer
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(Color.secondary.opacity(0.05))
        }
        .frame(maxWidth: 520, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .fill(Color.secondary.opacity(0.09))
        )
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 20, style: .continuous)
                .strokeBorder(request.isPending ? tint.opacity(0.55) : Color.secondary.opacity(0.15), lineWidth: 1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .task(id: PollKey(status: request.status, dismissed: request.dismissed)) {
            await pollWhileSigningIn()
        }
        .onDisappear { invalidateAction() }
        .onValueChange(of: request.dismissed) { dismissed in
            if dismissed == true { invalidateAction() }
        }
        .onValueChange(of: scenePhase) { phase in
            // Back from the browser: one look straight away rather than on
            // the next tick, so a finished sign-in shows the moment you return.
            if phase == .active, request.pollsStatus {
                Task { try? await session.checkConnectorRequest(message, in: chat) }
            }
        }
    }

    // MARK: - Parts

    private var heading: some View {
        HStack(alignment: .top, spacing: 12) {
            Text(verbatim: request.initial)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(.primary)
                .frame(width: 44, height: 44)
                .background(Color.secondary.opacity(0.14), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
                .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 8) {
                    Text(verbatim: request.displayName)
                        .font(.system(size: 15.5, weight: .semibold))
                        .lineLimit(1)
                    if presentation.showsConnectedBadge { connectedBadge }
                }
                bodyText
                    .font(.system(size: 13.5))
                    .foregroundStyle(Color.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                if presentation.showsSignInHint {
                    Text("Sign in or enter the app key on the secure connection page — never in chat.")
                        .font(.system(size: 12))
                        .foregroundStyle(Color.secondary.opacity(0.8))
                        .fixedSize(horizontal: false, vertical: true)
                }
                errorLine
            }
            Spacer(minLength: 0)
            if presentation.offersNotNow { notNowButton }
        }
    }

    private var connectedBadge: some View {
        HStack(spacing: 3) {
            Image(systemName: "checkmark").accessibilityHidden(true)
            Text("Connected")
        }
            .font(.system(size: 11, weight: .medium))
            .foregroundStyle(.green)
            .padding(.horizontal, 7)
            .padding(.vertical, 2)
            .background(Color.green.opacity(0.15), in: Capsule())
    }

    private var bodyText: Text {
        switch presentation.body {
        case .description:
            let description = request.description?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            return description.isEmpty ? Text(verbatim: message.text ?? "") : Text(verbatim: description)
        case .paused: return Text("Connected securely. Continue the paused task when you're ready.")
        case .resumed: return Text("Connected securely. Your bot is continuing the task.")
        }
    }

    @ViewBuilder private var errorLine: some View {
        if let failure {
            failure.text
                .font(.system(size: 12.5))
                .foregroundStyle(.red)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 4)
        } else if let error = request.error?.trimmingCharacters(in: .whitespacesAndNewlines), !error.isEmpty {
            Text(verbatim: error)
                .font(.system(size: 12.5))
                .foregroundStyle(.red)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 4)
        }
    }

    private var notNowButton: some View {
        Button(action: dismiss) {
            Image(systemName: "xmark")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Color.secondary)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(.top, -10)
        .padding(.trailing, -12)
        .accessibilityLabel(Text("Not now"))
        .accessibilityIdentifier("connector-card.not-now")
    }

    private var footer: some View {
        HStack(spacing: 10) {
            footerStatus
                .font(.system(size: 12))
                .foregroundStyle(Color.secondary)
                .lineLimit(2)
            Spacer(minLength: 4)
            footerAction
        }
        .frame(minHeight: 36)
    }

    @ViewBuilder private var footerStatus: some View {
        switch presentation.footer {
        case .waiting:
            HStack(spacing: 6) {
                ProgressView().controlSize(.mini)
                Text("Waiting for sign-in…")
            }
        case .readyToUse:
            HStack(spacing: 6) {
                plug
                Text("Ready to use")
            }
        case .requested:
            HStack(spacing: 6) {
                plug
                Text("Requested by \(requester)")
            }
        }
    }

    private var plug: some View {
        Image(systemName: "powerplug").accessibilityHidden(true)
    }

    @ViewBuilder private var footerAction: some View {
        if let primary = presentation.primary {
            Button(action: { perform(primary) }) {
                HStack(spacing: 6) {
                    if busy {
                        ProgressView().controlSize(.mini).tint(.white)
                    }
                    primaryTitle(primary)
                }
                .font(.system(size: 13.5, weight: .semibold))
                .padding(.horizontal, 4)
            }
            .buttonStyle(.borderedProminent)
            .tint(tint)
            .disabled(busy)
            .accessibilityIdentifier("connector-card.primary")
        } else if presentation.showsContinuing {
            HStack(spacing: 4) {
                Image(systemName: "checkmark").accessibilityHidden(true)
                Text("Continuing")
            }
                .font(.system(size: 12.5, weight: .medium))
                .foregroundStyle(.green)
        }
    }

    private func primaryTitle(_ action: ConnectorRequestPresentation.PrimaryAction) -> Text {
        switch action {
        case .connect: return Text("Connect securely")
        case .tryAgain: return Text("Try again")
        case .openAgain: return Text("Open again")
        case .continueTask: return Text("Continue task")
        }
    }

    // MARK: - Actions

    private func perform(_ action: ConnectorRequestPresentation.PrimaryAction) {
        switch action {
        case .connect, .tryAgain, .openAgain: connect(reuse: action != .tryAgain)
        case .continueTask: resume()
        }
    }

    /// Get a sign-in page — the one already open for its ten minutes, or a
    /// fresh one — and hand it to the system browser.
    private func connect(reuse: Bool) {
        guard !busy else { return }
        busy = true
        failure = nil
        actionGeneration += 1
        let generation = actionGeneration
        let key = linkKey
        Task {
            defer { if generation == actionGeneration { busy = false } }
            do {
                let url: URL
                if reuse, let remembered = ConnectorLinkMemory.shared.link(for: key) {
                    url = remembered
                } else {
                    url = try await session.authorizeConnectorRequest(message, in: chat)
                }
                guard generation == actionGeneration, key == linkKey else { return }
                ConnectorLinkMemory.shared.remember(url, for: key)
                let opened = await UIApplication.shared.open(url)
                if generation == actionGeneration, !opened { failure = .couldNotOpen }
            } catch {
                if generation == actionGeneration { failure = Self.failure(for: error) }
            }
        }
    }

    private func resume() {
        guard !busy else { return }
        busy = true
        failure = nil
        Task {
            defer { busy = false }
            do { try await session.resumeConnectorRequest(message, in: chat) }
            catch { failure = Self.failure(for: error) }
        }
    }

    private func dismiss() {
        invalidateAction()
        failure = nil
        ConnectorLinkMemory.shared.forget(linkKey)
        Task {
            do { try await session.dismissConnectorRequest(message, in: chat) }
            catch { failure = Self.failure(for: error) }
        }
    }

    private func invalidateAction() {
        actionGeneration += 1
        busy = false
    }

    /// While a sign-in page is open: ask every four seconds, up to five
    /// minutes. Ends on its own when a patch moves the card on (the task is
    /// restarted with the new status) or when the card leaves the screen.
    private func pollWhileSigningIn() async {
        guard request.pollsStatus else { return }
        for _ in 0..<ConnectorRequestPolling.maximumChecks {
            do { try await Task.sleep(for: ConnectorRequestPolling.interval) } catch { return }
            if scenePhase != .active { continue }
            if (try? await session.checkConnectorRequest(message, in: chat)) == true { return }
        }
    }

    private static func failure(for error: Error) -> Failure {
        switch error {
        case ConnectorRequestCallError.noComputer: return .noComputer
        case APIError.badURL: return .invalidLink
        default: return .computer(error.localizedDescription)
        }
    }
}
