package dev.uwumouse.app.runtime

import org.junit.Assert.*
import org.junit.Test

class CaptureReleaseWaitTest {
    @Test fun sustainedHeldInputKeepsOneWaitWithoutHotRetriesOrRenewingItsDeadline() {
        val wait=CaptureReleaseWait()
        assertTrue(wait.mayAttempt(0))
        wait.held(0)
        for(at in 1_000L..6_000L step 1_000L) {
            assertFalse(wait.mayAttempt(at-1))
            assertTrue(wait.mayAttempt(at))
            wait.held(at)
        }
        assertFalse(wait.expired(29_999))
        assertTrue(wait.expired(30_000))
        assertFalse(wait.mayAttempt(30_000))
        wait.held(30_000)
        assertTrue(wait.expired(30_000))
    }

    @Test fun successOrCancellationRemovesTheOldWaitBeforeANewOwner() {
        val wait=CaptureReleaseWait()
        wait.held(1_000);assertTrue(wait.pending)
        wait.clear()
        assertFalse(wait.pending);assertTrue(wait.mayAttempt(1_001))
        wait.held(50_000)
        assertFalse(wait.expired(79_999));assertTrue(wait.expired(80_000))
    }

    @Test fun clockRegressionExpiresTheWaitRatherThanExtendingIt() {
        val wait=CaptureReleaseWait();wait.held(1_000)
        assertTrue(wait.expired(999));assertFalse(wait.mayAttempt(999))
    }
}
