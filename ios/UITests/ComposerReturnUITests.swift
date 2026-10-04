import XCTest

/// Runs against the bundled ThreadPreview fleet: no paired computer, and the
/// preview has no API client, so nothing typed here can be sent anywhere.
final class ComposerReturnUITests: XCTestCase {
    /// Messages behaviour: the software keyboard's Return breaks the line and
    /// the arrow button on the chat bar is the only send.
    @MainActor
    func testKeyboardReturnInsertsANewlineInsteadOfSending() {
        let app = launchPreview()
        app.buttons["threads-toggle.preview-pepper"].tap()
        let gmail = app.buttons["thread.preview-gmail"]
        XCTAssertTrue(gmail.waitForExistence(timeout: 5))
        gmail.tap()

        let input = app.descendants(matching: .any).matching(identifier: "message-input").firstMatch
        XCTAssertTrue(input.waitForExistence(timeout: 5))
        input.tap()
        input.typeText("first line")

        let keys = app.keyboards.buttons
        let returnKey = keys.matching(NSPredicate(format: "label ==[c] 'return'")).firstMatch
        XCTAssertTrue(returnKey.waitForExistence(timeout: 5), "the keyboard should offer a plain return key")
        XCTAssertFalse(keys.matching(NSPredicate(format: "label ==[c] 'send'")).firstMatch.exists,
                       "the keyboard must not carry a second send key")
        returnKey.tap()
        input.typeText("second line")

        XCTAssertEqual(input.value as? String, "first line\nsecond line")
        recordScreenshot("Two-line draft after tapping the keyboard's return", in: app)
    }

    /// An iPad keyboard (MOCA-197): Shift-Return breaks the line and a bare
    /// Return sends. With no client in the preview, the send arrives as the
    /// offline error under the draft rather than as a message.
    @MainActor
    func testHardwareShiftReturnBreaksTheLineAndReturnSends() throws {
        let app = launchPreview()
        app.buttons["threads-toggle.preview-pepper"].tap()
        let gmail = app.buttons["thread.preview-gmail"]
        XCTAssertTrue(gmail.waitForExistence(timeout: 5))
        gmail.tap()

        let input = app.descendants(matching: .any).matching(identifier: "message-input").firstMatch
        XCTAssertTrue(input.waitForExistence(timeout: 5))
        input.tap()
        input.typeText("one")
        input.typeKey(.return, modifierFlags: .shift)
        input.typeText("two")
        let afterShiftReturn = input.value as? String
        let sendFailed = app.staticTexts["This computer is offline."]
        let shiftReturnSent = sendFailed.waitForExistence(timeout: 1)

        input.typeKey(.return, modifierFlags: [])
        let returnSent = sendFailed.waitForExistence(timeout: 5)

        // A headless simulator with no hardware keyboard attached drops the
        // Return that `typeKey` synthesizes before any text view sees it: a
        // bare SwiftUI TextEditor stays on one line too, while letters and
        // Space arrive (iOS 26.5, Oct 2026). Neither press doing anything at
        // all is that, and says nothing about the composer.
        if afterShiftReturn == "onetwo", !shiftReturnSent, !returnSent {
            throw XCTSkip("This simulator delivered no hardware Return to the app; run with a hardware keyboard connected.")
        }
        XCTAssertEqual(afterShiftReturn, "one\ntwo", "Shift-Return breaks the line")
        XCTAssertFalse(shiftReturnSent, "Shift-Return must not send")
        XCTAssertTrue(returnSent, "a bare hardware Return sends")
        XCTAssertEqual(input.value as? String, "one\ntwo", "Return sent the draft, it did not add to it")
        recordScreenshot("Two-line draft sent with a hardware Return", in: app)
    }

    @MainActor
    private func launchPreview() -> XCUIApplication {
        continueAfterFailure = false
        let app = XCUIApplication()
        app.terminate()
        app.launchArguments = [
            "-store-preview", "-threads-preview",
            "-AppleLanguages", "(en)", "-AppleLocale", "en_US",
            "-companion.prefs.islandIntro", "never",
            "-companion.onboarding.welcomeSeen", "YES",
            "-companion.onboarding.notificationsSeen", "YES"
        ]
        app.launch()
        if app.buttons["Connect computer"].exists {
            app.terminate()
            app.launch()
        }
        XCTAssertTrue(app.buttons["threads-toggle.preview-pepper"].waitForExistence(timeout: 10))
        return app
    }

    @MainActor
    private func recordScreenshot(_ name: String, in app: XCUIApplication) {
        let attachment = XCTAttachment(screenshot: app.screenshot())
        attachment.name = name
        attachment.lifetime = .keepAlways
        add(attachment)
    }
}
