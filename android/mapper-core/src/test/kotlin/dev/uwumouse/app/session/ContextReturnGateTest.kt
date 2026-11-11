package dev.uwumouse.app.session

import org.junit.Assert.*
import org.junit.Test

class ContextReturnGateTest {
    private val owner=ContextReturnGate.Identity(7,"profile","dev.example.game",9)
    private val frame=ContextReturnGate.Frame(50,30,1050,730)
    private fun observe(gate:ContextReturnGate,at:Long,current:ContextReturnGate.Identity?=owner,
                        bounds:ContextReturnGate.Frame?=frame,admitted:Boolean=true,released:Boolean=true,
                        preparing:Boolean=false,active:Boolean=false,paused:Boolean=true)=
        gate.observe(current,bounds,admitted,released,preparing,active,paused,at)

    @Test fun aReturnRequiresAnInterruptedOwnerAndStableFreshAdmittedContext() {
        val gate=ContextReturnGate()
        assertFalse(observe(gate,0))
        gate.interrupted(owner)
        assertFalse(observe(gate,100))
        assertFalse(observe(gate,699))
        assertTrue(observe(gate,700))
        assertEquals("starting_neutral_cursor",gate.diagnostics()["reason"])
    }

    @Test fun resizingAndTemporaryFocusLossRestartTheStabilityWindow() {
        val gate=ContextReturnGate();gate.interrupted(owner)
        assertFalse(observe(gate,0))
        assertFalse(observe(gate,500,bounds=frame.copy(right=1200)))
        assertFalse(observe(gate,1_000,bounds=frame.copy(right=1200),admitted=false))
        assertFalse(observe(gate,1_100,bounds=frame.copy(right=1200)))
        assertFalse(observe(gate,1_699,bounds=frame.copy(right=1200)))
        assertTrue(observe(gate,1_700,bounds=frame.copy(right=1200)))
    }

    @Test fun pendingContactsRecoveryPreparationOrNonPausedModeCannotRearm() {
        for(kind in 0..3) {
            val gate=ContextReturnGate();gate.interrupted(owner)
            assertFalse(observe(gate,0))
            assertFalse(observe(gate,1_000,released=kind!=0,preparing=kind==1,paused=kind!=2,
                bounds=if(kind==3) null else frame))
            assertFalse(observe(gate,1_100))
            assertTrue(observe(gate,1_700))
        }
    }

    @Test fun replacingOperationProfilePackageOrDisplayCancelsTheOldReturn() {
        for(replacement in listOf(owner.copy(operation=8),owner.copy(profileId="other"),
            owner.copy(packageName="dev.other.game"),owner.copy(displayId=0),owner.copy(manualPauseEpoch=1),null)) {
            val gate=ContextReturnGate();gate.interrupted(owner)
            assertFalse(observe(gate,0))
            assertFalse(observe(gate,1_000,current=replacement))
            assertNull(gate.identity)
            assertFalse(observe(gate,2_000))
        }
    }

    @Test fun manualStopDuringAFreshStartInvalidatesTheTicketAndAnyLateRetry() {
        val gate=ContextReturnGate();gate.interrupted(owner)
        assertFalse(observe(gate,0));assertTrue(observe(gate,600))
        val next=owner.copy(operation=8);gate.rebase(next)
        assertFalse(observe(gate,700,current=next,preparing=true))
        val stopped=next.copy(manualPauseEpoch=1)
        assertFalse(observe(gate,800,current=stopped))
        assertNull(gate.identity)
        assertFalse(observe(gate,2_000,current=next))
        assertFalse(observe(gate,3_000,current=stopped))
    }

    @Test fun rebasingCannotAdoptANewerManualPauseToContinueAnOldRequest() {
        val gate=ContextReturnGate();gate.interrupted(owner)
        assertFalse(observe(gate,0));assertTrue(observe(gate,600))
        try { gate.rebase(owner.copy(operation=8,manualPauseEpoch=1));fail("Manual pause epoch must stay immutable") }
        catch(expected:IllegalArgumentException) { assertEquals(owner,gate.identity) }
        assertFalse(observe(gate,700,current=owner.copy(manualPauseEpoch=1)))
        assertNull(gate.identity)
    }

    @Test fun explicitStopEditLockOrBackendReplacementClearsTheTicket() {
        for(cause in listOf("stopped","editor","locked","backend_changed")) {
            val gate=ContextReturnGate();gate.interrupted(owner)
            assertFalse(observe(gate,0));gate.clear(cause)
            assertFalse(observe(gate,10_000));assertEquals(cause,gate.diagnostics()["reason"])
        }
    }

