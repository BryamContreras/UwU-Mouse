package dev.uwumouse.latencyprobe

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import dev.uwumouse.app.session.InputLatencyProbe
import org.json.JSONObject
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Temporary measurement app. This is not the installed mapper or a replacement capture backend. */
class ProbeActivity : Activity() {
    internal lateinit var plot: ProbeView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setDecorFitsSystemWindows(false)
        plot = ProbeView(this)
        setContentView(plot)
        window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    override fun onResume() {
        super.onResume()
        ProbeRun.activity = this
    }
    override fun onPause() {
        ProbeRun.stop("activity_paused")
        if (ProbeRun.activity === this) ProbeRun.activity = null
        super.onPause()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && ProbeRun.activity === this) ProbeRun.stop("window_focus_lost")
    }
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (ProbeRun.nativeMotion(event)) return true
        return super.dispatchGenericMotionEvent(event)
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Consume native mouse clicks; they only change the laboratory drawing.
        if (ProbeRun.nativeMotion(event)) return true
        return super.dispatchTouchEvent(event)
    }
}

class ProbeAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        serviceInfo = serviceInfo.apply { eventTypes = 0; setMotionEventSources(0) }
        instance = this
    }
    internal fun capture(enabled: Boolean) {
        serviceInfo = serviceInfo.apply {
            eventTypes = 0
            setMotionEventSources(if (enabled) InputDevice.SOURCE_MOUSE or InputDevice.SOURCE_MOUSE_RELATIVE else 0)
        }
    }
    override fun onMotionEvent(event: MotionEvent) { ProbeRun.accessibilityMotion(event) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() { ProbeRun.stop("accessibility_interrupted") }
    override fun onDestroy() {
        ProbeRun.stop("accessibility_destroyed")
        instance = null
        super.onDestroy()
    }
    companion object { internal var instance: ProbeAccessibilityService? = null }
}

/** All mutations run on the main looper, as do both input callbacks and drawing. */
internal object ProbeRun {
    var activity: ProbeActivity? = null
    private val main = Handler(Looper.getMainLooper())
    private var mode = "none"
    private var running = false
    private var stopReason = "not_started"
    private var received = 0L
    private var drawn = 0L
    private var superseded = 0L
    private var histories = 0L
    private var lastDevice = -1
    private var lastSource = 0
    private var pendingSourceTime = 0L
    private var pendingAction = -1
    private var lastAction = -1
    private var lastButtons = 0
    private var x = 100f
    private var y = 100f
    private val endWindow = Runnable { stop("window_expired") }

    fun start(requestedMode: String, durationMs: Int): Map<String, Any?> {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(requestedMode == "native" || requestedMode == "accessibility") { "mode must be native or accessibility" }
        require(durationMs in 1..60_000) { "durationMs must be 1..60000" }
        val target = requireNotNull(activity) { "Open the laboratory Activity first" }
        check(target.hasWindowFocus()) { "Laboratory Activity must have window focus" }
        if (requestedMode == "accessibility") check(ProbeAccessibilityService.instance != null) { "Enable the temporary laboratory accessibility service first" }
        stop("new_run")
        mode = requestedMode
        received = 0L; drawn = 0L; superseded = 0L; histories = 0L
        lastDevice = -1; lastSource = 0; pendingSourceTime = 0L
        x = target.plot.width / 2f; y = target.plot.height / 2f
        InputLatencyProbe.start(durationMs, Process.myPid())
        running = true
        stopReason = "recording"
        try {
            ProbeAccessibilityService.instance?.capture(mode == "accessibility")
            main.postDelayed(endWindow, durationMs.toLong())
        } catch (failure: RuntimeException) {
            stop("source_configuration_failed")
            throw failure
        }
        target.plot.invalidate()
        return snapshot(0, 0)
    }

    fun stop(reason: String): Map<String, Any?> {
        main.removeCallbacks(endWindow)
        if (running) {
            running = false
            stopReason = reason
            ProbeAccessibilityService.instance?.capture(false)
            InputLatencyProbe.stop()
            pendingSourceTime = 0L
            activity?.plot?.invalidate()
        }
        return snapshot(0, 0)
    }

    fun nativeMotion(event: MotionEvent): Boolean {
        if (!running || mode != "native" || !isMouse(event)) return false
        // The recorder timestamps its stage immediately on entry, before any drawing work.
        InputLatencyProbe.recordStage("helper_native_receipt", event.eventTimeNanos, event.action, event.eventTime)
        receive(event)
        return true
    }

    fun accessibilityMotion(event: MotionEvent) {
        if (!running || mode != "accessibility") return
        InputLatencyProbe.recordStage("helper_accessibility_receipt", event.eventTimeNanos, event.action, event.eventTime)
        if (!isMouse(event)) return
        receive(event)
    }

