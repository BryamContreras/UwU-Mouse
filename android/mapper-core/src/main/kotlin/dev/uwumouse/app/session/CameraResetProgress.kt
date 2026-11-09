package dev.uwumouse.app.session

import dev.uwumouse.app.engine.CameraResetResult
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Resolves the bounded progress pipe of one camera reset. Each big-endian record is written
 * atomically after journal preparation and before the corresponding worker call. Observing
 * stage N therefore proves ACKs only through N - 1; a normal Binder reply can confirm N too.
 * EOF must already have been observed by the caller. A partial or inconsistent record never
 * supplies a guessed cancellation candidate.
 */
object CameraResetProgress {
    const val MAGIC = 0x55435231
    const val RECORD_BYTES = 12
    const val MAX_STAGES = 3
    const val MAX_BYTES = RECORD_BYTES * MAX_STAGES

    /** A null [binderResult] means that the transaction reply was lost. */
    fun resolve(records: ByteArray, binderResult: Int?): CameraResetResult {
        if (records.size > MAX_BYTES || records.size % RECORD_BYTES != 0) return unknown()
        val attempted = records.size / RECORD_BYTES
        val input = ByteBuffer.wrap(records).order(ByteOrder.BIG_ENDIAN)
        for (stage in 1..attempted) {
            if (input.int != MAGIC || input.int != stage || input.int != stage - 1) return unknown()
        }
        if (binderResult == null) {
            return CameraResetResult(maxOf(0, attempted - 1), attempted)
        }
        return when {
            binderResult in 0..MAX_STAGES && binderResult == attempted ->
                CameraResetResult(binderResult, attempted)
            binderResult in -MAX_STAGES..-1 && -binderResult == attempted ->
                CameraResetResult(attempted - 1, attempted)
            else -> unknown()
        }
    }

    private fun unknown() = CameraResetResult(0, 0, progressKnown = false)
}
