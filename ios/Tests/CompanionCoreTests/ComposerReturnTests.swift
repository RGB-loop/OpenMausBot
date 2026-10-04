// Which hardware Return presses send the composer's draft, without a key
// event. The app hands the press back to the text view when this says no.
import XCTest
@testable import CompanionCore

final class ComposerReturnTests: XCTestCase {
    func testABareReturnSends() {
        XCTAssertTrue(ComposerReturn.sends(shift: false, composing: false))
    }

    func testShiftReturnBreaksTheLine() {
        XCTAssertFalse(ComposerReturn.sends(shift: true, composing: false))
    }

    /// Pinyin, Zhuyin or Kana mid-word: Return commits the candidate, and the
    /// next Return sends what was committed.
    func testReturnWhileAnInputMethodComposesCommitsInsteadOfSending() {
        XCTAssertFalse(ComposerReturn.sends(shift: false, composing: true))
        XCTAssertFalse(ComposerReturn.sends(shift: true, composing: true))
    }
}
