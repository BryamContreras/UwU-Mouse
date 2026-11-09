package dev.uwumouse.app.session

import dev.uwumouse.app.engine.PrecisionAimFilter
import org.junit.Assert.*
import org.junit.Test

/** Virtual producer/consumer stress; does not substitute for Android Handler/device tests. */
class CameraMotionBurstStressTest {
    @Test fun thousandHertzForTenSecondsKeepsEveryFilteredSampleAndButtonBarrier() {
        val queue=InputQueueBuffer().apply { configureCameraMerge(1000.0,1000.0,preserveSamples=true) }
        val actualFilter=PrecisionAimFilter().apply { configure(true) }
        val referenceFilter=PrecisionAimFilter().apply { configure(true) }
        val entry=InputQueueEntry()
        val expectedAtButton=mutableMapOf<Int,Double>()
        var now=0L
        var produced=0
        var consumed=0
        var buttonEdges=0
        var expectedX=0.0
        var emittedX=0.0
        var emittedY=0.0
        var emissions=0
        var maxWait=0L
        val total=10_000
        fun produceThrough(deadline:Long) {
            while(produced<total && produced*1_000_000L<=deadline) {
                val source=produced*1_000_000L
                if(produced%250==0) {
                    val down=(produced/250)%2==0
                    expectedAtButton[produced]=expectedX
                    assertTrue("button must fit",queue.offer(2,produced,if(down)1f else 0f,0f,source,1L,source))
                    referenceFilter.setFiring(down)
                }
                val rawX=(produced%7+1)*.125f
                expectedX+=referenceFilter.filterX(rawX,source)
                assertTrue("motion must fit at sample $produced",queue.offer(3,0,rawX,.25f,source,1L,source))
                produced++
            }
        }
        val pacer=CameraMotionPacer({now},{dx,dy,_ ->
            emittedX+=dx;emittedY+=dy;emissions++
            // An Android injection blocks the worker while the physical producer continues.
            now+=4_000_000L
            produceThrough(now)
            true
        },{fail("Unlimited batching must not schedule a timer")}).apply { configure(0) }
        produceThrough(now)
        var drains=0
        while(consumed<total || queue.size>0) {
            if(queue.size==0) { now=produced*1_000_000L;produceThrough(now) }
            val started=now
            pacer.beginInputBatch()
            try {
                var count=0
                while(count++<64 && queue.pollInto(entry)) {
                    maxWait=maxOf(maxWait,now-entry.enqueuedAtNanos)
                    if(entry.type==3) {
                        val filtered=actualFilter.filterX(entry.x.toFloat(),entry.lastSampleTimeNanos)
                        assertTrue(pacer.offer(filtered,entry.y.toFloat(),entry.enqueuedAtNanos,entry.lastSampleTimeNanos))
                        consumed++
                    } else {
                        assertTrue(pacer.flush())
                        assertEquals("movement must precede button ${entry.code}",expectedAtButton.getValue(entry.code),emittedX,.002)
                        actualFilter.setFiring(entry.x!=0.0);buttonEdges++
                    }
                    if(now-started>=4_000_000L) break
                }
            } finally { assertTrue(pacer.endInputBatch()) }
            assertTrue("consumer must make progress",++drains<20_000)
        }
        assertEquals(total,consumed)
        assertEquals(40,buttonEdges)
        assertEquals(expectedX,emittedX,.002)
        assertEquals(total*.25,emittedY,0.0)
        assertTrue("injections must be combined",emissions<total/2)
        assertTrue("bounded backlog: ${queue.maxDepth}",queue.maxDepth<32)
        assertTrue("bounded wait: $maxWait",maxWait<32_000_000L)
        assertFalse(pacer.pending)
    }

    @Test fun isolatedSampleFlushesWithoutTimerOrArtificialWaitAndDiscardPreventsReplay() {
        var now=900L
        val delivered=mutableListOf<Float>()
        val pacer=CameraMotionPacer({now},{x,_,_ -> delivered.add(x);true },{fail("No timer")})
        pacer.configure(0)
        pacer.beginInputBatch()
        assertTrue(pacer.offer(.25f,.5f,now,1_000_000L))
        assertTrue(delivered.isEmpty())
        assertTrue(pacer.endInputBatch())
        assertEquals(listOf(.25f),delivered)
        assertEquals(900L,now)
        pacer.beginInputBatch()
        assertTrue(pacer.offer(50f,50f,now,2_000_000L))
        pacer.discard()
        assertTrue(pacer.endInputBatch())
        assertEquals(listOf(.25f),delivered)
        assertFalse(pacer.pending)
    }
}
