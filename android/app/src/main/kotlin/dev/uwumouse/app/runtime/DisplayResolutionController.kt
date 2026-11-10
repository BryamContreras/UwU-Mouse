package dev.uwumouse.app.runtime

import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.uwumouse.app.backend.BackendRegistry
import dev.uwumouse.app.backend.IInjector
import dev.uwumouse.app.game.GameCoordinator
import dev.uwumouse.app.session.SessionController
import dev.uwumouse.app.session.SessionMode
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Cold settings operations. No resolution work is scheduled while playing or drawing. */
object DisplayResolutionController {
    private val main=Handler(Looper.getMainLooper())
    private val owner=Binder() // App lifetime; leaving the settings Activity keeps the chosen size.
    private val io=ThreadPoolExecutor(0,1,10,TimeUnit.SECONDS,LinkedBlockingQueue<Runnable>(1),
        { task -> Thread(task,"UwUDisplaySettings").apply { isDaemon=true } })
    private data class Read(val displayId:Int,val callback:(Result<Bundle>)->Unit)
    private var pendingRead:Read?=null
    private var ioInFlight=false
    @Volatile var changing=false
        private set

    fun read(displayId:Int,callback:(Result<Bundle>)->Unit) = onMain {
        // Rotation/recreation replaces the observer instead of growing an executor queue.
        pendingRead=Read(displayId,callback)
        drainRead()
    }

    fun apply(displayId:Int,displayUniqueId:String,width:Int,height:Int,callback:(Result<Bundle>)->Unit) =
        change(displayId,callback) { backend ->
            require(width in 500..8192 && height in 500..8192) { "Usa dimensiones entre 500 y 8192 píxeles" }
            backend.applyDisplayResolution(displayId,displayUniqueId,width,height,owner)
        }

    fun restore(displayId:Int,displayUniqueId:String,callback:(Result<Bundle>)->Unit) =
        change(displayId,callback) { it.restoreDisplayResolution(displayId,displayUniqueId) }

    private fun change(displayId:Int,callback:(Result<Bundle>)->Unit,operation:(IInjector)->Bundle) = onMain {
        if(changing) {
            callback(Result.failure(IllegalStateException("Espera a que termine el cambio anterior")))
            return@onMain
        }
        if(displayId<0) {
            callback(Result.failure(IllegalArgumentException("Pantalla de destino no válida")))
            return@onMain
        }
        changing=true
        // Keep the bubble/library available for Restore, but end the old input stream.
        try {
            GameCoordinator.beginDisplayResolutionChange()
            SessionController.stopSession()
        } catch(failure:Exception) {
            finishChange(displayId,callback,Result.failure(failure))
            return@onMain
        }
        val deadline=SystemClock.uptimeMillis()+5000L
        val await=object:Runnable {
            override fun run() {
                val safe=!SessionController.active && !SessionController.isTransitioning() &&
                    SessionController.contacts==0 && !SessionController.releaseUnverified &&
                    SessionController.mode==SessionMode.STOPPED
                if(!safe || ioInFlight) {
                    if(SystemClock.uptimeMillis()<deadline) main.postDelayed(this,40)
                    else finishChange(displayId,callback,Result.failure(IllegalStateException(
                        "No se pudo cerrar la sesión anterior; comprueba que no queden controles pulsados")))
                    return
                }
                try {
                    NativeSessionHost.instance?.quiesceForDisplayResolution()
                    execute({ operation(requireBackend()) }) { finishChange(displayId,callback,it) }
                } catch(failure:Exception) { finishChange(displayId,callback,Result.failure(failure)) }
            }
        }
        main.post(await)
    }

    private fun finishChange(displayId:Int,callback:(Result<Bundle>)->Unit,result:Result<Bundle>) {
        changing=false
        val refreshed=runCatching {
            TargetDisplayGeometry.forget(displayId)
            GameCoordinator.geometryChanged()
            NativeSessionHost.instance?.refreshContext()
        }
        val outcome=refreshed.exceptionOrNull()?.let { Result.failure<Bundle>(it) } ?: result
        try { callback(outcome) } finally { drainRead() }
    }

    private fun drainRead() {
        if(changing || ioInFlight) return
        val request=pendingRead ?: return
        pendingRead=null
        execute({ requireBackend().getDisplayResolution(request.displayId) }) { outcome ->
            // A newer Activity/refresh owns the visible result. Its next request is retained.
            if(pendingRead==null) request.callback(outcome)
            drainRead()
        }
    }

    private fun requireBackend():IInjector {
        val backend=BackendRegistry.injector ?: throw IllegalStateException("Activa el motor ADB en Conexión")
        // A present false capability still has a status method explaining the platform error.
        check(backend.status().containsKey("displayResolutionSupported")) {
            "Reactiva el motor actualizado para usar la resolución personalizada"
        }
        return backend
    }

    private fun execute(operation:()->Bundle,callback:(Result<Bundle>)->Unit) {
        check(!ioInFlight)
        ioInFlight=true
        try {
            io.execute {
                val result=runCatching(operation)
                main.post { ioInFlight=false;callback(result) }
            }
        } catch(failure:Exception) {
            ioInFlight=false
            callback(Result.failure(failure))
        }
    }

    private inline fun onMain(crossinline operation:()->Unit) {
        if(Looper.myLooper()==Looper.getMainLooper()) operation() else main.post { operation() }
    }
}
