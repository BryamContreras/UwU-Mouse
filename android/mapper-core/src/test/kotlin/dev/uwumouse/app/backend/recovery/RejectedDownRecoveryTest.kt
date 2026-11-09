package dev.uwumouse.app.backend.recovery

import dev.uwumouse.app.engine.TouchComposer
import dev.uwumouse.app.engine.TouchFrame
import org.junit.Assert.*
import org.junit.Test

class RejectedDownRecoveryTest {
    private val down = TouchFrame(TouchComposer.DOWN,100,100,intArrayOf(0),floatArrayOf(50f),floatArrayOf(0f))
    private fun journal(explicitRejection:Boolean=true) = RecoveryJournal().also {
        assertTrue(it.begin(1,1920,1080,10137))
        assertTrue(it.prepare(1,down))
        assertTrue(if(explicitRejection) it.acknowledged(1,down,false) else it.fail(1))
    }
    private fun reject(journal:RecoveryJournal, terminated:Boolean=true, old:Long=1, replacement:Long=2):Boolean =
        journal.rejectedInitialDownCancellation(1,journal.cancellationFrame(200)!!,terminated,old,replacement)

    @Test fun exactRejectedRetryAndGlobalNoDownCloseWithoutClaimingAdmission() {
        val journal=journal()
        assertFalse(journal.reconciledRejectedDown(1,2,true))
        assertTrue(reject(journal))
        assertFalse(journal.reconciledRejectedDown(1,2,false))
        assertFalse(journal.reconciledRejectedDown(2,2,true))
        assertFalse(journal.reconciledRejectedDown(1,3,true))
        assertTrue(journal.reconciledRejectedDown(1,2,true))
        val snapshot=journal.snapshot()
        assertEquals(0,snapshot.possibleContacts)
        assertTrue(snapshot.rejectedDownReconciled)
        assertFalse(snapshot.cancellationAccepted)
        assertFalse(snapshot.receiverVerified)
        assertTrue(journal.begin(2,1920,1080,10137))
        assertFalse(journal.snapshot().rejectedDownReconciled)
    }

    @Test fun missingAckOrUnterminatedOrReusedWorkerCannotProveClosure() {
        assertFalse(reject(journal(false)))
        assertFalse(reject(journal(),terminated=false))
        assertFalse(reject(journal(),old=0))
        assertFalse(reject(journal(),replacement=1))
    }

    @Test fun alteredOrStaleCancellationCannotProveClosure() {
        val journal=journal()
        val cancel=journal.cancellationFrame(200)!!
        for(altered in listOf(cancel.copy(eventTime=199),cancel.copy(downTime=99),
            cancel.copy(xs=floatArrayOf(51f)),cancel.copy(ids=intArrayOf(1)),cancel.copy(action=TouchComposer.UP))) {
            assertFalse(journal.rejectedInitialDownCancellation(1,altered,true,1,2))
        }
        assertTrue(journal.rejectedInitialDownCancellation(1,cancel,true,1,2))
        journal.cancellationFrame(201) // A new retry needs its own negative acknowledgement.
        assertFalse(journal.reconciledRejectedDown(1,2,true))
    }

    @Test fun addingPointerToExistingGestureCannotUseInitialDownPolicy() {
        val journal=RecoveryJournal()
        assertTrue(journal.begin(1,1920,1080,10137))
        assertTrue(journal.prepare(1,down));assertTrue(journal.acknowledged(1,down,true))
        val add=TouchFrame(TouchComposer.POINTER_DOWN or (1 shl 8),100,101,
            intArrayOf(0,1),floatArrayOf(50f,100f),floatArrayOf(0f,100f))
        assertTrue(journal.prepare(1,add));assertTrue(journal.acknowledged(1,add,false))
        assertFalse(reject(journal))
        assertFalse(journal.reconciledRejectedDown(1,2,true))
    }
}
