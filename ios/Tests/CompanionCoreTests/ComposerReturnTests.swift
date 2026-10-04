// Which hardware Return presses send the composer's draft, without a key
// event. The app hands the press back to the text view when this says no.
import XCTest
@testable import CompanionCore

final class ComposerReturnTests: XCTestCase {
    func testABareReturnSends() {
        XCTAssertTrue(ComposerReturn.sends(shift: false, markedText: false, inputLanguage: "en-US"))
        XCTAssertTrue(ComposerReturn.sends(shift: false, markedText: false, inputLanguage: nil))
    }

    func testShiftReturnBreaksTheLine() {
        XCTAssertFalse(ComposerReturn.sends(shift: true, markedText: false, inputLanguage: "en-US"))
        XCTAssertFalse(ComposerReturn.sends(shift: true, markedText: true, inputLanguage: "en-US"))
    }

    /// Pinyin, Zhuyin or Kana mid-word: Return commits the candidate, and the
    /// next Return sends what was committed.
    func testReturnWhileChineseOrJapaneseComposesCommitsInsteadOfSending() {
        for language in ["zh-Hans", "zh-Hant", "zh_Hant_HK", "yue-Hant", "ja-JP", "ja"] {
            XCTAssertFalse(ComposerReturn.sends(shift: false, markedText: true, inputLanguage: language), language)
            XCTAssertFalse(ComposerReturn.sends(shift: true, markedText: true, inputLanguage: language), language)
            XCTAssertTrue(ComposerReturn.sends(shift: false, markedText: false, inputLanguage: language), language)
        }
    }

    /// An English inline prediction is marked text, as is the Korean syllable
    /// being typed; neither input method spends Return on it, so it sends.
    func testMarkedTextFromOtherInputModesStillSends() {
        for language in ["en-US", "ko-KR", "vi-VN", "jam", "emoji", "dictation"] {
            XCTAssertTrue(ComposerReturn.sends(shift: false, markedText: true, inputLanguage: language), language)
        }
        XCTAssertTrue(ComposerReturn.sends(shift: false, markedText: true, inputLanguage: nil))
        XCTAssertTrue(ComposerReturn.sends(shift: false, markedText: true, inputLanguage: ""))
    }
}
