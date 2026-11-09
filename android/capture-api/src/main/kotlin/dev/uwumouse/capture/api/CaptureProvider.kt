package dev.uwumouse.capture.api

enum class PhysicalInputKind { MOUSE, KEY }

/** Native evdev device IDs are event-node numbers, not Android InputDevice IDs. */
data class PhysicalInputFrame(
    val kind: PhysicalInputKind,
    val eventTimeNanos: Long,
    val deviceId: Int,
    val devicePath: String,
    val relX: Float = 0f,
    val relY: Float = 0f,
    val wheel: Float = 0f,
    val horizontalWheel: Float = 0f,
    val buttonState: Int = 0,
    val keyScanCode: Int = -1,
    val keyDown: Boolean = false,
    val keyRepeat: Boolean = false,
)

/** Raw movement samples retained in source order; one bounded transport delivery. */
data class PhysicalMouseBatch(
    val eventTimesNanos: LongArray,
    val deviceIds: IntArray,
    val devicePaths: Array<String>,
    val relXs: FloatArray,
    val relYs: FloatArray,
    val wheel: Float = 0f,
    val horizontalWheel: Float = 0f,
    val buttonState: Int = 0,
) {
    init {
        val size = eventTimesNanos.size
        require(size in 1..MAX_SAMPLES)
        require(deviceIds.size == size && devicePaths.size == size && relXs.size == size && relYs.size == size)
        require(eventTimesNanos.all { it >= 0L })
        require(relXs.all { it.isFinite() } && relYs.all { it.isFinite() })
        require(wheel.isFinite() && horizontalWheel.isFinite())
        require(buttonState and 31.inv() == 0)
    }

    companion object { const val MAX_SAMPLES = 256 }
}

data class CaptureDevice(
    val path: String,
    val name: String,
    val deviceId: Int,
    val bus: Int,
    val vendorId: Int,
    val productId: Int,
    val mouse: Boolean,
    val keyboard: Boolean,
    val initialButtons: Int = 0,
    val keyCodes: List<Int> = emptyList(),
    val relativeAxes: List<Int> = emptyList(),
    val mouseButtonCodes: List<Int> = emptyList(),
    val physicalId: String = "",
    val uniqueId: String = "",
)

data class CaptureRequest(
    val targetPackage: String,
    val displayId: Int,
    val mousePath: String? = null,
    val keyboardPaths: List<String> = emptyList(),
    val leaseDurationMs: Long = 1_500,
    val captureMouse: Boolean = true,
    val captureKeyboard: Boolean = true,
    /** Zero is unrestricted; this bounds movement transport, never physical key/button edges. */
    val mouseRateHz: Int = 0,
) {
    init {
        require(targetPackage.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")))
        require(displayId >= 0)
        require(leaseDurationMs in 250..2_000)
        require(captureMouse || captureKeyboard)
        require(mouseRateHz in 0..1_000)
        val paths = keyboardPaths + listOfNotNull(mousePath)
        require(paths.size <= 16)
        require(paths.all { it.matches(Regex("/dev/input/event[0-9]{1,4}")) })
        require(keyboardPaths.distinct().size == keyboardPaths.size)
    }
}

enum class CaptureState { STOPPED, RUNNING, LOST }

data class CaptureStatus(
    val providerId: String,
    val state: CaptureState,
    val captureId: Long = 0,
    val leaseExpiresMonotonicNanos: Long = 0,
    val devices: List<CaptureDevice> = emptyList(),
    val reason: String? = null,
    val targetPackage: String? = null,
    val displayId: Int = -1,
)

/** Lifecycle and context admission belong to the application, not to a particular reader. */
interface CaptureProvider {
    val providerId: String
    fun inventory(): List<CaptureDevice>
    fun start(request: CaptureRequest): CaptureStatus
    fun renewLease(): Boolean
    fun configureMouseRate(rateHz: Int): Boolean
    fun stop()
    fun status(): CaptureStatus
}
