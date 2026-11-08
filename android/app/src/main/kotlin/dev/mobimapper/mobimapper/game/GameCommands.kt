package dev.mobimapper.mobimapper.game

import dev.mobimapper.mobimapper.activation.LocalActivation
import dev.mobimapper.mobimapper.session.SessionController

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

    fun pause() = SessionController.pause("Pausada por usuario")
    fun openMenu() = GameCoordinator.openControlsMenu()
    fun stop() = GameCoordinator.stop()
}
