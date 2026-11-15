package dev.uwumouse.app.session

/**
 * Bounded FIFO for native callbacks that require main-thread ownership. The first entry
 * requests an immediate drain; later entries share it without posting another Runnable.
 * A callback stays pending in NativeMotionDispatchGate until its work has been consumed.
 * Ordering is between native callbacks. A drain can consume an entry offered after a UI
 * message was posted; the caller must budget/yield and recheck current UI/session state
 * inside every callback, rather than deciding its destination when it is queued.
 */
class NativeCallbackQueue<T:Any>(private val capacity:Int=512) {
    enum class Offer { SCHEDULE, QUEUED, FULL }
    data class Metrics(val depth:Int,val maxDepth:Int)

    init { require(capacity>0) }
    private val entries=arrayOfNulls<Any>(capacity)
    private var head=0
    private var size=0
    private var scheduled=false
    private var maxDepth=0

    @Synchronized fun offer(value:T):Offer {
        if(size==capacity) return Offer.FULL
        entries[(head+size)%capacity]=value
        size++
        maxDepth=maxOf(maxDepth,size)
        if(scheduled) return Offer.QUEUED
        scheduled=true
        return Offer.SCHEDULE
    }

    /** Only the main drain consumes work; removing an entry does not release scheduling. */
    @Suppress("UNCHECKED_CAST")
    @Synchronized fun poll():T? {
        if(size==0) return null
        val value=entries[head] as T
        entries[head]=null
        head=(head+1)%capacity
        size--
        return value
    }

    /** Called after the drain budget or an empty poll, including work offered during delivery. */
    @Synchronized fun finishDrain():Boolean {
        if(size!=0) return true
        scheduled=false
        return false
    }

    /** Failed Handler submission abandons ownership, without executing game input. */
    @Synchronized fun abandon():List<T> {
        val abandoned=ArrayList<T>(size)
        while(size!=0) abandoned.add(requireNotNull(poll()))
        scheduled=false
        return abandoned
    }

    @Synchronized fun metrics()=Metrics(size,maxDepth)
}
