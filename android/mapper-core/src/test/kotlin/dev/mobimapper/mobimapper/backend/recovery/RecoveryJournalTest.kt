package dev.mobimapper.mobimapper.backend.recovery

import dev.mobimapper.mobimapper.engine.TouchComposer
import dev.mobimapper.mobimapper.engine.TouchFrame
import org.junit.Assert.*
import org.junit.Test

class RecoveryJournalTest {
    private fun frame(action: Int, vararg ids: Int, time: Long = 100) = TouchFrame(
        action, 100, time, ids, FloatArray(ids.size) { 10f + ids[it] }, FloatArray(ids.size) { 20f + ids[it] },
    )

    private fun started(): RecoveryJournal = RecoveryJournal().also {
        assertTrue(it.begin(1, 1080, 2316, 10498))
    }

    private fun admit(journal: RecoveryJournal, frame: TouchFrame) {
        assertTrue(journal.prepare(1, frame))
        assertTrue(journal.acknowledged(1, frame, true))
    }

    @Test fun deathBeforeDownReplyKeepsOriginalGestureAndMetadata() {
        val journal = started()
        assertTrue(journal.prepare(1, frame(TouchComposer.DOWN, 4)))
        assertTrue(journal.fail(1))
        val cancel = requireNotNull(journal.cancellationFrame(150))
        assertEquals(TouchComposer.CANCEL, cancel.action)
        assertEquals(100L, cancel.downTime)
        assertEquals(150L, cancel.eventTime)
        assertArrayEquals(intArrayOf(4), cancel.ids)
        assertArrayEquals(floatArrayOf(14f), cancel.xs, 0f)
        assertEquals(0, journal.snapshot().displayId)
        assertEquals(0, journal.snapshot().deviceId)
        assertEquals(0x1002, journal.snapshot().source)
        assertEquals(10498, journal.snapshot().targetUid)
        assertNull(journal.snapshot().lastConfirmed)
        assertEquals(0, journal.snapshot().activeContacts)
        assertEquals(1, journal.snapshot().possibleContacts)
    }

    @Test fun deathDuringPointerDownIncludesUnacknowledgedNewPointer() {
        val journal = started()
        admit(journal, frame(TouchComposer.DOWN, 0))
        admit(journal, frame(TouchComposer.POINTER_DOWN or (1 shl 8), 0, 1, time = 101))
        val addingThird = frame(TouchComposer.POINTER_DOWN or (2 shl 8), 0, 1, 2, time = 102)
        assertTrue(journal.prepare(1, addingThird))
        assertTrue(journal.fail(1))
        assertArrayEquals(intArrayOf(0, 1, 2), journal.cancellationFrame(200)!!.ids)
        assertArrayEquals(intArrayOf(0, 1), journal.snapshot().activeFrame!!.ids)
        assertArrayEquals(intArrayOf(0, 1, 2), journal.snapshot().attempted!!.ids)
        assertFalse(journal.prepare(1, addingThird))
    }

    @Test fun deathDuringPointerUpRetainsFullPossibleSetWhileAdmittedUpRemovesOnlyLiftedId() {
        val journal = started()
        admit(journal, frame(TouchComposer.DOWN, 0))
        admit(journal, frame(TouchComposer.POINTER_DOWN or (1 shl 8), 0, 7, time = 101))
        admit(journal, frame(TouchComposer.POINTER_DOWN or (2 shl 8), 0, 7, 2, time = 102))
        val liftingMiddle = frame(TouchComposer.POINTER_UP or (1 shl 8), 0, 7, 2, time = 103)
        assertTrue(journal.prepare(1, liftingMiddle))
        assertTrue(journal.acknowledged(1, liftingMiddle, true))
        assertArrayEquals(intArrayOf(0, 2), journal.snapshot().activeFrame!!.ids)
        val liftingFirst = frame(TouchComposer.POINTER_UP, 0, 2, time = 104)
        assertTrue(journal.prepare(1, liftingFirst))
        assertTrue(journal.acknowledged(1, liftingFirst, false))
        assertArrayEquals(intArrayOf(0, 2), journal.cancellationFrame(105)!!.ids)
        assertEquals(RecoveryInjectionState.RECOVERY_NEEDED, journal.snapshot().state)
    }

