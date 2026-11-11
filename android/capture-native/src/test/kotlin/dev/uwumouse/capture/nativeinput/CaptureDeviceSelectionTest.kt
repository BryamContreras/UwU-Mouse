package dev.uwumouse.capture.nativeinput

import dev.uwumouse.capture.api.CaptureDevice
import dev.uwumouse.capture.api.CaptureRequest
import org.junit.Assert.*
import org.junit.Test

class CaptureDeviceSelectionTest {
    private fun device(id: Int, name: String, mouse: Boolean = false, keyboard: Boolean = false, bus: Int = 3) =
        CaptureDevice("/dev/input/event$id", name, id, bus, 1, 1, mouse, keyboard)

    private val mouse = device(15, "Logitech G502 HERO Gaming Mouse", mouse = true)
    private val mouseKeyboard = device(16, "Logitech G502 HERO Gaming Mouse Keyboard", keyboard = true)
    private val keyboard = device(19, "YJX_CHIP USB KEYBOARD Keyboard", keyboard = true)
    private val auxiliaryMouse = device(20, "YJX_CHIP USB KEYBOARD Mouse", mouse = true)
    private val inventory = listOf(mouse, mouseKeyboard, keyboard, auxiliaryMouse)
    private fun request(mousePath: String? = null, mouse: Boolean = true, keyboard: Boolean = true) =
        CaptureRequest("com.dts.freefireth", 2, mousePath = mousePath, captureMouse = mouse, captureKeyboard = keyboard)

    @Test fun keyboardAuxiliaryMouseDoesNotPreventCapturingBothRelativeNodes() {
        assertEquals(listOf(mouse, auxiliaryMouse, mouseKeyboard, keyboard), CaptureDeviceSelection.select(inventory, request()))
    }

    @Test fun explicitMouseSelectionStillExcludesOtherMouseOnlyNodes() {
        assertEquals(listOf(mouse, mouseKeyboard, keyboard), CaptureDeviceSelection.select(inventory, request(mouse.path)))
    }

    @Test fun deviceNamesAndVendorLabelsDoNotChooseTheMouse() {
        val renamed = inventory.map { it.copy(name = "identical", vendorId = 123, productId = 456) }
        assertEquals(setOf(15, 16, 19, 20), CaptureDeviceSelection.select(renamed, request()).map { it.deviceId }.toSet())
    }

    @Test fun keyboardOnlyCaptureLeavesBothMouseNodesAvailableToAndroid() {
        assertEquals(listOf(mouseKeyboard, keyboard), CaptureDeviceSelection.select(inventory, request(mouse = false)))
    }

    @Test fun mouseOnlyCaptureDoesNotGrabIndependentKeyboardInterfaces() {
        assertEquals(listOf(mouse, auxiliaryMouse), CaptureDeviceSelection.select(inventory, request(keyboard = false)))
    }

    @Test fun missingMouseOrExplicitPathFailsInsteadOfAcquiringOnlyKeyboard() {
        assertThrows(IllegalStateException::class.java) { CaptureDeviceSelection.select(listOf(keyboard), request()) }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureDeviceSelection.select(inventory, request("/dev/input/event99"))
        }
    }

    @Test fun internalAndNonEvdevDevicesAreNeverSelected() {
        val internal = device(1, "internal mouse", mouse = true, bus = 0x19)
        val invalid = device(2, "outside evdev", mouse = true).copy(path = "/data/local/tmp/event2")
        val bluetooth = device(3, "BT mouse", mouse = true, bus = 5)
        assertEquals(listOf(bluetooth), CaptureDeviceSelection.select(listOf(internal, invalid, bluetooth), request(keyboard = false)))
    }

    @Test fun mixedRoleNodeIsAcquiredOnceAndCannotBePartiallyGrabbed() {
        val mixed = device(5, "combined", mouse = true, keyboard = true)
        assertEquals(listOf(mixed), CaptureDeviceSelection.select(listOf(mixed), request()))
        for (partial in listOf(request(mouse = false), request(keyboard = false))) {
            assertThrows(IllegalStateException::class.java) { CaptureDeviceSelection.select(listOf(mixed), partial) }
        }
    }

    @Test fun automaticKeyboardOnlyCaptureSkipsCombinedMouseNodesWithoutGrabbingTheirMouse() {
        val mixed = device(5, "combined receiver", mouse = true, keyboard = true)
        val result = CaptureDeviceSelection.select(listOf(mixed, keyboard, mouse), request(mouse = false))
        assertEquals(listOf(keyboard), result)
        assertTrue(result.none { it.mouse })
        assertEquals(listOf(mixed, mouse, keyboard),
            CaptureDeviceSelection.select(listOf(mixed, keyboard, mouse), request()))
    }

    @Test fun automaticMouseOnlyCaptureSkipsCombinedKeyboardNodes() {
        val mixed = device(5, "combined receiver", mouse = true, keyboard = true)
        assertEquals(listOf(mouse), CaptureDeviceSelection.select(listOf(mixed, mouse, keyboard), request(keyboard = false)))
    }

    @Test fun missingIndependentKeyboardHasAnExplicitRoleOnlyCapabilityFailure() {
        val mixed = device(5, "combined receiver", mouse = true, keyboard = true)
        for (rows in listOf(listOf(mixed), listOf(mouse), emptyList())) {
            val failure = assertThrows(IllegalStateException::class.java) {
                CaptureDeviceSelection.select(rows, request(mouse = false))
            }
            assertEquals("no_keyboard_only_input_node_available", failure.message)
        }
    }

    @Test fun explicitCombinedRoleKeyboardIsNeverSilentlyReplacedByAnotherKeyboard() {
        val mixed = device(5, "combined receiver", mouse = true, keyboard = true)
        val partial = request(mouse = false).copy(keyboardPaths = listOf(mixed.path))
        val failure = assertThrows(IllegalStateException::class.java) {
            CaptureDeviceSelection.select(listOf(mixed, keyboard), partial)
        }
        assertEquals("mixed_role_input_node_cannot_be_partially_grabbed", failure.message)
    }

    @Test fun duplicateInventoryPathsFailRatherThanAssignTwoCallbackSlotsToOneNode() {
        assertThrows(IllegalStateException::class.java) { CaptureDeviceSelection.select(listOf(mouse, mouse), request()) }
    }

    @Test fun explicitMouseCannotSilentlyAcquireAnotherMixedRoleMouseViaKeyboardSelection() {
        val mixed = device(5, "combined", mouse = true, keyboard = true)
        assertThrows(IllegalStateException::class.java) { CaptureDeviceSelection.select(listOf(mouse, mixed), request(mouse.path)) }
        assertEquals(listOf(mixed), CaptureDeviceSelection.select(listOf(mouse, mixed), request(mixed.path)))
    }

    @Test fun automaticCaptureStillHonorsNativeSixteenNodeLimit() {
        val sixteen = (0 until 16).map { device(it, "mouse $it", mouse = true) }
        assertEquals(16, CaptureDeviceSelection.select(sixteen, request(keyboard = false)).size)
        assertThrows(IllegalArgumentException::class.java) {
            CaptureDeviceSelection.select(sixteen + device(16, "extra", mouse = true), request(keyboard = false))
        }
    }
}
