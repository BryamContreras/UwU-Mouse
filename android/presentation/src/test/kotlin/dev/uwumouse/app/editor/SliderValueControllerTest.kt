package dev.uwumouse.app.editor

import org.junit.Assert.*
import org.junit.Test

class SliderValueControllerTest {
    @Test fun buttonsChangeThenCommitExactlyOnceAndStopAtBothBounds() {
        val events=mutableListOf<String>()
        val values=SliderValueController(1,3,1,{ events+="change:$it" },{ events+="save" })
        assertFalse(values.canDecrease)
        assertFalse(values.step(-1))
        assertTrue(events.isEmpty())
        assertTrue(values.step(1))
        assertTrue(values.step(1))
        assertFalse(values.canIncrease)
        assertFalse(values.step(1))
        assertEquals(listOf("change:2","save","change:3","save"),events)
        values.step(-1)
        assertEquals(2,values.value)
    }

    @Test fun draggingUpdatesLiveAndPersistsOnlyAtReleaseWhileRefreshIsSilent() {
        val changes=mutableListOf<Int>()
        var saves=0
        val values=SliderValueController(0,100,40,{ changes+=it },{ saves++ })
        values.synchronize(70)
        values.dragTo(70)
        assertTrue(changes.isEmpty())
        values.dragTo(71);values.dragTo(72)
        assertEquals(listOf(71,72),changes)
        assertEquals(0,saves)
        values.finishDrag()
        assertEquals(1,saves)
    }

    @Test fun draftRefreshInsideChangeCanCanonicalizeWithoutAnotherCallbackOrSave() {
        val changes=mutableListOf<Int>()
        val committedValues=mutableListOf<Int>()
        lateinit var values:SliderValueController
        values=SliderValueController(0,100,50,{ requested ->
            changes+=requested
            // A geometry constraint can refresh the view to the admitted draft value.
            values.synchronize(50)
        },{ committedValues+=values.value })
        assertTrue(values.step(1))
        assertEquals(listOf(51),changes)
        assertEquals(listOf(50),committedValues)
        assertEquals(50,values.value)
    }

    @Test fun oneUnitStepsSupportSignedAndExtremeRangesWithoutOverflow() {
        val signed=SliderValueController(-100,100,-1,{}, {})
        signed.step(1);assertEquals(0,signed.value)
        signed.step(-1);assertEquals(-1,signed.value)
        val extremes=SliderValueController(Int.MIN_VALUE,Int.MAX_VALUE,Int.MAX_VALUE,{}, {})
        assertFalse(extremes.step(1))
        extremes.synchronize(Int.MIN_VALUE)
        assertFalse(extremes.step(-1))
        extremes.step(1);assertEquals(Int.MIN_VALUE+1,extremes.value)
    }
}