    @Test fun admittedFinalUpLeavesNoGestureToCancel() {
        val journal = started()
        admit(journal, frame(TouchComposer.DOWN, 0))
        admit(journal, frame(TouchComposer.UP, 0, time = 101))
        assertEquals(0, journal.snapshot().activeContacts)
        assertEquals(TouchComposer.UP, journal.snapshot().lastConfirmed!!.action)
        assertTrue(journal.fail(1))
        assertNull(journal.cancellationFrame(102))
    }

    @Test fun ambiguousFinalUpStillRequiresCancellation() {
        val journal = started()
        admit(journal, frame(TouchComposer.DOWN, 0))
        assertTrue(journal.prepare(1, frame(TouchComposer.UP, 0, time = 101)))
        assertTrue(journal.fail(1))
        assertArrayEquals(intArrayOf(0), journal.cancellationFrame(102)!!.ids)
    }

    @Test fun acceptedRecoveryIsAdmissionOnlyAndNeverResumesHeldActions() {
        val journal = started()
        admit(journal, frame(TouchComposer.DOWN, 0))
        assertTrue(journal.fail(1))
        assertTrue(journal.cancelled(1, true))
        val snapshot = journal.snapshot()
        assertEquals(RecoveryInjectionState.RECOVERY_ADMITTED, snapshot.state)
        assertTrue(snapshot.cancellationAccepted)
        assertFalse(snapshot.receiverVerified)
        assertEquals(0, snapshot.activeContacts)
        assertFalse(journal.prepare(1, frame(TouchComposer.DOWN, 0)))
        assertFalse(journal.begin(1, 1080, 2316, 10498))
        assertTrue(journal.begin(2, 1080, 2316, 10498))
        assertEquals(0, journal.snapshot().activeContacts)
        assertNull(journal.snapshot().lastConfirmed)
        assertFalse(journal.snapshot().receiverVerified)
        assertFalse(journal.prepare(2, frame(TouchComposer.MOVE, 0)))
        assertTrue(journal.prepare(2, frame(TouchComposer.DOWN, 0)))
    }

    @Test fun failedRecoveryRetainsCandidateAndBlocksNewGeneration() {
        val journal = started()
        admit(journal, frame(TouchComposer.DOWN, 3))
        assertTrue(journal.fail(1))
        assertTrue(journal.cancelled(1, false))
        assertFalse(journal.snapshot().cancellationAccepted)
        assertEquals(RecoveryInjectionState.RECOVERY_NEEDED, journal.snapshot().state)
        assertArrayEquals(intArrayOf(3), journal.cancellationFrame(101)!!.ids)
        assertFalse(journal.begin(2, 1080, 2316, 10498))
        assertTrue(journal.cancelled(1, true))
    }

    @Test fun staleGenerationAndLateRepliesCannotAffectNewSession() {
        val journal = started()
        val oldDown = frame(TouchComposer.DOWN, 0)
        admit(journal, oldDown)
        assertTrue(journal.fail(1))
        assertTrue(journal.cancelled(1, true))
        assertTrue(journal.begin(2, 1080, 2316, 10498))
        val newDown = frame(TouchComposer.DOWN, 8, time = 200)
        assertTrue(journal.prepare(2, newDown))
        assertFalse(journal.acknowledged(1, oldDown, true))
        assertFalse(journal.fail(1))
        assertFalse(journal.cancelled(1, true))
        assertFalse(journal.prepare(1, oldDown))
        assertTrue(journal.acknowledged(2, newDown, true))
        assertEquals(2L, journal.snapshot().sessionId)
        assertArrayEquals(intArrayOf(8), journal.snapshot().activeFrame!!.ids)
    }

    @Test fun unrelatedOrDuplicateAcknowledgementCannotCommitPreparedFrame() {
        val journal = started()
        val down = frame(TouchComposer.DOWN, 0)
        assertTrue(journal.prepare(1, down))
        assertFalse(journal.prepare(1, down))
        assertFalse(journal.acknowledged(1, down.copy(eventTime = 101), true))
        assertTrue(journal.acknowledged(1, down, true))
        assertFalse(journal.acknowledged(1, down, true))
    }

