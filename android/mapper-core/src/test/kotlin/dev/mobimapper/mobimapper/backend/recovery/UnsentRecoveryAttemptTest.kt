package dev.mobimapper.mobimapper.backend.recovery

import dev.mobimapper.mobimapper.engine.TouchComposer
import dev.mobimapper.mobimapper.engine.TouchFrame
import org.junit.Assert.*
import org.junit.Test

class UnsentRecoveryAttemptTest {
    private val down=TouchFrame(0,1000L,1000L,intArrayOf(8),floatArrayOf(690f),floatArrayOf(860f))
    private fun active():RecoveryJournal=RecoveryJournal().apply {
        check(begin(1,1000,2000,10001));check(prepare(1,down));check(acknowledged(1,down,true))
    }

    @Test fun abortPreservesConfirmedContactsAndTimestampHighWater() {
        val journal=active()
        val up=down.copy(action=TouchComposer.UP,eventTime=1100L)
        val before=journal.snapshot()
        assertTrue(journal.prepare(1,up));assertTrue(journal.abortUnsent(1,up))
        val after=journal.snapshot()
        assertEquals(RecoveryInjectionState.ACTIVE,after.state)
        assertNull(after.attempted)
        assertArrayEquals(requireNotNull(before.activeFrame).ids,requireNotNull(after.activeFrame).ids)
        assertArrayEquals(requireNotNull(before.lastConfirmed).xs,requireNotNull(after.lastConfirmed).xs,0f)
        assertFalse(journal.abortUnsent(1,up))
        assertFalse(journal.prepare(1,down.copy(action=2,eventTime=1099L)))
        val move=down.copy(action=2,eventTime=1100L,xs=floatArrayOf(700f))
        assertTrue(journal.prepare(1,move));assertTrue(journal.acknowledged(1,move,true))
        assertEquals(700f,requireNotNull(journal.snapshot().activeFrame).xs.single(),0f)
    }

    @Test fun onlyTheExactCurrentSessionAndPreparedFrameMayBeAborted() {
        val journal=active()
        val up=down.copy(action=1,eventTime=1100L)
        assertTrue(journal.prepare(1,up))
        for(changed in listOf(up.copy(eventTime=1101L),up.copy(xs=floatArrayOf(691f)),up.copy(action=2)))
            assertFalse(journal.abortUnsent(1,changed))
        assertFalse(journal.abortUnsent(2,up))
        assertNotNull(journal.snapshot().attempted)
        assertTrue(journal.abortUnsent(1,up))
    }

    @Test fun anAmbiguousAttemptCanNeverBeRemovedByAbortUnsent() {
        val journal=active()
        val up=down.copy(action=1,eventTime=1100L)
        assertTrue(journal.prepare(1,up));assertTrue(journal.fail(1))
        assertFalse(journal.abortUnsent(1,up))
        val candidate=requireNotNull(journal.cancellationFrame(1200L))
        assertArrayEquals(up.ids,candidate.ids)
        assertArrayEquals(up.xs,candidate.xs,0f)
        assertEquals(up.downTime,candidate.downTime)
    }

    @Test fun anAcknowledgedReleaseCannotBeRestoredByAbortUnsent() {
        val journal=active()
        val up=down.copy(action=1,eventTime=1100L)
        assertTrue(journal.prepare(1,up));assertTrue(journal.acknowledged(1,up,true))
        assertFalse(journal.abortUnsent(1,up))
        assertNull(journal.snapshot().activeFrame)
        assertNull(journal.snapshot().attempted)
    }
}
