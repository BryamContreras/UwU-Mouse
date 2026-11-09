package dev.uwumouse.app.backend

import org.junit.Assert.*
import org.junit.Test

class WorkerDeadlineTest {
    @Test fun continuousTrafficKeepsTimeoutsWithoutWakingForEveryAck() {
        val deadline=WorkerDeadline()
        assertNull(deadline.prepareWait(0))
        assertTrue(deadline.arm(0,1500))
        assertEquals(1500L,deadline.prepareWait(0))
        // A thousand completed exchanges replace the deadline while the watchdog sleeps.
        for(now in 1L..1000L) {
            deadline.clear()
            assertFalse(deadline.arm(now,1500))
        }
        assertEquals(1000L,deadline.prepareWait(1500))
        assertEquals(0L,deadline.prepareWait(2500))
    }

    @Test fun shorterOperationInterruptsALongerWatchdogWait() {
        val deadline=WorkerDeadline()
        assertTrue(deadline.arm(0,3000))
        assertEquals(3000L,deadline.prepareWait(0))
        deadline.clear()
        assertTrue(deadline.arm(100,1500))
        assertEquals(1500L,deadline.prepareWait(100))
        assertEquals(-1L,deadline.prepareWait(1601))
    }

    @Test fun clearedOperationCannotExpireAndAnIdleWatchdogIsWoken() {
        val deadline=WorkerDeadline()
        deadline.arm(0,1500)
        deadline.prepareWait(0)
        deadline.clear()
        assertNull(deadline.prepareWait(1500))
        assertTrue(deadline.arm(2000,1500))
        assertEquals(1500L,deadline.prepareWait(2000))
    }
}
