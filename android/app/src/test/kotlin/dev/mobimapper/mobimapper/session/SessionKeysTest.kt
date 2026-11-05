package dev.mobimapper.mobimapper.session

import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test

class SessionKeysTest {
    @Test fun physicalF9StopsAndItsLinuxScancodeDoesNot() {
        // Observed Logitech trace: KEYCODE_F9, scan=67. Android's key code is 139.
        assertEquals(SessionKeyAction.STOP, SessionKeys.actionFor(139))
        assertTrue(SessionKeys.consumes(KeyEvent.KEYCODE_F9))
        assertEquals(SessionKeyAction.PASS_THROUGH, SessionKeys.actionFor(67))
        assertEquals(SessionKeyAction.PASS_THROUGH, SessionKeys.actionFor(KeyEvent.KEYCODE_SYSRQ))
    }
    @Test fun cameraToggleAndUnassignedKeysStayDistinct() {
        assertEquals(SessionKeyAction.CAMERA_TOGGLE, SessionKeys.actionFor(KeyEvent.KEYCODE_F8))
        assertFalse(SessionKeys.consumes(KeyEvent.KEYCODE_FUNCTION))
        assertFalse(SessionKeys.consumes(KeyEvent.KEYCODE_Q))
    }
}
