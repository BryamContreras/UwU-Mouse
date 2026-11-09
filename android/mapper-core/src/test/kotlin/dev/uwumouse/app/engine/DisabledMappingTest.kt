package dev.uwumouse.app.engine

import org.junit.Assert.*
import org.junit.Test

class DisabledMappingTest {
    @Test fun disabledJoystickAllowsWAsOrdinaryActionWithoutMovingJoystick() {
        val frames=mutableListOf<TouchFrame>();var time=1L
        val composer=TouchComposer({ time++ },{ frames.add(it);true })
        val bindings=MappingBindings(actionKey=null,actionButton=null,joystickEnabled=false,
            touches=listOf(PreparedTouch("tapW",300f,400f,key=MappingEngine.W)))
        val engine=MappingEngine(composer,MappingGeometry(1000f,2000f),bindings)
        assertTrue(engine.key(MappingEngine.W,true));assertFalse(engine.key(MappingEngine.A,true))
        assertEquals(listOf("tapW"),composer.snapshot().map { it.owner })
        assertEquals(300f,frames.last().xs.single(),0f)
        engine.key(MappingEngine.W,false);assertEquals(0,composer.activeCount)
    }
    @Test fun disabledCameraNeverCreatesCameraContactButCursorAndActionsStillWork() {
        var time=1L;val composer=TouchComposer({ time++ },{ true })
        val engine=MappingEngine(composer,MappingGeometry(1000f,2000f),MappingBindings(cameraMappingEnabled=false))
        assertTrue(engine.setCameraEnabled(true));assertFalse(engine.cameraEnabled)
        assertFalse(engine.relativeMouse(500f,-500f));assertEquals(0,composer.activeCount)
        engine.key(MappingEngine.SPACE,true);assertEquals(1,composer.activeCount)
        assertTrue(engine.enterCursorMode());engine.cursorButton(true,200f,300f)
        assertTrue(engine.leaveCursorMode());assertFalse(engine.cameraEnabled)
        assertEquals(0,composer.activeCount);assertFalse(engine.relativeMouse(1f,1f))
    }
}
