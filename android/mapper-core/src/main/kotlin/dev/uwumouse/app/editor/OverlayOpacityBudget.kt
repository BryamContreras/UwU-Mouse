package dev.uwumouse.app.editor

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
     * Reallocate the admitted budget toward one cursor and the remaining passive windows.
     * The caller lowers the passive windows before showing the stronger cursor;
     * hiding the cursor precedes restoring the original per-layer alpha. Touchable menus
     * do not use this allocation. No system query or allocation occurs on pointer movement.
     */
    fun prioritizeCursor(baselineLayerAlpha:Float,layers:Int=3):CursorPriority {
        if(!baselineLayerAlpha.isFinite() || baselineLayerAlpha !in 0f..1f || layers<2)
            return CursorPriority(0f,0f)
        val baseline=baselineLayerAlpha.coerceAtMost(MAX_LAYER_ALPHA).toDouble()
        val admittedCombined=1.0-(1.0-baseline).pow(layers)
        val cursor=floorFloat(minOf(PREFERRED_CURSOR_ALPHA.toDouble(),admittedCombined))
        val passive=if(cursor==0f) 0f else floorFloat(minOf(baseline,
            1.0-((1.0-admittedCombined)/(1.0-cursor.toDouble())).pow(1.0/(layers-1))))
        return CursorPriority(cursor,passive)
    }

    /** Repartition an admitted opacity budget without exceeding its combined limit. */
    fun resizeLayerBudget(baselineLayerAlpha:Float,fromLayers:Int,toLayers:Int):Float {
        if(!baselineLayerAlpha.isFinite() || baselineLayerAlpha !in 0f..1f || fromLayers<=0 || toLayers<=0) return 0f
        val baseline=baselineLayerAlpha.coerceAtMost(MAX_LAYER_ALPHA).toDouble()
        return floorFloat((1.0-(1.0-baseline).pow(fromLayers.toDouble()/toLayers)).coerceAtMost(MAX_LAYER_ALPHA.toDouble()))
    }

    /** Do not round a layer upward beyond the Double budget used to derive it. */
    private fun floorFloat(value:Double):Float {
        val rounded=value.coerceIn(0.0,1.0).toFloat()
        return if(rounded.toDouble()>value) java.lang.Math.nextDown(rounded) else rounded
    }
}
