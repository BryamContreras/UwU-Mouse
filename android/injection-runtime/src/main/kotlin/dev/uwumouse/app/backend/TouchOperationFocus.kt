package dev.uwumouse.app.backend

/**
 * A focus observation belongs to one already-ready MOVE/contact-edge operation.
 * A fresh synchronous result admits its own stage even when the query was slow,
 * as on the ordinary injection path. Only an observation young from query START
 * can admit the following stage. No prefetch can add a third query to two frames.
 */
class TouchOperationFocus(
    private val clockNanos:()->Long,
    private val query:()->Boolean,
    private val lease:CameraFocusLease=CameraFocusLease(),
) {
    private var lastObservedNanos=Long.MIN_VALUE
    private var denied=false
    var queries=0L;private set
    var reused=0L;private set

    fun allowsStage(admission:()->Boolean={true}):Boolean {
        if(denied) return false
        var now=observeClock() ?: return false
        when(lease.observationAt(now)) {
            false -> { denied=true;return false }
            true -> {
                if(!admission()) { denied=true;return false }
                now=observeClock() ?: return false
                when(lease.observationAt(now)) {
                    true -> { reused++;return true }
                    false -> { denied=true;return false }
                    null -> Unit // Metadata work may have outlived the cached observation.
                }
            }
            null -> Unit
        }
        queries++
        val focused=try { query() } catch(_:Exception) { false }
        val finished=observeClock() ?: return false
        lease.observe(now,focused)
        if(!focused || lease.observationAt(finished)==false) {
            denied=true;return false
        }
        if(!admission()) { denied=true;return false }
        if(observeClock()==null) return false
        // A slow positive result is used once, without moving its provenance to 'finished'.
        return true
    }

    private fun observeClock():Long? {
        val now=clockNanos()
        if(now<0L || now<lastObservedNanos) { denied=true;return null }
        lastObservedNanos=now
        return now
    }
}
