package dev.mobimapper.mobimapper.backend

/** Presentation only: no settings, input routing or contact state. Called on session transitions. */
class PointerPresentation(private val setType: (Int) -> Unit) {
    var hidden = false
        private set
    var error = ""
        private set
    private var needsRestore = false

    fun hide() {
        needsRestore = true // A thrown Binder reply can still follow a successful change.
        try { setType(0); hidden = true; error = "" }
        catch (e: Exception) { hidden = false; error = e.javaClass.simpleName }
    }

    fun restore(force:Boolean=false) {
        if (!needsRestore && !force) return
        try { setType(1000); hidden = false; needsRestore = false; error = "" }
        catch (e: Exception) { error = e.javaClass.simpleName }
    }
}
