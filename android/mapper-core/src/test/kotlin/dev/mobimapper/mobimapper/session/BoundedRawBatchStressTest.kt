package dev.mobimapper.mobimapper.session

import dev.mobimapper.mobimapper.backend.recovery.RecoveryJournal
import dev.mobimapper.mobimapper.engine.*
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

/** Virtual producer/worker load; this does not execute Android Handler, Binder or evdev. */
class BoundedRawBatchStressTest {
    private data class ExpectedEdge(val type:Int,val down:Boolean,val time:Long,val contacts:List<Contact>)

    @Test fun eightThousandRawSamplesPerSecondSurviveSlowInjectionAndKeyButtonBarriers() {
        runStress(samplesPerPacket=8,scalar=false)
    }

    @Test fun scalarThousandHertzPathAlsoCombinesDeliveryWithoutMergingRawSamples() {
        runStress(samplesPerPacket=1,scalar=true)
    }

    private fun runStress(samplesPerPacket:Int,scalar:Boolean) {
        val totalPackets=10_000
        val queue=InputQueueBuffer().apply { configureCameraMerge(1000.0,1000.0,preserveSamples=true) }
        val entry=InputQueueEntry()
        val expectedEdges=ArrayDeque<ExpectedEdge>()
        val geometry=MappingGeometry(1000f,2000f)
        val bindings=MappingBindings(distinctCameraContacts=true)
        val referenceComposer=TouchComposer({1_000L},{true})
        val referenceEngine=MappingEngine(referenceComposer,geometry,bindings).apply { setCameraEnabled(true) }
        val referenceFilter=PrecisionAimFilter().apply { configure(true) }
        val actualFilter=PrecisionAimFilter().apply { configure(true) }
        var produced=0
        var consumedPackets=0
        var consumedRaw=0
        var consumedEdges=0
        var now=1_000_000L
        var referenceClock=0L
        var moveFrames=0
        var maxWait=0L

        fun produceThrough(deadline:Long) {
            while(produced<totalPackets && (produced+1L)*1_000_000L<=deadline) {
                val source=produced*1_000_000L
                val arrival=source+1_000_000L
                if(produced%500==0) {
                    val down=(produced/500)%2==0
                    expectedEdges.addLast(ExpectedEdge(1,down,source,referenceComposer.snapshot()))
                    assertTrue(queue.offer(1,MappingEngine.W,if(down)1f else 0f,0f,arrival,1L,source))
                    assertTrue(referenceEngine.key(MappingEngine.W,down))
                }
                if(produced%250==0) {
                    val down=(produced/250)%2==0
                    expectedEdges.addLast(ExpectedEdge(2,down,source,referenceComposer.snapshot()))
                    assertTrue(queue.offer(2,MappingEngine.LEFT_BUTTON,if(down)1f else 0f,0f,arrival,1L,source))
                    assertTrue(referenceEngine.mouseButton(MappingEngine.LEFT_BUTTON,down))
                    referenceFilter.setFiring(down)
                }
                val times=LongArray(samplesPerPacket) { source+it*(1_000_000L/samplesPerPacket) }
                val xs=FloatArray(samplesPerPacket) { if((produced*samplesPerPacket+it)%8<4) .125f else -.125f }
                val ys=FloatArray(samplesPerPacket) { .000125f }
                for(index in times.indices) {
                    val filtered=referenceFilter.filterX(xs[index],times[index])
                    assertTrue(referenceEngine.relativeMouse(filtered,ys[index]))
                    referenceClock=times[index]
                }
                val accepted=if(scalar) queue.offer(3,0,xs[0],ys[0],arrival,1L,times[0])
                    else queue.offerMotionBatch(RawMouseMotionBatch(times,xs,ys),arrival,1L)
                assertTrue("Queue overflow at packet $produced",accepted)
                produced++
            }
        }

        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001)) }
        val composer=TouchComposer({1_000L+now/1_000_000L},{frame ->
            assertTrue(journal.prepare(1,frame))
            assertTrue(journal.acknowledged(1,frame,true))
            if(frame.action==TouchComposer.MOVE) moveFrames++
            // The physical source continues while one synchronous delivery costs 4 ms.
            now+=4_000_000L
            produceThrough(now)
            true
        })
        val engine=MappingEngine(composer,geometry,bindings).apply { setCameraEnabled(true) }
        produceThrough(now)
        var drains=0
        while(consumedPackets<totalPackets || queue.size>0) {
            if(queue.size==0) { now=(produced+1L)*1_000_000L;produceThrough(now) }
            val started=now
            var batchOpen=false
            try {
                var count=0
                while(count++<64 && queue.pollInto(entry)) {
                    maxWait=maxOf(maxWait,now-entry.enqueuedAtNanos)
                    if(entry.type==8 || entry.type==3) {
                        if(!batchOpen) { assertTrue(composer.beginMoveBatch());batchOpen=true }
                        if(entry.type==3) {
                            assertNull(entry.motionBatch)
                            val filtered=actualFilter.filterX(entry.x.toFloat(),entry.lastSampleTimeNanos)
                            assertTrue(engine.relativeMouse(filtered,entry.y.toFloat()))
                            consumedRaw++
                        } else {
                            val raw=requireNotNull(entry.motionBatch)
                            for(index in raw.timesNanos.indices) {
                                val filtered=actualFilter.filterX(raw.xs[index],raw.timesNanos[index])
                                assertTrue(engine.relativeMouse(filtered,raw.ys[index]))
                                consumedRaw++
                            }
                        }
                        consumedPackets++
                    } else {
                        if(batchOpen) { assertTrue(composer.endMoveBatch());batchOpen=false }
                        val expected=expectedEdges.removeFirst()
                        assertEquals(expected.type,entry.type)
                        assertEquals(expected.down,entry.x!=0.0)
                        assertEquals(expected.time,entry.lastSampleTimeNanos)
                        assertEquals("Movement must precede edge at ${expected.time}",expected.contacts,composer.snapshot())
                        if(entry.type==1) assertTrue(engine.key(entry.code,entry.x!=0.0))
                        else {
                            assertTrue(engine.mouseButton(entry.code,entry.x!=0.0))
                            actualFilter.setFiring(entry.x!=0.0)
                        }
                        consumedEdges++
                    }
                    if(now-started>=4_000_000L) break
                }
            } finally { if(batchOpen) assertTrue(composer.endMoveBatch()) }
            assertTrue("Worker must make progress",++drains<20_000)
        }
        assertEquals(totalPackets,consumedPackets)
        assertEquals(totalPackets*samplesPerPacket,consumedRaw)
        assertEquals(60,consumedEdges)
        assertTrue(expectedEdges.isEmpty())
        assertEquals(referenceComposer.snapshot(),composer.snapshot())
        assertEquals(referenceEngine.cameraRestarts,engine.cameraRestarts)
        assertEquals(referenceFilter.snapshot()["activeSamples"],actualFilter.snapshot()["activeSamples"])
        assertEquals((totalPackets-1L)*1_000_000L+(samplesPerPacket-1L)*(1_000_000L/samplesPerPacket),referenceClock)
        assertTrue("Motion delivery must be combined: $moveFrames",moveFrames<totalPackets/2)
        assertTrue("Bounded queue depth: ${queue.maxDepth}",queue.maxDepth<64)
        assertTrue("Bounded waiting: $maxWait ns",maxWait<64_000_000L)
        println("BOUNDED_RAW_STRESS scalar=$scalar packets=$consumedPackets raw=$consumedRaw edges=$consumedEdges " +
            "moveFrames=$moveFrames maxDepth=${queue.maxDepth} maxWaitNanos=$maxWait")
        assertTrue(engine.pause())
        assertEquals(0,journal.snapshot().possibleContacts)
        assertFalse(composer.releaseUnverified)
    }
}
