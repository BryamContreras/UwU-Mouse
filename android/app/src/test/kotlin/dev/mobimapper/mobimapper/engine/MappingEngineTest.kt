package dev.mobimapper.mobimapper.engine

import kotlin.math.hypot
import org.junit.Assert.*
import org.junit.Test

class MappingEngineTest {
    private class Harness {
        val frames = mutableListOf<TouchFrame>()
        var now = 1000L
        val composer = TouchComposer({ now++ }, { frames.add(it); true })
        val geometry = MappingGeometry(1000f, 2000f)
        val engine = MappingEngine(composer, geometry)
    }

    @Test fun diagonalsAreNormalizedAndOppositeDirectionUsesMostRecentDown() {
        val h = Harness()
        assertTrue(h.engine.key(MappingEngine.W, true))
        assertTrue(h.engine.key(MappingEngine.D, true))
        val diagonal = h.composer.snapshot().single()
        assertEquals(h.geometry.joystickRadius,
            hypot(diagonal.x - h.geometry.joystickX, diagonal.y - h.geometry.joystickY), .001f)
        h.engine.key(MappingEngine.S, true)
        assertTrue(h.composer.snapshot().single().y > h.geometry.joystickY)
        h.engine.key(MappingEngine.W, true) // Repeat is not a new press and cannot reverse priority.
        assertTrue(h.composer.snapshot().single().y > h.geometry.joystickY)
        h.engine.key(MappingEngine.S, false)
        assertTrue(h.composer.snapshot().single().y < h.geometry.joystickY)
        h.engine.key(MappingEngine.W, false)
        h.engine.key(MappingEngine.D, false)
        assertEquals(0, h.composer.activeCount)
    }

    @Test fun releasingActionPreservesCameraAndJoystick() {
        val h = Harness()
        h.engine.key(MappingEngine.W, true)
        h.engine.key(MappingEngine.SPACE, true)
        h.engine.setCameraEnabled(true)
        h.engine.relativeMouse(10f, 5f)
        assertEquals(3, h.composer.activeCount)
        val otherIds = h.composer.snapshot().filter { it.owner != MappingEngine.ACTION_OWNER }.associate { it.owner to it.id }
        h.engine.key(MappingEngine.SPACE, false)
        assertEquals(otherIds, h.composer.snapshot().associate { it.owner to it.id })
        assertFalse(h.frames.any { it.action == TouchComposer.CANCEL })
    }

    @Test fun cameraPreservesSubpixelFractionsAndEventRateDoesNotScaleGain() {
        val one = Harness()
        one.engine.setCameraEnabled(true)
        one.engine.relativeMouse(10f, -5f)
        val many = Harness()
        many.engine.setCameraEnabled(true)
        repeat(100) { many.engine.relativeMouse(.1f, -.05f) }
        val singlePosition = one.composer.snapshot().single()
        val manyPosition = many.composer.snapshot().single()
        assertEquals(singlePosition.x, manyPosition.x, .01f)
        assertEquals(singlePosition.y, manyPosition.y, .01f)
        assertEquals(one.geometry.cameraX + 10f, manyPosition.x, .01f)
        val tiny = Harness()
        tiny.engine.setCameraEnabled(true)
        repeat(10_000) { tiny.engine.relativeMouse(.00001f, 0f) }
        assertEquals(tiny.geometry.cameraX + .1f, tiny.composer.snapshot().single().x, .0001f)
    }

    @Test fun cameraRecenterDoesNotCancelOtherContactsAndRetainsDisplacement() {
        val h = Harness()
        h.engine.key(MappingEngine.W, true)
        h.engine.key(MappingEngine.SPACE, true)
        val ids = h.composer.snapshot().associate { it.owner to it.id }
        h.engine.setCameraEnabled(true)
        assertTrue(h.engine.relativeMouse(500f, 0f))
        assertEquals(2L, h.engine.cameraRestarts)
        val contacts = h.composer.snapshot()
        assertEquals(3, contacts.size)
        for ((owner, id) in ids) assertEquals(id, contacts.first { it.owner == owner }.id)
        assertEquals(h.geometry.cameraX + 160f,
            contacts.first { it.owner == MappingEngine.CAMERA_OWNER }.x, .01f)
        assertFalse(h.frames.any { it.action == TouchComposer.CANCEL })
        assertEquals(2, h.frames.count { it.action and 255 == TouchComposer.POINTER_UP })
    }

    @Test fun cameraHandlesOppositeBoundaryDirectionWithoutSpin() {
        val h = Harness()
        h.engine.setCameraEnabled(true)
        assertTrue(h.engine.relativeMouse(170f, 320f))
        assertTrue(h.engine.relativeMouse(1f, 1f))
        assertEquals(1L, h.engine.cameraRestarts)
        assertTrue(h.engine.relativeMouse(-172f, -322f))
        assertTrue(h.engine.lastOperationSucceeded)
        assertFalse(h.composer.paused)
    }

    @Test fun pauseStopsAllOwnersAndResumeDoesNotResurrectHeldKeys() {
        val h = Harness()
        h.engine.key(MappingEngine.W, true)
        h.engine.mouseButton(1, true)
        h.engine.key(MappingEngine.SPACE, true)
        assertTrue(h.engine.pause())
        assertEquals(0, h.composer.activeCount)
        assertEquals(TouchComposer.CANCEL, h.frames.last().action)
        assertTrue(h.engine.resume())
        assertEquals(0, h.composer.activeCount)
        h.engine.key(MappingEngine.W, false)
        h.engine.mouseButton(1, false)
        h.engine.key(MappingEngine.SPACE, false)
        assertEquals(0, h.composer.activeCount)
        assertFalse(h.engine.cameraEnabled)
    }

    @Test fun newGeometryRequiresAConfirmedEmptyTransition() {
        val h = Harness()
        h.engine.key(MappingEngine.W, true)
        assertFalse(h.engine.updateGeometry(MappingGeometry(2000f, 1000f)))
        assertTrue(h.engine.pause())
        assertTrue(h.engine.updateGeometry(MappingGeometry(2000f, 1000f)))
        assertTrue(h.engine.resume())
        h.engine.key(MappingEngine.W, true)
        assertEquals(400f, h.composer.snapshot().single().x, .01f)
    }

    @Test fun unassignedInputsAreNotConsumedAndExtremeDeltaStopsSafely() {
        val h = Harness()
        assertFalse(h.engine.key(999, true))
        assertFalse(h.engine.mouseButton(8, true))
        assertFalse(h.engine.relativeMouse(5f, 5f))
        h.engine.setCameraEnabled(true)
        assertFalse(h.engine.relativeMouse(Float.NaN, 1f))
        assertFalse(h.engine.relativeMouse(1_000_000f, 0f))
        assertTrue(h.composer.paused)
        assertEquals(0, h.composer.activeCount)
    }
}
