package dev.uwumouse.app.engine

import kotlin.math.abs
import org.junit.Assert.*
import org.junit.Test

class PrecisionAimTuningTest {
    private fun firing()=PrecisionAimFilter().apply { configure(true);setFiring(true) }

    @Test fun fineAlternatingMotionKeepsTheSameReductionAtDifferentSamplingRates() {
        for(interval in listOf(1_000_000L,2_000_000L,4_000_000L,8_000_000L,10_000_000L)) {
            val filter=firing()
            val magnitude=(100.0*interval/1_000_000_000.0).toFloat()
            repeat(1000) { index ->
                val input=if(index%2==0) magnitude else -magnitude
                val output=filter.filterX(input,index*interval)
                assertTrue(output*input>0f)
                assertEquals(.6f,abs(output/input),.000001f)
            }
        }
    }

    @Test fun sustainedFineMotionRetainsPrecisionInsteadOfReturningToFullSensitivity() {
        for(interval in listOf(1_000_000L,2_000_000L,4_000_000L,8_000_000L,16_000_000L,32_000_000L)) {
            val filter=firing()
            val input=(100.0*interval/1_000_000_000.0).toFloat()
            val count=(2_000_000_000L/interval).toInt()
            var total=0.0
            var last=0f
            repeat(count) { index ->
                last=filter.filterX(input,index*interval)
                assertTrue(last>0f && last<=input)
                total+=last
            }
            assertEquals(.6f,last/input,.000001f)
            assertEquals(.6,total/(input.toDouble()*count),.002)
        }
    }

    @Test fun fastMouseSamplesNeverAlternateBetweenNominalAndActualTiming() {
        val filter=firing()
        repeat(100) { index ->
            val result=filter.filterX(4f,index*1_000_000L)
            if(index>0) assertEquals(4f.toRawBits(),result.toRawBits())
        }
        assertEquals(99L,filter.snapshot()["flickSamples"])
    }

    @Test fun fastFlickPassesImmediatelyAndNextFineSampleHasNoStoredTail() {
        val filter=firing()
        assertEquals(.6f,filter.filterX(1f,0L),.000001f)
        assertEquals(20f,filter.filterX(20f,8_000_000L),0f)
        assertEquals(.48f,filter.filterX(.8f,16_000_000L),.000001f)
        assertEquals(0f,filter.filterX(0f,24_000_000L),0f)
    }

    @Test fun transitionFromFineToFastMotionHasBoundedMonotonicGain() {
        var previous=0f
        for(speed in 200..1100 step 10) {
            val filter=firing()
            val dx=(speed*.008).toFloat()
            var output=0f
            repeat(40) { output=filter.filterX(dx,it*8_000_000L) }
            val gain=output/dx
            assertTrue(gain in .5999999f..1f)
            assertTrue(gain>=previous-.000001f)
            assertTrue(gain-previous<.03f || previous==0f)
            previous=gain
        }
        assertEquals(1f,previous,0f)
    }
}
