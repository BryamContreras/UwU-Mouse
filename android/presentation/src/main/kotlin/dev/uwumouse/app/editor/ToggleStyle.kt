package dev.uwumouse.app.editor

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Switch

/** Shared switch appearance for the editor and the floating menu. */
object ToggleStyle {
    private const val TRACK_WIDTH_DP=40
    private const val TRACK_HEIGHT_DP=20
    private const val THUMB_SIZE_DP=16
    private const val TOUCH_HEIGHT_DP=44
    fun checkColors()=ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf(android.R.attr.state_checked),intArrayOf()),
        intArrayOf(Color.rgb(78,95,120),Color.rgb(102,158,249),Color.rgb(165,181,205)))
    fun apply(toggle:Switch) {
        fun dp(value:Int)=(value*toggle.resources.displayMetrics.density+.5f).toInt()
        fun shape(color:Int,width:Int,height:Int)=GradientDrawable().apply {
            setColor(color)
            cornerRadius=dp(height)/2f
            setSize(dp(width),dp(height))
        }
        toggle.trackDrawable=StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled),shape(Color.rgb(76,89,110),TRACK_WIDTH_DP,TRACK_HEIGHT_DP))
            addState(intArrayOf(android.R.attr.state_checked),shape(Color.rgb(50,116,215),TRACK_WIDTH_DP,TRACK_HEIGHT_DP))
            addState(intArrayOf(),shape(Color.rgb(120,139,163),TRACK_WIDTH_DP,TRACK_HEIGHT_DP))
        }
        toggle.thumbDrawable=StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled),shape(Color.rgb(163,175,192),THUMB_SIZE_DP,THUMB_SIZE_DP))
            addState(intArrayOf(),shape(Color.WHITE,THUMB_SIZE_DP,THUMB_SIZE_DP))
        }
        toggle.showText=false
        toggle.splitTrack=false
        toggle.switchMinWidth=dp(TRACK_WIDTH_DP)
        toggle.switchPadding=dp(8)
        toggle.minHeight=dp(TOUCH_HEIGHT_DP)
    }
}
