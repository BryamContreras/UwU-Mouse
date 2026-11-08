package dev.mobimapper.mobimapper.backend

import org.junit.Assert.*
import org.junit.Test

class CameraFocusLeaseTest {
    private val ms=1_000_000L

    @Test fun noObservationNeverGrantsFocusAndEachOperationStartsEmpty() {
        val lease=CameraFocusLease()
        assertNull(lease.observationAt(0));assertTrue(lease.shouldPrefetch(0))
        lease.observe(0,true);assertEquals(true,lease.observationAt(ms))
        assertNull(CameraFocusLease().observationAt(ms))
    }
    @Test fun positiveLeaseHasExactEightMillisecondBoundary() {
        val lease=CameraFocusLease();lease.observe(20*ms,true)
        assertEquals(true,lease.observationAt(20*ms))
        assertEquals(true,lease.observationAt(28*ms))
        assertNull(lease.observationAt(28*ms+1))
    }
    @Test fun prefetchBeginsOnlyAtFourMillisecondsAndReusesAYoungerLease() {
        val lease=CameraFocusLease();lease.observe(100*ms,true)
        assertFalse(lease.shouldPrefetch(104*ms-1))
        assertTrue(lease.shouldPrefetch(104*ms))
        lease.observe(104*ms,true)
        assertFalse(lease.shouldPrefetch(105*ms))
        assertEquals(true,lease.observationAt(112*ms))
    }
    @Test fun queryLatencyCountsAgainstTheLeaseSinceQueryStart() {
        val lease=CameraFocusLease();lease.observe(200*ms,true)
        assertNull(lease.observationAt(209*ms))
        assertTrue(lease.shouldPrefetch(209*ms))
    }
    @Test fun negativeObservationCannotBeReusedOrRenewedInsideThisOperation() {
        val lease=CameraFocusLease();lease.observe(10*ms,false)
        assertEquals(false,lease.observationAt(10*ms))
        assertFalse(lease.shouldPrefetch(100*ms))
        lease.observe(101*ms,true)
        assertEquals(false,lease.observationAt(101*ms))
    }
    @Test fun focusLostDuringPrefetchEndsAnOtherwisePositiveOperation() {
        val lease=CameraFocusLease();lease.observe(10*ms,true)
        assertTrue(lease.shouldPrefetch(14*ms))
        lease.observe(14*ms,false)
        assertEquals(false,lease.observationAt(15*ms))
    }
    @Test fun clockRegressionsNeverGrantOrRenewFocus() {
        val readRegression=CameraFocusLease();readRegression.observe(10*ms,true)
        assertEquals(false,readRegression.observationAt(10*ms-1))
        readRegression.observe(11*ms,true)
        assertEquals(false,readRegression.observationAt(11*ms))
        val queryRegression=CameraFocusLease();queryRegression.observe(10*ms,true)
        queryRegression.observe(9*ms,true)
        assertEquals(false,queryRegression.observationAt(10*ms))
    }
    @Test fun boundsRejectInvalidConfigurationAndDoNotOverflow() {
        assertThrows(IllegalArgumentException::class.java) { CameraFocusLease(0,1) }
        assertThrows(IllegalArgumentException::class.java) { CameraFocusLease(8,0) }
        assertThrows(IllegalArgumentException::class.java) { CameraFocusLease(8,9) }
        val lease=CameraFocusLease()
        assertThrows(IllegalArgumentException::class.java) { lease.observe(-1,true) }
        assertThrows(IllegalArgumentException::class.java) { lease.observationAt(-1) }
        lease.observe(Long.MAX_VALUE-8*ms,true)
        assertEquals(true,lease.observationAt(Long.MAX_VALUE))
    }
}
