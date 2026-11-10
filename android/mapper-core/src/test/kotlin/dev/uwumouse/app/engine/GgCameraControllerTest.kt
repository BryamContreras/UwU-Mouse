package dev.uwumouse.app.engine

import org.junit.Assert.*
import org.junit.Test

class GgCameraControllerTest {
    @Test fun genericCameraWithoutAContactNeedsNoIdleWakeupsOrClockReads() {
        var now=1000L
        var clockReads=0
        val composer=TouchComposer({now},{true},continuousCamera=true)
        val geometry=MappingGeometry(1000f,2000f)
        val controller=GgCameraController(composer,geometry,
            GgCameraPolicy(false,AimBounds(50f,50f,950f,1950f),2000f),{ clockReads++;now })
        controller.activate()
        val readsAfterActivation=clockReads
        repeat(10_000) {
            now+=350L
            assertNull(controller.nextWakeupDelayMillis)
            assertTrue(controller.dispatchTimers())
        }
        assertEquals(readsAfterActivation,clockReads)
        assertEquals(0,composer.activeCount)
    }

    @Test fun freeFireMotionAndTimerQueriesDoNotReadAnUnusedIdleClock() {
        var idleClockReads=0
        var deliveryClockReads=0
        val frames=mutableListOf<TouchFrame>()
        val composer=TouchComposer({ deliveryClockReads++;1000L },{ frames+=it;true },continuousCamera=true)
        val geometry=MappingGeometry(2560f,1440f)
        val controller=GgCameraController(composer,geometry,
            GgCameraPolicy(true,AimBounds(0f,0f,2560f,1440f),2560f),{ idleClockReads++;1000L })
        controller.activate()
        repeat(1000) {
            assertTrue(controller.motion(.25f,-.5f))
            assertTrue(controller.dispatchTimers())
            assertNull(controller.nextWakeupDelayMillis)
        }
        assertEquals(0,idleClockReads)
        assertTrue(deliveryClockReads>0)
        assertEquals(1001,frames.size) // One admitted DOWN plus every motion, including the first.
    }

    private class Harness(
        freeFire:Boolean=false,
        val geometry:MappingGeometry=MappingGeometry(1000f,2000f),
    ) {
        var now=1000L
        var rejectUp=false
        var rejectDown=false
        var rejectCancel=false
        val frames=mutableListOf<TouchFrame>()
        val composer=TouchComposer({now},{ frame ->
            frames+=frame
            when(frame.action and 255) {
                TouchComposer.UP,TouchComposer.POINTER_UP -> !rejectUp
                TouchComposer.DOWN,TouchComposer.POINTER_DOWN -> !rejectDown
                TouchComposer.CANCEL -> !rejectCancel
                else -> true
            }
        },continuousCamera=true)
        val controller=GgCameraController(composer,geometry,
            GgCameraPolicy(freeFire,AimBounds(50f,50f,950f,1950f),maxOf(geometry.width,geometry.height)),{now}).apply { activate() }
        fun camera():Contact=composer.snapshot().single { it.owner==MappingEngine.CAMERA_OWNER }
        fun startAtAnchor() {
            assertTrue(controller.motion(1f,0f))
            assertTrue(controller.motion(-1f,0f))
        }
    }

    @Test fun genericFirstSampleCreatesFingerThenUsesIndependentLinearGainAndInversion() {
        val h=Harness(geometry=MappingGeometry(1000f,2000f,sensitivityX=.7f,sensitivityY=.35f,invertY=true))
        assertTrue(h.frames.isEmpty())
        assertTrue(h.controller.motion(400f,200f))
        assertEquals(listOf(TouchComposer.DOWN),h.frames.map { it.action })
        assertEquals(690f,h.camera().x,0f);assertEquals(860f,h.camera().y,0f)
        assertTrue(h.controller.motion(10f,20f))
        assertEquals(697f,h.camera().x,0f);assertEquals(853f,h.camera().y,0f)
        assertEquals(9,h.camera().id)
        assertEquals(0L,h.controller.restarts)
    }

