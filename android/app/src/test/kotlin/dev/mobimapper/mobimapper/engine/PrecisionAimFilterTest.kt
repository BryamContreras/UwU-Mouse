package dev.mobimapper.mobimapper.engine

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class PrecisionAimFilterTest {
    private fun firing()=PrecisionAimFilter().also { it.configure(true); it.setFiring(true) }
    private fun exact(expected:Float,actual:Float)=assertEquals(expected.toRawBits(),actual.toRawBits())

    @Test fun defaultOffPreservesEveryFloatBitIncludingSignedZeroAndNaNPayload() {
        val filter=PrecisionAimFilter()
        filter.setFiring(true)
        for(value in listOf(0f,-0f,.125f,-.125f,Float.MAX_VALUE,Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY,Float.fromBits(0x7fc00042))) {
            exact(value,filter.filterX(value,100L))
        }
        assertEquals(0L,filter.snapshot()["activeSamples"])
    }

    @Test fun enabledWithoutHeldTriggerIsAlsoBitwisePassThrough() {
        val filter=PrecisionAimFilter()
        filter.configure(true)
        for(value in listOf(0f,-0f,1f,-1f,Float.POSITIVE_INFINITY,Float.fromBits(0x7fc00042))) {
            exact(value,filter.filterX(value,100L))
        }
        assertEquals(0L,filter.snapshot()["activeSamples"])
    }

    @Test fun smallAlternatingHorizontalJitterIsDampedWithoutOutputInTheOppositeDirection() {
        val filter=firing()
        var outputMagnitude=0.0
        for(index in 0 until 100) {
            val dx=if(index%2==0) 1f else -1f
            val output=filter.filterX(dx,index*8_000_000L)
            assertTrue(output*dx>=0f)
            assertTrue(abs(output)<=abs(dx))
            outputMagnitude+=abs(output.toDouble())
        }
        assertTrue(outputMagnitude<90.0)
        assertTrue(outputMagnitude>60.0) // Modest damping instead of locking horizontal aim.
        assertEquals(99L,filter.snapshot()["reversals"])
    }

    @Test fun intentionalFineDriftConvergesInBothDirectionsWithoutADeadZone() {
        for(sign in listOf(1f,-1f)) {
            val filter=firing()
            var last=0f
            var total=0.0
            repeat(32) { index ->
                last=filter.filterX(sign,index*8_000_000L)
                assertTrue(last*sign>0f)
                assertTrue(abs(last)<=1f)
                total+=abs(last.toDouble())
            }
            assertTrue(abs(last)>.99f)
            assertTrue(total>30.0)
        }
    }

    @Test fun largeFlicksPassThroughExactlyAndDoNotLeaveAStoredTail() {
        val filter=firing()
        filter.filterX(1f,0L)
        for((index,value) in listOf(8f,-8f,40f,-400f,Float.MAX_VALUE).withIndex()) {
            exact(value,filter.filterX(value,(index+1)*8_000_000L))
        }
        exact(0f,filter.filterX(0f,50_000_000L))
        assertEquals(5L,filter.snapshot()["flickSamples"])
        assertEquals(1f,filter.snapshot()["lastGain"])
    }

    @Test fun mediumMotionBlendsTowardPassThroughAndAllReversalsRespectTheInputBound() {
        val firstSmall=firing().filterX(1f,0L)
        val firstMedium=firing().filterX(7f,0L)
        assertTrue(firstMedium/7f>firstSmall)
        val filter=firing()
        for((index,value) in listOf(.1f,-.1f,2f,-2f,3f,-3f,7.5f,-7.5f).withIndex()) {
            val output=filter.filterX(value,index*1_000_000L)
            assertTrue(output*value>=0f)
            assertTrue(abs(output)<=abs(value))
        }
    }

    @Test fun releaseCannotReplaySuppressedDisplacementAndNextPressStartsFresh() {
        val filter=firing()
        repeat(10) { filter.filterX(1f,it*8_000_000L) }
        filter.setFiring(false)
        exact(-1f,filter.filterX(-1f,81_000_000L))
        exact(0f,filter.filterX(0f,82_000_000L))
        filter.setFiring(true)
        exact(firing().filterX(1f,83_000_000L),filter.filterX(1f,83_000_000L))
    }

    @Test fun pauseOrModeResetClearsTriggerOwnershipAndTimingHistory() {
        val filter=firing()
        filter.filterX(2f,0L)
        filter.reset()
        assertEquals(false,filter.snapshot()["firing"])
        exact(-1f,filter.filterX(-1f,1_000_000L))
        filter.setFiring(true)
        exact(firing().filterX(1f,2_000_000L),filter.filterX(1f,2_000_000L))
        filter.configure(false)
        exact(1f,filter.filterX(1f,3_000_000L))
        assertEquals(false,filter.snapshot()["enabled"])
        assertEquals(false,filter.snapshot()["firing"])
        assertEquals(0L,filter.snapshot()["activeSamples"])
    }

    @Test fun zeroClearsTheEstimateAndCannotCreateAnyAutonomousMovement() {
        val filter=firing()
        repeat(8) { filter.filterX(1f,it*8_000_000L) }
        exact(-0f,filter.filterX(-0f,65_000_000L))
        exact(0f,filter.filterX(0f,66_000_000L))
        exact(firing().filterX(-1f,67_000_000L),filter.filterX(-1f,67_000_000L))
    }

    @Test fun activeNonFiniteSamplesAreRejectedToZeroAndClearState() {
        val filter=firing()
        for(value in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY)) {
            exact(0f,filter.filterX(value,1_000_000L))
        }
        exact(firing().filterX(1f,2_000_000L),filter.filterX(1f,2_000_000L))
        assertEquals(3L,filter.snapshot()["invalidSamples"])
    }

    @Test fun missingDuplicateOrOutOfOrderTimeCannotReuseAStaleSpeedEstimate() {
        val filter=firing()
        filter.filterX(1f,10_000_000L)
        exact(-1f,filter.filterX(-1f,9_000_000L))
        val initial=firing().filterX(1f,20_000_000L)
        exact(initial,filter.filterX(1f,20_000_000L))
        exact(1f,filter.filterX(1f,20_000_000L))
        exact(-1f,filter.filterX(-1f,Long.MIN_VALUE))
        exact(firing().filterX(-1f,30_000_000L),filter.filterX(-1f,30_000_000L))
        assertEquals(3L,filter.snapshot()["timingResets"])
    }

    @Test fun aLongIdleGapPassesTheNewIntentAndDropsTheOldEstimate() {
        val filter=firing()
        filter.filterX(1f,0L)
        exact(-1f,filter.filterX(-1f,200_000_000L))
        assertEquals(1L,filter.snapshot()["timingResets"])
        exact(firing().filterX(-1f,208_000_000L),filter.filterX(-1f,208_000_000L))
    }

    @Test fun timeBasedEstimateSettlesAtTheSameFineSpeedAcrossSamplingIntervals() {
        fun traverse(stepNanos:Long):Double {
            val filter=firing()
            val dx=(100.0*stepNanos/1_000_000_000.0).toFloat()
            var distance=0.0
            var time=0L
            while(time<400_000_000L) {
                distance+=filter.filterX(dx,time)
                time+=stepNanos
            }
            return distance
        }
        val at125=traverse(8_000_000L)
        val at250=traverse(4_000_000L)
        assertTrue(at125>38.0)
        assertTrue(at250>38.0)
        assertEquals(at125,at250,.5)
    }
}
