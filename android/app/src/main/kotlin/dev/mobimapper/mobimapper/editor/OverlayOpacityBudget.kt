package dev.mobimapper.mobimapper.editor

import kotlin.math.pow

/** Cold presentation policy for overlapping, non-touchable application overlays. */
object OverlayOpacityBudget {
    private const val MAX_LAYER_ALPHA=0.4f
    private const val COMBINED_MARGIN=0.02

    /**
     * Android combines opacity as 1-product(1-alpha). Reserve the worst case of all
     * three passive windows: cursor, mapped controls and a non-touchable bubble.
     * Trusted accessibility overlays on display0 retain their existing alpha1 and
     * do not call this policy. Unknown limits admit only fully transparent layers.
     * https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_NOT_TOUCHABLE
     */
    fun layerAlpha(maximumObscuringOpacity:Float,layers:Int=3):Float {
        if(!maximumObscuringOpacity.isFinite() || maximumObscuringOpacity !in 0f..1f || layers<=0) return 0f
        val combined=(maximumObscuringOpacity.toDouble()-COMBINED_MARGIN).coerceAtLeast(0.0)
        val alpha=1.0-(1.0-combined).pow(1.0/layers)
        return alpha.toFloat().coerceIn(0f,MAX_LAYER_ALPHA)
    }
}
