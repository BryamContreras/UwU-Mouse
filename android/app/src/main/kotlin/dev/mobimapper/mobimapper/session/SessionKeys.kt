package dev.mobimapper.mobimapper.session

import android.view.KeyEvent

enum class SessionKeyAction { MAPPING, CAMERA_TOGGLE, STOP, PASS_THROUGH }

/** Android key codes are distinct from Linux scancodes; see the physical F9 regression trace. */
object SessionKeys {
    fun actionFor(code: Int): SessionKeyAction = when(code) {
        KeyEvent.KEYCODE_F9 -> SessionKeyAction.STOP
        KeyEvent.KEYCODE_F8 -> SessionKeyAction.CAMERA_TOGGLE
        KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S,
        KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_SPACE -> SessionKeyAction.MAPPING
        else -> SessionKeyAction.PASS_THROUGH
    }
    fun consumes(code: Int) = actionFor(code) != SessionKeyAction.PASS_THROUGH
}
