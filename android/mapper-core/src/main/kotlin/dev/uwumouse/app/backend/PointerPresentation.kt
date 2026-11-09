package dev.uwumouse.app.backend

/** Presentation only: no settings, input routing or contact state. Called on session transitions. */
class PointerPresentation(private val setType: (Int) -> Unit) {
    var hidden = false
        private set
    var error = ""
        private set
    private var needsRestore = false
    private var sessionActive = false
    private var mouseCaptureActive = false

    /** Injection and native UI mouse capture can end independently. */
    fun setSessionActive(active:Boolean,forceRestore:Boolean=false) {
        sessionActive=active
        reconcile(forceRestore)
    }

    fun setMouseCaptureActive(active:Boolean,forceRestore:Boolean=false) {
        mouseCaptureActive=active
        reconcile(forceRestore)
    }

    /** The caller has stopped native capture and is closing the complete controller. */
    fun releaseOwners() {
        sessionActive=false;mouseCaptureActive=false
        restore(force=true)
    }

    private fun reconcile(forceRestore:Boolean) {
        if(sessionActive || mouseCaptureActive) hide() else restore(forceRestore)
    }

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
