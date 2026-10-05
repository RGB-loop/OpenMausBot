import XCTest

/// Runs against the bundled ApprovalPreview fleet: Kiwi holds two Linear
/// comments for a person, under an earlier allowed send and a denied push.
/// No paired computer and no API client, so nothing here is answered.
final class ApprovalCardUITests: XCTestCase {
    /// The card leads with what is being sent and where, not the request's
    /// raw arguments; those wait under Details.
    @MainActor
    func testAHeldSendReadsAsOneLineWithTheRequestUnderDetails() {
        let app = launchPreview()

        XCTAssertTrue(app.staticTexts["Send to Linear?"].firstMatch.waitForExistence(timeout: 5))
        XCTAssertTrue(app.staticTexts["Create linear comment ×2"].exists)
        XCTAssertTrue(app.buttons["Allow"].exists)
        XCTAssertTrue(app.buttons["Deny"].exists)
        XCTAssertFalse(contains("issueId", in: app), "the arguments stay collapsed")
        XCTAssertFalse(contains("always asks first", in: app), "the headline already says it")

        // Settled cards say what happened in words, not the stored verdict.
        XCTAssertTrue(contains("Allowed", in: app))
        XCTAssertTrue(contains("Denied", in: app))
        XCTAssertFalse(app.staticTexts["allow"].exists)
        XCTAssertFalse(contains("requires your approval", in: app), "a settled card drops its held note")
        screenshot("Pending and settled approval cards", in: app)

        let toggles = app.buttons.matching(identifier: "approval-details-toggle")
        XCTAssertGreaterThanOrEqual(toggles.count, 1)
        toggles.element(boundBy: toggles.count - 1).tap()
        XCTAssertTrue(app.descendants(matching: .any)["approval-details"].waitForExistence(timeout: 3))
        XCTAssertTrue(contains("issueId", in: app), "Details shows the full request")
        // Bring the opened box above the composer for the screenshot.
        app.swipeUp()
        screenshot("Approval details expanded", in: app)
    }

    @MainActor
    private func launchPreview() -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.terminate()
        app.launchArguments = [
            "-store-preview", "-approval-preview",
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
        let input = app.descendants(matching: .any)["message-input"]
        if !input.waitForExistence(timeout: 3) {
            let row = app.staticTexts["Kiwi"].firstMatch
            XCTAssertTrue(row.waitForExistence(timeout: 10), "Kiwi on the roster")
            // The pending approval raises the island over the roster for
            // its first seconds, and a tap then lands on the island.
            for _ in 0..<3 where !input.exists {
                row.tap()
                _ = input.waitForExistence(timeout: 3)
            }
        }
        XCTAssertTrue(input.exists, "Kiwi's chat opened")
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
