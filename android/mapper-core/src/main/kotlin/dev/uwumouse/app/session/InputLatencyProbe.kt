package dev.uwumouse.app.session

/**
 * Explicitly enabled, bounded diagnostic recorder. Timestamps use CLOCK_MONOTONIC
 * (System.nanoTime / Android event uptime), never wall time or elapsedRealtime.
 * No event is logged or written to disk, and disabled calls only read a flag.
 */
object InputLatencyProbe {
    private val recorder = InputLatencyRecorder()
    val enabled: Boolean get() = recorder.enabled

    fun start(durationMs: Int = 30_000, processId: Int): Map<String, Any?> =
        recorder.start(durationMs, processId)
    fun stop(): Map<String, Any?> = recorder.stop()
    fun snapshot(offset: Int = 0, limit: Int = 512): Map<String, Any?> = recorder.snapshot(offset, limit)

    fun recordReceipt(kind: String, sourceTimeNanos: Long, receivedTimeNanos: Long,
                      source: Int, action: Int, deviceId: Int, code: Int = -1,
                      historyIndex: Int = -1, active: Boolean = false,
                      targetConfirmed: Boolean = false, cursorMode: Boolean = false): Long =
        recorder.recordReceipt(kind, sourceTimeNanos, receivedTimeNanos, source, action,
            deviceId, code, historyIndex, active, targetConfirmed, cursorMode)

    /** Stage names must identify whether the source is physical input or synthetic composition. */
    fun recordStage(stage: String, sourceTimeNanos: Long, action: Int = -1, eventTimeMs: Long = -1) {
        if (!enabled) return
        recorder.recordStage(stage, sourceTimeNanos, action, eventTimeMs)
    }

    /** Records only the latest event actually used by a cursor draw, never initialization. */
    fun recordCursorDraw(receiptToken: Long) {
        if (!enabled || receiptToken == 0L) return
        recorder.recordCursorDraw(receiptToken)
    }
}

/** Pure Kotlin storage so expiry, clock handling and stale draw correlation are testable. */
internal class InputLatencyRecorder(private val clockNanos: () -> Long = System::nanoTime) {
    @Volatile var enabled = false
        private set
    private val capacity = 4096
    private val sequence = LongArray(capacity)
    private val stages = arrayOfNulls<String>(capacity)
    private val kinds = arrayOfNulls<String>(capacity)
    private val sourceTimes = LongArray(capacity)
    private val observedTimes = LongArray(capacity)
    private val syntheticEventTimes = LongArray(capacity)
    private val sources = IntArray(capacity)
    private val actions = IntArray(capacity)
    private val devices = IntArray(capacity)
    private val codes = IntArray(capacity)
    private val histories = IntArray(capacity)
    private val flags = IntArray(capacity)
    private var nextSequence = 0L
    private var totalRecorded = 0L
    private var stored = 0
    private var processId = -1
    private var runNumber = 0L
    private var runId: String? = null
    private var started = 0L
    private var deadline = 0L
    private var stopped = 0L
    private var durationMs = 0
    private var stopReason = "not_started"

    @Synchronized fun start(durationMs: Int, processId: Int): Map<String, Any?> {
        require(durationMs in 1..60_000) { "durationMs must be between 1 and 60000" }
        require(processId > 0) { "A real process ID is required" }
        enabled = false
        sequence.fill(0L)
        stages.fill(null)
        kinds.fill(null)
        totalRecorded = 0L
        stored = 0
        this.processId = processId
        this.durationMs = durationMs
        started = clockNanos()
        deadline = started + durationMs * 1_000_000L
        stopped = 0L
        runId = "$processId-$started-${++runNumber}"
        stopReason = "recording"
        enabled = true
        return metadata()
    }

    fun recordReceipt(kind: String, sourceTimeNanos: Long, receivedTimeNanos: Long,
                      source: Int, action: Int, deviceId: Int, code: Int = -1,
                      historyIndex: Int = -1, active: Boolean = false,
                      targetConfirmed: Boolean = false, cursorMode: Boolean = false): Long {
        if (!enabled) return 0L
        synchronized(this) {
            if (!admit(receivedTimeNanos)) return 0L
            val stage = when {
                kind == "native_motion" -> "native_motion_current_receipt"
                kind == "native_key" -> "native_key_receipt"
                kind == "key" -> "accessibility_key_receipt"
                historyIndex >= 0 -> "accessibility_motion_history_receipt"
                else -> "accessibility_motion_current_receipt"
            }
            return append(stage, kind, sourceTimeNanos, receivedTimeNanos, -1L, source,
                action, deviceId, code, historyIndex,
                (if (active) 1 else 0) or (if (targetConfirmed) 2 else 0) or (if (cursorMode) 4 else 0))
        }
    }

    fun recordStage(stage: String, sourceTimeNanos: Long, action: Int = -1, eventTimeMs: Long = -1) {
        if (!enabled) return
        val now = clockNanos()
        synchronized(this) {
            if (!admit(now)) return
            append(stage, "stage", sourceTimeNanos, now, eventTimeMs, -1, action, -1, -1, -1, 0)
        }
    }

    fun recordCursorDraw(receiptToken: Long) {
        if (!enabled || receiptToken <= 0L) return
        val now = clockNanos()
        synchronized(this) {
            if (!admit(now)) return
            val index = ((receiptToken - 1L) % capacity).toInt()
            if (sequence[index] != receiptToken || (stages[index] != "accessibility_motion_current_receipt" && stages[index] != "native_motion_current_receipt")) return
            append("cursor_draw_cpu_end", "motion", sourceTimes[index], now, -1L,
                sources[index], actions[index], devices[index], codes[index], -1, flags[index])
        }
    }

