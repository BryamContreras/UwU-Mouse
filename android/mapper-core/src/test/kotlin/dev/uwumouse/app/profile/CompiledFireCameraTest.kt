package dev.uwumouse.app.profile

import dev.uwumouse.app.backend.recovery.RecoveryJournal
import dev.uwumouse.app.engine.*
import org.junit.Assert.*
import org.junit.Test

class CompiledFireCameraTest {
    private class Harness(freeFire:Boolean=true,capacity:Boolean=false) {
        var now=1000L
        val profile=GameProfile.create(if(freeFire) "com.dts.freefireth" else "com.example.game",
            "Camera",1000,2000,0).let { p -> p.copy(
            calibration=p.calibration.copy(confirmed=true),
            additionalActions=if(capacity) (70..92).map { ActionSpec(.8f,.7f,"key",it) }
                else listOf(ActionSpec(.85f,.65f,"mouse",1))) }
        val prepared=ProfileCompiler.prepare(profile,1000,2000,0)
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001,TouchCoordinatePolicy.CONTINUOUS_CAMERA_9)) }
        val frames=mutableListOf<TouchFrame>()
        val composer=TouchComposer({now},{ frame -> frames+=frame
            journal.prepare(1,frame) && journal.acknowledged(1,frame,true)
        },continuousCamera=true)
        val engine=MappingEngine(composer,prepared.geometry,prepared.bindings,{now}).apply { setCameraEnabled(true) }
        fun camera()=composer.snapshot().single { it.owner==MappingEngine.CAMERA_OWNER }
        fun controls()=composer.snapshot().filter { it.owner!=MappingEngine.CAMERA_OWNER }
        fun startCamera() { assertTrue(engine.relativeMouse(1f,0f));assertEquals(9,camera().id) }
        fun holdFireAndMove() {
            assertTrue(engine.mouseButton(1,true));assertTrue(engine.lastOperationSucceeded)
            assertTrue(engine.key(MappingEngine.W,true));assertTrue(engine.lastOperationSucceeded)
        }
    }

    @Test fun twentyFourConfiguredActionsShareTenContactsWithoutBorrowingCameraIdentity() {
        val h=Harness(capacity=true)
        assertEquals(24,h.prepared.bindings.touches.size)
        h.startCamera();val camera=h.camera()
        for(key in listOf(MappingEngine.SPACE)+(70..92)) {
            assertTrue(h.engine.key(key,true));assertTrue(h.engine.lastOperationSucceeded)
            assertEquals(camera,h.camera());assertEquals(2,h.composer.activeCount)
            assertTrue(h.controls().all { it.id in 0..8 })
            h.engine.key(key,false)
        }
        for(key in 70..78) { h.engine.key(key,true);assertTrue(h.engine.lastOperationSucceeded) }
        assertEquals(10,h.composer.activeCount)
        h.engine.key(79,true)
        assertFalse(h.engine.lastOperationSucceeded);assertEquals("CONTACT_LIMIT",h.composer.lastError)
        assertEquals(camera,h.camera())
        assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
    }

    @Test fun smoothFreeFireRetainsItsFingerAndIndependentHeldControlsDuringLongTurns() {
        val h=Harness();h.startCamera();h.holdFireAndMove()
        h.engine.key(MappingEngine.SPACE,true);h.engine.key(MappingEngine.SPACE,false)
        val held=h.controls()
        repeat(1000) { index ->
            assertTrue(h.engine.relativeMouse(if(index%2==0) 40f else -39f,if(index%3==0) 8f else -2f))
            assertEquals(9,h.camera().id);assertEquals(held,h.controls())
        }
        h.now+=10_000L
        assertTrue(h.engine.dispatchGgCameraTimers())
        assertEquals(0L,h.engine.cameraRestarts);assertEquals(0L,h.engine.idleCameraRetirements)
        assertEquals(3,h.composer.activeCount)
        assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
    }

    @Test fun lateJoystickAndPartialActionUpPreserveFireAndCamera() {
        val h=Harness();h.startCamera()
        h.engine.mouseButton(1,true);val fire=h.controls().single()
        h.engine.key(MappingEngine.SPACE,true);h.engine.key(MappingEngine.W,true)
        h.engine.key(MappingEngine.SPACE,false)
        assertTrue(h.engine.lastOperationSucceeded)
        assertEquals(fire,h.controls().single { it.owner==fire.owner })
        assertTrue(h.engine.relativeMouse(100f,-80f))
        h.engine.mouseButton(1,false)
        assertEquals(2,h.composer.activeCount);assertEquals(9,h.camera().id)
        assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
    }

    @Test fun genericEdgeAndScheduledRestartReleaseOnlyTheCamera() {
        val h=Harness(freeFire=false);h.startCamera();h.holdFireAndMove()
        val held=h.controls()
        assertTrue(h.engine.relativeMouse(5000f,0f))
        assertEquals(held,h.composer.snapshot());assertEquals(1L,h.engine.cameraRestarts)
        val up=h.frames.last()
        assertEquals(TouchComposer.POINTER_UP,up.action and 255);assertEquals(9,up.ids[up.action ushr 8])
        assertTrue(h.engine.relativeMouse(1f,0f))
        h.now+=15L;assertTrue(h.engine.dispatchGgCameraTimers())
        assertEquals(held,h.controls());assertEquals(h.prepared.geometry.cameraX,h.camera().x,0f)
        assertTrue(h.engine.relativeMouse(3f,-2f))
        assertEquals(h.prepared.geometry.cameraX+3f*h.prepared.geometry.sensitivityX,h.camera().x,.0001f)
        assertEquals(held,h.controls())
        assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
    }

    @Test fun genericIdleReleaseKeepsMappedButtonsAndWasdHeld() {
        val h=Harness(freeFire=false);h.startCamera();h.holdFireAndMove()
        val held=h.controls()
        h.now+=700L;assertTrue(h.engine.dispatchGgCameraTimers())
        assertEquals(held,h.composer.snapshot());assertEquals(1L,h.engine.idleCameraRetirements)
        assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
    }

    @Test fun cursorRoundTripClearsHeldInputsAndRestartsFromTheSameAnchor() {
        val h=Harness();h.startCamera();val start=h.camera();h.holdFireAndMove()
        assertTrue(h.engine.relativeMouse(500f,400f))
        assertTrue(h.engine.enterCursorMode());assertEquals(0,h.composer.activeCount)
        assertTrue(h.engine.leaveCursorMode());h.startCamera()
        assertEquals(start,h.camera());assertTrue(h.controls().isEmpty())
        assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
    }
}