    @Test fun allFrameBoundariesUseDefensiveCopiesAndCancelTimeNeverGoesBackwards() {
        val journal = started()
        val down = frame(TouchComposer.DOWN, 6, time = 120)
        assertTrue(journal.prepare(1, down))
        down.ids[0] = 0
        down.xs[0] = 999f
        journal.snapshot().attempted!!.ids[0] = 1
        assertTrue(journal.fail(1))
        val cancel = journal.cancellationFrame(110)!!
        assertEquals(120L, cancel.eventTime)
        assertArrayEquals(intArrayOf(6), cancel.ids)
        assertArrayEquals(floatArrayOf(16f), cancel.xs, 0f)
        cancel.ids[0] = 9
        assertArrayEquals(intArrayOf(6), journal.cancellationFrame(130)!!.ids)
    }

    @Test fun normalCancelStopsAndRequiresExplicitNewGeneration() {
        val journal = started()
        admit(journal, frame(TouchComposer.DOWN, 0))
        admit(journal, frame(TouchComposer.CANCEL, 0, time = 101))
        assertEquals(RecoveryInjectionState.STOPPED, journal.snapshot().state)
        assertEquals(0, journal.snapshot().activeContacts)
        assertFalse(journal.prepare(1, frame(TouchComposer.DOWN, 0, time = 102)))
        assertFalse(journal.snapshot().receiverVerified)
        assertTrue(journal.begin(2, 1080, 2316, 10498))
    }

    @Test fun totalGuardianLossAdoptsOnlyOldCancelAndNeverFabricatesDownOrReceipt() {
        val journal = RecoveryJournal()
        val oldCancel = frame(TouchComposer.CANCEL, 0, 7, 2, time = 110)
        assertTrue(journal.adoptForRecovery(8, 1080, 2316, 10498, oldCancel))
        oldCancel.ids[0] = 9
        val snapshot = journal.snapshot()
        assertEquals(8L, snapshot.sessionId)
        assertEquals(RecoveryInjectionState.RECOVERY_NEEDED, snapshot.state)
        assertNull(snapshot.lastConfirmed)
        assertEquals(0, snapshot.activeContacts)
        assertEquals(3, snapshot.possibleContacts)
        assertFalse(snapshot.receiverVerified)
        assertFalse(snapshot.cancellationAccepted)
        val cancel = journal.cancellationFrame(105)!!
        assertEquals(TouchComposer.CANCEL, cancel.action)
        assertEquals(100L, cancel.downTime)
        assertEquals(110L, cancel.eventTime)
        assertArrayEquals(intArrayOf(0, 7, 2), cancel.ids)
        assertArrayEquals(floatArrayOf(10f, 17f, 12f), cancel.xs, 0f)
        assertEquals(0, snapshot.displayId)
        assertEquals(0x1002, snapshot.source)
        assertEquals(0, snapshot.deviceId)
        assertEquals(10498, snapshot.targetUid)
        assertFalse(journal.prepare(8, frame(TouchComposer.DOWN, 0)))
        assertTrue(journal.cancelled(8, true))
        assertFalse(journal.snapshot().receiverVerified)
        assertFalse(journal.prepare(8, frame(TouchComposer.DOWN, 0)))
        assertTrue(journal.begin(9, 1080, 2316, 10498))
        assertFalse(journal.snapshot().receiverVerified)
    }

    @Test fun adoptionRejectsDownPointerActionsAndIndexedCancel() {
        val journal = RecoveryJournal()
        for (action in listOf(
            TouchComposer.DOWN, TouchComposer.UP, TouchComposer.MOVE,
            TouchComposer.POINTER_DOWN or (1 shl 8), TouchComposer.POINTER_UP,
            TouchComposer.CANCEL or (1 shl 8),
        )) {
            assertFalse(journal.adoptForRecovery(1, 1080, 2316, 10498, frame(action, 0)))
        }
        assertEquals(RecoveryInjectionState.IDLE, journal.snapshot().state)
        assertFalse(journal.snapshot().receiverVerified)
        assertTrue(journal.adoptForRecovery(1, 1080, 2316, 10498, frame(TouchComposer.CANCEL, 0)))
    }