    @Test fun edgeReleaseDiscardsOverflowAndCooldownSamplesAndSchedulesDownWithoutNewInput() {
        val h=Harness()
        assertTrue(h.controller.motion(1f,0f))
        assertTrue(h.controller.motion(500f,0f))
        val up=h.frames.last()
        assertEquals(TouchComposer.UP,up.action)
        assertEquals(1190f,up.xs.single(),0f)
        assertTrue(h.composer.snapshot().isEmpty())
        assertEquals(1L,h.controller.restarts)
        h.now=1005L
        assertTrue(h.controller.motion(77f,20f))
        assertEquals(10L,h.controller.nextWakeupDelayMillis)
        h.now=1014L
        assertTrue(h.controller.motion(-200f,-20f))
        assertEquals(2,h.frames.size)
        h.now=1015L
        assertTrue(h.controller.dispatchTimers())
        assertEquals(TouchComposer.DOWN,h.frames.last().action)
        assertEquals(690f,h.camera().x,0f);assertEquals(860f,h.camera().y,0f)
        assertTrue(h.controller.motion(3f,-2f))
        assertEquals(693f,h.camera().x,0f);assertEquals(858f,h.camera().y,0f)
    }

    @Test fun genericIdleRetiresOnlyCameraAfterSevenHundredMillisecondsEvenWhileFireRemainsHeld() {
        val h=Harness()
        assertTrue(h.composer.begin("shoot",850f,1400f,preferredId=0))
        val fire=h.composer.snapshot().single()
        assertTrue(h.controller.motion(1f,0f))
        h.now=1350L
        assertTrue(h.controller.dispatchTimers())
        assertEquals(0L,h.controller.idleRetirements)
        h.now=1700L
        assertTrue(h.controller.dispatchTimers())
        val up=h.frames.last()
        assertEquals(TouchComposer.POINTER_UP,up.action and 255)
        assertEquals(9,up.ids[up.action ushr 8])
        assertEquals(listOf(fire),h.composer.snapshot())
        assertEquals(1L,h.controller.idleRetirements)
        assertEquals(0L,h.controller.restarts)
        assertNull(h.controller.nextWakeupDelayMillis)
    }

    @Test fun cameraRearmsItsOriginalIdleCheckPhaseAfterALongQuietInterval() {
        val h=Harness()
        assertNull(h.controller.nextWakeupDelayMillis)
        h.now=5000L
        assertTrue(h.controller.motion(1f,0f))
        assertEquals(200L,h.controller.nextWakeupDelayMillis)
        h.now=5550L
        assertTrue(h.controller.dispatchTimers())
        assertEquals(1,h.composer.activeCount)
        h.now=5900L
        assertTrue(h.controller.dispatchTimers())
        assertEquals(0,h.composer.activeCount)
        assertNull(h.controller.nextWakeupDelayMillis)
        h.now=20_000L
        assertTrue(h.controller.motion(1f,0f))
        assertEquals(250L,h.controller.nextWakeupDelayMillis)
        assertEquals(1,h.composer.activeCount)
        assertEquals(1L,h.controller.idleRetirements)
    }

    @Test fun anEdgeReleaseWithoutMoreMotionHasNoTimerButCooldownInputStillSchedulesItsDown() {
        val h=Harness()
        assertTrue(h.controller.motion(1f,0f))
        assertTrue(h.controller.motion(500f,0f))
        assertNull(h.controller.nextWakeupDelayMillis)
        h.now=1005L
        assertTrue(h.controller.motion(1f,0f))
        assertEquals(10L,h.controller.nextWakeupDelayMillis)
        h.now=1015L
        assertTrue(h.controller.dispatchTimers())
        assertEquals(1,h.composer.activeCount)
        assertEquals(335L,h.controller.nextWakeupDelayMillis)
    }

    @Test fun zeroOrSubEpsilonMotionRefreshesIdleDeadlineWithoutInjectingMove() {
        val h=Harness()
        assertTrue(h.controller.motion(1f,0f))
        h.now=1350L
        assertTrue(h.controller.dispatchTimers())
        h.now=1699L
        assertTrue(h.controller.motion(.00001f,0f))
        h.now=1700L
        assertTrue(h.controller.dispatchTimers())
        h.now=2050L
        assertTrue(h.controller.dispatchTimers())
        assertEquals(1,h.frames.size)
        h.now=2400L
        assertTrue(h.controller.dispatchTimers())
        assertEquals(1L,h.controller.idleRetirements)
        assertEquals(TouchComposer.UP,h.frames.last().action)
    }

