package dev.uwumouse.app.diagnostics

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import dev.uwumouse.app.backend.IInjector
import dev.uwumouse.app.engine.CameraResetResult
import dev.uwumouse.app.engine.TouchComposer
import dev.uwumouse.app.engine.TouchFrame
import dev.uwumouse.app.session.CameraResetClient
import java.io.File
import org.json.JSONObject

/** Shell-authorized debug fixture; it never obtains or replaces the application's real backend. */
internal object CameraResetPipeDiagnostics {
    @Synchronized fun call(context: Context, extras: Bundle?): Bundle {
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { "Debug build required" }
        check(Looper.myLooper() != Looper.getMainLooper()) { "Probe must run on a Binder thread" }
        val configuration = requireNotNull(extras)
        require(configuration.containsKey("caseId"))
        val caseId = configuration.getInt("caseId")
        require(caseId in 0..5)
        val requestId = requireNotNull(configuration.getString("requestId"))
        require(requestId.matches(Regex("[a-f0-9]{32}")))
        val probe = requireNotNull(IInjector.Stub.asInterface(requireNotNull(configuration.getBinder("probe"))))
        val started = SystemClock.uptimeMillis()
        val frames = listOf(
            TouchFrame(TouchComposer.UP, maxOf(0, started - 10), started,
                intArrayOf(9), floatArrayOf(600f), floatArrayOf(400f)),
            TouchFrame(TouchComposer.DOWN, started, started,
                intArrayOf(9), floatArrayOf(300f), floatArrayOf(400f)),
            TouchFrame(TouchComposer.MOVE, started, started,
                intArrayOf(9), floatArrayOf(302f), floatArrayOf(400f)),
        )
        val result = CameraResetClient(probe, 1L).inject(frames)
        val expected = when (caseId) {
            0 -> CameraResetResult(0, 0)
            in 1..3 -> CameraResetResult(caseId - 1, caseId)
            4 -> CameraResetResult(3, 3)
            else -> CameraResetResult(0, 0, progressKnown = false)
        }
        val document = JSONObject().put("caseId", caseId).put("requestId", requestId)
            .put("acceptedCount", result.acceptedCount).put("attemptedCount", result.attemptedCount)
            .put("progressKnown", result.progressKnown).put("assertOutcome", result == expected)
            .put("startedUptimeMs", started).put("completedUptimeMs", SystemClock.uptimeMillis())
        val target = AtomicFile(File(context.filesDir, "camera-reset-pipe-probe.json"))
        val output = target.startWrite()
        try {
            output.write(document.toString().toByteArray(Charsets.UTF_8))
            target.finishWrite(output)
        } catch (failure: Exception) {
            target.failWrite(output)
            throw failure
        }
        return Bundle().apply { putString("json", document.toString()) }
    }
}
