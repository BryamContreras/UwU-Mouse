package dev.uwumouse.app.game

import org.junit.Assert.*
import org.junit.Test

class GameSessionLifecycleTest {
    private val first=GameTaskClosureGate.Identity(1,"game",0)
    private fun sample(at:Long,present:Boolean=false,window:Boolean=false,focused:Boolean=false,
        valid:Boolean=true,contextValid:Boolean=true,packageName:String="game") =
        GameTaskClosureGate.Observation(packageName,at,valid,present,at,contextValid,window,focused)
    private fun arm(gate:GameTaskClosureGate,identity:GameTaskClosureGate.Identity=first) {
        assertFalse(gate.observe(identity,sample(1_000,present=true),1_000,true))
    }

    @Test fun backgroundMinimizedAndOtherDisplayTasksNeverCloseTheSession() {
        val gate=GameTaskClosureGate();arm(gate)
        for(now in 2_000L..30_000L step 1_000L)
            assertFalse(gate.observe(first,sample(now,present=true),now,true))
    }

    @Test fun removedTaskNeedsDistinctFreshAbsenceAcrossTheGracePeriodAndClosesOnce() {
        val gate=GameTaskClosureGate();arm(gate)
        assertFalse(gate.observe(first,sample(2_000),2_000,true))
        assertFalse(gate.observe(first,sample(2_000),2_999,true))
        assertFalse(gate.observe(first,sample(3_000),3_000,true))
        assertTrue(gate.observe(first,sample(4_000),4_000,true))
        assertFalse(gate.observe(first,sample(5_000),5_000,true))
    }

    @Test fun failedStaleOrConflictingSnapshotsBreakTheAbsenceConfirmation() {
        for(invalid in listOf(sample(3_000,valid=false),sample(3_000,contextValid=false),
            sample(1_000),sample(3_000,window=true),sample(3_000,focused=true),
            sample(3_000,packageName="other"))) {
            val gate=GameTaskClosureGate();arm(gate)
            assertFalse(gate.observe(first,sample(2_000),2_000,true))
            assertFalse(gate.observe(first,invalid,3_000,true))
            assertFalse(gate.observe(first,sample(4_000),4_000,true))
            assertFalse(gate.observe(first,sample(5_000),5_000,true))
            assertTrue(gate.observe(first,sample(6_000),6_000,true))
        }
    }

    @Test fun preparationEditorOrTransitionCannotAccumulateEvidenceTowardClosure() {
        val gate=GameTaskClosureGate();arm(gate)
        for(now in 2_000L..10_000L step 1_000L)
            assertFalse(gate.observe(first,sample(now),now,false))
        assertFalse(gate.observe(first,sample(11_000),11_000,true))
        assertFalse(gate.observe(first,sample(12_000),12_000,true))
        assertTrue(gate.observe(first,sample(13_000),13_000,true))
    }

    @Test fun taskReappearanceCancelsThePreviousAbsenceAndANewSessionNeedsItsOwnObservedTask() {
        val gate=GameTaskClosureGate();arm(gate)
        assertFalse(gate.observe(first,sample(2_000),2_000,true))
        assertFalse(gate.observe(first,sample(3_000,present=true),3_000,true))
        assertFalse(gate.observe(first,sample(4_000),4_000,true))
        val next=first.copy(operation=2)
        for(now in 5_000L..10_000L step 1_000L)
            assertFalse(gate.observe(next,sample(now),now,true))
        assertFalse(gate.observe(next,sample(11_000,present=true),11_000,true))
        assertFalse(gate.observe(next,sample(12_000),12_000,true))
        assertTrue(gate.observe(next,sample(14_000),14_000,true))
    }

    @Test fun visualCleanupFailureCannotPreventTheNativeStopOrTheOtherCleanupOwners() {
        val order=mutableListOf<String>()
        val failures=mutableListOf<Exception>()
        stopSessionThenCleanUp({order+="native"},listOf(
            {order+="monitor";error("Bad token")},
            {order+="editor"},
            {order+="bubble";throw IllegalArgumentException("Not attached")},
            {order+="controls"}),{failures+=it})
        assertEquals(listOf("native","monitor","editor","bubble","controls"),order)
        assertEquals(2,failures.size)
    }

    @Test fun nativeStopFailureStillLetsEveryVisualOwnerAttemptItsCleanup() {
        val cleanup=mutableListOf<Int>()
        try {
            stopSessionThenCleanUp({error("Stop failed")},listOf({cleanup+=1},{cleanup+=2}),{fail("Unexpected cleanup failure")})
            fail("Expected native failure")
        } catch(expected:IllegalStateException) { assertEquals("Stop failed",expected.message) }
        assertEquals(listOf(1,2),cleanup)
    }
}
