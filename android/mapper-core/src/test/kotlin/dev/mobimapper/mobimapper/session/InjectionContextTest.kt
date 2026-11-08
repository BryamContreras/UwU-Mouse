package dev.mobimapper.mobimapper.session

import org.junit.Assert.*
import org.junit.Test

class InjectionContextTest {
    private val game=PointerBounds(0f,0f,2316f,1080f)
    private val bars=listOf(PointerBounds(0f,0f,75f,1080f),PointerBounds(0f,0f,2316f,24f))
    @Test fun secondPointerCannotBeAddedIntoNavigationEvenWhenTheGameWindowHasFocus() {
        assertFalse(CursorPointPolicy.allowsFrame(261,floatArrayOf(1400f,30f),floatArrayOf(500f,90f),game,bars))
        assertTrue(CursorPointPolicy.allowsFrame(261,floatArrayOf(1400f,100f),floatArrayOf(500f,90f),game,bars))
    }
    @Test fun movingAnExistingOwnerIntoASystemRegionIsBlockedBeforeInjection() {
        assertFalse(CursorPointPolicy.allowsFrame(2,floatArrayOf(1400f),floatArrayOf(10f),game,bars))
        assertFalse(CursorPointPolicy.allowsFrame(0,floatArrayOf(1400f),floatArrayOf(500f),null,emptyList()))
    }
    @Test fun releasesRemainAdmissibleWhenAWindowAppearsAboveTheOldContact() {
        for(action in listOf(1,3,262)) {
            assertTrue(CursorPointPolicy.allowsFrame(action,floatArrayOf(30f),floatArrayOf(10f),null,bars))
        }
    }
}
