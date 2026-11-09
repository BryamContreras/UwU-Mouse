package dev.uwumouse.app.engine

import org.junit.Assert.*
import org.junit.Test

class ConfiguredMappingEngineTest {
    @Test fun multipleActionsAndFireReleaseIndependentlyOfMovementAndCamera() {
        val h=Harness(MappingBindings(actionKey=null,actionButton=null,touches=listOf(
            PreparedTouch("jump",800f,900f,key=62),PreparedTouch("skill",600f,800f,key=35),PreparedTouch("shot",850f,600f,button=1))))
        h.engine.key(MappingEngine.W,true); h.engine.setCameraEnabled(true); h.engine.relativeMouse(3f,2f)
        h.engine.key(62,true); h.engine.key(35,true); h.engine.mouseButton(1,true)
        assertEquals(5,h.composer.activeCount)
        val kept=h.composer.snapshot().filter { it.owner !in setOf("jump","shot") }.associate { it.owner to it.id }
        h.engine.key(62,false); h.engine.mouseButton(1,false)
        assertEquals(kept,h.composer.snapshot().associate { it.owner to it.id })
        assertTrue(h.engine.pause()); assertEquals(0,h.composer.activeCount)
        assertTrue(h.engine.resume()); assertEquals(0,h.composer.activeCount)
        assertFalse(h.frames.any { it.ids.size>10 })
    }
    private class Harness(bindings: MappingBindings) {
        val frames = mutableListOf<TouchFrame>()
        var now = 1000L
        val composer = TouchComposer({ now++ }, { frames.add(it); true })
        val geometry = MappingGeometry(1000f, 2000f)
        val engine = MappingEngine(composer, geometry, bindings)
    }

    @Test fun customDirectionsAndOneKeyActionReplaceTheLaboratoryBindings() {
        val h = Harness(MappingBindings(37, 39, 38, 40, 35, null))
        assertFalse(h.engine.key(MappingEngine.W, true))
        assertFalse(h.engine.key(MappingEngine.SPACE, true))
        assertFalse(h.engine.mouseButton(1, true))
        assertTrue(h.engine.key(37, true))
        assertTrue(h.engine.key(35, true))
        assertEquals(2, h.composer.activeCount)
        assertTrue(h.composer.snapshot().first { it.owner == MappingEngine.JOYSTICK_OWNER }.y < h.geometry.joystickY)
        h.engine.key(35, false)
        assertEquals(MappingEngine.JOYSTICK_OWNER, h.composer.snapshot().single().owner)
    }

    @Test fun secondaryMouseActionReleasesOnlyItsContactWhileMovementAndCameraContinue() {
        val h = Harness(MappingBindings(actionKey = null, actionButton = 2))
        h.engine.key(MappingEngine.W, true)
        h.engine.setCameraEnabled(true)
        h.engine.relativeMouse(5f, 5f)
        assertFalse(h.engine.mouseButton(1, true))
        assertTrue(h.engine.mouseButton(2, true))
        assertEquals(3, h.composer.activeCount)
        val ids = h.composer.snapshot().filter { it.owner != MappingEngine.FIRE_OWNER }.associate { it.owner to it.id }
        h.engine.mouseButton(2, false)
        assertEquals(ids, h.composer.snapshot().associate { it.owner to it.id })
        assertFalse(h.frames.any { it.action == TouchComposer.CANCEL })
    }

    @Test fun applyingNewConfigurationRequiresAnEmptyAcknowledgedTransitionAndDoesNotReplayHeldState() {
        val h = Harness(MappingBindings())
        val replacement = MappingBindings(37, 39, 38, 40, 35, null)
        val geometry = MappingGeometry(2000f, 1000f)
        h.engine.key(MappingEngine.W, true)
        assertFalse(h.engine.updateConfiguration(geometry, replacement))
        assertTrue(h.engine.pause())
        assertTrue(h.engine.updateConfiguration(geometry, replacement))
        assertTrue(h.engine.resume())
        assertEquals(0, h.composer.activeCount)
        assertFalse(h.engine.cameraEnabled)
        assertFalse(h.engine.key(MappingEngine.W, false))
        assertTrue(h.engine.key(37, true))
        assertEquals(geometry.joystickX, h.composer.snapshot().single().x, .001f)
    }

    @Test fun anUnacknowledgedReleaseCannotBeHiddenByApplyingAnotherConfiguration() {
        var now = 0L
        val composer = TouchComposer({ ++now }, { it.action != TouchComposer.CANCEL })
        val engine = MappingEngine(composer, MappingGeometry(1000f, 2000f))
        engine.key(MappingEngine.W, true)
        assertFalse(engine.pause())
        assertTrue(composer.releaseUnverified)
        assertFalse(engine.updateConfiguration(MappingGeometry(2000f, 1000f), MappingBindings(actionKey = 35)))
        assertEquals(1, composer.activeCount)
    }
}
