package dev.mobimapper.mobimapper.editor

import android.content.Context

/** The application composes this port with the privileged passive visual renderer. */
interface VisualCursorHost {
    fun show(displayId:Int,x:Float,y:Float,sizeDp:Float):Boolean
    fun move(x:Float,y:Float,sizeDp:Float)
    fun hide()
}

/** Display-coordinate hotspot; rendering has no touch target and uses full opacity. */
class GameCursorOverlay(private val service:Context,private val overlayContext:Context=service,private val host:VisualCursorHost) {
    private var sizeDp=BubbleSettings.DEFAULT_CURSOR_SIZE_DP
    private var x=0f
    private var y=0f
    var visible=false;private set
    fun setSizeDp(sizeDp:Float) {
        require(sizeDp.isFinite() && sizeDp in BubbleSettings.MIN_CURSOR_SIZE_DP..BubbleSettings.MAX_CURSOR_SIZE_DP)
        this.sizeDp=sizeDp
        if(visible) host.move(x,y,sizeDp)
    }
    fun refreshWindowOpacity() = Unit // The trusted visual surface always has alpha 1.
    @Suppress("UNUSED_PARAMETER")
    fun moveTo(x:Float,y:Float,latencyReceiptToken:Long=0L) {
        if(!x.isFinite() || !y.isFinite()) return
        this.x=x;this.y=y
        if(visible) host.move(x,y,sizeDp)
    }
    fun show() {
        if(visible) return
        check(host.show(overlayContext.display?.displayId ?: 0,x,y,sizeDp)) { "No se pudo mostrar el cursor opaco; actualiza o reactiva el motor" }
        visible=true
    }
    fun hide() {
        if(!visible) return
        visible=false;runCatching { host.hide() }
    }
}
