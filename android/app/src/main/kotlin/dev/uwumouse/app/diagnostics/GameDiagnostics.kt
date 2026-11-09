package dev.uwumouse.app.diagnostics

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import dev.uwumouse.app.game.GameCommands
import dev.uwumouse.app.game.GameCoordinator
import dev.uwumouse.app.runtime.NativeSessionHost
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Called only after BackendProvider authenticates the ADB shell/root caller. */
internal object GameDiagnostics {
    val methods = setOf("gamePrepare", "gameStart", "gameEdit", "gamePause", "gameMenu", "gameStop")

    fun call(context: Context, method: String, profileId: String?, extras: Bundle?): Bundle {
        require(method in methods)
        val displayId = extras?.getInt("displayId", 0) ?: 0
        require(displayId >= 0)
        val task = FutureTask<Unit> {
            NativeSessionHost.initialize(context)
            GameCoordinator.initialize(context)
            when (method) {
                "gamePrepare" -> NativeSessionHost.instance?.observeContextFor(10_000)
                "gameStart" -> GameCommands.startProfile(requireId(profileId), displayId)
                "gameEdit" -> GameCommands.openEditor(requireId(profileId), displayId)
                "gamePause" -> GameCommands.pause()
                "gameMenu" -> GameCommands.openMenu()
                "gameStop" -> GameCommands.stop()
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run()
        else check(Handler(Looper.getMainLooper()).post(task)) { "Main thread unavailable" }
        try { task.get(5, TimeUnit.SECONDS) } catch (failure: Exception) {
            task.cancel(false)
            throw failure
        }
        return Bundle().apply { putBoolean("accepted", true) }
    }

    private fun requireId(id: String?): String = requireNotNull(id).also {
        require(it.isNotBlank() && it.length <= 128) { "Perfil de prueba no válido" }
    }
}
