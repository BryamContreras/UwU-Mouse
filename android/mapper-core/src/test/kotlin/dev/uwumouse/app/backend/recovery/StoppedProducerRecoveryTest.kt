package dev.uwumouse.app.backend.recovery

import dev.uwumouse.app.engine.TouchComposer
import dev.uwumouse.app.engine.TouchCoordinatePolicy
import dev.uwumouse.app.engine.TouchFrame
import org.junit.Assert.*
import org.junit.Test

class StoppedProducerRecoveryTest {
    private fun frame(action:Int,vararg ids:Int,time:Long=100L)=
        TouchFrame(action,100L,time,ids,FloatArray(ids.size){20f+it},FloatArray(ids.size){30f+it})
    private fun admit(journal:RecoveryJournal,event:TouchFrame) {
        assertTrue(journal.prepare(7,event));assertTrue(journal.acknowledged(7,event,true))
    }
    private fun rejectedPointerUp():RecoveryJournal=RecoveryJournal(displayId=9).also { journal ->
        assertTrue(journal.begin(7,1080,2316,10498))
        admit(journal,frame(TouchComposer.DOWN,0))
        admit(journal,frame(TouchComposer.POINTER_DOWN or (1 shl 8),0,9,time=101))
        val up=frame(TouchComposer.POINTER_UP,0,9,time=102)
        assertTrue(journal.prepare(7,up));assertTrue(journal.acknowledged(7,up,false))
    }

    @Test fun rejectedPointerUpClosesOnlyWithStoppedCaptureEveryWorkerGoneAndGlobalNoDown() {
        val journal=rejectedPointerUp();val original=journal.snapshot()
        assertEquals(2,original.possibleContacts)
        assertEquals(TouchComposer.POINTER_UP,original.attempted?.action)
        for(captureStopped in listOf(false,true)) for(workersGone in listOf(false,true))
            for(noDown in listOf(false,true)) {
                if(captureStopped && workersGone && noDown) continue
                assertFalse(journal.reconciledStoppedProducers(original,captureStopped,workersGone,noDown))
                assertEquals(2,journal.snapshot().possibleContacts)
            }
        assertTrue(journal.reconciledStoppedProducers(original,true,true,true))
        val closed=journal.snapshot()
        assertEquals(0,closed.possibleContacts);assertEquals(0,closed.activeContacts)
        assertTrue(closed.terminalReconciled);assertTrue(closed.stoppedProducersReconciled)
        assertFalse(closed.cancellationAccepted);assertFalse(closed.receiverVerified)
        assertFalse(journal.prepare(7,frame(TouchComposer.DOWN,0,time=200)))
    }

    @Test fun aHeldPhysicalFingerKeepsClosurePendingUntilAFreshEmptyObservation() {
        val journal=rejectedPointerUp();val original=journal.snapshot()
        assertFalse(journal.reconciledStoppedProducers(original,true,true,false))
        assertEquals(RecoveryInjectionState.RECOVERY_NEEDED,journal.snapshot().state)
        assertArrayEquals(intArrayOf(0,9),journal.snapshot().attempted?.ids)
        assertFalse(journal.prepare(7,frame(TouchComposer.DOWN,0,time=200)))
        assertFalse(journal.prepare(7,frame(TouchComposer.CANCEL,0,9,time=200)))
        assertTrue(journal.reconciledStoppedProducers(original,true,true,true))
    }

    @Test fun aNormalCancellationAckClosesWithoutInventingRetirementOrDispatcherProof() {
        val journal=rejectedPointerUp()
        assertTrue(journal.cancelled(7,true))
        val closed=journal.snapshot()
        assertEquals(0,closed.possibleContacts);assertTrue(closed.cancellationAccepted)
        assertFalse(closed.terminalReconciled);assertFalse(closed.stoppedProducersReconciled)
        assertFalse(closed.receiverVerified)
    }

    @Test fun anotherJournalDestinationOrCandidateCannotBorrowTheProof() {
        val journal=rejectedPointerUp();val original=journal.snapshot()
        val altered=listOf(original.copy(sessionId=8),original.copy(displayId=0),original.copy(targetUid=10499),
            original.copy(width=1081),original.copy(height=2317),original.copy(source=8194),original.copy(deviceId=1),
            original.copy(coordinatePolicy=TouchCoordinatePolicy.CONTINUOUS_CAMERA_9),
            original.copy(lastConfirmed=original.lastConfirmed?.copy(eventTime=999)),
            original.copy(activeFrame=original.activeFrame?.copy(ids=intArrayOf(1,9))),
            original.copy(attempted=original.attempted?.copy(xs=floatArrayOf(50f,60f))))
        altered.forEach { assertFalse(journal.reconciledStoppedProducers(it,true,true,true)) }
        assertTrue(journal.reconciledStoppedProducers(original,true,true,true))
    }

    @Test fun activeStreamsAndImportedOrphansCannotClaimRetirementOfTheirFormerProducers() {
        val active=RecoveryJournal()
        assertTrue(active.begin(7,1080,2316,10498));admit(active,frame(TouchComposer.DOWN,0))
        assertFalse(active.reconciledStoppedProducers(active.snapshot(),true,true,true))
        val orphan=RecoveryJournal()
        assertTrue(orphan.adoptForRecovery(7,1080,2316,10498,frame(TouchComposer.CANCEL,0)))
        assertFalse(orphan.reconciledStoppedProducers(orphan.snapshot(),true,true,true))
    }

    @Test fun aNewExplicitSessionClearsTheTerminalProofAndNeverReusesItsSid() {
        val journal=rejectedPointerUp()
        assertTrue(journal.reconciledStoppedProducers(journal.snapshot(),true,true,true))
        assertFalse(journal.begin(7,1080,2316,10498))
        assertTrue(journal.begin(8,1080,2316,10498))
        assertFalse(journal.snapshot().stoppedProducersReconciled)
        assertFalse(journal.snapshot().terminalReconciled)
    }
}
