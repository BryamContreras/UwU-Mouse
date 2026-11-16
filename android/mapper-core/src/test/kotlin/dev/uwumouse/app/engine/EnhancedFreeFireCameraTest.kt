package dev.uwumouse.app.engine

import org.junit.Assert.*
import org.junit.Test

class EnhancedFreeFireCameraTest {
    private class Harness(enhanced:Boolean=true,density:Float=600f,freeFire:Boolean=true) {
        val geometry=MappingGeometry(2560f,1440f,sensitivityX=.45f,sensitivityY=1.35f)
        val frames=mutableListOf<TouchFrame>()
        var rejectCameraUp=false
        val composer=TouchComposer({1000L},{ frame ->
            frames+=frame
            !(rejectCameraUp && (frame.action and 255) in listOf(TouchComposer.UP,TouchComposer.POINTER_UP))
        },continuousCamera=true)
        val controller=UwUCameraController(composer,geometry,
            UwUCameraPolicy(freeFire,AimBounds(50f,50f,2510f,1390f),2560f,enhanced,density),{1000L}).apply { activate() }
        fun camera()=composer.snapshot().single { it.owner==MappingEngine.CAMERA_OWNER }
    }

    @Test fun disabledStrategyRetainsTheStandardCoordinateArithmeticForEveryOriginalReport() {
        val h=Harness(enhanced=false)
        val reference=FreeFireCompensation(h.geometry.cameraX.toDouble(),2560.0,666_666.0)
        val deltas=listOf(.25f,-.25f,4f,-8f,400f,-400f,.1f)
        repeat(1000) { index ->
            val dx=deltas[index%deltas.size]
            assertTrue(h.controller.motion(dx,0f))
            val expected=reference.advance(dx.toDouble()*h.geometry.sensitivityX).toFloat()
            assertEquals(expected.toRawBits(),h.camera().x.toRawBits())
        }
        assertNull(h.controller.nextWakeupDelayMillis)
    }

    @Test fun originNormalizationAppliesOnlyToHorizontalMotionAndSyntheticVerticalPixelsStayUnscaled() {
        val h=Harness()
        assertTrue(h.controller.motion(.25f,2f))
        assertEquals(h.geometry.cameraX+.25f*.45f*2f,h.camera().x,2f*Math.ulp(h.geometry.cameraX))
        assertEquals((h.geometry.cameraY.toDouble()+2.0*h.geometry.sensitivityY).toFloat(),h.camera().y,0f)
        val x=h.camera().x;val y=h.camera().y
        assertTrue(h.controller.syntheticMotion(.5f))
        assertEquals(x,h.camera().x,0f);assertEquals(y+.5f,h.camera().y,.0001f)
        assertNull(h.controller.nextWakeupDelayMillis)
    }

    @Test fun enabledDensityChangesHorizontalGainWithoutChangingVerticalGain() {
        val low=Harness(density=160f);val high=Harness(density=600f)
        assertTrue(low.controller.motion(400f,2f));assertTrue(high.controller.motion(400f,2f))
        assertTrue(high.camera().x>low.camera().x)
        assertEquals(high.camera().y,low.camera().y,0f)
        for(density in listOf(160f,600f)) {
            val generic=Harness(enhanced=true,density=density,freeFire=false)
            assertTrue(generic.controller.motion(1f,0f))
            assertTrue(generic.controller.motion(10f,2f))
            assertEquals(generic.geometry.cameraX+10f*.45f,generic.camera().x,0f)
        }
    }

    @Test fun coordinateLimitRenewalKeepsOtherContactsAndConsumesTheNewReportFromTheAnchor() {
        val h=Harness()
        assertTrue(h.composer.begin("fire",2200f,1000f))
        assertTrue(h.controller.motion(Float.MAX_VALUE,0f))
        assertEquals(666_666f,h.camera().x,0f)
        val held=h.composer.snapshot().single { it.owner=="fire" }
        assertTrue(h.controller.motion(2f,0f))
        assertEquals(1L,h.controller.restarts)
        assertEquals(held,h.composer.snapshot().single { it.owner=="fire" })
        assertEquals(9,h.camera().id)
        assertTrue(h.camera().x>h.geometry.cameraX && h.camera().x<h.geometry.cameraX+4f)
    }

    @Test fun rejectedLimitReleaseNeverStartsOrMovesAFreshContact() {
        val h=Harness()
        assertTrue(h.controller.motion(Float.MAX_VALUE,0f))
        h.rejectCameraUp=true
        val before=h.frames.size
        assertFalse(h.controller.motion(2f,0f))
        assertTrue(h.frames.drop(before).none { (it.action and 255)==TouchComposer.DOWN || (it.action and 255)==TouchComposer.MOVE })
        assertTrue(h.composer.paused)
    }

    @Test fun attenuatedOneUnitReverseReportsAccumulateUntilTheDeliveredFloatLeavesTheLimit() {
        val h=Harness(density=160f)
        assertTrue(h.controller.motion(Float.MAX_VALUE,0f))
        assertEquals(666_666f,h.camera().x,0f)
        repeat(2000) { assertTrue(h.controller.motion(-1f,0f)) }
        assertTrue(h.camera().x<666_666f)
        assertEquals(0L,h.controller.restarts)
        assertEquals(1,h.composer.activeCount)
    }

    @Test fun anAttenuatedOutwardOneUnitReportAtTheLimitStillRenewsTheContact() {
        val h=Harness(density=160f)
        assertTrue(h.controller.motion(Float.MAX_VALUE,0f))
        val before=h.frames.size
        assertTrue(h.controller.motion(1f,0f))
        assertEquals(1L,h.controller.restarts)
        assertEquals(listOf(TouchComposer.UP,TouchComposer.DOWN,TouchComposer.MOVE),
            h.frames.drop(before).map { it.action and 255 })
        assertTrue(h.camera().x>h.geometry.cameraX && h.camera().x<h.geometry.cameraX+2f)
        assertEquals(9,h.camera().id)
    }
}
