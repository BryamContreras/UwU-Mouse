package dev.uwumouse.app.session

import org.junit.Assert.*
import org.junit.Test

class ResolutionSettingsPresentationPolicyTest {
    private val game=ContextWindowFrame(2,1,0,0,1920,1080)
    private val panel=ContextWindowCandidate(3,"mapper",ContextWindowFrame(2,10,100,100,700,600),true,true)
    private val app=ContextWindowCandidate(1,"game",game,false,true)
    private val ime=ContextWindowCandidate(2,"keyboard",ContextWindowFrame(2,11,0,600,1920,1080),false,true)
    private fun state()=ResolutionSettingsPresentationState(
        true,"game","mapper",2,true,false,SessionMode.PAUSED,false,false,0,false,true,20,
        listOf(app,panel),game,ResolutionSettingsPresentationAnchor("game",game),
        true,"game",2,20,true,"game",true,20)

    @Test fun pausedAndStoppedSystemPanelsRetainOnlyTheirOwnPresentation() {
        assertTrue(ResolutionSettingsPresentationPolicy.allows(state()))
        assertTrue(ResolutionSettingsPresentationPolicy.allows(state().copy(mode=SessionMode.STOPPED)))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(state().copy(active=true)))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(state().copy(armed=true)))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(state().copy(contacts=1)))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(state().copy(releaseUnverified=true)))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(state().copy(mode=SessionMode.RECOVERING)))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(state().copy(mode=SessionMode.BLOCKED)))
    }

    @Test fun realImeFocusAndCoveredApplicationUseFreshTaskAndOriginalDisplayEvidence() {
        val covered=state().copy(gameFrame=null,windows=listOf(panel.copy(focused=false),ime.copy(focused=true)))
        assertTrue(ResolutionSettingsPresentationPolicy.allows(covered))
        assertFalse("A presentation anchor cannot authorize mapping",SessionPolicy.authorizedContext(
            "game",null,false,false))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(covered.copy(anchor=null)))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(covered.copy(taskQueryValid=false)))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(covered.copy(taskPresent=false)))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(covered.copy(taskPackage="other.game")))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(covered.copy(taskAgeMs=2001)))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(covered.copy(brokerDisplayId=0)))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(covered.copy(brokerPackage="other.game")))
    }

    @Test fun foreignActivitiesPermissionSheetsAndSystemCoversAreNeverOurEditor() {
        for(window in listOf(
            ContextWindowCandidate(1,"foreign",ContextWindowFrame(2,20,200,200,600,600),true,true),
            ContextWindowCandidate(3,"android",ContextWindowFrame(2,20,200,200,600,600),true,true),
            ContextWindowCandidate(1,"mapper",ContextWindowFrame(2,20,0,0,1920,1080),true,true),
            ContextWindowCandidate(3,"foreign",ContextWindowFrame(2,20,0,0,1920,1080),false,true))) {
            assertFalse(ResolutionSettingsPresentationPolicy.allows(state().copy(windows=listOf(app,panel,window))))
        }
        assertFalse(ResolutionSettingsPresentationPolicy.allows(state().copy(brokerPackage="foreign")))
    }

    @Test fun keyboardOnOriginalDisplayDoesNotDependOnAPrimaryDisplayAssumption() {
        val phoneGame=game.copy(displayId=0,right=1080,bottom=2316)
        val phonePanel=panel.copy(frame=panel.frame.copy(displayId=0))
        val phone=state().copy(displayId=0,gameFrame=phoneGame,
            windows=listOf(app.copy(frame=phoneGame),phonePanel,ime.copy(frame=ime.frame.copy(displayId=0))),
            anchor=ResolutionSettingsPresentationAnchor("game",phoneGame),brokerDisplayId=0)
        assertTrue(ResolutionSettingsPresentationPolicy.allows(phone))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(phone.copy(gameFrame=game)))
        // Another screen's app may have global activity focus while our actual panel/game
        // remain visible; this affects presentation only and cannot confirm injection.
        val foreignOnPhone=ContextWindowCandidate(1,"foreign",phoneGame.copy(layer=30),true,true)
        assertTrue(ResolutionSettingsPresentationPolicy.allows(state().copy(
            brokerPackage="foreign",brokerDisplayId=0,windows=listOf(app,panel,foreignOnPhone))))
    }

    @Test fun removingLockingOrStalingTheOriginalDisplayClosesPresentation() {
        assertFalse(ResolutionSettingsPresentationPolicy.allows(state().copy(displayReady=false)))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(state().copy(locked=true)))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(state().copy(metadataValid=false)))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(state().copy(metadataAgeMs=751)))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(state().copy(metadataAgeMs=-1)))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(state().copy(panelVisible=false)))
        assertFalse(ResolutionSettingsPresentationPolicy.allows(state().copy(windows=listOf(app))))
    }

    @Test fun resizedRealGameWindowDoesNotBorrowTheOldAnchorsInjectionGeometry() {
        val resized=game.copy(right=1280,bottom=720)
        val actual=state().copy(gameFrame=resized,windows=listOf(app.copy(frame=resized),panel,ime))
        assertTrue(ResolutionSettingsPresentationPolicy.allows(actual))
        assertEquals(1280,actual.gameFrame!!.right)
        assertEquals(1920,actual.anchor!!.frame.right)
        assertFalse(ResolutionSettingsPresentationPolicy.allows(actual.copy(gameFrame=null,taskPresent=false)))
    }
}
