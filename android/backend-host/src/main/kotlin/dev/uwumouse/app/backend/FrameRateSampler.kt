package dev.uwumouse.app.backend

import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Bounded, on-demand SurfaceFlinger queries. Never called from the input or UI threads. */
internal class FrameRateSampler {
    private data class Layer(val component:String, val name:String, val foundAtNanos:Long)
    private val busy=AtomicBoolean(false)
    private var cached:Layer?=null
    private var lastAttemptNanos=0L
    private var lastDiscoveryComponent:String?=null
    private var lastDiscoveryNanos=0L

    fun sample(packageName:String, component:String, shortComponent:String):Double? {
        if(!validPackage(packageName) || !busy.compareAndSet(false,true)) return null
        try {
            val now=System.nanoTime()
            if(lastAttemptNanos!=0L && now-lastAttemptNanos<MIN_INTERVAL_NANOS) return null
            lastAttemptNanos=now
            var layer=cached?.takeIf { it.component==component }
            if(layer==null) {
                if(lastDiscoveryComponent==component && now-lastDiscoveryNanos in 0 until LAYER_RETRY_NANOS) return null
                lastDiscoveryComponent=component;lastDiscoveryNanos=now
                val list=command("--list") ?: return null
                val name=FrameStatsParser.chooseLayer(list,component,shortComponent) ?: run { cached=null;return null }
                layer=Layer(component,name,now);cached=layer
            }
            val latency=command("--latency",layer.name)
            val fps=latency?.let { FrameStatsParser.fps(it,System.nanoTime()) }
            // Keep a good layer indefinitely; retry discovery at most every 10 s when
            // a removed surface, device limitation or static content yields no frames.
            if(fps==null && System.nanoTime()-layer.foundAtNanos>=LAYER_RETRY_NANOS) cached=null
            return fps
        } finally { busy.set(false) }
    }

    private fun command(vararg args:String):String? {
        val process=try { ProcessBuilder(listOf("/system/bin/dumpsys","SurfaceFlinger")+args)
            .redirectErrorStream(true).start() } catch (_:Exception) { return null }
        val bytes=ByteArrayOutputStream()
        val buffer=ByteArray(8192)
        val deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(1200)
        try {
            val input=process.inputStream
            while(System.nanoTime()<deadline) {
                val available=input.available()
                if(available>0) {
                    val count=input.read(buffer,0,minOf(buffer.size,available))
                    if(count>0) {
                        if(bytes.size()+count>MAX_BYTES) return null
                        bytes.write(buffer,0,count)
                    }
                } else if(!process.isAlive) {
                    return if(process.exitValue()==0) bytes.toString(Charsets.UTF_8.name()) else null
                } else Thread.sleep(10)
            }
            return null
        } catch (_:Exception) { return null }
        finally {
            if(process.isAlive) process.destroyForcibly()
            runCatching { process.waitFor(200,TimeUnit.MILLISECONDS) }
            runCatching { process.inputStream.close() }
            runCatching { process.outputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    companion object {
        private const val MIN_INTERVAL_NANOS=1_000_000_000L
        private const val LAYER_RETRY_NANOS=10_000_000_000L
        private const val MAX_BYTES=262144
        private val packageNamePattern=Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
        fun validPackage(value:String)=value.length<=255 && packageNamePattern.matches(value)
    }
}

/** Uses actual presented-frame timestamps, never the display refresh period. */
internal object FrameStatsParser {
    fun chooseLayer(output:String,component:String,shortComponent:String):String? {
        val candidates=output.lineSequence().map { it.trim() }.filter {
            it.length<=1024 && it.startsWith("SurfaceView[") && it.contains("(BLAST)") &&
                !it.contains("Background",ignoreCase=true) &&
                (it.startsWith("SurfaceView[$component]") || it.startsWith("SurfaceView[$shortComponent]"))
        }.distinct().take(2).toList()
        return candidates.singleOrNull()
    }

    fun fps(output:String,nowNanos:Long):Double? {
        val frames=output.lineSequence().drop(1).mapNotNull { line ->
            val columns=line.trim().split(Regex("\\s+"))
            if(columns.size<3) null else columns[1].toLongOrNull()
        }.filter { it>0 && it!=Long.MAX_VALUE && it<=nowNanos && nowNanos-it<=2_000_000_000L }
            .distinct().sorted().toList()
        if(frames.size<3) return null
        val duration=frames.last()-frames.first()
        if(duration<200_000_000L) return null
        val fps=(frames.size-1)*1_000_000_000.0/duration
        return fps.takeIf { it in 1.0..360.0 }
    }
}
