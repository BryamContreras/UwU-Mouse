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
        for(displayId in listOf(0,2)) {
            val identity=first.copy(displayId=displayId)
            val gate=GameTaskClosureGate();arm(gate,identity)
            for(now in 2_000L..30_000L step 1_000L)
                assertFalse(gate.observe(identity,sample(now,present=true),now,true))
        }
    }

    @Test fun externalGameClosureCanBeConfirmedWithThePhoneFocusedOrNoFocusedApplication() {
        val external=first.copy(displayId=2)
        val gate=GameTaskClosureGate();arm(gate,external)
        // The global task inventory remains authoritative on a DeX desktop or while
        // Inicio is focused on display 0. No target focus is required for closure.
        assertFalse(gate.observe(external,sample(2_000,focused=false),2_000,true))
        assertFalse(gate.observe(external,sample(3_000,focused=false),3_000,true))
        assertTrue(gate.observe(external,sample(4_000,focused=false),4_000,true))
    }

    @Test fun externalSessionNeverUsesStaleOrInvalidWindowMetadataToConfirmClosure() {
        val external=first.copy(displayId=2)
        val gate=GameTaskClosureGate();arm(gate,external)
        assertFalse(gate.observe(external,sample(2_000),2_000,true))
        assertFalse(gate.observe(external,sample(3_000,contextValid=false),3_000,true))
        assertFalse(gate.observe(external,sample(4_000).copy(contextCapturedUptimeMs=1_000),4_000,true))
        assertFalse(gate.observe(external,sample(5_000),5_000,true))
        assertFalse(gate.observe(external,sample(6_000),6_000,true))
        assertTrue(gate.observe(external,sample(7_000),7_000,true))
    }

    @Test fun switchingTheSessionDisplayDiscardsThePreviousDisplayAbsenceHistory() {
        val gate=GameTaskClosureGate();arm(gate)
        assertFalse(gate.observe(first,sample(2_000),2_000,true))
        val external=first.copy(displayId=2)
        for(now in 3_000L..7_000L step 1_000L)
            assertFalse(gate.observe(external,sample(now),now,true))
        assertFalse(gate.observe(external,sample(8_000,present=true),8_000,true))
        assertFalse(gate.observe(external,sample(9_000),9_000,true))
        assertTrue(gate.observe(external,sample(11_000),11_000,true))
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
