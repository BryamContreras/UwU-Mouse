package dev.mobimapper.windowcontext

import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Privileged metadata cache. A shell process owns the WMS dump capability; the app only
 * receives detached metadata. snapshot() performs no Binder calls, parsing, disk or device IO.
 */
class ShellWindowContextProvider(
    private val refreshMs: Long = 250,
    private val maxAgeMs: Long = 750,
    private val onInvalid: (() -> Unit)? = null,
) : WindowContextProvider {
    private data class Cache(val capturedAt: Long, val parsed: ParsedWindowSnapshot)
    private val closed = AtomicBoolean(false)
    private val lastDemandNanos = AtomicLong(0L)
    @Volatile private var linkageFailed = false
    private val dumper = BoundedWindowDumper()
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "UwUWindowContext").apply { isDaemon = true }
    }
    @Volatile private var cached = Cache(0, ParsedWindowSnapshot(false, reason = "not_ready"))

    init {
        check(Process.myUid() == 2000) { "Window metadata provider requires shell UID 2000" }
        require(refreshMs in 100..1000 && maxAgeMs in refreshMs..2000) { "Invalid window cache interval" }
        worker.scheduleWithFixedDelay(::refresh, 0, refreshMs, TimeUnit.MILLISECONDS)
    }

    private fun refresh() {
        if (closed.get() || linkageFailed) return
        val demanded = lastDemandNanos.get()
        val demandAge = System.nanoTime() - demanded
        if (demanded == 0L || demandAge !in 0..DEMAND_LEASE_NANOS) {
            // An idle guardian must not keep dumping WMS after every consumer has left.
            val previous = cached
            if (previous.parsed.reason != "idle_no_demand") {
                cached = Cache(SystemClock.uptimeMillis(), ParsedWindowSnapshot(false, reason = "idle_no_demand"))
                if (previous.parsed.valid) runCatching { onInvalid?.invoke() }
            }
            return
        }
        val started = SystemClock.uptimeMillis()
        val previous = cached.parsed.valid
        val parsed = try {
            val deadline = System.nanoTime() + DUMP_TIMEOUT_MS * 1_000_000
            val windows = dumper.dump(arrayOf("windows"), deadline, MAX_BYTES)
            val displays = if (windows != null) dumper.dump(arrayOf("displays"), deadline, MAX_BYTES - windows.bytes) else null
            when {
                windows == null -> ParsedWindowSnapshot(false, reason = "dump_windows_unavailable")
                displays == null -> ParsedWindowSnapshot(false, reason = "dump_displays_unavailable")
                else -> WindowDumpParser.parse(windows.text, displays.text)
            }
        } catch (_: LinkageError) {
            // Class initialization errors otherwise terminate a ScheduledFuture silently.
            // A broken class cannot be initialized again in this process: stop further IO.
            linkageFailed = true
            ParsedWindowSnapshot(false, reason = "window_runtime_linkage_error")
        } catch (_: Exception) {
            // Exception messages can contain private window metadata. Retain only fixed codes.
            ParsedWindowSnapshot(false, reason = "dump_failed")
        }
        if (closed.get()) return
        cached = Cache(started, parsed)
        if (previous && !parsed.valid) runCatching { onInvalid?.invoke() }
    }

    override fun snapshot(): Bundle {
        // Renew demand only; waking the polling worker never performs work on this caller.
        if (!closed.get()) lastDemandNanos.set(System.nanoTime())
        val copy = cached
        val age = SystemClock.uptimeMillis() - copy.capturedAt
        val valid = !closed.get() && copy.parsed.valid && age in 0..maxAgeMs
        return Bundle().apply {
            putBoolean("valid", valid)
            putLong("capturedUptimeMs", copy.capturedAt)
            putLong("ageMs", age)
            putLong("maximumAgeMs", maxAgeMs)
            putLong("demandLeaseMs", DEMAND_LEASE_NANOS / 1_000_000)
            putString("source", "shell_wms_metadata")
            putString("error", when {
                closed.get() -> "closed"
                !copy.parsed.valid -> copy.parsed.reason
                !valid -> "stale_snapshot"
                else -> ""
            })
            putString("focusedPackage", if (valid) copy.parsed.focusedPackage else null)
            putInt("focusedDisplayId", if (valid) copy.parsed.focusedDisplayId else -1)
            val windows = if (valid) copy.parsed.windows.mapTo(ArrayList()) { entry ->
                Bundle().apply {
                    putInt("id", entry.id); putInt("type", entry.type); putInt("layoutType", entry.layoutType)
                    putString("packageName", entry.packageName); putInt("ownerUid", entry.ownerUid)
                    putInt("displayId", entry.displayId); putInt("layer", entry.layer)
                    putInt("left", entry.left); putInt("top", entry.top)
                    putInt("right", entry.right); putInt("bottom", entry.bottom)
                    // WMS frame bounds do not establish view-content bounds. Do not invent them.
                    putInt("contentLeft", entry.left); putInt("contentTop", entry.top)
                    putInt("contentRight", entry.right); putInt("contentBottom", entry.bottom)
                    putBoolean("contentBoundsVerified", false)
                    putBoolean("focused", entry.focused); putBoolean("visible", true)
                }
            } else arrayListOf()
            putParcelableArrayList("windows", windows)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cached = Cache(SystemClock.uptimeMillis(), ParsedWindowSnapshot(false, reason = "closed"))
        worker.shutdownNow()
        dumper.close()
    }

    companion object {
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val DUMP_TIMEOUT_MS = 500L
        private const val DEMAND_LEASE_NANOS = 3_000_000_000L
    }
}

