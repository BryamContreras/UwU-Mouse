package dev.uwumouse.app.session

import org.junit.Assert.*
import org.junit.Test

class InputLatencyProbeTest {
    @Test fun nativeReceiptAndDrawKeepOriginalTimestampWithoutAccessibilityAttribution() {
        val time=2_000_000_000L
        var current=time
        val native=InputLatencyRecorder { current }
        native.start(30_000,123)
        val token=native.recordReceipt("native_motion",time-1_000_000L,time,8194,7,17)
        current+=4_000_000L
        native.recordCursorDraw(token)
        val rows=records(native.stop())
        assertEquals("native_motion_current_receipt",rows.first()["stage"])
        assertEquals(1.0,rows.first()["latencyMs"])
        assertEquals("cursor_draw_cpu_end",rows.last()["stage"])
        assertEquals(5.0,rows.last()["latencyMs"])
    }
    private var now = 1_000_000_000L
    private val recorder = InputLatencyRecorder { now }

    @Suppress("UNCHECKED_CAST")
    private fun records(snapshot: Map<String, Any?>) = snapshot["records"] as List<Map<String, Any?>>

    @Suppress("UNCHECKED_CAST")
    private fun summaries(snapshot: Map<String, Any?>) =
        snapshot["summaryByStage"] as Map<String, Map<String, Any?>>

    private fun receipt(sourceTime: Long = now - 2_000_000L, history: Int = -1): Long =
        recorder.recordReceipt("motion", sourceTime, now, 8194, 2, 7,
            historyIndex = history, active = true, targetConfirmed = true, cursorMode = true)

    @Test fun disabledRecorderRetainsNothingAndDoesNotReadClockOnStageOrDraw() {
        val disabled = InputLatencyRecorder { error("Disabled recorder accessed clock") }
        assertEquals(0L, disabled.recordReceipt("motion", 1L, 2L, 8194, 2, 7))
        disabled.recordStage("worker", 1L)
        disabled.recordCursorDraw(1L)
        val snapshot = disabled.snapshot()
        assertEquals(0, snapshot["storedCount"])
        assertEquals("not_started", snapshot["stopReason"])
    }

    @Test fun currentAndHistoryAreReportedSeparatelyWithTheirOwnSourceTime() {
        recorder.start(30_000, 123)
        receipt()
        receipt(now - 9_000_000L, history = 0)
        val snapshot = recorder.snapshot()
        val rows = records(snapshot)
        assertEquals(2.0, rows[0]["latencyMs"])
        assertEquals(9.0, rows[1]["latencyMs"])
        assertEquals(now, rows[0]["observedTimeNanos"])
        assertEquals(now, rows[1]["observedTimeNanos"])
        assertEquals(7, rows[0]["stateFlags"])
        assertEquals(-1, rows[0]["historyIndex"])
        assertEquals(0, rows[1]["historyIndex"])
        assertEquals(2.0, summaries(snapshot)["accessibility_motion_current_receipt"]!!["p95Ms"])
        assertEquals(9.0, summaries(snapshot)["accessibility_motion_history_receipt"]!!["p95Ms"])
    }

    @Test fun windowExpiresAtDeadlineWithoutRecordingLateEvents() {
        recorder.start(30_000, 123)
        receipt()
        now += 30_000_000_000L
        assertEquals(0L, receipt())
        assertFalse(recorder.enabled)
        val snapshot = recorder.snapshot()
        assertEquals("window_expired", snapshot["stopReason"])
        assertEquals(1, snapshot["storedCount"])
        assertEquals(now, snapshot["stoppedTimeNanos"])
    }

