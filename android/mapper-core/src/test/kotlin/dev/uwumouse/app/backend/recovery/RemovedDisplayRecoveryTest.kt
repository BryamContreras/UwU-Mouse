package dev.uwumouse.app.backend.recovery

import dev.uwumouse.app.engine.TouchComposer
import dev.uwumouse.app.engine.TouchCoordinatePolicy
import dev.uwumouse.app.engine.TouchFrame
import org.junit.Assert.*
import org.junit.Test

class RemovedDisplayRecoveryTest {
    private val down = TouchFrame(TouchComposer.DOWN, 100, 100, intArrayOf(0), floatArrayOf(1919.9998f), floatArrayOf(0f))
    private fun blocked(explicitRejection: Boolean = true) = RecoveryJournal(displayId = 9).also {
        assertTrue(it.begin(17, 1920, 1080, 10394))
        assertTrue(it.prepare(17, down))
        assertTrue(if (explicitRejection) it.acknowledged(17, down, false) else it.fail(17))
    }

    @Test fun allIndependentProofsCloseTheExactOriginalStreamWithoutClaimingAdmission() {
        val journal = blocked()
        val original = journal.snapshot()
        for (terminated in listOf(false, true)) for (removed in listOf(false, true)) for (empty in listOf(false, true)) {
            if (!(terminated && removed && empty))
                assertFalse(journal.reconciledRemovedDisplay(original, terminated, removed, empty))
        }
        assertEquals(1, journal.snapshot().possibleContacts)
        assertTrue(journal.reconciledRemovedDisplay(original, true, true, true))
        val closed = journal.snapshot()
        assertEquals(17L, closed.sessionId)
        assertEquals(9, closed.displayId)
        assertEquals(10394, closed.targetUid)
        assertEquals(0, closed.possibleContacts)
        assertEquals(RecoveryInjectionState.RECOVERY_ADMITTED, closed.state)
        assertTrue(closed.terminalReconciled)
        assertTrue(closed.removedDisplayReconciled)
        assertFalse(closed.cancellationAccepted)
        assertFalse(closed.receiverVerified)
        assertFalse(journal.reconciledRemovedDisplay(original, true, true, true))
        assertTrue(journal.begin(18, 1920, 1080, 10394))
        assertFalse(journal.snapshot().removedDisplayReconciled)
    }

    @Test fun missingDownReplyCanCloseOnlyWithTheStrongerRemovedDisplayProof() {
        val journal = blocked(explicitRejection = false)
        assertFalse(journal.reconciledRejectedDown(17, 2, true))
        assertTrue(journal.reconciledRemovedDisplay(journal.snapshot(), true, true, true))
        assertFalse(journal.snapshot().rejectedDownReconciled)
    }

    @Test fun changedIdentityGeometryOrCandidateCannotReuseAnEarlierProof() {
        val journal = blocked()
        val original = journal.snapshot()
        val altered = listOf(original.copy(sessionId = 18), original.copy(targetUid = 10395),
            original.copy(displayId = 11), original.copy(width = 2560), original.copy(height = 1440),
            original.copy(source = 8194), original.copy(deviceId = -1),
            original.copy(coordinatePolicy = TouchCoordinatePolicy.CONTINUOUS_CAMERA_9),
            original.copy(attempted = down.copy(eventTime = 101)),
            original.copy(attempted = down.copy(xs = floatArrayOf(1f))),
            original.copy(attempted = null), original.copy(lastConfirmed = down),
            original.copy(activeFrame = down), original.copy(state = RecoveryInjectionState.ACTIVE))
        altered.forEach { assertFalse(journal.reconciledRemovedDisplay(it, true, true, true)) }
        assertEquals(1, journal.snapshot().possibleContacts)
        assertTrue(journal.reconciledRemovedDisplay(original, true, true, true))
    }

    @Test fun activeJournalAndImportedOrphanNeverUseTheRemovedDisplayShortcut() {
        val active = RecoveryJournal(displayId = 9)
        assertTrue(active.begin(17, 1920, 1080, 10394))
        assertTrue(active.prepare(17, down))
        assertFalse(active.reconciledRemovedDisplay(active.snapshot(), true, true, true))
        val orphan = RecoveryJournal(displayId = 9)
        assertTrue(orphan.adoptForRecovery(17, 1920, 1080, 10394, down.copy(action = TouchComposer.CANCEL)))
        assertFalse(orphan.reconciledRemovedDisplay(orphan.snapshot(), true, true, true))
        assertEquals(1, orphan.snapshot().possibleContacts)
    }

    @Test fun acknowledgedNonterminalStreamNeedsTheSameCompleteProof() {
        val journal = RecoveryJournal(displayId = 9)
        assertTrue(journal.begin(17, 1920, 1080, 10394))
        assertTrue(journal.prepare(17, down)); assertTrue(journal.acknowledged(17, down, true))
        val move = down.copy(action = TouchComposer.MOVE, eventTime = 101, xs = floatArrayOf(100f))
        assertTrue(journal.prepare(17, move)); assertTrue(journal.fail(17))
        val original = journal.snapshot()
        assertFalse(journal.reconciledRemovedDisplay(original, false, true, true))
        assertTrue(journal.reconciledRemovedDisplay(original, true, true, true))
        assertEquals(0, journal.snapshot().possibleContacts)
    }
}
