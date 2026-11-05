package dev.mobimapper.mobimapper

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import dev.mobimapper.mobimapper.session.SessionController
import dev.mobimapper.mobimapper.session.SessionService
import dev.mobimapper.mobimapper.game.GameCoordinator
import dev.mobimapper.mobimapper.game.GameCatalog
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    companion object {
        var latest=java.lang.ref.WeakReference<MainActivity>(null)
        @Volatile var createdCount=0
        @Volatile var destroyedCount=0
        @Volatile var lastInstance=0
    }
    override fun onCreate(savedInstanceState:android.os.Bundle?) {
        super.onCreate(savedInstanceState); latest=java.lang.ref.WeakReference(this)
        createdCount++; lastInstance=System.identityHashCode(this)
        android.util.Log.i("MobiActivity","created instance=${System.identityHashCode(this)} pid=${android.os.Process.myPid()} uptime=${android.os.SystemClock.uptimeMillis()}")
    }
    override fun onDestroy() {
        destroyedCount++
        android.util.Log.i("MobiActivity","destroyed instance=${System.identityHashCode(this)} pid=${android.os.Process.myPid()} uptime=${android.os.SystemClock.uptimeMillis()}")
        super.onDestroy()
    }
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        GameCoordinator.initialize(this)
        dev.mobimapper.mobimapper.backend.BackendRegistry.requestAttach(this)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "dev.mobimapper/control.v1").setMethodCallHandler { call, result ->
            try {
                when(call.method) {
                    "status" -> result.success(SessionController.status(this))
                    "listApps" -> result.success(GameCatalog.list(this))
                    "listProfiles" -> result.success(GameCoordinator.profiles())
                    "selectedProfile" -> result.success(GameCoordinator.selected())
                    "createProfile" -> result.success(GameCoordinator.create(requireNotNull(call.argument<String>("packageName")),requireNotNull(call.argument<String>("name"))))
                    "selectProfile" -> result.success(GameCoordinator.select(requireNotNull(call.argument<String>("id"))))
                    "renameProfile" -> result.success(GameCoordinator.rename(requireNotNull(call.argument<String>("id")),requireNotNull(call.argument<String>("name"))))
                    "deleteProfile" -> { GameCoordinator.delete(requireNotNull(call.argument<String>("id"))); result.success(null) }
                    "openEditor" -> { GameCoordinator.openEditor(requireNotNull(call.argument<String>("id"))); result.success(null) }
                    "startProfile" -> { GameCoordinator.startProfile(requireNotNull(call.argument<String>("id"))); result.success(null) }
                    "pause" -> { SessionController.pause("Pausada por usuario"); result.success(null) }
                    "accessibilitySettings" -> { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); result.success(null) }
                    "openLab" -> { launchLab(); result.success(null) }
                    "startLab", "probe" -> {
                        GameCoordinator.clearTools()
                        val bounds = windowManager.maximumWindowMetrics.bounds
                        if (call.method == "startLab") SessionController.arm(bounds.width(), bounds.height())
                        else check(dev.mobimapper.mobimapper.backend.BackendRegistry.injector != null) { "Activa primero el backend ADB" }
                        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
                        startForegroundService(Intent(this, SessionService::class.java))
                        launchLab()
                        if (call.method == "probe") SessionController.probe(bounds.width(), bounds.height())
                        result.success(null)
                    }
                    "stop" -> { GameCoordinator.stop(); result.success(null) }
                    "shutdownBackend" -> { SessionController.shutdownBackend(); result.success(null) }
                    else -> result.notImplemented()
                }
            } catch(e: Exception) { result.error("NATIVE_STATE", e.message, null) }
        }
    }
    private fun launchLab() {
        val intent = packageManager.getLaunchIntentForPackage(SessionController.TARGET) ?: error("Instala el APK MobiMapper Input Lab")
        // Reuse the focused task's existing receiver when starting another session.
        // A new Activity would discard the independent observer between recovery and a new press.
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
    }
}
