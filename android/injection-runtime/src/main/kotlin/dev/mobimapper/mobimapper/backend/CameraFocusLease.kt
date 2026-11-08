package dev.mobimapper.mobimapper.backend

/**
 * Short-lived observations for one semantic camera replacement only. The owner still checks
 * capture, metadata, session and worker identity before every send. No query is performed here.
 * A negative observation or a clock regression cannot be renewed inside the same operation.
 */
class CameraFocusLease(
    private val maximumAgeNanos:Long=8_000_000L,
    private val prefetchAgeNanos:Long=4_000_000L,
) {
    init { require(maximumAgeNanos>0L && prefetchAgeNanos in 1L..maximumAgeNanos) }
    private var observed=false
    private var queryStarted=0L
    private var denied=false

    fun observe(queryStartedNanos:Long,focused:Boolean) {
        require(queryStartedNanos>=0L)
        if(observed && queryStartedNanos<queryStarted) denied=true
        queryStarted=maxOf(queryStarted,queryStartedNanos)
        observed=true
        if(!focused) denied=true
    }

    /** Null requests a fresh query; false ends the operation rather than guessing focus. */
    fun observationAt(nowNanos:Long):Boolean? {
        require(nowNanos>=0L)
        if(denied) return false
        if(!observed) return null
        if(nowNanos<queryStarted) { denied=true;return false }
        return if(nowNanos-queryStarted<=maximumAgeNanos) true else null
    }

    /** Refresh only an ageing positive lease; never restart a denied operation. */
    fun shouldPrefetch(nowNanos:Long):Boolean {
        val current=observationAt(nowNanos)
        if(current==false) return false
        return current==null || nowNanos-queryStarted>=prefetchAgeNanos
    }
}
