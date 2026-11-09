package dev.uwumouse.app.backend

import android.content.Context
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import dev.uwumouse.capture.api.CaptureDevice
import dev.uwumouse.capture.api.CaptureRequest
import dev.uwumouse.capture.api.CaptureState
import dev.uwumouse.capture.api.PhysicalInputFrame
import dev.uwumouse.capture.api.PhysicalInputKind
import dev.uwumouse.capture.api.PhysicalMouseBatch
import dev.uwumouse.capture.nativeinput.ShellEvdevCapture
import java.util.concurrent.atomic.AtomicLong

/**
 * Shell-side lifecycle boundary between the native reader and the authorized app.
 * The enclosing injector authenticates callers and supplies target admission.
 */
class NativeCaptureBroker(
    context: Context,
    applicationId: String,
    private val admit: (String, Int) -> Boolean,
    private val onLost: (Long, String) -> Unit,
) {
    private val lifecycle = Any()
    private val generations = AtomicLong(SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L))
    private val pendingConsumerLoss = AtomicLong(0L)
    private val applicationContext = context.createPackageContext(applicationId, Context.CONTEXT_IGNORE_SECURITY)
    private fun newReader(captureId: Long, batchTransport: Boolean = true) = ShellEvdevCapture(
        applicationContext,
        { frame -> dispatch(captureId, frame) },
        { reason -> invalidate(captureId, reason, true) },
        if(batchTransport) ({ batch -> dispatchBatch(captureId, batch) }) else null,
    )
    private var reader = newReader(0L)
    private var availableDevices: List<CaptureDevice> = reader.inventory()
    private var availableSampledUptimeMs = SystemClock.uptimeMillis()
    @Volatile private var captureActive = false
    @Volatile private var leaseDeadlineNanos = 0L
    // Injection admission must not depend on when a JVM loss callback gets scheduled.
    val active: Boolean get() = captureActive && System.nanoTime() < leaseDeadlineNanos
    @Volatile var activeId: Long = 0L
        private set
    @Volatile var targetPackage: String? = null
        private set
    @Volatile var displayId: Int = -1
        private set
    @Volatile private var client: IPhysicalInputCallback? = null
    private var clientBinder: IBinder? = null
    private var clientDeath: IBinder.DeathRecipient? = null
    @Volatile private var closed = false
    @Volatile private var lastReason = "not_started"
    @Volatile private var mouseEnabled = false
    private var keyboardEnabled = false
    val mouseActive: Boolean get() = active && mouseEnabled

    fun start(callback: IPhysicalInputCallback, targetPackage: String, displayId: Int,
              mouse: Boolean, keyboard: Boolean, mouseRateHz: Int = 0, batchTransport: Boolean = true): Bundle = synchronized(lifecycle) {
        check(!closed) { "Native capture broker is closed" }
        require(mouse || keyboard) { "Select at least one physical input source" }
        check(admit(targetPackage, displayId)) { "Native capture target is not admitted" }
        stopLocked("replaced")
        pendingConsumerLoss.set(0L)
        val captureId = generations.incrementAndGet()
        check(captureId > 0L) { "Capture generation exhausted" }
        val binder = callback.asBinder()
        check(binder.isBinderAlive) { "Capture consumer is unavailable" }
        val death = IBinder.DeathRecipient { invalidate(captureId, "capture_consumer_died", false) }
        // Reserve the generation before linking death, so an early death cannot escape cleanup.
        activeId = captureId
        this.targetPackage = targetPackage
        this.displayId = displayId
        client = callback
        clientBinder = binder
        clientDeath = death
        mouseEnabled = mouse
        keyboardEnabled = keyboard
        try {
            binder.linkToDeath(death, 0)
            // Bind callback ownership to this external generation. A delayed loss callback
            // from a previous reader cannot invalidate a newly started capture.
            reader = newReader(captureId,batchTransport)
            val started = try {
                reader.start(CaptureRequest(
                    targetPackage = targetPackage,
                    displayId = displayId,
                    leaseDurationMs = 1_500,
                    captureMouse = mouse,
                    captureKeyboard = keyboard,
                    mouseRateHz = mouseRateHz,
                ))
            } finally {
                // Publish this acquisition's inventory, including failed selection.
                // Status remains a cached read and never scans evdev on the event path.
                availableDevices = reader.availableDevices
                availableSampledUptimeMs = SystemClock.uptimeMillis()
            }
            check(started.state == CaptureState.RUNNING && binder.isBinderAlive) { "Native reader or consumer stopped during acquisition" }
            check(admit(targetPackage, displayId)) { "Native capture target changed during acquisition" }
            leaseDeadlineNanos = started.leaseExpiresMonotonicNanos
            captureActive = true
            lastReason = "running"
            status()
        } catch (failure: Throwable) {
            stopLocked(failure.message ?: "native_capture_start_failed")
            throw failure
        }
    }

    fun renew(captureId: Long): Boolean {
        val failure: String?
        synchronized(lifecycle) {
            if (!active || captureId != activeId || closed) return false
            val target = targetPackage ?: return false
            failure = when {
                clientBinder?.isBinderAlive != true -> "capture_consumer_died"
                !runCatching { admit(target, displayId) }.getOrDefault(false) -> "capture_target_context_lost"
                else -> {
                    val beforeRenew = System.nanoTime()
                    if (runCatching { reader.renewLease() }.getOrDefault(false)) {
                        // Native renewal computes its deadline later inside JNI, so this
                        // bound is conservative even if the Binder/JNI call takes time.
                        leaseDeadlineNanos = beforeRenew + 1_500_000_000L
                        null
                    } else "capture_lease_expired"
                }
            }
            if (failure == null) return true
        }
        invalidate(captureId, failure ?: "capture_renew_failed", true)
        return false
    }

    fun stop(captureId: Long) = synchronized(lifecycle) {
        // A callback from a replaced session must never stop its replacement.
        if (captureId != activeId || captureId <= 0L) return@synchronized
        stopLocked("stopped")
    }

    fun configureMouseRate(captureId: Long, mouseRateHz: Int): Boolean = synchronized(lifecycle) {
        if(!active || captureId!=activeId || captureId<=0L || closed) return@synchronized false
        require(mouseRateHz in 0..1000)
        reader.configureMouseRate(mouseRateHz)
    }

    fun close() = synchronized(lifecycle) {
        closed = true
        stopLocked("closed")
    }

    private fun stopLocked(reason: String) {
        // Clear externally visible ownership before waiting for native cleanup.
        captureActive = false
        leaseDeadlineNanos = 0L
        activeId = 0L
        val previousBinder = clientBinder
        val previousDeath = clientDeath
        client = null
        clientBinder = null
        clientDeath = null
        targetPackage = null
        displayId = -1
        mouseEnabled = false
        keyboardEnabled = false
        lastReason = reason
        if (previousBinder != null && previousDeath != null) {
            runCatching { previousBinder.unlinkToDeath(previousDeath, 0) }
        }
        reader.stop()
    }

    private fun dispatch(captureId: Long, frame: PhysicalInputFrame) {
        if (!active) return
        val consumer = client ?: return
        if (captureId <= 0L || !active || captureId != activeId) return
        try {
            consumer.onFrame(
                captureId, if (frame.kind == PhysicalInputKind.MOUSE) 0 else 1,
                frame.eventTimeNanos, frame.deviceId, frame.devicePath,
                frame.relX, frame.relY, frame.wheel, frame.horizontalWheel,
                frame.buttonState, frame.keyScanCode, frame.keyDown, frame.keyRepeat,
            )
        } catch (_: Exception) {
            // Avoid running a supervisor's recovery while holding the reader callback stack.
            if (!pendingConsumerLoss.compareAndSet(0L, captureId)) return
            Thread({
                try { invalidate(captureId, "capture_consumer_callback_failed", false) }
                finally { pendingConsumerLoss.compareAndSet(captureId, 0L) }
            }, "UwUCaptureConsumerLost")
                .apply { isDaemon = true; start() }
        }
    }

    private fun dispatchBatch(captureId: Long, batch: PhysicalMouseBatch) {
        if(!active || captureId<=0L || captureId!=activeId) return
        val consumer=client ?: return
        try {
            consumer.onMotionBatch(captureId,batch.eventTimesNanos,batch.deviceIds,batch.devicePaths,
                batch.relXs,batch.relYs,batch.wheel,batch.horizontalWheel,batch.buttonState)
        } catch(_:Exception) {
            if(!pendingConsumerLoss.compareAndSet(0L,captureId)) return
            Thread({
                try { invalidate(captureId,"capture_consumer_callback_failed",false) }
                finally { pendingConsumerLoss.compareAndSet(captureId,0L) }
            },"UwUCaptureConsumerLost").apply { isDaemon=true;start() }
        }
    }

    private fun invalidate(captureId: Long, reason: String, notifyClient: Boolean) {
        val previousClient: IPhysicalInputCallback?
        synchronized(lifecycle) {
            if (captureId != activeId || captureId <= 0L) return
            previousClient = client
            stopLocked(reason)
        }
        // Ownership and exclusive grabs are gone before session/journal recovery begins.
        if (notifyClient) runCatching { previousClient?.onCaptureLost(captureId, reason) }
        onLost(captureId, reason)
    }

    fun status(): Bundle = synchronized(lifecycle) {
        val native = reader.statusBundle()
        Bundle(native).apply {
            putBoolean("captureSupported", true)
            putBoolean("active", active)
            putBoolean("running", active && native.getBoolean("running"))
            putLong("nativeCaptureId", native.getLong("captureId"))
            putLong("captureId", activeId)
            putString("targetPackage", targetPackage)
            putInt("displayId", displayId)
            putString("reason", if (active) native.getString("reason") ?: lastReason else lastReason)
            putBoolean("captureMouse", mouseEnabled)
            putBoolean("captureKeyboard", keyboardEnabled)
            putBoolean("consumerAlive", clientBinder?.isBinderAlive == true)
            putLong("availableSampledUptimeMs", availableSampledUptimeMs)
            putStringArray("availablePaths", availableDevices.map { it.path }.toTypedArray())
            putStringArray("availableNames", availableDevices.map { it.name }.toTypedArray())
            putIntArray("availableBus", availableDevices.map { it.bus }.toIntArray())
            putIntArray("availableVendorIds", availableDevices.map { it.vendorId }.toIntArray())
            putIntArray("availableProductIds", availableDevices.map { it.productId }.toIntArray())
            putBooleanArray("availableMouse", availableDevices.map { it.mouse }.toBooleanArray())
            putBooleanArray("availableKeyboard", availableDevices.map { it.keyboard }.toBooleanArray())
        }
    }
}
