package dev.uwumouse.app.engine

import org.junit.Assert.*
import org.junit.Test

class FreeFireCompensationTest {
    @Test fun equalOppositeFlicksAndTheirPartitionsHaveNoAccumulatingResidue() {
        val axis=FreeFireCompensation(1286.4,1920.0,666_666.0)
        for(amplitude in listOf(.175,.7,7.0,70.0,280.0,7000.0)) {
            repeat(1000) {
                assertTrue(axis.advance(amplitude)>1286.4)
                assertEquals(1286.4,axis.advance(-amplitude),0.0)
            }
            val one=axis.advance(amplitude)
            axis.reset()
            repeat(256) { axis.advance(amplitude/256.0) }
            assertEquals(one,axis.advance(0.0),1e-8)
            repeat(256) { axis.advance(-amplitude/256.0) }
            assertEquals(1286.4,axis.advance(0.0),0.0)
        }
    }

    @Test fun reversalsReturnToAnExistingLargeCoordinateAndRetainFineMotion() {
        val axis=FreeFireCompensation(1286.4,1920.0,666_666.0)
        val position=axis.advance(1_000_000.0)
        val forward=axis.advance(.05)
        assertTrue(forward>position)
        assertEquals(position,axis.advance(-.05),1e-8)
        repeat(100_000) {
            axis.advance(280.0)
            axis.advance(-280.0)
        }
        assertEquals(position,axis.advance(0.0),1e-8)
        assertEquals(1286.4,axis.advance(-1_000_000.0),0.0)
    }

    @Test fun independentlyKnownInputIntegralReachesTheSamePointAtDifferentReferenceScales() {
        // Integral of 1/(.04+1.96/(1+5.6*z*z)) from z=0 to z=1.
        val inputAtOneReference=1.357725828630869
        for(reference in listOf(720.0,1920.0,2560.0)) {
            val axis=FreeFireCompensation(123.0,reference,666_666.0)
            assertEquals(123.0+reference,axis.advance(inputAtOneReference*reference),1e-7)
            axis.reset()
            assertEquals(123.0-reference,axis.advance(-inputAtOneReference*reference),1e-7)
        }
    }

    @Test fun extremeReportOverflowIsDiscardedAndFineReverseLeavesBothLimits() {
        val axis=FreeFireCompensation(1286.4,1920.0,666_666.0)
        assertTrue(axis.wouldClip(1e70))
        assertEquals(666_666.0,axis.advance(1e70),1e-8)
        assertTrue(axis.wouldClip(1.0))
        assertEquals(666_666.0,axis.advance(1.0),1e-8)
        assertFalse(axis.wouldClip(-1.0))
        assertTrue(axis.advance(-1.0)<666_666.0)
        assertEquals(-666_666.0,axis.advance(-1e70),1e-8)
        assertTrue(axis.advance(1.0)>-666_666.0)
        axis.reset()
        assertEquals(1286.4,axis.advance(0.0),0.0)
    }
}
