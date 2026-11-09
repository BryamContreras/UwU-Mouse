package dev.uwumouse.app.backend.recovery

import dev.uwumouse.app.engine.TouchComposer
import dev.uwumouse.app.engine.TouchCoordinatePolicy
import dev.uwumouse.app.engine.TouchFrame
import org.junit.Assert.*
import org.junit.Test

class ContinuousCameraJournalTest {
    private val continuous=TouchCoordinatePolicy.CONTINUOUS_CAMERA_9
    private fun frame(action:Int,ids:IntArray=intArrayOf(9),xs:FloatArray=FloatArray(ids.size){200f},
                      ys:FloatArray=FloatArray(ids.size){300f},time:Long=100L)=
        TouchFrame(action,100L,time,ids,xs,ys)
    private fun started(policy:TouchCoordinatePolicy=continuous)=RecoveryJournal().also {
        assertTrue(it.begin(1,1000,600,10137,policy))
    }
    private fun admit(journal:RecoveryJournal,frame:TouchFrame) {
        assertTrue(journal.prepare(1,frame))
        assertTrue(journal.acknowledged(1,frame,true))
    }
    private fun outside(time:Long=101L)=frame(TouchComposer.MOVE,xs=floatArrayOf(-5000f),
        ys=floatArrayOf(9000f),time=time)

    @Test fun legacyStrictSessionStillRejectsOutsideCameraCoordinates() {
        val journal=started(TouchCoordinatePolicy.STRICT)
        admit(journal,frame(TouchComposer.DOWN))
        assertFalse(journal.prepare(1,outside()))
        assertEquals(TouchCoordinatePolicy.STRICT,journal.snapshot().coordinatePolicy)
        assertArrayEquals(floatArrayOf(200f),journal.snapshot().activeFrame!!.xs,0f)
    }

    @Test fun initialCameraDownMustBeInsideEvenWithContinuousCapability() {
        for(x in listOf(-1f,1000f,-TouchCoordinatePolicy.LIMIT,TouchCoordinatePolicy.LIMIT)) {
            assertFalse(started().prepare(1,frame(TouchComposer.DOWN,xs=floatArrayOf(x))))
        }
        val journal=started()
        admit(journal,frame(TouchComposer.DOWN,ids=intArrayOf(0)))
        assertFalse(journal.prepare(1,frame(TouchComposer.POINTER_DOWN or (1 shl 8),
            ids=intArrayOf(0,9),xs=floatArrayOf(200f,-10f),time=101L)))
    }

    @Test fun admittedCameraCanCrossEveryBoundaryAndReleaseOutsideWithoutChangingDimensions() {
        val journal=started()
        admit(journal,frame(TouchComposer.DOWN))
        admit(journal,outside())
        admit(journal,frame(TouchComposer.MOVE,xs=floatArrayOf(TouchCoordinatePolicy.LIMIT),
            ys=floatArrayOf(-TouchCoordinatePolicy.LIMIT),time=102L))
        admit(journal,frame(TouchComposer.UP,xs=floatArrayOf(TouchCoordinatePolicy.LIMIT),
            ys=floatArrayOf(-TouchCoordinatePolicy.LIMIT),time=103L))
        val snap=journal.snapshot()
        assertEquals(1000,snap.width); assertEquals(600,snap.height)
        assertEquals(10137,snap.targetUid)
        assertEquals(0,snap.possibleContacts)
    }