    @Test fun adoptionRejectsMalformedIdsCoordinatesGeometryAndTimesWithoutChangingJournal() {
        val journal = RecoveryJournal()
        val cancel = frame(TouchComposer.CANCEL, 0)
        val invalidFrames = listOf(
            frame(TouchComposer.CANCEL),
            frame(TouchComposer.CANCEL, 0, 0),
            frame(TouchComposer.CANCEL, -1),
            frame(TouchComposer.CANCEL, 10),
            cancel.copy(xs = floatArrayOf()),
            cancel.copy(ys = floatArrayOf()),
            cancel.copy(xs = floatArrayOf(Float.NaN)),
            cancel.copy(xs = floatArrayOf(Float.POSITIVE_INFINITY)),
            cancel.copy(xs = floatArrayOf(-1f)),
            cancel.copy(xs = floatArrayOf(1080f)),
            cancel.copy(ys = floatArrayOf(2316f)),
            cancel.copy(downTime = -1),
            cancel.copy(eventTime = 99),
        )
        for (invalid in invalidFrames) {
            assertFalse(journal.adoptForRecovery(1, 1080, 2316, 10498, invalid))
            assertEquals(0L, journal.snapshot().sessionId)
            assertFalse(journal.snapshot().receiverVerified)
        }
        assertFalse(journal.adoptForRecovery(0, 1080, 2316, 10498, cancel))
        assertFalse(journal.adoptForRecovery(1, 0, 2316, 10498, cancel))
        assertFalse(journal.adoptForRecovery(1, 1080, -1, 10498, cancel))
        assertFalse(journal.adoptForRecovery(1, 1080, 2316, -1, cancel))
    }

    @Test fun adoptionNeverOverwritesLiveOrPendingGuardianAndPreservesMonotonicity() {
        val journal = started()
        val cancel = frame(TouchComposer.CANCEL, 0, time = 110)
        // A begun live session is protected even before its first touch.
        assertFalse(journal.adoptForRecovery(2, 1080, 2316, 10498, cancel))
        admit(journal, frame(TouchComposer.DOWN, 0))
        assertFalse(journal.adoptForRecovery(2, 1080, 2316, 10498, cancel))
        assertTrue(journal.fail(1))
        assertFalse(journal.adoptForRecovery(2, 1080, 2316, 10498, cancel))
        assertEquals(110L, journal.cancellationFrame(110)!!.eventTime)
        assertTrue(journal.cancelled(1, true))
        assertFalse(journal.adoptForRecovery(1, 1080, 2316, 10498, cancel))
        assertFalse(journal.adoptForRecovery(2, 1080, 2316, 10498, cancel.copy(eventTime = 109)))
        assertTrue(journal.adoptForRecovery(2, 1080, 2316, 10498, cancel))

        val stopped = started()
        admit(stopped, frame(TouchComposer.DOWN, 0))
        admit(stopped, frame(TouchComposer.CANCEL, 0, time = 150))
        assertFalse(stopped.adoptForRecovery(2, 1080, 2316, 10498, cancel))
        assertTrue(stopped.adoptForRecovery(2, 1080, 2316, 10498, cancel.copy(eventTime = 150)))
    }

    @Test fun normalPrepareRejectsCoordinatesAtExclusiveScreenBounds() {
        val journal = started()
        val down = frame(TouchComposer.DOWN, 0)
        assertFalse(journal.prepare(1, down.copy(xs = floatArrayOf(1080f))))
        assertFalse(journal.prepare(1, down.copy(ys = floatArrayOf(2316f))))
        assertTrue(journal.prepare(1, down.copy(xs = floatArrayOf(1079.9f), ys = floatArrayOf(2315.9f))))
    }

    @Test fun lostFinalUpCanBeReconciledByEmptyDispatcherWithoutInventingCancelOrReceiverReceipt() {
        val journal = started()
        admit(journal, frame(TouchComposer.DOWN, 4))
        val up = frame(TouchComposer.UP, 4, time = 101)
        assertTrue(journal.prepare(1, up))
        assertTrue(journal.fail(1))
        // A late cancellation can fail after the original UP has already closed the OS stream.
        assertTrue(journal.cancelled(1, false))
        assertTrue(journal.reconciledTerminal(1, dispatcherGloballyEmpty = true))
        val snapshot = journal.snapshot()
        assertEquals(RecoveryInjectionState.RECOVERY_ADMITTED, snapshot.state)
        assertTrue(snapshot.terminalReconciled)
        assertFalse(snapshot.cancellationAccepted)
        assertFalse(snapshot.receiverVerified)
        assertNull(snapshot.attempted)
        assertNull(snapshot.activeFrame)
        assertEquals(0, snapshot.possibleContacts)
        // Preserve the final real acknowledgement: UP never received a reply.
        val lastConfirmed = requireNotNull(snapshot.lastConfirmed)
        assertEquals(TouchComposer.DOWN, lastConfirmed.action)
        assertEquals(100L, lastConfirmed.eventTime)
        assertNull(journal.cancellationFrame(102))
        assertFalse(journal.prepare(1, frame(TouchComposer.DOWN, 0, time = 102)))
        assertFalse(journal.reconciledTerminal(1, dispatcherGloballyEmpty = true))
        assertTrue(journal.begin(2, 1080, 2316, 10498))
        assertFalse(journal.snapshot().terminalReconciled)
        assertFalse(journal.snapshot().receiverVerified)
    }

