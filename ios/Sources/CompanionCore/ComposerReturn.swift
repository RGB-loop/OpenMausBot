// Which hardware Return presses send the chat composer's draft.
//
// The software keyboard's Return never reaches this rule: it inserts a
// newline, like Messages, and the arrow button on the chat bar is the one
// send. A hardware Return sends and Shift-Return breaks the line. A Return
// pressed while an input method is composing — Pinyin's underlined letters
// waiting on a candidate — belongs to the input method, which commits the
// candidate; sending then would post the raw letters instead.
//
// Kept away from SwiftUI so the rule is testable without a key event, the
// way Android's `ComposerReturn` is.
import Foundation

public enum ComposerReturn {
    public static func sends(shift: Bool, composing: Bool) -> Bool {
        !shift && !composing
    }
}
