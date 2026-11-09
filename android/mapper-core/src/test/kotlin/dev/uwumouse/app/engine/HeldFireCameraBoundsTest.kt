package dev.uwumouse.app.engine

import dev.uwumouse.app.backend.recovery.RecoveryJournal
import org.junit.Assert.*
import org.junit.Test

class HeldFireCameraBoundsTest {
    private class Harness(continuous:Boolean=false,enabled:Boolean=true,explicitTouch:Boolean=true) {
        val geometry=MappingGeometry(1000f,2000f)
        val realBounds=AimBounds(50f,100f,949f,1899f)
        val fire=PreparedTouch("fire",850f,1600f,button=1)
        val bindings=MappingBindings(actionButton=if(explicitTouch) null else 1,
            touches=if(explicitTouch) listOf(fire) else emptyList(),
            distinctCameraContacts=true,cameraLast=true,continuousCamera=continuous,
            retireBoundedCameraOnIdle=true,heldFireCameraBounds=if(enabled) realBounds else null)
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001,
            if(continuous) TouchCoordinatePolicy.CONTINUOUS_CAMERA_9 else TouchCoordinatePolicy.STRICT)) }
        var now=1000L
        val frames=mutableListOf<TouchFrame>()
        val composer=TouchComposer({++now},{frame ->
            frames+=frame
            check(journal.prepare(1,frame));check(journal.acknowledged(1,frame,true));true
        },continuousCamera=continuous)
        val engine=MappingEngine(composer,geometry,bindings).apply {
            check(setCameraEnabled(true));check(key(MappingEngine.W,true));check(key(MappingEngine.SPACE,true))
            check(relativeMouse(160f,0f))
        }
        fun camera()=composer.snapshot().single { it.owner==MappingEngine.CAMERA_OWNER }
    }

    @Test fun heldM1KeepsTheExistingCameraAcrossItsRegionWithoutMovingFireOrOtherOwners() {
        val h=Harness()
        val camera=h.camera();assertTrue(h.engine.mouseButton(1,true))
        val held=h.composer.snapshot().filter { it.owner!=MappingEngine.CAMERA_OWNER }
        val start=h.frames.size
        assertTrue(h.engine.relativeMouse(50f,-500f))
        assertEquals(camera.id,h.camera().id)
        assertEquals(900f,h.camera().x,0f);assertEquals(360f,h.camera().y,0f)
        assertEquals(held,h.composer.snapshot().filter { it.owner!=MappingEngine.CAMERA_OWNER })
        assertTrue(h.frames.drop(start).all { it.action==TouchComposer.MOVE })
        assertEquals(0L,h.engine.cameraRestarts);assertEquals(0L,h.engine.heldFireCameraClamps)
        assertFalse(h.engine.canRetireIdleCamera)
        assertEquals(MappingEngine.CAMERA_OWNER,h.engine.aimOwner)
        assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
    }

    @Test fun actualGameEdgeClampsWithoutHandoffAndReversalRespondsImmediately() {
        val h=Harness();assertTrue(h.engine.mouseButton(1,true))
        val id=h.camera().id;val start=h.frames.size
        assertTrue(h.engine.relativeMouse(1500f,-4100f))
        assertEquals(949f,h.camera().x,0f);assertEquals(100f,h.camera().y,0f)
        assertEquals(1L,h.engine.heldFireCameraClamps)
        assertTrue(h.engine.relativeMouse(-10f,20f))
        assertEquals(939f,h.camera().x,0f);assertEquals(120f,h.camera().y,0f)
        assertEquals(id,h.camera().id)
        assertTrue(h.frames.drop(start).all { it.action==TouchComposer.MOVE })
        assertEquals(0L,h.engine.cameraRestarts)
    }

    @Test fun physicalUpDoesNotRecenterUntilNextMovementAndThatDeltaIsAppliedOnce() {
        val h=Harness();assertTrue(h.engine.mouseButton(1,true))
        assertTrue(h.engine.relativeMouse(50f,-500f))
        val outside=h.camera()
        val controls=h.composer.snapshot().filter { it.owner!=MappingEngine.CAMERA_OWNER && it.owner!=h.fire.owner }
        val before=h.frames.size
        assertTrue(h.engine.mouseButton(1,false))
        assertEquals(before+1,h.frames.size)
        assertEquals(outside,h.camera());assertEquals(0L,h.engine.cameraRestarts)
        assertTrue(h.engine.relativeMouse(3f,-2f))
        val fresh=h.camera()
        assertNotEquals(outside.id,fresh.id)
        assertEquals(h.geometry.cameraX+3f,fresh.x,0f);assertEquals(h.geometry.cameraY-2f,fresh.y,0f)
        assertEquals(controls,h.composer.snapshot().filter { it.owner!=MappingEngine.CAMERA_OWNER })
        assertEquals(1L,h.engine.cameraRestarts)
        assertFalse(h.composer.hasOwner(h.fire.owner))
        assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
    }

    @Test fun moveBatchFlushesTheHeldPositionBeforePhysicalUpAndCannotReplayItAtRestore() {
        val h=Harness();assertTrue(h.engine.mouseButton(1,true))
        assertTrue(h.composer.beginMoveBatch())
        assertTrue(h.engine.relativeMouse(50f,-500f))
        val outside=h.camera();val before=h.frames.size
        assertTrue(h.engine.mouseButton(1,false))
        assertEquals(listOf(TouchComposer.MOVE,TouchComposer.POINTER_UP),h.frames.drop(before).map { it.action and 255 })
        assertEquals(outside.x,h.frames[before].xs.last(),0f)
        assertTrue(h.engine.relativeMouse(3f,-2f))
        assertTrue(h.composer.endMoveBatch())
        assertEquals(h.geometry.cameraX+3f,h.camera().x,0f)
        assertEquals(h.geometry.cameraY-2f,h.camera().y,0f)
        assertEquals(1L,h.engine.cameraRestarts)
    }

    @Test fun defaultOrUnheldM1KeepsNormalBoundedSegmentation() {
        for(disabled in listOf(false,true)) {
            val h=Harness(enabled=!disabled)
            if(disabled) assertTrue(h.engine.mouseButton(1,true))
            val old=h.camera().id
            assertTrue(h.engine.relativeMouse(50f,0f))
            assertNotEquals(old,h.camera().id)
            assertEquals(h.geometry.cameraX+50f,h.camera().x,0f)
            assertEquals(1L,h.engine.cameraRestarts);assertEquals(0L,h.engine.heldFireCameraClamps)
        }
    }

    @Test fun aFallbackMouseButtonWithoutAnActivePreparedTouchDoesNotEnableTheTrial() {
        val h=Harness(explicitTouch=false)
        assertTrue(h.engine.mouseButton(1,true))
        assertTrue(h.engine.relativeMouse(50f,0f))
        assertEquals(1L,h.engine.cameraRestarts)
        assertEquals(h.geometry.cameraX+50f,h.camera().x,0f)
    }

    @Test fun continuousCameraRemainsPersistentOutsideGameBoundsBeforeAndAfterM1Up() {
        val h=Harness(continuous=true);assertTrue(h.engine.mouseButton(1,true))
        val id=h.camera().id;val fire=h.composer.snapshot().single { it.owner==h.fire.owner }
        assertTrue(h.engine.relativeMouse(5000f,-6000f))
        assertEquals(5850f,h.camera().x,0f);assertEquals(-5140f,h.camera().y,0f)
        assertEquals(id,h.camera().id);assertEquals(9,id)
        assertEquals(fire,h.composer.snapshot().single { it.owner==h.fire.owner })
        assertTrue(h.engine.mouseButton(1,false));assertTrue(h.engine.relativeMouse(3f,-2f))
        assertEquals(5853f,h.camera().x,0f);assertEquals(-5142f,h.camera().y,0f)
        assertEquals(0L,h.engine.cameraRestarts);assertEquals(0L,h.engine.heldFireCameraClamps)
        assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
    }

    @Test fun releasingInsideTheCameraRegionNeedsNoRestorationOrExtraEdges() {
        val h=Harness();assertTrue(h.engine.mouseButton(1,true))
        assertTrue(h.engine.relativeMouse(-50f,0f))
        val id=h.camera().id
        assertTrue(h.engine.mouseButton(1,false))
        val before=h.frames.size
        assertTrue(h.engine.relativeMouse(3f,-2f))
        assertEquals(id,h.camera().id)
        assertEquals(listOf(TouchComposer.MOVE),h.frames.drop(before).map { it.action })
        assertEquals(803f,h.camera().x,0f);assertEquals(858f,h.camera().y,0f)
        assertEquals(0L,h.engine.cameraRestarts)
    }

    @Test fun boundsMustUseExclusiveDisplayEdgesBeforeAnyInjection() {
        var injected=0
        val composer=TouchComposer({1000L},{injected++;true})
        val geometry=MappingGeometry(1000f,2000f)
        for(bounds in listOf(AimBounds(0f,0f,1000f,1999f),AimBounds(0f,0f,999f,2000f))) {
            assertThrows(IllegalArgumentException::class.java) {
                MappingEngine(composer,geometry,MappingBindings(heldFireCameraBounds=bounds))
            }
        }
        MappingEngine(composer,geometry,MappingBindings(heldFireCameraBounds=
            AimBounds(0f,0f,Math.nextDown(1000f),Math.nextDown(2000f))))
        assertEquals(0,injected)
    }

    @Test fun anEmptyConfigurationChangeCannotInstallBoundsOutsideItsNewDisplay() {
        val h=Harness()
        assertTrue(h.engine.setCameraEnabled(false))
        assertTrue(h.engine.key(MappingEngine.W,false));assertTrue(h.engine.key(MappingEngine.SPACE,false))
        assertFalse(h.engine.updateGeometry(MappingGeometry(900f,2000f)))
        assertFalse(h.engine.updateConfiguration(h.geometry,h.bindings.copy(
            heldFireCameraBounds=AimBounds(0f,0f,1000f,1999f))))
        assertTrue(h.engine.updateConfiguration(h.geometry,h.bindings))
        assertTrue(h.engine.resume());assertTrue(h.engine.setCameraEnabled(true))
        assertTrue(h.engine.relativeMouse(3f,-2f))
        assertEquals(693f,h.camera().x,0f);assertEquals(858f,h.camera().y,0f)
    }
}
