package dev.mobimapper.mobimapper.session

import org.junit.Assert.*
import org.junit.Test

class PointerTransitionGateTest {
    @Test fun rapidPressesCannotQueueSeveralUnacknowledgedModeFlips() {
        val gate=PointerTransitionGate();val first=gate.request()!!
        repeat(40) { assertNull(gate.request()) }
        assertTrue(gate.pending);assertTrue(gate.complete(first));assertFalse(gate.pending)
        assertNotNull(gate.request())
    }
    @Test fun pauseInvalidatesTheOldCaptureAcknowledgement() {
        val gate=PointerTransitionGate();val stale=gate.request()!!;gate.invalidate()
        val next=gate.request()!!
        assertFalse(gate.complete(stale));assertTrue(gate.pending)
        assertTrue(gate.complete(next));assertFalse(gate.pending)
    }
    @Test fun duplicateAcknowledgementsDoNotCompleteANewerTransition() {
        val gate=PointerTransitionGate();val first=gate.request()!!;gate.complete(first)
        val next=gate.request()!!
        assertFalse(gate.current(first));assertFalse(gate.complete(first));assertTrue(gate.current(next))
    }
    @Test fun holdReturnsToControlOnlyAfterItsReleaseIsAcknowledged() {
        val gate=PointerTransitionGate();gate.begin(false)
        val enter=gate.hold(true)!!
        assertEquals(true,gate.requestedCursor(enter));assertTrue(gate.temporaryCursorHeld)
        assertNull(gate.acknowledge(enter,true))
        val leave=gate.hold(false)!!
        assertEquals(false,gate.requestedCursor(leave));assertNull(gate.acknowledge(leave,false))
        assertFalse(gate.pending);assertFalse(gate.baseCursor)
    }
    @Test fun holdingInPersistentCursorNeverForcesControlOnRelease() {
        val gate=PointerTransitionGate();gate.begin(true)
        assertNull(gate.hold(true));assertTrue(gate.temporaryCursorHeld)
        assertNull(gate.hold(false));assertTrue(gate.baseCursor);assertFalse(gate.pending)
    }
    @Test fun repeatedHoldDownsAreIdempotent() {
        val gate=PointerTransitionGate();gate.begin(false)
        val enter=gate.hold(true)!!
        repeat(50) { assertNull(gate.hold(true)) }
        assertEquals(true,gate.requestedCursor(enter));assertNull(gate.acknowledge(enter,true))
        assertNotNull(gate.hold(false))
    }
    @Test fun rapidDownUpBeforeWorkerRunsUsesTheReleasedDestination() {
        val gate=PointerTransitionGate();gate.begin(false)
        val enter=gate.hold(true)!!;assertNull(gate.hold(false))
        assertEquals(false,gate.requestedCursor(enter))
        assertNull(gate.acknowledge(enter,false));assertFalse(gate.pending)
    }
    @Test fun releaseAfterWorkerReadIsLatchedAcrossTheMainThreadAcknowledgement() {
        val gate=PointerTransitionGate();gate.begin(false)
        val enter=gate.hold(true)!!;val workerDestination=gate.requestedCursor(enter)!!
        assertNull(gate.hold(false))
        val leave=gate.acknowledge(enter,workerDestination)!!
        assertFalse(gate.complete(enter));assertEquals(false,gate.requestedCursor(leave))
        assertNull(gate.acknowledge(leave,false));assertFalse(gate.pending)
    }
    @Test fun persistentToggleDuringHoldComposesWithTheHeldOverride() {
        val gate=PointerTransitionGate();gate.begin(false)
        val enter=gate.hold(true)!!;gate.acknowledge(enter,true)
        assertNull(gate.toggleBase());assertTrue(gate.baseCursor)
        assertNull(gate.hold(false))
        val leave=gate.toggleBase()!!;assertEquals(false,gate.requestedCursor(leave))
        gate.acknowledge(leave,false)
    }
    @Test fun severalFreshTogglesKeepTheLatestDestinationWhileOneHandoffIsPending() {
        val gate=PointerTransitionGate();gate.begin(false)
        val first=gate.toggleBase()!!
        assertNull(gate.toggleBase());assertNull(gate.toggleBase())
        assertEquals(true,gate.requestedCursor(first));assertNull(gate.acknowledge(first,true))
        assertTrue(gate.baseCursor);assertFalse(gate.pending)
    }
    @Test fun pauseInvalidatesHoldAndItsLateUpCannotArmAnotherSession() {
        val gate=PointerTransitionGate();gate.begin(false)
        val stale=gate.hold(true)!!;gate.invalidate()
        assertNull(gate.hold(false));assertNull(gate.toggleBase());assertNull(gate.requestedCursor(stale))
        assertNull(gate.acknowledge(stale,true));assertFalse(gate.temporaryCursorHeld)
        gate.begin(false)
        assertNull(gate.hold(false));assertFalse(gate.pending)
        assertEquals(true,gate.requestedCursor(gate.hold(true)!!))
    }
    @Test fun mappedCommandCannotChangeBaseUntilExistingHandoffIsAcknowledged() {
        val gate=PointerTransitionGate();gate.begin(false)
        val previous=gate.toggleBase()!!
        assertNull(gate.requestCommand());assertTrue(gate.baseCursor)
        gate.acknowledge(previous,true)
        val command=gate.requestCommand()!!
        assertFalse(gate.baseCursor);assertEquals(false,gate.requestedCursor(command))
    }
    @Test fun mappedCommandOwnsTicketEvenWhenHoldKeepsEffectiveCursorUnchanged() {
        val gate=PointerTransitionGate();gate.begin(true)
        assertNull(gate.hold(true))
        val command=gate.requestCommand()!!
        assertFalse(gate.baseCursor);assertEquals(true,gate.requestedCursor(command))
        assertNull(gate.acknowledge(command,true))
        val release=gate.hold(false)!!;assertEquals(false,gate.requestedCursor(release))
    }
}
