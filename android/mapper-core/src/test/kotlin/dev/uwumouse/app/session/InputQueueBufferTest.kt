package dev.uwumouse.app.session

import org.junit.Assert.*
import org.junit.Test

class InputQueueBufferTest {
    @Test fun precisionModePreservesOriginalDisplacementsTimesAndButtonEdges() {
        val queue=InputQueueBuffer().apply { configureCameraMerge(100.0,100.0,preserveSamples=true) }
        assertTrue(offer(queue,.2f,.5f,source=1_000_000L))
        assertTrue(offer(queue,.3f,.5f,source=2_000_000L))
        assertTrue(offer(queue,1f,0f,source=3_000_000L,type=2,code=1))
        assertEquals(3,queue.size)
        val first=take(queue);val second=take(queue);val button=take(queue)
        assertEquals(.2f.toDouble(),first.x,0.0)
        assertEquals(.3f.toDouble(),second.x,0.0)
        assertEquals(1_000_000L,first.lastSampleTimeNanos)
        assertEquals(2_000_000L,second.lastSampleTimeNanos)
        assertEquals(1,first.sampleCount)
        assertEquals(1,second.sampleCount)
        assertEquals(2,button.type)
        assertEquals(0L,queue.diagnostics().relativeMergedMoves)
        queue.disableCameraMerge()
        assertTrue(offer(queue,1f,0f));assertTrue(offer(queue,1f,0f))
        assertEquals(1,queue.size) // Laboratory/default behavior is restored explicitly.
    }
    private fun profiled(maxDx: Double = 100.0, maxDy: Double = 100.0) = InputQueueBuffer().apply {
        configureCameraMerge(maxDx, maxDy)
    }

    private fun offer(queue: InputQueueBuffer, x: Float, y: Float, source: Long = 0L,
                      offered: Long = 10_000_000L, run: Long = 1L, type: Int = 3, code: Int = 0) =
        queue.offer(type, code, x, y, offered, run, source)

    private fun take(queue: InputQueueBuffer) = InputQueueEntry().also { assertTrue(queue.pollInto(it)) }

    @Test fun firstSampleIsImmediatelyAvailableWithoutWaitingForAnotherSample() {
        val queue = profiled()
        assertTrue(offer(queue, 2f, 3f, source = 5_000_000L))
        val first = take(queue)
        assertEquals(2.0, first.x, 0.0)
        assertEquals(3.0, first.y, 0.0)
        assertEquals(1, first.sampleCount)
        assertEquals(0, queue.size)
    }

    @Test fun curvedSameDirectionSamplesSumInDoubleAndKeepOldestAdmissionTime() {
        val queue = profiled()
        assertTrue(offer(queue, .1f, .25f, source = 1_000_000L, offered = 20_000_000L))
        assertTrue(offer(queue, .2f, .1f, source = 3_000_000L, offered = 30_000_000L))
        assertTrue(offer(queue, .3f, 0f, source = 9_000_000L, offered = 40_000_000L))
        val entry = take(queue)
        assertEquals(.1f.toDouble() + .2f.toDouble() + .3f.toDouble(), entry.x, 0.0)
        assertEquals(.25f.toDouble() + .1f.toDouble(), entry.y, 0.0)
        assertEquals(20_000_000L, entry.enqueuedAtNanos)
        assertEquals(1_000_000L, entry.firstSampleTimeNanos)
        assertEquals(9_000_000L, entry.lastSampleTimeNanos)
        assertEquals(3, entry.sampleCount)
        assertEquals(2L, queue.diagnostics().relativeMergedMoves)
        assertEquals(0, queue.size)
    }

    @Test fun originalHistoryTimesBoundMergeEvenWhenOneCallbackOffersEverythingTogether() {
        val queue = profiled()
        val callbackTime = 100_000_000L
        assertTrue(offer(queue, 1f, 1f, source = 1_000_000L, offered = callbackTime))
        assertTrue(offer(queue, 1f, 2f, source = 9_000_000L, offered = callbackTime))
        assertTrue(offer(queue, 1f, 3f, source = 9_000_001L, offered = callbackTime))
        assertEquals(2, queue.size)
        val first = take(queue); val second = take(queue)
        assertEquals(2, first.sampleCount)
        assertEquals(1, second.sampleCount)
        assertEquals(2.0, first.x, 0.0)
        assertEquals(1.0, second.x, 0.0)
        assertEquals(callbackTime, first.enqueuedAtNanos)
        assertEquals(callbackTime, second.enqueuedAtNanos)
    }

