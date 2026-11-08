package dev.mobimapper.mobimapper.editor

import kotlin.math.pow

/** Cold presentation policy for overlapping, non-touchable application overlays. */
object OverlayOpacityBudget {
    private const val MAX_LAYER_ALPHA=0.4f
    private const val COMBINED_MARGIN=0.02
    private const val PREFERRED_CURSOR_ALPHA=.60f

    data class CursorPriority(val cursorAlpha:Float,val passiveAlpha:Float)

    /**
     * Android combines opacity as 1-product(1-alpha). Reserve the worst case of all
     * three passive windows: cursor, mapped controls and a non-touchable bubble.
     * Unknown limits admit only fully transparent layers.
     * https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE
     */
    fun layerAlpha(maximumObscuringOpacity:Float,layers:Int=3):Float {
        if(!maximumObscuringOpacity.isFinite() || maximumObscuringOpacity !in 0f..1f || layers<=0) return 0f
        val combined=(maximumObscuringOpacity.toDouble()-COMBINED_MARGIN).coerceAtLeast(0.0)
        val alpha=1.0-(1.0-combined).pow(1.0/layers)
        return alpha.toFloat().coerceIn(0f,MAX_LAYER_ALPHA)
    }

    /**
     * Reallocate the already admitted three-layer budget toward one cursor and two passive
     * windows. The caller lowers both passive windows before showing the stronger cursor;
     * hiding the cursor precedes restoring the original per-layer alpha. Touchable menus
     * do not use this allocation. No system query or allocation occurs on pointer movement.
     */
    fun prioritizeCursor(baselineLayerAlpha:Float):CursorPriority {
        if(!baselineLayerAlpha.isFinite() || baselineLayerAlpha !in 0f..1f)
            return CursorPriority(0f,0f)
        val baseline=baselineLayerAlpha.coerceAtMost(MAX_LAYER_ALPHA).toDouble()
        val admittedCombined=1.0-(1.0-baseline).pow(3)
        val cursor=floorFloat(minOf(PREFERRED_CURSOR_ALPHA.toDouble(),admittedCombined))
        val passive=if(cursor==0f) 0f else floorFloat(minOf(baseline,
            1.0-((1.0-admittedCombined)/(1.0-cursor.toDouble())).pow(.5)))
        return CursorPriority(cursor,passive)
    }

    /** Do not round a layer upward beyond the Double budget used to derive it. */
    private fun floorFloat(value:Double):Float {
        val rounded=value.coerceIn(0.0,1.0).toFloat()
        return if(rounded.toDouble()>value) java.lang.Math.nextDown(rounded) else rounded
    }
}
