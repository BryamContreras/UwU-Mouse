package dev.mobimapper.mobimapper.session

/**
 * Admission snapshot for motion-only native callbacks. Source callbacks must arrive serially
 * (the production source is one oneway Binder node). Main publishes lifecycle/edge state;
 * no motion can bypass an earlier callback that is still waiting for main.
 *
 * A route is only a queue hint. The consumer must recheck its generation, focus and mode,
 * and enqueue that exact generation rather than relabeling a sample for a newer session.
 */
class NativeMotionDispatchGate(private val capacity:Int=512) {
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
}
