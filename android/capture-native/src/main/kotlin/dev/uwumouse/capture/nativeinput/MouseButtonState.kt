package dev.uwumouse.capture.nativeinput

/** One logical mouse stream: another HID interface must not release a held button. */
internal class MouseButtonState {
    private val nodes = IntArray(16)
    var current = 0
        private set

    fun reset() { nodes.fill(0); current = 0 }

    fun update(slot: Int, buttons: Int): Int {
        require(slot in nodes.indices && buttons and 31.inv() == 0)
        nodes[slot] = buttons
        var combined = 0
        for (mask in nodes) combined = combined or mask
        current = combined
        return combined
    }
}
