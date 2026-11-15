package dev.uwumouse.app.backend

import dev.uwumouse.app.engine.TouchComposer
import dev.uwumouse.app.engine.TouchFrame
import org.junit.Assert.*
import org.junit.Test

class TouchEventAgePolicyTest {
    private val frame=TouchFrame(TouchComposer.MOVE,100,110,intArrayOf(9),floatArrayOf(300f),floatArrayOf(400f))

    @Test fun exactBoundaryMatchesTheOrdinaryInjectionAgeRequirement() {
        assertTrue(TouchEventAgePolicy.admits(frame,110))
        assertTrue(TouchEventAgePolicy.admits(frame,110+TouchEventAgePolicy.MAX_AGE_MILLIS-1))
        assertFalse(TouchEventAgePolicy.admits(frame,110+TouchEventAgePolicy.MAX_AGE_MILLIS))
        assertFalse(TouchEventAgePolicy.admits(frame,110+TouchEventAgePolicy.MAX_AGE_MILLIS+1))
    }

    @Test fun futureNegativeAndOverflowingAgesNeverPass() {
        assertFalse(TouchEventAgePolicy.admits(frame,109))
        assertFalse(TouchEventAgePolicy.admits(frame,-1))
        assertFalse(TouchEventAgePolicy.admits(frame.copy(eventTime=-1),0))
        assertFalse(TouchEventAgePolicy.admits(frame.copy(eventTime=0),Long.MAX_VALUE))
        assertTrue(TouchEventAgePolicy.admits(frame.copy(eventTime=Long.MAX_VALUE),Long.MAX_VALUE))
    }
}
