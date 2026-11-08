package dev.mobimapper.mobimapper.session

/**
 * Worker-owned camera delivery cadence; no Android dependency or idle ticker.
 * The owner invalidates/removes queued wakeups when configuring or discarding a session.
 * Flush preserves an outstanding wakeup so an input barrier cannot create duplicate callbacks.
 */
class CameraMotionPacer(
    private val clockNanos:()->Long,
    private val emit:(Float,Float,Long)->Boolean,
    private val schedule:(Long)->Unit,
) {
    @Volatile private var rateHz=0
    @Volatile private var samples=0L
    @Volatile private var merged=0L
    @Volatile private var emissions=0L
    @Volatile private var rejected=0L
    @Volatile private var failures=0L
    @Volatile private var bufferedSamples=0L
    @Volatile private var wakeupScheduled=false
    @Volatile private var nextDeadlineNanos=0L
    @Volatile private var failed=false
    private var bufferedX=0.0
    private var bufferedY=0.0
    private var oldestEnqueuedNanos=0L
    private var phaseOriginNanos=0L
    private var nextTick=0L
    private var hasPhase=false
    private var inputBatch=false
    private var batchFirstSampleNanos=Long.MIN_VALUE
    private var batchLastSampleNanos=Long.MIN_VALUE

    val pending:Boolean get()=bufferedSamples>0L

    fun configure(hz:Int) {
        require(hz in ALLOWED_RATES) { "Unsupported camera update rate" }
        discard()
        rateHz=hz
        samples=0L; merged=0L; emissions=0L; rejected=0L; failures=0L; failed=false
    }

    fun offer(dx:Float,dy:Float,enqueuedAtNanos:Long,sampleTimeNanos:Long=Long.MIN_VALUE):Boolean {
        if(!dx.isFinite() || !dy.isFinite() || failed) { rejected++; return false }
        samples++
        if(rateHz==0) return if(inputBatch) offerInputBatch(dx,dy,enqueuedAtNanos,sampleTimeNanos)
            else deliver(dx,dy,enqueuedAtNanos)
        if(dx==0f && dy==0f) return true
        val now=clockNanos()
        if(!hasPhase) {
            hasPhase=true; phaseOriginNanos=now; nextTick=1L
            nextDeadlineNanos=deadline(nextTick)
            return deliver(dx,dy,enqueuedAtNanos)
        }
        if(pending) {
            merged++
            oldestEnqueuedNanos=minOf(oldestEnqueuedNanos,enqueuedAtNanos)
        } else oldestEnqueuedNanos=enqueuedAtNanos
        bufferedX+=dx.toDouble(); bufferedY+=dy.toDouble(); bufferedSamples++
        if(now>=nextDeadlineNanos) {
            advancePast(now)
            return emitPending()
        }
        return schedulePending(now)
    }

    /**
     * Opt-in synchronous queue drain for continuous camera only. Filtering still runs on
     * every raw sample before offer; delivery combines at most eight inputs / 8 ms of
     * source time, with no timer or imposed Hz. An isolated sample flushes at drain end.
     */
    fun beginInputBatch() {
        check(!inputBatch) { "Input batches cannot be nested" }
        inputBatch=rateHz==0 && !failed
    }

    fun endInputBatch():Boolean {
        if(!inputBatch) return !failed
        inputBatch=false
        return if(pending) emitPending() else true
    }

    private fun offerInputBatch(dx:Float,dy:Float,enqueuedAtNanos:Long,sampleTimeNanos:Long):Boolean {
        if(pending && (sampleTimeNanos<0L || batchFirstSampleNanos<0L ||
            sampleTimeNanos<batchLastSampleNanos || sampleTimeNanos-batchFirstSampleNanos>INPUT_BATCH_WINDOW_NANOS)) {
            if(!emitPending()) return false
        }
        if(pending) {
            merged++
            oldestEnqueuedNanos=minOf(oldestEnqueuedNanos,enqueuedAtNanos)
        } else {
            oldestEnqueuedNanos=enqueuedAtNanos
            batchFirstSampleNanos=sampleTimeNanos
        }
        batchLastSampleNanos=sampleTimeNanos
        bufferedX+=dx.toDouble(); bufferedY+=dy.toDouble(); bufferedSamples++
        return bufferedSamples<INPUT_BATCH_MAX_SAMPLES || emitPending()
    }

    /** Consumes one owner wakeup. Late delivery emits one aggregate and skips missed deadlines. */
    fun drainDue():Boolean {
        wakeupScheduled=false
        if(failed) return false
        if(!pending) return true
        val now=clockNanos()
        if(now<nextDeadlineNanos) return schedulePending(now)
        advancePast(now)
        return emitPending()
    }

    /** Must run before a key, mouse-button or mode transition enters the mapping engine. */
    fun flush():Boolean {
        if(failed) return false
        if(!pending) return true
        advancePast(clockNanos())
        return emitPending()
    }

    /** Session cancellation discards movement; it must never replay after pause or recovery. */
    fun discard() {
        clearBuffer()
        wakeupScheduled=false
        hasPhase=false; phaseOriginNanos=0L; nextTick=0L; nextDeadlineNanos=0L
        inputBatch=false
    }

    /** Cold, approximate cross-thread counters. No map is created on offer/drain/flush. */
    fun diagnostics():Map<String,Any> = mapOf(
        "rateHz" to rateHz,"samples" to samples,"merged" to merged,"emissions" to emissions,
        "pending" to pending,"pendingSamples" to bufferedSamples,"wakeupScheduled" to wakeupScheduled,
        "nextDeadlineNanos" to nextDeadlineNanos,"failed" to failed,"rejected" to rejected,
        "failures" to failures,
        "inputBatch" to inputBatch,
    )

    private fun clearBuffer() {
        bufferedX=0.0; bufferedY=0.0; oldestEnqueuedNanos=0L; bufferedSamples=0L
        batchFirstSampleNanos=Long.MIN_VALUE; batchLastSampleNanos=Long.MIN_VALUE
    }

    private fun emitPending():Boolean {
        val x=bufferedX.toFloat(); val y=bufferedY.toFloat(); val enqueued=oldestEnqueuedNanos
        clearBuffer()
        if(!x.isFinite() || !y.isFinite()) return fail()
        if(x==0f && y==0f) return true
        return deliver(x,y,enqueued)
    }

    private fun deliver(dx:Float,dy:Float,enqueuedAtNanos:Long):Boolean {
        val accepted=try { emit(dx,dy,enqueuedAtNanos) } catch(_:Exception) { false }
        if(!accepted) return fail()
        emissions++
        return true
    }

    private fun fail():Boolean {
        failed=true; failures++
        discard()
        return false
    }

    private fun schedulePending(now:Long):Boolean {
        if(!pending || wakeupScheduled) return true
        wakeupScheduled=true
        return try { schedule((nextDeadlineNanos-now).coerceAtLeast(0L)); true }
        catch(_:Exception) { fail() }
    }

    private fun advancePast(now:Long) {
        if(!hasPhase || now<nextDeadlineNanos) return
        val elapsed=(now-phaseOriginNanos).coerceAtLeast(0L)
        // Exact rational phase: avoid truncating 120 Hz to a permanent 8 ms period.
        val wholeSeconds=elapsed/NANOS_PER_SECOND
        val fraction=elapsed%NANOS_PER_SECOND
        nextTick=wholeSeconds*rateHz+fraction*rateHz/NANOS_PER_SECOND+1L
        nextDeadlineNanos=deadline(nextTick)
    }

    private fun deadline(tick:Long):Long {
        val wholeSeconds=tick/rateHz
        val remainder=tick%rateHz
        val offset=wholeSeconds*NANOS_PER_SECOND+
            (remainder*NANOS_PER_SECOND+rateHz-1L)/rateHz
        return if(phaseOriginNanos>Long.MAX_VALUE-offset) Long.MAX_VALUE else phaseOriginNanos+offset
    }

    companion object {
        private const val NANOS_PER_SECOND=1_000_000_000L
        private const val INPUT_BATCH_MAX_SAMPLES=8L
        private const val INPUT_BATCH_WINDOW_NANOS=8_000_000L
        private val ALLOWED_RATES=setOf(0,60,90,120,125,144,240,250,500)
    }
}
