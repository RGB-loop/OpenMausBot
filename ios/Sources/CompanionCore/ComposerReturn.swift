// Which hardware Return presses send the chat composer's draft.
//
// The software keyboard's Return never reaches this rule: it inserts a
// newline, like Messages, and the arrow button on the chat bar is the one
// send. A hardware Return sends and Shift-Return breaks the line. A Return
// pressed while a Chinese or Japanese input method is composing — Pinyin's
// underlined letters waiting on a candidate — belongs to the input method,
// which commits the candidate; sending then would post the raw letters.
//
// Marked text alone does not mean that. iOS 17's inline predictions (the grey
// completion on an English keyboard) arrive as marked text too, and Korean
// keeps the syllable being typed marked; neither takes Return as its confirm
// key. Both keep sending, as they did before this rule knew about input
// methods.
//
// Kept away from SwiftUI so the rule is testable without a key event, the
// way Android's `ComposerReturn` is.
import Foundation

public enum ComposerReturn {
    /// - Parameters:
    ///   - markedText: the field holds text an input method has not committed.
    ///   - inputLanguage: the active input mode's `primaryLanguage`, such as
    ///     "zh-Hans" or "ja-JP"; nil when there is none.
    public static func sends(shift: Bool, markedText: Bool, inputLanguage: String?) -> Bool {
        !shift && !(markedText && returnConfirmsCandidate(inputLanguage))
    }

    /// Chinese (Pinyin, Zhuyin, Cangjie, Wubi, Stroke), Cantonese and Japanese
    /// input methods spend Return on the marked text, committing it as typed
    /// or as converted.
    static func returnConfirmsCandidate(_ inputLanguage: String?) -> Bool {
        guard let language = inputLanguage?
            .split(whereSeparator: { $0 == "-" || $0 == "_" })
            .first?
            .lowercased()
        else { return false }
        return ["zh", "yue", "ja"].contains(language)
    }
}
