package dev.uwumouse.app.session

import dev.uwumouse.app.engine.PrecisionAimFilter
import org.junit.Assert.*
import org.junit.Test

class CameraSampleAgePolicyTest {
    @Test fun ageBudgetIncludesTheBoundaryAndRejectsExpiredOrFutureInput() {
        val now=1_000_000_000L
        for(age in listOf(0L,1L,CameraSampleAgePolicy.MAX_AGE_NANOS)) {
            assertEquals(age,CameraSampleAgePolicy.ageNanos(now-age,now))
            assertTrue(CameraSampleAgePolicy.admitsAge(age))
        }
        assertFalse(CameraSampleAgePolicy.admitsAge(CameraSampleAgePolicy.ageNanos(now-100_000_001L,now)))
        assertEquals(CameraSampleAgePolicy.INVALID_AGE,CameraSampleAgePolicy.ageNanos(now+1,now))
        assertEquals(CameraSampleAgePolicy.INVALID_AGE,CameraSampleAgePolicy.ageNanos(-1,now))
        assertFalse(CameraSampleAgePolicy.admitsAge(CameraSampleAgePolicy.INVALID_AGE))
    }

    @Test fun mixedDeviceTimesDiscardOnlyExpiredSamplesWithoutReorderingFreshMotion() {
        val now=1_000_000_000L
        val raw=RawMouseMotionBatch(longArrayOf(now-101_000_000L,now-1_000_000L,now-102_000_000L,now),
            floatArrayOf(100f,1f,-100f,-2f),FloatArray(4))
        val accepted=mutableListOf<Float>()
        var expired=0
        assertTrue(raw.deliverMotion { dx,_,time ->
            if(CameraSampleAgePolicy.admitsAge(CameraSampleAgePolicy.ageNanos(time,now))) accepted+=dx else expired++
            true
        })
        assertEquals(listOf(1f,-2f),accepted);assertEquals(2,expired)
    }

    @Test fun discardingSpeedHistoryPreservesHeldFireAndDoesNotReplaySuppressedMotion() {
        val filter=PrecisionAimFilter().apply { configure(true);setFiring(true) }
        filter.filterX(.1f,1_000_000L);filter.filterX(4f,2_000_000L)
        assertEquals(true,filter.snapshot()["firing"])
        filter.discardMotionHistory()
        val afterGap=filter.filterX(.25f,1_000_000_000L)
        val fresh=PrecisionAimFilter().apply { configure(true);setFiring(true) }
        assertEquals(fresh.filterX(.25f,1_000_000_000L),afterGap,0f)
        assertTrue(afterGap in 0f.. .25f)
        assertEquals(true,filter.snapshot()["firing"])
        filter.setFiring(false)
        assertEquals(-10f,filter.filterX(-10f,1_001_000_000L),0f)
    }
}
