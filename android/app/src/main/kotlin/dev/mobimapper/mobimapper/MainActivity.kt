package dev.mobimapper.mobimapper

import android.Manifest
import android.app.ActivityOptions
import android.hardware.display.DisplayManager
import android.graphics.Point
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.Settings
import dev.mobimapper.mobimapper.runtime.NativeSessionHost
import dev.mobimapper.mobimapper.editor.ExternalOverlayPermissionRequiredException
import dev.mobimapper.mobimapper.activation.LocalActivation
import dev.mobimapper.mobimapper.session.SessionController
import dev.mobimapper.mobimapper.session.SessionService
import dev.mobimapper.mobimapper.game.GameCoordinator
import dev.mobimapper.mobimapper.game.GameCatalog
import dev.mobimapper.mobimapper.game.GameCommands
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
        NativeSessionHost.initialize(this)
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
        LocalActivation.initialize(this)
        dev.mobimapper.mobimapper.backend.BackendRegistry.requestAttach(this)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "dev.mobimapper/control.v1").setMethodCallHandler { call, result ->
            try {
                when(call.method) {
                    "status" -> result.success(SessionController.status(this)+mapOf("uiDisplayId" to (display?.displayId ?: 0),
                        "drawOverlays" to Settings.canDrawOverlays(this)))
                    "activationStatus" -> result.success(LocalActivation.status())
                    "activationStartDiscovery" -> {
                        if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),42)
                        LocalActivation.startDiscovery();result.success(null)
                    }
                    "activationPair" -> { LocalActivation.pair(call.argument("host"),call.argument("port"),requireNotNull(call.argument<String>("code")));result.success(null) }
                    "activationConnect" -> { LocalActivation.connect(call.argument("host"),call.argument("port"));result.success(null) }
                    "activationCancel" -> { LocalActivation.cancel();result.success(null) }
                    "wirelessDebuggingSettings" -> {
                        try { startActivity(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")) }
                        catch(_:android.content.ActivityNotFoundException) {
                            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                            android.widget.Toast.makeText(this,"Abre Depuración inalámbrica",android.widget.Toast.LENGTH_LONG).show()
                        }
                        result.success(null)
                    }
                    "listApps" -> result.success(GameCatalog.list(this))
                    "listProfiles" -> result.success(GameCoordinator.profiles())
                    "selectedProfile" -> result.success(GameCoordinator.selectedForDisplay(display?.displayId ?: 0))
                    "createProfile" -> result.success(GameCoordinator.create(requireNotNull(call.argument<String>("packageName")),requireNotNull(call.argument<String>("name")),display?.displayId ?: 0))
                    "selectProfile" -> result.success(GameCoordinator.select(requireNotNull(call.argument<String>("id"))))
                    "renameProfile" -> result.success(GameCoordinator.rename(requireNotNull(call.argument<String>("id")),requireNotNull(call.argument<String>("name"))))
                    "deleteProfile" -> { GameCoordinator.delete(requireNotNull(call.argument<String>("id"))); result.success(null) }
                    "openEditor" -> { GameCommands.openEditor(requireNotNull(call.argument<String>("id")),display?.displayId ?: 0); result.success(null) }
                    "startProfile" -> { GameCommands.startProfile(requireNotNull(call.argument<String>("id")),display?.displayId ?: 0); result.success(null) }
                    "pause" -> { GameCommands.pause(); result.success(null) }
                    // Retain the command name for older clients; capture now needs an overlay grant.
                    "accessibilitySettings" -> { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))); result.success(null) }
                    "openOverlaySettings" -> {
                        try { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName"))) }
                        catch(_:android.content.ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
                        result.success(null)
                    }
                    "openLab" -> { launchLab(); result.success(null) }
                    "startLab", "probe" -> {
                        check(!LocalActivation.busy) { "Espera a que termine la activación" }
                        GameCoordinator.clearTools()
                        val displayId=display?.displayId ?: 0
                        val labDisplay=requireNotNull(getSystemService(DisplayManager::class.java).getDisplay(displayId)) { "Pantalla no disponible" }
                        val size=Point()
                        @Suppress("DEPRECATION") labDisplay.getRealSize(size)
                        if (call.method == "startLab") SessionController.arm(size.x,size.y,displayId)
                        else check(dev.mobimapper.mobimapper.backend.BackendRegistry.injector != null) { "Activa primero el backend ADB" }
                        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
                        startForegroundService(Intent(this, SessionService::class.java))
                        launchLab(displayId)
                        if (call.method == "probe") SessionController.probe(size.x,size.y,displayId)
                        result.success(null)
                    }
                    "stop" -> { GameCommands.stop(); result.success(null) }
                    "shutdownBackend" -> { SessionController.shutdownBackend(); result.success(null) }
                    else -> result.notImplemented()
                }
            } catch(e: ExternalOverlayPermissionRequiredException) {
                result.error("EXTERNAL_OVERLAY_PERMISSION_REQUIRED",e.message,mapOf("displayId" to e.displayId))
            } catch(e: Exception) { result.error("NATIVE_STATE", e.message, null) }
        }
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==42) dev.mobimapper.mobimapper.activation.ActivationService.instance?.refresh()
    }
    private fun launchLab(displayId:Int=display?.displayId ?: 0) {
        val intent = packageManager.getLaunchIntentForPackage(SessionController.TARGET) ?: error("Instala el APK MobiMapper Input Lab")
        // Reuse the focused task's existing receiver when starting another session.
        // A new Activity would discard the independent observer between recovery and a new press.
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent,ActivityOptions.makeBasic().apply { launchDisplayId=displayId }.toBundle())
    }
}
