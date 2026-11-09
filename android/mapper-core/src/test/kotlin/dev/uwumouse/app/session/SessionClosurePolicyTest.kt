package dev.uwumouse.app.session

import org.junit.Assert.*
import org.junit.Test

class SessionClosurePolicyTest {
    private val original=GuardianSessionIdentity(50L,10394,"com.example.game",9)
    private val blocked=GuardianClosureSnapshot(original,1,0,true,false,false,false,"BLOCKED")
    private val closed=blocked.copy(possibleContacts=0,releaseUnverified=false,cancelAdmitted=true,recoveryState="RECOVERED")

    @Test fun idleStopNeedsNoWorkerOrBackendButEveryPendingOwnershipStillCloses() {
        assertFalse(SessionClosurePolicy.needsStopWork(false,false,false,0,false,false))
        assertTrue(SessionClosurePolicy.needsStopWork(true,false,false,0,false,false))
        assertTrue(SessionClosurePolicy.needsStopWork(false,true,false,0,false,false))
        assertTrue(SessionClosurePolicy.needsStopWork(false,false,true,0,false,false))
        assertTrue(SessionClosurePolicy.needsStopWork(false,false,false,2,false,false))
        assertTrue(SessionClosurePolicy.needsStopWork(false,false,false,0,true,false))
        assertTrue(SessionClosurePolicy.needsStopWork(false,false,false,0,false,true))
    }

    @Test fun aFreshControllerCanRetainTheSurvivingGuardiansOriginalClosureContext() {
        assertTrue(SessionClosurePolicy.mayAdoptGuardian(0L,false,false,false,false,false,blocked))
        assertEquals(9,blocked.identity.displayId)
        assertEquals("com.example.game",blocked.identity.packageName)
        assertTrue(blocked.needsClosure)
        assertFalse(SessionClosurePolicy.mayAdoptGuardian(0L,false,false,false,false,false,closed))
    }

    @Test fun anExistingLocalStreamOrStartCannotBeOverwrittenByGuardianAdoption() {
        assertFalse(SessionClosurePolicy.mayAdoptGuardian(20L,false,false,false,false,false,blocked))
        assertFalse(SessionClosurePolicy.mayAdoptGuardian(0L,true,false,false,false,false,blocked))
        assertFalse(SessionClosurePolicy.mayAdoptGuardian(0L,false,true,false,false,false,blocked))
        assertFalse(SessionClosurePolicy.mayAdoptGuardian(0L,false,false,true,false,false,blocked))
        assertFalse(SessionClosurePolicy.mayAdoptGuardian(0L,false,false,false,true,false,blocked))
        assertFalse(SessionClosurePolicy.mayAdoptGuardian(0L,false,false,false,false,true,blocked))
    }

    @Test fun neitherEmptyLocalStateNorAnotherSessionDisplayOrUidProvesTheOriginalStreamClosed() {
        assertFalse(blocked.provesClosureOf(original))
        assertTrue(closed.provesClosureOf(original))
        for(other in listOf(original.copy(sessionId=51L),original.copy(displayId=0),
            original.copy(targetUid=10395),original.copy(packageName="other.game")))
            assertFalse(closed.provesClosureOf(other))
    }

    @Test fun everyContactAndKeyReleaseFlagMustBeClosedWithATerminalProof() {
        for(unknown in listOf(closed.copy(possibleContacts=1),closed.copy(confirmedContacts=1),
            closed.copy(releaseUnverified=true),closed.copy(keyReleaseUnverified=true),
            closed.copy(cancelAdmitted=false,terminalReconciled=false)))
            assertFalse(unknown.provesClosureOf(original))
        assertTrue(closed.copy(cancelAdmitted=false,terminalReconciled=true).provesClosureOf(original))
    }

    @Test fun invalidOrUntargetedIdentityAndMalformedCountsCannotBeAdopted() {
        for(invalid in listOf(blocked.copy(identity=original.copy(sessionId=0L)),
            blocked.copy(identity=original.copy(targetUid=-1)),blocked.copy(identity=original.copy(displayId=-1)),
            blocked.copy(identity=original.copy(packageName="")),blocked.copy(possibleContacts=-1),
            blocked.copy(possibleContacts=11),blocked.copy(confirmedContacts=11))) {
            assertFalse(invalid.valid)
            assertFalse(SessionClosurePolicy.mayAdoptGuardian(0L,false,false,false,false,false,invalid))
        }
    }

    @Test fun explicitStopSettlesOnlyAfterConfirmedReleaseAndKeepsFailureRecoverable() {
        assertEquals(SessionMode.STOPPED,SessionClosurePolicy.settledMode(true,true,false))
        assertEquals(SessionMode.PAUSED,SessionClosurePolicy.settledMode(false,true,false))
        assertEquals(SessionMode.BLOCKED,SessionClosurePolicy.settledMode(true,false,false))
        assertEquals(SessionMode.RECOVERING,SessionClosurePolicy.settledMode(true,false,true))
    }

    @Test fun foregroundLifetimeOutlivesUiCloseUntilTheReleaseBarrierHasFinished() {
        fun allowed(mode:SessionMode=SessionMode.STOPPED,transitioning:Boolean=false,contacts:Int=0,
                    unverified:Boolean=false,stop:Boolean=true,active:Boolean=false)=
            SessionClosurePolicy.mayStopForegroundService(stop,mode,active,transitioning,contacts,unverified)
        assertTrue(allowed())
        assertFalse(allowed(transitioning=true))
        assertFalse(allowed(contacts=1))
        assertFalse(allowed(unverified=true))
        assertFalse(allowed(mode=SessionMode.BLOCKED))
        assertFalse(allowed(mode=SessionMode.RECOVERING))
        assertFalse(allowed(mode=SessionMode.PAUSED))
        assertFalse(allowed(stop=false));assertFalse(allowed(active=true))
    }
    @Test fun delayedRecoveryNoticesCannotReopenAnAlreadyVerifiedStoppedSession() {
        assertFalse(SessionClosurePolicy.mayApplyRecoveryNotice(false,false,0))
        assertTrue(SessionClosurePolicy.mayApplyRecoveryNotice(true,false,0))
        assertTrue(SessionClosurePolicy.mayApplyRecoveryNotice(false,true,0))
        assertTrue(SessionClosurePolicy.mayApplyRecoveryNotice(false,false,1))
    }
}
