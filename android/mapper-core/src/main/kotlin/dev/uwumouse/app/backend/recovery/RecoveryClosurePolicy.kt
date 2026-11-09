package dev.uwumouse.app.backend.recovery

/** Normal worker recovery precedes terminal retirement; physical replacement never reopens it. */
object RecoveryClosurePolicy {
    fun mayAttemptNormalRecovery(originalContextAdmitted:Boolean,originalIdentityMatches:Boolean,
                                 explicitRejectedPointerUp:Boolean,terminalRetirementStarted:Boolean):Boolean =
        originalContextAdmitted && originalIdentityMatches && !explicitRejectedPointerUp && !terminalRetirementStarted
}
