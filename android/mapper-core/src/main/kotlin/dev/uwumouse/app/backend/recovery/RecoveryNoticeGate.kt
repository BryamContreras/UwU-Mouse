package dev.uwumouse.app.backend.recovery

/**
 * Orders guardian recovery notices delivered through independent Binder callback threads.
 * A notice cannot create a session. Within one worker generation, a terminal notice dominates
 * STARTED even when the callback thread delivering STARTED was delayed. A greater generation
 * can describe another recovery; the controller remains responsible for closing its session.
 */
class RecoveryNoticeGate {
    private var sid = 0L
    private var workerGeneration = 0L
    private var phase = 0
    private var rejected = 0L

    val currentSessionId: Long @Synchronized get() = sid
    val latestWorkerGeneration: Long @Synchronized get() = workerGeneration
    val currentPhase: Int @Synchronized get() = phase
    /** Rejected callbacks over this gate's lifetime. Starting another session preserves it. */
    val discarded: Long @Synchronized get() = rejected

    /** Only the controller's explicit session start can advance the session id. */
    @Synchronized fun begin(sessionId: Long): Boolean {
        if (sessionId <= sid) return false
        sid = sessionId
        workerGeneration = 0
        phase = 0
        return true
    }

    /** A rejected callback changes only the diagnostic counter, never the accepted ordering. */
    @Synchronized fun accept(sessionId: Long, workerGen: Long, phase: Int): Boolean {
        if (sid == 0L || sessionId != sid || workerGen <= 0 || phase !in STARTED..FAILED ||
            workerGen < workerGeneration ||
            (workerGen == workerGeneration && this.phase in ADMITTED..FAILED)
        ) {
            rejected++
            return false
        }
        workerGeneration = workerGen
        this.phase = phase
        return true
    }

    companion object {
        const val STARTED = 1
        const val ADMITTED = 2
        const val FAILED = 3
    }
}
