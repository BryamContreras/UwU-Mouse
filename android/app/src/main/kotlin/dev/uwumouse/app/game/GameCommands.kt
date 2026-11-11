package dev.uwumouse.app.game

import dev.uwumouse.app.activation.LocalActivation

/** The UI and authorized diagnostics use the same application commands. */
object GameCommands {
    fun startProfile(id: String, displayId: Int) {
        check(!LocalActivation.busy) { "Espera a que termine la activación" }
        GameCoordinator.startProfile(id, cursor = true, displayId = displayId)
    }

    fun openEditor(id: String, displayId: Int) {
        check(!LocalActivation.busy) { "Espera a que termine la activación" }
        GameCoordinator.openEditor(id, displayId)
    }

    fun pause() = GameCoordinator.pauseByUser()
    fun openMenu() = GameCoordinator.openControlsMenu()
    fun stop() = GameCoordinator.stop()
    fun recoverPendingSession():Boolean {
        check(!LocalActivation.busy) { "Espera a que termine la activación" }
        return GameCoordinator.focusPendingClosure()
    }
}
