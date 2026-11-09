package dev.uwumouse.app.engine

import dev.uwumouse.app.backend.recovery.RecoveryInjectionState
import dev.uwumouse.app.backend.recovery.RecoveryJournal
import org.junit.Assert.*
import org.junit.Test

class IdleContinuousCameraTest {
    private class Harness(bindings:MappingBindings=MappingBindings(continuousCamera=true,cameraLast=true)) {
        val geometry=MappingGeometry(1000f,2000f)
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001,
            if(bindings.continuousCamera) TouchCoordinatePolicy.CONTINUOUS_CAMERA_9 else TouchCoordinatePolicy.STRICT)) }
        var now=1000L
        var accept=true
        val frames=mutableListOf<TouchFrame>()
        val composer=TouchComposer({++now},{frame ->
            frames+=frame
            if(frame.action==TouchComposer.CANCEL && journal.snapshot().state==RecoveryInjectionState.RECOVERY_NEEDED) {
                val expected=requireNotNull(journal.cancellationFrame(frame.eventTime))
                check(expected.ids.contentEquals(frame.ids) && expected.xs.contentEquals(frame.xs) &&
                    expected.ys.contentEquals(frame.ys) && expected.downTime==frame.downTime)
                check(journal.cancelled(1,accept))
                accept
            } else {
                check(journal.prepare(1,frame))
                if(accept) { check(journal.acknowledged(1,frame,true));true }
                else { check(journal.fail(1));false } // The reply was lost after the attempted event.
            }
        },continuousCamera=bindings.continuousCamera)
        val engine=MappingEngine(composer,geometry,bindings).apply { check(setCameraEnabled(true)) }
    }

    @Test fun retirementLiftsOnlyOutsideCameraAndNextMotionStartsAtAnchorWithNine() {
        val h=Harness()
        assertTrue(h.engine.key(MappingEngine.W,true))
        assertTrue(h.engine.key(MappingEngine.SPACE,true))
        val controls=h.composer.snapshot()
        assertTrue(h.engine.relativeMouse(-4000f,5000f))
        val before=h.composer.snapshot().single { it.owner==MappingEngine.CAMERA_OWNER }
        assertEquals(9,before.id)
        assertTrue(h.engine.canRetireIdleCamera)
        val count=h.frames.size
        assertTrue(h.engine.retireIdleCamera())
        assertEquals(count+1,h.frames.size)
        val lifted=h.frames.last()
        assertEquals(TouchComposer.POINTER_UP,lifted.action and 255)
        assertEquals(9,lifted.ids[lifted.action ushr 8])
        assertEquals(before.x,lifted.xs.last(),0f)
        assertEquals(before.y,lifted.ys.last(),0f)
        assertEquals(controls,h.composer.snapshot())
        assertEquals(2,h.journal.snapshot().possibleContacts)
        assertTrue(h.engine.cameraEnabled)
        assertTrue(h.engine.lastOperationSucceeded)
        assertEquals(1L,h.engine.idleCameraRetirements)
        assertEquals(0L,h.engine.cameraRestarts)
        assertFalse(h.engine.canRetireIdleCamera)
        assertTrue(h.engine.retireIdleCamera())
        assertEquals(count+1,h.frames.size)
        assertTrue(h.engine.relativeMouse(5f,-3f))
        val down=h.frames[h.frames.lastIndex-1]
        assertEquals(TouchComposer.POINTER_DOWN,down.action and 255)
        assertEquals(9,down.ids.last())
        assertEquals(h.geometry.cameraX,down.xs.last(),0f)
        assertEquals(h.geometry.cameraY,down.ys.last(),0f)
        val resumed=h.composer.snapshot().last()
        assertEquals(h.geometry.cameraX+5f,resumed.x,0f)
        assertEquals(h.geometry.cameraY-3f,resumed.y,0f)
        assertEquals(controls,h.composer.snapshot().filter { it.owner!=MappingEngine.CAMERA_OWNER })
        assertTrue(h.engine.pause())
        assertEquals(0,h.journal.snapshot().possibleContacts)
    }

    @Test fun eitherHeldMouseButtonInhibitsRetirementUntilBothAreReleased() {
        val h=Harness(MappingBindings(continuousCamera=true,cameraLast=true,actionButton=null,touches=listOf(
            PreparedTouch("shoot",850f,1400f,button=1),PreparedTouch("scope",800f,1600f,button=2))))
        assertTrue(h.engine.relativeMouse(1000f,0f))
        assertTrue(h.engine.canRetireIdleCamera)
        assertTrue(h.engine.mouseButton(1,true))
        assertTrue(h.engine.mouseButton(2,true))
        val held=h.composer.snapshot();val count=h.frames.size
        assertFalse(h.engine.canRetireIdleCamera)
        assertTrue(h.engine.retireIdleCamera())
        assertEquals(held,h.composer.snapshot());assertEquals(count,h.frames.size)
        assertTrue(h.engine.mouseButton(1,false))
        assertFalse(h.engine.canRetireIdleCamera)
        assertTrue(h.engine.retireIdleCamera())
        assertEquals(0L,h.engine.idleCameraRetirements)
        assertTrue(h.engine.mouseButton(2,false))
        assertTrue(h.engine.canRetireIdleCamera)
        assertTrue(h.engine.retireIdleCamera())
        assertEquals(0,h.composer.activeCount)
        assertEquals(1L,h.engine.idleCameraRetirements)
    }

    @Test fun smallSlowStepsInsideDisplayKeepTheirExistingFingerAcrossIdleChecks() {
        val h=Harness()
        repeat(50) {
            h.now+=100L // The deadline is the session owner's responsibility.
            assertTrue(h.engine.relativeMouse(.25f,-.125f))
            assertFalse(h.engine.canRetireIdleCamera)
            assertTrue(h.engine.retireIdleCamera())
        }
        assertEquals(1,h.frames.count { it.action==TouchComposer.DOWN })
        assertEquals(0L,h.engine.idleCameraRetirements)
        assertEquals(h.geometry.cameraX+12.5f,h.composer.snapshot().single().x,0f)
        assertFalse(h.frames.any { it.action==TouchComposer.UP })
    }

    @Test fun accumulatedTravelCanRetireRepeatedTurnsWithZeroNetDisplacement() {
        val h=Harness()
        repeat(20) {
            assertTrue(h.engine.relativeMouse(100f,0f))
            assertTrue(h.engine.relativeMouse(-100f,0f))
            if(it<19) assertFalse(h.engine.canRetireIdleCamera)
        }
        assertEquals(h.geometry.cameraX,h.composer.snapshot().single().x,0f)
        assertTrue(h.engine.canRetireIdleCamera)
        assertTrue(h.engine.retireIdleCamera())
        assertTrue(h.engine.relativeMouse(1f,0f))
        assertFalse(h.engine.canRetireIdleCamera) // A new DOWN resets accumulated travel.
        assertEquals(1L,h.engine.idleCameraRetirements)
    }

    @Test fun disabledCursorPausedAndBoundedStatesNeverRetireCamera() {
        val h=Harness()
        assertTrue(h.engine.retireIdleCamera())
        assertTrue(h.frames.isEmpty())
        assertTrue(h.engine.relativeMouse(1000f,0f))
        assertTrue(h.engine.setCameraEnabled(false))
        assertFalse(h.engine.canRetireIdleCamera)
        assertTrue(h.engine.retireIdleCamera())
        assertTrue(h.engine.setCameraEnabled(true))
        assertTrue(h.engine.relativeMouse(1000f,0f))
        assertTrue(h.engine.enterCursorMode())
        assertFalse(h.engine.canRetireIdleCamera)
        assertTrue(h.engine.retireIdleCamera())
        assertTrue(h.engine.leaveCursorMode())
        assertTrue(h.engine.relativeMouse(1000f,0f))
        assertTrue(h.engine.pause())
        val count=h.frames.size
        assertFalse(h.engine.canRetireIdleCamera)
        assertTrue(h.engine.retireIdleCamera())
        assertEquals(count,h.frames.size)
        assertEquals(0L,h.engine.idleCameraRetirements)
        val bounded=Harness(MappingBindings(distinctCameraContacts=true))
        assertTrue(bounded.engine.relativeMouse(1000f,0f))
        assertFalse(bounded.engine.canRetireIdleCamera)
        assertTrue(bounded.engine.retireIdleCamera())
        assertEquals(0L,bounded.engine.idleCameraRetirements)
    }

    @Test fun ambiguousRetirementAndCancelRetainExactOutsideContactSetWithoutReplay() {
        val h=Harness()
        assertTrue(h.engine.key(MappingEngine.W,true))
        assertTrue(h.engine.relativeMouse(-4000f,5000f))
        val before=h.composer.snapshot()
        h.accept=false
        assertFalse(h.engine.retireIdleCamera())
        assertFalse(h.engine.lastOperationSucceeded)
        assertEquals(0L,h.engine.idleCameraRetirements)
        assertTrue(h.composer.paused);assertTrue(h.composer.releaseUnverified)
        val attempted=h.frames[h.frames.lastIndex-1]
        assertEquals(TouchComposer.POINTER_UP,attempted.action and 255)
        val recovery=requireNotNull(h.composer.recoveryFrame())
        assertArrayEquals(attempted.ids,recovery.ids)
        assertArrayEquals(attempted.xs,recovery.xs,0f)
        assertArrayEquals(attempted.ys,recovery.ys,0f)
        assertEquals(before,h.composer.snapshot())
        assertEquals(2,h.journal.snapshot().possibleContacts)
        val count=h.frames.size
        assertFalse(h.engine.relativeMouse(5f,0f))
        assertTrue(h.engine.retireIdleCamera()) // Paused is an ineligible no-op, not recovery.
        assertEquals(count,h.frames.size)
        h.accept=true
        assertTrue(h.engine.pause())
        assertEquals(0,h.journal.snapshot().possibleContacts)
        assertEquals(0,h.composer.activeCount)
        assertArrayEquals(attempted.xs,h.frames.last().xs,0f)
    }
}
