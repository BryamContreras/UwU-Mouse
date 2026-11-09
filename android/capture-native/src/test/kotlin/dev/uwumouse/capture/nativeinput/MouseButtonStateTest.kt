package dev.uwumouse.capture.nativeinput

import org.junit.Assert.*
import org.junit.Test

class MouseButtonStateTest {
    @Test fun movementFromAnotherMouseDoesNotReleaseHeldPrimaryButton() {
        val buttons = MouseButtonState()
        assertEquals(1, buttons.update(0, 1))
        assertEquals(1, buttons.update(1, 0))
        assertEquals(0, buttons.update(0, 0))
    }

    @Test fun sameButtonRemainsDownUntilBothNodesReleaseIt() {
        val buttons = MouseButtonState()
        assertEquals(1, buttons.update(0, 1))
        assertEquals(1, buttons.update(1, 1))
        assertEquals(1, buttons.update(0, 0))
        assertEquals(0, buttons.update(1, 0))
    }

    @Test fun independentButtonsRemainCombinedAcrossAllSixteenNodes() {
        val buttons = MouseButtonState()
        assertEquals(1, buttons.update(0, 1))
        assertEquals(3, buttons.update(15, 2))
        assertEquals(19, buttons.update(5, 16))
        assertEquals(18, buttons.update(0, 0))
        assertEquals(2, buttons.update(5, 0))
        assertEquals(0, buttons.update(15, 0))
    }

    @Test fun newCaptureCannotInheritHeldButtonsFromAnOldCapture() {
        val buttons = MouseButtonState()
        buttons.update(0, 31)
        buttons.reset()
        assertEquals(0, buttons.update(1, 0))
    }

    @Test fun invalidSlotsAndUnknownButtonBitsAreRejected() {
        val buttons = MouseButtonState()
        for (slot in listOf(-1, 16)) assertThrows(IllegalArgumentException::class.java) { buttons.update(slot, 0) }
        for (mask in listOf(-1, 32)) assertThrows(IllegalArgumentException::class.java) { buttons.update(0, mask) }
    }
}
