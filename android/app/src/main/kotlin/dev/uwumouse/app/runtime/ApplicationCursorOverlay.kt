package dev.uwumouse.app.runtime

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dev.uwumouse.app.editor.OverlayWindows
import dev.uwumouse.app.backend.VisualCursorRenderer
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** App-owned cursor window. Unlike a shell process, this PID is registered with WindowManager. */
internal class ApplicationCursorOverlay(private val context:Context,private val cursorStyle:String="classic"):VisualCursorRenderer {
    private val main=Handler(Looper.getMainLooper())
    private var windows:WindowManager?=null
    @Volatile private var view:CursorView?=null
    private var params:WindowManager.LayoutParams?=null
    @Volatile private var x=0f
    @Volatile private var y=0f
    @Volatile private var size=20f
    @Volatile var error="";private set
    val visible:Boolean get()=view!=null

    private fun <T> onMain(action:()->T):T {
        if(Looper.myLooper()==main.looper) return action()
        val task=FutureTask(action)
        check(main.post(task)) { "No se pudo publicar el cursor en el hilo principal" }
        return try { task.get(3,TimeUnit.SECONDS) }
        catch(failure:Exception) { task.cancel(false);throw IllegalStateException("No se pudo actualizar el cursor",failure) }
    }

    override fun show(displayId:Int,newX:Float,newY:Float,sizeDp:Float):Boolean = onMain {
        if(!valid(newX,newY,sizeDp)) return@onMain false
        if(context.display?.displayId!=displayId) { error="Pantalla del cursor no disponible";return@onMain false }
        val windowAlpha=OverlayWindows.cursorWindowAlpha(context)
        if(!windowAlpha.isFinite() || windowAlpha<=0f) { error="No se pudo consultar el límite de opacidad de Android";return@onMain false }
        x=newX;y=newY;size=sizeDp
        if(view!=null) { view?.requestFrame();return@onMain true }
        runCatching {
            val manager=context.getSystemService(WindowManager::class.java)
            val cursor=CursorView(context)
            val layout=WindowManager.LayoutParams(-1,-1,OverlayWindows.type(context),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply {
                alpha=windowAlpha
                gravity=Gravity.TOP or Gravity.START
                title="UwU Mouse Cursor"
                packageName=context.packageName
                layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            }
            manager.addView(cursor,layout)
            windows=manager;view=cursor;params=layout;error=""
            cursor.requestFrame()
            true
        }.getOrElse {
            error=it.message ?: it.javaClass.simpleName
            false
        }
    }

    override fun move(newX:Float,newY:Float,sizeDp:Float) {
        if(!valid(newX,newY,sizeDp)) return
        onMain {
            if(x==newX && y==newY && size==sizeDp) return@onMain
            x=newX;y=newY;size=sizeDp
            view?.requestFrame()
        }
    }

    override fun refreshWindowOpacity() = onMain {
        val cursor=view ?: return@onMain
        val layout=params ?: return@onMain
        val alpha=OverlayWindows.cursorWindowAlpha(context)
        if(layout.alpha!=alpha) {
            layout.alpha=alpha
            windows?.updateViewLayout(cursor,layout)
        }
    }

    override fun hide() = onMain {
        val cursor=view
        if(cursor!=null) try { windows?.removeViewImmediate(cursor) }
        catch(failure:RuntimeException) {
            if(failure !is IllegalArgumentException || cursor.isAttachedToWindow) {
                error=failure.message ?: failure.javaClass.simpleName
                throw failure // Retain the owner so a later cleanup can retry the same window.
            }
        }
        view=null;params=null
        windows=null
        error=""
    }

    private fun valid(x:Float,y:Float,size:Float)=x.isFinite() && y.isFinite() && size.isFinite() && size in 10f..64f

    private inner class CursorView(ctx:Context):View(ctx) {
        private val density=resources.displayMetrics.density
        private val path=Path().apply { moveTo(0f,0f);lineTo(1.4f,20f);lineTo(7.8f,13.2f);lineTo(17.5f,13.6f);close() }
        private val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=when(cursorStyle) {
                "dark" -> Color.rgb(30,38,57)
                "cyan" -> Color.rgb(57,225,241)
                "pink" -> Color.rgb(255,126,184)
                else -> Color.rgb(246,249,255)
            }
            style=Paint.Style.FILL
        }
        private val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color=if(cursorStyle=="dark") Color.WHITE else Color.rgb(25,52,102)
            style=Paint.Style.STROKE;strokeWidth=1.1f;strokeJoin=Paint.Join.ROUND
        }
        private val origin=IntArray(2)
        private var framePending=false
        private val drawFrame=Runnable { framePending=false;invalidate() }
        /** Conflate visual motion only; clicks and touch coordinates keep their input order. */
        fun requestFrame() {
            if(framePending) return
            framePending=true
            postOnAnimation(drawFrame)
        }
        override fun onDetachedFromWindow() {
            removeCallbacks(drawFrame)
            framePending=false
            super.onDetachedFromWindow()
        }
        override fun onDraw(canvas:Canvas) {
            getLocationOnScreen(origin)
            canvas.save()
            canvas.translate(this@ApplicationCursorOverlay.x-origin[0],this@ApplicationCursorOverlay.y-origin[1])
            canvas.scale(density*this@ApplicationCursorOverlay.size/20f,density*this@ApplicationCursorOverlay.size/20f)
            canvas.drawPath(path,fill);canvas.drawPath(path,edge)
            canvas.restore()
        }
    }
}
