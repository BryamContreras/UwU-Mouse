package dev.mobimapper.mobimapper.session

import org.junit.Assert.*
import org.junit.Test

class WindowSelectionPolicyTest {
    private val ownPackage="dev.mobimapper.mobimapper"
    private val editorFrame=ContextWindowFrame(0,5,0,0,2316,1080)

    @Test fun focusedOwnEditorOverlayCanSupplyEvidenceWhenActiveRootIsMissing() {
        assertTrue(WindowSelectionPolicy.ownsFocusedEditorOverlay(true,4,ownPackage,ownPackage,editorFrame,true))
        assertTrue(SessionPolicy.authorizedContext("game",null,true,true))
    }
    @Test fun ownActivityUnknownOwnerAndMerelyActiveOverlayAreNotEditorFocusEvidence() {
        assertFalse(WindowSelectionPolicy.ownsFocusedEditorOverlay(true,1,ownPackage,ownPackage,editorFrame,true))
        assertFalse(WindowSelectionPolicy.ownsFocusedEditorOverlay(true,4,null,ownPackage,editorFrame,true))
        assertFalse(WindowSelectionPolicy.ownsFocusedEditorOverlay(true,4,"other",ownPackage,editorFrame,true))
        assertFalse(WindowSelectionPolicy.ownsFocusedEditorOverlay(true,4,ownPackage,ownPackage,editorFrame,false))
    }
    @Test fun hiddenOtherDisplayOrEmptyEditorCannotKeepContext() {
        assertFalse(WindowSelectionPolicy.ownsFocusedEditorOverlay(false,4,ownPackage,ownPackage,editorFrame,true))
        assertFalse(WindowSelectionPolicy.ownsFocusedEditorOverlay(true,4,ownPackage,ownPackage,editorFrame.copy(displayId=1),true))
        assertFalse(WindowSelectionPolicy.ownsFocusedEditorOverlay(true,4,ownPackage,ownPackage,editorFrame.copy(right=0),true))
    }
    @Test fun externalEditorFocusEvidenceMustBelongToTheSelectedDisplay() {
        val externalEditor=editorFrame.copy(displayId=2)
        assertTrue(WindowSelectionPolicy.ownsFocusedEditorOverlay(true,4,ownPackage,ownPackage,externalEditor,true,displayId=2))
        assertFalse(WindowSelectionPolicy.ownsFocusedEditorOverlay(true,4,ownPackage,ownPackage,editorFrame,true,displayId=2))
        assertFalse(WindowSelectionPolicy.ownsFocusedEditorOverlay(true,4,ownPackage,ownPackage,externalEditor.copy(displayId=3),true,displayId=2))
        assertFalse(WindowSelectionPolicy.ownsFocusedEditorOverlay(true,4,ownPackage,ownPackage,externalEditor,true))
    }
    @Test fun editorFocusNeverAuthorizesOwnActivityOtherApplicationMissingTargetOrSystemCover() {
        val ownEditor=WindowSelectionPolicy.ownsFocusedEditorOverlay(true,4,ownPackage,ownPackage,editorFrame,true)
        assertFalse(SessionPolicy.authorizedContext("game",ownPackage,ownEditor,true))
        assertFalse(SessionPolicy.authorizedContext("game","settings",ownEditor,true))
        assertFalse(SessionPolicy.authorizedContext("game",null,ownEditor,false))
        assertFalse(SessionPolicy.authorizedContext("game",null,ownEditor,true,true))
    }
    private fun window(id:Int,width:Int=2316,height:Int=1080,layer:Int=1,focused:Boolean=false,active:Boolean=false,
                       owner:String?="game",display:Int=0)=ApplicationWindowCandidate(id,owner,
        ContextWindowFrame(display,layer,0,0,width,height),focused,active)

    @Test fun focusedPopupDoesNotReplaceLargerMainSurfaceRegardlessOfListOrder() {
        val main=window(1);val popup=window(2,500,300,5,focused=true,active=true)
        assertEquals(main,WindowSelectionPolicy.selectTarget(listOf(popup,main),"game",null))
        assertEquals(main,WindowSelectionPolicy.selectTarget(listOf(main,popup),"game",2))
    }
    @Test fun selectedExternalDisplayIgnoresLargerPhoneWindowsAndKeepsTheMainSurface() {
        val phone=window(1,3840,2160,layer=8,focused=true,active=true)
        val externalMain=window(2,1920,1080,display=2)
        val externalPopup=window(3,600,400,layer=5,focused=true,active=true,display=2)
        val anotherDisplay=window(4,3840,2160,layer=9,display=3)
        val candidates=listOf(phone,externalPopup,anotherDisplay,externalMain)
        assertEquals(externalMain,WindowSelectionPolicy.selectTarget(candidates,"game",phone.id,displayId=2))
        assertEquals(externalMain,WindowSelectionPolicy.selectTarget(candidates.reversed(),"game",externalPopup.id,displayId=2))
        assertNull(WindowSelectionPolicy.selectTarget(listOf(phone,anotherDisplay),"game",externalMain.id,displayId=2))
        assertEquals(phone,WindowSelectionPolicy.selectTarget(candidates,"game",externalMain.id))
    }
    @Test fun equalAreaRetainsPreviousWindowAcrossFocusAndLayerChanges() {
        val main=window(1);val duplicate=window(2,layer=5,focused=true,active=true)
        assertEquals(main,WindowSelectionPolicy.selectTarget(listOf(duplicate,main),"game",1))
        assertEquals(main,WindowSelectionPolicy.selectTarget(listOf(main,duplicate),"game",1))
        assertEquals(duplicate,WindowSelectionPolicy.selectTarget(listOf(main,duplicate),"game",null))
    }
    @Test fun vanishedOrInvalidWindowDoesNotReturnStaleBounds() {
        assertNull(WindowSelectionPolicy.selectTarget(emptyList(),"game",1))
        assertNull(WindowSelectionPolicy.selectTarget(listOf(window(1,width=0),window(2,owner="other"),window(3,display=2)),"game",1))
        val replacement=window(4)
        assertEquals(replacement,WindowSelectionPolicy.selectTarget(listOf(replacement),"game",1))
    }
    @Test fun sameVisibleIdCanRetainKnownOwnershipThroughAnUnresolvedRoot() {
        val first=WindowSelectionPolicy.resolveOwners(listOf(WindowOwner(1,"game"),WindowOwner(2,"system")),emptyMap())
        val next=WindowSelectionPolicy.resolveOwners(listOf(WindowOwner(1,null),WindowOwner(3,null)),first)
        assertEquals(mapOf(1 to "game"),next)
        val gone=WindowSelectionPolicy.resolveOwners(emptyList(),next)
        assertTrue(WindowSelectionPolicy.resolveOwners(listOf(WindowOwner(1,null)),gone).isEmpty())
    }
    @Test fun resolvedNewOwnerSupersedesCachedOwnershipAndUnknownRootsRemainUnknown() {
        val resolved=WindowSelectionPolicy.resolveOwners(listOf(WindowOwner(1,"other"),WindowOwner(2,null)),mapOf(1 to "game"))
        assertEquals(mapOf(1 to "other"),resolved)
        assertNull(WindowSelectionPolicy.selectTarget(listOf(window(1,owner=resolved[1]),window(2,owner=resolved[2])),"game",1))
    }
}
