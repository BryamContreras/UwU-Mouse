package dev.uwumouse.app.editor

/** Detached control-plane state. Dimensions stay in the display's natural orientation. */
data class DisplayResolutionSettingsState(
    val available: Boolean = false,
    val displayUniqueId: String = "",
    val gameRotation: Int = 0,
    val naturalWidth: Int = 0,
    val naturalHeight: Int = 0,
    val managed: Boolean = false,
    val canRestore: Boolean = false,
    val operationSucceeded: Boolean = true,
    val changed: Boolean = false,
    val message: String = "",
    val displayName: String = "",
)

/** Bound to one explicit display by the app; callbacks arrive on the main thread. */
interface DisplayResolutionSettingsPort {
    fun read(callback: (Result<DisplayResolutionSettingsState>) -> Unit)
    fun apply(displayUniqueId: String, naturalWidth: Int, naturalHeight: Int,
        callback: (Result<DisplayResolutionSettingsState>) -> Unit)
    fun restore(displayUniqueId: String, callback: (Result<DisplayResolutionSettingsState>) -> Unit)
}
