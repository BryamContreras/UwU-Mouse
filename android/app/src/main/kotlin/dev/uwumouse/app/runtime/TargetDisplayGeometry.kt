package dev.uwumouse.app.runtime

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.WindowManager

/** Display.getRealSize can inherit another display's app max-bounds in desktop mode. */
internal object TargetDisplayGeometry {
    data class Geometry(val width:Int,val height:Int,val rotation:Int,val densityDpi:Int)
    private var scoped:Context?=null
    private var scopedId=-1

    @Synchronized fun clear() { scoped=null;scopedId=-1 }

    @Synchronized fun forget(displayId:Int) {
        if(scopedId==displayId) clear()
    }

    /** One reusable window context; no windows or periodic work are created. */
    @Synchronized fun read(context:Context,displayId:Int):Geometry {
        require(displayId>=0) { "Pantalla de destino no válida" }
        val display=context.getSystemService(DisplayManager::class.java).getDisplay(displayId)
            ?: run { forget(displayId);throw IllegalArgumentException("Pantalla desconectada") }
        check(display.isValid) { "Pantalla desconectada" }
        val target=scoped?.takeIf { scopedId==displayId && it.display?.isValid==true }
            ?: context.applicationContext.createWindowContext(display,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null).also {
                scoped=it;scopedId=displayId
            }
        val bounds=target.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        check(display.isValid && bounds.width() in 100..8192 && bounds.height() in 100..8192) {
            "Dimensiones de pantalla no disponibles"
        }
        return Geometry(bounds.width(),bounds.height(),target.display?.rotation ?: display.rotation,
            target.resources.displayMetrics.densityDpi)
    }
}
