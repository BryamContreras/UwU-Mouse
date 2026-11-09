package dev.uwumouse.capture.nativeinput

import android.content.Context
import android.os.Bundle
import android.os.Process
import dev.uwumouse.capture.api.CaptureDevice
import dev.uwumouse.capture.api.CaptureProvider
import dev.uwumouse.capture.api.CaptureRequest
import dev.uwumouse.capture.api.CaptureState
import dev.uwumouse.capture.api.CaptureStatus
import dev.uwumouse.capture.api.PhysicalInputFrame
import dev.uwumouse.capture.api.PhysicalInputKind
import dev.uwumouse.capture.api.PhysicalMouseBatch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Runs only in the authorized shell process. The caller admits the target context and
 * renews a short lease; the reader never opens an Android window or starts a session.
 */
class ShellEvdevCapture(
    context: Context,
    private val onFrame: (PhysicalInputFrame) -> Unit,
    private val onLost: (String) -> Unit,
    private val onMouseBatch: ((PhysicalMouseBatch) -> Unit)? = null,
) : CaptureProvider {
    override val providerId = "shell-evdev"
    private val libraryFile = File(context.applicationInfo.nativeLibraryDir, "libuwu_capture.so")
    private val lifecycle = Any()
    @Volatile private var handle = 0L
    @Volatile private var selected = emptyList<CaptureDevice>()
    @Volatile private var lossReason: String? = null
    @Volatile private var request: CaptureRequest? = null
    private var loaded = false
    private val deviceIdentities = HashMap<String, String>()
    private val mouseButtons = MouseButtonState()
    @Volatile var availableDevices: List<CaptureDevice> = emptyList()
        private set

    private fun requireShellLibrary() {
        check(Process.myUid() == 2000) { "Native capture requires authorized shell UID 2000" }
        if (!loaded) synchronized(lifecycle) {
            if (!loaded) {
                check(libraryFile.isFile) { "Extracted libuwu_capture.so not found in nativeLibraryDir" }
                System.load(libraryFile.absolutePath)
                loaded = true
            }
        }
    }

    override fun inventory(): List<CaptureDevice> = synchronized(lifecycle) {
        requireShellLibrary()
        decodeDevices(JSONArray(nativeInventory())).also { availableDevices = it }
    }

    override fun start(request: CaptureRequest): CaptureStatus = synchronized(lifecycle) {
        requireShellLibrary()
        stopLocked()
        val devices = CaptureDeviceSelection.select(inventory(), request)
        mouseButtons.reset()
        selected = devices
        lossReason = null
        this.request = request
        try {
            val fingerprints = devices.map { requireNotNull(deviceIdentities[it.path]) }.toTypedArray()
            handle = nativeStart(devices.map { it.path }.toTypedArray(), fingerprints,
                request.leaseDurationMs.toInt(), request.captureMouse, request.captureKeyboard, request.mouseRateHz)
            check(handle != 0L) { "Native capture did not create a session" }
            val native = JSONObject(nativeStatus(handle))
            selected = decodeDevices(native.getJSONArray("devices"))
            status()
        } catch (failure: Throwable) {
            stopLocked()
            lossReason = failure.message ?: "native_start_failed"
            throw failure
        }
    }

    override fun renewLease(): Boolean {
        val current = handle
        return current != 0L && nativeRenew(current)
    }

    override fun configureMouseRate(rateHz: Int): Boolean {
        require(rateHz in 0..1_000)
        val current = handle
        return current != 0L && nativeConfigureMouseRate(current, rateHz)
    }

    override fun stop() = synchronized(lifecycle) { stopLocked() }

    private fun stopLocked() {
        val previous = handle
        handle = 0L
        if (previous != 0L) nativeStop(previous)
        request = null
        selected = emptyList()
    }

    override fun status(): CaptureStatus = readStatus().first

    private fun readStatus(): Pair<CaptureStatus, JSONObject?> {
        val current = handle
        val configured = request
        if (current == 0L) return CaptureStatus(providerId, CaptureState.STOPPED, reason = lossReason) to null
        val snapshot = JSONObject(nativeStatus(current))
        val running = snapshot.optBoolean("running", false)
        return CaptureStatus(
            providerId, if (running) CaptureState.RUNNING else CaptureState.LOST,
            current, snapshot.optLong("leaseExpiresMonotonicNanos"), selected,
            snapshot.optString("reason").takeIf { it.isNotBlank() } ?: lossReason,
            configured?.targetPackage, configured?.displayId ?: -1,
        ) to snapshot
    }

    fun startBundle(request: CaptureRequest): Bundle { start(request); return statusBundle() }

    fun statusBundle(): Bundle {
        val (snapshot, native) = readStatus()
        return Bundle().apply {
            putString("provider", snapshot.providerId)
            putString("state", snapshot.state.name)
            putBoolean("running", snapshot.state == CaptureState.RUNNING)
            putLong("captureId", snapshot.captureId)
            putLong("leaseExpiresMonotonicNanos", snapshot.leaseExpiresMonotonicNanos)
            putString("reason", snapshot.reason)
            putString("targetPackage", snapshot.targetPackage)
            putInt("displayId", snapshot.displayId)
            putStringArray("devicePaths", snapshot.devices.map { it.path }.toTypedArray())
            putStringArray("deviceNames", snapshot.devices.map { it.name }.toTypedArray())
            putIntArray("initialButtons", snapshot.devices.map { it.initialButtons }.toIntArray())
            for (key in listOf("mouseRateHz", "requestedMouseRateHz")) putInt(key, native?.optInt(key) ?: 0)
            for (key in listOf("rawMouseSamples", "rawMotionSamples", "deliveredMotionSamples", "mouseBatches",
                "coalescedMouseSamples", "barrierFlushes", "discardedMotionSamples", "pendingMotionSamples",
                "maxMotionBatchSamples", "rawInputRecords", "nativeReadCalls", "mouseEdgeFrames", "keyFrames")) {
                putLong(key, native?.optLong(key) ?: 0L)
            }
        }
    }

    @Suppress("unused") // JNI, names preserved by the library's consumer rules.
    private fun onNativeFrame(captureId: Long, slot: Int, time: Long, dx: Float, dy: Float, wheel: Float,
                              horizontalWheel: Float, buttons: Int, scanCode: Int, keyState: Int) {
        if (handle != captureId) return
        val device = selected.getOrNull(slot) ?: return
        // Native reports a node's buttons. Merge only mouse samples, so movement from
        // a keyboard's auxiliary mouse cannot fabricate UP for the physical mouse.
        val logicalButtons = if (scanCode < 0) mouseButtons.update(slot, buttons) else buttons
        onFrame(PhysicalInputFrame(
            if (scanCode >= 0) PhysicalInputKind.KEY else PhysicalInputKind.MOUSE,
            time, device.deviceId, device.path, dx, dy, wheel, horizontalWheel, logicalButtons,
            scanCode, keyState == 1 || keyState == 2, keyState == 2,
        ))
    }

    @Suppress("unused") // One delivery retains every original displacement and source timestamp.
    private fun onNativeMotionBatch(captureId: Long, slots: IntArray, times: LongArray,
                                    xs: FloatArray, ys: FloatArray, wheel: Float, horizontalWheel: Float) {
        if (handle != captureId) return
        check(slots.size == times.size && xs.size == times.size && ys.size == times.size)
        val inventory = selected
        val ids = IntArray(slots.size)
        val paths = Array(slots.size) { index ->
            val device = requireNotNull(inventory.getOrNull(slots[index]))
            ids[index] = device.deviceId
            device.path
        }
        val batch = PhysicalMouseBatch(times, ids, paths, xs, ys, wheel, horizontalWheel, mouseButtons.current)
        val receiver = onMouseBatch
        if (receiver != null) receiver(batch)
        else for (index in times.indices) {
            onFrame(PhysicalInputFrame(PhysicalInputKind.MOUSE, times[index], ids[index], paths[index], xs[index], ys[index],
                if (index == times.lastIndex) wheel else 0f, if (index == times.lastIndex) horizontalWheel else 0f,
                batch.buttonState))
        }
    }

    @Suppress("unused")
    private fun onNativeLost(captureId: Long, reason: String) {
        if (handle != captureId) return
        lossReason = reason
        // Lifecycle callers may stop/join the native reader. Never run their loss handling
        // synchronously on that reader thread, and never acquire lifecycle here.
        Thread({ onLost(reason) }, "UwUNativeCaptureLost").apply { isDaemon = true; start() }
    }

    private fun decodeDevices(array: JSONArray): List<CaptureDevice> = (0 until array.length()).map { index ->
        val row = array.getJSONObject(index)
        deviceIdentities[row.getString("path")] = row.getString("identityFingerprint")
        CaptureDevice(
            row.getString("path"), row.getString("name"), row.getInt("deviceId"),
            row.getInt("bus"), row.getInt("vendorId"), row.getInt("productId"),
            row.getBoolean("mouse"), row.getBoolean("keyboard"), row.optInt("initialButtons"),
            row.optJSONArray("keyCodes").ints(), row.optJSONArray("relativeAxes").ints(),
            row.optJSONArray("mouseButtonCodes").ints(), row.optString("physicalId"), row.optString("uniqueId"),
        )
    }

    private fun JSONArray?.ints(): List<Int> = if (this == null) emptyList() else (0 until length()).map { getInt(it) }

    private external fun nativeInventory(): String
    private external fun nativeStart(paths: Array<String>, identities: Array<String>, leaseMillis: Int, captureMouse: Boolean, captureKeyboard: Boolean, mouseRateHz: Int): Long
    private external fun nativeConfigureMouseRate(captureId: Long, rateHz: Int): Boolean
    private external fun nativeRenew(captureId: Long): Boolean
    private external fun nativeStop(captureId: Long)
    private external fun nativeStatus(captureId: Long): String
}
