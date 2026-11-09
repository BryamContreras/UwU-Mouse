package dev.uwumouse.app.game

import android.graphics.Bitmap
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.uwumouse.app.backend.BackendRegistry
import dev.uwumouse.app.backend.IInjector
import dev.uwumouse.app.editor.StatusVisualPort
import dev.uwumouse.app.session.SessionController
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Uses shell's trusted, non-touchable window; the app retains a safe fallback. */
internal class TrustedStatusVisualPort(private val onLost:()->Unit):StatusVisualPort {
    private val owner=Binder()
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var backend:IInjector?=null
    private var binder:IBinder?=null
    private var death:IBinder.DeathRecipient?=null
    // A zero token denotes an ambiguous admission that must be cleaned up by kind.
    private val tokens=HashMap<Int,Long>()
    private val pendingRemoval=HashSet<Int>()
    @Volatile private var healthSnapshot:Map<Int,Long> = emptyMap()
    @Volatile private var pendingSnapshot:Set<Int> = emptySet()
    private var healthWorker:ScheduledExecutorService?=null
    private var closeRequested=false

    private data class Motion(val kind:Int,val token:Long,val x:Int,val y:Int,val alpha:Float,
                              val version:Long,val backend:IInjector)
    private class Lane {
        val pending=AtomicReference<Motion?>(null)
        val running=AtomicBoolean(false)
        val version=AtomicLong(0)
    }
    private val bubbleMotion=Lane()
    private val monitorMotion=Lane()
    private fun lane(kind:Int):Lane? = when(kind) {
        StatusVisualPort.BUBBLE -> bubbleMotion
        StatusVisualPort.MONITOR -> monitorMotion
        else -> null
    }
    private fun invalidateMotion(kind:Int) {
        lane(kind)?.let { it.version.incrementAndGet();it.pending.set(null) }
    }

    override fun available():Boolean = BackendRegistry.injector?.asBinder()?.isBinderAlive==true

    override fun show(kind:Int,displayId:Int,x:Int,y:Int,opacity:Float,bitmap:Bitmap):Boolean {
        check(Looper.myLooper()==Looper.getMainLooper())
        if(lane(kind)==null) return false
        val current=BackendRegistry.injector ?: return false
        val currentBinder=current.asBinder()
        if(!currentBinder.isBinderAlive) return false
        if(binder!==currentBinder) {
            close()
            // Retain the old owner until its potentially admitted window is gone.
            if(tokens.isNotEmpty()) return false
            val recipient=IBinder.DeathRecipient {
                main.post {
                    if(binder===currentBinder) {
                        invalidateMotion(StatusVisualPort.BUBBLE);invalidateMotion(StatusVisualPort.MONITOR)
                        tokens.clear();pendingRemoval.clear();backend=null;binder=null;death=null;publishHealth()
                        if(!closeRequested) onLost()
                    }
                }
            }
            if(!runCatching { currentBinder.linkToDeath(recipient,0);true }.getOrDefault(false)) return false
            backend=current;binder=currentBinder;death=recipient
        }
        closeRequested=false
        invalidateMotion(kind)
        if(kind in pendingRemoval && !cleanup(kind)) return false
        return try {
            val token=tokens[kind]
            if(token!=null && token!=0L) {
                if(current.updateTrustedStatusVisual(kind,token,x,y,opacity,bitmap)) return true
                // update=false may leave the old surface alive; retire it before replacement.
                if(!cleanup(kind)) return false
            }
            val created=current.showTrustedStatusVisual(kind,displayId,SessionController.targetPackage,x,y,opacity,bitmap,owner)
            if(created>0) { tokens[kind]=created;pendingRemoval.remove(kind);publishHealth();true }
            else { tokens[kind]=0L;pendingRemoval.add(kind);publishHealth();cleanup(kind);false }
        } catch(_:Exception) {
            // A failed reply may have admitted a surface even when the token is unknown.
            if(tokens[kind]==null) tokens[kind]=0L
            pendingRemoval.add(kind);publishHealth();cleanup(kind)
            false
        }
    }

    /** Motion is conflated on a single background worker, never on the input/UI path. */
    override fun updatePosition(kind:Int,x:Int,y:Int,opacity:Float):Boolean {
        check(Looper.myLooper()==Looper.getMainLooper())
        val token=tokens[kind]?.takeIf { it!=0L } ?: return false
        if(kind in pendingRemoval) return false
        val current=backend ?: return false
        val worker=healthWorker ?: return false
        val lane=lane(kind) ?: return false
        lane.pending.set(Motion(kind,token,x,y,opacity,lane.version.get(),current))
        return enqueue(lane,worker)
    }

