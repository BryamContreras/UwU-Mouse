package dev.uwumouse.app.editor

import android.graphics.Rect
import android.graphics.PointF

/** Detached window geometry supplied by the application host, never accessibility nodes. */
data class EditorWindowBounds(
    val packageName: String,
    val displayId: Int,
    val frame: Rect,
    val content: Rect,
    val focused: Boolean,
    val layer: Int,
)

/** Admission and window discovery are host capabilities, not responsibilities of editor UI. */
interface EditorPresentationHost {
    fun begin(packageName: String, displayId: Int): Boolean
    fun end(reason: String)
    fun windows(displayId: Int): List<EditorWindowBounds>
    fun cursorPosition():PointF?=null
    fun cursorSizeDp():Float=20f
    fun cursorStyle():String="classic"
}
