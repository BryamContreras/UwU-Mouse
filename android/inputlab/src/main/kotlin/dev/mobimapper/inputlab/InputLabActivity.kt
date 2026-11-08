package dev.mobimapper.inputlab

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/** Independent, receive-only observer. No IPC, network, accessibility or injection API. */
class InputLabActivity : Activity() {
    private lateinit var observer: ObserverView
    private val observationId = UUID.randomUUID().toString()
    private val activityId = Integer.toHexString(System.identityHashCode(this))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep an unattended observation visible without changing the device's timeout setting.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        observer = ObserverView(
            this, observationId, activityId,
            intent.getBooleanExtra("latencyProbe", false),
            intent.getIntExtra("latencyProbeSeconds", 30).coerceIn(1, 60),
        )
        setContentView(observer)
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        observer.recordLifecycle("created")
        Log.i(TAG, "observer_ready observation=$observationId activity=$activityId " +
            "pid=${android.os.Process.myPid()} uid=${android.os.Process.myUid()} package=$packageName")
    }

    override fun onStart() {
        super.onStart()
        if (::observer.isInitialized) observer.recordLifecycle("started")
    }

    override fun onResume() {
        super.onResume()
        if (::observer.isInitialized) observer.resumeObservation()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (::observer.isInitialized) observer.observeKey(event)
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (::observer.isInitialized &&
            (event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE))
        ) observer.observeMouse(event)
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onPause() {
        if (::observer.isInitialized) observer.pauseObservation()
        super.onPause()
    }

    override fun onStop() {
        if (::observer.isInitialized) observer.recordLifecycle("stopped")
        super.onStop()
    }

    override fun onDestroy() {
        if (::observer.isInitialized) observer.closeObservation()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (::observer.isInitialized) observer.recordWindowFocus(hasFocus)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::observer.isInitialized) observer.recordLifecycle("configuration_changed: $newConfig")
    }

    companion object {
        const val TAG = "MobiInputLab"
    }
}

private data class Contact(val id: Int, val x: Float, val y: Float, val pressure: Float)

private data class Sample(
    val sequence: Long,
    val action: Int,
    val actionIndex: Int,
    val downTime: Long,
    val eventTime: Long,
    val receiptTime: Long,
    val source: Int,
    val deviceId: Int,
    val contacts: List<Contact>,
) {
    fun json(): JSONObject = JSONObject().apply {
        put("sequence", sequence)
        put("action", action)
        put("actionMasked", action and MotionEvent.ACTION_MASK)
        put("actionIndex", actionIndex)
        put("pointerCount", contacts.size)
        put("downTimeUptimeMs", downTime)
        put("eventTimeUptimeMs", eventTime)
        put("receiptUptimeMs", receiptTime)
        put("eventToReceiptMs", receiptTime - eventTime)
        put("source", source)
        put("deviceId", deviceId)
        put("contacts", JSONArray().apply {
            contacts.forEach { contact ->
                put(JSONObject().apply {
                    put("id", contact.id)
                    put("x", contact.x)
                    put("y", contact.y)
                    put("pressure", contact.pressure)
                })
            }
        })
    }

    fun logLine(): String =
        "seq=$sequence action=${MotionEvent.actionToString(action)} index=$actionIndex " +
            "count=${contacts.size} ids=${contacts.joinToString(",") { it.id.toString() }} " +
            "xy=${contacts.joinToString(";") { "${it.x},${it.y}" }} " +
            "downTime=$downTime eventTime=$eventTime receiptUptime=$receiptTime " +
            "eventToReceiptMs=${receiptTime - eventTime} device=$deviceId source=0x${source.toString(16)}"
}

private data class LifecycleEntry(val state: String, val uptime: Long, val resumed: Boolean, val focused: Boolean) {
    fun json(): JSONObject = JSONObject().apply {
        put("state", state)
        put("uptimeMs", uptime)
        put("resumed", resumed)
        put("windowFocused", focused)
    }
}