    @Test fun terminalReconciliationRejectsNonemptyDispatcherAndStaleSessionWithoutChangingJournal() {
        val journal = started()
        admit(journal, frame(TouchComposer.DOWN, 0))
        assertTrue(journal.prepare(1, frame(TouchComposer.UP, 0, time = 101)))
        assertTrue(journal.fail(1))
        assertFalse(journal.reconciledTerminal(1, dispatcherGloballyEmpty = false))
        assertFalse(journal.reconciledTerminal(0, dispatcherGloballyEmpty = true))
        assertFalse(journal.reconciledTerminal(2, dispatcherGloballyEmpty = true))
        val snapshot = journal.snapshot()
        assertEquals(RecoveryInjectionState.RECOVERY_NEEDED, snapshot.state)
        assertFalse(snapshot.terminalReconciled)
        assertFalse(snapshot.cancellationAccepted)
        assertFalse(snapshot.receiverVerified)
        assertEquals(TouchComposer.UP, snapshot.attempted!!.action)
        assertArrayEquals(intArrayOf(0), snapshot.activeFrame!!.ids)
        assertTrue(journal.reconciledTerminal(1, dispatcherGloballyEmpty = true))
    }

    @Test fun emptyDispatcherCannotReconcileAmbiguousPointerDownOrPointerUp() {
        for (uncertainAction in listOf(TouchComposer.POINTER_DOWN or (1 shl 8), TouchComposer.POINTER_UP)) {
            val journal = started()
            admit(journal, frame(TouchComposer.DOWN, 0))
            if (uncertainAction == TouchComposer.POINTER_UP) {
                admit(journal, frame(TouchComposer.POINTER_DOWN or (1 shl 8), 0, 1, time = 101))
            }
            assertTrue(journal.prepare(1, frame(uncertainAction, 0, 1, time = 102)))
            assertTrue(journal.fail(1))
            assertFalse(journal.reconciledTerminal(1, dispatcherGloballyEmpty = true))
            val snapshot = journal.snapshot()
            assertEquals(RecoveryInjectionState.RECOVERY_NEEDED, snapshot.state)
            assertFalse(snapshot.terminalReconciled)
            assertFalse(snapshot.receiverVerified)
            assertArrayEquals(intArrayOf(0, 1), snapshot.attempted!!.ids)
        }
    }

    @Test fun reconciliationRequiresFailedUnacknowledgedExactUpNotAnActiveOrAlreadyAdmittedEvent() {
        val journal = started()
        admit(journal, frame(TouchComposer.DOWN, 0))
        val up = frame(TouchComposer.UP, 0, time = 101)
        assertTrue(journal.prepare(1, up))
        assertFalse(journal.reconciledTerminal(1, dispatcherGloballyEmpty = true))
        assertTrue(journal.acknowledged(1, up, true))
        assertTrue(journal.fail(1))
        assertFalse(journal.reconciledTerminal(1, dispatcherGloballyEmpty = true))
        assertFalse(journal.snapshot().terminalReconciled)

        val imported = RecoveryJournal()
        assertTrue(imported.adoptForRecovery(1, 1080, 2316, 10498, frame(TouchComposer.CANCEL, 0)))
        assertFalse(imported.reconciledTerminal(1, dispatcherGloballyEmpty = true))
        assertFalse(imported.snapshot().terminalReconciled)
        assertFalse(imported.snapshot().receiverVerified)
    }

