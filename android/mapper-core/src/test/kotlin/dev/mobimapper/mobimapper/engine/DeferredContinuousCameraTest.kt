package dev.mobimapper.mobimapper.engine

import dev.mobimapper.mobimapper.backend.recovery.RecoveryJournal
import dev.mobimapper.mobimapper.session.CameraIdlePolicy
import org.junit.Assert.*
import org.junit.Test

class DeferredContinuousCameraTest {
    private class Harness(regional:Boolean=true,private val recordFrames:Boolean=true) {
        val geometry=MappingGeometry(1000f,2000f)
        val fire=PreparedTouch("primary",850f,1600f,button=1)
        val journal=RecoveryJournal().apply { check(begin(1,1000,2000,10001,TouchCoordinatePolicy.CONTINUOUS_CAMERA_9)) }
        val frames=mutableListOf<TouchFrame>()
        var now=1000L
        var cameraDowns=0
        var cameraUps=0
        var cancellations=0
        val composer=TouchComposer({++now},{frame ->
            if(recordFrames) frames+=frame
            val action=frame.action and 255
            if(action in listOf(TouchComposer.DOWN,TouchComposer.POINTER_DOWN) && frame.ids[frame.action ushr 8]==9) cameraDowns++
            if(action in listOf(TouchComposer.UP,TouchComposer.POINTER_UP) && frame.ids[frame.action ushr 8]==9) cameraUps++
            if(action==TouchComposer.CANCEL) cancellations++
            check(journal.prepare(1,frame));check(journal.acknowledged(1,frame,true));true
        },continuousCamera=true)
        val engine=MappingEngine(composer,geometry,MappingBindings(continuousCamera=true,cameraLast=true,
            actionButton=null,touches=listOf(fire),retireBoundedCameraOnIdle=regional)).apply {
            check(setCameraEnabled(true));check(key(MappingEngine.W,true))
        }
        fun camera()=composer.snapshot().single { it.owner==MappingEngine.CAMERA_OWNER }
    }

    @Test fun aHundredThousandMovingSamplesKeepOneFingerWithoutRegionOrDisplayClamps() {
        val h=Harness(recordFrames=false)
        assertTrue(h.engine.mouseButton(1,true))
        val held=h.composer.snapshot()
        val policy=CameraIdlePolicy().apply { configureRate(100) }
        var time=1_000_000_000L
        var x=h.geometry.cameraX.toDouble();var y=h.geometry.cameraY.toDouble()
        val cycle=listOf(2000f to 3000f,-4000f to -6000f,8000f to 2000f,-6000f to 1000f)
        repeat(100_000) { index ->
            val (dx,dy)=cycle[index%cycle.size]
            time+=2_000_000L
            policy.sourceMotion(time);policy.motionReceived(time)
            assertTrue(h.engine.relativeMouse(dx,dy))
            policy.motionDelivered(time)
            x+=dx;y+=dy
            val camera=h.camera()
            assertEquals(9,camera.id);assertEquals(x.toFloat(),camera.x,0f);assertEquals(y.toFloat(),camera.y,0f)
            assertFalse(h.engine.canRetireIdleCamera) // Physical primary hold still owns both edges.
            assertTrue(requireNotNull(policy.remainingIdleNanos(time))>0L)
        }
        assertEquals(held,h.composer.snapshot().filter { it.owner!=MappingEngine.CAMERA_OWNER })
        assertEquals(1,h.cameraDowns);assertEquals(0,h.cameraUps);assertEquals(0,h.cancellations)
        assertEquals(0L,h.engine.cameraRestarts);assertEquals(0L,h.engine.heldFireCameraClamps)
        assertEquals(0L,h.engine.idleCameraRetirements)
        assertTrue(h.engine.pause());assertEquals(0,h.journal.snapshot().possibleContacts)
    }

