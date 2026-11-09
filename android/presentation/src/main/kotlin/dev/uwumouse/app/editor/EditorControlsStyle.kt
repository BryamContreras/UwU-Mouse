package dev.uwumouse.app.editor

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import dev.uwumouse.presentation.R

/** Common dimensions and appearance for editor and floating-menu controls. */
object EditorControlsStyle {
    const val CLOSE_TARGET_DP = 40
    const val CLOSE_ICON_DP = 28
    const val ACTION_HEIGHT_DP = 38
    const val ACTION_GAP_DP = 8

    fun closeButton(context: Context, description: String, action: () -> Unit): View {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density + .5f).toInt()
        return FrameLayout(context).apply {
            isClickable = true
            isFocusable = true
            contentDescription = description
            setOnClickListener { action() }
            addView(ImageView(context).apply {
                setImageResource(R.drawable.mm_close)
                scaleType = ImageView.ScaleType.FIT_CENTER
                imageTintList = null
                setPadding(dp(6), dp(6), dp(6), dp(6))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(53, 102, 171), Color.rgb(26, 59, 116))).apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(1), Color.rgb(99, 156, 230))
                }
            }, FrameLayout.LayoutParams(dp(CLOSE_ICON_DP), dp(CLOSE_ICON_DP), Gravity.CENTER))
        }
    }
}