    private fun admit(now: Long): Boolean {
        if (!enabled) return false
        if (now >= deadline) {
            enabled = false
            stopped = deadline
            stopReason = "window_expired"
            return false
        }
        // A callback timestamp acquired before a concurrent new run cannot enter that run.
        return now >= started
    }

    private fun append(stage: String, kind: String, sourceTime: Long, observedTime: Long,
                       eventTimeMs: Long, source: Int, action: Int, deviceId: Int,
                       code: Int, historyIndex: Int, stateFlags: Int): Long {
        val token = ++nextSequence
        val index = ((token - 1L) % capacity).toInt()
        sequence[index] = token
        stages[index] = stage
        kinds[index] = kind
        sourceTimes[index] = sourceTime
        observedTimes[index] = observedTime
        syntheticEventTimes[index] = eventTimeMs
        sources[index] = source
        actions[index] = action
        devices[index] = deviceId
        codes[index] = code
        histories[index] = historyIndex
        flags[index] = stateFlags
        totalRecorded++
        stored = minOf(stored + 1, capacity)
        return token
    }

    fun stop(): Map<String, Any?> {
        synchronized(this) {
            if (enabled) {
                val now = clockNanos()
                if (now >= deadline) {
                    stopped = deadline
                    stopReason = "window_expired"
                } else {
                    stopped = now
                    stopReason = "requested"
                }
                enabled = false
            }
        }
        return snapshot()
    }

    private fun metadata(): Map<String, Any?> = linkedMapOf(
        "runId" to runId, "pid" to processId, "enabled" to enabled,
        "durationMs" to durationMs, "startedTimeNanos" to started,
        "deadlineTimeNanos" to deadline, "stoppedTimeNanos" to stopped,
        "stopReason" to stopReason, "capacity" to capacity,
        "totalRecorded" to totalRecorded, "storedCount" to stored,
        "overwrittenCount" to (totalRecorded - stored),
        "clock" to "System.nanoTime CLOCK_MONOTONIC; Android event uptime; nanoseconds",
        "receiptScope" to "Original source timestamp to the receipt named by stage: native stages start at the kernel timestamp; legacy accessibility stages include Android delivery. Excludes peripheral pre-kernel latency.",
        "nativeMotionCodeScope" to "native_motion records retain the mouse button bitmask in code; action is the mapper motion sample, not a reconstructed Android button edge.",
        "drawScope" to "cursor_draw_cpu_end is completion of CPU onDraw commands, not GPU completion, display presentation or photon latency",
        "historicalScope" to "Historical samples retain their own source timestamp and share callback receipt; reported separately from current samples",
        "stageScope" to "Stage names identify source origin: *_syntheticComposition starts at synthetic touch composition, not physical mouse input; mapper_mouse_worker_latest_sample uses the latest original input sample",
        "instrumentation" to "Optional in-memory recorder; no per-movement logs or disk writes; recording and snapshots add overhead",
        "flags" to mapOf("active" to 1, "targetConfirmed" to 2, "cursorMode" to 4),
    )

    fun snapshot(offset: Int = 0, limit: Int = 512): Map<String, Any?> {
        require(offset >= 0) { "offset must be nonnegative" }
        require(limit in 0..512) { "limit must be between 0 and 512" }
        val result: MutableMap<String, Any?>
        val rows: List<Map<String, Any?>>
        synchronized(this) {
            if (enabled) admit(clockNanos())
            result = metadata().toMutableMap()
            val first = nextSequence - stored + 1L
            rows = (0 until stored).map { offset ->
                val index = ((first + offset - 1L) % capacity).toInt()
                val valid = sourceTimes[index] > 0L && observedTimes[index] >= sourceTimes[index]
                linkedMapOf<String, Any?>(
                    "sequence" to sequence[index], "stage" to stages[index], "kind" to kinds[index],
                    "sourceTimeNanos" to sourceTimes[index], "observedTimeNanos" to observedTimes[index],
                    "latencyMs" to if (valid) (observedTimes[index] - sourceTimes[index]) / 1e6 else null,
                    "validTiming" to valid, "eventTimeMs" to syntheticEventTimes[index],
                    "source" to sources[index], "action" to actions[index], "deviceId" to devices[index],
                    "code" to codes[index], "historyIndex" to histories[index], "stateFlags" to flags[index],
                )
            }
        }
        // Sorting and summary allocations never run in the event callback or while holding its lock.
        result["summaryByStage"] = rows.groupBy { it["stage"] as String }.mapValues { (_, samples) ->
            val times = samples.mapNotNull { it["latencyMs"] as? Double }.sorted()
            linkedMapOf<String, Any?>("count" to samples.size, "validTimingCount" to times.size,
                "invalidTimingCount" to (samples.size - times.size),
                "minMs" to times.firstOrNull(), "meanMs" to if (times.isEmpty()) null else times.average(),
                "p50Ms" to percentile(times, .50), "p95Ms" to percentile(times, .95),
                "p99Ms" to percentile(times, .99), "maxMs" to times.lastOrNull())
        }
        val page = rows.drop(offset).take(limit)
        result["records"] = page
        result["recordsOffset"] = offset
        result["recordsReturned"] = page.size
        result["nextRecordsOffset"] = if (offset + page.size < rows.size && page.isNotEmpty()) offset + page.size else null
        result["paginationScope"] = "Offsets are stable after latencyStop; while recording, overwrites can move the retained window"
        return result
    }

    private fun percentile(sorted: List<Double>, quantile: Double): Double? =
        if (sorted.isEmpty()) null else sorted[((sorted.size - 1) * quantile).toInt()]
}
