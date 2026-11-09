package dev.mobimapper.mobimapper.session

import org.junit.Assert.*
import org.junit.Test

class FocusRecoveryGateTest {
    @Test fun focusReturnWhilePauseStillFinishesDoesNotLoseRecovery() {
        val gate=FocusRecoveryGate()
        gate.contextChanged(false);gate.contextChanged(true)
        assertNull(gate.begin(41L,eligible=false,nowUptimeMs=100L))
        assertNotNull(gate.begin(41L,eligible=true,nowUptimeMs=200L))
    }
    @Test fun onlyOneAttemptRunsAndRetriesAreBoundedWithinTheSameFocusEpoch() {
        val gate=FocusRecoveryGate();gate.contextChanged(true)
        val first=gate.begin(41L,true,100L)!!
        assertNull(gate.begin(41L,true,200L))
        gate.finished(first,false,200L)
        assertNull(gate.begin(41L,true,449L))
        val second=gate.begin(41L,true,450L)!!;gate.finished(second,false,500L)
        val third=gate.begin(41L,true,750L)!!;gate.finished(third,false,800L)
        assertNull(gate.begin(41L,true,10_000L))
        gate.contextChanged(false);assertNull(gate.begin(41L,true,10_001L))
        gate.contextChanged(true);assertNotNull(gate.begin(41L,true,10_002L))
    }
    @Test fun lateFinishCannotAffectAnotherFocusEpochOrAnotherSession() {
        val gate=FocusRecoveryGate();gate.contextChanged(true)
        val old=gate.begin(41L,true,100L)!!
        gate.contextChanged(false);gate.contextChanged(true)
        gate.finished(old,true,200L)
        val current=gate.begin(41L,true,201L)!!
        gate.finished(old,true,202L)
        assertNull(gate.begin(42L,true,203L))
        gate.finished(current,true,204L)
        assertNull(gate.begin(41L,true,1000L))
        assertNotNull(gate.begin(42L,true,1001L))
    }
    @Test fun unavailableContextAndInvalidSessionNeverRequestRecovery() {
        val gate=FocusRecoveryGate()
        assertNull(gate.begin(41L,true,100L))
        gate.contextChanged(true)
        assertNull(gate.begin(0L,true,100L));assertNull(gate.begin(-1L,true,100L))
        assertNull(gate.begin(41L,false,100L))
    }
    @Test fun unavailableEngineCapabilityStopsRetriesWithoutClaimingSuccessfulRecovery() {
        val gate=FocusRecoveryGate();gate.contextChanged(true)
        val attempt=gate.begin(41L,true,100L)!!
        gate.finished(attempt,false,200L,retryAllowed=false)
        assertNull(gate.begin(41L,true,10_000L))
    }
    @Test fun removedDisplayClosureCanBeAttemptedWithoutWeakeningTheDefaultFocusGate() {
        val gate=FocusRecoveryGate(maximumAttempts=1)
        assertNull(gate.begin(41L,true,100L))
        val closure=gate.begin(41L,true,100L,allowUnfocused=true)!!
        assertNull(gate.begin(41L,true,101L,allowUnfocused=true))
        gate.finished(closure,false,200L)
        assertNull(gate.begin(41L,true,10_000L,allowUnfocused=true))
        gate.contextChanged(true)
        assertNotNull(gate.begin(41L,true,10_001L))
    }
    @Test fun explicitRetryRenewsOnlyTheSameIdleClosureOwnerWithoutStartingAnything() {
        val gate=FocusRecoveryGate(maximumAttempts=1)
        val closure=gate.begin(41L,true,100L,allowUnfocused=true)!!
        assertFalse(gate.retryExplicitly(41L))
        gate.finished(closure,false,200L)
        assertFalse(gate.retryExplicitly(42L));assertFalse(gate.retryExplicitly(0L))
        assertTrue(gate.retryExplicitly(41L))
        assertNull(gate.begin(41L,true,201L)) // Retrying closure does not manufacture focus.
        assertNotNull(gate.begin(41L,true,201L,allowUnfocused=true))
    }
}
