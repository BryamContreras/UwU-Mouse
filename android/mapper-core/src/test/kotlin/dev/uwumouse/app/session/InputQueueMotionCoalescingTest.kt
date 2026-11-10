package dev.uwumouse.app.session

import dev.uwumouse.app.engine.PrecisionAimFilter
import org.junit.Assert.*
import org.junit.Test

class InputQueueMotionCoalescingTest {
    private fun queue()=InputQueueBuffer().apply {
        configureCameraMerge(1000.0,1000.0,preserveSamples=true)
        configureMotionBatchCoalescing(true)
    }
    private fun batch(start:Int,count:Int)=RawMouseMotionBatch(LongArray(count) { (start+it)*1_000_000L },
        FloatArray(count) { if((start+it)%2==0) .25f else -.5f },FloatArray(count) { .125f })
    private fun take(queue:InputQueueBuffer)=InputQueueEntry().also { assertTrue(queue.pollInto(it)) }

    @Test fun singletonNeedsNoCopyOrWaitAndQueuedBatchesFlattenOnceWithoutChangingOriginalSamples() {
        val queue=queue()
        val isolated=batch(0,1)
        assertTrue(queue.offerMotionBatch(isolated,10L,1L,7))
        assertSame(isolated,take(queue).motionBatch)
        val first=batch(1,3);val second=batch(4,4)
        assertTrue(queue.offerMotionBatch(first,20L,1L,7))
        assertTrue(queue.offerMotionBatch(second,30L,1L,7))
        val result=take(queue)
        assertEquals(8,result.type);assertEquals(7,result.code);assertEquals(1L,result.generation)
        assertEquals(20L,result.enqueuedAtNanos);assertEquals(1_000_000L,result.firstSampleTimeNanos)
        assertEquals(7_000_000L,result.lastSampleTimeNanos);assertEquals(7,result.sampleCount)
        val flattened=requireNotNull(result.motionBatch)
        assertArrayEquals(first.timesNanos+second.timesNanos,flattened.timesNanos)
        assertArrayEquals(first.xs+second.xs,flattened.xs,0f)
        assertArrayEquals(first.ys+second.ys,flattened.ys,0f)
        flattened.xs[0]=99f
        assertEquals(-.5f,first.xs[0],0f) // Producer payloads were never mutated/reused.
        assertEquals(1L,queue.diagnostics().coalescedMotionEntries)
        assertEquals(0,queue.size)
    }

    @Test fun mixedScalarsAndBatchesPreserveEveryFilterInputWhileReducingQueueCallbacks() {
        val queue=queue()
        val raw=batch(0,256)
        for(index in 0 until 64) assertTrue(queue.offer(3,7,raw.xs[index],raw.ys[index],5L,4L,raw.timesNanos[index]))
        for(start in 64 until 256 step 16) assertTrue(queue.offerMotionBatch(batch(start,16),10L,4L,7))
        val entry=take(queue)
        assertEquals(256,entry.sampleCount);assertEquals(8,entry.type)
        val combined=requireNotNull(entry.motionBatch)
        assertArrayEquals(raw.timesNanos,combined.timesNanos)
        assertArrayEquals(raw.xs,combined.xs,0f);assertArrayEquals(raw.ys,combined.ys,0f)
        val expected=PrecisionAimFilter().apply { configure(true);setFiring(true) }
        val actual=PrecisionAimFilter().apply { configure(true);setFiring(true) }
        val filtered=mutableListOf<Float>()
        assertTrue(combined.deliverMotion { dx,_,time -> filtered+=actual.filterX(dx,time);true })
        assertEquals(raw.timesNanos.indices.map { expected.filterX(raw.xs[it],raw.timesNanos[it]) },filtered)
        assertEquals(expected.snapshot(),actual.snapshot())
        assertEquals(75L,queue.diagnostics().coalescedMotionEntries)
        assertEquals(0,queue.size)
    }

    @Test fun buttonKeyModeEpochAndGenerationRemainFifoBarriers() {
        val queue=queue()
        fun motion(index:Int,epoch:Int=7,run:Long=4L) {
            assertTrue(queue.offerMotionBatch(batch(index,1),index.toLong(),run,epoch))
        }
        motion(0);motion(1)
        assertTrue(queue.offer(2,1,1f,0f,2L,4L))
        motion(3);motion(4)
        assertTrue(queue.offer(1,51,0f,0f,5L,4L))
        motion(6)
        assertTrue(queue.offer(4,8,0f,0f,7L,4L))
        motion(8);motion(9,epoch=8);motion(10,epoch=8,run=5L)
        val entries=generateSequence { if(queue.size==0) null else take(queue) }.toList()
        assertEquals(listOf(8,2,8,1,8,4,8,8,8),entries.map { it.type })
        assertEquals(listOf(2,2,1,1,1,1),entries.filter { it.type==8 }.map { it.sampleCount })
        assertEquals(listOf(7,7,7,7,8,8),entries.filter { it.type==8 }.map { it.code })
        assertEquals(5L,entries.last().generation)
    }

    @Test fun maxBatchSizeAndRingWrapStayBoundedWithoutCrossingAProducerPayload() {
        val queue=queue()
        repeat(4) { turn ->
            repeat(130) { assertTrue(queue.offerMotionBatch(batch(it*2,2),it.toLong(),turn.toLong(),7)) }
            val first=take(queue);val second=take(queue)
            assertEquals(256,first.sampleCount);assertEquals(4,second.sampleCount)
            assertEquals(0,queue.size)
        }
        assertEquals(130,queue.maxDepth)
        repeat(InputQueueBuffer.CAPACITY) { assertTrue(queue.offerMotionBatch(batch(0,1),0L,9L,7)) }
        assertFalse(queue.offerMotionBatch(batch(0,1),0L,9L,7))
        queue.clear()
        assertFalse(queue.pollInto(InputQueueEntry()))
        assertEquals(4L*128,queue.diagnostics().coalescedMotionEntries)
        queue.resetMetrics();assertEquals(0L,queue.diagnostics().coalescedMotionEntries)
    }

    @Test fun optInCannotChangePendingInputAndUnknownScalarTimesRemainSeparate() {
        val queue=InputQueueBuffer()
        assertTrue(queue.offerMotionBatch(batch(0,1),0L,1L))
        assertThrows(IllegalStateException::class.java) { queue.configureMotionBatchCoalescing(true) }
        queue.clear();queue.configureMotionBatchCoalescing(true)
        assertTrue(queue.offer(3,7,1f,0f,0L,1L))
        assertTrue(queue.offerMotionBatch(batch(0,1),0L,1L,7))
        assertEquals(3,take(queue).type)
        assertEquals(8,take(queue).type)
    }
}