    private fun isMouse(event: MotionEvent) = event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)

    private fun receive(event: MotionEvent) {
        if (!InputLatencyProbe.enabled) { stop("recorder_window_expired"); return }
        received++
        histories += event.historySize
        lastDevice = event.deviceId; lastSource = event.source
        lastAction = event.action; lastButtons = event.buttonState
        if (pendingSourceTime != 0L) superseded++
        pendingSourceTime = event.eventTimeNanos
        pendingAction = event.action
        val target = activity ?: return
        if (event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
            event.getAxisValue(MotionEvent.AXIS_RELATIVE_X) != 0f || event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y) != 0f) {
            x = (x + event.getAxisValue(MotionEvent.AXIS_RELATIVE_X)).coerceIn(0f, target.plot.width.toFloat())
            y = (y + event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)).coerceIn(0f, target.plot.height.toFloat())
        } else {
            x = event.x.coerceIn(0f, target.plot.width.toFloat())
            y = event.y.coerceIn(0f, target.plot.height.toFloat())
        }
        target.plot.postInvalidateOnAnimation()
    }

    fun draw(canvas: Canvas, paint: Paint) {
        canvas.drawColor(0xff111920.toInt())
        paint.color = 0xffd8e4ec.toInt(); paint.textSize = 32f
        canvas.drawText("UwU Mouse · laboratorio temporal", 25f, 60f, paint)
        canvas.drawText("Modo: $mode · ${if (running) "midiendo" else stopReason}", 25f, 110f, paint)
        canvas.drawText("Callbacks: $received · dibujados: $drawn · agrupados: $superseded", 25f, 160f, paint)
        canvas.drawText("Mouse: device=$lastDevice action=$lastAction buttons=$lastButtons", 25f, 210f, paint)
        canvas.drawText("Se mide hasta onDraw CPU. No mide el píxel visible.", 25f, 260f, paint)
        paint.color = if (lastButtons == 0) 0xff56d9c1.toInt() else 0xffefb65f.toInt()
        canvas.drawCircle(x, y, 22f, paint)
        if (pendingSourceTime != 0L && running) {
            InputLatencyProbe.recordStage("helper_${mode}_draw_cpu_end", pendingSourceTime, pendingAction)
            drawn++
            pendingSourceTime = 0L
        }
    }

    fun snapshot(offset: Int, limit: Int): Map<String, Any?> = InputLatencyProbe.snapshot(offset, limit).toMutableMap().apply {
        put("helperPackage", "dev.uwumouse.latencyprobe")
        put("helperMode", mode); put("helperRunning", running); put("helperStopReason", stopReason)
        put("activityFocused", activity?.hasWindowFocus() == true)
        put("activityDisplayId", activity?.display?.displayId)
        put("accessibilityConnected", ProbeAccessibilityService.instance != null)
        put("receivedCallbacks", received); put("drawnLatestSamples", drawn)
        put("supersededBeforeDraw", superseded); put("historicalSamplesNotSeparatelyTimed", histories)
        put("lastDeviceId", lastDevice); put("lastSource", lastSource)
        put("receiptScope", "Auxiliary app: original current MotionEvent timestamp to first recordStage call in native Activity or accessibility callback; whole Android delivery path, not pure accessibility cost or mapper delay")
        put("drawScope", "Same Activity and View in both modes; only the latest pending callback is attributed to CPU onDraw completion. Earlier callbacks have no individual draw. Not GPU, presentation or photon latency")
        put("historicalScope", "Current callback samples only; historical samples are counted but not timed separately")
        put("stageScope", "helper_native_* and helper_accessibility_* belong to the auxiliary benchmark, not the installed mapper implementation")
    }
}

internal class ProbeView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init { isFocusable = true; isFocusableInTouchMode = true; requestFocus() }
    override fun onDraw(canvas: Canvas) { super.onDraw(canvas); ProbeRun.draw(canvas, paint) }
    override fun onTouchEvent(event: MotionEvent) = true
}

class ProbeControlProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == 2000 || Binder.getCallingUid() == 0) { "ADB shell required" }
        val task = FutureTask<Map<String, Any?>> {
            when (method) {
                "start" -> ProbeRun.start(extras?.getString("mode") ?: arg ?: "native", extras?.getInt("durationMs", 30_000) ?: 30_000)
                "stop" -> ProbeRun.stop("requested")
                "snapshot" -> ProbeRun.snapshot(extras?.getInt("offset", 0) ?: 0, extras?.getInt("limit", 512) ?: 512)
                else -> throw IllegalArgumentException("Unknown laboratory method")
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run() else Handler(Looper.getMainLooper()).post(task)
        return Bundle().apply { putString("json", JSONObject(task.get(5, TimeUnit.SECONDS)).toString()) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
