package dev.uwumouse.app.session

import org.junit.Assert.*
import org.junit.Test

class SessionPolicyTest {
    private val target=ContextWindowFrame(0,3,75,0,2316,1080)
    private val targetPackage="com.example.game"
    private val ownPackage="dev.uwumouse.app"
    private fun external(type:Int=-1,owner:String?="com.samsung.android.game.gametools",
                         frame:ContextWindowFrame=target.copy(layer=5,left=0))=ContextWindowCandidate(type,owner,frame)

    @Test fun unknownAndKnownSystemFullCoversBlockAuthorizationAndTheSameDownPoints() {
        for(type in listOf(-1,3)) {
            // Focus/activity are intentionally irrelevant: a nonfocusable external cover still blocks DOWN.
            val candidate=external(type)
            val candidates=listOf(candidate)
            val blocked=candidates.filter { SessionPolicy.potentiallyBlockingWindow(target,it,targetPackage,ownPackage) }
            val cover=SessionPolicy.coveringWindow(target,candidates,targetPackage,ownPackage)
            assertEquals(candidate,cover)
            assertEquals(listOf(candidate),blocked)
            assertFalse(SessionPolicy.authorizedContext(targetPackage,targetPackage,false,true,cover!=null))
            assertFalse(SessionPolicy.authorizedContext(targetPackage,null,true,true,cover!=null))
            val blockedPoints=blocked.map { PointerBounds(it.frame.left.toFloat(),it.frame.top.toFloat(),it.frame.right.toFloat(),it.frame.bottom.toFloat()) }
            assertFalse(CursorPointPolicy.allowsFrame(0,floatArrayOf(1657f),floatArrayOf(480f),
                PointerBounds(75f,0f,2316f,1080f),blockedPoints))
        }
    }

    @Test fun disappearanceOfUnknownCoverPermitsAuthorizationWithoutForegroundFallback() {
        val before=SessionPolicy.coveringWindow(target,listOf(external()),targetPackage,ownPackage)
        assertFalse(SessionPolicy.authorizedContext(targetPackage,targetPackage,false,true,before!=null))
        val after=SessionPolicy.coveringWindow(target,emptyList(),targetPackage,ownPackage)
        assertNull(after)
        assertTrue(SessionPolicy.authorizedContext(targetPackage,targetPackage,false,true,after!=null))
        assertFalse(SessionPolicy.authorizedContext(targetPackage,targetPackage,false,false,after!=null))
        assertFalse(SessionPolicy.authorizedContext(targetPackage,"other.app",false,true,after!=null))
    }

    @Test fun externalCoverSelectorExcludesOwnTargetLowerAndOtherDisplayWindows() {
        val excluded=listOf(
            external(owner=ownPackage),external(owner=targetPackage),
            external(type=1,owner=ownPackage),external(type=1,owner=targetPackage),
            external(frame=target.copy(layer=2)),external(frame=target.copy(layer=target.layer)),
            external(frame=target.copy(displayId=2,layer=8)),
        )
        for(candidate in excluded) {
            assertFalse(SessionPolicy.potentiallyBlockingWindow(target,candidate,targetPackage,ownPackage))
            assertNull(SessionPolicy.coveringWindow(target,listOf(candidate),targetPackage,ownPackage))
        }
        assertNull(SessionPolicy.coveringWindow(target,excluded,targetPackage,ownPackage))
        assertNull(SessionPolicy.coveringWindow(target.copy(displayId=2),listOf(external()),targetPackage,ownPackage))
    }

    @Test fun fullApplicationWindowFromAnotherPackageStillBlocksTheGame() {
        val application=external(type=1,owner="other.application")
        assertTrue(SessionPolicy.potentiallyBlockingWindow(target,application,targetPackage,ownPackage))
        val cover=SessionPolicy.coveringWindow(target,listOf(application),targetPackage,ownPackage)
        assertEquals(application,cover)
        assertFalse(SessionPolicy.authorizedContext(targetPackage,targetPackage,false,true,cover!=null))
    }

