package dev.uwumouse.app.game

import android.os.Handler
import android.os.Looper
import dev.uwumouse.app.backend.BackendRegistry
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Samples the shell backend off the UI and input threads only while the monitor is shown. */
internal class PerformanceMonitorSampler(private val onSample:(Float?)->Unit) {
    private val main=Handler(Looper.getMainLooper())
    // Binder may ignore interruption. Reuse this lane across visibility changes so a
    // blocked call can never leave another executor/thread behind on each restart.
    private val executor=ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable,"UwU-FPS-monitor").apply { isDaemon=true;priority=Thread.MIN_PRIORITY }
    }.apply {
        removeOnCancelPolicy=true
        setKeepAliveTime(10,TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
    }
    private val loop=PerformanceSampleLoop(
        schedule={ delay,task ->
            val future=executor.schedule(Runnable { task() },delay,TimeUnit.MILLISECONDS)
            val cancel:()->Unit={ future.cancel(false) }
            cancel
        },
        post={ main.post(it) },
        remove={ main.removeCallbacks(it) },
        sample={ packageName,display ->
            val result=BackendRegistry.injector?.sampleGameFps(packageName,display)
            if(result?.getBoolean("available")==true) result.getDouble("fps").toFloat() else null
        },
        onSample=onSample,
    )

    fun start(packageName:String,display:Int) {
        check(Looper.myLooper()==Looper.getMainLooper())
        loop.start(packageName,display)
    }

    fun stop() {
        check(Looper.myLooper()==Looper.getMainLooper())
        loop.stop()
    }
}

/** One in-flight sample and one replaceable delivery; scheduling/posting must enqueue callbacks. */
internal class PerformanceSampleLoop(
    private val schedule:(Long,()->Unit)->(()->Unit),
    private val post:(Runnable)->Unit,
    private val remove:(Runnable)->Unit,
    private val sample:(String,Int)->Float?,
    private val onSample:(Float?)->Unit,
) {
    private class Target(val packageName:String,val displayId:Int)
    private class Result(val target:Target,val fps:Float?)
    private val lock=Any()
    private var target:Target?=null
    private var inFlight=false
    private var cancelScheduled:(()->Unit)?=null
    private var pending:Result?=null
    private val deliver=Runnable {
        val result=synchronized(lock) {
            pending.also { pending=null }?.takeIf { it.target===target }
        }
        if(result!=null) onSample(result.fps)
    }

    /** start, stop and result delivery share the UI thread; sampling uses the worker lane. */
    fun start(packageName:String,displayId:Int)=synchronized(lock) {
        if(target?.let { it.packageName==packageName && it.displayId==displayId }==true) return@synchronized
        cancelPending()
        target=Target(packageName,displayId)
        if(!inFlight) scheduleNext(0L)
    }

    fun stop()=synchronized(lock) {
        target=null
        cancelPending()
    }

    private fun cancelPending() {
        cancelScheduled?.invoke();cancelScheduled=null
        pending=null;remove(deliver)
    }

    private fun scheduleNext(delayMillis:Long) {
        val expected=target ?: return
        cancelScheduled=schedule(delayMillis) { takeSample(expected) }
    }

    private fun takeSample(expected:Target) {
        synchronized(lock) {
            if(target!==expected) return
            cancelScheduled=null;inFlight=true
        }
        val fps=runCatching { sample(expected.packageName,expected.displayId) }.getOrNull()
        synchronized(lock) {
            inFlight=false
            if(target===expected) {
                pending=Result(expected,fps)
                remove(deliver);post(deliver)
                scheduleNext(3_000L)
            } else if(target!=null) {
                // A visibility/target change during IPC retains only its latest request.
                scheduleNext(0L)
            }
        }
    }
}
