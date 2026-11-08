package dev.mobimapper.mobimapper.editor

import android.content.Context

/** One cached document; writes only when a UI slider/drag ends, never from game input. */
class BubbleSettingsStore(context: Context) : BubbleSettingsPort {
    private val preferences = context.applicationContext.getSharedPreferences("floating_ui_v1", Context.MODE_PRIVATE)
    @Volatile override var value: BubbleSettings = runCatching {
        preferences.getString("settings", null)?.let(BubbleSettings::decode) ?: BubbleSettings()
    }.getOrDefault(BubbleSettings())
        private set
    override fun save(next: BubbleSettings): Boolean {
        if (next == value) return true
        if (!preferences.edit().putString("settings", next.encode()).commit()) return false
        value = next
        return true
    }
}