    @Test fun orphanImportedCancelReconcilesEmptyDispatcherWithoutClaimingRetryOrReceiverSuccess() {
        val journal = RecoveryJournal()
        assertTrue(journal.adoptForRecovery(7, 1080, 2316, 10498, frame(TouchComposer.CANCEL, 0, 1, 2)))
        assertFalse(journal.reconciledTerminal(7, dispatcherGloballyEmpty = true))
        // The lost worker could already have closed the OS stream on guardian EOF.
        assertTrue(journal.cancelled(7, false))
        assertTrue(journal.reconciledOrphan(7, dispatcherGloballyEmpty = true))
        val snapshot = journal.snapshot()
        assertEquals(RecoveryInjectionState.RECOVERY_ADMITTED, snapshot.state)
        assertTrue(snapshot.terminalReconciled)
        assertFalse(snapshot.cancellationAccepted)
        assertFalse(snapshot.receiverVerified)
        assertNull(snapshot.lastConfirmed)
        assertNull(snapshot.attempted)
        assertEquals(0, snapshot.possibleContacts)
        assertFalse(journal.reconciledOrphan(7, dispatcherGloballyEmpty = true))
        assertFalse(journal.prepare(7, frame(TouchComposer.DOWN, 0, time = 101)))
        assertTrue(journal.begin(8, 1080, 2316, 10498))
        assertFalse(journal.snapshot().terminalReconciled)
    }

    @Test fun orphanReconciliationRequiresMatchingSessionAndAuthoritativeEmptyDispatcher() {
        val journal = RecoveryJournal()
        assertTrue(journal.adoptForRecovery(2, 1080, 2316, 10498, frame(TouchComposer.CANCEL, 4)))
        assertFalse(journal.reconciledOrphan(2, dispatcherGloballyEmpty = false))
        assertFalse(journal.reconciledOrphan(1, dispatcherGloballyEmpty = true))
        assertFalse(journal.reconciledOrphan(3, dispatcherGloballyEmpty = true))
        val snapshot = journal.snapshot()
        assertEquals(RecoveryInjectionState.RECOVERY_NEEDED, snapshot.state)
        assertFalse(snapshot.terminalReconciled)
        assertFalse(snapshot.cancellationAccepted)
        assertFalse(snapshot.receiverVerified)
        assertArrayEquals(intArrayOf(4), snapshot.attempted!!.ids)
        assertNull(snapshot.lastConfirmed)
        assertTrue(journal.reconciledOrphan(2, dispatcherGloballyEmpty = true))
    }

    @Test fun delayedExpiryFromPreviousSessionCannotExpireItsReplacement() {
        val journal = started()
        val delayedTimeoutSid = journal.snapshot().sessionId
        assertTrue(journal.begin(2, 1080, 2316, 10498))
        val newDown = frame(TouchComposer.DOWN, 0)
        assertTrue(journal.prepare(2, newDown))
        assertTrue(journal.acknowledged(2, newDown, true))
        assertFalse(journal.canExpire(delayedTimeoutSid))
        assertTrue(journal.canExpire(2))
        assertEquals(RecoveryInjectionState.ACTIVE, journal.snapshot().state)
        assertEquals(1, journal.snapshot().activeContacts)
    }

    @Test fun expiryCannotOverrideRecoveryOrACompletedSession() {
        val journal = started()
        admit(journal, frame(TouchComposer.DOWN, 0))
        assertTrue(journal.fail(1))
        assertFalse(journal.canExpire(1))
        assertTrue(journal.cancelled(1, true))
        assertFalse(journal.canExpire(1))
        assertEquals(RecoveryInjectionState.RECOVERY_ADMITTED, journal.snapshot().state)
    }

    @Test fun onlyCurrentActiveSessionCanExpireBeforeOrDuringItsGesture() {
        val journal = RecoveryJournal()
        assertFalse(journal.canExpire(0))
        assertTrue(journal.begin(1, 1080, 2316, 10498))
        assertTrue(journal.canExpire(1))
        assertFalse(journal.canExpire(2))
        admit(journal, frame(TouchComposer.DOWN, 0))
        assertTrue(journal.canExpire(1))
        assertEquals(RecoveryInjectionState.ACTIVE, journal.snapshot().state)
    }

