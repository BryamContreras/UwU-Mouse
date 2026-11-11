package dev.uwumouse.app.runtime

/** Optional paused-session shortcuts must not seize the system mouse or latch a session error. */
internal class OptionalKeyboardCapturePolicy {
    var unavailable=false; private set
    fun wantsCapture(wanted:Boolean,mouseRequested:Boolean)=wanted && (mouseRequested || !unavailable)
    fun acceptCapabilityFailure(mouseRequested:Boolean,reason:String?):Boolean {
        if(mouseRequested || reason !in setOf("no_keyboard_only_input_node_available",
                "mixed_role_input_node_cannot_be_partially_grabbed")) return false
        unavailable=true
        return true
    }
    fun inventoryChanged() { unavailable=false }
}
