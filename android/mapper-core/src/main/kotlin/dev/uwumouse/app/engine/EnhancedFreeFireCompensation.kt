package dev.uwumouse.app.engine

import kotlin.math.abs
import kotlin.math.max

/**
 * Density-aware horizontal displacement, evaluated once per original mouse report.
 * Sensitivity follows the positional gain. Double coordinates and compensated addition
 * retain small fractions without storing clipped overflow or introducing a motion tail.
 */
internal class EnhancedFreeFireCompensation(
    private val anchor:Double,
    private val densityDpi:Double,
    private val coordinateLimit:Double,
    private val sensitivity:Double,
) {
    private var position=anchor
    private var correction=0.0

    init {
        require(anchor.isFinite() && coordinateLimit.isFinite() && coordinateLimit>0.0 && abs(anchor)<=coordinateLimit)
        require(densityDpi.isFinite() && densityDpi>0.0 && sensitivity.isFinite() && sensitivity>0.0)
    }

    fun reset() { position=anchor;correction=0.0 }

    /** The algebraic form avoids cancellation in (position+delta)-position and 0/0. */
    fun displacement(rawDelta:Double):Double {
        require(rawDelta.isFinite())
        if(rawDelta==0.0) return rawDelta
        return rawDelta*gain(anchor,position,rawDelta,densityDpi)*3.0*sensitivity
    }

    fun wouldClip(rawDelta:Double):Boolean {
        if(rawDelta==0.0) return false
        val next=position+(displacement(rawDelta)-correction)
        return next< -coordinateLimit || next>coordinateLimit
    }

    fun advance(rawDelta:Double):Double {
        if(rawDelta==0.0) return position
        val adjusted=displacement(rawDelta)-correction
        val next=position+adjusted
        val bounded=next.coerceIn(-coordinateLimit,coordinateLimit)
        correction=if(next==bounded) (next-position)-adjusted else 0.0
        position=bounded
        return position
    }

    companion object {
        /** Positional gain before the factor3 and user sensitivity; no temporal filtering. */
        internal fun gain(anchor:Double,position:Double,rawDelta:Double,densityDpi:Double):Double {
            require(anchor.isFinite() && position.isFinite() && rawDelta.isFinite() && densityDpi.isFinite() && densityDpi>0.0)
            if(rawDelta==0.0) return 1.0
            val midpoint=(position-anchor)+rawDelta/2.0
            val positionTerm=1.0+abs(((326.0/densityDpi)*midpoint)/300.0)*3.0
            // The span is capped at one after dividing by300. Cancelling the common
            // delta leaves max(|delta|,300)/(280*positionTerm), including fine input.
            return max(abs(rawDelta),300.0)/(280.0*positionTerm)
        }
    }
}
