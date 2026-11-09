package dev.uwumouse.app.backend

import android.content.Context
import android.graphics.*
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
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
    private var closing=false
    private var applicationLayer=false
    private val pendingRetirement=ArrayList<View>()
    @Volatile private var x=0f
    @Volatile private var y=0f
    @Volatile private var size=20f
    @Volatile private var style="classic"
    private val motion=CursorMotionMailbox()
    private val motionFrame=Choreographer.FrameCallback {
        val position=motion.drain()
        if(position!=null && position.token==token && !closing) {
            x=position.x;y=position.y;size=position.size
            view?.invalidate()
        }
    }
    private val scheduleMotion=Runnable { Choreographer.getInstance().postFrameCallback(motionFrame) }
    fun setStyle(value:String) {
        require(value in setOf("classic", "dark", "cyan", "pink"))
        style=value
        main.post { view?.postInvalidateOnAnimation() }
    }
    @Volatile var error="";private set
    @Volatile var visible=false;private set
    private val verify=object:Runnable {
        override fun run() {
            cleanupRetired()
            if(view==null && pendingRetirement.isEmpty()) return
            if(closing || !allowed(displayId)) hideOnMain() else main.postDelayed(this,500)
        }
    }
    private fun <T> onMain(action:()->T):T {
        if(Looper.myLooper()==main.looper) return action()
        val task=FutureTask(action);check(main.post(task))
        return try { task.get(3,TimeUnit.SECONDS) } catch(failure:Exception) { task.cancel(false);throw failure }
    }
    fun show(display:Int,newX:Float,newY:Float,sizeDp:Float):Long = onMain {
        if(!valid(newX,newY,sizeDp) || !allowed(display)) return@onMain 0L
        if(closing) return@onMain 0L
        x=newX;y=newY;size=sizeDp
        if(view!=null && displayId==display) {
            token=++nextToken;motion.activate(token,newX,newY,sizeDp)
            view?.postInvalidateOnAnimation();return@onMain token
        }
        if(!hideOnMain()) return@onMain 0L
        runCatching {
            val screen=requireNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(display))
            // A system-alert window owned by shell sorts immediately above application overlays,
            // while remaining below the IME and critical system windows.
            // Shell holds INTERNAL_SYSTEM_WINDOW; use the old type as a safe fallback on
            // vendors that refuse this optional elevated layer.
            @Suppress("DEPRECATION")
            val types=intArrayOf(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            var failure:Throwable?=null
            for(windowType in types) try {
                val overlay=context.createDisplayContext(screen).createWindowContext(windowType,null)
                val manager=overlay.getSystemService(WindowManager::class.java)
                val cursor=CursorView(overlay)
                val p=WindowManager.LayoutParams(-1,-1,windowType,
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
                motion.activate(token,newX,newY,sizeDp)
                applicationLayer=windowType==WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                main.postDelayed(verify,500)
                return@onMain token
            } catch(e:Exception) {
                failure=e
            }
            throw failure ?: IllegalStateException("No se pudo mostrar el cursor")
        }.getOrElse { error=it.message ?: it.javaClass.simpleName;0L }
    }
    fun move(cursorToken:Long,newX:Float,newY:Float,sizeDp:Float) {
        if(!valid(newX,newY,sizeDp)) return
        if(motion.offer(cursorToken,newX,newY,sizeDp)) main.post(scheduleMotion)
    }
    /** Called only after adding a status visual, never for pointer movement. */
    fun raiseAboveStatus(display:Int):Boolean = onMain {
        if(!applicationLayer || view==null || displayId!=display) return@onMain true
        if(closing || !allowed(display) || !cleanupRetired()) return@onMain false
        val old=requireNotNull(view);val manager=requireNotNull(windows)
        val current=old.layoutParams as? WindowManager.LayoutParams ?: return@onMain false
        val params=WindowManager.LayoutParams().apply { copyFrom(current) }
        val raised=CursorView(old.context)
        try { manager.addView(raised,params) }
        catch(failure:RuntimeException) { error=failure.javaClass.simpleName;return@onMain false }
        try { manager.removeViewImmediate(old) }
        catch(failure:RuntimeException) {
            if(failure !is IllegalArgumentException || old.isAttachedToWindow) {
                error=failure.javaClass.simpleName
                try { manager.removeViewImmediate(raised) }
                catch(_:RuntimeException) { pendingRetirement.add(raised) }
                return@onMain false
            }
        }
        view=raised;raised.postInvalidateOnAnimation();true
    }
    fun hide() { onMain { hideOnMain() } }
    // Zero is an explicit owner-only cleanup after an ambiguous show Binder reply.
    // SupervisedInjector authenticates the application UID before reaching this method.
    fun hide(cursorToken:Long) { onMain { if(cursorToken==0L || cursorToken==token) hideOnMain() } }
    private fun hideOnMain():Boolean {
        motion.deactivate()
        closing=true
        main.removeCallbacks(verify)
        if(!cleanupRetired()) { main.postDelayed(verify,500);return false }
        val cursor=view
        if(cursor!=null) try { windows?.removeViewImmediate(cursor) }
        catch(failure:RuntimeException) {
            if(failure !is IllegalArgumentException || cursor.isAttachedToWindow) {
                error=failure.message ?: failure.javaClass.simpleName
                // A visual cleanup failure must never prevent releasing native input.
                // Keep its owner and retry; status remains visible until removal is confirmed.
                main.postDelayed(verify,500)
                return false
            }
        }
        view=null;windows=null;displayId=-1;visible=false;token=0L;applicationLayer=false
        closing=false
        return true
    }
    private fun cleanupRetired():Boolean {
        val manager=windows ?: return pendingRetirement.isEmpty()
        val it=pendingRetirement.iterator()
        while(it.hasNext()) {
            val extra=it.next()
            try { manager.removeViewImmediate(extra);it.remove() }
            catch(failure:RuntimeException) {
                if(failure is IllegalArgumentException && !extra.isAttachedToWindow) it.remove()
                else error=failure.javaClass.simpleName
            }
        }
        return pendingRetirement.isEmpty()
    }
    private fun valid(x:Float,y:Float,size:Float)=x.isFinite() && y.isFinite() && size.isFinite() && size in 10f..64f
    private inner class CursorView(ctx:Context):View(ctx) {
        private val density=resources.displayMetrics.density
        private val path=Path().apply { moveTo(0f,0f);lineTo(1.4f,20f);lineTo(7.8f,13.2f);lineTo(17.5f,13.6f);close() }
        private val fill=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(246,249,255);style=Paint.Style.FILL }
        private val edge=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(25,52,102);style=Paint.Style.STROKE;strokeWidth=1.1f;strokeJoin=Paint.Join.ROUND }
        private val origin=IntArray(2)
        override fun onDraw(canvas:Canvas) {
            fill.color=when(style) {
                "dark" -> Color.rgb(30,38,57)
                "cyan" -> Color.rgb(57,225,241)
                "pink" -> Color.rgb(255,126,184)
                else -> Color.rgb(246,249,255)
            }
            edge.color=if(style=="dark") Color.WHITE else Color.rgb(25,52,102)
            getLocationOnScreen(origin)
            canvas.save();canvas.translate(this@TrustedCursorOverlay.x-origin[0],this@TrustedCursorOverlay.y-origin[1]);canvas.scale(density*size/20f,density*size/20f)
            canvas.drawPath(path,fill);canvas.drawPath(path,edge);canvas.restore()
        }
    }
}