    @Test fun orphanPathNeverReconcilesNormalCancelUpPointerDownOrPointerUp() {
        for (action in listOf(
            TouchComposer.CANCEL, TouchComposer.UP,
            TouchComposer.POINTER_DOWN or (1 shl 8), TouchComposer.POINTER_UP,
        )) {
            val journal = started()
            admit(journal, frame(TouchComposer.DOWN, 0))
            if (action == TouchComposer.POINTER_UP) {
                admit(journal, frame(TouchComposer.POINTER_DOWN or (1 shl 8), 0, 1, time = 101))
            }
            val uncertain = if (action == TouchComposer.CANCEL || action == TouchComposer.UP) {
                frame(action, 0, time = 102)
            } else frame(action, 0, 1, time = 102)
            assertTrue(journal.prepare(1, uncertain))
            assertTrue(journal.fail(1))
            assertFalse(journal.reconciledOrphan(1, dispatcherGloballyEmpty = true))
            val snapshot = journal.snapshot()
            assertEquals(RecoveryInjectionState.RECOVERY_NEEDED, snapshot.state)
            assertFalse(snapshot.terminalReconciled)
            assertFalse(snapshot.cancellationAccepted)
            assertFalse(snapshot.receiverVerified)
            assertEquals(action, snapshot.attempted!!.action)
            assertNotNull(snapshot.lastConfirmed)
        }
    }

    private fun rejectedMoveJournal(): RecoveryJournal = started().also { journal ->
        admit(journal, frame(TouchComposer.DOWN, 0))
        val move = frame(TouchComposer.MOVE, 0, time = 101).copy(xs = floatArrayOf(30f))
        assertTrue(journal.prepare(1, move))
        assertTrue(journal.acknowledged(1, move, false))
    }

    @Test fun rejectedMoveRequiresExplicitRejectedRecoveryCancelAndIndependentNoDownObservation() {
        val journal = rejectedMoveJournal()
        assertFalse(journal.canReconcileRejectedMove(1))
        assertFalse(journal.reconciledRejectedMove(1, true))
        // Merely obtaining a candidate does not mean the worker replied to its injection.
        val cancel = journal.cancellationFrame(200)!!
        assertFalse(journal.reconciledRejectedMove(1, true))
        assertTrue(journal.rejectedRecoveryCancellation(1, cancel))
        assertTrue(journal.canReconcileRejectedMove(1))
        assertFalse(journal.reconciledRejectedMove(1, false))
        assertFalse(journal.begin(2, 1080, 2316, 10498))
        assertEquals(1, journal.snapshot().possibleContacts)
        assertEquals(RecoveryInjectionState.RECOVERY_NEEDED, journal.snapshot().state)
        assertTrue(journal.reconciledRejectedMove(1, true))
        val snapshot = journal.snapshot()
        assertEquals(RecoveryInjectionState.RECOVERY_ADMITTED, snapshot.state)
        assertEquals(0, snapshot.possibleContacts)
        assertFalse(snapshot.cancellationAccepted)
        assertFalse(snapshot.receiverVerified)
        assertTrue(snapshot.terminalReconciled)
        assertTrue(snapshot.rejectedMoveReconciled)
        assertFalse(journal.prepare(1, frame(TouchComposer.DOWN, 0, time = 201)))
        assertTrue(journal.begin(2, 1080, 2316, 10498))
        assertFalse(journal.snapshot().rejectedMoveReconciled)
        assertFalse(journal.prepare(2, frame(TouchComposer.MOVE, 0, time = 201)))
    }

    @Test fun missingMoveReplyAndFailedCancelDoNotBecomeExplicitNegativeMoveProvenance() {
        val journal = started()
        admit(journal, frame(TouchComposer.DOWN, 0))
        val move = frame(TouchComposer.MOVE, 0, time = 101)
        assertTrue(journal.prepare(1, move))
        assertTrue(journal.fail(1)) // Worker died or pipe threw: result is unknown.
        val cancel = journal.cancellationFrame(200)!!
        assertFalse(journal.rejectedRecoveryCancellation(1, cancel))
        assertTrue(journal.cancelled(1, false))
        assertFalse(journal.canReconcileRejectedMove(1))
        assertFalse(journal.reconciledRejectedMove(1, true))
        assertFalse(journal.acknowledged(1, move, false)) // Late reply cannot retrofit provenance.
        assertFalse(journal.rejectedRecoveryCancellation(1, cancel))
        assertEquals(1, journal.snapshot().possibleContacts)
    }

