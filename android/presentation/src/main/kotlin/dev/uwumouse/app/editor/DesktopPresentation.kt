package dev.uwumouse.app.editor

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.view.Display
import kotlin.math.roundToInt

/** Scale controls on a separately routed display while retaining its real window and pixel space. */
internal object DesktopPresentation {
    private const val SCALE = 1.15f

    fun contextForControls(window: Context): Context {
        val display = runCatching { window.display }.getOrNull()
        // A mirrored window still belongs to the display from which Android mirrors it.
        if (display == null || display.displayId == Display.DEFAULT_DISPLAY) return window
        val baseDensityDpi = window.resources.displayMetrics.densityDpi
        val configuration = Configuration().apply {
            densityDpi = (baseDensityDpi * SCALE).roundToInt()
        }
        val resourcesContext = window.createConfigurationContext(configuration)
        return object : ContextWrapper(window) {
            override fun getResources(): Resources = resourcesContext.resources
        }
    }
}
