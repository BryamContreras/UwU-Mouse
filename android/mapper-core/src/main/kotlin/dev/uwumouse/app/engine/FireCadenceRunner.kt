package dev.uwumouse.app.engine

import dev.uwumouse.app.profile.PreparedFireCadence

/**
 * Worker-owned vertical camera drift while the configured physical primary button remains held.
 * Schedulers enqueue callbacks on that worker and return cancellation handles. No callback
 * synthesizes a button edge, changes physical input timestamps, or reads storage/device state.
 */
class FireCadenceRunner(
    private val config:PreparedFireCadence,
    private val clockMillis:()->Long,
    private val schedule:(Long,()->Unit)->(()->Unit),
    private val moveCamera:(Float)->Boolean,
    private val canContinue:()->Boolean={true},
    private val changed:()->Unit={},
) {
    var held=false;private set
    var running=false;private set
    var active=false;private set
    var ticks=0L;private set
    var totalY=0.0;private set
    var skippedElapsedMillis=0L;private set
    var rejected=0L;private set
    var lastError:String?=null;private set
    val pending:Boolean get()=pendingCancel!=null
    private var pendingCancel:(()->Unit)?=null
    private var epoch=0L
    private var eligibleAt=0L
    private var endsAt=Long.MAX_VALUE
    private var lastTickAt=0L

    /** UP always clears the latch, including after a cursor/pause transition rejects new starts. */
    fun input(kind:String,code:Int,down:Boolean,canStart:Boolean=true):Boolean {
        if(kind!=config.triggerKind || code!=config.code) return false
        if(!down) { held=false;stop();changed();return true }
        if(held) return true
        held=true
        if(!canStart || !canContinue() || config.verticalPixelsPerSecond==0f) return true
        val now=clockMillis()
        if(now<0L) { reject("FIRE_CADENCE_CLOCK_INVALID");return true }
        eligibleAt=if(now>Long.MAX_VALUE-config.delayMillis) Long.MAX_VALUE else now+config.delayMillis
        endsAt=if(config.durationMillis==0L || eligibleAt>Long.MAX_VALUE-config.durationMillis) Long.MAX_VALUE
            else eligibleAt+config.durationMillis
        lastTickAt=eligibleAt
        epoch++;running=true;active=false;lastError=null
        val token=epoch
        if(valid(token)) later(config.delayMillis,token)
        changed()
        return true
    }

    /** Retain the physical latch so returning to the game cannot restart an old held press. */
    fun cancel() { stop();changed() }

    private fun stop() {
        epoch++;running=false;active=false
        pendingCancel?.invoke();pendingCancel=null
    }
    private fun valid(token:Long)=token==epoch && running && held

    private fun later(delayMillis:Long,token:Long) {
        val cancel=try { schedule(delayMillis) {
            if(valid(token)) { pendingCancel=null;tick(token) }
        } } catch(_:Exception) { reject("FIRE_CADENCE_SCHEDULER_FAILED");return }
        if(valid(token)) pendingCancel=cancel else cancel()
    }

    private fun tick(token:Long) {
        if(!valid(token)) return
        if(!canContinue()) { stop();changed();return }
        val now=clockMillis()
        if(now<0L || (active && now<lastTickAt)) { reject("FIRE_CADENCE_CLOCK_INVALID");return }
        if(now<eligibleAt) { later(eligibleAt-now,token);changed();return }
        active=true
        val effectiveNow=minOf(now,endsAt)
        val elapsed=effectiveNow-lastTickAt
        lastTickAt=effectiveNow
        if(elapsed>0L) {
            val acceptedElapsed=minOf(elapsed,MAX_ELAPSED_MILLIS)
            skippedElapsedMillis+=elapsed-acceptedElapsed
            val delta=(config.verticalPixelsPerSecond.toDouble()*acceptedElapsed/1000.0).toFloat()
            if(delta!=0f) {
                val accepted=try { moveCamera(delta) } catch(_:Exception) { false }
                if(!accepted) { reject("FIRE_CADENCE_CAMERA_REJECTED");return }
                ticks++;totalY+=delta.toDouble()
            }
        }
        if(now>=endsAt && config.durationMillis!=0L) stop()
        else if(valid(token)) later(if(config.durationMillis==0L) TICK_MILLIS else minOf(TICK_MILLIS,endsAt-now),token)
        changed()
    }

    private fun reject(error:String) {
        rejected++;lastError=error;stop();changed()
    }

    companion object {
        const val TICK_MILLIS=16L
        const val MAX_ELAPSED_MILLIS=32L
    }
}