    @Test fun resetCancelsDelayedContactAndPreservesSessionCountersAcrossActivation() {
        val h=Harness()
        assertTrue(h.controller.motion(1f,0f))
        assertTrue(h.controller.motion(500f,0f))
        h.now=1005L
        assertTrue(h.controller.motion(1f,0f))
        assertEquals(10L,h.controller.nextWakeupDelayMillis)
        h.controller.reset()
        h.now=1020L
        assertTrue(h.controller.dispatchTimers())
        assertNull(h.controller.nextWakeupDelayMillis)
        assertTrue(h.composer.snapshot().isEmpty())
        assertEquals(2,h.frames.size)
        h.controller.activate()
        assertTrue(h.controller.motion(100f,0f))
        assertEquals(690f,h.camera().x,0f)
        assertEquals(1L,h.controller.restarts)
    }

    @Test fun freeFireIntegratesStandardCompensationAndOnlyRenewsAtItsCoordinateLimit() {
        val h=Harness(freeFire=true)
        h.startAtAnchor()
        assertTrue(h.controller.motion(1000f,0f))
        // Independent bisection of the standard curve's input integral, reference=2000.
        // A large report integrates along the curve instead of freezing its initial gain.
        assertEquals(1899.716240045f,h.camera().x,.001f)
        assertTrue(h.controller.motion(100f,0f))
        assertEquals(1965.693216334f,h.camera().x,.001f)
        h.now=100_000L
        assertTrue(h.controller.dispatchTimers())
        assertNull(h.controller.nextWakeupDelayMillis)
        assertEquals(0L,h.controller.idleRetirements)
        assertTrue(h.controller.motion(100_000_000f,-100_000_000f))
        assertEquals(666_666f,h.camera().x,0f)
        assertEquals(-666_666f,h.camera().y,0f)
        assertEquals(1L,h.controller.restarts)
    }

    @Test fun freeFireOpposingFlicksReturnExactlyToTheAnchorOnPhoneAndExternalGeometry() {
        for((width,height) in listOf(1080f to 2400f,1920f to 1080f,2560f to 1440f)) {
            for(inverted in listOf(false,true)) {
                val h=Harness(true,MappingGeometry(width,height,sensitivityX=.7f,sensitivityY=.7f,invertX=inverted))
                h.startAtAnchor()
                val anchor=h.camera()
                for(size in listOf(.25f,1f,10f,100f,400f,10_000f)) {
                    assertTrue(h.controller.motion(size,size*.25f))
                    assertTrue(h.controller.motion(-size,-size*.25f))
                    assertEquals("reversal $size on $width x $height",anchor,h.camera())
                }
                assertEquals(0L,h.controller.restarts)
            }
        }
    }

    @Test fun freeFireColdOpposingFlicksConsumeTheFirstReportAndReturnToTheActualDown() {
        for((width,height) in listOf(1080f to 2400f,1920f to 1080f,2560f to 1440f)) {
            for(inverted in listOf(false,true)) {
                val h=Harness(true,MappingGeometry(width,height,sensitivityX=.7f,sensitivityY=.7f,invertX=inverted))
                assertTrue(h.controller.motion(100f,25f))
                assertEquals(listOf(TouchComposer.DOWN,TouchComposer.MOVE),h.frames.map { it.action })
                val down=h.frames.first()
                val anchor=Contact(MappingEngine.CAMERA_OWNER,9,down.xs.single(),down.ys.single())
                assertNotEquals(anchor,h.camera())
                assertTrue(h.controller.motion(-100f,-25f))
                assertEquals(anchor,h.camera())
                assertEquals(0L,h.controller.restarts)
            }
        }
    }

    @Test fun freeFireColdSingleFlickAndPartitionedReportsReachTheSamePoint() {
        for(batched in listOf(false,true)) {
            val one=Harness(true,MappingGeometry(1920f,1080f,sensitivityX=.7f,sensitivityY=.7f))
            val split=Harness(true,one.geometry)
            if(batched) {
                assertTrue(one.composer.beginMoveBatch())
                assertTrue(split.composer.beginMoveBatch())
            }
            assertTrue(one.controller.motion(400f,-200f))
            repeat(4) { assertTrue(split.controller.motion(100f,-50f)) }
            if(batched) {
                assertTrue(one.composer.endMoveBatch())
                assertTrue(split.composer.endMoveBatch())
            }
            assertEquals(one.camera(),split.camera())
            assertTrue(one.controller.motion(-400f,200f))
            repeat(4) { assertTrue(split.controller.motion(-100f,50f)) }
            assertEquals(one.camera(),split.camera())
            assertEquals(one.geometry.cameraX,one.camera().x,0f)
            assertEquals(one.geometry.cameraY,one.camera().y,0f)
        }
    }

