package dev.mobimapper.mobimapper.engine

import dev.mobimapper.mobimapper.profile.*
import dev.mobimapper.mobimapper.session.RawMouseMotionBatch
import dev.mobimapper.mobimapper.session.PointerTransitionGate
import org.junit.Assert.*
import org.junit.Test

class SmoothMotionStabilityTest {
    private fun profile(width:Int=2560,height:Int=1440,area:GameArea=GameArea(0f,0f,width.toFloat(),height.toFloat()),scope:String="phone")=
        GameProfile.create("com.dts.freefireth","Stable camera",width,height,0,scope).let {
            it.copy(calibration=it.calibration.copy(area=area,confirmed=true,
                windowFrame=if(scope=="external") GameArea(0f,0f,width.toFloat(),height.toFloat()) else null),
                additionalActions=listOf(ActionSpec(.8f,.7f,"mouse",1)))
        }

    private class Harness(val profile:GameProfile) {
        var now=1000L
        val prepared=ProfileCompiler.prepare(profile,profile.calibration.screenWidth,profile.calibration.screenHeight,0,
            profile.calibration.displayScope,profile.calibration.windowFrame)
        val frames=mutableListOf<TouchFrame>()
        val composer=TouchComposer({now},{ frames+=it;true },continuousCamera=true)
        val engine=MappingEngine(composer,prepared.geometry,prepared.bindings,{now}).apply {
            setCameraEnabled(true);check(relativeMouse(1f,0f))
        }
        val precision=PrecisionAimFilter().apply { configure(prepared.profile.camera.precisionAim) }
        fun fire(held:Boolean) {
            val recognized=engine.mouseButton(1,held)
            precision.setFiring(held && recognized)
        }
        fun camera()=composer.snapshot().single { it.owner==MappingEngine.CAMERA_OWNER }
        fun batch(raw:RawMouseMotionBatch) {
            assertTrue(composer.beginMoveBatch())
            assertTrue(raw.deliverMotion { dx,dy,time -> engine.relativeMouse(precision.filterX(dx,time),dy) })
            assertTrue(composer.endMoveBatch())
        }
    }

    private fun run(batchSize:Int,precision:Boolean,fire:Boolean=true):Harness {
        val h=Harness(profile().let { it.copy(camera=it.camera.copy(precisionAim=precision)) })
        h.fire(fire)
        for(start in 0 until 256 step batchSize) {
            h.batch(RawMouseMotionBatch(LongArray(batchSize) { (start+it)*1_000_000L },
                FloatArray(batchSize) { .2f },FloatArray(batchSize) { -.25f }))
        }
        return h
    }

    @Test fun nativeBatchBoundariesDoNotChangeTheCompensatedDisplacement() {
        for(precision in listOf(false,true)) {
            val reference=run(1,precision)
            for(size in listOf(4,16,64,256)) {
                val batched=run(size,precision)
                assertEquals(reference.camera(),batched.camera())
                assertEquals(reference.precision.snapshot(),batched.precision.snapshot())
                assertTrue(batched.frames.count { it.action==TouchComposer.MOVE }<reference.frames.count { it.action==TouchComposer.MOVE })
            }
        }
    }

    @Test fun precisionActuallyDampsSmoothCameraOnlyDuringPhysicalFireAndPreservesY() {
        val plain=run(16,false)
        val precise=run(16,true)
        val released=run(16,true,fire=false)
        assertTrue(precise.camera().x<plain.camera().x)
        assertEquals(plain.camera().y,precise.camera().y,0f)
        assertEquals(plain.camera(),released.camera())
        assertEquals(256L,precise.precision.snapshot()["activeSamples"])
        assertTrue(precise.precision.snapshot()["attenuatedSamples"] as Long>0L)
        assertEquals(0L,released.precision.snapshot()["activeSamples"])
        precise.fire(false)
        assertEquals(1f,precise.precision.filterX(1f,257_000_000L),0f)
        assertEquals(false,precise.precision.snapshot()["firing"])
    }

