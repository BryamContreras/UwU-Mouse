package dev.uwumouse.app.session

import dev.uwumouse.app.engine.PrecisionAimFilter
import org.junit.Assert.*
import org.junit.Test

class CameraInputBatchTest {
    private data class Motion(val x: Float, val y: Float, val enqueued: Long)
    private class Harness(hz: Int = 0) {
        var now = 0L
        var reject = false
        val motions = mutableListOf<Motion>()
        val wakeups = mutableListOf<Long>()
        val pacer = CameraMotionPacer({ now }, { x, y, time ->
            if (reject) false else { motions += Motion(x, y, time); true }
        }, { wakeups += it }).apply { configure(hz) }
        fun offer(x: Float = 1f, y: Float = 0f, source: Long = now, enqueued: Long = now) =
            pacer.offer(x, y, enqueued, source)
    }

    @Test fun isolatedSampleDeliversAtSynchronousDrainEndWithoutATimerOrHzLimit() {
        val h = Harness()
        h.pacer.beginInputBatch()
        assertTrue(h.offer(2f, 3f, source = 42L, enqueued = 100L))
        assertTrue(h.motions.isEmpty())
        assertTrue(h.pacer.endInputBatch())
        assertEquals(listOf(Motion(2f, 3f, 100L)), h.motions)
        assertTrue(h.wakeups.isEmpty())
        assertEquals(0, h.pacer.diagnostics()["rateHz"])
    }

    @Test fun oneThousandRawSamplesRemainBoundedToEightInputsWithoutDroppingDisplacement() {
        val h = Harness()
        h.pacer.beginInputBatch()
        repeat(1_000) { sample ->
            assertTrue(h.offer(1f, -.5f, source = sample * 1_000_000L, enqueued = sample.toLong()))
        }
        assertTrue(h.pacer.endInputBatch())
        assertEquals(125, h.motions.size)
        assertTrue(h.motions.all { it.x == 8f && it.y == -4f })
        assertEquals(1_000.0, h.motions.sumOf { it.x.toDouble() }, 0.0)
        assertEquals(-500.0, h.motions.sumOf { it.y.toDouble() }, 0.0)
        assertEquals(0L, h.motions.first().enqueued)
        assertEquals(992L, h.motions.last().enqueued)
        assertTrue(h.wakeups.isEmpty())
    }

    @Test fun sourceTimeBoundsAggregationEvenWhenOldSamplesArriveInOneWorkerDrain() {
        val h = Harness()
        h.pacer.beginInputBatch()
        assertTrue(h.offer(1f, source = 0L, enqueued = 10L))
        assertTrue(h.offer(2f, source = 8_000_000L, enqueued = 11L))
        assertTrue(h.offer(4f, source = 8_000_001L, enqueued = 12L))
        assertEquals(listOf(Motion(3f, 0f, 10L)), h.motions)
        assertTrue(h.pacer.endInputBatch())
        assertEquals(listOf(Motion(3f, 0f, 10L), Motion(4f, 0f, 12L)), h.motions)
    }

    @Test fun filteringUsesEveryOriginalSampleAndButtonBarrierFlushesBeforeFiringChanges() {
        val h = Harness()
        val filter = PrecisionAimFilter().apply { configure(true); setFiring(true) }
        val filtered = mutableListOf<Float>()
        h.pacer.beginInputBatch()
        for ((index, delta) in listOf(1f, 2f, -1f, -.5f).withIndex()) {
            val time = (index + 1L) * 1_000_000L
            val value = filter.filterX(delta, time)
            filtered += value
            assertTrue(h.offer(value, source = time))
        }
        assertEquals(4L, filter.snapshot()["activeSamples"])
        assertEquals(1L, filter.snapshot()["reversals"])
        assertTrue(h.motions.isEmpty())
        assertTrue(h.pacer.flush()) // Same FIFO barrier used before a mouse button edge.
        assertEquals(filtered.sum(), h.motions.single().x, .000001f)
        filter.setFiring(false)
        assertTrue(h.offer(filter.filterX(4f, 5_000_000L), source = 5_000_000L))
        assertTrue(h.pacer.endInputBatch())
        assertEquals(4f, h.motions.last().x, 0f)
        assertEquals(2, h.motions.size)
    }

    @Test fun cancellationOrReconfigurationDuringDrainCannotReplayBufferedMotion() {
        val h = Harness()
        h.pacer.beginInputBatch()
        assertTrue(h.offer(50f))
        h.pacer.discard()
        assertTrue(h.pacer.endInputBatch())
        assertTrue(h.motions.isEmpty())
        h.pacer.beginInputBatch()
        assertTrue(h.offer(60f))
        h.pacer.configure(0)
        assertTrue(h.pacer.endInputBatch())
        assertTrue(h.motions.isEmpty())
        assertTrue(h.offer(2f))
        assertEquals(listOf(Motion(2f, 0f, 0L)), h.motions)
    }

    @Test fun fixedRateDeliveryKeepsItsExistingCadenceAcrossDrainHooks() {
        val h = Harness(60)
        h.pacer.beginInputBatch()
        assertTrue(h.offer()) // Existing immediate first delivery.
        h.now = 1_000_000L
        assertTrue(h.offer(2f))
        assertTrue(h.pacer.endInputBatch())
        assertEquals(1, h.motions.size)
        assertTrue(h.pacer.pending)
        assertEquals(1, h.wakeups.size)
        h.now = 16_666_667L
        assertTrue(h.pacer.drainDue())
        assertEquals(listOf(1f, 2f), h.motions.map { it.x })
    }

    @Test fun failedBatchDeliveryStopsAndCannotRetainMotionForAnotherDrain() {
        val h = Harness()
        h.pacer.beginInputBatch()
        assertTrue(h.offer(10f))
        h.reject = true
        assertFalse(h.pacer.endInputBatch())
        assertFalse(h.pacer.pending)
        h.pacer.beginInputBatch()
        assertFalse(h.offer(20f))
        assertFalse(h.pacer.endInputBatch())
        h.reject = false
        h.pacer.configure(0)
        assertTrue(h.offer(1f))
        assertEquals(listOf(1f), h.motions.map { it.x })
    }

    @Test fun missingOrReversedSourceTimesSplitDeliveryInsteadOfInventingASampleInterval() {
        val h = Harness()
        h.pacer.beginInputBatch()
        assertTrue(h.offer(1f, source = Long.MIN_VALUE))
        assertTrue(h.offer(2f, source = 10L))
        assertTrue(h.offer(3f, source = 9L))
        assertTrue(h.pacer.endInputBatch())
        assertEquals(listOf(1f, 2f, 3f), h.motions.map { it.x })
        assertTrue(h.wakeups.isEmpty())
    }
}
