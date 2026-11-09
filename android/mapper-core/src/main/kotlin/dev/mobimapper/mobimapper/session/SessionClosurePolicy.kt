package dev.mobimapper.mobimapper.session

/** Identity of an existing guardian journal, never a request to start or retarget input. */
data class GuardianSessionIdentity(val sessionId:Long,val targetUid:Int,val packageName:String,val displayId:Int) {
    val valid:Boolean get()=sessionId>0L && targetUid>=10_000 && displayId>=0 &&
        packageName.matches(Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))
}

/** Detached, authenticated guardian status. Possible contacts remain possible until closure proof. */
data class GuardianClosureSnapshot(
    val identity:GuardianSessionIdentity,
    val possibleContacts:Int,val confirmedContacts:Int,
    val releaseUnverified:Boolean,val keyReleaseUnverified:Boolean,
    val cancelAdmitted:Boolean,val terminalReconciled:Boolean,val recoveryState:String,
) {
    val valid:Boolean get()=identity.valid && possibleContacts in 0..10 && confirmedContacts in 0..10
    fun provesClosureOf(expected:GuardianSessionIdentity):Boolean = valid && identity==expected &&
        possibleContacts==0 && confirmedContacts==0 && !releaseUnverified && !keyReleaseUnverified &&
        (cancelAdmitted || terminalReconciled)
    val needsClosure:Boolean get()=valid && !provesClosureOf(identity) &&
        (possibleContacts>0 || confirmedContacts>0 || releaseUnverified || keyReleaseUnverified ||
            recoveryState in setOf("ACTIVE","RECOVERING","BLOCKED","PAUSED_CONTROLLER_GONE"))
}

/** Closing the UI is separate from proving that Android released its old input stream. */
object SessionClosurePolicy {
    /** A late terminal notice cannot reopen ownership that already has verified closure. */
    fun mayApplyRecoveryNotice(hasSessionOwner:Boolean,releaseUnverified:Boolean,contacts:Int):Boolean =
        hasSessionOwner || releaseUnverified || contacts>0

    fun mayAdoptGuardian(localSessionId:Long,hasComposer:Boolean,active:Boolean,armed:Boolean,
                        starting:Boolean,testRunning:Boolean,snapshot:GuardianClosureSnapshot):Boolean =
        localSessionId==0L && !hasComposer && !active && !armed && !starting && !testRunning && snapshot.needsClosure

    fun settledMode(explicitStop:Boolean,releaseVerified:Boolean,recoveryInProgress:Boolean):SessionMode = when {
        releaseVerified -> if(explicitStop) SessionMode.STOPPED else SessionMode.PAUSED
        recoveryInProgress -> SessionMode.RECOVERING
        else -> SessionMode.BLOCKED
    }

    fun mayStopForegroundService(stopRequested:Boolean,mode:SessionMode,active:Boolean,transitioning:Boolean,
                                 contacts:Int,releaseUnverified:Boolean):Boolean =
        stopRequested && mode==SessionMode.STOPPED && !active && !transitioning && contacts==0 && !releaseUnverified
}