    @Test fun smallMotionRetainsItsGainAfterCoordinatesGrowPastFloatPrecision() {
        val h=Harness(profile().let { it.copy(camera=it.camera.copy(sensitivityY=.01f)) })
        assertTrue(h.engine.syntheticCameraMotion(200_000f))
        val start=h.camera().y
        assertTrue(h.composer.beginMoveBatch())
        repeat(10_000) { assertTrue(h.engine.relativeMouse(0f,1f)) }
        assertTrue(h.composer.endMoveBatch())
        assertEquals(start+100f,h.camera().y,.016f)
    }

    @Test fun sameGameWindowHasTheSameCompensationOnDifferentDisplayResolutions() {
        val game=GameArea(0f,0f,1280f,720f)
        val small=Harness(profile(1920,1080,game))
        val large=Harness(profile(2560,1440,game))
        assertEquals(1280f,requireNotNull(small.prepared.bindings.ggCameraPolicy).referenceWidth,0f)
        val raw=RawMouseMotionBatch(LongArray(256) { it*1_000_000L },FloatArray(256) { 2f },FloatArray(256) { 1f })
        small.batch(raw);large.batch(raw)
        assertEquals(small.camera(),large.camera())
    }

    @Test fun proportionalGameGeometryAndInputProduceProportionalOutput() {
        val small=Harness(profile(1280,720));val large=Harness(profile(2560,1440))
        small.batch(RawMouseMotionBatch(LongArray(256) { it*1_000_000L },FloatArray(256) { 2f },FloatArray(256) { 1f }))
        large.batch(RawMouseMotionBatch(LongArray(256) { it*1_000_000L },FloatArray(256) { 4f },FloatArray(256) { 2f }))
        assertEquals(small.camera().x*2f,large.camera().x,.0001f)
        assertEquals(small.camera().y*2f,large.camera().y,.0001f)
    }

    @Test fun phoneAndExternalDisplayUseIdenticalCompensationAndPrecision() {
        for(size in listOf(1080 to 2400,1920 to 1080,2560 to 1440)) {
            val phone=Harness(profile(size.first,size.second).let { it.copy(camera=it.camera.copy(precisionAim=true)) })
            val external=Harness(profile(size.first,size.second,scope="external").let { it.copy(camera=it.camera.copy(precisionAim=true)) })
            phone.fire(true);external.fire(true)
            val raw=RawMouseMotionBatch(LongArray(256) { it*2_000_000L },
                FloatArray(256) { if(it%2==0) .3f else -.2f },FloatArray(256) { -.25f })
            phone.batch(raw);external.batch(raw)
            assertEquals(phone.camera(),external.camera())
            assertEquals(phone.precision.snapshot(),external.precision.snapshot())
            assertEquals(256L,phone.precision.snapshot()["activeSamples"])
            assertTrue(phone.precision.snapshot()["attenuatedSamples"] as Long>0)
        }
    }

    @Test fun suspensionRoundTripResetsCameraAndPrecisionWithoutChangingSavedGains() {
        val h=Harness(profile().let { it.copy(camera=it.camera.copy(precisionAim=true,suspendMouseButton=16)) })
        val start=h.camera();val saved=h.profile
        h.fire(true)
        h.batch(RawMouseMotionBatch(longArrayOf(0,8_000_000),floatArrayOf(1f,1f),floatArrayOf(1f,1f)))
        assertTrue(h.camera().x>start.x)
        assertTrue((h.precision.snapshot()["lastGain"] as Float)<1f)
        val gate=PointerTransitionGate().apply { begin(false) }
        val enter=requireNotNull(gate.hold(true));assertEquals(true,gate.requestedCursor(enter))
        h.precision.reset();assertTrue(h.engine.enterCursorMode());gate.acknowledge(enter,true)
        assertTrue(h.composer.snapshot().isEmpty())
        assertEquals(false,h.precision.snapshot()["firing"])
        val leave=requireNotNull(gate.hold(false));assertEquals(false,gate.requestedCursor(leave))
        assertTrue(h.engine.leaveCursorMode());gate.acknowledge(leave,false)
        assertTrue(h.engine.relativeMouse(1f,0f))
        assertEquals(start,h.camera());assertEquals(saved,h.profile)
        assertEquals(1f,h.precision.filterX(1f,16_000_000),0f)
    }
}