    @Test fun physicalPrimaryHoldBlocksQuietRetirementAndNextPostUpDeltaStartsOnceAtAnchor() {
        val h=Harness()
        val movement=h.composer.snapshot().single()
        assertTrue(h.engine.relativeMouse(119f,0f));assertTrue(h.engine.canRetireIdleCamera)
        assertTrue(h.engine.mouseButton(1,true))
        val fire=h.composer.snapshot().single { it.owner==h.fire.owner }
        assertTrue(h.engine.relativeMouse(100_000f,-200_000f))
        assertEquals(fire,h.composer.snapshot().single { it.owner==h.fire.owner })
        assertFalse(h.engine.canRetireIdleCamera)
        val count=h.frames.size
        assertTrue(h.engine.retireIdleCamera());assertEquals(count,h.frames.size)
        assertTrue(h.engine.mouseButton(1,false))
        assertTrue(h.engine.canRetireIdleCamera)
        val outside=h.camera()
        assertTrue(h.engine.retireIdleCamera())
        assertEquals(listOf(movement),h.composer.snapshot())
        assertEquals(outside.x,h.frames.last().xs.last(),0f)
        assertTrue(h.engine.relativeMouse(3f,-2f))
        assertEquals(693f,h.camera().x,0f);assertEquals(858f,h.camera().y,0f)
        assertEquals(9,h.camera().id);assertTrue(h.engine.cameraEnabled)
        assertEquals(1L,h.engine.idleCameraRetirements);assertEquals(0L,h.engine.cameraRestarts)
        assertFalse(h.composer.hasOwner(h.fire.owner))
    }

    @Test fun cadencePolicyControlsQuietDeadlineRatherThanRegionalEligibilityAlone() {
        val h=Harness();assertTrue(h.engine.relativeMouse(119f,0f))
        assertTrue(h.engine.canRetireIdleCamera)
        val policy=CameraIdlePolicy().apply {
            configureRate(100)
            listOf(0L,8_000_000L,16_000_000L,24_000_000L).forEach(::sourceMotion)
            motionReceived(40_000_000L);motionDelivered(42_000_000L)
        }
        assertEquals(24_000_000L,policy.quietThresholdNanos)
        val count=h.frames.size
        assertEquals(1L,policy.remainingIdleNanos(65_999_999L))
        assertEquals(count,h.frames.size)
        assertEquals(0L,policy.remainingIdleNanos(66_000_000L))
        assertTrue(h.engine.retireIdleCamera())
        assertEquals(count+1,h.frames.size);assertEquals(1,h.cameraUps)
    }

    @Test fun ordinaryContinuousModeKeepsItsPreviousIdleCriteriaWithoutRegionalOptIn() {
        val stable=Harness(regional=false)
        assertTrue(stable.engine.relativeMouse(119f,0f));assertFalse(stable.engine.canRetireIdleCamera)
        assertTrue(stable.engine.relativeMouse(1000f,0f));assertTrue(stable.engine.canRetireIdleCamera)
        val deferred=Harness()
        assertTrue(deferred.engine.relativeMouse(Math.nextDown(119f),0f));assertFalse(deferred.engine.canRetireIdleCamera)
        assertTrue(deferred.engine.relativeMouse(1f,0f));assertTrue(deferred.engine.canRetireIdleCamera)
    }

    @Test fun finiteCoordinateLimitStillCancelsAtTheLastValidPointWithoutARebase() {
        val h=Harness()
        assertTrue(h.engine.relativeMouse(100_000f,-200_000f))
        val last=h.frames.last()
        assertFalse(h.engine.relativeMouse(TouchCoordinatePolicy.LIMIT*2,0f))
        assertEquals(TouchComposer.CANCEL,h.frames.last().action)
        assertArrayEquals(last.ids,h.frames.last().ids)
        assertArrayEquals(last.xs,h.frames.last().xs,0f);assertArrayEquals(last.ys,h.frames.last().ys,0f)
        assertTrue(h.composer.paused);assertFalse(h.engine.cameraEnabled)
        assertEquals(0L,h.engine.cameraRestarts);assertEquals(0L,h.engine.idleCameraRetirements)
        assertEquals(0,h.journal.snapshot().possibleContacts)
    }
}
