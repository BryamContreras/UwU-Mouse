package dev.mobimapper.mobimapper.backend

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Base64
import android.util.Log
import dev.mobimapper.mobimapper.session.SessionController
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

object BackendRegistry {
    private const val PREFERENCES="mobimapper_backend_v2"
    @Volatile var injector: IInjector? = null
    private var attachInFlight=false
    fun requestAttach(context:Context) {
        synchronized(this) {
            if(injector?.asBinder()?.isBinderAlive==true || attachInFlight) return
            attachInFlight=true
        }
        val appContext=context.applicationContext
        Thread({
            try {
                val preferences=appContext.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE)
                val port=preferences.getInt("port",0)
                val encoded=preferences.getString("nonce",null)
                if(port !in 1..65535 || encoded==null) return@Thread
                val nonce=Base64.decode(encoded,Base64.NO_WRAP)
                if(nonce.size!=32) return@Thread
                Socket().use { socket ->
                    socket.soTimeout=1500
                    socket.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"),port),1500)
                    val output=DataOutputStream(socket.getOutputStream())
                    output.writeInt(0x4d4d5232); output.write(nonce); output.writeByte(1); output.flush()
                    if(socket.getInputStream().read()!=1) Log.w("MobiBackend","reattach request was not accepted")
                }
            } catch(_:Exception) {
                // The capability is private and must never be exposed in diagnostics/logs.
                Log.w("MobiBackend","reattach endpoint unavailable")
            } finally {
                synchronized(this) { attachInFlight=false }
            }
        },"MobiBindRequest").apply { isDaemon=true; start() }
    }
    /** Called only by BackendProvider after it authenticates the shell/root caller. */
    fun persistReattachConfiguration(context:Context,port:Int,nonce:ByteArray) {
        require(port in 1..65535 && nonce.size==32)
        val identity=Binder.clearCallingIdentity()
        try {
            check(context.getSharedPreferences(PREFERENCES,Context.MODE_PRIVATE).edit()
                .putInt("port",port).putString("nonce",Base64.encodeToString(nonce,Base64.NO_WRAP)).commit()) {
                "Could not save private backend connection"
            }
        } finally { Binder.restoreCallingIdentity(identity) }
    }
}

/** Only the already authorized ADB shell (or existing root) can register a backend. */
class BackendProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == 2000 || Binder.getCallingUid() == 0) { "ADB shell required" }
        if (method == "diagnostics") {
            val identity = Binder.clearCallingIdentity()
            return try { Bundle().apply { putString("json", org.json.JSONObject(SessionController.status(requireNotNull(context))).toString()) } }
            finally { Binder.restoreCallingIdentity(identity) }
        }
        if (method == "profiles") {
            val identity = Binder.clearCallingIdentity()
            return try {
                dev.mobimapper.mobimapper.game.GameCoordinator.initialize(requireNotNull(context))
                val profiles = dev.mobimapper.mobimapper.game.GameCoordinator.profiles()
                val selected = dev.mobimapper.mobimapper.game.GameCoordinator.selected()
                Bundle().apply {
                    putString("json", org.json.JSONObject().put("schemaVersion", dev.mobimapper.mobimapper.profile.GameProfile.SCHEMA_VERSION)
                        .put("profiles", org.json.JSONArray(profiles))
                        .put("selected", org.json.JSONObject.wrap(selected)).toString())
                }
            } finally { Binder.restoreCallingIdentity(identity) }
        }
        if (method == "profilesImport") {
            // Caller UID was checked above, before decoding or dropping its identity.
            val document=dev.mobimapper.mobimapper.profile.ProfileCodec.decodeImportPayload(requireNotNull(arg) { "Documento Base64 requerido" })
            val selectImported=if(extras?.containsKey("selectImported")==true)
                extras.get("selectImported") as? Boolean ?: throw IllegalArgumentException("selectImported necesita verdadero o falso") else false
            val identity=Binder.clearCallingIdentity()
            return try {
                val task=FutureTask<Map<String,Any?>> {
                    dev.mobimapper.mobimapper.game.GameCoordinator.initialize(requireNotNull(context))
                    dev.mobimapper.mobimapper.game.GameCoordinator.importProfileDocument(document,selectImported)
                }
                if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()) task.run()
                else check(android.os.Handler(android.os.Looper.getMainLooper()).post(task)) { "Main thread unavailable" }
                val imported=try { task.get(5,TimeUnit.SECONDS) } catch(failure:Exception) {
                    task.cancel(false);throw failure
                }
                Bundle().apply { putBoolean("accepted",true);putString("json",org.json.JSONObject(imported).toString()) }
            } finally { Binder.restoreCallingIdentity(identity) }
        }
        if(method in setOf("labProbe","labStop","labFault","labRecreateUi","labStale","labCursor","labMapped","labMacro","labActions","cameraTrial","profileCameraMode")) {
            val identity=Binder.clearCallingIdentity()
            return try {
                when(method) {
                    "labProbe" -> SessionController.testGesture(requireNotNull(context),extras?.getInt("count",3) ?:3,extras?.getInt("hold",4000) ?:4000,extras?.getBoolean("terminal",false) ?:false)
                    "labStop" -> SessionController.pause("Parada de prueba")
                    "labCursor" -> SessionController.testCursorLab(requireNotNull(context))
                    "labMacro" -> SessionController.testMacroLab(requireNotNull(context),extras?.getBoolean("automated",false) ?:false)
                    "labActions" -> SessionController.testActionLab(requireNotNull(context))
                    "labMapped" -> SessionController.testMappedLab(requireNotNull(context),extras?.getBoolean("automated",false) ?:false,extras?.getBoolean("hold",false) ?:false,extras?.getBoolean("precision",false) ?:false)
                    "cameraTrial" -> SessionController.configureCameraTrial(requireNotNull(extras?.getString("strategy")))
                    "profileCameraMode" -> {
                        val mode=requireNotNull(extras?.getString("mode"))
                        val task=FutureTask<Any?> {
                            dev.mobimapper.mobimapper.game.GameCoordinator.initialize(requireNotNull(context))
                            dev.mobimapper.mobimapper.game.GameCoordinator.setSelectedCameraMode(mode)
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
                        val activity=requireNotNull(dev.mobimapper.mobimapper.MainActivity.latest.get()) { "No Flutter Activity exists" }
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
        return Bundle().apply { putInt("protocol", 4) }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw SecurityException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw SecurityException()
}
