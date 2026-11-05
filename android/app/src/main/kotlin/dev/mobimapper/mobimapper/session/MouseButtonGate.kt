package dev.mobimapper.mobimapper.session

/**
 * Main-thread button edges for one capture epoch. Android can first report an already held button
 * when sources are enabled, so that baseline is never interpreted as a new physical press.
 * The mask uses primitives only; no allocation or profile lookup occurs per mouse sample.
 */
class MouseButtonGate {
    private var initialized = false
    private var previous = 0
    private var suppressed = 0

    fun reset() {
        initialized = false
        previous = 0
        suppressed = 0
    }
    /** A mode switch within the same capture epoch has an observed physical baseline. */
    fun rebase(buttons:Int) { initialized=true;previous=buttons;suppressed=buttons }

    /** Changed buttons eligible for mapping; use the sampled state to distinguish down from up. */
    fun transitions(buttons: Int): Int {
        if (!initialized) {
            initialized = true
            previous = buttons
            suppressed = buttons
            return 0
        }
        val changed = (buttons xor previous) and suppressed.inv()
        suppressed = suppressed and buttons
        previous = buttons
        return changed
    }
}
