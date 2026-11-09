package dev.mobimapper.mobimapper.session

import dev.mobimapper.mobimapper.engine.PrecisionAimFilter
import org.junit.Assert.*
import org.junit.Test

class RawMouseMotionBatchTest {
    private fun batch(count:Int=10)=RawMouseMotionBatch(LongArray(count) { it*8_000_000L },
        FloatArray(count) { 1f },FloatArray(count) { -.25f })

    @Test fun precisionRunsBeforeSummationEvenWhenTheAggregateWouldBeAFlick() {
        val raw=batch()
        val filter=PrecisionAimFilter().apply { configure(true);setFiring(true) }
        val reference=PrecisionAimFilter().apply { configure(true);setFiring(true) }
        var expected=0.0
        for(index in raw.timesNanos.indices) expected+=reference.filterX(raw.xs[index],raw.timesNanos[index])
        assertTrue(raw.xs.sum()>=8f)
        assertEquals(expected.toFloat(),raw.filteredXSum { x,time -> filter.filterX(x,time) },0f)
        assertEquals(10L,filter.snapshot()["activeSamples"])
        assertEquals(0L,filter.snapshot()["flickSamples"])
        assertEquals(-2.5f,raw.ySum(),0f)
    }

    @Test fun batchAndPhysicalEdgesKeepTheirFifoOrderAndGeneration() {
        val queue=InputQueueBuffer()
        val first=batch();val second=batch(80)
        assertTrue(queue.offerMotionBatch(first,10L,7L))
        assertTrue(queue.offer(2,1,1f,0f,11L,7L))
        assertTrue(queue.offerMotionBatch(second,12L,7L))
        assertTrue(queue.offer(2,1,0f,0f,13L,7L))
        val entry=InputQueueEntry()
        assertTrue(queue.pollInto(entry));assertSame(first,entry.motionBatch);assertEquals(7L,entry.generation)
        assertEquals(10,entry.sampleCount)
        assertTrue(queue.pollInto(entry));assertEquals(2,entry.type);assertNull(entry.motionBatch);assertEquals(1.0,entry.x,0.0)
        assertTrue(queue.pollInto(entry));assertSame(second,entry.motionBatch);assertEquals(80,entry.sampleCount)
        assertTrue(queue.pollInto(entry));assertEquals(2,entry.type);assertNull(entry.motionBatch);assertEquals(0.0,entry.x,0.0)
        assertFalse(queue.pollInto(entry))
    }

    @Test fun clearingAndWrappingTheQueueCannotReplayABatchFromTheOldSession() {
        val queue=InputQueueBuffer();val entry=InputQueueEntry()
        repeat(InputQueueBuffer.CAPACITY) { assertTrue(queue.offerMotionBatch(batch(1),it.toLong(),1L)) }
        assertFalse(queue.offerMotionBatch(batch(),1L,1L))
        queue.clear()
        repeat(InputQueueBuffer.CAPACITY*2) {
            assertTrue(queue.offer(1,51,0f,0f,it.toLong(),2L))
            assertTrue(queue.pollInto(entry));assertNull(entry.motionBatch);assertEquals(2L,entry.generation)
        }
    }

    @Test fun malformedBatchesAreRejectedBeforeQueueAdmission() {
        for(count in listOf(0,257)) assertThrows(IllegalArgumentException::class.java) { batch(count) }
        assertThrows(IllegalArgumentException::class.java) { RawMouseMotionBatch(longArrayOf(1),floatArrayOf(),floatArrayOf(1f)) }
        assertThrows(IllegalArgumentException::class.java) { RawMouseMotionBatch(longArrayOf(-1),floatArrayOf(1f),floatArrayOf(1f)) }
        assertThrows(IllegalArgumentException::class.java) { RawMouseMotionBatch(longArrayOf(1),floatArrayOf(Float.NaN),floatArrayOf(1f)) }
    }
    @Test fun nativeSamplesFromDifferentDevicesKeepEqualAndOutOfOrderTimesWithoutLosingCursorMotion() {
        val cursor=CursorPositionPolicy()
        assertTrue(cursor.begin(PointerBounds(0f,0f,100f,100f),100,100))
        val raw=RawMouseMotionBatch(longArrayOf(20,20,10),floatArrayOf(4f,5f,-2f),floatArrayOf(1f,2f,3f))
        raw.moveCursor(cursor)
        assertEquals(57f,cursor.x,0f);assertEquals(56f,cursor.y,0f)
    }
    @Test fun wheelOnlyRecordsDoNotResetPrecisionHistoryButVerticalMovementStillReachesTheFilter() {
        val raw=RawMouseMotionBatch(longArrayOf(1,2,3),floatArrayOf(1f,0f,0f),floatArrayOf(0f,0f,1f))
        val filtered=mutableListOf<Long>()
        assertEquals(1f,raw.filteredXSum { x,time -> filtered+=time;x },0f)
        assertEquals(listOf(1L,3L),filtered)
        assertEquals(1f,raw.ySum(),0f)
        assertEquals(2,raw.movingSampleCount)
    }

    @Test fun motionMetadataPreservesNegativeZeroVerticalOnlyAndDuplicateDeviceTimes() {
        val raw=RawMouseMotionBatch(longArrayOf(20,20,10,10),
            floatArrayOf(-0f,0f,-.25f,0f),floatArrayOf(0f,1f,0f,-0f))
        assertEquals(2,raw.movingSampleCount)
        val delivered=mutableListOf<Long>()
        assertTrue(raw.deliverMotion { _,_,time -> delivered+=time;true })
        assertEquals(listOf(20L,10L),delivered)
        assertEquals(0,RawMouseMotionBatch(longArrayOf(0),floatArrayOf(0f),floatArrayOf(-0f)).movingSampleCount)
        assertThrows(IllegalArgumentException::class.java) {
            RawMouseMotionBatch(longArrayOf(0),floatArrayOf(0f),floatArrayOf(Float.POSITIVE_INFINITY))
        }
    }
}
