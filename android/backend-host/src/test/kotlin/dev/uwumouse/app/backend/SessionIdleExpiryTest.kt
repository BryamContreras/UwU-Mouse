package dev.uwumouse.app.backend

import org.junit.Assert.*
import org.junit.Test

class SessionIdleExpiryTest {
    private val minute=60_000L

    @Test fun twoHoursOfAcknowledgedActivityKeepOneTimerUntilEachDueCheck() {
        var now=0L
        val expiry=SessionIdleExpiry({now})
        var nextTimer=now+expiry.begin(1L)
        var scheduledTimers=1
        repeat(120) { elapsedMinutes ->
            now=(elapsedMinutes+1)*minute
            assertTrue(expiry.acceptedActivity(1L))
            if(now>=nextTimer) {
                val delay=requireNotNull(expiry.remainingMillis(1L))
                assertTrue(delay>0L)
                nextTimer=now+delay
                scheduledTimers++
            }
        }
        assertEquals(120*minute,now)
        assertEquals(30*minute,expiry.remainingMillis(1L))
        assertEquals(5,scheduledTimers)
        now+=30*minute
        assertEquals(0L,expiry.remainingMillis(1L))
    }

    @Test fun inactivityExpiresExactlyAtThirtyMinutesAndActivityCannotReviveIt() {
        var now=7L
        val expiry=SessionIdleExpiry({now})
        assertEquals(30*minute,expiry.begin(1L))
        now+=30*minute-1L
        assertEquals(1L,expiry.remainingMillis(1L))
        now++
        assertEquals(0L,expiry.remainingMillis(1L))
        assertFalse(expiry.acceptedActivity(1L))
        now+=minute
        assertEquals(0L,expiry.remainingMillis(1L))
    }

    @Test fun activityMovesTheDeadlineButDoesNotAddAnAbsoluteSessionLimit() {
        var now=0L
        val expiry=SessionIdleExpiry({now})
        expiry.begin(1L)
        now=29*minute
        assertTrue(expiry.acceptedActivity(1L))
        now=30*minute
        assertEquals(29*minute,expiry.remainingMillis(1L))
        now=58*minute
        assertEquals(minute,expiry.remainingMillis(1L))
        now=59*minute
        assertEquals(0L,expiry.remainingMillis(1L))
    }

    @Test fun aDelayedTimerCannotLetActivityAfterTheIdleBudgetReopenTheSession() {
        var now=0L
        val expiry=SessionIdleExpiry({now})
        expiry.begin(1L)
        now=30*minute
        assertFalse(expiry.acceptedActivity(1L))
        assertEquals(0L,expiry.remainingMillis(1L))
        now++
        assertFalse(expiry.acceptedActivity(1L))
    }

    @Test fun staleIdsCannotRefreshOrExpireTheCurrentSessionOrReadItsClock() {
        var now=0L
        var clockReads=0
        val expiry=SessionIdleExpiry({clockReads++;now})
        expiry.begin(1L)
        now=minute
        expiry.begin(2L)
        val readsBefore=clockReads
        assertFalse(expiry.acceptedActivity(1L))
        assertFalse(expiry.acceptedActivity(3L))
        assertNull(expiry.remainingMillis(1L))
        assertEquals(readsBefore,clockReads)
        now=2*minute
        assertEquals(29*minute,expiry.remainingMillis(2L))
    }

    @Test fun clearPreventsLateActivityAndOnlyANewIdCanArmAnotherSession() {
        var now=0L
        val expiry=SessionIdleExpiry({now})
        expiry.begin(1L)
        expiry.clear()
        now=minute
        assertFalse(expiry.acceptedActivity(1L))
        assertNull(expiry.remainingMillis(1L))
        assertEquals(0L,expiry.sessionId)
        assertThrows(IllegalArgumentException::class.java) { expiry.begin(1L) }
        assertEquals(30*minute,expiry.begin(2L))
        assertTrue(expiry.acceptedActivity(2L))
    }

    @Test fun clockRegressionFailsClosedUntilAnExplicitNewSession() {
        var now=10L
        val expiry=SessionIdleExpiry({now})
        expiry.begin(1L)
        now=20L
        assertTrue(expiry.acceptedActivity(1L))
        now=19L
        assertEquals(0L,expiry.remainingMillis(1L))
        now=21L
        assertFalse(expiry.acceptedActivity(1L))
        assertEquals(0L,expiry.remainingMillis(1L))
        expiry.begin(2L)
        assertEquals(30*minute,expiry.remainingMillis(2L))
    }

    @Test fun invalidActivityClockCannotExtendTheDeadlineOrReviveAfterClockRecovery() {
        var now=0L
        val expiry=SessionIdleExpiry({now})
        expiry.begin(1L)
        now=-1L
        assertFalse(expiry.acceptedActivity(1L))
        now=1L
        assertEquals(0L,expiry.remainingMillis(1L))
        assertFalse(expiry.acceptedActivity(1L))
    }

    @Test fun invalidConfigurationOrBeginDoesNotReplaceAnArmedSession() {
        var now=0L
        assertThrows(IllegalArgumentException::class.java) { SessionIdleExpiry({now},0L) }
        val expiry=SessionIdleExpiry({now})
        assertThrows(IllegalArgumentException::class.java) { expiry.begin(0L) }
        expiry.begin(1L)
        now=-1L
        assertThrows(IllegalArgumentException::class.java) { expiry.begin(2L) }
        assertEquals(1L,expiry.sessionId)
        now=1L
        assertEquals(30*minute-1L,expiry.remainingMillis(1L))
    }
}
