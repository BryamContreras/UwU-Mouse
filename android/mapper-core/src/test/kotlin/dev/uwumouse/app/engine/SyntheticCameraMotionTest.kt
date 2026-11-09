package dev.uwumouse.app.engine

import dev.uwumouse.app.backend.recovery.RecoveryJournal
import dev.uwumouse.app.profile.PreparedFireCadence
import org.junit.Assert.*
import org.junit.Test

class SyntheticCameraMotionTest {
    private class Rig(continuous:Boolean=true,manualFireAim:Boolean=false) {
        var now=1L
        val frames=mutableListOf<TouchFrame>()
        val geometry=MappingGeometry(1000f,2000f,sensitivityX=.25f,sensitivityY=4f,invertY=true)
        val fire=PreparedTouch("primary",850f,1600f,button=1,mouseAim=manualFireAim)
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001,
            if(continuous) TouchCoordinatePolicy.CONTINUOUS_CAMERA_9 else TouchCoordinatePolicy.STRICT)) }
        val composer=TouchComposer({now},{frame ->
            frames+=frame
            check(journal.prepare(1,frame));check(journal.acknowledged(1,frame,true));true
        },continuousCamera=continuous)
        val engine=MappingEngine(composer,geometry,MappingBindings(continuousCamera=continuous,cameraLast=true,
            distinctCameraContacts=true,retireBoundedCameraOnIdle=true,actionButton=null,touches=listOf(fire),
            fireDragBounds=if(manualFireAim) AimBounds(50f,100f,949f,1899f) else null)).apply {
            check(setCameraEnabled(true))
        }
        fun camera()=composer.snapshot().single { it.owner==MappingEngine.CAMERA_OWNER }
    }

    @Test fun syntheticDisplayPixelsBypassMouseSensitivityAndInvertWhileKeepingCameraIdentity() {
        for(continuous in listOf(false,true)) {
            val r=Rig(continuous)
            assertTrue(r.engine.relativeMouse(8f,10f)) // Physical: +2 X, -40 Y after sensitivity/inversion.
            val old=r.camera()
            assertTrue(r.engine.syntheticCameraMotion(-3f))
            assertEquals(old.id,r.camera().id)
            assertEquals(old.x,r.camera().x,0f);assertEquals(old.y-3f,r.camera().y,0f)
            assertTrue(r.engine.syntheticCameraMotion(2f))
            assertEquals(old.y-1f,r.camera().y,0f)
            assertEquals(0L,r.engine.cameraRestarts)
        }
    }

    @Test fun stationaryPhysicalFireCanStartCameraAtItsOwnAnchorWithoutMovingOrPulsingM1() {
        val r=Rig();assertTrue(r.engine.key(MappingEngine.W,true));assertTrue(r.engine.mouseButton(1,true))
        val held=r.composer.snapshot();val count=r.frames.size
        assertFalse(r.composer.hasOwner(MappingEngine.CAMERA_OWNER))
        assertTrue(r.engine.syntheticCameraMotion(-2f))
        assertEquals(9,r.camera().id)
        assertEquals(r.geometry.cameraX,r.camera().x,0f);assertEquals(r.geometry.cameraY-2f,r.camera().y,0f)
        assertEquals(held,r.composer.snapshot().filter { it.owner!=MappingEngine.CAMERA_OWNER })
        assertEquals(listOf(TouchComposer.POINTER_DOWN,TouchComposer.MOVE),r.frames.drop(count).map { it.action and 255 })
        assertEquals(1L,r.engine.fireHoldStarts);assertEquals(0L,r.engine.fireHoldEnds)
        assertFalse(r.engine.canRetireIdleCamera)
    }

    @Test fun syntheticOutputDoesNotClaimPhysicalMouseButtonOwnership() {
        val r=Rig();assertTrue(r.engine.syntheticCameraMotion(1000f))
        assertTrue(r.engine.canRetireIdleCamera) // No physical button was pressed by the synthetic call.
        assertEquals(0L,r.engine.fireHoldStarts);assertEquals(0L,r.engine.fireHoldEnds)
    }

    @Test fun disabledCursorPausedAndNonFiniteCallsCannotCreateSyntheticContacts() {
        for(state in listOf("disabled","cursor","paused","nan","infinity")) {
            val r=Rig()
            when(state) {
                "disabled" -> assertTrue(r.engine.setCameraEnabled(false))
                "cursor" -> assertTrue(r.engine.enterCursorMode())
                "paused" -> assertTrue(r.engine.pause())
            }
            val before=r.frames.size
            val delta=when(state) { "nan" -> Float.NaN;"infinity" -> Float.POSITIVE_INFINITY;else -> -2f }
            assertFalse(r.engine.syntheticCameraMotion(delta))
            assertEquals(before,r.frames.size)
        }
    }

    @Test fun manualFireAimIsNeverReinterpretedAsSyntheticMovementOfTheTriggerFinger() {
        val r=Rig(continuous=false,manualFireAim=true)
        assertTrue(r.engine.mouseButton(1,true))
        val held=r.composer.snapshot();val before=r.frames.size
        assertFalse(r.engine.syntheticCameraMotion(-2f))
        assertEquals(held,r.composer.snapshot());assertEquals(before,r.frames.size)
    }

    @Test fun clockRunnerMovesOnlyCameraAndPhysicalReleaseCancelsAllFutureSyntheticWork() {
        data class Job(val at:Long,val action:()->Unit,var cancelled:Boolean=false)
        val r=Rig();val jobs=mutableListOf<Job>()
        val runner=FireCadenceRunner(PreparedFireCadence(verticalPixelsPerSecond=-100f),{r.now},
            {delay,task -> val job=Job(r.now+delay,task);jobs+=job;{job.cancelled=true}},
            r.engine::syntheticCameraMotion,{r.engine.cameraEnabled && !r.composer.paused})
        assertTrue(r.engine.key(MappingEngine.W,true));assertTrue(r.engine.mouseButton(1,true))
        val held=r.composer.snapshot();runner.input("mouse",1,true)
        val end=r.now+2000L
        while(true) {
            val next=jobs.filter { !it.cancelled && it.at<=end }.minByOrNull { it.at } ?: break
            jobs.remove(next);r.now=next.at;next.action()
        }
        assertTrue(runner.ticks>0L)
        assertEquals(9,r.camera().id)
        assertEquals(r.geometry.cameraY+runner.totalY.toFloat(),r.camera().y,.001f)
        assertEquals(held,r.composer.snapshot().filter { it.owner!=MappingEngine.CAMERA_OWNER })
        val primary=held.single { it.owner==r.fire.owner }
        assertEquals(1,r.frames.count { frame -> frame.action and 255 in listOf(0,5) && frame.ids[frame.action ushr 8]==primary.id })
        assertFalse(r.frames.any { frame -> frame.action and 255 in listOf(1,6) && frame.ids[frame.action ushr 8]==primary.id })
        assertEquals(0L,r.engine.cameraRestarts)
        runner.input("mouse",1,false,canStart=false);assertTrue(r.engine.mouseButton(1,false))
        val count=r.frames.size
        jobs.toList().forEach { r.now=maxOf(r.now,it.at);it.action() }
        assertEquals(count,r.frames.size)
        assertTrue(r.engine.pause());assertEquals(0,r.journal.snapshot().possibleContacts)
    }
}
