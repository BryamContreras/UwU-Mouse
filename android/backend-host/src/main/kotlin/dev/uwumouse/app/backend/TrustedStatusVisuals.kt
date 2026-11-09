package dev.uwumouse.app.backend

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Shell-owned, non-touchable mirrors of our app's small game overlays. */
internal class TrustedStatusVisuals(private val context:Context,private val allowed:(String,Int)->Boolean) {
    private val main=Handler(Looper.getMainLooper())
    private data class Visual(val kind:Int,val displayId:Int,val target:String,val token:Long,
                              val client:IBinder,val death:IBinder.DeathRecipient,
                              val manager:WindowManager,val view:BitmapView,val params:WindowManager.LayoutParams)
    private val visuals=HashMap<Int,Visual>()
    private val pendingRemoval=HashSet<Int>()
    private var nextToken=0L
    @Volatile var bubbleVisible=false;private set
    @Volatile var monitorVisible=false;private set
    @Volatile private var bubbleToken=0L
    @Volatile private var monitorToken=0L
    @Volatile var error="";private set

    private val verify=object:Runnable {
        override fun run() {
            for(visual in visuals.values.toList()) {
                if(visual.kind in pendingRemoval || !visual.client.isBinderAlive || !isAllowed(visual.target,visual.displayId)) hideOnMain(visual.kind,visual.token)
            }
            if(visuals.isNotEmpty()) main.postDelayed(this,500)
        }
    }
    private fun <T> onMain(action:()->T):T {
        if(Looper.myLooper()==main.looper) return action()
        val task=FutureTask(action);check(main.post(task))
        return try { task.get(3,TimeUnit.SECONDS) } catch(failure:Exception) { task.cancel(false);throw failure }
    }
    private fun validKind(kind:Int)=kind==1 || kind==2
    private fun isAllowed(target:String,displayId:Int)=runCatching { allowed(target,displayId) }.getOrDefault(false)
    private fun validBitmap(bitmap:Bitmap?)=bitmap!=null && !bitmap.isRecycled && bitmap.config!=Bitmap.Config.HARDWARE &&
        bitmap.width in 1..1024 && bitmap.height in 1..1024 && bitmap.width.toLong()*bitmap.height*4<=512*1024
    private fun validPosition(displayId:Int,x:Int,y:Int,width:Int,height:Int):Boolean {
        val display=context.getSystemService(DisplayManager::class.java).getDisplay(displayId) ?: return false
        if(!display.isValid) return false
        val size=Point()
        @Suppress("DEPRECATION") display.getRealSize(size)
        // The controller bubble deliberately docks half outside the display edge.
        // Limit clipping so at least half of each dimension remains on screen.
        return width<=size.x && height<=size.y && x>=-width/2 && y>=-height/2 &&
            x.toLong()+width/2<=size.x && y.toLong()+height/2<=size.y
    }
    private fun validAlpha(alpha:Float)=alpha.isFinite() && alpha in 0f..1f

