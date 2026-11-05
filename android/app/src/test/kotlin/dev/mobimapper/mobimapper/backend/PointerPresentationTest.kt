package dev.mobimapper.mobimapper.backend

import org.junit.Assert.*
import org.junit.Test

class PointerPresentationTest {
    @Test fun lateSourceReleaseCanRestoreAgainAfterAndroidReplacedThePointerIcon() {
        val calls=mutableListOf<Int>();val pointer=PointerPresentation { calls.add(it) }
        pointer.hide();pointer.restore();pointer.restore(force=true)
        assertEquals(listOf(0,1000,1000),calls)
        assertFalse(pointer.hidden)
    }
    @Test fun normalPauseRestoresOnceAndAnotherSessionCanHideAgain() {
        val calls=mutableListOf<Int>(); val pointer=PointerPresentation { calls.add(it) }
        pointer.restore(); assertTrue(calls.isEmpty())
        pointer.hide(); assertTrue(pointer.hidden)
        pointer.restore(); pointer.restore(); assertFalse(pointer.hidden)
        pointer.hide(); pointer.restore()
        assertEquals(listOf(0,1000,0,1000),calls)
    }
    @Test fun ambiguousHideFailureStillRequiresRestoration() {
        val calls=mutableListOf<Int>(); val pointer=PointerPresentation {
            calls.add(it); if(it==0) throw IllegalStateException("Lost reply")
        }
        pointer.hide(); assertFalse(pointer.hidden); assertFalse(pointer.error.isEmpty())
        pointer.restore(); assertEquals(listOf(0,1000),calls); assertEquals("",pointer.error)
    }
    @Test fun failedRestorationCanBeRetriedWithoutPretendingThePointerWasRestored() {
        var fail=true
        val pointer=PointerPresentation { if(it==1000 && fail) throw IllegalStateException() }
        pointer.hide(); pointer.restore(); assertTrue(pointer.hidden)
        fail=false; pointer.restore(); assertFalse(pointer.hidden); assertEquals("",pointer.error)
    }
}
