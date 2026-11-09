package dev.uwumouse.app.backend.recovery

import dev.uwumouse.app.engine.TouchComposer
import dev.uwumouse.app.engine.TouchCoordinatePolicy
import dev.uwumouse.app.engine.TouchFrame
import org.junit.Assert.*
import org.junit.Test

class FocusLossRecoveryTest {
    private val sid=41L
    private fun frame(action:Int,time:Long=100L,ids:IntArray=intArrayOf(2)) =
        TouchFrame(action,100L,time,ids,FloatArray(ids.size) { 200f+ids[it] },FloatArray(ids.size) { 300f+ids[it] })
    private fun active(display:Int=0,policy:TouchCoordinatePolicy=TouchCoordinatePolicy.STRICT)=
        RecoveryJournal(displayId=display).also {
            assertTrue(it.begin(sid,1920,1080,10498,policy))
            val down=frame(TouchComposer.DOWN)
            assertTrue(it.prepare(sid,down));assertTrue(it.acknowledged(sid,down,true))
        }
    private fun focusLost()=active().also { assertTrue(it.failForFocusLoss(sid)) }
    private fun reject(j:RecoveryJournal,cancel:TouchFrame=j.cancellationFrame(200L)!!)=
        j.rejectedFocusLossCancellation(sid,cancel,true,4L,5L)

    @Test fun acknowledgedForeignFocusStreamCanCloseAfterRetirementRejectionAndIndependentNoDown() {
        val j=focusLost()
        val original=j.snapshot()
        assertFalse(j.canReconcileFocusLoss(sid,5L))
        assertTrue(reject(j))
        assertTrue(j.canReconcileFocusLoss(sid,5L))
        assertFalse(j.reconciledFocusLoss(sid,5L,false))
        assertTrue(j.reconciledFocusLoss(sid,5L,true))
        val result=j.snapshot()
        assertEquals(RecoveryInjectionState.RECOVERY_ADMITTED,result.state)
        assertEquals(original.sessionId,result.sessionId)
        assertEquals(original.targetUid,result.targetUid)
        assertEquals(original.displayId,result.displayId)
        assertEquals(original.coordinatePolicy,result.coordinatePolicy)
        assertEquals(0,result.possibleContacts)
        assertTrue(result.focusLossReconciled);assertTrue(result.terminalReconciled)
        assertFalse(result.cancellationAccepted);assertFalse(result.receiverVerified)
        assertNotNull(result.lastConfirmed)
        assertFalse(j.prepare(sid,frame(TouchComposer.DOWN,300L)))
        assertFalse(j.begin(sid,1920,1080,10498))
        assertTrue(j.begin(sid+1,1920,1080,10498))
        assertFalse(j.snapshot().focusLossReconciled)
    }

    @Test fun genericFailureAndUncertainEventsCannotGainFocusLossProvenance() {
        val generic=active();assertTrue(generic.fail(sid))
        assertFalse(reject(generic));assertFalse(generic.reconciledFocusLoss(sid,5L,true))
        for(action in listOf(TouchComposer.MOVE,TouchComposer.UP,TouchComposer.POINTER_DOWN or (1 shl 8))) {
            val j=active()
            val uncertain=if(action and 255==TouchComposer.POINTER_DOWN) frame(action,101L,intArrayOf(2,3)) else frame(action,101L)
            assertTrue(j.prepare(sid,uncertain))
            assertFalse(j.failForFocusLoss(sid))
            assertTrue(j.fail(sid))
            assertFalse(reject(j));assertFalse(j.reconciledFocusLoss(sid,5L,true))
            assertEquals(RecoveryInjectionState.RECOVERY_NEEDED,j.snapshot().state)
            assertNotNull(j.snapshot().attempted)
        }
        val missingDown=RecoveryJournal()
        assertTrue(missingDown.begin(sid,1920,1080,10498))
        assertTrue(missingDown.prepare(sid,frame(TouchComposer.DOWN)))
        assertFalse(missingDown.failForFocusLoss(sid))
        assertTrue(missingDown.fail(sid));assertFalse(reject(missingDown))
    }