    @Test fun dexApplicationTypeCaptionRejectsIts36PixelStripWithoutBlockingTheWholeGame() {
        val game=ContextWindowFrame(2,0,0,0,1920,1080)
        val caption=ContextWindowCandidate(1,"com.android.systemui",ContextWindowFrame(2,1,0,0,1920,36))
        assertTrue(SessionPolicy.potentiallyBlockingWindow(game,caption,targetPackage,ownPackage))
        val cover=SessionPolicy.coveringWindow(game,listOf(caption),targetPackage,ownPackage)
        assertNull(cover)
        assertTrue(SessionPolicy.authorizedContext(targetPackage,targetPackage,false,true,cover!=null))
        val bounds=PointerBounds(0f,0f,1920f,1080f)
        val blocked=listOf(PointerBounds(0f,0f,1920f,36f))
        assertFalse(CursorPointPolicy.allowsFrame(0,floatArrayOf(100f),floatArrayOf(18f),bounds,blocked))
        assertTrue(CursorPointPolicy.allowsFrame(0,floatArrayOf(100f),floatArrayOf(36f),bounds,blocked))
        assertTrue(CursorPointPolicy.allowsFrame(0,floatArrayOf(960f),floatArrayOf(540f),bounds,blocked))
    }

    @Test fun externalGameIsBlockedOnlyByCoversOnItsOwnDisplay() {
        val game=target.copy(displayId=2)
        for(type in listOf(-1,3)) {
            val desktopCover=external(type,frame=game.copy(layer=5,left=0))
            val phoneCover=external(type,frame=desktopCover.frame.copy(displayId=0,layer=10))
            assertTrue(SessionPolicy.potentiallyBlockingWindow(game,desktopCover,targetPackage,ownPackage))
            assertFalse(SessionPolicy.potentiallyBlockingWindow(game,phoneCover,targetPackage,ownPackage))
            assertNull(SessionPolicy.coveringWindow(game,listOf(phoneCover),targetPackage,ownPackage))
            val cover=SessionPolicy.coveringWindow(game,listOf(phoneCover,desktopCover),targetPackage,ownPackage)
            assertEquals(desktopCover,cover)
            assertFalse(SessionPolicy.authorizedContext(targetPackage,targetPackage,false,true,cover!=null))
            assertTrue(SessionPolicy.systemWindowCoversTarget(game,desktopCover.frame,true))
            assertFalse(SessionPolicy.systemWindowCoversTarget(game,phoneCover.frame,true))
        }
    }

    @Test fun unknownOwnerAndExternalPackagesHaveNoFullCoverExemption() {
        for(owner in listOf(null,"com.samsung.android.game.gametools","another.overlay")) {
            val candidate=external(owner=owner)
            assertTrue(SessionPolicy.potentiallyBlockingWindow(target,candidate,targetPackage,ownPackage))
            assertEquals(candidate,SessionPolicy.coveringWindow(target,listOf(candidate),targetPackage,ownPackage))
        }
    }

    @Test fun partialExternalWindowsKeepContextButStillRejectDownAtCoveredPoints() {
        val candidates=listOf(
            external(frame=target.copy(layer=5,left=0,right=100)),
            external(type=3,frame=target.copy(layer=6,left=100,top=100,right=500,bottom=400)),
        )
        assertTrue(candidates.all { SessionPolicy.potentiallyBlockingWindow(target,it,targetPackage,ownPackage) })
        assertNull(SessionPolicy.coveringWindow(target,candidates,targetPackage,ownPackage))
        assertTrue(SessionPolicy.authorizedContext(targetPackage,targetPackage,false,true))
        val points=candidates.map { PointerBounds(it.frame.left.toFloat(),it.frame.top.toFloat(),it.frame.right.toFloat(),it.frame.bottom.toFloat()) }
        val game=PointerBounds(75f,0f,2316f,1080f)
        assertFalse(CursorPointPolicy.allowsFrame(0,floatArrayOf(90f),floatArrayOf(600f),game,points))
        assertFalse(CursorPointPolicy.allowsFrame(0,floatArrayOf(250f),floatArrayOf(250f),game,points))
        assertTrue(CursorPointPolicy.allowsFrame(0,floatArrayOf(800f),floatArrayOf(600f),game,points))
        assertTrue(CursorPointPolicy.allowsFrame(3,floatArrayOf(90f),floatArrayOf(600f),null,points))
    }

