package dev.uwumouse.app.engine

import org.junit.Assert.*
import org.junit.Test

class EnhancedFreeFireCompensationTest {
    @Test fun positionalGainMatchesIndependentFloatReferenceVectorsWithinRoundingTolerance() {
        // Independent numeric reference vectors; the implementation evaluates an equivalent
        // expression in Double rather than rounding the proposed coordinate to Float.
        val vectors=listOf(
            doubleArrayOf(100.0,100.0,1.0,160.0,1.0606235389707115),
            doubleArrayOf(100.0,100.0,-80.0,160.0,.5903186870597561),
            doubleArrayOf(100.0,250.0,80.0,160.0,.21994942473812237),
            doubleArrayOf(100.0,-250.0,-80.0,600.0,.343516654518055),
            doubleArrayOf(1286.4,2071.04,400.0,600.0,.2249762381378424),
            doubleArrayOf(1286.4,2071.04,-400.0,600.0,.3420462860608414),
            doubleArrayOf(123.25,600000.0,2.0,440.0,.00024101155188975583),
            doubleArrayOf(100.0,100.0,.05,600.0,1.0712830286712391),
        )
        for(v in vectors) assertEquals(v[4],EnhancedFreeFireCompensation.gain(v[0],v[1],v[2],v[3]),
            maxOf(1e-9,kotlin.math.abs(v[4])*2e-6))
    }

    @Test fun spanBeyondThreeHundredAndDensityBothAffectTheCurrentReportWithoutATimer() {
        for(delta in listOf(-1000.0,-400.0,-300.0,-80.0,-.001,.001,80.0,300.0,400.0,1000.0)) {
            val at160=EnhancedFreeFireCompensation.gain(100.0,100.0,delta,160.0)
            val at600=EnhancedFreeFireCompensation.gain(100.0,100.0,delta,600.0)
            assertTrue(at160.isFinite() && at160>0.0)
            assertTrue(at600>at160)
            assertEquals(at600,EnhancedFreeFireCompensation.gain(100.0,100.0,-delta,600.0),0.0)
        }
        assertEquals(15.0/14.0,EnhancedFreeFireCompensation.gain(100.0,100.0,1e-20,600.0),1e-14)
        assertEquals(1.0,EnhancedFreeFireCompensation.gain(100.0,100.0,0.0,600.0),0.0)
    }

    @Test fun opposingReportsUseTheCurrentPositionAndResetRestoresOnlyTheCoordinateHistory() {
        val axis=EnhancedFreeFireCompensation(100.0,600.0,666_666.0,.7)
        val first=axis.advance(80.0)
        val reversed=axis.advance(-80.0)
        assertTrue(first>100.0)
        assertTrue(reversed<first)
        // This report-wise equation is intentionally not replaced with a reversible integral.
        assertTrue(kotlin.math.abs(reversed-100.0)>.001)
        val fresh=EnhancedFreeFireCompensation(100.0,600.0,666_666.0,.7)
        axis.reset()
        assertEquals(100.0,axis.advance(0.0),0.0)
        assertEquals(fresh.advance(.01),axis.advance(.01),0.0)
    }

    @Test fun fractionalReverseReportsRemainEffectiveAtALargeCoordinateAndOverflowNeverReplays() {
        val axis=EnhancedFreeFireCompensation(100.0,600.0,666_666.0,.7)
        assertTrue(axis.wouldClip(1e70))
        assertEquals(666_666.0,axis.advance(1e70),0.0)
        assertTrue(axis.wouldClip(2.0));assertFalse(axis.wouldClip(-2.0))
        val first=axis.advance(-2.0)
        assertTrue(first<666_666.0)
        assertEquals(666_666f,first.toFloat(),0f)
        var previous=first
        repeat(1000) {
            val next=axis.advance(-2.0)
            assertTrue(next<previous);previous=next
        }
        assertTrue(previous.toFloat()<666_666f)
        assertTrue(previous>666_660.0)
        assertEquals(previous,axis.advance(0.0),0.0)
        axis.reset()
        assertEquals(100.0,axis.advance(0.0),0.0)
    }

    @Test fun extremeFiniteReportsStayBoundedAndInvalidStateIsRejectedBeforeMutation() {
        val axis=EnhancedFreeFireCompensation(100.0,600.0,666_666.0,.7)
        for(delta in listOf(Double.MAX_VALUE,-Double.MAX_VALUE,Float.MAX_VALUE.toDouble(),-Float.MAX_VALUE.toDouble())) {
            assertTrue(axis.wouldClip(delta))
            assertTrue(axis.advance(delta) in -666_666.0..666_666.0)
        }
        val before=axis.advance(0.0)
        for(delta in listOf(Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { axis.advance(delta) }
            assertEquals(before,axis.advance(0.0),0.0)
        }
        for(density in listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY))
            assertThrows(IllegalArgumentException::class.java) { EnhancedFreeFireCompensation(0.0,density,666_666.0,1.0) }
    }
}
