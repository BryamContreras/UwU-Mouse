package dev.uwumouse.app.engine

import org.junit.Assert.*
import org.junit.Test

/** Numerical/stream regression; a game's own gesture recognition still needs device testing. */
class CameraStreamStabilityTest {
    private class Harness(sensitivity:Float) {
        var now=1_000L
        var deliveredX=0.0
        var deliveredY=0.0
        var previous:TouchFrame?=null
        val geometry=MappingGeometry(1000f,2000f,sensitivityX=sensitivity,sensitivityY=sensitivity)
        val composer=TouchComposer({now},{ frame ->
            if(frame.action==TouchComposer.MOVE) {
                val currentIndex=frame.ids.indexOf(TouchCoordinatePolicy.CAMERA_ID)
                val old=previous
                val oldIndex=old?.ids?.indexOf(TouchCoordinatePolicy.CAMERA_ID) ?: -1
                if(currentIndex>=0 && oldIndex>=0) {
                    deliveredX+=frame.xs[currentIndex]-old!!.xs[oldIndex]
                    deliveredY+=frame.ys[currentIndex]-old.ys[oldIndex]
                }
            }
            previous=when(frame.action and 255) {
                TouchComposer.UP,TouchComposer.CANCEL -> null
                TouchComposer.POINTER_UP -> {
                    val removed=frame.action ushr 8
                    frame.copy(ids=frame.ids.filterIndexed { index,_ -> index!=removed }.toIntArray(),
                        xs=frame.xs.filterIndexed { index,_ -> index!=removed }.toFloatArray(),
                        ys=frame.ys.filterIndexed { index,_ -> index!=removed }.toFloatArray())
                }
                else -> frame
            }
            true
        },continuousCamera=true)
        val engine=MappingEngine(composer,geometry,MappingBindings(continuousCamera=true,cameraLast=true)).apply {
            check(setCameraEnabled(true))
        }
        fun move(x:Float,y:Float=0f) {
            now++
            assertTrue(engine.relativeMouse(x,y))
            assertFalse(composer.paused)
        }
    }

    @Test fun longContinuousContactDoesNotAmplifyTheSameInputAsItsAgeAndPositionGrow() {
        val h=Harness(.25f)
        repeat(1000) { h.move(1f,.5f) }
        val firstX=h.deliveredX
        val firstY=h.deliveredY
        repeat(100_000) { h.move(1f,.5f) }
        val beforeX=h.deliveredX
        val beforeY=h.deliveredY
        repeat(1000) { h.move(1f,.5f) }
        assertEquals(250.0,firstX,0.0)
        assertEquals(125.0,firstY,0.0)
        assertEquals(firstX,h.deliveredX-beforeX,0.0)
        assertEquals(firstY,h.deliveredY-beforeY,0.0)
        assertEquals(1,h.composer.activeCount)
    }

    @Test fun smallStepsAtLargeCoordinatesPreserveNetGainDespiteFloatPayloadQuantization() {
        val h=Harness(.05f)
        repeat(49_500) { h.move(400f) }
        val before=h.deliveredX
        repeat(1000) { h.move(1f) }
        assertEquals(50.0,h.deliveredX-before,.0625)
        val beforeReverse=h.deliveredX
        repeat(1000) { h.move(-1f) }
        assertEquals(-50.0,h.deliveredX-beforeReverse,.0625)
        assertEquals(before,h.deliveredX,.0625)
    }
}
