package dev.mobimapper.mobimapper.editor

import kotlin.math.pow
import org.junit.Assert.*
import org.junit.Test

class OverlayOpacityBudgetTest {
    private fun combined(alpha:Float,layers:Int)=1.0-(1.0-alpha.toDouble()).pow(layers)

    @Test fun threeOverlappingPassiveWindowsRemainBelowTheActualSystemLimit() {
        val alpha=OverlayOpacityBudget.layerAlpha(0.8f)
        assertTrue(alpha>0f)
        assertTrue(alpha<=0.4f)
        assertTrue(combined(alpha,3)<0.8)
        assertEquals(0.78,combined(alpha,3),0.000001)
    }

    @Test fun removingAnyWindowCannotExceedTheReservedCombinedBudget() {
        val alpha=OverlayOpacityBudget.layerAlpha(0.8f)
        val worst=combined(alpha,3)
        for(count in 1..3) assertTrue(combined(alpha,count)<=worst)
    }

    @Test fun everySupportedSystemLimitAndLayerCountHasAConservativeBound() {
        for(layers in listOf(1,2,3,4,8)) for(step in 0..1000) {
            val maximum=step/1000f
            val alpha=OverlayOpacityBudget.layerAlpha(maximum,layers)
            assertTrue(alpha.isFinite() && alpha in 0f..0.4f)
            assertTrue("limit=$maximum layers=$layers",combined(alpha,layers)<=maximum.toDouble())
        }
    }

    @Test fun evenAnUnrestrictedLimitDoesNotMakeThePassiveUiOpaque() {
        assertEquals(0.4f,OverlayOpacityBudget.layerAlpha(1f),0f)
        assertTrue(combined(OverlayOpacityBudget.layerAlpha(1f),3)<1.0)
    }

    @Test fun unknownInvalidOrTooSmallLimitsFailClosedToTransparentWindows() {
        for(limit in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,-0.1f,1.01f,0f,0.01f,0.02f))
            assertEquals(0f,OverlayOpacityBudget.layerAlpha(limit),0f)
        for(layers in listOf(0,-1,Int.MIN_VALUE))
            assertEquals(0f,OverlayOpacityBudget.layerAlpha(0.8f,layers),0f)
    }
}