/** Two reusable daemon threads, at most one bounded dump operation; no unbounded timeout retries. */
private class BoundedWindowDumper : AutoCloseable {
    data class Text(val text: String, val bytes: Int)
    private class Operation(val pipe: Array<ParcelFileDescriptor>) {
        val accepted = AtomicBoolean(false)
        val result = ArrayBlockingQueue<Text>(1)
        fun close() { pipe.forEach { runCatching { it.close() } } }
    }

    private val pool = Executors.newFixedThreadPool(2) { task ->
        Thread(task, "UwUWindowDumpIO").apply { isDaemon = true }
    }
    private val active = AtomicReference<Operation?>()
    private val closed = AtomicBoolean(false)
    @Volatile private var manager: IBinder? = null

    fun dump(arguments: Array<String>, deadlineNanos: Long, maxBytes: Int): Text? {
        if (closed.get() || maxBytes <= 0 || System.nanoTime() >= deadlineNanos || active.get() != null) return null
        val binder = manager ?: (Class.forName("android.os.ServiceManager")
            .getMethod("checkService", String::class.java).invoke(null, "window") as? IBinder)
            ?.also { manager = it } ?: return null
        val operation = Operation(ParcelFileDescriptor.createPipe())
        if (!active.compareAndSet(null, operation)) { operation.close(); return null }
        val completion = DumpCompletion(2) { active.compareAndSet(operation, null) }
        try {
            pool.execute {
                try {
                    val bytes = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
                    ParcelFileDescriptor.AutoCloseInputStream(operation.pipe[0]).use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes - bytes.size() + 1))
                            if (count < 0) break
                            if (count == 0) continue
                            if (count > maxBytes - bytes.size()) return@execute
                            bytes.write(buffer, 0, count)
                        }
                    }
                    val decoder = Charsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    operation.result.offer(Text(decoder.decode(ByteBuffer.wrap(bytes.toByteArray())).toString(), bytes.size()))
                } catch (_: Exception) {
                    // Fixed unavailable status is returned by the cache, never arbitrary dump text.
                } finally { completion.taskFinished() }
            }
            pool.execute {
                try {
                    binder.dumpAsync(operation.pipe[1].fileDescriptor, arguments)
                    operation.accepted.set(true)
                } catch (_: Exception) {
                    manager = null
                } finally {
                    runCatching { operation.pipe[1].close() }
                    completion.taskFinished()
                }
            }
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) return null
            val result = operation.result.poll(remaining, TimeUnit.NANOSECONDS) ?: return null
            // Completion publishes BOTH tasks' results and clears active before a second
            // dump may begin. A zero task counter alone does not publish that cleanup.
            return result.takeIf { completion.awaitUntil(deadlineNanos) && operation.accepted.get() }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally { operation.close() }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.get()?.close()
        pool.shutdownNow()
    }
}