    @Test fun snapshotAlsoExpiresIdleRunAndStopPreservesItsEvidence() {
        recorder.start(10, 123)
        receipt()
        now += 20_000_000L
        val snapshot = recorder.snapshot()
        assertFalse(snapshot["enabled"] as Boolean)
        assertEquals("window_expired", snapshot["stopReason"])
        assertEquals(1_010_000_000L, snapshot["stoppedTimeNanos"])
        val stopped = recorder.stop()
        assertEquals(snapshot["runId"], stopped["runId"])
        assertEquals(records(snapshot), records(stopped))
    }

    @Test fun ringCapacityIsFixedAndPagesRemainChronologicalAfterStop() {
        recorder.start(30_000, 123)
        repeat(4100) { receipt() }
        val firstPage = recorder.stop()
        assertEquals(4096, firstPage["storedCount"])
        assertEquals(4100L, firstPage["totalRecorded"])
        assertEquals(4L, firstPage["overwrittenCount"])
        assertEquals(5L, records(firstPage).first()["sequence"])
        assertEquals(512, records(firstPage).size)
        assertEquals(512, firstPage["nextRecordsOffset"])
        val lastPage = recorder.snapshot(4090, 512)
        assertEquals(6, records(lastPage).size)
        assertEquals(4100L, records(lastPage).last()["sequence"])
        assertNull(lastPage["nextRecordsOffset"])
        assertEquals(4096, summaries(firstPage)["accessibility_motion_current_receipt"]!!["count"])
    }

    @Test fun newRunRejectsOldDrawTokenAndCallbackTimestamp() {
        recorder.start(30_000, 123)
        val oldToken = receipt()
        val oldReceiptTime = now
        val firstRunId = recorder.snapshot()["runId"]
        now += 1_000_000L
        recorder.start(30_000, 123)
        assertNotEquals(firstRunId, recorder.snapshot()["runId"])
        recorder.recordCursorDraw(oldToken)
        assertEquals(0L, recorder.recordReceipt("motion", oldReceiptTime - 1L, oldReceiptTime, 8194, 2, 7))
        assertEquals(0, recorder.snapshot()["storedCount"])
        val currentToken = receipt()
        now += 5_000_000L
        recorder.recordCursorDraw(currentToken)
        val draw = records(recorder.snapshot()).last()
        assertEquals("cursor_draw_cpu_end", draw["stage"])
        assertEquals(7.0, draw["latencyMs"])
        assertEquals(8194, draw["source"])
    }

    @Test fun missingOrFutureSourceTimesAreExcludedInsteadOfClampedToZero() {
        recorder.start(30_000, 123)
        recorder.recordStage("worker", -1L)
        recorder.recordStage("worker", now + 1L)
        recorder.recordStage("worker", now - 3_000_000L, 2, 1000L)
        val snapshot = recorder.snapshot()
        val summary = summaries(snapshot)["worker"]!!
        assertEquals(3, summary["count"])
        assertEquals(2, summary["invalidTimingCount"])
        assertEquals(1, summary["validTimingCount"])
        assertEquals(3.0, summary["p95Ms"])
        assertNull(records(snapshot)[0]["latencyMs"])
        assertEquals(1000L, records(snapshot).last()["eventTimeMs"])
    }

    @Test fun stopDisablesRecordingAndEmptySnapshotHasNoInventedPercentiles() {
        recorder.start(30_000, 123)
        val stopped = recorder.stop()
        assertFalse(recorder.enabled)
        assertEquals("requested", stopped["stopReason"])
        assertEquals(0L, receipt())
        assertTrue(summaries(stopped).isEmpty())
    }

    @Test fun invalidWindowAndPaginationArgumentsAreRejected() {
        for (duration in listOf(0, -1, 60_001)) {
            assertThrows(IllegalArgumentException::class.java) { recorder.start(duration, 123) }
        }
        assertThrows(IllegalArgumentException::class.java) { recorder.snapshot(-1, 512) }
        assertThrows(IllegalArgumentException::class.java) { recorder.snapshot(0, 513) }
        assertThrows(IllegalArgumentException::class.java) { recorder.start(30_000, 0) }
    }
}