    @Test fun preparationCannotDuplicateAStartAndSuccessRetiresTheTicket() {
        val gate=ContextReturnGate();gate.interrupted(owner)
        assertFalse(observe(gate,0));assertTrue(observe(gate,600))
        val next=owner.copy(operation=8);gate.rebase(next)
        assertFalse(observe(gate,800,current=next,preparing=true))
        assertFalse(observe(gate,5_000,current=next,preparing=true))
        assertFalse(observe(gate,5_100,current=next,active=true,paused=false))
        assertNull(gate.identity);assertEquals("resumed",gate.diagnostics()["reason"])
    }

    @Test fun sustainedPhysicalReleaseWaitDoesNotRetireTheTicketOrSpendMoreStartAttempts() {
        val gate=ContextReturnGate();gate.interrupted(owner)
        assertFalse(observe(gate,0));assertTrue(observe(gate,600))
        val next=owner.copy(operation=8);gate.rebase(next)
        for(at in 1_000L..7_000L step 1_000L) {
            assertFalse(gate.observe(next,frame,true,true,false,true,false,at,captureReady=false))
            assertEquals(next,gate.identity)
            assertEquals(1,gate.diagnostics()["attempts"])
            assertEquals("waiting_for_capture",gate.diagnostics()["reason"])
        }
        assertFalse(gate.observe(next,frame,true,true,false,true,false,7_100,captureReady=true))
        assertNull(gate.identity);assertEquals("resumed",gate.diagnostics()["reason"])
    }

    @Test fun manualPauseCancelsAnActiveSessionStillWaitingForPhysicalRelease() {
        val gate=ContextReturnGate();gate.interrupted(owner)
        assertFalse(observe(gate,0));assertTrue(observe(gate,600))
        val next=owner.copy(operation=8);gate.rebase(next)
        assertFalse(gate.observe(next,frame,true,true,false,true,false,1_000,captureReady=false))
        assertFalse(gate.observe(next.copy(manualPauseEpoch=1),frame,true,true,false,false,true,1_100,captureReady=false))
        assertNull(gate.identity)
        assertFalse(gate.observe(next,frame,true,true,false,true,false,5_000,captureReady=true))
    }

    @Test fun failedFreshStartsHaveAFiniteBudgetAcrossNewGenerations() {
        val gate=ContextReturnGate();var current=owner;gate.interrupted(current)
        for(attempt in 0..2) {
            val time=attempt*2_000L
            assertFalse(observe(gate,time,current=current))
            assertTrue(observe(gate,time+600,current=current))
            current=current.copy(operation=current.operation+1);gate.rebase(current)
            assertFalse(observe(gate,time+700,current=current,preparing=true))
            assertFalse(observe(gate,time+900,current=current))
        }
        assertFalse(observe(gate,6_000,current=current))
        assertNull(gate.identity);assertEquals("attempts_exhausted",gate.diagnostics()["reason"])
        assertFalse(observe(gate,100_000,current=current))
    }

    @Test fun longBackgroundIntervalsDoNotRestoreAnythingUntilStableConfirmedReturn() {
        val gate=ContextReturnGate();gate.interrupted(owner)
        assertFalse(observe(gate,0,admitted=false))
        assertFalse(observe(gate,3_600_000,admitted=false))
        assertFalse(observe(gate,3_600_100))
        assertTrue(observe(gate,3_600_700))
    }

    @Test fun invalidGeometryAndClockRegressionCannotSatisfyStability() {
        val gate=ContextReturnGate();gate.interrupted(owner)
        assertFalse(observe(gate,100))
        assertFalse(observe(gate,1_000,bounds=frame.copy(left=-1)))
        assertFalse(observe(gate,1_100))
        assertFalse(observe(gate,1_000))
        assertFalse(observe(gate,1_200));assertTrue(observe(gate,1_800))
    }

    @Test fun systemCursorHandoffWaitsForActualDepartureBeforeRecapturing() {
        val gate=ContextReturnGate();gate.interrupted(owner,true,frame)
        assertFalse(observe(gate,0));assertFalse(observe(gate,10_000))
        assertEquals("waiting_for_system_handoff",gate.diagnostics()["reason"])
        assertFalse(observe(gate,10_100,admitted=false))
        assertFalse(observe(gate,10_200));assertTrue(observe(gate,10_800))
    }

    @Test fun windowResizeCompletesSystemHandoffWithoutRequiringAnUnrelatedFocusedApp() {
        val gate=ContextReturnGate();gate.interrupted(owner,true,frame)
        assertFalse(observe(gate,0));assertFalse(observe(gate,1_000))
        val large=frame.copy(right=1920,bottom=1080)
        assertFalse(observe(gate,1_100,bounds=large));assertTrue(observe(gate,1_700,bounds=large))
    }
}