    @Test fun rejectedMoveRecoveryRejectsWrongSessionAndAlteredCancellationMetadata() {
        val journal = rejectedMoveJournal()
        val cancel = journal.cancellationFrame(200)!!
        assertFalse(journal.rejectedRecoveryCancellation(2, cancel))
        for (changed in listOf(
            cancel.copy(action = TouchComposer.UP),
            cancel.copy(downTime = 99),
            cancel.copy(eventTime = 201),
            cancel.copy(ids = intArrayOf(1)),
            cancel.copy(xs = floatArrayOf(31f)),
            cancel.copy(ys = floatArrayOf(21f)),
        )) assertFalse(journal.rejectedRecoveryCancellation(1, changed))
        assertFalse(journal.canReconcileRejectedMove(1))
        assertTrue(journal.rejectedRecoveryCancellation(1, cancel))
        assertFalse(journal.reconciledRejectedMove(2, true))
        assertFalse(journal.reconciledRejectedMove(0, true))
        assertTrue(journal.reconciledRejectedMove(1, true))
    }

    @Test fun rejectedMoveAfterAcknowledgedPartialReleaseUsesOnlyRemainingConfirmedIds() {
        val journal = started()
        admit(journal, frame(TouchComposer.DOWN, 0))
        admit(journal, frame(TouchComposer.POINTER_DOWN or (1 shl 8), 0, 4, time = 101))
        admit(journal, frame(TouchComposer.POINTER_UP, 0, 4, time = 102))
        val move = frame(TouchComposer.MOVE, 4, time = 103)
        assertTrue(journal.prepare(1, move))
        assertTrue(journal.acknowledged(1, move, false))
        val cancel = journal.cancellationFrame(200)!!
        assertArrayEquals(intArrayOf(4), cancel.ids)
        assertTrue(journal.rejectedRecoveryCancellation(1, cancel))
        assertTrue(journal.reconciledRejectedMove(1, true))
        assertFalse(journal.snapshot().receiverVerified)
        assertFalse(journal.snapshot().cancellationAccepted)
    }

    @Test fun rejectedNonMoveRepliesCannotUseRejectedMoveReconciliation() {
        for (action in listOf(
            TouchComposer.DOWN, TouchComposer.UP, TouchComposer.CANCEL,
            TouchComposer.POINTER_DOWN or (1 shl 8), TouchComposer.POINTER_UP,
        )) {
            val journal = started()
            if (action != TouchComposer.DOWN) admit(journal, frame(TouchComposer.DOWN, 0))
            if (action == TouchComposer.POINTER_UP) {
                admit(journal, frame(TouchComposer.POINTER_DOWN or (1 shl 8), 0, 1, time = 101))
            }
            val uncertain = if (action and 0xff in listOf(TouchComposer.POINTER_DOWN, TouchComposer.POINTER_UP)) {
                frame(action, 0, 1, time = 102)
            } else frame(action, 0, time = 102)
            assertTrue(journal.prepare(1, uncertain))
            assertTrue(journal.acknowledged(1, uncertain, false))
            assertFalse(journal.rejectedRecoveryCancellation(1, journal.cancellationFrame(200)!!))
            assertFalse(journal.reconciledRejectedMove(1, true))
            assertFalse(journal.snapshot().rejectedMoveReconciled)
        }
    }

    @Test fun importedOrphanAndOldGenerationCannotGainRejectedMoveProvenance() {
        val journal = rejectedMoveJournal()
        val oldCancel = journal.cancellationFrame(200)!!
        assertTrue(journal.cancelled(1, true))
        assertTrue(journal.begin(2, 1080, 2316, 10498))
        assertFalse(journal.rejectedRecoveryCancellation(1, oldCancel))
        assertFalse(journal.reconciledRejectedMove(1, true))
        assertTrue(journal.fail(2))
        assertTrue(journal.cancelled(2, true))
        assertTrue(journal.adoptForRecovery(3, 1080, 2316, 10498, oldCancel.copy(eventTime = 201)))
        assertFalse(journal.rejectedRecoveryCancellation(3, journal.cancellationFrame(202)!!))
        assertFalse(journal.reconciledRejectedMove(3, true))
        assertTrue(journal.reconciledOrphan(3, true)) // Original orphan policy remains separate.
        assertFalse(journal.snapshot().rejectedMoveReconciled)
    }
}
