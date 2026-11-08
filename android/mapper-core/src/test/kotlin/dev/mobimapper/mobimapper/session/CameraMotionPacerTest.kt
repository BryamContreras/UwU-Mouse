package dev.mobimapper.mobimapper.session

import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

class CameraMotionPacerTest {
    private data class Motion(val dx:Float,val dy:Float,val enqueued:Long)
    private class Harness(hz:Int=0) {
        var now=0L
        var rejectNext=false
        val emitted=mutableListOf<Motion>()
        val order=mutableListOf<String>()
        val wakeups=ArrayDeque<Long>()
        var scheduledCalls=0
        val pacer=CameraMotionPacer({now},{dx,dy,enqueued ->
            if(rejectNext) { rejectNext=false; false }
            else { emitted+=Motion(dx,dy,enqueued); order+="move"; true }
        },{delay ->
            assertTrue(delay>=0L)
            wakeups.addLast(now+delay); scheduledCalls++
        })
        init { pacer.configure(hz) }
        fun offer(dx:Float=1f,dy:Float=0f,enqueued:Long=now)=pacer.offer(dx,dy,enqueued)
        fun advanceTo(time:Long) {
            require(time>=now)
            while(wakeups.isNotEmpty() && wakeups.peekFirst()<=time) {
                now=wakeups.removeFirst()
                assertTrue(pacer.drainDue())
            }
            now=time
        }
        fun drainAt(time:Long):Boolean {
            wakeups.removeFirst(); now=time
            return pacer.drainDue()
        }
    }

    @Test fun defaultUnlimitedPreservesEachFiniteSampleAndTimestampExactly() {
        var now=0L
        val emitted=mutableListOf<Motion>()
        val pacer=CameraMotionPacer({now},{x,y,t -> emitted+=Motion(x,y,t); true },
            {fail("Unlimited mode must not schedule")})
        val expected=listOf(Motion(.25f,-.5f,11L),Motion(-.25f,.5f,12L),Motion(0f,0f,13L))
        for(motion in expected) {
            assertTrue(pacer.offer(motion.dx,motion.dy,motion.enqueued)); now++
        }
        assertEquals(expected,emitted)
        assertFalse(pacer.pending)
        assertTrue(pacer.flush()); assertTrue(pacer.drainDue())
        assertEquals(3L,pacer.diagnostics()["emissions"])
        assertEquals(0,pacer.diagnostics()["rateHz"])
    }

    @Test fun oneTwentyHertzRetainsFractionalPhaseThroughOneSecond() {
        val h=Harness(120)
        assertTrue(h.offer()) // First sample is delivered immediately.
        for(tick in 1L..120L) {
            h.now++
            assertTrue(h.offer())
            assertEquals(1,h.wakeups.size)
            val expected=(tick*1_000_000_000L+119L)/120L
            assertEquals(expected,h.wakeups.peekFirst().toLong())
            assertTrue(h.drainAt(expected))
        }
        assertEquals(1_000_000_000L,h.now)
        assertEquals(121,h.emitted.size)
        assertTrue(h.wakeups.isEmpty())
        assertFalse(h.pacer.pending)
    }

    @Test fun physicalOneTwentyFiveHertzLimitedToSixtyPreservesAllNetMovement() {
        val h=Harness(60)
        for(sample in 0 until 125) {
            h.advanceTo(sample*8_000_000L)
            assertTrue(h.offer(1f,-.5f))
        }
        h.advanceTo(1_000_000_000L)
        assertEquals(125.0,h.emitted.sumOf { it.dx.toDouble() },0.0)
        assertEquals(-62.5,h.emitted.sumOf { it.dy.toDouble() },0.0)
        assertTrue(h.emitted.size in 59..61)
        assertTrue(h.emitted.size<125)
        assertFalse(h.pacer.pending)
        assertTrue(h.wakeups.isEmpty())
        assertTrue((h.pacer.diagnostics()["merged"] as Long)>0L)
    }

    @Test fun requestedTwoFortyCannotInventSamplesBeyondPhysicalOneTwentyFive() {
        val h=Harness(240)
        for(sample in 0 until 125) {
            h.advanceTo(sample*8_000_000L)
            assertTrue(h.offer(1f,.5f))
        }
        h.advanceTo(2_000_000_000L)
        assertEquals(125,h.emitted.size)
        assertEquals(125.0,h.emitted.sumOf { it.dx.toDouble() },0.0)
        assertEquals(0,h.scheduledCalls)
        assertFalse(h.pacer.pending)
    }

    @Test fun mergePreservesEarliestEnqueueTimeAndAllowsNetReversalWithinThePeriod() {
        val h=Harness(60)
        assertTrue(h.offer())
        h.now=1_000_000L; assertTrue(h.offer(5f,4f,800L))
        h.now=2_000_000L; assertTrue(h.offer(-2f,-1f,700L))
        val due=h.wakeups.peekFirst()
        assertTrue(h.drainAt(due))
        assertEquals(Motion(3f,3f,700L),h.emitted.last())
        assertEquals(1L,h.pacer.diagnostics()["merged"])
    }

    @Test fun flushBeforeBarrierPreservesOrderAndOutstandingWakeupWithoutDuplicates() {
        val h=Harness(60)
        assertTrue(h.offer())
        h.now=1_000_000L; assertTrue(h.offer(2f,0f,10L))
        h.now=2_000_000L; assertTrue(h.offer(3f,0f,20L))
        assertTrue(h.pacer.flush())
        h.order+="key"
        assertFalse(h.pacer.pending)
        assertEquals(1,h.wakeups.size)
        h.now=3_000_000L; assertTrue(h.offer(4f,0f,30L))
        assertEquals(1,h.wakeups.size)
        assertEquals(1,h.scheduledCalls)
        assertTrue(h.drainAt(h.wakeups.peekFirst()))
        assertEquals(listOf("move","move","key","move"),h.order)
        assertEquals(listOf(1f,5f,4f),h.emitted.map { it.dx })
        assertTrue(h.wakeups.isEmpty())
    }