/** Main-thread-only, bounded observation. Drawing timestamps describe CPU recording, not display. */
private class ReceiverLatencyProbe(private val enabled: Boolean, durationSeconds: Int) {
    private class Entry(
        val sequence: Long,
        val kind: String,
        val eventTimeNanos: Long,
        val receiptNanos: Long,
        val action: Int,
        val source: Int,
        val deviceId: Int,
        val historySize: Int,
        val touchSequence: Long?,
    ) {
        var drawCpuCompleteNanos = 0L
        var supersededBySequence = 0L
    }

    private val startedNanos = if (enabled) System.nanoTime() else 0L
    private val deadlineNanos = if (enabled) startedNanos + durationSeconds * 1_000_000_000L else 0L
    private val entries = ArrayList<Entry>(if (enabled) MAX_ENTRIES else 0)
    private var pendingTouch: Entry? = null
    private var pendingMouse: Entry? = null
    private var limitReached = false

    fun receiptNanos(): Long = if (enabled) System.nanoTime() else 0L

    fun received(event: MotionEvent, receiptNanos: Long, kind: String, touchSequence: Long? = null) {
        if (!enabled || receiptNanos < startedNanos || receiptNanos > deadlineNanos || limitReached) return
        val entry = Entry(
            entries.size.toLong() + 1L, kind, event.eventTimeNanos, receiptNanos,
            event.action, event.source, event.deviceId, event.historySize, touchSequence,
        )
        val previous = if (kind == "mouse") pendingMouse else pendingTouch
        previous?.supersededBySequence = entry.sequence
        if (kind == "mouse") pendingMouse = entry else pendingTouch = entry
        entries.add(entry)
        if (entries.size == MAX_ENTRIES) limitReached = true
    }

    fun drawComplete() {
        if (!enabled || (pendingTouch == null && pendingMouse == null)) return
        // Timestamp after all drawing commands, once per frame and with no per-frame allocation.
        val completed = System.nanoTime()
        if (completed <= deadlineNanos) {
            pendingTouch?.drawCpuCompleteNanos = completed
            pendingMouse?.drawCpuCompleteNanos = completed
        }
        pendingTouch = null
        pendingMouse = null
    }

    fun snapshot(includeActiveSamples: Boolean): JSONObject {
        val now = if (enabled) System.nanoTime() else 0L
        val collecting = enabled && now <= deadlineNanos && !limitReached
        // Avoid repeatedly serializing thousands of entries during the timed interval.
        val includeSamples = !collecting || includeActiveSamples
        return JSONObject().apply {
            put("enabled", enabled)
            put("collecting", collecting)
            put("startedMonotonicNanos", startedNanos)
            put("deadlineMonotonicNanos", deadlineNanos)
            put("snapshotMonotonicNanos", now)
            put("maxSamples", MAX_ENTRIES)
            put("recordedSamples", entries.size)
            put("sampleLimitReached", limitReached)
            put("samplesIncluded", includeSamples)
            put("clock", "System.nanoTime and MotionEvent.eventTimeNanos; CLOCK_MONOTONIC, excludes suspend")
            put("unit", "one current MotionEvent per callback; history is counted but not timed separately")
            put("drawAttribution", "latest touch and latest mouse state in each frame; earlier callbacks of that kind are superseded")
            put("limitations", "CPU onDraw completion only, not RenderThread/GPU/compositor/presentation/photon. Physical or injected origin is not inferred. Event timestamps of injected touches do not retain the mapper's original mouse timestamp. Existing observer snapshots, drawing and transition logs add overhead. Full samples are deferred during passive snapshots while collecting.")
            put("samples", JSONArray().apply {
                if (includeSamples) entries.forEach { entry ->
                    put(JSONObject().apply {
                        put("sequence", entry.sequence)
                        put("kind", entry.kind)
                        put("touchSequence", entry.touchSequence ?: JSONObject.NULL)
                        put("eventTimeMonotonicNanos", entry.eventTimeNanos)
                        put("receiptMonotonicNanos", entry.receiptNanos)
                        put("action", entry.action)
                        put("source", entry.source)
                        put("deviceId", entry.deviceId)
                        put("historySize", entry.historySize)
                        put("eventToReceiptMs", (entry.receiptNanos - entry.eventTimeNanos) / 1e6)
                        put("drawStatus", when {
                            entry.drawCpuCompleteNanos != 0L -> "drawn_latest_state"
                            entry.supersededBySequence != 0L -> "superseded_before_draw"
                            now > deadlineNanos -> "window_ended_without_draw"
                            else -> "pending_draw"
                        })
                        put("supersededBySequence", entry.supersededBySequence.takeIf { it != 0L } ?: JSONObject.NULL)
                        put("drawCpuCompleteMonotonicNanos", entry.drawCpuCompleteNanos.takeIf { it != 0L } ?: JSONObject.NULL)
                        put("receiptToDrawCpuMs", if (entry.drawCpuCompleteNanos == 0L) JSONObject.NULL
                            else (entry.drawCpuCompleteNanos - entry.receiptNanos) / 1e6)
                        put("eventToDrawCpuMs", if (entry.drawCpuCompleteNanos == 0L) JSONObject.NULL
                            else (entry.drawCpuCompleteNanos - entry.eventTimeNanos) / 1e6)
                    })
                }
            })
        }
    }

