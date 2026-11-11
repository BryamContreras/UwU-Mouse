package dev.uwumouse.capture.nativeinput

import dev.uwumouse.capture.api.CaptureDevice
import dev.uwumouse.capture.api.CaptureRequest

/** Select physical roles, including auxiliary mouse interfaces exposed by USB keyboards. */
internal object CaptureDeviceSelection {
    private val eventPath = Regex("/dev/input/event[0-9]{1,4}")

    fun select(inventory: List<CaptureDevice>, request: CaptureRequest): List<CaptureDevice> {
        val available = inventory.filter { (it.bus == 3 || it.bus == 5) && eventPath.matches(it.path) }
        check(available.distinctBy { it.path }.size == available.size) { "Duplicate external input node" }
        // EVIOCGRAB owns an entire node. Automatic role-only capture must leave a
        // combined node with Android instead of rejecting safe independent nodes.
        // Explicit selections are still validated below and never silently skipped.
        val automatic = available.filter {
            (!it.mouse || request.captureMouse) && (!it.keyboard || request.captureKeyboard)
        }
        val mice = if (!request.captureMouse) emptyList() else if (request.mousePath != null) {
            listOf(requireNotNull(available.singleOrNull { it.path == request.mousePath && it.mouse }) {
                "Selected mouse is not an external relative mouse"
            })
        } else {
            automatic.filter { it.mouse }.also {
                check(it.isNotEmpty()) { "No external relative mouse is available" }
            }
        }
        val keyboards = if (!request.captureKeyboard) emptyList() else if (request.keyboardPaths.isEmpty()) {
            automatic.filter { it.keyboard }
        } else {
            request.keyboardPaths.map { path ->
                requireNotNull(available.singleOrNull { it.path == path && it.keyboard }) {
                    "Selected keyboard is unavailable or internal"
                }
            }
        }
        val selected = (mice + keyboards).distinctBy { it.path }
        check(selected.isNotEmpty()) {
            if (request.captureKeyboard && !request.captureMouse) "no_keyboard_only_input_node_available"
            else "No external input devices match this request"
        }
        require(selected.size <= 16) { "At most sixteen native input nodes are supported" }
        check(selected.none { (it.mouse && !request.captureMouse) || (it.keyboard && !request.captureKeyboard) }) {
            "mixed_role_input_node_cannot_be_partially_grabbed"
        }
        check(!request.captureMouse || request.mousePath == null || selected.none { it.mouse && it.path != request.mousePath }) {
            "explicit_mouse_selection_conflicts_with_mixed_role_keyboard"
        }
        return selected
    }
}
