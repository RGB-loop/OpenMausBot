package com.openmausbot.companion.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Which Return presses send the draft, and which break the line.
 *
 * Only a hardware Return without Shift sends: the phone's own keyboard inserts
 * a newline, like Messages, and the arrow button on the chat bar is the one
 * send. Kept outside Compose so the rule is testable without a key event.
 */
object ComposerReturn {
    fun sends(
        isReturnKey: Boolean,
        keyDown: Boolean,
        shift: Boolean,
        fromSoftwareKeyboard: Boolean,
    ): Boolean = isReturnKey && keyDown && !shift && !fromSoftwareKeyboard

    /**
     * A hardware Shift+Return. The composer types this break itself: the
     * text field's own key handling maps a bare Return to a newline but has
     * nothing for Shift+Return, so without it the press did nothing at all.
     */
    fun breaksLine(
        isReturnKey: Boolean,
        keyDown: Boolean,
        shift: Boolean,
        fromSoftwareKeyboard: Boolean,
    ): Boolean = isReturnKey && keyDown && shift && !fromSoftwareKeyboard

    /** [value] with a line break typed over its selection and the caret after it. */
    fun breakLine(value: TextFieldValue): TextFieldValue {
        val start = value.selection.min
        val text = value.text.replaceRange(start, value.selection.max, "\n")
        return TextFieldValue(text, TextRange(start + 1))
    }
}
