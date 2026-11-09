package dev.uwumouse.app.session

import org.junit.Assert.*
import org.junit.Test

class ProfileSwitchGateTest {
    private fun identity(device:Int,code:Int)=(device.toLong() shl 32) or code.toLong()

    @Test fun aHeldTriggerSelectsOnceAndItsUpRemainsOwnedAfterConfigurationOrContextChanges() {
        val gate=ProfileSwitchGate();val key=identity(1,35)
        assertEquals("one",gate.press(key,35,true,true,mapOf(35 to "one")))
        assertNull(gate.press(key,35,true,true,mapOf(35 to "two")))
        assertTrue(gate.owns(key));assertFalse(gate.isEmpty)
        assertNull(gate.press(key,35,false,false,emptyMap()))
        assertTrue(gate.release(key));assertTrue(gate.isEmpty)
        assertEquals("two",gate.press(key,35,true,true,mapOf(35 to "two")))
    }
    @Test fun repeatedOrOutOfContextKeysNeverBeginAProfileSwitch() {
        val gate=ProfileSwitchGate();val key=identity(1,35);val bindings=mapOf(35 to "one")
        assertNull(gate.press(key,35,false,true,bindings))
        assertNull(gate.press(key,35,true,false,bindings))
        assertNull(gate.press(key,36,true,true,bindings));assertTrue(gate.isEmpty)
        assertFalse(gate.release(key))
    }
    @Test fun keyboardAssignmentOwnsTheWholePairWithoutCreatingAnActivation() {
        val gate=ProfileSwitchGate();val key=identity(1,35)
        gate.claim(key)
        assertNull(gate.press(key,35,true,true,mapOf(35 to "one")))
        assertTrue(gate.owns(key));assertTrue(gate.release(key));assertTrue(gate.isEmpty)
    }
    @Test fun disconnectAndForcedFilterResetClearOnlyTheLostOwners() {
        val gate=ProfileSwitchGate();val first=identity(1,35);val second=identity(2,35)
        gate.claim(first);gate.claim(second);gate.removeDevice(1)
        assertFalse(gate.owns(first));assertTrue(gate.owns(second))
        gate.clear();assertTrue(gate.isEmpty);assertFalse(gate.release(second))
    }
}
