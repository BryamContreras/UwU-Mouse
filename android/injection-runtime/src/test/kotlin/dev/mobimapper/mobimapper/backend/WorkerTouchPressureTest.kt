package dev.mobimapper.mobimapper.backend

import org.junit.Assert.*
import org.junit.Test

class WorkerTouchPressureTest {
    @Test fun pointerUpZerosOnlyTheLiftedIndexAtEveryContactCount() {
        for(count in 2..10) for(lifted in 0 until count) {
            val action=6 or (lifted shl 8)
            for(index in 0 until count) assertEquals(
                if(index==lifted) 0f else 1f,WorkerTouchPressure.forPointer(action,index),0f)
        }
    }

    @Test fun actionIndexDeterminesReleaseIndependentlyOfPointerId() {
        val ids=intArrayOf(1,8,9)
        val action=6 or (1 shl 8) // ID 8 is lifted while ordinary ID 1 and camera ID 9 stay down.
        val pressures=FloatArray(ids.size) { WorkerTouchPressure.forPointer(action,it) }
        assertArrayEquals(floatArrayOf(1f,0f,1f),pressures,0f)
        assertArrayEquals(intArrayOf(1,8,9),ids)
    }

    @Test fun finalUpAndCancelHaveNoRemainingPressedPointer() {
        for(action in listOf(1,3)) for(index in 0 until 10) {
            assertEquals(0f,WorkerTouchPressure.forPointer(action,index),0f)
        }
    }

    @Test fun downPointerDownAndMoveRestorePressureAfterReusableCoordinatesWereReleased() {
        val reusable=FloatArray(10) { 1f }
        for(action in listOf(3,0,6 or (8 shl 8),2,1,5 or (9 shl 8),2)) {
            for(index in reusable.indices) reusable[index]=WorkerTouchPressure.forPointer(action,index)
            if(action and 255 in listOf(0,2,5)) assertArrayEquals(FloatArray(10) { 1f },reusable,0f)
            if(action==1 || action==3) assertArrayEquals(FloatArray(10),reusable,0f)
        }
    }

    @Test fun unsupportedActionOrPointerStorageCannotProduceAContactPressure() {
        assertThrows(IllegalArgumentException::class.java) { WorkerTouchPressure.forPointer(4,0) }
        assertThrows(IllegalArgumentException::class.java) { WorkerTouchPressure.forPointer(2,-1) }
        assertThrows(IllegalArgumentException::class.java) { WorkerTouchPressure.forPointer(2,10) }
    }
}
