package dev.mobimapper.mobimapper.engine

import dev.mobimapper.mobimapper.backend.recovery.RecoveryJournal
import org.junit.Assert.*
import org.junit.Test

class IdleBoundedCameraTest {
    private class Harness(bindings:MappingBindings=MappingBindings(distinctCameraContacts=true,
        cameraLast=true,retireBoundedCameraOnIdle=true),val geometry:MappingGeometry=MappingGeometry(1000f,2000f)) {
        var now=1000L
        val frames=mutableListOf<TouchFrame>()
        val journal=RecoveryJournal().apply { check(begin(1,geometry.width.toInt(),geometry.height.toInt(),10001,
            if(bindings.continuousCamera) TouchCoordinatePolicy.CONTINUOUS_CAMERA_9 else TouchCoordinatePolicy.STRICT)) }
        val composer=TouchComposer({++now},{frame ->
            frames+=frame
            check(journal.prepare(1,frame));check(journal.acknowledged(1,frame,true));true
        },continuousCamera=bindings.continuousCamera)
        val engine=MappingEngine(composer,geometry,bindings).apply { check(setCameraEnabled(true)) }
    }

    @Test fun eitherAxisBecomesEligibleAtSeventyPercentInAllFourDirections() {
        for((x,y) in listOf(119f to 0f,-119f to 0f,0f to 224f,0f to -224f)) {
            val h=Harness()
            assertTrue(h.engine.relativeMouse(x,y))
            assertTrue(h.engine.canRetireIdleCamera)
            assertTrue(h.engine.retireIdleCamera())
            assertEquals(1L,h.engine.idleCameraRetirements)
            assertEquals(0,h.composer.activeCount)
            assertEquals(0,h.journal.snapshot().possibleContacts)
        }
    }

    @Test fun smallerDisplacementsAndSubthresholdDiagonalsRetainTheExistingFinger() {
        for((x,y) in listOf(Math.nextDown(119f) to 0f,0f to Math.nextDown(224f),100f to 150f,1f to -1f)) {
            val h=Harness();assertTrue(h.engine.relativeMouse(x,y))
            val before=h.composer.snapshot();val count=h.frames.size
            assertFalse(h.engine.canRetireIdleCamera)
            assertTrue(h.engine.retireIdleCamera())
            assertEquals(before,h.composer.snapshot());assertEquals(count,h.frames.size)
            assertEquals(0L,h.engine.idleCameraRetirements)
        }
    }

    @Test fun ordinaryBoundedBindingsNeverAcquireTheDiagnosticIdleBehavior() {
        val h=Harness(MappingBindings(distinctCameraContacts=true,cameraLast=true))
        assertTrue(h.engine.relativeMouse(160f,300f))
        assertFalse(h.engine.canRetireIdleCamera)
        val count=h.frames.size
        assertTrue(h.engine.retireIdleCamera())
        assertEquals(count,h.frames.size);assertEquals(0L,h.engine.idleCameraRetirements)
    }

    @Test fun heldMouseButtonsBlockRetirementUntilBothPhysicalUps() {
        val h=Harness(MappingBindings(distinctCameraContacts=true,cameraLast=true,retireBoundedCameraOnIdle=true,
            touches=listOf(PreparedTouch("scope",800f,1600f,button=2))))
        assertTrue(h.engine.relativeMouse(119f,0f))
        assertTrue(h.engine.mouseButton(1,true));assertTrue(h.engine.mouseButton(2,true))
        val held=h.composer.snapshot();val count=h.frames.size
        assertFalse(h.engine.canRetireIdleCamera)
        assertTrue(h.engine.retireIdleCamera())
        assertEquals(held,h.composer.snapshot());assertEquals(count,h.frames.size)
        assertTrue(h.engine.mouseButton(1,false));assertFalse(h.engine.canRetireIdleCamera)
        assertTrue(h.engine.mouseButton(2,false));assertTrue(h.engine.canRetireIdleCamera)
        assertTrue(h.engine.retireIdleCamera());assertEquals(0,h.composer.activeCount)
    }

    @Test fun retirementPreservesWasdAndActionsAndAppliesOnlyTheNextNewDeltaAtAnchor() {
        val h=Harness()
        assertTrue(h.engine.key(MappingEngine.W,true));assertTrue(h.engine.key(MappingEngine.SPACE,true))
        val controls=h.composer.snapshot()
        assertTrue(h.engine.relativeMouse(119f,-20f))
        val downTime=h.frames.last().downTime
        val oldCamera=h.composer.snapshot().last();val count=h.frames.size
        assertTrue(h.engine.retireIdleCamera())
        assertEquals(count+1,h.frames.size)
        assertEquals(TouchComposer.POINTER_UP,h.frames.last().action and 255)
        assertEquals(oldCamera.x,h.frames.last().xs.last(),0f)
        assertEquals(controls,h.composer.snapshot())
        assertTrue(h.engine.cameraEnabled);assertTrue(h.engine.lastOperationSucceeded)
        assertFalse(h.engine.canRetireIdleCamera)
        assertTrue(h.engine.retireIdleCamera());assertEquals(count+1,h.frames.size)
        assertTrue(h.engine.relativeMouse(3f,-2f))
        val fresh=h.composer.snapshot().last()
        assertEquals(h.geometry.cameraX+3f,fresh.x,0f)
        assertEquals(h.geometry.cameraY-2f,fresh.y,0f)
        assertEquals(downTime,h.frames.last().downTime)
        assertEquals(controls,h.composer.snapshot().dropLast(1))
        assertEquals(1L,h.engine.idleCameraRetirements);assertEquals(0L,h.engine.cameraRestarts)
        assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
    }

    @Test fun thresholdUsesMappedDisplayDisplacementAfterSensitivity() {
        val bindings=MappingBindings(distinctCameraContacts=true,cameraLast=true,retireBoundedCameraOnIdle=true)
        val h=Harness(bindings,MappingGeometry(1000f,2000f,sensitivityX=.25f))
        assertTrue(h.engine.relativeMouse(475f,0f));assertFalse(h.engine.canRetireIdleCamera)
        assertTrue(h.engine.relativeMouse(1f,0f));assertTrue(h.engine.canRetireIdleCamera)
    }

    @Test fun regionalRetirementIsExplicitOptInForContinuousAndNeverAllowsCursorOrPausedRetirement() {
        val continuous=Harness(MappingBindings(continuousCamera=true,cameraLast=true,retireBoundedCameraOnIdle=true))
        assertTrue(continuous.engine.relativeMouse(119f,0f))
        assertTrue(continuous.engine.canRetireIdleCamera)
        assertTrue(continuous.engine.relativeMouse(1000f,0f))
        assertTrue(continuous.engine.canRetireIdleCamera)
        assertTrue(continuous.engine.retireIdleCamera())
        val bounded=Harness()
        assertTrue(bounded.engine.relativeMouse(119f,0f));assertTrue(bounded.engine.enterCursorMode())
        assertFalse(bounded.engine.canRetireIdleCamera)
        assertTrue(bounded.engine.leaveCursorMode());assertTrue(bounded.engine.relativeMouse(119f,0f))
        assertTrue(bounded.engine.pause());assertFalse(bounded.engine.canRetireIdleCamera)
        val count=bounded.frames.size
        assertTrue(bounded.engine.retireIdleCamera());assertEquals(count,bounded.frames.size)
    }
}
