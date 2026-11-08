package dev.mobimapper.mobimapper.engine

import org.junit.Assert.*
import org.junit.Test

class CameraResetSequenceTest {
    private fun plan(held:Boolean=true):List<TouchFrame> {
        val ids=if(held) intArrayOf(0,8) else intArrayOf(8)
        val xs=if(held) floatArrayOf(100f,860f) else floatArrayOf(860f)
        val ys=if(held) floatArrayOf(200f,800f) else floatArrayOf(800f)
        val up=TouchFrame(if(held) 262 else 1,1000L,1100L,ids,xs,ys)
        val down=up.copy(action=if(held) 261 else 0,downTime=if(held) 1000L else 1101L,eventTime=1101L,
            ids=ids.copyOf().apply { this[lastIndex]=9 },xs=xs.copyOf().apply { this[lastIndex]=690f })
        return listOf(up,down,down.copy(action=2,eventTime=1102L,xs=down.xs.copyOf().apply { this[lastIndex]=692f }))
    }

    @Test fun validatesAndDetachesItsNarrowThreeFramePlan() {
        for(held in listOf(false,true)) {
            val source=plan(held);val sequence=CameraResetSequence(source)
            source.first().xs[0]=-99f
            assertTrue(sequence.frames.first().xs[0]>=0f)
            assertEquals(3,sequence.frames.size)
        }
    }

    @Test fun rejectsChangedHeldOwnerPositionOrPointerOrder() {
        val plan=plan()
        for(changed in listOf(
            plan[1].copy(xs=plan[1].xs.copyOf().apply { this[0]+=1f }),
            plan[1].copy(ids=intArrayOf(1,9)),plan[1].copy(action=5))) {
            assertThrows(IllegalArgumentException::class.java) { CameraResetSequence(listOf(plan[0],changed,plan[2])) }
        }
    }

    @Test fun rejectsMalformedTimesActionsIdsAndMissingStages() {
        val plan=plan()
        val invalid=listOf(plan.take(2),listOf(plan[0],plan[1],plan[2].copy(action=3)),
            listOf(plan[0],plan[1],plan[2].copy(downTime=1001L)),
            listOf(plan[0],plan[1],plan[2].copy(eventTime=1099L)),
            listOf(plan[0],plan[1].copy(ids=intArrayOf(0,0)),plan[2]),
            listOf(plan[0].copy(ids=intArrayOf(0,7)),plan[1],plan[2]),
            listOf(plan[0],plan[1].copy(xs=floatArrayOf(100f,-1f)),plan[2]))
        invalid.forEach { frames -> assertThrows(IllegalArgumentException::class.java) { CameraResetSequence(frames) } }
    }

    @Test fun countsAllowOnlyAnAcknowledgedPrefixAndOnePossibleAttempt() {
        for(accepted in 0..3) for(attempted in accepted..minOf(3,accepted+1))
            assertTrue(CameraResetResult(accepted,attempted).progressKnown)
        assertThrows(IllegalArgumentException::class.java) { CameraResetResult(0,2) }
        assertThrows(IllegalArgumentException::class.java) { CameraResetResult(2,1) }
        assertFalse(CameraResetResult(0,0,false).progressKnown)
    }
}