    @Test fun eightSamplesIsAHardLimitAndDoesNotFallBackToLegacyForCollinearSamples() {
        val queue = profiled()
        repeat(9) { assertTrue(offer(queue, 1f, 2f, source = it.toLong())) }
        assertEquals(2, queue.size)
        assertEquals(8, take(queue).sampleCount)
        assertEquals(1, take(queue).sampleCount)
        assertEquals(7L, queue.diagnostics().mergedMoves)
    }

    @Test fun missingOrReversedSourceTimesNeverUseExpandedOrLegacyProfileMerge() {
        val queue = profiled()
        repeat(2) {
            assertTrue(queue.offer(3, 0, 1f, 2f, 100L, 1L))
        }
        assertTrue(offer(queue, 1f, 2f, source = 50L))
        assertTrue(offer(queue, 1f, 2f, source = 49L))
        assertEquals(4, queue.size)
        assertEquals(0L, queue.diagnostics().mergedMoves)
    }

    @Test fun reversalOnEitherAxisSplitsRatherThanCancellingAcceptedDisplacement() {
        val queue = profiled()
        assertTrue(offer(queue, 3f, 4f, source = 1L))
        assertTrue(offer(queue, -2f, 1f, source = 2L))
        assertTrue(offer(queue, -1f, -2f, source = 3L))
        assertEquals(3, queue.size)
        val first = take(queue); val second = take(queue); val third = take(queue)
        assertEquals(0.0, first.x + second.x + third.x, 0.0)
        assertEquals(3.0, first.y + second.y + third.y, 0.0)
        assertEquals(0L, queue.diagnostics().mergedMoves)
    }

    @Test fun keyUpButtonEdgesModeChangeAndGenerationAreHardFifoBarriers() {
        val queue = profiled()
        // Relative, W up, relative, M1 down, relative, M1 up, relative, mode, relative.
        val types = listOf(3, 1, 3, 2, 3, 2, 3, 4, 3)
        types.forEachIndexed { index, type ->
            val down = index == 3
            assertTrue(offer(queue, if (type == 3 || down) 1f else 0f, 0f,
                source = index.toLong(), type = type, code = if (type == 1) 51 else 1))
        }
        assertTrue(offer(queue, 1f, 0f, source = 10L, run = 2L))
        val entries = (0 until queue.size).map { take(queue) }
        assertEquals(types + 3, entries.map { it.type })
        assertEquals(0.0, entries[1].x, 0.0) // W up survives between movement packets.
        assertEquals(1.0, entries[3].x, 0.0)
        assertEquals(0.0, entries[5].x, 0.0)
        assertEquals(2L, entries.last().generation)
        assertEquals(0L, queue.diagnostics().mergedMoves)
    }

    @Test fun regionGainLimitsSplitPacketsWithoutClippingOrDroppingTheRemainder() {
        val queue = profiled(maxDx = 4.0, maxDy = 3.0)
        assertTrue(offer(queue, 3f, 1f, source = 1L))
        assertTrue(offer(queue, 2f, 1f, source = 2L)) // Would exceed X limit.
        assertTrue(offer(queue, 1f, 3f, source = 3L)) // Would exceed Y limit.
        val entries = (0 until queue.size).map { take(queue) }
        assertEquals(3, entries.size)
        assertEquals(6.0, entries.sumOf { it.x }, 0.0)
        assertEquals(5.0, entries.sumOf { it.y }, 0.0)
        // An original oversized sample retains the engine's existing extreme-input policy.
        assertTrue(offer(queue, 500f, 0f, source = 4L))
        assertEquals(500.0, take(queue).x, 0.0)
    }

    @Test fun profileMergeUsesDoubleUntilOneFinalConsumerConversion() {
        val queue = profiled(maxDx = 100_000_000.0)
        assertTrue(offer(queue, 16_777_216f, 0f, source = 1L))
        repeat(7) { assertTrue(offer(queue, 1f, 0f, source = it + 2L)) }
        assertEquals(16_777_223.0, take(queue).x, 0.0)
        assertEquals(7L, queue.diagnostics().relativeMergedMoves)
    }