    @Test fun missingNegativeAckWorkerRetirementOrNewGenerationCannotBeSubstituted() {
        val j=focusLost();val cancel=j.cancellationFrame(200L)!!
        assertFalse(j.canReconcileFocusLoss(sid,5L)) // No explicit false ACK was registered.
        assertFalse(j.rejectedFocusLossCancellation(sid,cancel,false,4L,5L))
        assertFalse(j.rejectedFocusLossCancellation(sid,cancel,true,0L,5L))
        assertFalse(j.rejectedFocusLossCancellation(sid,cancel,true,4L,4L))
        assertFalse(j.rejectedFocusLossCancellation(sid,cancel,true,4L,3L))
        assertFalse(j.rejectedFocusLossCancellation(sid-1,cancel,true,4L,5L))
        assertTrue(reject(j,cancel))
        assertFalse(j.reconciledFocusLoss(sid,4L,true))
        assertFalse(j.reconciledFocusLoss(sid,6L,true))
        assertFalse(j.reconciledFocusLoss(sid-1,5L,true))
        assertEquals(1,j.snapshot().possibleContacts)
    }

    @Test fun cancelMustBeExactAndAnewCandidateInvalidatesItsOldNegativeReply() {
        val j=focusLost();val cancel=j.cancellationFrame(200L)!!
        val different=listOf(cancel.copy(action=TouchComposer.MOVE),cancel.copy(action=TouchComposer.CANCEL or (1 shl 8)),
            cancel.copy(downTime=99L),cancel.copy(eventTime=199L),cancel.copy(eventTime=201L),
            cancel.copy(ids=intArrayOf(3)),cancel.copy(xs=floatArrayOf(999f)),cancel.copy(ys=floatArrayOf(999f)))
        different.forEach { assertFalse(reject(j,it)) }
        assertTrue(reject(j,cancel))
        cancel.ids[0]=8 // Stored proof owns its arrays.
        assertTrue(j.canReconcileFocusLoss(sid,5L))
        val next=j.cancellationFrame(201L)!!
        assertFalse(j.canReconcileFocusLoss(sid,5L))
        assertFalse(j.reconciledFocusLoss(sid,5L,true))
        assertTrue(reject(j,next));assertTrue(j.reconciledFocusLoss(sid,5L,true))
    }

    @Test fun orphanAndAlreadyTerminalStreamsKeepTheirOwnPolicy() {
        val orphan=RecoveryJournal()
        assertTrue(orphan.adoptForRecovery(sid,1920,1080,10498,frame(TouchComposer.CANCEL)))
        assertFalse(orphan.failForFocusLoss(sid));assertFalse(reject(orphan))
        assertTrue(orphan.reconciledOrphan(sid,true))
        assertFalse(orphan.snapshot().focusLossReconciled)
        val terminal=active()
        val up=frame(TouchComposer.UP,101L)
        assertTrue(terminal.prepare(sid,up));assertTrue(terminal.acknowledged(sid,up,true))
        assertFalse(terminal.failForFocusLoss(sid))
    }

    @Test fun originalDisplayAndExteriorContinuousCameraCoordinatesRemainUnchanged() {
        val j=active(2,TouchCoordinatePolicy.CONTINUOUS_CAMERA_9)
        val add=frame(TouchComposer.POINTER_DOWN or (1 shl 8),101L,intArrayOf(2,9))
        assertTrue(j.prepare(sid,add));assertTrue(j.acknowledged(sid,add,true))
        val move=add.copy(action=TouchComposer.MOVE,eventTime=102L,xs=floatArrayOf(202f,6000f),ys=floatArrayOf(302f,-4000f))
        assertTrue(j.prepare(sid,move));assertTrue(j.acknowledged(sid,move,true))
        assertTrue(j.failForFocusLoss(sid))
        val cancel=j.cancellationFrame(200L)!!
        assertArrayEquals(move.ids,cancel.ids)
        assertArrayEquals(move.xs,cancel.xs,0f);assertArrayEquals(move.ys,cancel.ys,0f)
        assertTrue(reject(j,cancel));assertTrue(j.reconciledFocusLoss(sid,5L,true))
        assertEquals(2,j.snapshot().displayId)
        assertEquals(TouchCoordinatePolicy.CONTINUOUS_CAMERA_9,j.snapshot().coordinatePolicy)
    }
}