    @Test fun discardCannotReplayPendingMovementWhenAnOldCallbackArrives() {
        val h=Harness(60)
        assertTrue(h.offer())
        h.now=1_000_000L; assertTrue(h.offer(50f,70f))
        h.pacer.discard()
        assertFalse(h.pacer.pending)
        assertTrue(h.drainAt(h.wakeups.peekFirst()))
        assertEquals(1,h.emitted.size)
        assertTrue(h.wakeups.isEmpty())
        h.now=20_000_000L
        assertTrue(h.offer(2f,3f))
        assertEquals(Motion(2f,3f,20_000_000L),h.emitted.last())
    }

    @Test fun lateCallbackEmitsOneAggregateAndSkipsMissedDeadlines() {
        val h=Harness(120)
        assertTrue(h.offer())
        h.now=1_000_000L; assertTrue(h.offer(2f,3f))
        h.now=7_000_000L; assertTrue(h.offer(4f,5f))
        assertTrue(h.drainAt(100_000_000L))
        assertEquals(2,h.emitted.size)
        assertEquals(Motion(6f,8f,1_000_000L),h.emitted.last())
        assertEquals(108_333_334L,h.pacer.diagnostics()["nextDeadlineNanos"])
        assertTrue(h.wakeups.isEmpty())
        h.now=101_000_000L; assertTrue(h.offer())
        assertEquals(1,h.wakeups.size)
        assertEquals(108_333_334L,h.wakeups.peekFirst().toLong())
    }

    @Test fun anEarlyCallbackReschedulesOnlyTheRemainingDelay() {
        val h=Harness(120)
        assertTrue(h.offer())
        h.now=1_000_000L; assertTrue(h.offer())
        assertTrue(h.drainAt(4_000_000L))
        assertEquals(1,h.emitted.size)
        assertEquals(1,h.wakeups.size)
        assertEquals(8_333_334L,h.wakeups.peekFirst().toLong())
        assertTrue(h.drainAt(8_333_334L))
        assertEquals(2,h.emitted.size)
    }

    @Test fun idleAndZeroNetPeriodsDoNotGenerateAnAlwaysRunningTicker() {
        val h=Harness(60)
        assertTrue(h.offer())
        h.now=1_000_000L; assertTrue(h.offer(2f,3f))
        h.now=2_000_000L; assertTrue(h.offer(-2f,-3f))
        h.advanceTo(2_000_000_000L)
        assertEquals(1,h.emitted.size)
        assertFalse(h.pacer.pending)
        assertTrue(h.wakeups.isEmpty())
        assertTrue(h.pacer.flush()); assertTrue(h.pacer.drainDue())
        assertEquals(1,h.scheduledCalls)
    }

    @Test fun invalidSamplesAreRejectedWithoutSchedulingOrContaminatingAcceptedMovement() {
        val h=Harness(60)
        for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
            assertFalse(h.offer(value,1f)); assertFalse(h.offer(1f,value))
        }
        assertTrue(h.emitted.isEmpty()); assertTrue(h.wakeups.isEmpty())
        assertTrue(h.offer())
        h.now=1_000_000L; assertTrue(h.offer(2f,3f,42L))
        assertFalse(h.offer(Float.NaN,4f))
        assertTrue(h.pacer.flush())
        assertEquals(Motion(2f,3f,42L),h.emitted.last())
        assertEquals(7L,h.pacer.diagnostics()["rejected"])
    }

    @Test fun failedDeliveryDiscardsPendingAndStopsUntilExplicitConfigure() {
        val h=Harness(60)
        assertTrue(h.offer())
        h.now=1_000_000L; assertTrue(h.offer(2f,3f))
        h.rejectNext=true
        assertFalse(h.drainAt(h.wakeups.peekFirst()))
        assertFalse(h.pacer.pending)
        assertEquals(true,h.pacer.diagnostics()["failed"])
        assertFalse(h.offer()); assertFalse(h.pacer.flush())
        assertTrue(h.wakeups.isEmpty())
        h.pacer.configure(120)
        assertTrue(h.offer())
        assertEquals(false,h.pacer.diagnostics()["failed"])
        assertEquals(1L,h.pacer.diagnostics()["emissions"])
    }

    @Test fun configureValidatesRatesBeforeChangingStateAndResetsSessionCounters() {
        val h=Harness(90)
        assertTrue(h.offer())
        h.now=1_000_000L; assertTrue(h.offer())
        try { h.pacer.configure(123); fail("Unsupported rate accepted") }
        catch(_:IllegalArgumentException) { }
        assertTrue(h.pacer.pending)
        assertEquals(90,h.pacer.diagnostics()["rateHz"])
        for(hz in listOf(0,60,90,120,125,144,240,250,500)) {
            h.wakeups.clear() // The owner removes/invalidates callbacks on configure.
            h.pacer.configure(hz)
            assertFalse(h.pacer.pending)
            assertEquals(hz,h.pacer.diagnostics()["rateHz"])
            assertEquals(0L,h.pacer.diagnostics()["samples"])
            assertEquals(0L,h.pacer.diagnostics()["emissions"])
        }
    }
}