    private fun enqueue(lane:Lane,worker:ScheduledExecutorService):Boolean {
        if(!lane.running.compareAndSet(false,true)) return true
        return try {
            worker.execute {
                try {
                    while(true) {
                        val motion=lane.pending.getAndSet(null) ?: break
                        if(lane.version.get()!=motion.version) continue
                        val ok=runCatching {
                            motion.backend.updateTrustedStatusVisual(motion.kind,motion.token,motion.x,motion.y,motion.alpha,null)
                        }.getOrDefault(false)
                        val stale=lane.version.get()!=motion.version
                        if(!ok || stale) main.post {
                            // A concurrent image update may have reused the same token. Rebuild
                            // the latest visual if an older motion finished after that update.
                            if(!closeRequested && backend===motion.backend && tokens[motion.kind]==motion.token && motion.kind !in pendingRemoval) onLost()
                        }
                    }
                } finally {
                    lane.running.set(false)
                    if(lane.pending.get()!=null) main.post {
                        // The previous worker may have been retired while this IPC was in flight.
                        healthWorker?.let { if(lane.pending.get()!=null) enqueue(lane,it) }
                    }
                }
            }
            true
        } catch(_:RuntimeException) { lane.running.set(false);lane.pending.set(null);false }
    }

    override fun hide(kind:Int) {
        check(Looper.myLooper()==Looper.getMainLooper())
        invalidateMotion(kind)
        if(tokens.containsKey(kind)) cleanup(kind)
    }

    /** Retain uncertain ownership and retry rather than assuming a failed Binder reply closed it. */
    private fun cleanup(kind:Int):Boolean {
        val token=tokens[kind] ?: return true
        val current=backend
        if(current==null || !current.asBinder().isBinderAlive) {
            tokens.remove(kind);pendingRemoval.remove(kind);publishHealth();return true
        }
        val closed=runCatching { current.hideTrustedStatusVisual(kind,token) }.getOrDefault(false)
        if(closed) { tokens.remove(kind);pendingRemoval.remove(kind) }
        else pendingRemoval.add(kind)
        publishHealth()
        return closed
    }

    fun close() {
        check(Looper.myLooper()==Looper.getMainLooper())
        closeRequested=true
        for(kind in tokens.keys.toList()) hide(kind)
        if(tokens.isEmpty()) finishClose()
    }

    private fun finishClose() {
        val recipient=death
        if(recipient!=null) binder?.let { runCatching { it.unlinkToDeath(recipient,0) } }
        binder=null;backend=null;death=null
        healthSnapshot=emptyMap();pendingSnapshot=emptySet()
        healthWorker?.shutdown();healthWorker=null
    }

    /** Retries uncertain cleanup and detects visuals retired by the backend. */
    private fun publishHealth() {
        healthSnapshot=tokens.toMap();pendingSnapshot=pendingRemoval.toSet()
        if(healthSnapshot.isEmpty()) {
            healthWorker?.shutdown();healthWorker=null
            if(closeRequested) finishClose()
            return
        }
        if(healthWorker!=null) return
        val worker=Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task,"UwU-visual-health").apply { isDaemon=true;priority=Thread.MIN_PRIORITY }
        }
        healthWorker=worker
        worker.scheduleWithFixedDelay({
            val current=backend
            val snapshot=healthSnapshot
            val cleaning=pendingSnapshot
            if(current!=null) for((kind,token) in snapshot) {
                if(kind in cleaning) {
                    val removed=runCatching { current.hideTrustedStatusVisual(kind,token) }.getOrDefault(false)
                    if(removed) main.post {
                        if(backend===current && tokens[kind]==token && kind in pendingRemoval) {
                            tokens.remove(kind);pendingRemoval.remove(kind);publishHealth()
                            if(!closeRequested) onLost()
                        }
                    }
                } else {
                    val visible=runCatching { current.isTrustedStatusVisualVisible(kind,token) }.getOrDefault(false)
                    if(!visible) main.post {
                        if(backend===current && tokens[kind]==token && kind !in pendingRemoval) {
                            invalidateMotion(kind);tokens.remove(kind);publishHealth()
                            if(!closeRequested) onLost()
                        }
                    }
                }
            }
        },2,2,TimeUnit.SECONDS)
    }
}