    @Test fun legacyCollinearArithmeticAndCursorLatestPositionRemainUnchanged() {
        val queue = InputQueueBuffer()
        // Legacy retains Float rounding and has no time/count limit.
        assertTrue(offer(queue, 16_777_216f, 0f, source = 0L))
        repeat(10) { assertTrue(offer(queue, 1f, 0f, source = (it + 1L) * 100_000_000L)) }
        assertEquals(16_777_216.0, take(queue).x, 0.0)
        assertTrue(offer(queue, 1f, 2f))
        assertTrue(offer(queue, 2f, 1f))
        assertEquals(2, queue.size) // Noncollinear legacy samples are still separate.
        queue.clear()
        assertTrue(offer(queue, 10f, 20f, offered = 100L, type = 6))
        assertTrue(offer(queue, 30f, 40f, offered = 200L, type = 6))
        assertTrue(offer(queue, 1f, 0f, type = 5))
        assertTrue(offer(queue, 50f, 60f, type = 6))
        val cursor = take(queue)
        assertEquals(30.0, cursor.x, 0.0)
        assertEquals(40.0, cursor.y, 0.0)
        assertEquals(100L, cursor.enqueuedAtNanos)
        assertEquals(5, take(queue).type)
        assertEquals(50.0, take(queue).x, 0.0)
        assertEquals(1L, queue.diagnostics().cursorMergedMoves)
    }

    @Test fun clearAndNewGenerationNeverReplayBufferedMotionOrHeldEdges() {
        val queue = profiled()
        assertTrue(offer(queue, 1f, 0f, type = 1)) // Held W.
        assertTrue(offer(queue, 5f, 6f, source = 1L))
        queue.clear()
        assertEquals(0, queue.diagnostics().currentDepth)
        assertFalse(queue.pollInto(InputQueueEntry()))
        assertTrue(offer(queue, 2f, 3f, source = 2L, run = 2L))
        val fresh = take(queue)
        assertEquals(3, fresh.type)
        assertEquals(2L, fresh.generation)
        assertEquals(2.0, fresh.x, 0.0)
        assertEquals(3.0, fresh.y, 0.0)
    }

    @Test fun ringWrapSaturationAndMetricResetPreservePendingEvents() {
        val queue = profiled()
        repeat(InputQueueBuffer.CAPACITY) {
            assertTrue(offer(queue, 1f, 0f, type = 1, code = it))
        }
        assertFalse(offer(queue, 1f, 0f, type = 2))
        repeat(100) { assertEquals(it, take(queue).code) }
        repeat(100) { assertTrue(offer(queue, 1f, 0f, type = 1, code = it + 256)) }
        val remaining = (0 until queue.size).map { take(queue).code }
        assertEquals((100 until 356).toList(), remaining)
        assertTrue(offer(queue, 1f, 1f, source = 1L))
        assertTrue(offer(queue, 1f, 2f, source = 2L))
        queue.resetMetrics()
        val metrics = queue.diagnostics()
        assertEquals(1, metrics.currentDepth)
        assertEquals(1, metrics.maxDepth)
        assertEquals(0L, metrics.mergedMoves)
        assertEquals(2, take(queue).sampleCount)
    }

    @Test fun invalidConfigurationAndNonFiniteInputAreRejectedWithoutChangingPendingData() {
        val queue = profiled()
        assertThrows(IllegalArgumentException::class.java) { queue.configureCameraMerge(0.0, 1.0) }
        assertThrows(IllegalArgumentException::class.java) { queue.configureCameraMerge(1.0, Double.NaN) }
        assertFalse(offer(queue, Float.NaN, 1f))
        assertFalse(offer(queue, 1f, Float.POSITIVE_INFINITY))
        assertTrue(offer(queue, 2f, 3f))
        assertThrows(IllegalStateException::class.java) { queue.disableCameraMerge() }
        assertThrows(IllegalStateException::class.java) { queue.configureCameraMerge(10.0, 10.0) }
        assertEquals(2.0, take(queue).x, 0.0)
        queue.disableCameraMerge()
        assertTrue(offer(queue, 1f, 2f))
        assertTrue(offer(queue, 2f, 4f, source = 100_000_000L))
        assertEquals(1, queue.size)
    }
}
