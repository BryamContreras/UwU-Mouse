package dev.mobimapper.mobimapper.backend

import android.content.Context
import android.graphics.*
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Shell-owned passive visual. Trust is granted by WMS permission, never by changing system settings. */
internal class TrustedCursorOverlay(private val context:Context,private val allowed:(Int)->Boolean) {
    private val main=Handler(Looper.getMainLooper())
    private var windows:WindowManager?=null
    private var view:CursorView?=null
    private var displayId=-1
    private var nextToken=0L
    private var token=0L
    @Volatile private var x=0f
    @Volatile private var y=0f
    @Volatile private var size=20f
    @Volatile var error="";private set
    @Volatile var visible=false;private set
    private val verify=object:Runnable {
        override fun run() {
            if(view==null) return
            if(!allowed(displayId)) hideOnMain() else main.postDelayed(this,500)
        }
    }
    private fun <T> onMain(action:()->T):T {
        if(Looper.myLooper()==main.looper) return action()
        val task=FutureTask(action);check(main.post(task))
        return try { task.get(3,TimeUnit.SECONDS) } catch(failure:Exception) { task.cancel(false);throw failure }
    }
    fun show(display:Int,newX:Float,newY:Float,sizeDp:Float):Long = onMain {
        if(!valid(newX,newY,sizeDp) || !allowed(display)) return@onMain 0L
        x=newX;y=newY;size=sizeDp
        if(view!=null && displayId==display) { token=++nextToken;view?.postInvalidateOnAnimation();return@onMain token }
        hideOnMain()
        runCatching {
            val screen=requireNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(display))
            val overlay=context.createDisplayContext(screen).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null)
            val manager=overlay.getSystemService(WindowManager::class.java)
            val cursor=CursorView(overlay)
            val p=WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply {
                alpha=1f;gravity=Gravity.TOP or Gravity.START;title="UwU Mouse · Cursor opaco"
                packageName="com.android.shell"
                layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            }
            // The shell owns INTERNAL_SYSTEM_WINDOW. WMS rejects this flag for ordinary apps.
            val type=WindowManager.LayoutParams::class.java
            val privateFlags=type.getField("privateFlags")
            privateFlags.setInt(p,privateFlags.getInt(p) or type.getField("PRIVATE_FLAG_TRUSTED_OVERLAY").getInt(null))
            manager.addView(cursor,p)
            windows=manager;view=cursor;displayId=display;visible=true;error="";token=++nextToken
            main.postDelayed(verify,500)
            token
        }.getOrElse { error=it.message ?: it.javaClass.simpleName;0L }
    }
    fun move(cursorToken:Long,newX:Float,newY:Float,sizeDp:Float) {
        if(!valid(newX,newY,sizeDp)) return
        main.post {
            if(cursorToken==0L || cursorToken!=token) return@post
            x=newX;y=newY;size=sizeDp;view?.postInvalidateOnAnimation()
        }
    }
    fun hide() { onMain { hideOnMain() } }
    // Zero is an explicit owner-only cleanup after an ambiguous show Binder reply.
    // SupervisedInjector authenticates the application UID before reaching this method.
    fun hide(cursorToken:Long) { onMain { if(cursorToken==0L || cursorToken==token) hideOnMain() } }
    private fun hideOnMain() {
        main.removeCallbacks(verify)
        view?.let { cursor -> runCatching { windows?.removeViewImmediate(cursor) } }
        view=null;windows=null;displayId=-1;visible=false;token=0L
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
            canvas.save();canvas.translate(this@TrustedCursorOverlay.x-origin[0],this@TrustedCursorOverlay.y-origin[1]);canvas.scale(density*size/20f,density*size/20f)
            canvas.drawPath(path,fill);canvas.drawPath(path,edge);canvas.restore()
        }
    }
}
