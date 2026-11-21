package dev.uwumouse.app

import android.Manifest
import android.app.ActivityOptions
import android.graphics.Point
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import dev.uwumouse.app.runtime.NativeSessionHost
import dev.uwumouse.app.editor.ExternalOverlayPermissionRequiredException
import dev.uwumouse.app.activation.LocalActivation
import dev.uwumouse.app.activation.ActivationFlutterActivity
import dev.uwumouse.app.activation.LocalActivationActivity
import dev.uwumouse.app.session.SessionController
import dev.uwumouse.app.session.SessionService
import dev.uwumouse.app.game.GameCoordinator
import dev.uwumouse.app.game.GameCatalog
import dev.uwumouse.app.game.GameCommands
import dev.uwumouse.app.backend.BackendRegistry
import dev.uwumouse.app.backend.BackendStatusReader
import dev.uwumouse.app.backend.BackendStatusSnapshot
import dev.uwumouse.app.backend.SharedStatusRead
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : ActivationFlutterActivity() {
    override fun onResume() {
        super.onResume()
        // A desktop display may have disconnected while the session service was stopped.
        dev.uwumouse.app.runtime.TargetDisplayGeometry.clear()
    }
    private var pendingStatusResult:MethodChannel.Result?=null
    private var pendingStatusToken:Any?=null
    private var statusSubscription:SharedStatusRead.Subscription?=null
    private var profileFileResult:MethodChannel.Result?=null
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
        android.util.Log.i("UwuActivity","created instance=${System.identityHashCode(this)} pid=${android.os.Process.myPid()} uptime=${android.os.SystemClock.uptimeMillis()}")
    }
    override fun onDestroy() {
        statusSubscription?.cancel();statusSubscription=null
        pendingStatusToken=null
        val statusResult=pendingStatusResult;pendingStatusResult=null
        statusResult?.error("ACTIVITY_DESTROYED","La pantalla se cerró durante la consulta",null)
        destroyedCount++
        android.util.Log.i("UwuActivity","destroyed instance=${System.identityHashCode(this)} pid=${android.os.Process.myPid()} uptime=${android.os.SystemClock.uptimeMillis()}")
        super.onDestroy()
    }
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        GameCoordinator.initialize(this)
        LocalActivation.initialize(this)
        dev.uwumouse.app.backend.BackendRegistry.requestAttach(this)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, "dev.uwumouse/control.v1").setMethodCallHandler { call, result ->
            try {
                if (handleConnectionCall(call, result)) return@setMethodCallHandler
                when(call.method) {
                    "cursorStyle" -> result.success(CursorStylePreferences.get(this))
                    "setCursorStyle" -> {
                        val style=requireNotNull(call.argument<String>("style"))
                        CursorStylePreferences.set(this, style)
                        NativeSessionHost.instance?.updateCursorStyle(style)
                        result.success(null)
                    }
                    "status" -> requestStatus(result)
                    "openActivationWindow" -> {
                        launchInSeparateTask(Intent(this,LocalActivationActivity::class.java))
                        result.success(null)
                    }
                    "listApps" -> result.success(GameCatalog.list(this))
                    "listProfiles" -> result.success(GameCoordinator.profiles())
                    "selectedProfile" -> result.success(GameCoordinator.selectedForDisplay(display?.displayId ?: 0))
                    "createProfile" -> result.success(GameCoordinator.create(requireNotNull(call.argument<String>("packageName")),requireNotNull(call.argument<String>("name")),display?.displayId ?: 0))
                    "selectProfile" -> result.success(GameCoordinator.select(requireNotNull(call.argument<String>("id"))))
                    "renameProfile" -> result.success(GameCoordinator.rename(requireNotNull(call.argument<String>("id")),requireNotNull(call.argument<String>("name"))))
                    "duplicateProfile" -> result.success(GameCoordinator.duplicate(requireNotNull(call.argument<String>("id"))))
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
                        val geometry=dev.uwumouse.app.runtime.TargetDisplayGeometry.read(this,displayId)
                        val size=Point(geometry.width,geometry.height)
                        if (call.method == "startLab") SessionController.arm(size.x,size.y,displayId)
                        else check(dev.uwumouse.app.backend.BackendRegistry.injector != null) { "Activa primero el backend ADB" }
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
    private fun requestStatus(result:MethodChannel.Result) {
        if(pendingStatusResult!=null) {
            result.error("STATUS_IN_PROGRESS","Ya hay una consulta de estado pendiente",null)
            return
        }
        val binder=BackendRegistry.injector?.asBinder()
        if(binder==null) { result.success(uiStatus(null));return }
        val identity=SessionController.statusIdentity()
        val token=Any()
        pendingStatusResult=result;pendingStatusToken=token
        val activity=java.lang.ref.WeakReference(this)
        val subscription=BackendStatusReader.request(binder) { outcome ->
            activity.get()?.completeStatus(token,binder,identity,outcome)
        }
        if(subscription==null) {
            pendingStatusResult=null;pendingStatusToken=null
            result.error("STATUS_IN_PROGRESS","El motor anterior aún está respondiendo",null)
        } else statusSubscription=subscription
    }
    private fun completeStatus(token:Any,binder:android.os.IBinder,identity:SessionController.StatusIdentity,
                               outcome:Result<BackendStatusSnapshot>) {
        if(pendingStatusToken!==token) return
        val result=pendingStatusResult ?: return
        pendingStatusResult=null;pendingStatusToken=null;statusSubscription=null
        if(isDestroyed || isFinishing) {
            result.error("ACTIVITY_DESTROYED","La pantalla se cerró durante la consulta",null)
            return
        }
        val snapshot=outcome.getOrNull()
        if(BackendRegistry.injector?.asBinder()!==binder || SessionController.statusIdentity()!=identity ||
            (snapshot!=null && !snapshot.isCurrent(identity,SessionController.statusIdentity()))) {
            result.error("STATUS_STALE","La sesión cambió durante la consulta; actualiza su estado",null)
            return
        }
        try { result.success(uiStatus(snapshot?.value)) }
        catch(failure:Exception) { result.error("NATIVE_STATE",failure.message,null) }
    }
    private fun uiStatus(backend:android.os.Bundle?):Map<String,Any?> =
        SessionController.status(this,backend)+mapOf("uiDisplayId" to (display?.displayId ?: 0),
            "drawOverlays" to Settings.canDrawOverlays(this))
    @Deprecated("Android activity result bridge")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode!=73) return
        val callback=profileFileResult;profileFileResult=null
        val error=data?.getStringExtra("error")
        if(error!=null) callback?.error("PROFILE_FILE",error,null) else callback?.success(resultCode==RESULT_OK)
    }
    private fun launchLab(displayId:Int=display?.displayId ?: 0) {
        val intent = packageManager.getLaunchIntentForPackage(SessionController.TARGET) ?: error("Instala el APK UwUMouse Input Lab")
        // Reuse the focused task's existing receiver when starting another session.
        // A new Activity would discard the independent observer between recovery and a new press.
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent,ActivityOptions.makeBasic().apply { launchDisplayId=displayId }.toBundle())
    }
}
