package dev.uwumouse.capture.api

import org.junit.Assert.*
import org.junit.Test

class PhysicalMouseBatchTest {
    @Test fun sourceSamplesAndDeviceIdentityRemainSeparateEvenWhenTimeOrderCrossesDevices() {
        val batch=PhysicalMouseBatch(longArrayOf(20,10),intArrayOf(15,20),arrayOf("/dev/input/event15","/dev/input/event20"),
            floatArrayOf(1f,-1f),floatArrayOf(2f,3f),wheel=.25f,buttonState=1)
        assertArrayEquals(longArrayOf(20,10),batch.eventTimesNanos)
        assertEquals(.25f,batch.wheel,0f)
        assertEquals(1,batch.buttonState)
    }

    @Test fun mismatchedOversizedOrInvalidSamplesCannotReachTheConsumer() {
        fun batch(times:LongArray=longArrayOf(1),ids:IntArray=intArrayOf(15),xs:FloatArray=floatArrayOf(1f),buttons:Int=0)=
            PhysicalMouseBatch(times,ids,Array(times.size){"/dev/input/event15"},xs,FloatArray(times.size),buttonState=buttons)
        assertThrows(IllegalArgumentException::class.java){batch(times=longArrayOf())}
        assertThrows(IllegalArgumentException::class.java){batch(times=LongArray(257){1})}
        assertThrows(IllegalArgumentException::class.java){batch(ids=intArrayOf())}
        assertThrows(IllegalArgumentException::class.java){batch(xs=floatArrayOf(Float.NaN))}
        assertThrows(IllegalArgumentException::class.java){batch(times=longArrayOf(-1))}
        assertThrows(IllegalArgumentException::class.java){batch(buttons=32)}
    }

    @Test fun maximumGamingMouseBatchRemainsBoundedAndPreservesFractionalDisplacement() {
        val count=PhysicalMouseBatch.MAX_SAMPLES
        val batch=PhysicalMouseBatch(LongArray(count){it.toLong()},IntArray(count){15},Array(count){"/dev/input/event15"},
            FloatArray(count){.125f},FloatArray(count){-.25f})
        assertEquals(32f,batch.relXs.sum(),0f)
        assertEquals(-64f,batch.relYs.sum(),0f)
    }
}
