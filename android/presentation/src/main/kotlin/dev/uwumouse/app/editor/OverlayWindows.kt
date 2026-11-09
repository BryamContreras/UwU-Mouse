package dev.uwumouse.app.editor

import android.content.Context
import android.content.ContextWrapper
import android.hardware.input.InputManager
import android.provider.Settings
import android.view.WindowManager

/** Standard overlays on every display need the user's explicit Android grant. */
class ExternalOverlayPermissionRequiredException(val displayId:Int):SecurityException(
    "Permite mostrar UwU Mouse sobre otras aplicaciones para usar esta pantalla",
)

/** Window admission is fixed; cold visibility transitions may rebalance passive opacity. */
object OverlayWindows {
    class ExternalContext internal constructor(base:Context,private val baselineAlpha:Float):ContextWrapper(base) {
        private val monitorAlpha=OverlayOpacityBudget.resizeLayerBudget(baselineAlpha,3,4)
        private val priority=OverlayOpacityBudget.prioritizeCursor(baselineAlpha,3)
        private val monitorPriority=OverlayOpacityBudget.prioritizeCursor(monitorAlpha,4).let {
            OverlayOpacityBudget.CursorPriority(minOf(it.cursorAlpha,priority.cursorAlpha),minOf(it.passiveAlpha,priority.passiveAlpha))
        }
        @Volatile internal var cursorActive=false
        @Volatile internal var monitorActive=false
        val nonTouchAlpha:Float get()=if(cursorActive) {
            if(monitorActive) monitorPriority.passiveAlpha else priority.passiveAlpha
        } else if(monitorActive) monitorAlpha else baselineAlpha
        val cursorWindowAlpha:Float get()=if(cursorActive) {
            if(monitorActive) monitorPriority.cursorAlpha else priority.cursorAlpha
        } else if(monitorActive) monitorAlpha else baselineAlpha
    }

    fun requirePermission(context:Context,displayId:Int) {
        require(displayId>=0) { "Pantalla de superposición no válida" }
        if(!Settings.canDrawOverlays(context))
            throw ExternalOverlayPermissionRequiredException(displayId)
    }

    fun externalContext(windowContext:Context,nonTouchAlpha:Float):Context {
        require(nonTouchAlpha.isFinite() && nonTouchAlpha in 0f..1f)
        return ExternalContext(windowContext,nonTouchAlpha)
    }

    /** Update the allocation only; the owner must refresh passive windows before raising cursor. */
    fun setCursorActive(context:Context,active:Boolean):Boolean {
        val external=context as? ExternalContext ?: return false
        if(external.cursorActive==active) return false
        external.cursorActive=active
        return true
    }

    /** Reserve a fourth passive window; owner applies opacity changes before adding it. */
    fun setMonitorActive(context:Context,active:Boolean):Boolean {
        val external=context as? ExternalContext ?: return false
        if(external.monitorActive==active) return false
        external.monitorActive=active
        return true
    }

    fun cursorWindowAlpha(context:Context):Float =
        (context as? ExternalContext)?.cursorWindowAlpha ?: nonTouchAlpha(context)

    fun type(context:Context):Int {
        val displayId=runCatching { context.display?.displayId }.getOrNull() ?: 0
        requirePermission(context,displayId)
        return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    }

    fun nonTouchAlpha(context:Context):Float = (context as? ExternalContext)?.nonTouchAlpha ?: run {
        val maximum=runCatching {
            context.getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
        }.getOrDefault(Float.NaN)
        // Unmanaged contexts cannot signal monitor transitions, so reserve all four layers.
        OverlayOpacityBudget.layerAlpha(maximum,4)
    }
}
