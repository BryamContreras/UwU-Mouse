package dev.mobimapper.mobimapper.engine

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class PrecisionAimTuningTest {
    private fun firing()=PrecisionAimFilter().apply { configure(true);setFiring(true) }

    @Test fun alternatingJitterHasAModestAdditionalReductionAtSlowAndFastPolling() {
        for(interval in listOf(8_000_000L,1_000_000L)) {
            val filter=firing()
            var squared=0.0
            repeat(2_000) { index ->
                val input=if(index%2==0) 1f else -1f
                val output=filter.filterX(input,index*interval)
                assertTrue(output*input>0f)
                assertTrue(abs(output)<=abs(input))
                if(index>0) squared+=output.toDouble()*output
            }
            val currentRms=sqrt(squared/1_999)
            // For alternating direction the old estimate restarted every sample.
            val previousRms=1.0-.45*exp(-interval/12_000_000.0)
            assertTrue("Extra damping must remain subtle: $currentRms / $previousRms",
                currentRms/previousRms in .88.. .95)
            assertTrue("Small intentional movement must remain visible",currentRms>.5)
        }
    }

    @Test fun constantFineIntentStillConvergesAtTheSameSpeedAcrossPollingRates() {
        val distances=mutableListOf<Double>()
        for(interval in listOf(8_000_000L,1_000_000L)) {
            val filter=firing()
            val input=(100.0*interval/1_000_000_000.0).toFloat()
            var total=0.0
            var last=0f
            var time=0L
            while(time<2_000_000_000L) {
                last=filter.filterX(input,time)
                assertTrue(last>0f && last<=input)
                total+=last
                time+=interval
            }
            assertTrue(last/input>.999f)
            assertTrue(total>198.5)
            distances+=total
        }
        assertEquals(distances[0],distances[1],.75)
    }

    @Test fun repeatedAndChangingIntervalsMatchUncachedMathematicsBitForBit() {
        val filter=firing()
        val intervals=longArrayOf(100_000L,500_000L,1_000_000L,1_000_000L,1_000_000L,
            3_000_000L,3_000_000L,32_000_000L,50_000_000L)
        var time=0L
        var previousTime=0L
        var previousDirection=0
        var speed=0.0
        repeat(10_000) { index ->
            if(index>0) time+=intervals[index%intervals.size]
            val input=(if(index%13<7) 1f else -1f)*(1f+(index%5)*.25f)
            val direction=if(input>0) 1 else -1
            if(direction!=previousDirection) speed=0.0
            val dt=(if(index==0) 8_000_000L else time-previousTime).coerceIn(500_000L,32_000_000L)
            val seconds=dt/1_000_000_000.0
            val magnitude=abs(input.toDouble())
            speed+=(magnitude/seconds-speed)*(1.0-exp(-dt/14_000_000.0))
            val damped=minOf(magnitude,speed*seconds)
            val weight=.50*((8.0-magnitude)/6.0).coerceIn(0.0,1.0)
            val expected=((magnitude+(damped-magnitude)*weight).coerceIn(0.0,magnitude)*direction).toFloat()
            assertEquals("Sample $index",expected.toRawBits(),filter.filterX(input,time).toRawBits())
            previousDirection=direction;previousTime=time
        }
    }
}
