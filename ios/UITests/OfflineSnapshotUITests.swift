import XCTest

/// MOCA-296: a cold launch away from the computer shows the last sync,
/// read-only. Runs against the bundled ApprovalPreview fleet saved as an
/// offline snapshot (`-offline-preview`): Kiwi's chat holds a pending
/// approval, and the computer is out of reach.
final class OfflineSnapshotUITests: XCTestCase {
    @MainActor
    func testTheLastSyncShowsReadOnlyWithTheBannerOnHomeAndInAChat() {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.terminate()
        app.launchArguments = [
            "-store-preview", "-approval-preview", "-offline-preview",
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
            "-companion.prefs.islandIntro", "never",
            "-companion.prefs.rosterDensity", "comfortable",
            "-companion.onboarding.welcomeSeen", "YES",
            "-companion.onboarding.notificationsSeen", "YES"
        ]
        app.launch()
        if app.buttons["Connect computer"].exists {
            app.terminate()
            app.launch()
        }

        // Home: the roster from the saved copy, under one quiet line.
        let banner = app.descendants(matching: .any)["offline-snapshot-banner"]
        XCTAssertTrue(banner.waitForExistence(timeout: 10), "the offline banner on Home")
        XCTAssertTrue(
            app.descendants(matching: .any)
                .matching(NSPredicate(format: "label BEGINSWITH %@", "Not connected · last updated"))
                .firstMatch.exists
        )
        let row = app.staticTexts["Kiwi"].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5), "Kiwi on the cached roster")
        XCTAssertFalse(app.buttons["New bot"].exists, "nothing is created from the last sync")
        screenshot("Home showing the last sync", in: app)

        // A chat: readable, nothing to send or answer.
        let input = app.descendants(matching: .any)["message-input"]
        for _ in 0..<3 where !input.exists {
            row.tap()
            _ = input.waitForExistence(timeout: 3)
        }
        XCTAssertTrue(input.exists, "Kiwi's chat opened")
        XCTAssertTrue(app.descendants(matching: .any)["offline-snapshot-banner"].waitForExistence(timeout: 3),
                      "the banner in the chat too")
        XCTAssertFalse(input.isEnabled, "the composer takes no words it could not send")
        XCTAssertTrue(app.descendants(matching: .any)
            .matching(NSPredicate(format: "placeholderValue == %@ OR label == %@ OR value == %@",
                                  "Reconnect to send", "Reconnect to send", "Reconnect to send"))
            .firstMatch.exists, "the composer says why")

        XCTAssertTrue(app.staticTexts["Send to Linear?"].firstMatch.waitForExistence(timeout: 5),
                      "the saved ask still reads as one")
        XCTAssertTrue(app.descendants(matching: .any)["reconnect-to-answer"].exists)
        for choice in ["Allow", "Deny"] {
            let button = app.buttons[choice].firstMatch
            XCTAssertTrue(button.exists, "\(choice) is shown")
            XCTAssertFalse(button.isEnabled, "\(choice) cannot answer from the cache")
        }
        XCTAssertFalse(app.buttons["composer-stop"].exists, "no Stop for a saved turn")
        screenshot("Chat showing the last sync", in: app)
    }

    @MainActor
    private func screenshot(_ name: String, in app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
