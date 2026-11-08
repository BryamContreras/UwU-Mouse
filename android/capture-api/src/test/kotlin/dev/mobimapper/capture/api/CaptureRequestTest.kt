package dev.mobimapper.capture.api

import org.junit.Assert.*
import org.junit.Test

class CaptureRequestTest {
    @Test fun movementTransportRateAcceptsUserChoicesAndExistingProfilesWithoutAffectingEdges() {
        for(rate in listOf(0,60,90,100,120,144,240,500,1000)) {
            assertEquals(rate,CaptureRequest("dev.mobimapper.inputlab",2,mouseRateHz=rate).mouseRateHz)
        }
        for(rate in listOf(-1,1001,Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java){CaptureRequest("dev.mobimapper.inputlab",2,mouseRateHz=rate)}
        }
    }

    @Test fun leaseCannotOutliveTwoSecondsOrDisableEverySource() {
        for (lease in listOf(0L, 249L, 2_001L)) {
            assertThrows(IllegalArgumentException::class.java) { CaptureRequest("dev.mobimapper.inputlab", 0, leaseDurationMs = lease) }
        }
        assertThrows(IllegalArgumentException::class.java) { CaptureRequest("dev.mobimapper.inputlab", 0, captureMouse = false, captureKeyboard = false) }
    }

    @Test fun explicitPathsCannotEscapeEvdevAndKeyboardNodesCannotRepeat() {
        for (path in listOf("/dev/input/../event1", "/dev/input/event", "/dev/input/event1/extra", "/dev/input/event-1", "/data/local/tmp/event1")) {
            assertThrows(IllegalArgumentException::class.java) { CaptureRequest("dev.mobimapper.inputlab", 0, mousePath = path) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureRequest("dev.mobimapper.inputlab", 0, keyboardPaths = listOf("/dev/input/event1", "/dev/input/event1"))
        }
    }

    @Test fun targetAndDisplayMustBeExplicit() {
        assertThrows(IllegalArgumentException::class.java) { CaptureRequest("", 0) }
        assertThrows(IllegalArgumentException::class.java) { CaptureRequest("dev.mobimapper.inputlab", -1) }
        val request = CaptureRequest("dev.mobimapper.inputlab", 0, mousePath = "/dev/input/event17", captureKeyboard = false)
        assertTrue(request.captureMouse)
        assertFalse(request.captureKeyboard)
        assertEquals(1_500L, request.leaseDurationMs)
    }
}