    private companion object { const val MAX_ENTRIES = 4096 }
}

private class ObserverView(
    context: Context,
    private val observationId: String,
    private val activityId: String,
    latencyProbeEnabled: Boolean,
    latencyProbeSeconds: Int,
) : View(context) {
    private val latencyProbe = ReceiverLatencyProbe(latencyProbeEnabled, latencyProbeSeconds)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val active = linkedMapOf<Int, Contact>()
    private val sequenceValidator = TouchSequenceValidator()
    private val samples = ArrayDeque<Sample>(MAX_SAMPLES)
    private val transitions = ArrayDeque<Sample>(MAX_TRANSITIONS)
    private val lifecycle = ArrayDeque<LifecycleEntry>(MAX_LIFECYCLE_ENTRIES)
    private val snapshotHandler = Handler(Looper.getMainLooper())
    // One running write and one replaceable pending snapshot. Slow storage cannot grow a queue.
    private val snapshotWriter = ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
        { runnable -> Thread(runnable, "InputLabSnapshots") },
        ThreadPoolExecutor.DiscardOldestPolicy(),
    )
    private var resumed = false
    private var windowFocused = false
    private var lifecycleState = "initializing"
    private var observationClosed = false
    private var dirty = true
    private var snapshotsRequested = 0L
    private val liveSnapshot = object : Runnable {
        override fun run() {
            if (observationClosed || !resumed) return
            // A resumed observer publishes a heartbeat even when no more events arrive.
            if (dirty || resumed) requestSnapshot("passive")
            snapshotHandler.postDelayed(this, LIVE_SNAPSHOT_INTERVAL_MS)
        }
    }
    private val notes = ArrayDeque<String>(8)
    private var sequence = 0L
    private var maxContacts = 0
    private var partialReleases = 0
    private var validationErrors = 0
    private var keyDowns = 0L
    private var keyUps = 0L
    private var mappedKeyEvents = 0L
    private var mouseEvents = 0L
    private var wheelEvents = 0L
    private var mouseButtonEvents = 0L
    private var movementSamples = 0L
    private var touchTransitions = 0L
    private var lastEvent = "Esperando contactos"
    private var lastKey = "Sin eventos de teclado"
    private var lastMouse = "Sin eventos de ratón"
    private var saveStatus = "Guardar escribe solo datos de este laboratorio"
    private var insetDescription = "Insets pendientes"
    private var measuredWidth = 0
    private var measuredHeight = 0
    private val startedAt = SystemClock.uptimeMillis()
    private val saveButton = RectF()
    private val contactColors = intArrayOf(
        0xff56d9c1.toInt(), 0xffefb65f.toInt(), 0xff9c9afa.toInt(),
        0xffe97ca3.toInt(), 0xff8dc6ff.toInt(),
    )

    init {
        contentDescription = "Laboratorio independiente de eventos táctiles, teclado y ratón"
        isFocusable = true
        isFocusableInTouchMode = true
        setOnApplyWindowInsetsListener { _, insets ->
            val systemBars = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars())
            val cutout = insets.getInsets(WindowInsets.Type.displayCutout())
            insetDescription = "Barras=${systemBars.left},${systemBars.top},${systemBars.right},${systemBars.bottom} " +
                "recorte=${cutout.left},${cutout.top},${cutout.right},${cutout.bottom}"
            dirty = true
            insets
        }
        requestFocus()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        measuredWidth = w
        measuredHeight = h
        dirty = true
        addNote("Lienzo ${w}×$h display=${display?.displayId}")
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val receiptNanos = latencyProbe.receiptNanos()
        val receipt = SystemClock.uptimeMillis()
        val mouse = event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
        // Mouse clicks also enter this touch observer; time them once as mouse, not again as touch.
        if (mouse) observeMouse(event, receiptNanos)
        else latencyProbe.received(event, receiptNanos, "touch", sequence + 1L)
        // Copy values during callback. Never retain a recyclable MotionEvent.
        val contacts = (0 until event.pointerCount).map { index ->
            Contact(event.getPointerId(index), event.getX(index), event.getY(index), event.getPressure(index))
        }
        val sample = Sample(
            ++sequence, event.action, event.actionIndex, event.downTime, event.eventTime,
            receipt, event.source, event.deviceId, contacts,
        )
        if (samples.size == MAX_SAMPLES) samples.removeFirst()
        samples.addLast(sample)
        validateAndApply(event.actionMasked, event.actionIndex, event.downTime, contacts)
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            movementSamples++
        } else {
            touchTransitions++
            if (transitions.size == MAX_TRANSITIONS) transitions.removeFirst()
            transitions.addLast(sample)
            Log.i(InputLabActivity.TAG, "observation=$observationId activity=$activityId " +
                "targetUid=${android.os.Process.myUid()} display=${display?.displayId} " +
                "${sample.logLine()} pending=${active.keys}")
        }
        dirty = true
        lastEvent = "#$sequence ${MotionEvent.actionToString(event.action)} · " +
            "${contacts.size} punteros · demora evento→recepción ${receipt - event.eventTime} ms"
        if (event.actionMasked == MotionEvent.ACTION_UP && saveButton.contains(event.x, event.y)) {
            performClick()
            saveSummary()
        }
        postInvalidateOnAnimation()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun validateAndApply(action: Int, index: Int, downTime: Long, contacts: List<Contact>) {
        fun error(reason: String) {
            validationErrors++
            addNote("Secuencia: $reason")
            Log.w(InputLabActivity.TAG, "sequence_error observation=$observationId activity=$activityId " +
                "seq=$sequence reason=$reason")
        }
        sequenceValidator.accept(action, index, downTime, IntArray(contacts.size) { contacts[it].id }).forEach(::error)
        maxContacts = max(maxContacts, contacts.size)
        active.clear()
        contacts.forEach { active[it.id] = it }
        when (action) {
            MotionEvent.ACTION_POINTER_UP -> {
                if (index in contacts.indices) {
                    active.remove(contacts[index].id)
                    partialReleases++
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> active.clear()
        }
    }

    fun observeKey(event: KeyEvent) {
        dirty = true
        when (event.action) {
            KeyEvent.ACTION_DOWN -> keyDowns++
            KeyEvent.ACTION_UP -> keyUps++
        }
        if (event.keyCode in MAPPED_KEYS) mappedKeyEvents++
        val device = event.device
        val actionLabel = when (event.action) {
            KeyEvent.ACTION_DOWN -> "DOWN"
            KeyEvent.ACTION_UP -> "UP"
            else -> "ACTION_${event.action}"
        }
        lastKey = "${KeyEvent.keyCodeToString(event.keyCode)} $actionLabel " +
            "scan=${event.scanCode} rep=${event.repeatCount} dispositivo=${event.deviceId}"
        Log.i(InputLabActivity.TAG, "key $lastKey source=0x${event.source.toString(16)} " +
            "virtual=${device?.isVirtual} eventTime=${event.eventTime} receiptUptime=${SystemClock.uptimeMillis()}")
        postInvalidateOnAnimation()
    }

    fun observeMouse(event: MotionEvent, receiptNanos: Long = latencyProbe.receiptNanos()) {
        latencyProbe.received(event, receiptNanos, "mouse")
        dirty = true
        mouseEvents++
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) wheelEvents++
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS || event.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE) {
            mouseButtonEvents++
        }
        lastMouse = "${MotionEvent.actionToString(event.action)} botones=${event.buttonState} " +
            "rueda=${event.getAxisValue(MotionEvent.AXIS_VSCROLL)},${event.getAxisValue(MotionEvent.AXIS_HSCROLL)} " +
            "rel=${event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)},${event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)}"
        // No per-movement logging: diagnostics must not dominate the observed workload.
        if (event.actionMasked != MotionEvent.ACTION_HOVER_MOVE && event.actionMasked != MotionEvent.ACTION_MOVE) {
            Log.i(InputLabActivity.TAG, "mouse $lastMouse device=${event.deviceId} source=0x${event.source.toString(16)}")
        }
        postInvalidateOnAnimation()
    }

    private fun addNote(note: String) {
        if (notes.size == 8) notes.removeFirst()
        notes.addLast(note)
    }

    fun recordLifecycle(state: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        lifecycleState = state
        val entry = LifecycleEntry(state, SystemClock.uptimeMillis(), resumed, windowFocused)
        if (lifecycle.size == MAX_LIFECYCLE_ENTRIES) lifecycle.removeFirst()
        lifecycle.addLast(entry)
        dirty = true
        Log.i(InputLabActivity.TAG, "lifecycle observation=$observationId activity=$activityId " +
            "pid=${android.os.Process.myPid()} state=$state uptime=${entry.uptime} " +
            "resumed=$resumed focused=$windowFocused seq=$sequence pending=${active.keys}")
    }

    fun resumeObservation() {
        resumed = true
        recordLifecycle("resumed")
        snapshotHandler.removeCallbacks(liveSnapshot)
        snapshotHandler.post(liveSnapshot)
    }

    fun pauseObservation() {
        resumed = false
        recordLifecycle("paused")
        snapshotHandler.removeCallbacks(liveSnapshot)
        requestSnapshot("pause")
    }

    fun recordWindowFocus(hasFocus: Boolean) {
        windowFocused = hasFocus
        recordLifecycle("window_focus:$hasFocus")
        if (!resumed) requestSnapshot("window_focus")
    }

    fun closeObservation() {
        if (observationClosed) return
        recordLifecycle("destroyed")
        requestSnapshot("destroy")
        observationClosed = true
        snapshotHandler.removeCallbacksAndMessages(null)
        // Queued immutable snapshots finish without retaining an active UI callback.
        snapshotWriter.shutdown()
    }

    fun saveSummary() {
        requestSnapshot("manual")
        saveStatus = "Guardado solicitado · $sequence eventos · ${active.size} pendientes"
        invalidate()
    }

    private fun requestSnapshot(reason: String) {
        check(Looper.myLooper() == Looper.getMainLooper())
        if (observationClosed) return
        val snapshotUptime = SystemClock.uptimeMillis()
        val snapshotNumber = ++snapshotsRequested
        val publicationOrder = SNAPSHOT_ORDER.incrementAndGet()
        val windowLocation = IntArray(2)
        getLocationOnScreen(windowLocation)
        val summary = JSONObject().apply {
            put("schemaVersion", 2)
            put("observationId", observationId)
            put("activityId", activityId)
            put("pid", android.os.Process.myPid())
            put("package", context.packageName)
            put("uid", android.os.Process.myUid())
            put("startedUptimeMs", startedAt)
            put("savedUptimeMs", snapshotUptime)
            put("capturedUptimeMs", snapshotUptime)
            put("snapshotNumber", snapshotNumber)
            put("publicationOrder", publicationOrder)
            put("snapshotReason", reason)
            put("snapshotIntervalMs", LIVE_SNAPSHOT_INTERVAL_MS)
            put("activityResumed", resumed)
            put("windowFocused", windowFocused)
            put("lastLifecycleState", lifecycleState)
            put("lifecycle", JSONArray().apply { lifecycle.forEach { put(it.json()) } })
            put("clock", "android.os.SystemClock.uptimeMillis; MotionEvent.eventTime/downTime same timebase")
            put("displayId", display?.displayId ?: -1)
            put("width", measuredWidth)
            put("height", measuredHeight)
            put("windowOriginScreenX", windowLocation[0])
            put("windowOriginScreenY", windowLocation[1])
            put("insets", insetDescription)
            put("totalTouchEvents", sequence)
            put("touchCountUnit", "callbacks; historical MotionEvent samples are not counted separately")
            put("touchTransitions", touchTransitions)
            put("movementSamples", movementSamples)
            put("maxContacts", maxContacts)
            put("partialReleases", partialReleases)
            put("pendingContactIds", JSONArray(active.keys.toList()))
            put("validatorPendingContactIds", JSONArray(sequenceValidator.pendingIds.toList()))
            put("sequenceErrors", validationErrors)
            put("keyDowns", keyDowns)
            put("keyUps", keyUps)
            put("assignedCandidateKeyEvents", mappedKeyEvents)
            put("mouseEvents", mouseEvents)
            put("mouseCountUnit", "callbacks from SOURCE_MOUSE or SOURCE_MOUSE_RELATIVE only")
            put("wheelEvents", wheelEvents)
            put("mouseButtonEvents", mouseButtonEvents)
            put("retainedSampleLimit", MAX_SAMPLES)
            put("samples", JSONArray().apply { samples.forEach { put(it.json()) } })
            put("retainedTransitionLimit", MAX_TRANSITIONS)
            put("transitions", JSONArray().apply { transitions.forEach { put(it.json()) } })
            put("omittedSamples", sequence - samples.size)
            put("omittedTransitions", touchTransitions - transitions.size)
            put("lastReceivedEvent", samples.peekLast()?.json() ?: JSONObject.NULL)
            put("lastReceivedTransition", transitions.peekLast()?.json() ?: JSONObject.NULL)
            put("latencyProbe", latencyProbe.snapshot(includeActiveSamples = reason != "passive"))
            put("limitations", "Event-to-receipt timing only. Origin physical/injected not inferred. No mapper communication.")
        }
        val payload = summary.toString(2)
        val filesDirectory = context.filesDir
        val snapshotSequence = sequence
        val snapshotPending = active.size
        val snapshotErrors = validationErrors
        dirty = false
        snapshotWriter.execute {
            try {
                // A closing Activity may still drain IO while a new Activity starts. Older
                // observations must never overwrite a newer snapshot or share its temp file.
                val published = synchronized(SNAPSHOT_PUBLICATION_LOCK) {
                    if (publicationOrder <= latestPublishedOrder) {
                        false
                    } else {
                        // Both names use atomic replacement; summary keeps older scripts working.
                        for (name in listOf("input-lab-live", "input-lab-summary")) {
                            val destination = File(filesDirectory, "$name.json")
                            val temporary = File(filesDirectory, "$name-$observationId.tmp")
                            temporary.writeText(payload, Charsets.UTF_8)
                            check(temporary.renameTo(destination)) { "No se pudo sustituir $name" }
                        }
                        latestPublishedOrder = publicationOrder
                        true
                    }
                }
                if (published && reason != "passive") {
                    Log.i(InputLabActivity.TAG, "snapshot_saved observation=$observationId activity=$activityId " +
                        "snapshot=$snapshotNumber reason=$reason uptime=$snapshotUptime " +
                        "events=$snapshotSequence pending=$snapshotPending errors=$snapshotErrors")
                }
            } catch (error: Exception) {
                Log.e(InputLabActivity.TAG, "snapshot_save_failed observation=$observationId " +
                    "snapshot=$snapshotNumber reason=$reason", error)
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xff111920.toInt())
        val w = width.toFloat()
        val h = height.toFloat()
        val unit = min(w / 1080f, h / 1600f).coerceAtLeast(.55f)
        val margin = 32f * unit
        var y = max(60f * unit, h * .045f)
        fun text(value: String, size: Float = 25f, color: Int = 0xffd8e4ec.toInt()) {
            paint.color = color
            paint.style = Paint.Style.FILL
            paint.textSize = size * unit
            canvas.drawText(value, margin, y, paint)
            y += (size + 13f) * unit
        }
        text("MobiMapper · Laboratorio independiente", 36f, 0xff56d9c1.toInt())
        text("Observa eventos. No captura ni ayuda al mapeador.", 24f)
        text("UID ${android.os.Process.myUid()} · display ${display?.displayId} · ${width}×$height", 23f)
        text("Activos ${active.size} · máximo $maxContacts · liberaciones parciales $partialReleases", 23f)
        text("Errores de secuencia $validationErrors · eventos $sequence", 23f)
        text(lastEvent, 21f)
        text("Teclado observado: ↓$keyDowns ↑$keyUps · WASD/ESPACIO $mappedKeyEvents", 21f)
        text(lastKey, 19f)
        text("Ratón observado: $mouseEvents · rueda $wheelEvents · botones $mouseButtonEvents", 21f)
        text(lastMouse.take(105), 19f)
        text("Si están asignadas, estas entradas indican fugas al destino.", 19f, 0xffefb65f.toInt())

        val joystickX = w * .23f
        val joystickY = h * .70f
        val radius = w * .12f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f * unit
        paint.color = 0xff397184.toInt()
        canvas.drawCircle(joystickX, joystickY, radius, paint)
        canvas.drawLine(joystickX - radius, joystickY, joystickX + radius, joystickY, paint)
        canvas.drawLine(joystickX, joystickY - radius, joystickX, joystickY + radius, paint)
        paint.color = 0xff3f567b.toInt()
        canvas.drawRoundRect(w * .50f, h * .30f, w * .90f, h * .80f, 18f * unit, 18f * unit, paint)
        paint.color = 0xff886b42.toInt()
        canvas.drawCircle(w * .82f, h * .82f, w * .07f, paint)
        paint.style = Paint.Style.FILL
        paint.textSize = 24f * unit
        paint.color = 0xff8ab9c7.toInt()
        canvas.drawText("JOYSTICK", joystickX - radius * .64f, joystickY + radius + 34f * unit, paint)
        canvas.drawText("CÁMARA", w * .53f, h * .32f + 30f * unit, paint)
        canvas.drawText("ACCIÓN", w * .75f, h * .82f + 8f * unit, paint)

        active.values.forEach { contact ->
            val color = contactColors[contact.id.mod(contactColors.size)]
            paint.color = color
            paint.style = Paint.Style.FILL
            canvas.drawCircle(contact.x, contact.y, 23f * unit, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * unit
            canvas.drawCircle(contact.x, contact.y, 40f * unit, paint)
            paint.style = Paint.Style.FILL
            paint.textSize = 23f * unit
            val xLabel = min(contact.x + 28f * unit, w - 275f * unit).coerceAtLeast(margin)
            canvas.drawText("ID ${contact.id} (${contact.x.toInt()}, ${contact.y.toInt()})", xLabel, contact.y - 30f * unit, paint)
        }

        y = h * .895f
        text(insetDescription, 17f)
        text(saveStatus, 19f)
        saveButton.set(margin, h * .95f - 20f * unit, w - margin, h * .985f)
        paint.color = 0xff204b50.toInt()
        canvas.drawRoundRect(saveButton, 12f * unit, 12f * unit, paint)
        paint.color = Color.WHITE
        paint.textSize = 23f * unit
        canvas.drawText("Guardar resumen de esta prueba", margin + 24f * unit, saveButton.centerY() + 8f * unit, paint)
        latencyProbe.drawComplete()
    }

    companion object {
        const val MAX_SAMPLES = 256
        const val MAX_TRANSITIONS = 512
        const val MAX_LIFECYCLE_ENTRIES = 64
        const val LIVE_SNAPSHOT_INTERVAL_MS = 500L
        private val SNAPSHOT_ORDER = AtomicLong()
        private val SNAPSHOT_PUBLICATION_LOCK = Any()
        private var latestPublishedOrder = 0L
        val MAPPED_KEYS = setOf(KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_SPACE)
    }
}
