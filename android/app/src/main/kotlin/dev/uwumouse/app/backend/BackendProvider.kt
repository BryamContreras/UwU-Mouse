package dev.uwumouse.app.backend

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import dev.uwumouse.app.session.SessionController
import dev.uwumouse.app.session.InputLatencyProbe
import dev.uwumouse.app.diagnostics.GameDiagnostics
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Only the already authorized ADB shell (or existing root) can register a backend. */
class BackendProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == 2000 || Binder.getCallingUid() == 0) { "ADB shell required" }
        if (method == "cameraResetPipeProbe") {
            val identity = Binder.clearCallingIdentity()
            return try {
                dev.uwumouse.app.diagnostics.CameraResetPipeDiagnostics.call(requireNotNull(context), extras)
            } finally { Binder.restoreCallingIdentity(identity) }
        }
        if (method in GameDiagnostics.methods) {
            val identity = Binder.clearCallingIdentity()
            return try { GameDiagnostics.call(requireNotNull(context), method, arg, extras) }
            finally { Binder.restoreCallingIdentity(identity) }
        }
        if (method in setOf("latencyStart", "latencyStop", "latencySnapshot")) {
            // Same shell/root authorization as diagnostics, before clearing caller identity.
            val identity = Binder.clearCallingIdentity()
            return try {
                val result = when (method) {
                    "latencyStart" -> InputLatencyProbe.start(
                        extras?.getInt("durationMs", 30_000) ?: 30_000, android.os.Process.myPid())
                    "latencyStop" -> InputLatencyProbe.stop()
                    else -> InputLatencyProbe.snapshot(
                        extras?.getInt("offset", 0) ?: 0, extras?.getInt("limit", 512) ?: 512)
                }
                Bundle().apply { putString("json", org.json.JSONObject(result).toString()) }
            } finally { Binder.restoreCallingIdentity(identity) }
        }
        if (method == "diagnostics") {
            val identity = Binder.clearCallingIdentity()
            return try { Bundle().apply { putString("json", org.json.JSONObject(SessionController.status(requireNotNull(context))).toString()) } }
            finally { Binder.restoreCallingIdentity(identity) }
        }
        if(method=="activationStatus") {
            val identity=Binder.clearCallingIdentity()
            return try {
                dev.uwumouse.app.activation.LocalActivation.initialize(requireNotNull(context))
                Bundle().apply { putString("json",org.json.JSONObject(dev.uwumouse.app.activation.LocalActivation.status()).toString()) }
            } finally { Binder.restoreCallingIdentity(identity) }
        }
        if(method=="nativeCaptureRate") {
            // Bounded diagnostics use the same generation-checked runtime operation.
            val rate=requireNotNull(arg).toInt()
            require(rate in 0..1000)
            val identity=Binder.clearCallingIdentity()
            return try {
                val backend=requireNotNull(BackendRegistry.injector)
                val capture=backend.captureStatus()
                val captureId=capture.getLong("captureId")
                check(capture.getBoolean("running") && capture.getBoolean("captureMouse") && captureId>0L)
                check(backend.setCaptureMouseRate(captureId,rate)) { "No se pudo configurar la frecuencia nativa" }
                Bundle().apply { putBoolean("accepted",true);putLong("captureId",captureId);putInt("mouseRateHz",rate) }
            } finally { Binder.restoreCallingIdentity(identity) }
        }
        if (method == "profiles") {
            val identity = Binder.clearCallingIdentity()
            return try {
                dev.uwumouse.app.game.GameCoordinator.initialize(requireNotNull(context))
                val profiles = dev.uwumouse.app.game.GameCoordinator.profiles()
                val selected = dev.uwumouse.app.game.GameCoordinator.selected()
                Bundle().apply {
                    putString("json", org.json.JSONObject().put("schemaVersion", dev.uwumouse.app.profile.GameProfile.SCHEMA_VERSION)
                        .put("profiles", org.json.JSONArray(profiles))
                        .put("selected", org.json.JSONObject.wrap(selected)).toString())
                }
            } finally { Binder.restoreCallingIdentity(identity) }
        }
        if (method == "profilesImport") {
            // Caller UID was checked above, before decoding or dropping its identity.
            val document=dev.uwumouse.app.profile.ProfileCodec.decodeImportPayload(requireNotNull(arg) { "Documento Base64 requerido" })
            val selectImported=if(extras?.containsKey("selectImported")==true)
                extras.get("selectImported") as? Boolean ?: throw IllegalArgumentException("selectImported necesita verdadero o falso") else false
            val identity=Binder.clearCallingIdentity()
            return try {
                val task=FutureTask<Map<String,Any?>> {
                    dev.uwumouse.app.game.GameCoordinator.initialize(requireNotNull(context))
                    dev.uwumouse.app.game.GameCoordinator.importProfileDocument(document,selectImported)
                }
                if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) task.run()
                else check(android.os.Handler(android.os.Looper.getMainLooper()).post(task)) { "Main thread unavailable" }
                val imported=try { task.get(5,TimeUnit.SECONDS) } catch(failure:Exception) {
                    task.cancel(false);throw failure
                }
                Bundle().apply { putBoolean("accepted",true);putString("json",org.json.JSONObject(imported).toString()) }
            } finally { Binder.restoreCallingIdentity(identity) }
        }
        if(method in setOf("labPrepare","labProbe","labStop","labFault","labRecreateUi","labStale","labCursor","labMapped","labMacro","labActions","labCamera","cameraTrial","profileCameraMode")) {
            val identity=Binder.clearCallingIdentity()
            return try {
                when(method) {
                    "labPrepare" -> {
                        val task=FutureTask<Unit> {
                            dev.uwumouse.app.runtime.NativeSessionHost.initialize(requireNotNull(context))
                            SessionController.useLaboratory(extras?.getInt("displayId",0) ?: 0)
                            dev.uwumouse.app.runtime.NativeSessionHost.instance?.observeContextFor(5000)
                        }
                        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) task.run()
                        else check(android.os.Handler(android.os.Looper.getMainLooper()).post(task))
                        task.get(5,TimeUnit.SECONDS)
                    }
                    "labProbe" -> SessionController.testGesture(requireNotNull(context),extras?.getInt("count",3) ?:3,extras?.getInt("hold",4000) ?:4000,extras?.getBoolean("terminal",false) ?:false)
                    "labStop" -> SessionController.pause("Parada de prueba")
                    "labCursor" -> SessionController.testCursorLab(requireNotNull(context))
                    "labMacro" -> SessionController.testMacroLab(requireNotNull(context),extras?.getBoolean("automated",false) ?:false)
                    "labActions" -> SessionController.testActionLab(requireNotNull(context),extras?.getInt("displayId",0) ?: 0)
                    "labCamera" -> SessionController.testCameraLab(requireNotNull(context),extras?.getInt("displayId",0) ?: 0)
                    "labMapped" -> SessionController.testMappedLab(requireNotNull(context),extras?.getBoolean("automated",false) ?:false,extras?.getBoolean("hold",false) ?:false,extras?.getBoolean("precision",false) ?:false)
                    "cameraTrial" -> SessionController.configureCameraTrial(requireNotNull(extras?.getString("strategy")))
                    "profileCameraMode" -> {
                        val mode=requireNotNull(extras?.getString("mode"))
                        val task=FutureTask<Any?> {
                            dev.uwumouse.app.game.GameCoordinator.initialize(requireNotNull(context))
                            dev.uwumouse.app.game.GameCoordinator.setSelectedCameraMode(mode)
                        }
                        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) task.run()
                        else check(android.os.Handler(android.os.Looper.getMainLooper()).post(task)) { "Main thread unavailable" }
                        val selected=try { task.get(5,TimeUnit.SECONDS) } catch(failure:Exception) {
                            task.cancel(false); throw failure
                        }
                        return Bundle().apply {
                            putBoolean("accepted",true)
                            putString("json",org.json.JSONObject.wrap(selected)?.toString() ?: "null")
                        }
                    }
                    "labFault" -> requireNotNull(BackendRegistry.injector).armTestFault(requireNotNull(extras).getInt("action"),extras.getInt("stage"))
                    "labRecreateUi" -> {
                        val activity=requireNotNull(dev.uwumouse.app.MainActivity.latest.get()) { "No Flutter Activity exists" }
                        check(!activity.isDestroyed && !activity.isFinishing) { "Flutter Activity already destroyed" }
                        android.os.Handler(android.os.Looper.getMainLooper()).post { activity.recreate() }
                    }
                    "labStale" -> return Bundle().apply { SessionController.testStaleWork().forEach { (k,v)->putBoolean(k,v) } }
                }
                Bundle().apply { putBoolean("accepted",true) }
            } finally { Binder.restoreCallingIdentity(identity) }
        }
        require(method == "attach")
        val configuration=requireNotNull(extras)
        require(configuration.getInt("protocol") == 4) { "Backend protocol mismatch" }
        val binder = requireNotNull(configuration.getBinder("backend"))
        val reattachPort=configuration.getInt("reattachPort",0)
        val reattachNonce=requireNotNull(configuration.getByteArray("reattachNonce")) { "Backend connection capability missing" }
        require(reattachPort in 1..65535 && reattachNonce.size==32)
        synchronized(BackendRegistry) {
            val current=BackendRegistry.injector?.asBinder()
            check(current==binder || current?.isBinderAlive != true) { "Backend already running; stop it before updating" }
            if(current!=binder) {
                binder.linkToDeath({
                    synchronized(BackendRegistry) {
                        if (BackendRegistry.injector?.asBinder() == binder) {
                            BackendRegistry.injector = null
                            SessionController.backendDied()
                        }
                    }
                }, 0)
            }
            check(binder.isBinderAlive) { "Backend disconnected during attach" }
            BackendRegistry.persistReattachConfiguration(requireNotNull(context),reattachPort,reattachNonce)
            BackendRegistry.injector = IInjector.Stub.asInterface(binder)
        }
        SessionController.backendAvailable()
        dev.uwumouse.app.runtime.NativeSessionHost.initialize(requireNotNull(context))
        return Bundle().apply { putInt("protocol", 4) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException()
}
