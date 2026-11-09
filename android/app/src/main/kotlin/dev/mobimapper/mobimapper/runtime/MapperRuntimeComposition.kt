package dev.mobimapper.mobimapper.runtime

import android.graphics.Rect
import android.view.InputDevice
import dev.mobimapper.mobimapper.MainActivity
import dev.mobimapper.mobimapper.backend.BackendRegistry
import dev.mobimapper.mobimapper.backend.IInjector
import dev.mobimapper.mobimapper.engine.TouchFrame
import dev.mobimapper.mobimapper.game.GameCoordinator
import dev.mobimapper.mobimapper.session.SessionController
import dev.mobimapper.mobimapper.session.SessionEnvironment
import dev.mobimapper.mobimapper.session.SessionEnvironmentDiagnostics
import dev.mobimapper.mobimapper.session.SessionEvents
import dev.mobimapper.mobimapper.session.SessionRuntimePorts

/** Wiring only: binding these adapters neither opens devices nor starts a session. */
object MapperRuntimeComposition {
    private val ports = SessionRuntimePorts(AndroidSessionEnvironment(), AndroidSessionEvents())

    fun initialize() = SessionController.bindRuntime(ports)
}

private class AndroidSessionEnvironment : SessionEnvironment {
    private val capture: NativeSessionHost? get() = NativeSessionHost.instance
    override var injector: IInjector?
        get() = BackendRegistry.injector
        set(value) { BackendRegistry.injector = value }
    override val available: Boolean get() = capture != null
    override val currentApplicationPackage: String? get() = capture?.currentApplicationPackage
    override fun boundsForPackage(packageName: String): Rect? = capture?.boundsForPackage(packageName)
    override fun refreshContext() { capture?.refreshContext() }
    override fun captureMouse(enabled: Boolean, epoch: Long) { capture?.captureMouse(enabled, epoch) }
    override fun hideCursorForTransition() { capture?.hideCursorForTransition() }
    override fun inputDispatchIdle(): Boolean = capture?.inputDispatchIdle() == true
    override fun allowsInjection(frame: TouchFrame, virtualCameraAdmitted: Boolean): Boolean =
        capture?.allowsInjection(frame, virtualCameraAdmitted) == true
    override fun contextDiagnostics(): Map<String, Any?>? = capture?.contextWindowDiagnostics()

    override fun diagnostics(): SessionEnvironmentDiagnostics {
        val host = capture
        val devices = InputDevice.getDeviceIds().asSequence().mapNotNull { InputDevice.getDevice(it) }
            .filter { it.isExternal && !it.isVirtual }.toList()
        return SessionEnvironmentDiagnostics(host != null, "native-evdev", host?.nativeCaptureError ?: "", mapOf(
            "mouseCaptured" to host?.mouseCaptured,
            "cursorVisible" to host?.cursorVisible,
            "cursorRenderer" to host?.cursorRenderer,
            "cursorRendererError" to host?.cursorRendererError,
            "cursorCleanupPending" to host?.cursorCleanupPending,
            "trustedCursorAdmissionError" to host?.trustedCursorAdmissionError,
            "cursorX" to host?.cursorX,
            "cursorY" to host?.cursorY,
            "cursorSamples" to host?.cursorSamples,
            "contextBlockedBySystemWindow" to (host?.contextBlockedBySystemWindow ?: false),
            "contextBlockReason" to host?.contextBlockReason,
            "contextSystemWindowCount" to host?.contextSystemWindowCount,
            "contextCoveringSystemPackage" to host?.contextCoveringSystemPackage,
            "contextCheckedUptime" to host?.contextCheckedUptime,
            "windowContext" to host?.contextWindowDiagnostics(),
            "physicalMouseButtons" to host?.physicalMouseButtons,
            "devices" to devices.map { mapOf("name" to it.name, "descriptor" to it.descriptor,
                "vendorId" to it.vendorId, "productId" to it.productId, "sources" to it.sources) },
        ))
    }
}

private class AndroidSessionEvents : SessionEvents {
    override fun changed() = GameCoordinator.refreshControls()
    override fun requestEditor() = GameCoordinator.editCurrent()
    override fun diagnostics(): Map<String, Any?> = mapOf(
        "editorVisible" to GameCoordinator.editorVisible,
        "editorLastClose" to GameCoordinator.lastEditorClose,
        "bubbleVisible" to GameCoordinator.bubbleVisible,
        "bubbleMenuVisible" to GameCoordinator.bubbleMenuVisible,
        "controlsVisible" to GameCoordinator.controlsVisible,
        "bubbleAppearance" to GameCoordinator.bubbleAppearance,
        "targetPreparation" to GameCoordinator.lastTargetPreparation,
        "uiCreated" to MainActivity.createdCount,
        "uiDestroyed" to MainActivity.destroyedCount,
        "uiInstance" to MainActivity.lastInstance,
    )
}
