package dev.mobimapper.mobimapper.engine

import org.junit.Assert.*
import org.junit.Test

class GgCameraControllerTest {
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
        assertEquals(1000,frames.size)
    }

    private class Harness(
        freeFire:Boolean=false,
        val geometry:MappingGeometry=MappingGeometry(1000f,2000f),
    ) {
        var now=1000L
        var rejectUp=false
        val frames=mutableListOf<TouchFrame>()
        val composer=TouchComposer({now},{ frame ->
            frames+=frame
            !(rejectUp && (frame.action and 255) in listOf(TouchComposer.UP,TouchComposer.POINTER_UP))
        },continuousCamera=true)
        val controller=GgCameraController(composer,geometry,
            GgCameraPolicy(freeFire,AimBounds(50f,50f,950f,1950f),maxOf(geometry.width,geometry.height)),{now}).apply { activate() }
        fun camera():Contact=composer.snapshot().single { it.owner==MappingEngine.CAMERA_OWNER }
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

    @Test fun freeFireStandardCompensationMatchesKnownCoordinatesAndHasNoAutomaticRetirement() {
        val h=Harness(freeFire=true)
        assertTrue(h.controller.motion(1f,0f))
        assertTrue(h.controller.motion(1000f,0f))
        assertEquals(2690f,h.camera().x,0f)
        // At a horizontal distance of one landscape width, GG's standard factor is .3369697.
        // This fixture distinguishes 5.6*r*r from the incorrectly squared (5.6*r) term.
        assertTrue(h.controller.motion(100f,0f))
        assertEquals(2723.697f,h.camera().x,.001f)
        h.now=100_000L
        assertTrue(h.controller.dispatchTimers())
        assertNull(h.controller.nextWakeupDelayMillis)
        assertEquals(0L,h.controller.idleRetirements)
        assertTrue(h.controller.motion(100_000_000f,-100_000_000f))
        assertEquals(666_666f,h.camera().x,0f)
        assertEquals(-666_666f,h.camera().y,0f)
        assertEquals(0L,h.controller.restarts)
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
        assertTrue(ff.controller.motion(1f,1f))
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
