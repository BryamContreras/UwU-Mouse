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
import dev.mobimapper.mobimapper.activation.ActivationNetworkPermission
import dev.mobimapper.mobimapper.session.SessionController
import dev.mobimapper.mobimapper.session.SessionService
import dev.mobimapper.mobimapper.game.GameCoordinator
import dev.mobimapper.mobimapper.game.GameCatalog
import dev.mobimapper.mobimapper.game.GameCommands
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var profileFileResult:MethodChannel.Result?=null
    private class PendingActivation(val result:MethodChannel.Result,val action:()->Unit)
    private var pendingActivation:PendingActivation?=null
    private var activationPermissionRequestInFlight=false
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
        cancelPendingActivation("Se cerró la pantalla de activación; vuelve a intentarlo")
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
                        withActivationPermission(result,requestNotifications=true) { LocalActivation.startDiscovery() }
                    }
                    "activationPair" -> {
                        val host=call.argument<String>("host");val port=call.argument<Int>("port")
                        val code=requireNotNull(call.argument<String>("code"))
                        withActivationPermission(result) { LocalActivation.pair(host,port,code) }
                    }
                    "activationConnect" -> {
                        val host=call.argument<String>("host");val port=call.argument<Int>("port")
                        withActivationPermission(result) { LocalActivation.connect(host,port) }
                    }
                    "activationCancel" -> { cancelPendingActivation("Activación cancelada");LocalActivation.cancel();result.success(null) }
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
                    "exportProfileFile", "importProfileFile" -> {
                        check(profileFileResult==null) { "Termina la selección de archivo anterior" }
                        profileFileResult=result
                        val picker=Intent(this,ProfileFileActivity::class.java)
                        if(call.method=="exportProfileFile") picker.putExtra("exportId",requireNotNull(call.argument<String>("id")))
                        @Suppress("DEPRECATION")
                        startActivityForResult(picker,73)
                    }
                    "openEditor" -> { GameCommands.openEditor(requireNotNull(call.argument<String>("id")),display?.displayId ?: 0); result.success(null) }
                    "startProfile" -> { GameCommands.startProfile(requireNotNull(call.argument<String>("id")),display?.displayId ?: 0); result.success(null) }
                    "recoverPendingSession" -> result.success(GameCommands.recoverPendingSession())
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
            } catch(e: Exception) {
                if(profileFileResult===result) profileFileResult=null
                result.error("NATIVE_STATE", e.message, null)
            }
        }
    }
    override fun onRequestPermissionsResult(requestCode:Int,permissions:Array<out String>,grantResults:IntArray) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults)
        if(requestCode==42) {
            activationPermissionRequestInFlight=false
            val pending=pendingActivation ?: return
            pendingActivation=null
            if(isFinishing || isDestroyed) {
                pending.result.error("ACTIVATION_CANCELLED","Vuelve a abrir la activación",null)
                return
            }
            completeActivation(pending)
        }
    }
    private fun withActivationPermission(result:MethodChannel.Result,requestNotifications:Boolean=false,action:()->Unit) {
        check(!activationPermissionRequestInFlight && pendingActivation==null) { "Responde primero a la solicitud de permisos de Android" }
        val permissions=mutableListOf<String>()
        if(android.os.Build.VERSION.SDK_INT>=37 && !ActivationNetworkPermission.granted(this))
            permissions+=Manifest.permission.ACCESS_LOCAL_NETWORK
        if(requestNotifications && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            permissions+=Manifest.permission.POST_NOTIFICATIONS
        val pending=PendingActivation(result,action)
        if(permissions.isEmpty()) { completeActivation(pending);return }
        pendingActivation=pending
        activationPermissionRequestInFlight=true
        try { requestPermissions(permissions.toTypedArray(),42) }
        catch(failure:Exception) { pendingActivation=null;activationPermissionRequestInFlight=false;throw failure }
    }
    private fun completeActivation(pending:PendingActivation) {
        if(!ActivationNetworkPermission.granted(this)) {
            pending.result.error("LOCAL_NETWORK_PERMISSION_REQUIRED",
                "Permite Dispositivos cercanos en UwU Mouse para vincular el motor por red local",null)
            return
        }
        try { pending.action();pending.result.success(null) }
        catch(failure:Exception) { pending.result.error("NATIVE_STATE",failure.message,null) }
    }
    private fun cancelPendingActivation(message:String) {
        val pending=pendingActivation ?: return
        pendingActivation=null
        pending.result.error("ACTIVATION_CANCELLED",message,null)
    }
    @Deprecated("Android activity result bridge")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=73) return
        val callback=profileFileResult;profileFileResult=null
        val error=data?.getStringExtra("error")
        if(error!=null) callback?.error("PROFILE_FILE",error,null) else callback?.success(resultCode==RESULT_OK)
    }
    private fun launchLab(displayId:Int=display?.displayId ?: 0) {
        val intent = packageManager.getLaunchIntentForPackage(SessionController.TARGET) ?: error("Instala el APK MobiMapper Input Lab")
        // Reuse the focused task's existing receiver when starting another session.
        // A new Activity would discard the independent observer between recovery and a new press.
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent,ActivityOptions.makeBasic().apply { launchDisplayId=displayId }.toBundle())
    }
}
