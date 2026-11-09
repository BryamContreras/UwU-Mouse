package dev.uwumouse.app.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.WindowManager

/** Optional trusted, non-touchable rendering supplied by the session host. */
interface StatusVisualPort {
    fun show(kind:Int,displayId:Int,x:Int,y:Int,opacity:Float,bitmap:Bitmap):Boolean
    fun updatePosition(kind:Int,x:Int,y:Int,opacity:Float):Boolean = false
    fun hide(kind:Int)
    fun available():Boolean=true
    companion object { const val BUBBLE=1;const val MONITOR=2 }
}

/** Snapshots only on changes; the caller retains the ordinary-window fallback and hit testing. */
internal class StatusVisualRenderer(private val port:StatusVisualPort?,private val kind:Int,private val displayId:Int) {
    private data class Key(val x:Int,val y:Int,val width:Int,val height:Int,val opacity:Float,val revision:Long)
    private var last:Key?=null
    private var image:Bitmap?=null
    private var revision=0L
    private var retryAfter=0L
    private var trusted=false
    fun invalidate() { revision++ }
    fun show(view:View,p:WindowManager.LayoutParams,opacity:Float):Boolean {
        val target=port ?: return false
        if(!runCatching { target.available() }.getOrDefault(false)) { hide();return false }
        val key=Key(p.x,p.y,p.width,p.height,opacity,revision)
        if(trusted && last==key) return true
        if(!trusted && SystemClock.uptimeMillis()<retryAfter) return false
        if(p.width<=0 || p.height<=0) return false
        val previous=last
        if(trusted && previous!=null && previous.width==p.width && previous.height==p.height && previous.revision==revision) {
            val moved=runCatching { target.updatePosition(kind,p.x,p.y,opacity) }.getOrDefault(false)
            if(moved) { last=key;return true }
            runCatching { target.hide(kind) }
            trusted=false;last=null;image=null;retryAfter=SystemClock.uptimeMillis()+3000
            return false
        }
        val ok=try {
            val bitmap=if(previous!=null && previous.width==p.width && previous.height==p.height &&
                previous.revision==revision && image!=null) requireNotNull(image) else {
                if(view.width!=p.width || view.height!=p.height) {
                    view.measure(View.MeasureSpec.makeMeasureSpec(p.width,View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(p.height,View.MeasureSpec.EXACTLY))
                    view.layout(0,0,p.width,p.height)
                }
                val next=Bitmap.createBitmap(p.width,p.height,Bitmap.Config.ARGB_8888)
                val previousAlpha=view.alpha
                try {
                    // View.alpha is composed by its parent; the trusted host applies opacity once.
                    view.alpha=1f
                    view.draw(Canvas(next))
                } finally { view.alpha=previousAlpha }
                next
            }
            image=bitmap
            target.show(kind,displayId,p.x,p.y,opacity,bitmap)
        } catch(_:RuntimeException) { false }
        if(ok) { trusted=true;last=key;retryAfter=0L }
        else {
            if(trusted) runCatching { target.hide(kind) }
            trusted=false;last=null;image=null;retryAfter=SystemClock.uptimeMillis()+3000
        }
        return ok
    }
    fun hide() {
        if(trusted) runCatching { port?.hide(kind) }
        trusted=false;last=null;image=null;retryAfter=0L
    }
}