    @Test fun failedColdDownNeverConsumesTheFirstReportAsAMove() {
        val h=Harness(true)
        h.rejectDown=true
        assertFalse(h.controller.motion(400f,200f))
        assertEquals(listOf(TouchComposer.DOWN,TouchComposer.CANCEL),h.frames.map { it.action })
        assertTrue(h.composer.paused)
        assertFalse(h.composer.releaseUnverified)
        assertTrue(h.composer.snapshot().isEmpty())
        h.rejectDown=false
        assertFalse(h.controller.motion(-400f,-200f))
        assertEquals(2,h.frames.size)
    }

    @Test fun freeFireCompensationUsesTheActualDownAnchorAtTheLastDisplayPixel() {
        val h=Harness(true,MappingGeometry(1280f,720f,cameraX=1279.5f,cameraY=719.5f,
            cameraRadiusX=.1f,cameraRadiusY=.1f,sensitivityX=.7f,sensitivityY=.7f))
        h.startAtAnchor()
        val anchor=h.camera()
        assertEquals(1279f,anchor.x,0f)
        assertEquals(719f,anchor.y,0f)
        assertTrue(h.controller.motion(100f,25f))
        assertTrue(h.controller.motion(-100f,-25f))
        assertEquals(anchor,h.camera())
        assertTrue(h.composer.end(MappingEngine.CAMERA_OWNER))
        h.controller.activate()
        h.startAtAnchor()
        assertEquals(anchor,h.camera())
        assertTrue(h.controller.motion(100_000_000f,100_000_000f))
        assertEquals(0L,h.controller.restarts)
        assertTrue(h.controller.motion(1f,1f))
        assertEquals(1L,h.controller.restarts)
        assertTrue(h.camera().x<1282f)
        assertEquals(719.7f,h.camera().y,.0001f)
    }

    @Test fun freeFireLimitRenewalPreservesHeldContactsAndConsumesTheFreshMotion() {
        val h=Harness(true)
        assertTrue(h.composer.begin("joystick",200f,1400f,preferredId=0))
        assertTrue(h.composer.begin("fire",850f,1300f,preferredId=1))
        val ordinary=h.composer.snapshot()
        h.startAtAnchor()
        assertTrue(h.controller.motion(0f,100_000_000f))
        assertEquals(666_666f,h.camera().y,0f)
        val start=h.frames.size
        assertTrue(h.controller.motion(10f,10f))
        val renewal=h.frames.drop(start)
        assertEquals(listOf(TouchComposer.POINTER_UP,TouchComposer.POINTER_DOWN,TouchComposer.MOVE),renewal.map { it.action and 255 })
        assertEquals(9,renewal[0].ids[renewal[0].action ushr 8])
        assertEquals(9,renewal[1].ids[renewal[1].action ushr 8])
        assertEquals(666_666f,renewal[0].ys.last(),0f)
        assertEquals(h.geometry.cameraY,renewal[1].ys.last(),0f)
        assertEquals(h.geometry.cameraY+10f,h.camera().y,0f)
        assertTrue(h.camera().x>h.geometry.cameraX)
        assertEquals(ordinary,h.composer.snapshot().filter { it.owner!=MappingEngine.CAMERA_OWNER })
        assertEquals(1L,h.controller.restarts)
        assertFalse(h.composer.paused)
        assertFalse(h.composer.releaseUnverified)
    }

    @Test fun freeFireHorizontalLimitRenewsForFurtherOutwardInputButReversesWithoutReplay() {
        val h=Harness(true)
        h.startAtAnchor()
        assertTrue(h.controller.motion(100_000_000f,0f))
        assertEquals(666_666f,h.camera().x,0f)
        assertTrue(h.controller.motion(-1f,0f))
        assertTrue(h.controller.motion(-1f,0f))
        assertTrue(h.camera().x<666_666f)
        assertEquals(0L,h.controller.restarts)
        assertTrue(h.controller.motion(100_000_000f,0f))
        assertEquals(666_666f,h.camera().x,0f)
        assertTrue(h.controller.motion(1f,0f))
        assertTrue(h.camera().x>h.geometry.cameraX)
        assertTrue(h.camera().x<h.geometry.cameraX+3f)
        assertEquals(2L,h.controller.restarts)
    }

