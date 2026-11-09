package dev.uwumouse.app.engine

/** Fixed touch-down duration. The profile wait begins only after this tap is released. */
const val MACRO_TAP_MS = 60

/** Immutable display-pixel instructions prepared before capture starts. */
data class PreparedMacro(
    val id: String,
    val owner: String,
    val triggerKind: String,
    val code: Int,
    val steps: List<PreparedMacroStep>,
)

data class PreparedMacroStep(val x: Float, val y: Float, val waitMs: Int)
