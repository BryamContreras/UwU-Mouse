package dev.uwumouse.app.session

/** A return ticket owns no input state. Existing context observations drive bounded fresh starts. */
class ContextReturnGate(private val stableMillis:Long=600L,private val retryMillis:Long=500L,
                        private val maximumAttempts:Int=3) {
    data class Identity(val operation:Long,val profileId:String,val packageName:String,val displayId:Int,
                        val manualPauseEpoch:Long=0L)
    data class Frame(val left:Int,val top:Int,val right:Int,val bottom:Int) {
        val valid:Boolean get()=left>=0 && top>=0 && right>left && bottom>top
    }
    var identity:Identity?=null; private set
    private var stableFrame:Frame?=null
    private var stableSince=-1L
    private var attempts=0
    private var inFlight=false
    private var nextAttemptAt=0L
    private var lastObservedAt=-1L
    private var departureRequired=false
    private var departureObserved=false
    private var departureFrame:Frame?=null
    private var reason="not_requested"
    init { require(stableMillis>=0 && retryMillis>=0 && maximumAttempts>0) }

    fun interrupted(owner:Identity,requireDeparture:Boolean=false,originalFrame:Frame?=null) {
        if(identity==owner) return
        identity=owner;attempts=0;inFlight=false;nextAttemptAt=0;lastObservedAt=-1
        departureRequired=requireDeparture;departureObserved=!requireDeparture;departureFrame=originalFrame
        resetStability();reason="waiting_for_game"
    }

    /** Starting a new generation changes only ownership, never the retry budget or held controls. */
    fun rebase(owner:Identity) {
        val old=identity ?: return
        require(old.profileId==owner.profileId && old.packageName==owner.packageName && old.displayId==owner.displayId &&
            old.manualPauseEpoch==owner.manualPauseEpoch)
        identity=owner
    }

    fun clear(cause:String="cancelled") {
        identity=null;inFlight=false;departureFrame=null;resetStability();reason=cause
    }

    fun invalidateStability() { resetStability() }

    /** True requests a new neutral-cursor session; the caller never restores pressed inputs. */
    fun observe(current:Identity?,frame:Frame?,admitted:Boolean,releaseVerified:Boolean,
                preparing:Boolean,active:Boolean,paused:Boolean,now:Long,captureReady:Boolean=active):Boolean {
        val owner=identity ?: return false
        if(current!=owner) { clear("owner_changed");return false }
        if(active) {
            if(captureReady) clear("resumed") else reason="waiting_for_capture"
            return false
        }
        if(now<0 || (lastObservedAt>=0 && now<lastObservedAt)) {
            resetStability();lastObservedAt=now;reason="clock_changed";return false
        }
        lastObservedAt=now
        if(departureRequired && !departureObserved) {
            if(!admitted || (frame?.valid==true && departureFrame!=null && frame!=departureFrame)) departureObserved=true
            else { resetStability();reason="waiting_for_system_handoff";return false }
        }
        if(preparing) { resetStability();reason="preparing";return false }
        if(inFlight) {
            inFlight=false;resetStability();nextAttemptAt=now+retryMillis
            reason="retry_wait";return false
        }
        if(attempts>=maximumAttempts) { clear("attempts_exhausted");return false }
        if(!admitted || frame?.valid!=true || !releaseVerified || !paused) {
            resetStability()
            reason=when { !admitted || frame?.valid!=true -> "waiting_for_game"
                !releaseVerified -> "waiting_for_release";else -> "waiting_for_pause" }
            return false
        }
        if(stableFrame!=frame) { stableFrame=frame;stableSince=now }
        if(now<nextAttemptAt || now-stableSince<stableMillis) { reason="stabilizing";return false }
        attempts++;inFlight=true;reason="starting_neutral_cursor"
        return true
    }

    private fun resetStability() { stableFrame=null;stableSince=-1L }

    fun diagnostics():Map<String,Any?> = mapOf("pending" to (identity!=null),"reason" to reason,
        "attempts" to attempts,"inFlight" to inFlight,"stableSinceUptimeMs" to stableSince,
        "departureRequired" to departureRequired,"departureObserved" to departureObserved,
        "operation" to identity?.operation,"profileId" to identity?.profileId,
        "manualPauseEpoch" to identity?.manualPauseEpoch,
        "packageName" to identity?.packageName,"displayId" to identity?.displayId)
}
