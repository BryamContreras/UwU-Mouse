package dev.uwumouse.app.editor

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.view.Display
import kotlin.math.roundToInt

/** Scale controls on a separately routed display while retaining its real window and pixel space. */
internal object DesktopPresentation {
    private const val CONTROLS_SCALE = 1.25f
    const val BUBBLE_SCALE = 1.35f

    fun onIndependentDisplay(window: Context): Boolean {
        val displayId = runCatching { window.display?.displayId }.getOrNull()
        // A mirrored window still belongs to the display from which Android mirrors it.
        return displayId != null && displayId != Display.DEFAULT_DISPLAY
    }

    fun contextForControls(window: Context): Context {
        if (!onIndependentDisplay(window)) return window
        val baseDensityDpi = window.resources.displayMetrics.densityDpi
        val configuration = Configuration().apply {
            densityDpi = (baseDensityDpi * CONTROLS_SCALE).roundToInt()
        }
        val resourcesContext = window.createConfigurationContext(configuration)
        return object : ContextWrapper(window) {
            override fun getResources(): Resources = resourcesContext.resources
        }
    }
}
