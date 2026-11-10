package dev.uwumouse.app.session

/**
 * Admission snapshot for motion-only native callbacks. Source callbacks must arrive serially
 * (the production source is one oneway Binder node). Main publishes lifecycle/edge state;
 * no motion can bypass an earlier callback that is still waiting for main.
 *
 * A route is only a queue hint. The consumer must recheck its generation, focus and mode,
 * and enqueue that exact generation rather than relabeling a sample for a newer session.
 */
class NativeMotionDispatchGate(private val capacity:Int=512) {
    enum class Dispatch { DEFERRED, DIRECT, STALE }
    data class Route(val captureId:Long,val generation:Long,val buttons:Int,val pointerEpoch:Int) {
        init { require(captureId>0L && generation>=0L && buttons and 31.inv()==0) }
    }

    init { require(capacity>0) }
    private var route:Route?=null
    private var pending=0
    val pendingCallbacks:Int @Synchronized get()=pending

    @Synchronized fun publish(value:Route?) { route=value }

    /** Reserve before posting to main, including callbacks that will only be discarded. */
    @Synchronized fun defer():Boolean {
        if(pending==capacity) return false
        pending++
        return true
    }

    /** Publish after all earlier edge/queue work in this main callback has completed. */
    @Synchronized fun complete(value:Route?) {
        check(pending>0) { "Unpaired native callback completion" }
        route=value
        pending--
    }

    @Synchronized fun directRoute(captureId:Long,buttons:Int):Route? {
        if(pending!=0) return null
        val current=route ?: return null
        return if(current.captureId==captureId && current.buttons==buttons) current else null
    }

    /** A rejected claimed batch belongs to the old mode; never defer/relabel it for a new one. */
    inline fun dispatch(captureId:Long,buttons:Int,submit:(Route)->Boolean):Dispatch {
        val current=directRoute(captureId,buttons) ?: return Dispatch.DEFERRED
        return if(submit(current)) Dispatch.DIRECT else Dispatch.STALE
    }

    companion object {
        /** Only scalar movement is eligible; button identity is checked against the route. */
        fun isMotionOnlyFrame(kind:Int,timeNanos:Long,x:Float,y:Float,wheel:Float,horizontalWheel:Float,
            buttons:Int,keyScanCode:Int,keyDown:Boolean,keyRepeat:Boolean):Boolean =
            kind==0 && keyScanCode<0 && !keyDown && !keyRepeat && timeNanos>=0L &&
                x.isFinite() && y.isFinite() && (x!=0f || y!=0f) && wheel==0f && horizontalWheel==0f &&
                buttons and 31.inv()==0
    }
}
