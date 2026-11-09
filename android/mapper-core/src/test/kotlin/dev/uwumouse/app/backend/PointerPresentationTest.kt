package dev.uwumouse.app.backend

import org.junit.Assert.*
import org.junit.Test

class PointerPresentationTest {
    @Test fun pausedSessionKeepsSystemPointerHiddenWhileNativeMenuOwnsTheMouse() {
        val calls=mutableListOf<Int>();val pointer=PointerPresentation { calls+=it }
        pointer.setSessionActive(true)
        pointer.setMouseCaptureActive(true)
        pointer.setSessionActive(false,forceRestore=true)
        assertTrue(pointer.hidden)
        assertFalse(calls.contains(1000))
        pointer.setMouseCaptureActive(false,forceRestore=true)
        assertEquals(1000,calls.last())
        assertFalse(pointer.hidden)
    }

    @Test fun nativeMouseCaptureCanOwnThePointerWithoutBeginningAnInjectionSession() {
        val calls=mutableListOf<Int>();val pointer=PointerPresentation { calls+=it }
        pointer.setMouseCaptureActive(true)
        assertEquals(listOf(0),calls)
        assertTrue(pointer.hidden)
        pointer.setMouseCaptureActive(false)
        assertEquals(listOf(0,1000),calls)
        assertFalse(pointer.hidden)
    }

    @Test fun endingMenuCaptureDuringResumeDoesNotRestorePointerOwnedByNewSession() {
        val calls=mutableListOf<Int>();val pointer=PointerPresentation { calls+=it }
        pointer.setMouseCaptureActive(true)
        pointer.setSessionActive(true)
        pointer.setMouseCaptureActive(false,forceRestore=true)
        assertTrue(pointer.hidden)
        assertFalse(calls.contains(1000))
        pointer.setSessionActive(false)
        assertEquals(1000,calls.last())
    }

    @Test fun forceStopDropsBothOwnersAndLateSessionReleaseCannotHideAgain() {
        val calls=mutableListOf<Int>();val pointer=PointerPresentation { calls+=it }
        pointer.setMouseCaptureActive(true)
        pointer.setSessionActive(true)
        pointer.releaseOwners()
        assertFalse(pointer.hidden)
        assertEquals(1000,calls.last())
        val restoredCalls=calls.size
        pointer.setSessionActive(false)
        pointer.setMouseCaptureActive(false)
        assertEquals(restoredCalls,calls.size)
    }

    @Test fun captureFailureStillRestoresAfterAnAmbiguousHideReply() {
        val calls=mutableListOf<Int>();val pointer=PointerPresentation {
            calls+=it
            if(it==0) throw IllegalStateException("Lost hide reply")
        }
        pointer.setMouseCaptureActive(true)
        pointer.setMouseCaptureActive(false,forceRestore=true)
        assertEquals(listOf(0,1000),calls)
        assertFalse(pointer.hidden)
        assertEquals("",pointer.error)
    }

    @Test fun laboratoryAndKeyboardOnlyCaptureDoNotHideThePointer() {
        val calls=mutableListOf<Int>();val pointer=PointerPresentation { calls+=it }
        pointer.setSessionActive(false)
        pointer.setMouseCaptureActive(false)
        assertTrue(calls.isEmpty())
    }
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
