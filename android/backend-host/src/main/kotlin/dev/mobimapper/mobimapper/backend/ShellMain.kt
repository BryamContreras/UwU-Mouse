package dev.mobimapper.mobimapper.backend

import android.content.Context
import android.content.ComponentName
import android.content.AttributionSource
import android.content.pm.PackageManager
import android.os.*
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import java.lang.reflect.Method
import kotlin.system.exitProcess

/** Runs in app_process under shell, never in the ordinary app's UID. */
object ShellMain {
    private const val TAG = "MobiMapperShell"
    private const val SHELL_PACKAGE = "com.android.shell"
    @JvmStatic fun main(args: Array<String>) {
        var phase = "identity"
        try {
        check(Process.myUid() == 2000 || Process.myUid() == 0)
        require(args.size<=1)
        val identity=BackendIdentity(args.firstOrNull() ?: "dev.mobimapper.mobimapper")
        Log.i(TAG, "bootstrap uid=${Process.myUid()} pid=${Process.myPid()} protocol=4")
        phase = "context"
        Looper.prepareMainLooper()
        val threadClass = Class.forName("android.app.ActivityThread")
        val thread = threadClass.getMethod("systemMain").invoke(null)
        val system = threadClass.getMethod("getSystemContext").invoke(thread) as Context
        val context = system.createPackageContext(SHELL_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        // A system-created Context may inherit the system container's op package (android).
        // Match attribution to the actual, already authorized shell UID before provider acquisition.
        phase = "shell-attribution"
        val source = AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
        writeFieldIfPresent(context, "mOpPackageName", SHELL_PACKAGE)
        writeFieldIfPresent(context, "mAttributionSource", source)
        check(context.opPackageName == SHELL_PACKAGE && context.attributionSource.packageName == SHELL_PACKAGE &&
            context.attributionSource.uid == Process.myUid()) { "Shell context attribution could not be established" }
        Log.i(TAG, "context package=${context.packageName} opPackage=${context.opPackageName} attributionUid=${context.attributionSource.uid}")
        phase = "injector"
        val backend = SupervisedInjector(context,identity)
        val extras = Bundle().apply {
            putInt("protocol", 4); putBinder("backend", backend)
            backend.addReattachConfiguration(this)
        }
        phase = "provider-external-attach"
        val reply = attachExternalProvider(source, extras, identity.authority)
        check(reply?.getInt("protocol") == 4) { "Backend provider protocol mismatch" }
        Log.i(TAG, "backend ready providerProtocol=4 uid=${Process.myUid()}")
        println("MobiMapper backend ready uid=${Process.myUid()} protocol=4")
        phase = "binder-loop"
        backend.startReattachServer { attachExternalProvider(source, extras, identity.authority) }; Looper.loop()
        } catch (failure: Throwable) {
            Log.e(TAG, "bootstrap failed phase=$phase uid=${Process.myUid()} pid=${Process.myPid()}", failure)
            failure.printStackTrace()
            exitProcess(1)
        }
    }
    /**
     * A shell app_process is not an ordinary registered application process. Use the external
     * provider route used by AOSP Android 14's `content` command, with a bounded acquisition token.
     */
    private fun attachExternalProvider(source: AttributionSource, extras: Bundle, authority:String): Bundle? {
        val activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val managerInterface = Class.forName("android.app.IActivityManager")
        val acquire = managerInterface.getMethod("getContentProviderExternal", String::class.java,
            Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
        val release = managerInterface.getMethod("removeContentProviderExternal", String::class.java, IBinder::class.java)
        val call = Class.forName("android.content.IContentProvider").getMethod("call", AttributionSource::class.java,
            String::class.java, String::class.java, String::class.java, Bundle::class.java)
        val acquisition = Binder()
        var acquired = false
        try {
            val holder = acquire.invoke(activityManager, authority, 0, acquisition, "MobiMapper")
                ?: error("Backend provider not found for user 0")
            acquired = true
            val provider = holder.javaClass.getField("provider").get(holder)
                ?: error("Backend provider has no published Binder")
            Log.i(TAG, "external provider acquired user=0 protocol=4")
            return call.invoke(provider, source, authority, "attach", null, extras) as? Bundle
        } finally {
            if (acquired) {
                try { release.invoke(activityManager, authority, acquisition) }
                catch (failure: Exception) { Log.e(TAG, "External provider acquisition cleanup failed", failure) }
            }
        }
    }
    /** Bootstrapping only; Android 14 ContextImpl retains the system container's attribution. */
    private fun writeFieldIfPresent(target: Any, name: String, value: Any): Boolean {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            val field = try { type.getDeclaredField(name) } catch (_: NoSuchFieldException) { type = type.superclass; continue }
            field.isAccessible = true
            field.set(target, value)
            return true
        }
        return false
    }
}

