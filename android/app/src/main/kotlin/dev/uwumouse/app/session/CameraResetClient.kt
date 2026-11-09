package dev.uwumouse.app.session

import android.os.ParcelFileDescriptor
import dev.uwumouse.app.backend.IInjector
import dev.uwumouse.app.engine.CameraResetResult
import dev.uwumouse.app.engine.TouchFrame
import java.io.InputStream

/** One synchronous reset RPC; the progress pipe survives a lost Binder transaction reply. */
class CameraResetClient(private val backend: IInjector, private val sid: Long) {
    private var accepted = emptyList<TouchFrame>()
    private var attempted: TouchFrame? = null

    /** Detached acknowledged frames, suitable for applying ordinary per-frame statistics. */
    val acceptedFrames: List<TouchFrame>
        @Synchronized get() = accepted.map { it.detached() }

    /** Null also when progress is unknown: callers must not manufacture a recovery frame. */
    val lastAttemptedFrame: TouchFrame?
        @Synchronized get() = attempted?.detached()

    @Synchronized fun inject(frames: List<TouchFrame>): CameraResetResult {
        accepted = emptyList(); attempted = null
        if (frames.size != CameraResetProgress.MAX_STAGES) return unknown()
        val detached = frames.map { it.detached() }
        val counts = IntArray(detached.size) { detached[it].ids.size }
        if (counts.any { it !in 1..10 } || detached.any {
                it.xs.size != it.ids.size || it.ys.size != it.ids.size
            }) return unknown()
        val actions = IntArray(detached.size) { detached[it].action }
        val downTimes = LongArray(detached.size) { detached[it].downTime }
        val eventTimes = LongArray(detached.size) { detached[it].eventTime }
        val total = counts.sum()
        val ids = IntArray(total)
        val xs = FloatArray(total)
        val ys = FloatArray(total)
        var offset = 0
        for (frame in detached) {
            frame.ids.copyInto(ids, offset)
            frame.xs.copyInto(xs, offset)
            frame.ys.copyInto(ys, offset)
            offset += frame.ids.size
        }
        val descriptors = try { ParcelFileDescriptor.createPipe() } catch (_: Exception) {
            // No privileged call took place, so the original stream is still the only candidate.
            return CameraResetResult(0, 0)
        }
        val result = try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptors[0]).use { input ->
                var reply: Int? = null
                try {
                    reply = backend.injectCameraReset(sid, actions, downTimes, eventTimes,
                        counts, ids, xs, ys, descriptors[1])
                } catch (_: Exception) {
                    // The descriptor's records, not the Binder exception, identify the last attempt.
                } finally {
                    // Our duplicate would otherwise keep the read side alive after backend death.
                    try { descriptors[1].close() } catch (_: Exception) { }
                }
                CameraResetProgress.resolve(input.readBoundedProgress(), reply)
            }
        } catch (_: Exception) {
            unknown()
        } finally {
            // Also cover failure while constructing the reader, before entering its use block.
            try { descriptors[0].close() } catch (_: Exception) { }
            try { descriptors[1].close() } catch (_: Exception) { }
        }
        if (result.progressKnown) {
            accepted = detached.take(result.acceptedCount)
            attempted = detached.getOrNull(result.attemptedCount - 1)
        }
        return result
    }

    private fun InputStream.readBoundedProgress(): ByteArray {
        // One extra byte detects an oversized message without allocating or draining unbounded input.
        val bytes = ByteArray(CameraResetProgress.MAX_BYTES + 1)
        var count = 0
        while (count < bytes.size) {
            val read = read(bytes, count, bytes.size - count)
            if (read < 0) break
            if (read == 0) continue
            count += read
        }
        return bytes.copyOf(count)
    }

    private fun TouchFrame.detached() = copy(ids = ids.copyOf(), xs = xs.copyOf(), ys = ys.copyOf())
    private fun unknown() = CameraResetResult(0, 0, progressKnown = false)
}
