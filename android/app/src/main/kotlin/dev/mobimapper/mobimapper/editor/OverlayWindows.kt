package dev.mobimapper.mobimapper.editor

import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import android.view.WindowManager

/** A standard overlay on an external display needs the user's explicit Android grant. */
class ExternalOverlayPermissionRequiredException(val displayId:Int):SecurityException(
    "Permite mostrar UwU Mouse sobre otras aplicaciones para usar esta pantalla",
)

/** Window admission and passthrough opacity are fixed when a presentation is created. */
object OverlayWindows {
    class ExternalContext internal constructor(base:Context,val nonTouchAlpha:Float):ContextWrapper(base)

    fun requirePermission(context:Context,displayId:Int) {
        if(displayId!=0 && !Settings.canDrawOverlays(context))
            throw ExternalOverlayPermissionRequiredException(displayId)
    }

    fun externalContext(windowContext:Context,nonTouchAlpha:Float):Context {
        require(nonTouchAlpha.isFinite() && nonTouchAlpha in 0f..1f)
        return ExternalContext(windowContext,nonTouchAlpha)
    }

    fun type(context:Context):Int = if(context is ExternalContext)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY

    fun nonTouchAlpha(context:Context):Float = (context as? ExternalContext)?.nonTouchAlpha ?: 1f
}
