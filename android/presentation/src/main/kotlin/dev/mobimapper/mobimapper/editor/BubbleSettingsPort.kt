package dev.mobimapper.mobimapper.editor

/** Persistence is supplied by composition; the presentation library never opens app storage. */
interface BubbleSettingsPort {
    val value: BubbleSettings
    fun save(next: BubbleSettings): Boolean
}
