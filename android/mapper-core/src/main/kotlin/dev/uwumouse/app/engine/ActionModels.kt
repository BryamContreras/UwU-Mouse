package dev.uwumouse.app.engine

/** Coordinates and triggers are compiled before capture; interval is between tap starts. */
data class PreparedRepeat(
    val owner: String,
    val x: Float,
    val y: Float,
    val triggerKind: String,
    val code: Int,
    val intervalMs: Int,
    val toggle: Boolean = false,
    val durationSeconds: Int = 0,
)

data class PreparedCursorAction(
    val owner: String,
    val x: Float,
    val y: Float,
    val triggerKind: String,
    val code: Int,
)
