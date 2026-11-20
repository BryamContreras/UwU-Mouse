package dev.uwumouse.app.editor

import kotlin.math.roundToInt

/** Editor steps are hundredths of the gain already stored by camera profiles. */
internal object CameraSensitivityScale {
    const val MINIMUM = 1
    private const val NORMAL_MAXIMUM = 300

    fun position(gain: Float): Int = (gain * 100f).roundToInt()
    fun gain(position: Int): Float = position / 100f

    /** An older profile remains editable at its saved gain, with room to turn it down. */
    fun maximum(savedGain: Float): Int = maxOf(NORMAL_MAXIMUM, position(savedGain))
}
