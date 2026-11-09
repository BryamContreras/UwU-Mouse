package dev.uwumouse.app.session

import org.junit.Assert.*
import org.junit.Test

class PhysicalMouseInputPolicyTest {
    private fun reject(event:Int,device:Int,external:Boolean=true,virtual:Boolean=false)=
        PhysicalMouseInputPolicy.rejectionReason(event,device,external,virtual)

    @Test fun observedSamsungDexHybridSourceIsAdmittedOnlyWithActualMouseCapabilities() {
        // Actual DeX callback: 12290 == MOUSE(8194) | TOUCHSCREEN(4098).
        assertNull(reject(12290,12290))
        assertNull(reject(12290,8194))
        assertEquals("device_no_mouse",reject(12290,4098))
    }

    @Test fun ordinaryAndRelativeMouseSourcesKeepWorkingForPhysicalMice() {
        assertNull(reject(8194,8194))
        assertNull(reject(131076,131076))
        assertNull(reject(131076,8194))
        assertNull(reject(8194,131076))
    }

    @Test fun touchOnlyAndInjectedTouchSourcesDoNotBecomeMouseEvenWithAMouseDeviceId() {
        for(device in listOf(4098,8194,12290)) assertEquals("touchscreen",reject(4098,device))
    }

    @Test fun stylusGamepadJoystickAndPointerClassWithoutMouseBitsAreRejected() {
        for(source in listOf(0x4002,0xC002,0x401,0x01000010,0x2,0))
            assertEquals("Non-mouse source $source","non_mouse_source",reject(source,8194))
        assertEquals("touchscreen",reject(0x5002,8194)) // Stylus plus touchscreen, still no mouse.
    }

    @Test fun aMouseEventCannotClaimADeviceWhoseCapabilitiesOnlyContainOtherSources() {
        for(device in listOf(0x1002,0x4002,0x401,0x01000010,0x2,0))
            assertEquals("Non-mouse device $device","device_no_mouse",reject(8194,device))
    }

    @Test fun virtualAndInternalDevicesRemainExcludedEvenWithHybridMouseSources() {
        assertEquals("internal",reject(12290,12290,external=false))
        assertEquals("virtual",reject(12290,12290,virtual=true))
        assertEquals("internal",reject(131076,131076,external=false,virtual=true))
    }
}
