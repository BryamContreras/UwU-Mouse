package dev.uwumouse.app.backend.recovery

import org.junit.Assert.*
import org.junit.Test

class RecoveryNoticeGateTest {
    @Test fun admittedBeforeDelayedStartedCannotRegressToRecovering() {
        val gate = RecoveryNoticeGate()
        assertTrue(gate.begin(10))
        assertTrue(gate.accept(10, 4, RecoveryNoticeGate.ADMITTED))
        assertFalse(gate.accept(10, 4, RecoveryNoticeGate.STARTED))
        assertEquals(10L, gate.currentSessionId)
        assertEquals(4L, gate.latestWorkerGeneration)
        assertEquals(RecoveryNoticeGate.ADMITTED, gate.currentPhase)
        assertEquals(1L, gate.discarded)
    }

    @Test fun oldSessionCallbacksAfterNewBeginCannotAffectTheNewSession() {
        val gate = RecoveryNoticeGate()
        assertTrue(gate.begin(10))
        assertTrue(gate.accept(10, 8, RecoveryNoticeGate.STARTED))
        assertTrue(gate.begin(11))
        assertEquals(0L, gate.latestWorkerGeneration)
        assertEquals(0, gate.currentPhase)
        assertFalse(gate.accept(10, 9, RecoveryNoticeGate.ADMITTED))
        assertFalse(gate.accept(10, 10, RecoveryNoticeGate.FAILED))
        assertEquals(11L, gate.currentSessionId)
        assertEquals(0L, gate.latestWorkerGeneration)
        assertEquals(0, gate.currentPhase)
        assertEquals(2L, gate.discarded)
        assertTrue(gate.accept(11, 1, RecoveryNoticeGate.STARTED))
    }

    @Test fun duplicateAndConflictingTerminalNoticesAreRejectedForTheSameGeneration() {
        for (terminal in listOf(RecoveryNoticeGate.ADMITTED, RecoveryNoticeGate.FAILED)) {
            val gate = RecoveryNoticeGate()
            assertTrue(gate.begin(1))
            assertTrue(gate.accept(1, 1, RecoveryNoticeGate.STARTED))
            assertTrue(gate.accept(1, 1, terminal))
            assertFalse(gate.accept(1, 1, terminal))
            assertFalse(gate.accept(1, 1, RecoveryNoticeGate.ADMITTED))
            assertFalse(gate.accept(1, 1, RecoveryNoticeGate.FAILED))
            assertFalse(gate.accept(1, 1, RecoveryNoticeGate.STARTED))
            assertEquals(terminal, gate.currentPhase)
            assertEquals(1L, gate.latestWorkerGeneration)
            assertEquals(4L, gate.discarded)
        }
    }

    @Test fun olderWorkerCannotOverrideLatestEvenIfItsTerminalArrivesLate() {
        val gate = RecoveryNoticeGate()
        assertTrue(gate.begin(1))
        assertTrue(gate.accept(1, 2, RecoveryNoticeGate.STARTED))
        assertTrue(gate.accept(1, 3, RecoveryNoticeGate.STARTED))
        assertFalse(gate.accept(1, 2, RecoveryNoticeGate.ADMITTED))
        assertFalse(gate.accept(1, 1, RecoveryNoticeGate.FAILED))
        assertEquals(3L, gate.latestWorkerGeneration)
        assertEquals(RecoveryNoticeGate.STARTED, gate.currentPhase)
        assertTrue(gate.accept(1, 3, RecoveryNoticeGate.ADMITTED))
    }

    @Test fun newerWorkerMayStartAnotherRecoveryInTheSameOpenSession() {
        val gate = RecoveryNoticeGate()
        assertTrue(gate.begin(1))
        assertTrue(gate.accept(1, 3, RecoveryNoticeGate.FAILED))
        assertTrue(gate.accept(1, 4, RecoveryNoticeGate.STARTED))
        assertEquals(4L, gate.latestWorkerGeneration)
        assertEquals(RecoveryNoticeGate.STARTED, gate.currentPhase)
        assertFalse(gate.accept(1, 3, RecoveryNoticeGate.ADMITTED))
        assertTrue(gate.accept(1, 4, RecoveryNoticeGate.ADMITTED))
    }

    @Test fun callbacksNeverBeginSessionsAndInvalidNoticesDoNotChangeAcceptedState() {
        val gate = RecoveryNoticeGate()
        assertFalse(gate.accept(5, 1, RecoveryNoticeGate.STARTED))
        assertEquals(0L, gate.currentSessionId)
        assertTrue(gate.begin(5))
        assertTrue(gate.accept(5, 2, RecoveryNoticeGate.STARTED))
        assertFalse(gate.accept(6, 3, RecoveryNoticeGate.ADMITTED))
        assertFalse(gate.accept(5, 0, RecoveryNoticeGate.STARTED))
        assertFalse(gate.accept(5, -1, RecoveryNoticeGate.STARTED))
        assertFalse(gate.accept(5, 3, 0))
        assertFalse(gate.accept(5, 3, 4))
        assertEquals(5L, gate.currentSessionId)
        assertEquals(2L, gate.latestWorkerGeneration)
        assertEquals(RecoveryNoticeGate.STARTED, gate.currentPhase)
        assertEquals(6L, gate.discarded)
    }

    @Test fun onlyStrictlyIncreasingExplicitBeginResetsAcceptedWorkerState() {
        val gate = RecoveryNoticeGate()
        assertFalse(gate.begin(0))
        assertFalse(gate.begin(-1))
        assertTrue(gate.begin(2))
        assertTrue(gate.accept(2, 8, RecoveryNoticeGate.ADMITTED))
        assertFalse(gate.begin(2))
        assertFalse(gate.begin(1))
        assertEquals(8L, gate.latestWorkerGeneration)
        assertEquals(RecoveryNoticeGate.ADMITTED, gate.currentPhase)
        assertFalse(gate.accept(1, 10, RecoveryNoticeGate.STARTED))
        assertTrue(gate.begin(3))
        assertEquals(0L, gate.latestWorkerGeneration)
        assertEquals(0, gate.currentPhase)
        assertEquals(1L, gate.discarded)
    }
}
