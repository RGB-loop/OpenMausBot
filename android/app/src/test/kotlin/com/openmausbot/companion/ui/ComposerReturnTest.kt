package com.openmausbot.companion.ui

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComposerReturnTest {
    @Test
    fun hardwareReturnSends() {
        assertTrue(ComposerReturn.sends(isReturnKey = true, keyDown = true, shift = false, fromSoftwareKeyboard = false))
        assertFalse(ComposerReturn.breaksLine(isReturnKey = true, keyDown = true, shift = false, fromSoftwareKeyboard = false))
    }

    @Test
    fun hardwareShiftReturnBreaksTheLine() {
        assertFalse(ComposerReturn.sends(isReturnKey = true, keyDown = true, shift = true, fromSoftwareKeyboard = false))
        assertTrue(ComposerReturn.breaksLine(isReturnKey = true, keyDown = true, shift = true, fromSoftwareKeyboard = false))
    }

    @Test
    fun softwareKeyboardReturnBreaksTheLine() {
        assertFalse(ComposerReturn.sends(isReturnKey = true, keyDown = true, shift = false, fromSoftwareKeyboard = true))
        // The field's own handling does that one, as it always has.
        assertFalse(ComposerReturn.breaksLine(isReturnKey = true, keyDown = true, shift = false, fromSoftwareKeyboard = true))
        assertFalse(ComposerReturn.breaksLine(isReturnKey = true, keyDown = true, shift = true, fromSoftwareKeyboard = true))
    }

    @Test
    fun keyUpAndOtherKeysAreLeftAlone() {
        assertFalse(ComposerReturn.sends(isReturnKey = true, keyDown = false, shift = false, fromSoftwareKeyboard = false))
        assertFalse(ComposerReturn.sends(isReturnKey = false, keyDown = true, shift = false, fromSoftwareKeyboard = false))
        assertFalse(ComposerReturn.breaksLine(isReturnKey = true, keyDown = false, shift = true, fromSoftwareKeyboard = false))
        assertFalse(ComposerReturn.breaksLine(isReturnKey = false, keyDown = true, shift = true, fromSoftwareKeyboard = false))
    }

    @Test
    fun theBreakGoesAtTheCaret() {
        assertEquals(
            TextFieldValue("one\ntwo", TextRange(4)),
            ComposerReturn.breakLine(TextFieldValue("onetwo", TextRange(3))),
        )
        assertEquals(
            TextFieldValue("one\n", TextRange(4)),
            ComposerReturn.breakLine(TextFieldValue("one", TextRange(3))),
        )
    }

    @Test
    fun theBreakReplacesTheSelectionWhicheverWayItWasDragged() {
        val expected = TextFieldValue("one\ntwo", TextRange(4))
        assertEquals(expected, ComposerReturn.breakLine(TextFieldValue("one - two", TextRange(3, 6))))
        assertEquals(expected, ComposerReturn.breakLine(TextFieldValue("one - two", TextRange(6, 3))))
    }
}
