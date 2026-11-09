package dev.uwumouse.app.session

import org.junit.Assert.*
import org.junit.Test

class InputDeviceChangePolicyTest {
    private val keyboard = InputDeviceSnapshot("usb-keyboard", 1133, 50475, 0x101, 2,
        external=true,virtual=false,enabled=true,supportedKeys=setOf(29,32,47,51,62,135),name="Keyboard")
    private val relativeX = InputDeviceMotionRange(27,0x2002,-1f,1f,0f,0f,1f)
    private val relativeY = relativeX.copy(axis=28)
    private val mouse = keyboard.copy(descriptor="usb-mouse",sources=0x2002,keyboardType=0,
        motionRanges=setOf(relativeX,relativeY),supportedKeys=emptySet(),name="Mouse")

    @Test fun keyboardLayoutNotificationWithoutChangedCapabilitiesDoesNotPauseAWorkingSession() {
        for (changed in listOf(keyboard.copy(),keyboard.copy(name="Desktop keyboard"),
            keyboard.copy(supportedKeys=keyboard.supportedKeys.reversed().toSet()))) {
            val decision=InputDeviceChangePolicy.evaluate(keyboard,changed)
            assertFalse(decision.pause)
            assertEquals("mapping_capabilities_unchanged",decision.reason)
            assertTrue(decision.changedFields.isEmpty())
        }
    }

    @Test fun internalAndVirtualDeviceReconfigurationCannotPauseExternalKeyboardAndMouseMapping() {
        val internal=keyboard.copy(descriptor="internal",external=false,supportedKeys=emptySet())
        val virtual=keyboard.copy(descriptor="virtual",virtual=true)
        for (device in listOf(internal,virtual)) {
            val changed=device.copy(sources=0x2002,keyboardType=0,enabled=false,descriptor="reconfigured")
            val decision=InputDeviceChangePolicy.evaluate(device,changed)
            assertFalse(decision.pause)
            assertEquals("unrelated_internal_or_virtual",decision.reason)
            assertFalse(InputDeviceChangePolicy.evaluate(null,device).pause)
            assertFalse(InputDeviceChangePolicy.evaluate(device,null).pause)
        }
    }

    @Test fun changingTheIdentityOrUsableCapabilitiesOfAnExternalDeviceRequiresAPause() {
        val material=listOf(
            keyboard.copy(descriptor="different") to "descriptor",
            keyboard.copy(vendorId=1) to "vendorId",
            keyboard.copy(productId=1) to "productId",
            keyboard.copy(sources=0x2002) to "sources",
            keyboard.copy(keyboardType=0) to "keyboardType",
            keyboard.copy(external=false) to "external",
            keyboard.copy(virtual=true) to "virtual",
            keyboard.copy(enabled=false) to "enabled",
            keyboard.copy(supportedKeys=keyboard.supportedKeys-51) to "supportedKeys",
        )
        for ((changed,field) in material) {
            val decision=InputDeviceChangePolicy.evaluate(keyboard,changed)
            assertTrue("Changed $field",decision.pause)
            assertEquals("external_material_change",decision.reason)
            assertEquals(listOf(field),decision.changedFields)
        }
    }

    @Test fun relativeMouseRangesAreOrderIndependentButTheirLossOrScaleChangeIsMaterial() {
        assertFalse(InputDeviceChangePolicy.evaluate(mouse,mouse.copy(motionRanges=linkedSetOf(relativeY,relativeX))).pause)
        for (ranges in listOf(setOf(relativeX),setOf(relativeX.copy(resolution=2f),relativeY))) {
            val decision=InputDeviceChangePolicy.evaluate(mouse,mouse.copy(motionRanges=ranges))
            assertTrue(decision.pause)
            assertEquals(listOf("motionRanges"),decision.changedFields)
        }
    }

    @Test fun missingOrUntrackedExternalDevicesCannotBeTreatedAsBenignMetadata() {
        val lost=InputDeviceChangePolicy.evaluate(keyboard,null)
        assertTrue(lost.pause)
        assertEquals("external_unavailable",lost.reason)
        val replacement=InputDeviceChangePolicy.evaluate(null,keyboard)
        assertTrue(replacement.pause)
        assertEquals("external_not_in_cache",replacement.reason)
        assertTrue(InputDeviceChangePolicy.evaluate(null,null).pause)
    }

    @Test fun aPreviouslyInternalOrVirtualDeviceBecomingPhysicalIsNotAnUnrelatedChange() {
        assertTrue(InputDeviceChangePolicy.evaluate(keyboard.copy(external=false),keyboard).pause)
        assertTrue(InputDeviceChangePolicy.evaluate(mouse.copy(virtual=true),mouse).pause)
    }
}
