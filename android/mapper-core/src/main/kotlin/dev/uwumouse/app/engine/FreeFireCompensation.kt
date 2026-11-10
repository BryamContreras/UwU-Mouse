package dev.uwumouse.app.engine

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Integrates the standard position-dependent gain without a sample-sized Euler step.
 *
 * For z=(touch-anchor)/reference, g(z)=.04+1.96/(1+5.6*z*z). The input coordinate
 * F(z)=integral(1/g) is additive, so opposing displacements and differently split
 * reports reach the same touch position. Only the final Android payload is Float.
 */
internal class FreeFireCompensation(
    private val anchor:Double,
    private val reference:Double,
    private val coordinateLimit:Double,
) {
    private val minimumInput=integral((-coordinateLimit-anchor)/reference)*reference
    private val maximumInput=integral((coordinateLimit-anchor)/reference)*reference
    private var input=0.0
    private var correction=0.0
    private var normalizedPosition=0.0

    fun reset() { input=0.0;correction=0.0;normalizedPosition=0.0 }

    /** Inspect before changing the contact; discarded overflow is never stored for replay. */
    fun wouldClip(delta:Double):Boolean {
        val next=input+(delta-correction)
        return next<minimumInput || next>maximumInput
    }

    fun advance(delta:Double):Double {
        if(delta==0.0) return (anchor+normalizedPosition*reference).coerceIn(-coordinateLimit,coordinateLimit)
        // Compensated accumulation avoids repeatedly rounding a fine report into a large sum.
        val adjusted=delta-correction
        val next=input+adjusted
        val bounded=next.coerceIn(minimumInput,maximumInput)
        correction=if(next==bounded) (next-input)-adjusted else 0.0
        input=bounded
        normalizedPosition=inverse(input/reference,normalizedPosition)
        return (anchor+normalizedPosition*reference).coerceIn(-coordinateLimit,coordinateLimit)
    }

    private fun inverse(signedInput:Double,previous:Double):Double {
        if(signedInput==0.0) return 0.0
        val sign=if(signedInput<0.0) -1.0 else 1.0
        val target=abs(signedInput)
        // .5 <= F'(z) <= 25; atan is bounded. This bracket is at most 4.6 units wide,
        // even for an extreme finite report, and Newton cannot leave it.
        var low=target/25.0
        var high=min(target*2.0,(target+MAXIMUM_ATAN_TERM)/25.0)
        val oldPosition=previous*sign
        var position=if(oldPosition>=low && oldPosition<=high) oldPosition else high
        val tolerance=1e-10/reference+target*2e-15
        repeat(12) {
            val residual=integral(position)-target
            if(abs(residual)<=tolerance) return position*sign
            if(residual>0.0) high=position else low=position
            val square=position*position
            val derivative=(1.0+5.6*square)/(2.0+.224*square)
            val candidate=position-residual/derivative
            val next=if(candidate>low && candidate<high) candidate else low+(high-low)*.5
            if(next==position) return position*sign
            position=next
        }
        // A fixed fallback bound covers every valid coordinate/reference, without an
        // unbounded convergence loop or an allocation on the motion path.
        repeat(40) {
            val residual=integral(position)-target
            if(abs(residual)<=tolerance) return position*sign
            if(residual>0.0) high=position else low=position
            val next=low+(high-low)*.5
            if(next==position) return position*sign
            position=next
        }
        return position*sign
    }

    companion object {
        private val ROOT=sqrt(.112)
        private val MAXIMUM_ATAN_TERM=24.5/ROOT*(Math.PI*.5)

        private fun integral(position:Double):Double {
            val scaled=position*ROOT
            // Evaluate t-atan(t) as a series near zero instead of subtracting almost
            // equal numbers. This retains fine reports around the camera's anchor.
            val remainder=if(abs(scaled)<.125) {
                val square=scaled*scaled
                scaled*square*(1.0/3.0-square*(1.0/5.0-square*(1.0/7.0-
                    square*(1.0/9.0-square*(1.0/11.0-square*(1.0/13.0-square/15.0))))))
            } else scaled-atan(scaled)
            return (.5*scaled+24.5*remainder)/ROOT
        }
    }
}
