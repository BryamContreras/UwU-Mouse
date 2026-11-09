package dev.mobimapper.mobimapper.backend

import org.junit.Assert.*
import org.junit.Test

class VisualCursorRouterTest {
    @Test fun failedOrdinaryTrustedHideRemainsPendingAndCanBeRetried() {
        val events=mutableListOf<String>()
        val trusted=Renderer("trusted",events)
        val application=Renderer("application",events)
        val router=VisualCursorRouter(trusted,application)
        assertTrue(router.show(0,10f,20f,20f))
        trusted.hideFailure=IllegalStateException()
        assertThrows(IllegalStateException::class.java) { router.hide() }
        assertTrue(router.cleanupPending)
        assertEquals("trusted_cleanup_IllegalStateException",router.error)
        assertFalse(router.show(0,11f,21f,20f))
        assertEquals(1,trusted.publications.size)
        assertTrue(application.publications.isEmpty())
        trusted.hideFailure=null
        router.hide()
        assertFalse(router.cleanupPending)
        assertTrue(router.show(0,12f,22f,20f))
        assertEquals("trusted",router.route)
    }

    @Test fun failedOrdinaryApplicationHideCannotBeReportedAsComplete() {
        val events=mutableListOf<String>()
        val trusted=Renderer("trusted",events).apply { admitted=false }
        val application=Renderer("application",events)
        val router=VisualCursorRouter(trusted,application)
        assertTrue(router.show(17,10f,20f,20f))
        application.hideFailure=IllegalArgumentException()
        assertThrows(IllegalStateException::class.java) { router.hide() }
        assertTrue(router.cleanupPending)
        assertFalse(router.show(17,11f,21f,20f))
        assertEquals(1,application.publications.size)
        application.hideFailure=null
        router.hide()
        assertFalse(router.cleanupPending)
        assertTrue(router.show(17,12f,22f,20f))
        assertEquals("application",router.route)
    }

    private data class Position(val x:Float,val y:Float,val size:Float)
    private data class Publication(val display:Int,val position:Position)
    private class BackendDisconnectedException:Exception()
    private class Renderer(private val name:String,private val events:MutableList<String>):VisualCursorRenderer {
        val publications=mutableListOf<Publication>()
        val moves=mutableListOf<Position>()
        var admitted=true
        var showFailure:Exception?=null
        var moveFailure:Exception?=null
        var hideFailure:Exception?=null
        var hides=0
        var refreshes=0
        override fun show(displayId:Int,x:Float,y:Float,sizeDp:Float):Boolean {
            events+="$name.show"
            publications+=Publication(displayId,Position(x,y,sizeDp))
            showFailure?.let { throw it }
            return admitted
        }
        override fun move(x:Float,y:Float,sizeDp:Float) {
            events+="$name.move"
            moves+=Position(x,y,sizeDp)
            moveFailure?.let { throw it }
        }
        override fun refreshWindowOpacity() { refreshes++ }
        override fun hide() { events+="$name.hide";hides++;hideFailure?.let { throw it } }
    }

    @Test fun admittedTrustedCursorPreservesTheExistingOpaqueRouteAndReleasesItOnce() {
        val events=mutableListOf<String>()
        val trusted=Renderer("trusted",events)
        val application=Renderer("application",events)
        val router=VisualCursorRouter(trusted,application)
        assertTrue(router.show(4,120f,90f,20f))
        router.move(121f,91f,24f)
        router.refreshWindowOpacity()
        assertEquals("trusted",router.route)
        assertEquals(listOf(Publication(4,Position(120f,90f,20f))),trusted.publications)
        assertEquals(listOf(Position(121f,91f,24f)),trusted.moves)
        assertEquals(1,trusted.refreshes)
        assertTrue(application.publications.isEmpty())
        router.hide();router.hide()
        assertEquals(1,trusted.hides)
        assertEquals(0,application.hides)
        assertEquals("none",router.route)
    }

    @Test fun rejectedTrustedCapabilityIsCleanedBeforeFallbackAndCachedForThatRouter() {
        val events=mutableListOf<String>()
        val trusted=Renderer("trusted",events).apply { admitted=false }
        val application=Renderer("application",events)
        val router=VisualCursorRouter(trusted,application)
        assertTrue(router.show(17,200f,100f,30f))
        assertEquals(listOf("trusted.show","trusted.hide","application.show"),events)
        assertEquals("application",router.route)
        assertEquals("window_not_admitted",router.trustedError)
        assertEquals("",router.error)
        assertEquals(Publication(17,Position(200f,100f,30f)),application.publications.single())
        router.hide()
        assertTrue(router.show(17,210f,110f,30f))
        assertEquals(1,trusted.publications.size)
        assertEquals(2,application.publications.size)
        router.move(215f,115f,30f);router.refreshWindowOpacity()
        assertEquals(listOf(Position(215f,115f,30f)),application.moves)
        assertEquals(1,application.refreshes)
    }

    @Test fun thrownTrustedAdmissionDoesNotPreventTheApplicationCursor() {
        val events=mutableListOf<String>()
        val trusted=Renderer("trusted",events).apply { showFailure=SecurityException() }
        val application=Renderer("application",events)
        val router=VisualCursorRouter(trusted,application)
        assertTrue(router.show(0,20f,40f,20f))
        assertEquals("SecurityException",router.trustedError)
        assertEquals(listOf("trusted.show","trusted.hide","application.show"),events)
        router.hide();router.hide()
        assertEquals(1,application.hides)
    }

