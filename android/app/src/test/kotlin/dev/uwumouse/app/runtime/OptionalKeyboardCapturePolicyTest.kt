package dev.uwumouse.app.runtime

import org.junit.Assert.*
import org.junit.Test

class OptionalKeyboardCapturePolicyTest {
    @Test fun unavailableOptionalShortcutDoesNotRetryUntilInventoryChangesButFullCaptureCanResume() {
        val gate=OptionalKeyboardCapturePolicy()
        assertTrue(gate.wantsCapture(true,false))
        assertTrue(gate.acceptCapabilityFailure(false,"no_keyboard_only_input_node_available"))
        repeat(100) { assertFalse(gate.wantsCapture(true,false)) }
        assertTrue(gate.wantsCapture(true,true))
        assertFalse(gate.wantsCapture(false,true))
        gate.inventoryChanged()
        assertTrue(gate.wantsCapture(true,false))
    }
    @Test fun activeCaptureAndUnknownFailuresRemainFatal() {
        val gate=OptionalKeyboardCapturePolicy()
        assertFalse(gate.acceptCapabilityFailure(true,"no_keyboard_only_input_node_available"))
        assertFalse(gate.acceptCapabilityFailure(false,"exclusive_input_grab_denied_errno_13"))
        assertFalse(gate.acceptCapabilityFailure(false,null))
        assertFalse(gate.unavailable)
        assertTrue(gate.acceptCapabilityFailure(false,"mixed_role_input_node_cannot_be_partially_grabbed"))
    }
}
