package dev.mobimapper.mobimapper.session

import org.junit.Assert.*
import org.junit.Test

class EditorPresentationPolicyTest {
    private val game="com.example.game"
    private val own="dev.mobimapper.mobimapper"
    private val frame=ContextWindowFrame(2,0,0,0,1920,1080)
    private val anchor=EditorPresentationAnchor(7,game,frame)
    private val overlay=ContextWindowCandidate(3,own,frame.copy(layer=1),focused=true,rootResolved=true)

    private fun allowed(mode:SessionMode=SessionMode.EDITING,active:Boolean=false,contacts:Int=0,
                        releaseUnverified:Boolean=false,targetPackage:String=game,targetDisplayId:Int=2,
                        editorToken:Long=7,anchor:EditorPresentationAnchor?=this.anchor,
                        brokerPackage:String?=game,brokerDisplayId:Int=2,brokerTrusted:Boolean=true,
                        age:Long=0,overlay:ContextWindowCandidate?=this.overlay)=
        SessionPolicy.mayRetainEditorPresentation(mode,active,contacts,releaseUnverified,
            targetPackage,targetDisplayId,own,editorToken,anchor,brokerPackage,brokerDisplayId,brokerTrusted,age,overlay)

    @Test fun anAnchoredOwnOverlayCanRetainOnlyTheEditorWhenTheApplicationIsAbsent() {
        assertTrue(allowed())
        assertTrue(allowed(overlay=overlay.copy(type=4)))
        // Presentation retention never manufactures an application window or injection context.
        assertFalse(SessionPolicy.authorizedContext(game,game,true,targetWindowVisible=false))
        assertFalse(CursorPointPolicy.allowsFrame(0,floatArrayOf(960f),floatArrayOf(540f),null,emptyList()))
    }

    @Test fun playingRecoveringBlockedAndStoppedStatesNeverUsePresentationRetention() {
        for(mode in SessionMode.values().filter { it!=SessionMode.EDITING }) assertFalse(allowed(mode=mode))
        assertFalse(allowed(active=true))
        assertFalse(allowed(contacts=1))
        assertFalse(allowed(contacts=-1))
        assertFalse(allowed(releaseUnverified=true))
    }

    @Test fun brokerEvidenceMustBeTrustedFreshAndForTheExactGameAndDisplay() {
        assertFalse(allowed(brokerTrusted=false))
        assertFalse(allowed(brokerPackage=null))
        assertFalse(allowed(brokerPackage="other.game"))
        assertFalse(allowed(brokerPackage=own))
        assertFalse(allowed(brokerDisplayId=0))
        assertFalse(allowed(brokerDisplayId=-1))
        assertFalse(allowed(age=-1))
        assertTrue(allowed(age=500))
        assertFalse(allowed(age=501))
    }

    @Test fun aFreshResolvedFocusedOwnWindowIsRequiredRatherThanCachedOwnership() {
        assertFalse(allowed(overlay=null))
        assertFalse(allowed(overlay=overlay.copy(focused=false)))
        assertFalse(allowed(overlay=overlay.copy(rootResolved=false)))
        assertFalse(allowed(overlay=overlay.copy(packageName=null)))
        assertFalse(allowed(overlay=overlay.copy(packageName="other.overlay")))
        for(type in listOf(-1,1,2,5)) assertFalse(allowed(overlay=overlay.copy(type=type)))
        assertFalse(allowed(overlay=overlay.copy(frame=frame.copy(displayId=0))))
    }

    @Test fun theAnchorBelongsToThisExactPresentationGameAndDisplay() {
        assertFalse(allowed(anchor=null))
        assertFalse(allowed(editorToken=0))
        assertFalse(allowed(editorToken=-1))
        assertFalse(allowed(anchor=anchor.copy(token=6)))
        assertFalse(allowed(anchor=anchor.copy(packageName="other.game")))
        assertFalse(allowed(anchor=anchor.copy(frame=frame.copy(displayId=0))))
        assertFalse(allowed(targetDisplayId=-1))
        assertFalse(allowed(targetPackage=""))
    }

    @Test fun emptyOrNegativeGeometryDoesNotProveAnApplicationOrEditorWindow() {
        for(invalid in listOf(frame.copy(left=-1),frame.copy(top=-1),frame.copy(right=0),frame.copy(bottom=0),frame.copy(left=1920))) {
            assertFalse(allowed(anchor=anchor.copy(frame=invalid)))
            assertFalse(allowed(overlay=overlay.copy(frame=invalid)))
        }
    }

    @Test fun aPhoneEditorCannotReuseAnExternalAnchorOrExternalBrokerFocus() {
        assertFalse(allowed(targetDisplayId=0))
        val phone=frame.copy(displayId=0,right=2316)
        assertTrue(allowed(targetDisplayId=0,anchor=anchor.copy(frame=phone),brokerDisplayId=0,
            overlay=overlay.copy(frame=phone)))
    }
}
