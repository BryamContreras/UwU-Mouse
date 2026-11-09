package dev.uwumouse.app.backend.recovery

import dev.uwumouse.app.engine.TouchComposer
import dev.uwumouse.app.engine.TouchFrame
import org.junit.Assert.*
import org.junit.Test

class RecoveryClosurePolicyTest {
    private fun ambiguousPointerUp(rejected:Boolean):RecoverySnapshot {
        val journal=RecoveryJournal()
        assertTrue(journal.begin(9,1080,2316,10498))
        fun frame(action:Int,vararg ids:Int,time:Long)=TouchFrame(action,100L,time,ids,
            FloatArray(ids.size){20f},FloatArray(ids.size){30f})
        for(event in listOf(frame(TouchComposer.DOWN,0,time=100),
            frame(TouchComposer.POINTER_DOWN or (1 shl 8),0,1,time=101),
            frame(TouchComposer.POINTER_DOWN or (2 shl 8),0,1,9,time=102))) {
            assertTrue(journal.prepare(9,event));assertTrue(journal.acknowledged(9,event,true))
        }
        val up=frame(TouchComposer.POINTER_UP,0,1,9,time=103)
        assertTrue(journal.prepare(9,up))
        if(rejected) assertTrue(journal.acknowledged(9,up,false)) else assertTrue(journal.fail(9))
        return journal.snapshot()
    }
    @Test fun workerLossWithThreeContactsKeepsNormalCancellationRecoveryBeforeRetirement() {
        val lostReply=ambiguousPointerUp(false)
        assertEquals(3,lostReply.possibleContacts)
        assertFalse(lostReply.explicitRejectedPointerUp)
        assertTrue(RecoveryClosurePolicy.mayAttemptNormalRecovery(true,true,lostReply.explicitRejectedPointerUp,false))
    }
    @Test fun explicitPointerUpRejectionCannotBeTreatedAsWorkerLoss() {
        val rejected=ambiguousPointerUp(true)
        assertTrue(rejected.explicitRejectedPointerUp)
        assertFalse(RecoveryClosurePolicy.mayAttemptNormalRecovery(true,true,rejected.explicitRejectedPointerUp,false))
    }
    @Test fun lostFocusWrongIdentityOrStartedTerminalRetirementForbidAnyNormalRetry() {
        assertFalse(RecoveryClosurePolicy.mayAttemptNormalRecovery(false,true,false,false))
        assertFalse(RecoveryClosurePolicy.mayAttemptNormalRecovery(true,false,false,false))
        assertFalse(RecoveryClosurePolicy.mayAttemptNormalRecovery(true,true,false,true))
    }
}
