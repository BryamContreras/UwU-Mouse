package dev.uwumouse.app.runtime

import android.os.Bundle
import dev.uwumouse.app.editor.DisplayResolutionSettingsPort
import dev.uwumouse.app.editor.DisplayResolutionSettingsState

/** Binds a System form to its original display. No overlay or view is kept by the worker. */
class OverlayDisplayResolutionSettings(
    private val displayId: Int,
    private val rotation: () -> Int,
    private val validate: () -> Unit,
) : DisplayResolutionSettingsPort {
    private var gameRotation = 0

    override fun read(callback: (Result<DisplayResolutionSettingsState>) -> Unit) {
        val prepared = runCatching {
            validate()
            // Read before an EditText makes the own overlay focusable or opens the IME.
            rotation().also { require(it in 0..3) }
        }
        prepared.fold(onSuccess = { captured ->
            gameRotation = captured
            DisplayResolutionController.read(displayId) { result -> callback(result.map { state(it, captured) }) }
        }, onFailure = { callback(Result.failure(it)) })
    }

    override fun apply(displayUniqueId: String, naturalWidth: Int, naturalHeight: Int,
        callback: (Result<DisplayResolutionSettingsState>) -> Unit) {
        submit(callback) { complete ->
            DisplayResolutionController.apply(displayId, displayUniqueId, naturalWidth, naturalHeight, complete)
        }
    }

    override fun restore(displayUniqueId: String, callback: (Result<DisplayResolutionSettingsState>) -> Unit) {
        submit(callback) { complete -> DisplayResolutionController.restore(displayId, displayUniqueId, complete) }
    }

    private fun submit(callback: (Result<DisplayResolutionSettingsState>) -> Unit,
        operation: ((Result<Bundle>) -> Unit) -> Unit) {
        try {
            validate()
            val captured = gameRotation
            operation { result -> callback(result.map { state(it, captured) }) }
        } catch (failure: Exception) { callback(Result.failure(failure)) }
    }

    private fun state(value: Bundle, capturedRotation: Int) = DisplayResolutionSettingsState(
        available = value.getBoolean("available", false),
        displayUniqueId = value.getString("displayUniqueId").orEmpty(),
        gameRotation = capturedRotation,
        naturalWidth = value.getInt("width", 0),
        naturalHeight = value.getInt("height", 0),
        managed = value.getBoolean("managed", false),
        canRestore = value.getBoolean("canRestore", false),
        operationSucceeded = value.getBoolean("operationSucceeded", false),
        changed = value.getBoolean("changed", false),
        message = value.getString("message").orEmpty(),
        displayName = value.getString("displayName").orEmpty(),
    )
}