    @Test fun failedLimitUpNeverAdmitsANewCameraAndRetainsUnverifiedRelease() {
        val h=Harness(true)
        h.startAtAnchor()
        assertTrue(h.controller.motion(0f,100_000_000f))
        val start=h.frames.size
        h.rejectUp=true;h.rejectCancel=true
        assertFalse(h.controller.motion(0f,1f))
        assertTrue(h.composer.paused)
        assertTrue(h.composer.releaseUnverified)
        assertEquals(listOf(TouchComposer.UP,TouchComposer.CANCEL),h.frames.drop(start).map { it.action and 255 })
        assertEquals(0L,h.controller.restarts)
        assertFalse(h.controller.motion(0f,1f))
        assertEquals(1,h.frames.count { it.action==TouchComposer.DOWN })
    }

    @Test fun failedLimitDownStopsBeforeFreshMoveAndActivationClearsTheInputCoordinate() {
        val h=Harness(true)
        h.startAtAnchor()
        assertTrue(h.controller.motion(0f,100_000_000f))
        val start=h.frames.size
        h.rejectDown=true
        assertFalse(h.controller.motion(0f,1f))
        assertTrue(h.composer.paused)
        assertEquals(listOf(TouchComposer.UP,TouchComposer.DOWN,TouchComposer.CANCEL),h.frames.drop(start).map { it.action and 255 })
        assertEquals(0L,h.controller.restarts)
        h.rejectDown=false
        h.composer.acknowledgeBackendReset()
        assertTrue(h.composer.resume())
        h.controller.activate()
        h.startAtAnchor()
        val anchor=h.camera()
        assertTrue(h.controller.motion(100f,0f))
        assertTrue(h.controller.motion(-100f,0f))
        assertEquals(anchor,h.camera())
    }

    @Test fun anchorsAreDeterministicAndSyntheticPixelsBypassPhysicalGain() {
        val generic=Harness()
        assertTrue(generic.controller.motion(1f,0f))
        assertEquals(690f,generic.camera().x,0f);assertEquals(860f,generic.camera().y,0f)
        assertTrue(generic.composer.end(MappingEngine.CAMERA_OWNER))
        generic.controller.activate()
        assertTrue(generic.controller.motion(1f,0f))
        assertEquals(690f,generic.camera().x,0f);assertEquals(860f,generic.camera().y,0f)
        val ff=Harness(freeFire=true,geometry=MappingGeometry(1000f,2000f,
            sensitivityX=4f,sensitivityY=3f,invertY=true))
        ff.startAtAnchor()
        assertEquals(690f,ff.camera().x,0f);assertEquals(860f,ff.camera().y,0f)
        assertTrue(ff.controller.syntheticMotion(30f))
        assertEquals(690f,ff.camera().x,0f);assertEquals(890f,ff.camera().y,0f)
    }

    @Test fun rejectedEdgeUpPausesAndNeverSchedulesAnotherDown() {
        val h=Harness()
        assertTrue(h.controller.motion(1f,0f))
        h.rejectUp=true
        assertFalse(h.controller.motion(500f,0f))
        assertTrue(h.composer.paused)
        assertEquals(TouchComposer.CANCEL,h.frames.last().action)
        assertEquals(0L,h.controller.restarts)
        assertNull(h.controller.nextWakeupDelayMillis)
        h.now=2000L
        assertFalse(h.controller.dispatchTimers())
        assertEquals(1,h.frames.count { it.action==TouchComposer.DOWN })
    }

    @Test fun genericExteriorCoordinatesBeyondTransportLimitCancelInsteadOfWrappingOrRecycling() {
        val h=Harness()
        assertTrue(h.controller.motion(1f,0f))
        assertFalse(h.controller.motion(Float.MAX_VALUE,0f))
        assertTrue(h.composer.paused)
        assertEquals("CAMERA_COORDINATE_LIMIT",h.composer.lastError)
        assertTrue(h.composer.snapshot().isEmpty())
        assertEquals(TouchComposer.CANCEL,h.frames.last().action)
        assertEquals(0L,h.controller.restarts)
    }
}
