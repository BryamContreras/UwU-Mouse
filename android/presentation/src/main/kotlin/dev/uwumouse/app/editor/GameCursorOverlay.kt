package dev.uwumouse.app.editor

import android.content.Context

typealias VisualCursorHost = dev.uwumouse.app.backend.VisualCursorRenderer

/** Display-coordinate hotspot; rendering has no touch target and follows the overlay opacity budget. */
class GameCursorOverlay(private val service:Context,private val overlayContext:Context=service,
    private val host:VisualCursorHost,private val onMoveFailure:(Exception)->Unit) {
    private var sizeDp=BubbleSettings.DEFAULT_CURSOR_SIZE_DP
    private var x=0f
    private var y=0f
    var visible=false;private set
    val cleanupPending:Boolean get()=host.cleanupPending
    fun setSizeDp(sizeDp:Float) {
        require(sizeDp.isFinite() && sizeDp in BubbleSettings.MIN_CURSOR_SIZE_DP..BubbleSettings.MAX_CURSOR_SIZE_DP)
        this.sizeDp=sizeDp
        if(visible) moveTo(x,y)
    }
    fun refreshWindowOpacity() { if(visible) host.refreshWindowOpacity() }
    @Suppress("UNUSED_PARAMETER")
    fun moveTo(x:Float,y:Float,latencyReceiptToken:Long=0L):Boolean {
        if(!x.isFinite() || !y.isFinite()) return false
        this.x=x;this.y=y
        if(visible) try { host.move(x,y,sizeDp) }
        catch(failure:Exception) { hide();onMoveFailure(failure);return false }
        return true
    }
    fun show() {
        check(!cleanupPending) { "Espera a que se cierre el cursor anterior" }
        if(visible) return
        check(host.show(overlayContext.display?.displayId ?: 0,x,y,sizeDp)) { "No se pudo mostrar el cursor; comprueba el permiso de superposición o reactiva el motor" }
        visible=true
    }
    fun hide():Boolean {
        return try { host.hide();visible=false;true }
        catch(_:Exception) { false }
    }
}
