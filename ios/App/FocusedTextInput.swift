// The text input that has the keyboard, for what SwiftUI's `KeyPress` does
// not answer: is an input method composing in it, and which one?
//
// Pinyin, Zhuyin, Kana and the other input methods hold what is typed as
// marked text — underlined, over a candidate bar — until a key commits it.
// The field's UIKit text view knows, through `UITextInput.markedTextRange`,
// and its `textInputMode` names the input method. It is found the documented
// way, not by walking SwiftUI's private views: an action sent to a nil target
// goes to the first responder.
import UIKit

enum FocusedTextInput {
    /// Nil when nothing has the keyboard, or what has it is not text.
    @MainActor static var current: (UIResponder & UITextInput)? {
        firstResponder as? UIResponder & UITextInput
    }

    @MainActor fileprivate static var reported: UIResponder?

    @MainActor private static var firstResponder: UIResponder? {
        reported = nil
        defer { reported = nil }
        UIApplication.shared.sendAction(
            #selector(UIResponder.openMausReportFirstResponder),
            to: nil,
            from: nil,
            for: nil
        )
        return reported
    }
}

private extension UIResponder {
    @objc func openMausReportFirstResponder() {
        FocusedTextInput.reported = self
    }
}
