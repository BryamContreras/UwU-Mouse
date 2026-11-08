package dev.mobimapper.mobimapper.engine

import kotlin.math.abs
import kotlin.math.exp

/**
 * Optional, worker-owned horizontal input damping while the physical firing button is held.
 * Small deltas use a time-based speed estimate. A reversal starts a new estimate, so output
 * can never move against the current input. Suppressed displacement is discarded, never queued.
 * The caller passes original sample times and handles Y unchanged.
 */
class PrecisionAimFilter {
    @Volatile private var enabled=false
    @Volatile private var firing=false
    @Volatile private var activeSamples=0L
    @Volatile private var attenuatedSamples=0L
    @Volatile private var flickSamples=0L
    @Volatile private var invalidSamples=0L
    @Volatile private var timingResets=0L
    @Volatile private var reversals=0L
    @Volatile private var lastGain=1f
    private var haveSample=false
    private var lastSampleTimeNanos=0L
    private var direction=0
    private var smoothedSpeed=0.0
    // Polling intervals often repeat. Reuse the exact exponential, never an approximation.
    private var coefficientIntervalNanos=Long.MIN_VALUE
    private var coefficientSeconds=0.0
    private var coefficientAlpha=0.0

    /** Configure only at an empty session transition; an old held trigger is never restored. */
    fun configure(enabled:Boolean) {
        this.enabled=enabled
        reset()
        activeSamples=0L; attenuatedSamples=0L; flickSamples=0L
        invalidSamples=0L; timingResets=0L; reversals=0L
    }

    fun setFiring(held:Boolean) {
        if(firing!=held) { firing=held; clearMotion() }
    }

    /** Pause, editing and mode changes clear both timing history and physical-trigger ownership. */
    fun reset() {
        firing=false
        clearMotion()
    }

    fun filterX(dx:Float,sampleTimeNanos:Long):Float {
        // Preserve the original Float, including signed zero/NaN payload, on the legacy path.
        if(!enabled || !firing) return dx
        activeSamples++
        if(!dx.isFinite()) { invalidSamples++; clearMotion(); return 0f }
        if(dx==0f) { clearMotion(); return dx }
        if(sampleTimeNanos<0L || (haveSample && sampleTimeNanos<=lastSampleTimeNanos)) {
            timingResets++; clearMotion(); return dx
        }
        val elapsed=if(haveSample) sampleTimeNanos-lastSampleTimeNanos else NOMINAL_SAMPLE_NANOS
        if(haveSample && elapsed>RESET_GAP_NANOS) {
            timingResets++; clearMotion(); return dx
        }
        val magnitude=abs(dx.toDouble())
        if(magnitude>=FLICK_DELTA) {
            flickSamples++; clearMotion(); return dx
        }
        val nextDirection=if(dx>0f) 1 else -1
        if(direction!=nextDirection) {
            if(direction!=0) reversals++
            smoothedSpeed=0.0
            direction=nextDirection
        }
        val dtNanos=elapsed.coerceIn(MIN_SAMPLE_NANOS,MAX_SAMPLE_NANOS)
        if(dtNanos!=coefficientIntervalNanos) {
            coefficientSeconds=dtNanos/1_000_000_000.0
            coefficientAlpha=1.0-exp(-dtNanos/SMOOTHING_NANOS)
            coefficientIntervalNanos=dtNanos
        }
        val dtSeconds=coefficientSeconds
        val rawSpeed=magnitude/dtSeconds
        smoothedSpeed+=(rawSpeed-smoothedSpeed)*coefficientAlpha
        val damped=minOf(magnitude,smoothedSpeed*dtSeconds)
        // Blend smoothly back to exact input before the large-motion pass-through threshold.
        val weight=MAX_DAMPING*((FLICK_DELTA-magnitude)/(FLICK_DELTA-SMALL_DELTA)).coerceIn(0.0,1.0)
        val outputMagnitude=(magnitude+(damped-magnitude)*weight).coerceIn(0.0,magnitude)
        val output=(if(nextDirection>0) outputMagnitude else -outputMagnitude).toFloat()
        lastSampleTimeNanos=sampleTimeNanos; haveSample=true
        lastGain=(outputMagnitude/magnitude).toFloat()
        if(abs(output)<abs(dx)) attenuatedSamples++
        return output
    }

    /** Cold, approximate metadata only; filtering allocates no maps, timers or queued samples. */
    fun snapshot():Map<String,Any> = mapOf(
        "enabled" to enabled,"firing" to firing,"activeSamples" to activeSamples,
        "attenuatedSamples" to attenuatedSamples,"flickSamples" to flickSamples,
        "invalidSamples" to invalidSamples,"timingResets" to timingResets,"reversals" to reversals,
        "lastGain" to lastGain,
    )

    private fun clearMotion() {
        haveSample=false; lastSampleTimeNanos=0L; direction=0; smoothedSpeed=0.0; lastGain=1f
    }

    companion object {
        // Relative input units before profile sensitivity; not a calibrated physical distance.
        private const val SMALL_DELTA=2.0
        private const val FLICK_DELTA=8.0
        private const val MAX_DAMPING=.50 // Slightly stronger damping; no dead zone or stored tail.
        private const val SMOOTHING_NANOS=14_000_000.0
        private const val NOMINAL_SAMPLE_NANOS=8_000_000L
        private const val MIN_SAMPLE_NANOS=500_000L
        private const val MAX_SAMPLE_NANOS=32_000_000L
        private const val RESET_GAP_NANOS=100_000_000L
    }
}
