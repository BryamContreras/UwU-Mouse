package dev.uwumouse.app.session

import org.junit.Assert.*
import org.junit.Test

class LocalPointerGestureGateTest {
    @Test fun dragKeepsOriginalWidgetAndReleaseClearsBeforeCloseCallback() {
        val gate=LocalPointerGestureGate()
        assertEquals(LocalPointerTarget.MENU,gate.press(LocalPointerTarget.MENU))
        assertEquals(LocalPointerTarget.NONE,gate.press(LocalPointerTarget.BUBBLE))
        assertEquals(LocalPointerTarget.MENU,gate.owner)
        assertEquals(LocalPointerTarget.MENU,gate.release())
        assertEquals(LocalPointerTarget.NONE,gate.owner)
        assertEquals(LocalPointerTarget.NONE,gate.cancel())
        assertEquals(LocalPointerTarget.NONE,gate.release())
    }
    @Test fun outsidePressCannotProduceUpOnAWidgetEnteredLater() {
        val gate=LocalPointerGestureGate()
        assertEquals(LocalPointerTarget.NONE,gate.press(LocalPointerTarget.NONE))
        assertEquals(LocalPointerTarget.NONE,gate.owner)
        assertEquals(LocalPointerTarget.NONE,gate.release())
    }
    @Test fun cancellationCannotCarryOwnershipToReplacementMenu() {
        val gate=LocalPointerGestureGate()
        gate.press(LocalPointerTarget.MENU)
        assertEquals(LocalPointerTarget.MENU,gate.cancel())
        assertEquals(LocalPointerTarget.NONE,gate.release())
        assertEquals(LocalPointerTarget.BUBBLE,gate.press(LocalPointerTarget.BUBBLE))
        assertEquals(LocalPointerTarget.BUBBLE,gate.release())
    }
}
