package dev.uwumouse.app.profile

import dev.uwumouse.app.engine.*
import org.junit.Assert.*
import org.junit.Test

class SharedKeyMappingTest {
    private fun profile()=GameProfile.create("com.dts.freefireth","Shared key",1920,1080,0).let {
        it.copy(calibration=it.calibration.copy(confirmed=true),additionalActions=listOf(
            ActionSpec(.3f,.4f,code=it.action.code),ActionSpec(.6f,.6f,code=it.action.code)))
    }
    @Test fun onePhysicalPressHoldsEveryMappedPointUntilTheSamePhysicalRelease() {
        val p=profile();assertEquals(p,ProfileCodec.decode(ProfileCodec.encode(p)))
        val prepared=ProfileCompiler.prepare(p,1920,1080,0)
        val frames=mutableListOf<TouchFrame>()
        val composer=TouchComposer({1000L},{ frames+=it;true },continuousCamera=true)
        val engine=MappingEngine(composer,prepared.geometry,prepared.bindings)
        engine.setCameraEnabled(true);engine.relativeMouse(1f,0f)
        val camera=composer.snapshot().single()
        assertTrue(engine.key(p.action.code,true));assertTrue(engine.lastOperationSucceeded)
        assertEquals(4,composer.activeCount)
        val mapped=composer.snapshot().filter { it.owner!=camera.owner }
        assertEquals(3,mapped.map { it.id }.distinct().size)
        assertEquals(prepared.bindings.touches.map { it.x to it.y }.toSet(),mapped.map { it.x to it.y }.toSet())
        val count=frames.size;engine.key(p.action.code,true);assertEquals(count,frames.size)
        engine.key(p.action.code,false);assertEquals(listOf(camera),composer.snapshot())
        assertTrue(engine.pause());assertTrue(composer.snapshot().isEmpty())
    }
    @Test fun sharedTapKeyStillCannotConflictWithCommandsMacrosDirectionsOrSessionShortcuts() {
        val p=profile()
        for(candidate in listOf(
            p.copy(additionalActions=p.additionalActions+ActionSpec(.5f,.5f,code=p.action.code,behavior=ActionSpec.REPEAT)),
            p.copy(macros=listOf(MacroSpec("macro","Macro",.5f,.5f,code=p.action.code,steps=listOf(MacroStep(.5f,.5f))))),
            p.copy(action=p.action.copy(code=p.joystick.upKey)),
            p.copy(shortcuts=p.shortcuts.copy(cameraToggle=p.action.code)))) {
            assertTrue(ProfileValidator.validate(candidate).isNotEmpty())
        }
    }
    @Test fun duplicateMouseButtonsRemainUnambiguous() {
        val p=profile().let { it.copy(action=it.action.copy(triggerKind="mouse",code=1),
            additionalActions=listOf(ActionSpec(.4f,.5f,"mouse",1))) }
        assertTrue(ProfileValidator.validate(p).isNotEmpty())
    }
}
