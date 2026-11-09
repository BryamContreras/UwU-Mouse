package dev.mobimapper.mobimapper.session

/** Bounded control-plane retries; a focus return never arms a session or replays input. */
class FocusRecoveryGate(private val maximumAttempts:Int=3,private val retryDelayMs:Long=250L) {
    class Attempt internal constructor(val sessionId:Long,val focusEpoch:Long,val ordinal:Int)
    private var focused=false
    private var epoch=0L
    private var sid=0L
    private var attemptedEpoch=-1L
    private var attempts=0
    private var nextAttemptAt=0L
    private var inFlight:Attempt?=null
    init { require(maximumAttempts>0 && retryDelayMs>=0L) }

    @Synchronized fun contextChanged(confirmed:Boolean) {
        if(confirmed && !focused) epoch++
        focused=confirmed
    }
    @Synchronized fun begin(sessionId:Long,eligible:Boolean,nowUptimeMs:Long,allowUnfocused:Boolean=false):Attempt? {
        if((!focused && !allowUnfocused) || !eligible || sessionId<=0L || inFlight!=null) return null
        if(sid!=sessionId || attemptedEpoch!=epoch) {
            sid=sessionId;attemptedEpoch=epoch;attempts=0;nextAttemptAt=0L
        }
        if(attempts>=maximumAttempts || nowUptimeMs<nextAttemptAt) return null
        return Attempt(sessionId,epoch,++attempts).also { inFlight=it }
    }
    /** A user may retry closure without authorizing a new input stream or changing focus. */
    @Synchronized fun retryExplicitly(sessionId:Long):Boolean {
        if(sessionId<=0L || inFlight!=null || (sid!=0L && sid!=sessionId)) return false
        attempts=0;nextAttemptAt=0L
        return true
    }
    @Synchronized fun finished(attempt:Attempt,succeeded:Boolean,nowUptimeMs:Long,retryAllowed:Boolean=true) {
        if(inFlight!==attempt) return
        inFlight=null
        if(attempt.sessionId!=sid || attempt.focusEpoch!=epoch) return
        if(succeeded || !retryAllowed) attempts=maximumAttempts
        else nextAttemptAt=nowUptimeMs+retryDelayMs
    }
}
