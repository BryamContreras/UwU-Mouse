package dev.mobimapper.mobimapper.session

import android.os.Handler
import android.os.SystemClock
/** Fixed storage. Profile merging is opt-in; the laboratory keeps its original merge rule. */
class InputQueue(private val worker: Handler, private val consume: (Int, Int, Float, Float, Long, Long) -> Unit, private val overflow: () -> Unit) {
    private val buffer = InputQueueBuffer()
    private var scheduled = false
    private val entry = InputQueueEntry()
    /** Read only from the synchronous worker callback; avoids another boxed callback argument. */
    val consumingSampleTimeNanos:Long get()=entry.lastSampleTimeNanos
    private val drain = object : Runnable {
        override fun run() {
            val started = SystemClock.elapsedRealtimeNanos()
            repeat(64) {
                synchronized(this@InputQueue) {
                    if (!buffer.pollInto(entry)) { scheduled = false; return }
                }
                consume(entry.type, entry.code, entry.x.toFloat(), entry.y.toFloat(), entry.enqueuedAtNanos, entry.generation)
                if (SystemClock.elapsedRealtimeNanos() - started >= 4_000_000L) {
                    scheduleMore()
                    return
                }
            }
            scheduleMore()
        }
    }

    /** Yield without sleeping; at most one drain remains scheduled, even across clear/offer. */
    private fun scheduleMore() {
        val failed = synchronized(this) {
            if (buffer.size == 0) { scheduled = false; false }
            else if (worker.post(drain)) false
            else { scheduled = false; buffer.clear(); true }
        }
        if (failed) overflow()
    }
    fun offer(type: Int, code: Int = 0, x: Float = 0f, y: Float = 0f, generation: Long = 0L,
              sampleTimeNanos: Long = InputQueueEntry.NO_SAMPLE_TIME): Boolean {
        val accepted = synchronized(this) {
            if (!buffer.offer(type, code, x, y, SystemClock.elapsedRealtimeNanos(), generation, sampleTimeNanos))
                return@synchronized false
            if (!scheduled) {
                scheduled = true
                if (!worker.post(drain)) { scheduled = false; buffer.clear(); return@synchronized false }
            }
            true
        }
        // Do not enter lifecycle locks while holding the queue lock.
        if (!accepted) overflow()
        return accepted
    }
    /** Limits are raw relative deltas prepared from camera radius / absolute sensitivity. */
    @Synchronized fun configureCameraMerge(maxDx: Double, maxDy: Double, preserveSamples:Boolean=false) =
        buffer.configureCameraMerge(maxDx, maxDy, preserveSamples)
    @Synchronized fun disableCameraMerge() = buffer.disableCameraMerge()
    @Synchronized fun clear() = buffer.clear()
    @Synchronized fun resetMetrics() = buffer.resetMetrics()
    @Synchronized fun diagnostics(): InputQueueMetrics = buffer.diagnostics()
    @Synchronized fun metrics(): Pair<Int, Long> = buffer.maxDepth to buffer.mergedMoves
}
