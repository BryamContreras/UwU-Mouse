package dev.uwumouse.app.backend

/** A passive cursor renderer. It never owns input capture or injected contacts. */
interface VisualCursorRenderer {
    val cleanupPending:Boolean get()=false
    fun show(displayId:Int,x:Float,y:Float,sizeDp:Float):Boolean
    fun move(x:Float,y:Float,sizeDp:Float)
    fun refreshWindowOpacity() {}
    fun hide()
}

/**
 * Prefer the trusted renderer when WindowManager admits it; otherwise use the app window.
 * The owner creates a new router when the backend Binder or target display changes. A rejected
 * capability is therefore cached only for that backend/display, never for a phone model or SDK.
 */
class VisualCursorRouter(private val trusted:VisualCursorRenderer,private val application:VisualCursorRenderer):VisualCursorRenderer {
    private var trustedRejected=false
    private var pendingCleanup:VisualCursorRenderer?=null
    private var selected:VisualCursorRenderer?=null
    private var displayId=-1
    var route="none";private set
    var trustedError="";private set
    var error="";private set
    override val cleanupPending:Boolean get()=pendingCleanup!=null

    override fun show(displayId:Int,x:Float,y:Float,sizeDp:Float):Boolean {
        if(selected!=null) { move(x,y,sizeDp);return true }
        this.displayId=displayId
        pendingCleanup?.let { if(!close(it)) return false }
        if(!trustedRejected) {
            val admitted=try { trusted.show(displayId,x,y,sizeDp) }
                catch(failure:Exception) { trustedError=failure.javaClass.simpleName;false }
            if(admitted) { selected=trusted;route="trusted";error="";return true }
            if(trustedError.isEmpty()) trustedError="window_not_admitted"
            trustedRejected=true
            // A failed Binder reply can follow a successful addView. Clean up before fallback.
            if(!close(trusted)) return false
        }
        return showApplication(x,y,sizeDp)
    }

    private fun showApplication(x:Float,y:Float,sizeDp:Float):Boolean {
        val admitted=try { application.show(displayId,x,y,sizeDp) }
            catch(failure:Exception) { error=failure.javaClass.simpleName;false }
        if(admitted) { selected=application;route="application";error="";return true }
        close(application)
        selected=null;route="none"
        if(error.isEmpty()) error="application_window_not_admitted"
        return false
    }

    private fun close(renderer:VisualCursorRenderer):Boolean {
        pendingCleanup=renderer
        return try { renderer.hide();pendingCleanup=null;true }
            catch(failure:Exception) {
                val owner=if(renderer===trusted) "trusted" else "application"
                error="${owner}_cleanup_${failure.javaClass.simpleName}";false
            }
    }

    override fun move(x:Float,y:Float,sizeDp:Float) {
        val renderer=selected ?: return
        try { renderer.move(x,y,sizeDp) }
        catch(failure:Exception) {
            if(renderer!==trusted) { error=failure.javaClass.simpleName;throw failure }
            trustedError=failure.javaClass.simpleName;trustedRejected=true
            selected=null;route="none"
            check(close(trusted)) { "No se pudo cerrar el cursor anterior: $error" }
            check(showApplication(x,y,sizeDp)) { "No se pudo recuperar el cursor: $error" }
        }
    }

    override fun refreshWindowOpacity() { selected?.refreshWindowOpacity() }

    override fun hide() {
        val renderer=selected
        selected=null;route="none"
        val pending=renderer ?: pendingCleanup
        if(pending!=null) check(close(pending)) { "No se pudo confirmar el cierre del cursor: $error" }
    }
}
