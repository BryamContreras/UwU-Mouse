package dev.uwumouse.app.editor

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.widget.SeekBar

/** A slightly fuller track without changing the slider's touch target or value handling. */
object EditorSeekBarStyle {
    fun apply(slider: SeekBar) {
        val density = slider.resources.displayMetrics.density
        val thickness = (6 * density + .5f).toInt()
        fun track(color: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = thickness / 2f
            setSize(-1, thickness)
            setColor(color)
        }
        slider.progressDrawable = LayerDrawable(arrayOf(
            track(Color.rgb(45, 67, 103)),
            // ProgressBar refreshes secondaryProgress after progress. Its own layer prevents
            // the default zero secondary value from resetting the level of the whole track.
            ClipDrawable(track(Color.TRANSPARENT), Gravity.LEFT, ClipDrawable.HORIZONTAL),
            ClipDrawable(track(Color.rgb(102, 171, 255)), Gravity.LEFT, ClipDrawable.HORIZONTAL),
        )).apply {
            setId(0, android.R.id.background)
            setId(1, android.R.id.secondaryProgress)
            setId(2, android.R.id.progress)
            for (index in 0..2) {
                setLayerHeight(index, thickness)
                setLayerGravity(index, Gravity.FILL_HORIZONTAL or Gravity.CENTER_VERTICAL)
            }
        }
        slider.progressTintList = null
        slider.progressBackgroundTintList = null
        slider.thumbTintList = ColorStateList.valueOf(Color.rgb(177, 217, 255))
        slider.splitTrack = false
    }
}