    @Test fun editorWaitsForAcknowledgedRelease() {
        assertFalse(SessionPolicy.mayEdit(3,false,false))
        assertFalse(SessionPolicy.mayEdit(0,true,false))
        assertFalse(SessionPolicy.mayEdit(0,false,true))
        assertTrue(SessionPolicy.mayEdit(0,false,false))
    }
    @Test fun configurationCannotReplacePlayingOrUncertainStream() {
        assertFalse(SessionPolicy.mayInstallConfiguration(SessionMode.PLAYING,0,false))
        assertFalse(SessionPolicy.mayInstallConfiguration(SessionMode.RECOVERING,0,false))
        assertFalse(SessionPolicy.mayInstallConfiguration(SessionMode.BLOCKED,0,false))
        assertFalse(SessionPolicy.mayInstallConfiguration(SessionMode.EDITING,1,false))
        assertTrue(SessionPolicy.mayInstallConfiguration(SessionMode.EDITING,0,false))
    }
    @Test fun ownEditorMayCoverGameButNeverAnotherApplication() {
        assertTrue(SessionPolicy.authorizedContext("game","game",false,true))
        assertTrue(SessionPolicy.authorizedContext("game",null,true,true))
        assertFalse(SessionPolicy.authorizedContext("game","settings",true,true))
        assertFalse(SessionPolicy.authorizedContext("game",null,true,false))
        assertFalse(SessionPolicy.authorizedContext("game",null,false,true))
        assertFalse(SessionPolicy.authorizedContext("game","game",false,false))
    }
    @Test fun fullSystemCoverBlocksEvenWhenGameKeepsApplicationFocus() {
        assertFalse(SessionPolicy.authorizedContext("game","game",false,true,systemWindowCoversTarget=true))
        assertFalse(SessionPolicy.authorizedContext("game",null,true,true,systemWindowCoversTarget=true))
        assertTrue(SessionPolicy.authorizedContext("game","game",false,true,systemWindowCoversTarget=false))
    }
    @Test fun higherSystemWindowCoveringTheWholeGameIsABarrier() {
        val game=ContextWindowFrame(0,3,75,0,2316,1080)
        assertTrue(SessionPolicy.systemWindowCoversTarget(game,ContextWindowFrame(0,5,0,0,2316,1080),true))
        assertTrue(SessionPolicy.systemWindowCoversTarget(game,game.copy(layer=4),true))
    }
    @Test fun barsAndSmallOrPartiallyCoveringWindowsDoNotBlockContext() {
        val game=ContextWindowFrame(0,3,75,0,2316,1080)
        assertFalse(SessionPolicy.systemWindowCoversTarget(game,ContextWindowFrame(0,5,0,0,75,1080),true))
        assertFalse(SessionPolicy.systemWindowCoversTarget(game,ContextWindowFrame(0,5,100,100,500,400),true))
        assertFalse(SessionPolicy.systemWindowCoversTarget(game,game.copy(layer=5,bottom=1079),true))
    }
    @Test fun otherDisplayLowerLayerAndOwnEditorAreNotSystemCovers() {
        val game=ContextWindowFrame(0,3,75,0,2316,1080)
        assertFalse(SessionPolicy.systemWindowCoversTarget(game,game.copy(displayId=2,layer=8),true))
        assertFalse(SessionPolicy.systemWindowCoversTarget(game,game.copy(layer=2),true))
        assertFalse(SessionPolicy.systemWindowCoversTarget(game,game,true))
        assertFalse(SessionPolicy.systemWindowCoversTarget(game,game.copy(layer=8),true,isOwnEditor=true))
        assertFalse(SessionPolicy.systemWindowCoversTarget(game,game.copy(layer=8),false))
    }
    @Test fun emptyBoundsAndInvalidTargetDisplayDoNotProveACover() {
        val game=ContextWindowFrame(0,3,75,0,2316,1080)
        assertFalse(SessionPolicy.systemWindowCoversTarget(game.copy(right=75),game.copy(layer=8),true))
        assertFalse(SessionPolicy.systemWindowCoversTarget(game,game.copy(layer=8,bottom=0),true))
        assertFalse(SessionPolicy.systemWindowCoversTarget(game.copy(displayId=-1),game.copy(displayId=-1,layer=8),true))
    }
}
