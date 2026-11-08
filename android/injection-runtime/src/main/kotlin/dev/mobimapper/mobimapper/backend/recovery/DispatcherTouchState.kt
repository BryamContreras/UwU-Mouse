package dev.mobimapper.mobimapper.backend.recovery

import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.io.BufferedReader
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One-shot privileged diagnostic for an ambiguous terminal UP, never an input hot-path query.
 *
 * AOSP Android 14 InputDispatcher::dumpDispatchStateLocked emits the accepted empty line only
 * when mTouchStatesByDisplay is empty. That map can also contain mouse hover without down
 * pointers: TouchState::isDown() checks window.pointerIds, separately from hovering pointers.
 * The parser additionally accepts the complete, bounded hover-only/empty-window section
 * observed on this Samsung; every touching/pilfered bit must be zero and downTime unset.
 * https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android14-release/services/inputflinger/dispatcher/InputDispatcher.cpp
 * https://android.googlesource.com/platform/frameworks/native/+/refs/heads/android14-release/services/inputflinger/dispatcher/TouchState.cpp
 * This text is an implementation detail, not an SDK contract. Unknown OEM/version output fails
 * closed. A dispatcher snapshot does not establish that a receiver processed UP/CANCEL.
 * The dump is parsed in memory, never logged, persisted, returned or exposed through status.
 */
object DispatcherTouchState {
    private const val MAX_BYTES = 2 * 1024 * 1024
    private const val TIMEOUT_MS = 2000L
    private val querying = AtomicBoolean(false)

    fun isDefinitelyUntouched(): Boolean {
        if (!querying.compareAndSet(false, true)) return false
        val pipe = try { ParcelFileDescriptor.createPipe() } catch (_: Exception) {
            querying.set(false)
            return false
        }
        val result = ArrayBlockingQueue<Boolean>(1)
        val requestAccepted = AtomicBoolean(false)
        val remainingThreads = AtomicInteger(2)
        fun finished() {
            if (remainingThreads.decrementAndGet() == 0) querying.set(false)
        }
        val reader = Thread({
            var untouched = false
            try {
                val decoder = Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { source ->
                    BufferedReader(InputStreamReader(LimitedInputStream(source), decoder)).use { text ->
                        untouched = DispatcherTouchStateParser.isDefinitelyUntouched(text.lineSequence())
                    }
                }
            } catch (_: Exception) {
                untouched = false
            } finally {
                result.offer(untouched && requestAccepted.get())
                finished()
            }
        }, "MobiInputStateRead").apply { isDaemon = true }
        val dumper = Thread({
            try {
                val manager = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String::class.java).invoke(null, "input") as? IBinder
                if (manager != null) {
                    manager.dumpAsync(pipe[1].fileDescriptor, emptyArray())
                    requestAccepted.set(true)
                }
            } catch (_: Exception) {
                // Missing DUMP privilege, hidden API differences and Binder failure fail closed.
            } finally {
                runCatching { pipe[1].close() }
                finished()
            }
        }, "MobiInputStateDump").apply { isDaemon = true }
        return try {
            reader.start()
            dumper.start()
            result.poll(TIMEOUT_MS, TimeUnit.MILLISECONDS) == true
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        } finally {
            // Closing both ends stops a blocked read/dump after timeout or an over-limit stream.
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
        }
    }

    private class LimitedInputStream(source: InputStream) : FilterInputStream(source) {
        private var consumed = 0

        override fun read(): Int {
            val value = `in`.read()
            if (value >= 0 && ++consumed > MAX_BYTES) throw IOException("Input state exceeds limit")
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val count = `in`.read(buffer, offset, minOf(length, MAX_BYTES - consumed + 1))
            if (count > 0) {
                consumed += count
                if (consumed > MAX_BYTES) throw IOException("Input state exceeds limit")
            }
            return count
        }
    }
}
