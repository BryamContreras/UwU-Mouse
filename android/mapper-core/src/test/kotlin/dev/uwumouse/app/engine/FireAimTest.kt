package dev.uwumouse.app.engine

import dev.uwumouse.app.backend.recovery.RecoveryJournal
import org.junit.Assert.*
import org.junit.Test

class FireAimTest {
    private class Harness {
        val frames=mutableListOf<TouchFrame>(); var now=1000L
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001)) }
        val composer=TouchComposer({++now},{ frame ->
            frames.add(frame);journal.prepare(1,frame) && journal.acknowledged(1,frame,true)
        })
        val geometry=MappingGeometry(1000f,2000f)
        val fire=PreparedTouch("touch:fire",850f,1600f,button=1,mouseAim=true)
        val bindings=MappingBindings(actionButton=null,touches=listOf(fire),distinctCameraContacts=true,
            fireDragBounds=AimBounds(50f,100f,949f,1899f))
        val engine=MappingEngine(composer,geometry,bindings).apply { setCameraEnabled(true) }
    }

    @Test fun repeatedShotsMoveTheFireFingerAndRestoreCameraWithoutLosingJoystickOrAction() {
        val h=Harness();h.engine.key(MappingEngine.W,true);h.engine.key(MappingEngine.SPACE,true)
        val held=h.composer.snapshot()
        repeat(30) {
            assertTrue(h.engine.relativeMouse(5f,3f))
            assertTrue(h.engine.mouseButton(1,true));assertTrue(h.engine.lastOperationSucceeded)
            assertFalse(h.composer.hasOwner(MappingEngine.CAMERA_OWNER))
            assertEquals(h.fire.owner,h.engine.aimOwner)
            assertTrue(h.engine.relativeMouse(10f,-70f))
            val fire=h.composer.snapshot().first { it.owner==h.fire.owner }
            assertEquals(860f,fire.x,.001f);assertEquals(1530f,fire.y,.001f)
            assertTrue(h.engine.mouseButton(1,false));assertFalse(h.composer.hasOwner(h.fire.owner))
            assertTrue(h.engine.relativeMouse(-5f,4f))
            assertEquals(MappingEngine.CAMERA_OWNER,h.engine.aimOwner)
            held.forEach { contact -> assertEquals(contact,h.composer.snapshot().first { it.owner==contact.owner }) }
        }
        assertFalse(h.frames.any { it.action==TouchComposer.CANCEL })
        assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
    }

    @Test fun duplicateButtonDownDoesNotRestartOrMoveBackToTheFireAnchor() {
        val h=Harness();h.engine.mouseButton(1,true);h.engine.relativeMouse(0f,-120f)
        val old=h.composer.snapshot().single();val count=h.frames.size
        h.engine.mouseButton(1,true)
        assertEquals(count,h.frames.size);assertEquals(old,h.composer.snapshot().single())
    }

    @Test fun heldFireNeverBecomesUpDownAtTheGameBoundaryAndRespondsWhenReversed() {
        val h=Harness();h.engine.mouseButton(1,true);h.engine.relativeMouse(1600f,-4100f)
        for(frame in h.frames) {
            assertTrue(frame.xs.all { it in 50f..949f });assertTrue(frame.ys.all { it in 100f..1899f })
        }
        assertEquals(listOf(0,2),h.frames.map { it.action })
        val contact=h.composer.snapshot().single()
        assertEquals(949f,contact.x,.001f);assertEquals(100f,contact.y,.001f)
        assertEquals(0L,h.engine.cameraRestarts);assertFalse(h.composer.paused)
        h.engine.relativeMouse(-10f,20f)
        assertEquals(contact.id,h.composer.snapshot().single().id)
        assertEquals(939f,h.composer.snapshot().single().x,.001f)
        assertEquals(120f,h.composer.snapshot().single().y,.001f)
        h.engine.mouseButton(1,false);assertEquals(0,h.composer.activeCount)
    }

    @Test fun cursorModeAndPauseNeverRestoreHeldFireAfterTheTransition() {
        val h=Harness();h.engine.mouseButton(1,true);h.engine.relativeMouse(0f,-10f)
        assertTrue(h.engine.enterCursorMode());assertTrue(h.engine.leaveCursorMode())
        assertTrue(h.engine.relativeMouse(0f,10f));assertFalse(h.composer.hasOwner(h.fire.owner))
        h.engine.mouseButton(1,false);assertFalse(h.composer.hasOwner(h.fire.owner))
        h.engine.mouseButton(1,true);assertTrue(h.composer.hasOwner(h.fire.owner))
        assertTrue(h.engine.pause());assertTrue(h.engine.resume())
        h.engine.mouseButton(1,false);assertEquals(0,h.composer.activeCount)
    }

    @Test fun highCameraIdsKeepTheShootingFingerSeparateAtOrdinaryContactCounts() {
        val h=Harness();h.engine.key(MappingEngine.W,true)
        repeat(10) {
            h.engine.relativeMouse(180f,0f)
            assertTrue(h.composer.snapshot().first { it.owner==MappingEngine.CAMERA_OWNER }.id in 8..9)
            h.engine.mouseButton(1,true)
            assertTrue(h.composer.snapshot().first { it.owner==h.fire.owner }.id < 8)
            h.engine.mouseButton(1,false)
        }
    }

    @Test fun fittingBoundarySampleUsesUpDownMoveAndKeepsTheEntireDelta() {
        val h=Harness();h.engine.relativeMouse(160f,0f)
        h.frames.clear();assertTrue(h.engine.relativeMouse(20f,0f))
        assertEquals(listOf(1,0,2),h.frames.map { it.action })
        assertEquals(h.geometry.cameraX+20f,h.composer.snapshot().single().x,.001f)
        assertEquals(1L,h.engine.cameraRestarts)
    }
}
