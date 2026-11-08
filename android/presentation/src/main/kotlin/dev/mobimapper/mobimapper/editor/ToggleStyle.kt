package dev.mobimapper.mobimapper.editor

import android.content.res.ColorStateList
import android.graphics.Color
import android.widget.Switch

/** Color, position and explicit Sí/No distinguish states in every overlay context. */
object ToggleStyle {
    fun checkColors()=ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf(android.R.attr.state_checked),intArrayOf()),
        intArrayOf(Color.rgb(78,95,120),Color.rgb(102,158,249),Color.rgb(165,181,205)))
    fun apply(toggle:Switch) {
        val states=arrayOf(intArrayOf(-android.R.attr.state_enabled),intArrayOf(android.R.attr.state_checked),intArrayOf())
        toggle.thumbTintList=ColorStateList(states,intArrayOf(Color.rgb(100,112,132),Color.WHITE,Color.rgb(184,196,213)))
        toggle.trackTintList=ColorStateList(states,intArrayOf(Color.rgb(46,57,76),Color.rgb(44,105,205),Color.rgb(66,79,101)))
        toggle.showText=true;toggle.textOn="Sí";toggle.textOff="No";toggle.splitTrack=false
        toggle.switchPadding=(8*toggle.resources.displayMetrics.density).toInt()
    }
}
