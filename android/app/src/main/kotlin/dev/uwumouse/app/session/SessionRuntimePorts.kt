package dev.uwumouse.app.session

import android.graphics.Rect
import dev.uwumouse.app.backend.IInjector
import dev.uwumouse.app.engine.TouchFrame

/** Application capabilities needed by the session, independent of a concrete capture host. */
interface SessionEnvironment {
    var injector: IInjector?
    val available: Boolean
    val currentApplicationPackage: String?
    fun boundsForPackage(packageName: String): Rect?
    fun refreshContext()
    fun captureMouse(enabled: Boolean, epoch: Long)
    fun hideCursorForTransition()
    /** Cached capture state only; false while callbacks or a physical mouse button are pending. */
    fun inputDispatchIdle(): Boolean
    fun allowsInjection(frame: TouchFrame, virtualCameraAdmitted: Boolean): Boolean
    fun contextDiagnostics(): Map<String, Any?>?
    /** Cold control-plane diagnostics; never requested while consuming an input sample. */
    fun diagnostics(): SessionEnvironmentDiagnostics
}

data class SessionEnvironmentDiagnostics(
    val available: Boolean,
    val captureBackend: String,
    val captureError: String,
    val fields: Map<String, Any?>,
)

/** Session notifications; presentation decides which windows or controls need updating. */
interface SessionEvents {
    fun changed()
    fun requestEditor()
    fun diagnostics(): Map<String, Any?>
}

data class SessionRuntimePorts(val environment: SessionEnvironment, val events: SessionEvents) {
    companion object {
        /** Fail closed before the application composition root has run. */
        internal val UNBOUND = SessionRuntimePorts(object : SessionEnvironment {
            override var injector: IInjector? = null
            override val available = false
            override val currentApplicationPackage: String? = null
            override fun boundsForPackage(packageName: String): Rect? = null
            override fun refreshContext() = Unit
            override fun captureMouse(enabled: Boolean, epoch: Long) = Unit
            override fun hideCursorForTransition() = Unit
            override fun inputDispatchIdle() = false
            override fun allowsInjection(frame: TouchFrame, virtualCameraAdmitted: Boolean) = false
            override fun contextDiagnostics(): Map<String, Any?>? = null
            override fun diagnostics() = SessionEnvironmentDiagnostics(false, "unavailable", "", emptyMap())
        }, object : SessionEvents {
            override fun changed() = Unit
            override fun requestEditor() = Unit
            override fun diagnostics(): Map<String, Any?> = emptyMap()
        })
    }
}

/** One composition owns a process; a later component cannot replace a live session's ports. */
internal class SessionRuntimeBinding {
    @Volatile var ports: SessionRuntimePorts = SessionRuntimePorts.UNBOUND
        private set

    @Synchronized fun bind(next: SessionRuntimePorts) {
        require(next !== SessionRuntimePorts.UNBOUND) { "A concrete session runtime is required" }
        check(ports === SessionRuntimePorts.UNBOUND || ports === next) { "Session runtime already composed" }
        ports = next
    }
}
