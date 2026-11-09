package dev.uwumouse.app.session

/**
 * Quiet time is measured from real local receipt and completed delivery, never from a
 * buffered device/source timestamp. Source motion only estimates a conservative quiet interval;
 * it never starts or advances the local quiet deadline. The owner separately checks queues,
 * buttons and generation.
 * This policy schedules nothing and cannot grant capture, injection or a camera renewal.
 */
class CameraIdlePolicy(private val idleNanos:Long=20_000_000L) {
    init { require(idleNanos>0L) { "Camera idle interval must be positive" } }
    private var lastReceived=Long.MIN_VALUE
    private var lastDelivered=Long.MIN_VALUE
    private var deliveryPeriodNanos=0L
    private var lastSource=Long.MIN_VALUE
    private var currentSourceMinimum=Long.MAX_VALUE
    private var previousSourceMinimum=Long.MAX_VALUE
    private var sourceIntervals=0

    /**
     * Motion-event cadence is not certified hardware polling: low-DPI movement and Bluetooth
     * can emit irregularly. Two 16-interval blocks retain the minimum over 16–31 accepted
     * intervals (or the available startup prefix), with O(1) work and no per-sample allocation.
     * A long genuine pause cannot replace that estimate with a new, very large interval.
     */
    @Synchronized fun sourceMotion(timeNanos:Long) {
        if(timeNanos<0L || timeNanos<=lastSource) return
        val previous=lastSource
        lastSource=timeNanos
        if(previous==Long.MIN_VALUE) return
        val interval=timeNanos-previous
        if(interval>MAX_SOURCE_INTERVAL_NANOS) return
        currentSourceMinimum=minOf(currentSourceMinimum,interval)
        if(++sourceIntervals==SOURCE_BLOCK_INTERVALS) {
            previousSourceMinimum=currentSourceMinimum
            currentSourceMinimum=Long.MAX_VALUE
            sourceIntervals=0
        }
    }

    /** Hz limits delivery from capture, not hardware polling; zero means unrestricted delivery. */
    @Synchronized fun configureRate(hz:Int) {
        require(hz in 0..1000) { "Camera capture rate must be between 0 and 1000 Hz" }
        deliveryPeriodNanos=if(hz==0) 0L else (NANOS_PER_SECOND+hz-1L)/hz
    }

    /** At least 20 ms by default, three observed source periods and two capture-delivery periods. */
    val quietThresholdNanos:Long
        @Synchronized get() {
            val observed=minOf(currentSourceMinimum,previousSourceMinimum)
            val sourceFloor=if(observed==Long.MAX_VALUE) 0L else saturatingTimes(observed,3L)
            return maxOf(idleNanos,sourceFloor,saturatingTimes(deliveryPeriodNanos,2L))
        }

    /** May be called by the capture receipt thread while the worker awaits an ACK. */
    @Synchronized fun motionReceived(nowNanos:Long) {
        require(nowNanos>=0L) { "Receipt requires a local monotonic timestamp" }
        lastReceived=maxOf(lastReceived,nowNanos)
    }

    /** Called only after the worker has completed the corresponding movement delivery. */
    @Synchronized fun motionDelivered(nowNanos:Long) {
        require(nowNanos>=0L) { "Delivery requires a local monotonic timestamp" }
        lastDelivered=maxOf(lastDelivered,nowNanos)
    }

    /** A session reset forgets observations but retains its explicitly configured delivery rate. */
    @Synchronized fun reset() {
        lastReceived=Long.MIN_VALUE
        lastDelivered=Long.MIN_VALUE
        lastSource=Long.MIN_VALUE
        currentSourceMinimum=Long.MAX_VALUE
        previousSourceMinimum=Long.MAX_VALUE
        sourceIntervals=0
    }

    /** Null means no motion has been observed since reset. Backward time never proves quiet. */
    @Synchronized fun remainingIdleNanos(nowNanos:Long):Long? {
        require(nowNanos>=0L) { "Idle checks require a local monotonic timestamp" }
        val latest=maxOf(lastReceived,lastDelivered)
        if(latest==Long.MIN_VALUE) return null
        val threshold=quietThresholdNanos
        if(nowNanos<latest) return threshold
        val quietNanos=nowNanos-latest
        return if(quietNanos>=threshold) 0L else threshold-quietNanos
    }

    private fun saturatingTimes(value:Long,factor:Long):Long =
        if(value>Long.MAX_VALUE/factor) Long.MAX_VALUE else value*factor

    private companion object {
        const val NANOS_PER_SECOND=1_000_000_000L
        const val MAX_SOURCE_INTERVAL_NANOS=100_000_000L
        const val SOURCE_BLOCK_INTERVALS=16
    }
}
