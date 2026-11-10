package dev.uwumouse.app.session

/**
 * A physical GG-camera sample is admitted against the kernel's CLOCK_MONOTONIC clock.
 * Callers must use System.nanoTime(), never elapsedRealtimeNanos() (which includes sleep).
 * An expired displacement is discarded before it changes camera/filter state; no catch-up tail.
 */
object CameraSampleAgePolicy {
    const val MAX_AGE_NANOS = 100_000_000L
    const val INVALID_AGE = Long.MIN_VALUE

    fun ageNanos(sampleTimeNanos: Long, monotonicNowNanos: Long): Long =
        if (sampleTimeNanos < 0L || sampleTimeNanos > monotonicNowNanos) INVALID_AGE
        else monotonicNowNanos - sampleTimeNanos

    fun admitsAge(ageNanos: Long): Boolean = ageNanos in 0L..MAX_AGE_NANOS
}
