package dev.uwumouse.app.session

/**
 * Admission/ownership for native callbacks. Source callbacks must arrive serially
 * (the production source is one oneway Binder node). Main publishes lifecycle/edge state;
 * no motion can bypass an earlier callback that is still waiting for main.
 *
 * A route is only a queue hint. The consumer must recheck its generation, focus and mode,
 * and enqueue that exact generation rather than relabeling a sample for a newer session.
 */
class NativeMotionDispatchGate(private val capacity:Int=512) {
    enum class Dispatch { DEFERRED, DIRECT, STALE, OVERFLOW }
    enum class Admission { ACCEPTED, STALE, OVERFLOW }
    data class Route(val captureId:Long,val generation:Long,val buttons:Int,val pointerEpoch:Int,
        val gameplay:NativeGameplayPolicy=NativeGameplayPolicy.NONE) {
        init { require(captureId>0L && generation>=0L && buttons and 31.inv()==0) }
    }
    data class KeyOwner(val code:Int,val route:Route)
    data class ButtonObservation(val released:Int,val changed:Int)

    init { require(capacity>0) }
    private var route:Route?=null
    private var pending=0
    private val buttonGate=MouseButtonGate()
    @Volatile private var observedButtons=0
    @Volatile private var buttonsKnown=false
    private val keyOwners=HashMap<Long,KeyOwner>()
    val pendingCallbacks:Int @Synchronized get()=pending
    val physicalButtons:Int get()=observedButtons
    val physicalButtonsKnown:Boolean get()=buttonsKnown
    val ownedKeyCount:Int @Synchronized get()=keyOwners.size

    @Synchronized fun resetButtons(known:Boolean=false) {
        observedButtons=0;buttonsKnown=known
        if(known) buttonGate.rebase(0) else buttonGate.reset()
    }
    @Synchronized fun rebaseButtons(buttons:Int) { buttonGate.rebase(buttons) }
    @Synchronized fun resetButtonBaseline() { buttonGate.reset() }
    @Synchronized fun observeButtons(buttons:Int):ButtonObservation {
        val previous=if(buttonsKnown) observedButtons else buttons
        observedButtons=buttons;buttonsKnown=true
        return ButtonObservation(previous and buttons.inv(),previous xor buttons)
    }
    @Synchronized fun buttonTransitions(buttons:Int):Int=buttonGate.transitions(buttons)
    @Synchronized fun clearKeyOwners() { keyOwners.clear() }
    @Synchronized fun removeDeviceKeys(deviceId:Int) { keyOwners.keys.removeAll { (it shr 32).toInt()==deviceId } }
    /** Main's deferred path drains an existing press without assigning its UP to a new session. */
    @Synchronized fun ownedKey(deviceId:Int,scanCode:Int,release:Boolean):KeyOwner? {
        val identity=keyIdentity(deviceId,scanCode)
        return if(release) keyOwners.remove(identity) else keyOwners[identity]
    }

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

    /**
     * The callback may only validate primitive/session snapshots and enqueue without invoking
     * overflow/lifecycle/UI callbacks. Lock order: this gate -> pointer gate / input queue.
     * The caller handles OVERFLOW after this monitor is released.
     */
    @Synchronized fun dispatchButtons(captureId:Long,buttons:Int,submit:(Route,Int)->Admission):Dispatch {
        if(pending!=0 || !buttonsKnown) return Dispatch.DEFERRED
        val current=route ?: return Dispatch.DEFERRED
        if(current.captureId!=captureId || current.buttons!=observedButtons) return Dispatch.DEFERRED
        val changed=observedButtons xor buttons
        if(changed==0 || changed and current.gameplay.buttons.inv()!=0) return Dispatch.DEFERRED
        val edges=buttonGate.transitions(buttons)
        val admission=submit(current,edges)
        observedButtons=buttons
        route=if(admission==Admission.ACCEPTED) current.copy(buttons=buttons) else null
        return admission.dispatch()
    }

    @Synchronized fun dispatchKey(captureId:Long,deviceId:Int,scanCode:Int,code:Int,
        down:Boolean,repeat:Boolean,submit:(Route,Int,Boolean)->Admission):Dispatch {
        if(pending!=0) return Dispatch.DEFERRED
        val identity=keyIdentity(deviceId,scanCode)
        val owner=keyOwners[identity]
        if(owner!=null) {
            if(owner.route.captureId!=captureId) return Dispatch.DEFERRED
            // A repeat/duplicate DOWN belongs to its original owner and never starts another action.
            if(down) return Dispatch.DIRECT
            keyOwners.remove(identity)
            // UP can retire ownership even after the gameplay route was invalidated. Session
            // admission uses the exact DOWN generation/epoch, so it cannot release a newer press.
            return submit(owner.route,owner.code,false).dispatch()
        }
        val current=route ?: return Dispatch.DEFERRED
        if(current.captureId!=captureId || !down || repeat || code !in current.gameplay.keys || keyOwners.size==capacity) return Dispatch.DEFERRED
        val admission=submit(current,code,true)
        // Retain the physical tail even if a concurrent pause rejected this DOWN or an overflow
        // admitted only a prefix. Its UP is ours to consume, never a passthrough key to another app.
        keyOwners[identity]=KeyOwner(code,current)
        if(admission!=Admission.ACCEPTED) route=null
        return admission.dispatch()
    }

    private fun Admission.dispatch()=when(this) {
        Admission.ACCEPTED -> Dispatch.DIRECT
        Admission.STALE -> Dispatch.STALE
        Admission.OVERFLOW -> Dispatch.OVERFLOW
    }

    companion object {
        private fun keyIdentity(deviceId:Int,scanCode:Int)=(deviceId.toLong() shl 32) or (scanCode.toLong() and 0xffffffffL)
        /** Only scalar movement is eligible; button identity is checked against the route. */
        fun isMotionOnlyFrame(kind:Int,timeNanos:Long,x:Float,y:Float,wheel:Float,horizontalWheel:Float,
            buttons:Int,keyScanCode:Int,keyDown:Boolean,keyRepeat:Boolean):Boolean =
            kind==0 && keyScanCode<0 && !keyDown && !keyRepeat && timeNanos>=0L &&
                x.isFinite() && y.isFinite() && (x!=0f || y!=0f) && wheel==0f && horizontalWheel==0f &&
                buttons and 31.inv()==0
    }
}
