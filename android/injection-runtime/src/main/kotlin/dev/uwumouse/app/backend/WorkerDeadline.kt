package dev.uwumouse.app.backend

/** Guarded by the pipe monitor. Extending a wait never requires waking its watchdog. */
internal class WorkerDeadline {
    private var deadline=0L
    private var waitingUntil=0L

    fun arm(nowMillis:Long,timeoutMillis:Long):Boolean {
        require(timeoutMillis>0L)
        deadline=nowMillis+timeoutMillis
        return waitingUntil==0L || deadline<waitingUntil
    }

    fun clear() { deadline=0L }

    /** Null parks indefinitely; nonpositive means that the active operation expired. */
    fun prepareWait(nowMillis:Long):Long? {
        waitingUntil=deadline
        return if(deadline==0L) null else deadline-nowMillis
    }
}
