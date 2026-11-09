package dev.uwumouse.app.session

import org.junit.Assert.*
import org.junit.Test

class EditorFocusHandoffPolicyTest {
    private val game="com.example.game"
    private val frame=ContextWindowFrame(2,4,0,0,2560,1440)
    private val anchor=EditorPresentationAnchor(7,game,frame)
    private fun allowed(mode:SessionMode=SessionMode.EDITING,active:Boolean=false,armed:Boolean=false,
                        contacts:Int=0,release:Boolean=false,captureStopped:Boolean=true,
                        target:String=game,display:Int=2,token:Long=7,anchor:EditorPresentationAnchor?=this.anchor,
                        admitted:Long=1000,now:Long=1200,valid:Boolean=false,error:String="focused_window_not_visible",
                        metadataAge:Long=120,broker:String?=game,brokerDisplay:Int=2,brokerValid:Boolean=true,
                        queryAge:Long=1,displayReady:Boolean=true,locked:Boolean=false,foreign:Boolean=false)=
        SessionPolicy.mayRetainEditorFocusHandoff(mode,active,armed,contacts,release,captureStopped,
            target,display,token,anchor,admitted,now,valid,error,metadataAge,broker,brokerDisplay,brokerValid,
            queryAge,displayReady,locked,foreign)

    @Test fun aFreshFocusVisibilityRaceRetainsOnlyTheAlreadyAdmittedIdleEditor() {
        assertTrue(allowed())
        assertFalse(SessionPolicy.authorizedContext(game,null,false,targetWindowVisible=false))
        assertFalse(CursorPointPolicy.allowsFrame(0,floatArrayOf(200f),floatArrayOf(100f),null,emptyList()))
        assertFalse(allowed(active=true))
        assertFalse(allowed(armed=true))
        assertFalse(allowed(contacts=1))
        assertFalse(allowed(contacts=-1))
        assertFalse(allowed(release=true))
        assertFalse(allowed(captureStopped=false))
        for(mode in SessionMode.values().filter { it!=SessionMode.EDITING }) assertFalse(allowed(mode=mode))
    }

    @Test fun theAdmissionDeadlineCannotBeExtendedByRepeatedInvalidSnapshots() {
        for(now in listOf(1000L,1200L,1499L,1500L)) assertTrue(allowed(now=now))
        for(now in listOf(1501L,1600L,2000L)) assertFalse(allowed(now=now))
        assertFalse(allowed(now=999L))
        assertFalse(allowed(admitted=-1L))
        // A genuinely new admission has its own timestamp and identity; a poll does not.
        assertTrue(allowed(admitted=2000,now=2100,token=8,anchor=anchor.copy(token=8)))
    }

    @Test fun theIndependentTaskFocusMustRemainValidFreshAndOnTheExactDestination() {
        assertFalse(allowed(broker=null))
        assertFalse(allowed(broker="other.game"))
        assertFalse(allowed(broker="dev.uwumouse.app"))
        assertFalse(allowed(brokerDisplay=0))
        assertFalse(allowed(brokerDisplay=-1))
        assertFalse(allowed(brokerValid=false))
        assertTrue(allowed(queryAge=500))
        assertFalse(allowed(queryAge=501))
        assertFalse(allowed(queryAge=-1))
    }

    @Test fun unrelatedErrorsValidForeignWindowsAndUnavailableDisplaysNeverGetGrace() {
        for(error in listOf("","incomplete_window_visibility","dump_windows_unavailable","dump_displays_unavailable","no_visible_windows")) {
            assertFalse(allowed(error=error))
        }
        assertFalse(allowed(valid=true))
        assertFalse(allowed(foreign=true))
        assertFalse(allowed(displayReady=false))
        assertFalse(allowed(locked=true))
        assertTrue(allowed(metadataAge=500))
        assertFalse(allowed(metadataAge=501))
        assertFalse(allowed(metadataAge=-1))
    }

    @Test fun onlyTheExactPresentationAnchorCanSurviveItsOwnFocusHandoff() {
        assertFalse(allowed(anchor=null))
        assertFalse(allowed(token=0))
        assertFalse(allowed(token=8))
        assertFalse(allowed(anchor=anchor.copy(packageName="other.game")))
        assertFalse(allowed(anchor=anchor.copy(frame=frame.copy(displayId=0))))
        assertFalse(allowed(anchor=anchor.copy(frame=frame.copy(right=0))))
        assertFalse(allowed(target=""))
        assertFalse(allowed(display=-1))
    }
}