    @Test fun lostTrustedBinderRecoversAtTheLatestHotspotAndDoesNotRetryTheBrokenRoute() {
        val events=mutableListOf<String>()
        val trusted=Renderer("trusted",events)
        val application=Renderer("application",events)
        val router=VisualCursorRouter(trusted,application)
        assertTrue(router.show(12,10f,20f,20f))
        trusted.moveFailure=BackendDisconnectedException()
        router.move(300f,400f,28f)
        assertEquals("application",router.route)
        assertEquals("BackendDisconnectedException",router.trustedError)
        assertEquals(Publication(12,Position(300f,400f,28f)),application.publications.single())
        assertEquals(listOf("trusted.show","trusted.move","trusted.hide","application.show"),events)
        router.move(301f,401f,28f)
        router.hide()
        assertTrue(router.show(12,302f,402f,28f))
        assertEquals(1,trusted.publications.size)
        assertEquals(1,trusted.moves.size)
        assertEquals(listOf(Position(301f,401f,28f)),application.moves)
    }

    @Test fun rejectionOfBothWindowsNeverAdvertisesAVisibleRoute() {
        val events=mutableListOf<String>()
        val trusted=Renderer("trusted",events).apply { admitted=false }
        val application=Renderer("application",events).apply { admitted=false }
        val router=VisualCursorRouter(trusted,application)
        assertFalse(router.show(17,100f,200f,20f))
        assertEquals("none",router.route)
        assertEquals("application_window_not_admitted",router.error)
        assertEquals(listOf("trusted.show","trusted.hide","application.show","application.hide"),events)
        router.hide();router.move(101f,201f,20f)
        assertEquals(1,trusted.hides)
        assertEquals(1,application.hides)
        assertTrue(trusted.moves.isEmpty());assertTrue(application.moves.isEmpty())
    }

    @Test fun failedRecoveryPropagatesSoTheOwnerCanReleaseInputCapture() {
        val events=mutableListOf<String>()
        val trusted=Renderer("trusted",events)
        val application=Renderer("application",events).apply { showFailure=SecurityException() }
        val router=VisualCursorRouter(trusted,application)
        assertTrue(router.show(0,1f,2f,20f))
        trusted.moveFailure=BackendDisconnectedException()
        assertThrows(IllegalStateException::class.java) { router.move(3f,4f,20f) }
        assertEquals("none",router.route)
        assertEquals("SecurityException",router.error)
        assertEquals(1,trusted.hides);assertEquals(1,application.hides)
    }

    @Test fun applicationMoveFailureRemainsObservableAndTheOwnerCanStillRemoveItsWindow() {
        val events=mutableListOf<String>()
        val trusted=Renderer("trusted",events).apply { admitted=false }
        val application=Renderer("application",events)
        val router=VisualCursorRouter(trusted,application)
        assertTrue(router.show(0,1f,2f,20f))
        application.moveFailure=IllegalArgumentException()
        assertThrows(IllegalArgumentException::class.java) { router.move(3f,4f,20f) }
        assertEquals("IllegalArgumentException",router.error)
        router.hide();router.hide()
        assertEquals(1,application.hides)
        assertEquals("none",router.route)
    }

    @Test fun aNewBackendOrDisplayOwnerProbesTheTrustedCapabilityAgain() {
        val events=mutableListOf<String>()
        val trusted=Renderer("trusted",events).apply { admitted=false }
        val application=Renderer("application",events)
        val first=VisualCursorRouter(trusted,application)
        assertTrue(first.show(0,1f,2f,20f));first.hide()
        trusted.admitted=true
        val replacement=VisualCursorRouter(trusted,application)
        assertTrue(replacement.show(17,3f,4f,20f))
        assertEquals("trusted",replacement.route)
        assertEquals(2,trusted.publications.size)
        assertEquals(1,application.publications.size)
    }

    @Test fun unconfirmedTrustedCleanupPreventsFallbackOnEverySubsequentShow() {
        val events=mutableListOf<String>()
        val trusted=Renderer("trusted",events).apply {
            showFailure=BackendDisconnectedException();hideFailure=BackendDisconnectedException()
        }
        val application=Renderer("application",events)
        val router=VisualCursorRouter(trusted,application)
        assertFalse(router.show(0,1f,2f,20f))
        assertFalse(router.show(0,3f,4f,20f))
        assertEquals("none",router.route)
        assertTrue(router.error.startsWith("trusted_cleanup_"))
        assertTrue(application.publications.isEmpty())
        trusted.hideFailure=null
        assertTrue(router.show(0,5f,6f,20f))
        assertEquals("application",router.route)
        assertEquals(1,trusted.publications.size)
        assertEquals(1,application.publications.size)
    }

    @Test fun failedCleanupDuringMoveAlsoBlocksLaterFallbackUntilItCanBeConfirmed() {
        val events=mutableListOf<String>()
        val trusted=Renderer("trusted",events)
        val application=Renderer("application",events)
        val router=VisualCursorRouter(trusted,application)
        assertTrue(router.show(17,1f,2f,20f))
        trusted.moveFailure=BackendDisconnectedException();trusted.hideFailure=BackendDisconnectedException()
        assertThrows(IllegalStateException::class.java) { router.move(3f,4f,20f) }
        assertFalse(router.show(17,5f,6f,20f))
        assertTrue(application.publications.isEmpty())
        trusted.hideFailure=null
        assertTrue(router.show(17,7f,8f,20f))
        assertEquals(Publication(17,Position(7f,8f,20f)),application.publications.single())
    }
}
