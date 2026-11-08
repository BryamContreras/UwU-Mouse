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

    @Test fun cursorReceivesSixtyPercentWhileTwoPassiveLayersKeepTheSameAdmittedBudget() {
        val baseline=OverlayOpacityBudget.layerAlpha(.8f)
        val priority=OverlayOpacityBudget.prioritizeCursor(baseline)
        assertEquals(.60f,priority.cursorAlpha,.000001f)
        assertEquals(.25838f,priority.passiveAlpha,.00002f)
        assertTrue(priority.cursorAlpha>baseline)
        assertTrue(priority.passiveAlpha<baseline)
        assertEquals(.78,combined(priority.cursorAlpha,priority.passiveAlpha,priority.passiveAlpha),.000001)
    }

    @Test fun cursorReallocationCannotIncreaseAnyAdmittedBaselineBudget() {
        for(step in 0..1000) {
            val limit=step/1000f
            val baseline=OverlayOpacityBudget.layerAlpha(limit)
            val priority=OverlayOpacityBudget.prioritizeCursor(baseline)
            assertTrue(priority.cursorAlpha.isFinite() && priority.passiveAlpha.isFinite())
            assertTrue(priority.cursorAlpha in 0f..0.60f)
            assertTrue(priority.passiveAlpha in 0f..baseline)
            val reserved=combined(baseline,3)
            val active=combined(priority.cursorAlpha,priority.passiveAlpha,priority.passiveAlpha)
            assertTrue("limit=$limit baseline=$baseline priority=$priority",active<=reserved+1e-12)
            assertTrue(active<=limit.toDouble())
            for(others in 0..2) {
                val subset=1.0-(1.0-priority.cursorAlpha.toDouble())*
                    (1.0-priority.passiveAlpha.toDouble()).pow(others)
                assertTrue(subset<=reserved+1e-12)
            }
        }
    }

    @Test fun loweringOthersBeforeShowingCursorAndHidingBeforeRestoringAreSafe() {
        val base=OverlayOpacityBudget.layerAlpha(.8f)
        val priority=OverlayOpacityBudget.prioritizeCursor(base)
        val reserve=combined(base,3)
        val stages=listOf(
            combined(0f,base,base),
            combined(0f,priority.passiveAlpha,base),
            combined(0f,priority.passiveAlpha,priority.passiveAlpha),
            combined(priority.cursorAlpha,priority.passiveAlpha,priority.passiveAlpha),
            combined(0f,priority.passiveAlpha,priority.passiveAlpha),
            combined(0f,base,priority.passiveAlpha),
            combined(0f,base,base),
        )
        assertTrue(stages.all { it<=reserve+1e-12 })
        assertTrue(combined(priority.cursorAlpha,base,base)>.8)
    }

    @Test fun invalidBaselineCannotGrantOpacityToCursorOrOtherWindows() {
        for(baseline in listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,-.1f,1.01f))
            assertEquals(OverlayOpacityBudget.CursorPriority(0f,0f),OverlayOpacityBudget.prioritizeCursor(baseline))
        assertEquals(OverlayOpacityBudget.CursorPriority(0f,0f),OverlayOpacityBudget.prioritizeCursor(0f))
        assertEquals(OverlayOpacityBudget.prioritizeCursor(.4f),OverlayOpacityBudget.prioritizeCursor(1f))
        assertEquals(OverlayOpacityBudget.CursorPriority(0f,0f),OverlayOpacityBudget.prioritizeCursor(Float.MIN_VALUE))
    }

    private fun combined(vararg alpha:Float):Double = 1.0-alpha.fold(1.0) { product,value -> product*(1.0-value.toDouble()) }
}
