import XCTest

/// Bundled offline fleet only: Pepper's Gmail thread with a connection card
/// in each state (App/ConnectorPreview.json). No client, so nothing is
/// tapped through to a computer — this checks what the phone draws.
final class ConnectorCardUITests: XCTestCase {
    @MainActor
    func testConnectionCardsDrawEachStateWithItsButton() {
        let app = launchPreview()

        let required = app.descendants(matching: .any)["message-preview-connector-required"]
        XCTAssertTrue(required.waitForExistence(timeout: 10))
        XCTAssertTrue(app.staticTexts["GitHub"].exists)
        XCTAssertTrue(app.staticTexts["Requested by Pepper"].exists)
        XCTAssertTrue(app.buttons["Connect securely"].exists)
        XCTAssertTrue(contains("never in chat", in: app))
        XCTAssertGreaterThanOrEqual(app.buttons.matching(identifier: "connector-card.not-now").count, 2,
                                    "required and authorizing cards can be set aside")

        let authorizing = app.descendants(matching: .any)["message-preview-connector-authorizing"]
        XCTAssertTrue(authorizing.exists)
        XCTAssertTrue(app.buttons["Open again"].exists)
        XCTAssertTrue(app.staticTexts["Waiting for sign-in…"].exists)

        let connected = app.descendants(matching: .any)["message-preview-connector-connected"]
        if !connected.exists { app.swipeUp() }
        XCTAssertTrue(connected.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Continue task"].exists)
        XCTAssertTrue(contains("Continue the paused task", in: app))

        // The fallback line for older phones never shows beside the card.
        XCTAssertFalse(contains("to continue.", in: app))
        screenshot("Connection cards: required, authorizing, connected", in: app)
    }

    @MainActor
    private func launchPreview() -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.launchArguments = [
            "-store-preview", "-chat-presentation-preview", "-connector-preview",
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
            "-companion.prefs.islandIntro", "never",
            "-companion.prefs.activityDetail", "hidden",
            "-companion.prefs.rosterDensity", "comfortable",
            "-companion.onboarding.welcomeSeen", "YES",
            "-companion.onboarding.notificationsSeen", "YES"
        ]
        app.launch()
        let threads = app.buttons["threads-toggle.preview-pepper"]
        XCTAssertTrue(threads.waitForExistence(timeout: 10))
        threads.tap()
        app.buttons["thread.preview-gmail"].tap()
        return app
    }

    @MainActor
    private func contains(_ text: String, in app: XCUIApplication) -> Bool {
        app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", text)).firstMatch.exists
    }

    @MainActor
    private func screenshot(_ name: String, in app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