    fun show(kind:Int,displayId:Int,target:String,x:Int,y:Int,alpha:Float,bitmap:Bitmap?,client:IBinder):Long = onMain {
        if(!validKind(kind) || !validBitmap(bitmap) || !validAlpha(alpha) || !client.isBinderAlive ||
            !validPosition(displayId,x,y,bitmap!!.width,bitmap.height) || !isAllowed(target,displayId)) return@onMain 0L
        if(!hideOnMain(kind,0)) return@onMain 0L
        runCatching {
            val display=requireNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(displayId))
            val overlay=context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null)
            val manager=overlay.getSystemService(WindowManager::class.java)
            val root=BitmapView(overlay,bitmap)
            val params=WindowManager.LayoutParams(bitmap.width,bitmap.height,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    (if(kind==1) WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS else 0),
                PixelFormat.TRANSLUCENT).apply {
                this.x=x;this.y=y;this.alpha=alpha;gravity=Gravity.TOP or Gravity.START
                title=if(kind==1) "UwU Mouse · Burbuja visual" else "UwU Mouse · Monitor visual"
                packageName="com.android.shell"
                layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                setFitInsetsTypes(0)
            }
            val type=WindowManager.LayoutParams::class.java
            val privateFlags=type.getField("privateFlags")
            privateFlags.setInt(params,privateFlags.getInt(params) or type.getField("PRIVATE_FLAG_TRUSTED_OVERLAY").getInt(null))
            val token=++nextToken
            val death=IBinder.DeathRecipient { main.post { hideOnMain(kind,token) } }
            client.linkToDeath(death,0)
            try { manager.addView(root,params) } catch(failure:Throwable) { client.unlinkToDeath(death,0);throw failure }
            visuals[kind]=Visual(kind,displayId,target,token,client,death,manager,root,params)
            markVisible(kind,token);error=""
            main.removeCallbacks(verify);main.postDelayed(verify,500)
            token
        }.getOrElse { error=it.javaClass.simpleName;0L }
    }

    fun update(kind:Int,token:Long,x:Int,y:Int,alpha:Float,bitmap:Bitmap?):Boolean = onMain {
        val visual=visuals[kind] ?: return@onMain false
        if(token==0L || visual.token!=token || !validAlpha(alpha) || !visual.client.isBinderAlive ||
            !isAllowed(visual.target,visual.displayId) || (bitmap!=null && !validBitmap(bitmap))) return@onMain false
        val width=bitmap?.width ?: visual.params.width;val height=bitmap?.height ?: visual.params.height
        if(!validPosition(visual.displayId,x,y,width,height)) return@onMain false
        runCatching {
            val layoutChanged=visual.params.x!=x || visual.params.y!=y || visual.params.width!=width ||
                visual.params.height!=height || visual.params.alpha!=alpha
            visual.params.x=x;visual.params.y=y;visual.params.width=width;visual.params.height=height;visual.params.alpha=alpha
            if(bitmap!=null) visual.view.setImage(bitmap)
            if(layoutChanged) visual.manager.updateViewLayout(visual.view,visual.params)
            true
        }.getOrElse { error=it.javaClass.simpleName;false }
    }
    fun hide(kind:Int,token:Long):Boolean = onMain { if(!validKind(kind)) false else hideOnMain(kind,token) }
    /** Fast, lock-free status query; does not perform WMS calls or start a process. */
    fun isVisible(kind:Int,token:Long):Boolean = token!=0L && when(kind) {
        1 -> bubbleToken==token
        2 -> monitorToken==token
        else -> false
    }
    fun hideAll() = onMain { visuals.values.toList().forEach { hideOnMain(it.kind,it.token) } }
    private fun hideOnMain(kind:Int,token:Long):Boolean {
        val visual=visuals[kind] ?: return true
        if(token!=0L && token!=visual.token) return false
        try { visual.manager.removeViewImmediate(visual.view) }
        catch(failure:RuntimeException) {
            if(failure !is IllegalArgumentException || visual.view.isAttachedToWindow) {
                error=failure.javaClass.simpleName
                pendingRemoval.add(kind)
                main.removeCallbacks(verify);main.postDelayed(verify,500)
                return false
            }
        }
        visuals.remove(kind);pendingRemoval.remove(kind);runCatching { visual.client.unlinkToDeath(visual.death,0) };markVisible(kind,0L)
        if(visuals.isEmpty()) main.removeCallbacks(verify)
        return true
    }
    private fun markVisible(kind:Int,token:Long) {
        if(kind==1) { bubbleToken=token;bubbleVisible=token!=0L }
        else { monitorToken=token;monitorVisible=token!=0L }
    }
    private class BitmapView(context:Context,private var bitmap:Bitmap):View(context) {
        private val paint=Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        fun setImage(value:Bitmap) { bitmap=value;invalidate() }
        override fun onDraw(canvas:Canvas) { canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);canvas.drawBitmap(bitmap,0f,0f,paint) }
    }
}
