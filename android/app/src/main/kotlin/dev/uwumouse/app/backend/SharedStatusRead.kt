package dev.uwumouse.app.backend

import java.util.concurrent.atomic.AtomicBoolean

/** The read's own identity prevents a new subscriber from blessing an older in-flight snapshot. */
internal data class StatusSnapshot<I,V>(val identityAtReadStart:I,val value:V) {
    fun isCurrent(requestedIdentity:I,currentIdentity:I):Boolean =
        identityAtReadStart==requestedIdentity && identityAtReadStart==currentIdentity
}

/** One outstanding read; recreated screens can share it without queueing another blocked RPC. */
internal class SharedStatusRead<K:Any,V>(
    private val execute:(Runnable)->Unit,
    private val read:(K)->V,
    private val deliver:(Runnable)->Unit,
) {
    class Subscription internal constructor(private val cancelAction:()->Unit) {
        fun cancel()=cancelAction()
    }
    private class Listener<V>(val callback:(Result<V>)->Unit) {
        val active=AtomicBoolean(true)
    }
    private class Flight<K,V>(val key:K) {
        val listeners=LinkedHashSet<Listener<V>>()
    }
    private val lock=Any()
    private var flight:Flight<K,V>?=null

    /** Null rejects another backend while the previous read is still outstanding. */
    fun request(key:K,callback:(Result<V>)->Unit):Subscription? {
        val listener=Listener(callback)
        val current:Flight<K,V>
        val start:Boolean
        synchronized(lock) {
            val running=flight
            if(running!=null && running.key!==key) return null
            start=running==null
            current=running ?: Flight<K,V>(key).also { flight=it }
            current.listeners.add(listener)
        }
        if(start) try {
            execute(Runnable { finish(current,runCatching { read(key) }) })
        } catch(failure:Exception) { finish(current,Result.failure(failure)) }
        return Subscription {
            listener.active.set(false)
            synchronized(lock) { current.listeners.remove(listener) }
        }
    }

    private fun finish(current:Flight<K,V>,outcome:Result<V>) {
        val listeners=synchronized(lock) {
            if(flight!==current) return
            flight=null
            current.listeners.toList().also { current.listeners.clear() }
        }
        for(listener in listeners) deliver(Runnable {
            if(listener.active.compareAndSet(true,false)) listener.callback(outcome)
        })
    }
}
