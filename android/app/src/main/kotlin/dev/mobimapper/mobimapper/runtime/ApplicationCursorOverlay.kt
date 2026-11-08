package dev.mobimapper.mobimapper.runtime

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
import dev.mobimapper.mobimapper.editor.OverlayWindows
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** App-owned cursor window. Unlike a shell process, this PID is registered with WindowManager. */
internal class ApplicationCursorOverlay(private val context:Context) {
    private val main=Handler(Looper.getMainLooper())
    private var windows:WindowManager?=null
    private var view:CursorView?=null
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

    fun show(newX:Float,newY:Float,sizeDp:Float):Boolean = onMain {
        if(!valid(newX,newY,sizeDp)) return@onMain false
        x=newX;y=newY;size=sizeDp
        if(view!=null) { view?.postInvalidateOnAnimation();return@onMain true }
        runCatching {
            val manager=context.getSystemService(WindowManager::class.java)
            val cursor=CursorView(context)
            val layout=WindowManager.LayoutParams(-1,-1,OverlayWindows.type(context),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply {
                alpha=OverlayWindows.cursorWindowAlpha(context)
                gravity=Gravity.TOP or Gravity.START
                title="UwU Mouse Cursor"
                packageName=context.packageName
                layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            }
            manager.addView(cursor,layout)
            windows=manager;view=cursor;params=layout;error=""
            cursor.postInvalidateOnAnimation()
            true
        }.getOrElse {
            error=it.message ?: it.javaClass.simpleName
            false
        }
    }

    fun move(newX:Float,newY:Float,sizeDp:Float) {
        if(!valid(newX,newY,sizeDp)) return
        onMain {
            x=newX;y=newY;size=sizeDp
            view?.postInvalidateOnAnimation()
        }
    }

    fun refreshWindowOpacity() = onMain {
        val cursor=view ?: return@onMain
        val layout=params ?: return@onMain
        val alpha=OverlayWindows.cursorWindowAlpha(context)
        if(layout.alpha!=alpha) {
            layout.alpha=alpha
            windows?.updateViewLayout(cursor,layout)
        }
    }

    fun hide() = onMain {
        val cursor=view
        view=null;params=null
        if(cursor!=null) runCatching { windows?.removeViewImmediate(cursor) }
        windows=null
        error=""
    }

    private fun valid(x:Float,y:Float,size:Float)=x.isFinite() && y.isFinite() && size.isFinite() && size in 10f..64f

    private inner class CursorView(ctx:Context):View(ctx) {
        private val density=resources.displayMetrics.density
        private val path=Path().apply { moveTo(0f,0f);lineTo(1.4f,20f);lineTo(7.8f,13.2f);lineTo(17.5f,13.6f);close() }
        private val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(246,249,255);style=Paint.Style.FILL }
        private val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(25,52,102);style=Paint.Style.STROKE;strokeWidth=1.1f;strokeJoin=Paint.Join.ROUND }
        private val origin=IntArray(2)
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
