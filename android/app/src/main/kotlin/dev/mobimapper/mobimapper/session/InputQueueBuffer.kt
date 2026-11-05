package dev.mobimapper.mobimapper.session

import kotlin.math.abs

/** Cold diagnostic snapshot; the queue keeps only primitive arrays on its input path. */
data class InputQueueMetrics(
    val currentDepth: Int,
    val maxDepth: Int,
    val mergedMoves: Long,
    val relativeMergedMoves: Long,
    val cursorMergedMoves: Long,
)

/** Reused by the single consumer, never retained by the producer. */
internal class InputQueueEntry {
    var type = 0
    var code = 0
    var x = 0.0
    var y = 0.0
    var enqueuedAtNanos = 0L
    var generation = 0L
    var firstSampleTimeNanos = NO_SAMPLE_TIME
    var lastSampleTimeNanos = NO_SAMPLE_TIME
    var sampleCount = 0

    companion object { const val NO_SAMPLE_TIME = Long.MIN_VALUE }
}

/**
 * Fixed ring storage without Android dependencies. Its owner serializes access.
 *
 * The default keeps the laboratory's original Float/collinear merge exactly. A prepared game
 * profile can opt into bounded, same-direction merging. Source time and queue admission time
 * are deliberately separate: MotionEvent history can arrive in one callback much later.
 */
internal class InputQueueBuffer {
    private val types = IntArray(CAPACITY)
    private val codes = IntArray(CAPACITY)
    private val xs = DoubleArray(CAPACITY)
    private val ys = DoubleArray(CAPACITY)
    private val times = LongArray(CAPACITY)
    private val generations = LongArray(CAPACITY)
    private val firstSamples = LongArray(CAPACITY)
    private val lastSamples = LongArray(CAPACITY)
    private val sampleCounts = IntArray(CAPACITY)
    private var head = 0
    var size = 0; private set
    var maxDepth = 0; private set
    var mergedMoves = 0L; private set
    private var relativeMergedMoves = 0L
    private var cursorMergedMoves = 0L
    private var cameraMergeEnabled = false
    private var preserveCameraSamples = false
    private var cameraMaxDx = 0.0
    private var cameraMaxDy = 0.0

    fun configureCameraMerge(maxDx: Double, maxDy: Double, preserveSamples: Boolean = false) {
        check(size == 0) { "Configure camera merging before enqueueing the session" }
        require(maxDx.isFinite() && maxDy.isFinite() && maxDx > 0.0 && maxDy > 0.0)
        cameraMaxDx = maxDx
        cameraMaxDy = maxDy
        cameraMergeEnabled = true
        preserveCameraSamples = preserveSamples
    }

    fun disableCameraMerge() {
        check(size == 0) { "Disable camera merging after clearing the previous session" }
        cameraMergeEnabled = false
        preserveCameraSamples = false
    }

    fun offer(type: Int, code: Int, x: Float, y: Float, enqueuedAtNanos: Long,
              generation: Long, sampleTimeNanos: Long = InputQueueEntry.NO_SAMPLE_TIME): Boolean {
        if (!x.isFinite() || !y.isFinite()) return false
        if (size > 0) {
            val last = (head + size - 1) % CAPACITY
            if (types[last] == type && generations[last] == generation) {
                if (type == 6) {
                    xs[last] = x.toDouble(); ys[last] = y.toDouble()
                    mergedMoves++; cursorMergedMoves++
                    return true // Latest absolute position, without crossing a button edge.
                }
                if (type == 3 && mergeRelative(last, x, y, sampleTimeNanos)) {
                    mergedMoves++; relativeMergedMoves++
                    return true // Preserve admission time of the oldest accepted sample.
                }
            }
        }
        if (size == CAPACITY) return false
        val index = (head + size) % CAPACITY
        types[index] = type; codes[index] = code
        xs[index] = x.toDouble(); ys[index] = y.toDouble()
        times[index] = enqueuedAtNanos; generations[index] = generation
        firstSamples[index] = sampleTimeNanos; lastSamples[index] = sampleTimeNanos
        sampleCounts[index] = 1
        size++; maxDepth = maxOf(maxDepth, size)
        return true
    }

    private fun mergeRelative(last: Int, x: Float, y: Float, sampleTimeNanos: Long): Boolean {
        if (preserveCameraSamples) return false // Precision filtering needs each original displacement/time.
        if (!cameraMergeEnabled) {
            // Preserve the old arithmetic and tolerance, including its Float rounding.
            val oldX = xs[last].toFloat(); val oldY = ys[last].toFloat()
            val sumX = oldX + x; val sumY = oldY + y
            val cross = oldX * y - oldY * x
            if (oldX * x < 0f || oldY * y < 0f || !cross.isFinite() || abs(cross) > .0001f ||
                !sumX.isFinite() || !sumY.isFinite()) return false
            xs[last] = sumX.toDouble(); ys[last] = sumY.toDouble()
            return true
        }
        val firstTime = firstSamples[last]
        if (firstTime < 0L || sampleTimeNanos < lastSamples[last] || sampleTimeNanos < 0L ||
            sampleTimeNanos - firstTime > CAMERA_MERGE_WINDOW_NANOS ||
            sampleCounts[last] >= CAMERA_MERGE_MAX_SAMPLES) return false
        val oldX = xs[last]; val oldY = ys[last]
        if (oldX * x < 0.0 || oldY * y < 0.0) return false // No reversals on either axis.
        val sumX = oldX + x.toDouble(); val sumY = oldY + y.toDouble()
        if (!fitsDelta(sumX, cameraMaxDx) || !fitsDelta(sumY, cameraMaxDy)) return false
        xs[last] = sumX; ys[last] = sumY
        lastSamples[last] = sampleTimeNanos; sampleCounts[last]++
        return true
    }

    private fun fitsDelta(value: Double, limit: Double): Boolean = value.isFinite() &&
        abs(value) <= limit && value.toFloat().isFinite() && abs(value.toFloat().toDouble()) <= limit

    fun pollInto(entry: InputQueueEntry): Boolean {
        if (size == 0) return false
        entry.type = types[head]; entry.code = codes[head]
        entry.x = xs[head]; entry.y = ys[head]
        entry.enqueuedAtNanos = times[head]; entry.generation = generations[head]
        entry.firstSampleTimeNanos = firstSamples[head]; entry.lastSampleTimeNanos = lastSamples[head]
        entry.sampleCount = sampleCounts[head]
        head = (head + 1) % CAPACITY; size--
        return true
    }

    fun clear() { size = 0; head = 0 }

    /** Does not discard pending events; maxDepth starts at the current depth. */
    fun resetMetrics() {
        maxDepth = size; mergedMoves = 0L; relativeMergedMoves = 0L; cursorMergedMoves = 0L
    }

    fun diagnostics() = InputQueueMetrics(size, maxDepth, mergedMoves, relativeMergedMoves, cursorMergedMoves)

    companion object {
        const val CAPACITY = 256
        const val CAMERA_MERGE_WINDOW_NANOS = 8_000_000L
        const val CAMERA_MERGE_MAX_SAMPLES = 8
    }
}
