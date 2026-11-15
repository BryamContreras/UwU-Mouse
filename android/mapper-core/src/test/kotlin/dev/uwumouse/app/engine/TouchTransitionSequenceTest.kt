package dev.uwumouse.app.engine

import org.junit.Assert.*
import org.junit.Test

class TouchTransitionSequenceTest {
    private val move=TouchFrame(TouchComposer.MOVE,100,110,intArrayOf(0,9),floatArrayOf(10f,1200f),floatArrayOf(20f,-100f))
    private fun down()=TouchFrame(TouchComposer.POINTER_DOWN or (1 shl 8),100,110,intArrayOf(0,1,9),
        floatArrayOf(10f,50f,1200f),floatArrayOf(20f,60f,-100f))

    @Test fun moveMayChangeCoordinatesButMustExtendTheExactExistingGesture() {
        val plan=TouchTransitionSequence(listOf(move,down()))
        assertTrue(plan.matchesPrevious(move.copy(eventTime=105,xs=floatArrayOf(1f,2f))))
        assertFalse(plan.matchesPrevious(move.copy(downTime=99)))
        assertFalse(plan.matchesPrevious(move.copy(eventTime=111)))
        assertFalse(plan.matchesPrevious(move.copy(ids=intArrayOf(9,0))))
    }

    @Test fun pointerUpRetainsItsCompleteAttemptAndPostEventDropsOnlyTheLiftedId() {
        val up=move.copy(action=TouchComposer.POINTER_UP)
        val plan=TouchTransitionSequence(listOf(move,up))
        assertArrayEquals(intArrayOf(0,9),plan.frames.last().ids)
        val after=requireNotNull(TouchTransitionSequence.postEvent(up))
        assertArrayEquals(intArrayOf(9),after.ids)
        assertArrayEquals(floatArrayOf(1200f),after.xs,0f)
        val only=move.copy(ids=intArrayOf(9),xs=floatArrayOf(1200f),ys=floatArrayOf(-100f))
        TouchTransitionSequence(listOf(only,only.copy(action=TouchComposer.UP)))
        assertNull(TouchTransitionSequence.postEvent(only.copy(action=TouchComposer.UP)))
    }

    @Test fun inputArraysCannotMutateDetachedPlan() {
        val first=move.copy(xs=move.xs.copyOf());val second=down()
        val sequence=TouchTransitionSequence(listOf(first,second))
        first.xs[0]=999f;second.ids[0]=8
        assertEquals(10f,sequence.frames.first().xs[0],0f)
        assertEquals(0,sequence.frames.last().ids[0])
    }

    @Test fun rejectsChangedContinuingPositionsMissingMoveNewGestureOrWrongPointerIndex() {
        val invalid=listOf(
            listOf(move),listOf(move,down(),move),listOf(move.copy(action=TouchComposer.UP),down()),
            listOf(move,down().copy(xs=floatArrayOf(11f,50f,1200f))),
            listOf(move,down().copy(action=TouchComposer.POINTER_DOWN or (3 shl 8))),
            listOf(move,move.copy(action=TouchComposer.DOWN)),
            listOf(move,move.copy(action=TouchComposer.UP)),
            listOf(move,move.copy(action=TouchComposer.POINTER_UP or (2 shl 8))),
            listOf(move,down().copy(downTime=99)),listOf(move,down().copy(eventTime=109)),
            listOf(move,down().copy(ids=intArrayOf(0,0,9))))
        for(frames in invalid) {
            try { TouchTransitionSequence(frames);fail("Accepted invalid sequence") } catch(_:IllegalArgumentException) { }
        }
    }
}