    @Test fun outsideCapabilityRejectsNonFiniteAndExcessiveCoordinates() {
        val journal=started()
        admit(journal,frame(TouchComposer.DOWN))
        for(x in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,
            TouchCoordinatePolicy.LIMIT+1f,-TouchCoordinatePolicy.LIMIT-1f)) {
            assertFalse(journal.prepare(1,frame(TouchComposer.MOVE,xs=floatArrayOf(x),time=101L)))
            assertFalse(journal.prepare(1,frame(TouchComposer.MOVE,ys=floatArrayOf(x),time=101L)))
        }
    }

    @Test fun joystickAndFireMayJoinAndLeaveWhileCameraRemainsOutside() {
        val journal=started()
        admit(journal,frame(TouchComposer.DOWN))
        admit(journal,outside())
        admit(journal,frame(TouchComposer.POINTER_DOWN,ids=intArrayOf(0,9),
            xs=floatArrayOf(100f,-5000f),ys=floatArrayOf(450f,9000f),time=102L))
        admit(journal,frame(TouchComposer.POINTER_DOWN or (1 shl 8),ids=intArrayOf(0,1,9),
            xs=floatArrayOf(100f,850f,-5000f),ys=floatArrayOf(450f,450f,9000f),time=103L))
        admit(journal,frame(TouchComposer.POINTER_UP,ids=intArrayOf(0,1,9),
            xs=floatArrayOf(100f,850f,-5000f),ys=floatArrayOf(450f,450f,9000f),time=104L))
        assertArrayEquals(intArrayOf(1,9),journal.snapshot().activeFrame!!.ids)
        admit(journal,frame(TouchComposer.POINTER_UP,ids=intArrayOf(1,9),
            xs=floatArrayOf(850f,-5000f),ys=floatArrayOf(450f,9000f),time=105L))
        admit(journal,frame(TouchComposer.UP,xs=floatArrayOf(-5000f),ys=floatArrayOf(9000f),time=106L))
        assertEquals(0,journal.snapshot().possibleContacts)
    }

    @Test fun capabilityNeverAllowsAnotherPointerOutside() {
        val journal=started()
        admit(journal,frame(TouchComposer.DOWN))
        admit(journal,frame(TouchComposer.POINTER_DOWN,ids=intArrayOf(0,9),time=101L))
        assertFalse(journal.prepare(1,frame(TouchComposer.MOVE,ids=intArrayOf(0,9),
            xs=floatArrayOf(-1f,-5000f),time=102L)))
        assertFalse(journal.prepare(1,frame(TouchComposer.POINTER_DOWN or (1 shl 8),
            ids=intArrayOf(0,1,9),xs=floatArrayOf(100f,1001f,-5000f),time=102L)))
    }

    @Test fun missingMoveReplyKeepsExactOutsideCancellationCandidate() {
        val journal=started()
        admit(journal,frame(TouchComposer.DOWN))
        val attempted=outside()
        assertTrue(journal.prepare(1,attempted))
        assertTrue(journal.fail(1)) // Worker may have died before or after delivery, before ACK.
        attempted.xs[0]=0f
        val cancel=journal.cancellationFrame(200L)!!
        assertEquals(TouchComposer.CANCEL,cancel.action)
        assertEquals(100L,cancel.downTime)
        assertArrayEquals(intArrayOf(9),cancel.ids)
        assertArrayEquals(floatArrayOf(-5000f),cancel.xs,0f)
        assertArrayEquals(floatArrayOf(9000f),cancel.ys,0f)
        assertFalse(journal.begin(2,1000,600,10137))
    }

    @Test fun workerFailureAfterAcknowledgedOutsideMoveUsesSameCoordinates() {
        val journal=started()
        admit(journal,frame(TouchComposer.DOWN))
        admit(journal,outside())
        assertTrue(journal.fail(1))
        val cancel=journal.cancellationFrame(200L)!!
        assertArrayEquals(floatArrayOf(-5000f),cancel.xs,0f)
        assertArrayEquals(floatArrayOf(9000f),cancel.ys,0f)
        cancel.xs[0]=0f
        assertEquals(-5000f,journal.cancellationFrame(201L)!!.xs[0],0f)
    }

    @Test fun ambiguousPointerDownIncludesBothNewControlAndOutsideCameraInCancel() {
        val journal=started()
        admit(journal,frame(TouchComposer.DOWN))
        admit(journal,outside())
        val adding=frame(TouchComposer.POINTER_DOWN,ids=intArrayOf(0,9),
            xs=floatArrayOf(100f,-5000f),ys=floatArrayOf(450f,9000f),time=102L)
        assertTrue(journal.prepare(1,adding))
        assertTrue(journal.acknowledged(1,adding,false))
        val cancel=journal.cancellationFrame(200L)!!
        assertArrayEquals(intArrayOf(0,9),cancel.ids)
        assertArrayEquals(floatArrayOf(100f,-5000f),cancel.xs,0f)
        assertArrayEquals(floatArrayOf(450f,9000f),cancel.ys,0f)
    }

    @Test fun explicitNewStrictSessionDoesNotInheritContinuousCapability() {
        val journal=started()
        admit(journal,frame(TouchComposer.DOWN))
        admit(journal,outside())
        assertFalse(journal.begin(2,1000,600,10137,TouchCoordinatePolicy.STRICT))
        assertEquals(continuous,journal.snapshot().coordinatePolicy)
        assertTrue(journal.fail(1)); assertTrue(journal.cancelled(1,true))
        assertTrue(journal.begin(2,1000,600,10137))
        assertEquals(TouchCoordinatePolicy.STRICT,journal.snapshot().coordinatePolicy)
        val down=frame(TouchComposer.DOWN,time=300L).copy(downTime=300L)
        assertTrue(journal.prepare(2,down)); assertTrue(journal.acknowledged(2,down,true))
        assertFalse(journal.prepare(2,outside(301L).copy(downTime=300L)))
    }

    @Test fun orphanOutsideCancelRequiresExplicitContinuousPolicyAndCannotImportDown() {
        val cancel=outside().copy(action=TouchComposer.CANCEL)
        assertFalse(RecoveryJournal().adoptForRecovery(8,1000,600,10137,cancel))
        val journal=RecoveryJournal()
        assertTrue(journal.adoptForRecovery(8,1000,600,10137,cancel,continuous))
        assertEquals(continuous,journal.snapshot().coordinatePolicy)
        assertEquals(RecoveryInjectionState.RECOVERY_NEEDED,journal.snapshot().state)
        assertFalse(journal.prepare(8,frame(TouchComposer.DOWN)))
        assertFalse(RecoveryJournal().adoptForRecovery(8,1000,600,10137,
            cancel.copy(action=TouchComposer.DOWN),continuous))
        assertFalse(RecoveryJournal().adoptForRecovery(8,1000,600,10137,
            cancel.copy(ids=intArrayOf(8)),continuous))
    }

    @Test fun replacementWorkerCoordinateGatePermitsOnlyRecoveryCancelWithoutPriorCamera() {
        val move=outside()
        assertFalse(continuous.allowsCoordinates(move,1000,600))
        assertFalse(continuous.allowsCoordinates(move,1000,600,recoveryCancellation=true))
        val cancel=move.copy(action=TouchComposer.CANCEL)
        assertTrue(continuous.allowsCoordinates(cancel,1000,600,recoveryCancellation=true))
        assertFalse(TouchCoordinatePolicy.STRICT.allowsCoordinates(cancel,1000,600,
            recoveryCancellation=true))
        assertFalse(continuous.allowsCoordinates(cancel.copy(ids=intArrayOf(8)),1000,600,
            recoveryCancellation=true))
    }
}
