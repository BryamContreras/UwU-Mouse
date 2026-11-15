package dev.uwumouse.app.session

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One reusable resource slot with permanent, monitor-free cancellation for its owner's lifetime. */
class CancellationOwner<T:Any>(private val close:(T)->Unit) {
    private class Lease<T:Any>(val value:T,val claimed:Boolean=false)
    private val cancelledFlag=AtomicBoolean()
    private val resource=AtomicReference<Lease<T>?>()
    val cancelled:Boolean get()=cancelledFlag.get()

    /** A losing registration closes its own resource, even if cancellation preceded publication. */
    fun register(value:T):Boolean {
        val lease=Lease(value)
        if(cancelledFlag.get() || !resource.compareAndSet(null,lease)) {
            closeQuietly(value);return false
        }
        if(cancelledFlag.get()) {
            if(resource.compareAndSet(lease,null)) closeQuietly(value)
            return false
        }
        return true
    }

    /** Successful CAS reserves the RPC as in flight relative to cancellation. No monitor is held. */
    fun claim(value:T):Boolean {
        val current=resource.get() ?: return false
        if(cancelledFlag.get() || current.value!==value || current.claimed) return false
        return resource.compareAndSet(current,Lease(value,claimed=true)) &&
            !cancelledFlag.get() && resource.get()?.value===value
    }

    /** A stale finally cannot remove a later call's descriptor or undo permanent cancellation. */
    fun clear(value:T) {
        while(true) {
            val current=resource.get() ?: return
            if(current.value!==value || resource.compareAndSet(current,null)) return
        }
    }

    fun cancel() {
        cancelledFlag.set(true)
        resource.getAndSet(null)?.value?.let(::closeQuietly)
    }

    private fun closeQuietly(value:T) { try { close(value) } catch(_:Exception) { } }
}
