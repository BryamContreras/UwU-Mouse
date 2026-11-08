package dev.mobimapper.mobimapper.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dev.mobimapper.mobimapper.session.InputLatencyProbe

/** Display-coordinate hotspot. This window can never become an injection target. */
class GameCursorOverlay(private val service:Context,private val overlayContext:Context=service) {
    private val windows=overlayContext.getSystemService(WindowManager::class.java)
    private var view:PointerView?=null
    private var parameters:WindowManager.LayoutParams?=null
    private var sizeDp=BubbleSettings.DEFAULT_CURSOR_SIZE_DP
    private var x=0f
    private var y=0f
    val visible:Boolean get()=view!=null
    fun setSizeDp(sizeDp:Float) {
        require(sizeDp.isFinite() && sizeDp in BubbleSettings.MIN_CURSOR_SIZE_DP..BubbleSettings.MAX_CURSOR_SIZE_DP)
        if(this.sizeDp==sizeDp) return
        this.sizeDp=sizeDp
        view?.apply { pointerScale=sizeDp/20f;postInvalidateOnAnimation() }
    }
    fun refreshWindowOpacity() {
        val pointer=view ?: return
        val p=parameters ?: return
        val alpha=OverlayWindows.cursorWindowAlpha(overlayContext)
        if(p.alpha==alpha) return
        p.alpha=alpha;windows.updateViewLayout(pointer,p)
    }
    fun moveTo(x:Float,y:Float,latencyReceiptToken:Long=0L) {
        if(!x.isFinite() || !y.isFinite()) return
        this.x=x;this.y=y
        view?.apply {
            pointerX=x;pointerY=y;pendingLatencyReceiptToken=latencyReceiptToken
            postInvalidateOnAnimation()
        }
    }
    fun show() {
        if(view!=null) return
        val pointer=PointerView(overlayContext).apply {
            pointerX=x;pointerY=y;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES
            pointerScale=sizeDp/20f
            contentDescription="MobiMapper · cursor visual"
        }
        val p=WindowManager.LayoutParams(-1,-1,OverlayWindows.type(overlayContext),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,PixelFormat.TRANSLUCENT).apply {
            gravity=Gravity.TOP or Gravity.START;title="MobiMapper · Cursor"
            alpha=OverlayWindows.cursorWindowAlpha(overlayContext)
            layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;setFitInsetsTypes(0)
        }
        // A failed admission must never advertise a visible cursor to the session owner.
        windows.addView(pointer,p);view=pointer;parameters=p
    }
    fun hide() {
        val prior=view ?: return;view=null;parameters=null
        try { windows.removeViewImmediate(prior) } catch (_:IllegalArgumentException) { /* Display removal may detach it first. */ }
    }
    private class PointerView(context:Context):View(context) {
        var pointerX=0f;var pointerY=0f
        var pointerScale=1f
        var pendingLatencyReceiptToken=0L
        private val density=resources.displayMetrics.density
        private val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style=Paint.Style.FILL
            shader=LinearGradient(0f,0f,16*density,20*density,
                intArrayOf(Color.rgb(156,235,255),Color.rgb(72,189,255),Color.rgb(36,132,235)),null,Shader.TileMode.CLAMP)
        }
        private val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=Color.rgb(21,53,94);style=Paint.Style.STROKE;strokeWidth=.95f*density
            strokeJoin=Paint.Join.ROUND
        }
        private val shadow=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=Color.argb(60,8,31,64);style=Paint.Style.STROKE;strokeWidth=2.2f*density;strokeJoin=Paint.Join.ROUND
        }
        // The local origin stays the hotspot. Paths and gradient are built once, not per move.
        private val path=Path().apply {
            moveTo(0f,0f);lineTo(1.4f*density,20*density);lineTo(7.8f*density,13.2f*density)
            lineTo(17.5f*density,13.6f*density);close()
        }
        private val origin=IntArray(2)
        override fun onDraw(canvas:Canvas) {
            getLocationOnScreen(origin)
            canvas.save()
            canvas.translate(pointerX-origin[0],pointerY-origin[1])
            // Scale around the arrow tip; drawing size never shifts the shared click hotspot.
            canvas.scale(pointerScale,pointerScale)
            canvas.save();canvas.translate(.6f*density,.8f*density);canvas.drawPath(path,shadow);canvas.restore()
            canvas.drawPath(path,fill);canvas.drawPath(path,edge)
            canvas.restore()
            val token=pendingLatencyReceiptToken
            pendingLatencyReceiptToken=0L
            if(token!=0L) InputLatencyProbe.recordCursorDraw(token)
        }
    }
}
