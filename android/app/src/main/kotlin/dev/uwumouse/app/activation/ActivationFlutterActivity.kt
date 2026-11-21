package dev.uwumouse.app.activation

import android.Manifest
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.widget.Toast
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

abstract class ActivationFlutterActivity : FlutterActivity() {
    private class PendingActivation(val result: MethodChannel.Result, val action: () -> Unit)
    private var pendingPermission: PendingActivation? = null
    private var requestingPermission = false

    protected fun handleConnectionCall(call: MethodCall, result: MethodChannel.Result): Boolean {
        val otherWindowOwnsActivation = this !is LocalActivationActivity && LocalActivationActivity.isOpen
        when (call.method) {
            "activationStatus" -> {
                val status = LocalActivation.status()
                result.success(if (otherWindowOwnsActivation) status + ("busy" to true) else status)
            }
            "activationStartDiscovery" -> withPermission(result, otherWindowOwnsActivation, requestNotifications = true) {
                LocalActivation.startDiscovery()
            }
            "activationPair" -> withPermission(result, otherWindowOwnsActivation) {
                LocalActivation.pair(call.argument<String>("host"), call.argument<Int>("port"),
                    requireNotNull(call.argument<String>("code")))
            }
            "activationConnect" -> withPermission(result, otherWindowOwnsActivation) {
                LocalActivation.connect(call.argument<String>("host"), call.argument<Int>("port"))
            }
            "activationCancel" -> {
                cancelPendingActivation("Activación cancelada")
                if (!otherWindowOwnsActivation) LocalActivation.cancel()
                result.success(null)
            }
            "wirelessDebuggingSettings" -> {
                try { launchInSeparateTask(Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS")) }
                catch (_: ActivityNotFoundException) {
                    launchInSeparateTask(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                    Toast.makeText(this, "Abre Depuración inalámbrica", Toast.LENGTH_LONG).show()
                }
                result.success(null)
            }
            else -> return false
        }
        return true
    }

    protected fun launchInSeparateTask(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val displayId = display?.displayId ?: Display.DEFAULT_DISPLAY
        val activities = getSystemService(ActivityManager::class.java)
        if (displayId != Display.DEFAULT_DISPLAY && activities.isActivityStartAllowedOnDisplay(this, displayId, intent)) {
            startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle())
        } else startActivity(intent)
    }

    private fun withPermission(result: MethodChannel.Result, otherWindowOwnsActivation: Boolean,
                               requestNotifications: Boolean = false, action: () -> Unit) {
        check(!otherWindowOwnsActivation) { "Continúa en la ventana Activar motor" }
        check(!requestingPermission && pendingPermission == null) {
            "Responde primero a la solicitud de permisos de Android"
        }
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 37 && !ActivationNetworkPermission.granted(this))
            permissions += Manifest.permission.ACCESS_LOCAL_NETWORK
        if (requestNotifications && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permissions += Manifest.permission.POST_NOTIFICATIONS
        val pending = PendingActivation(result, action)
        if (permissions.isEmpty()) { completeActivation(pending); return }
        pendingPermission = pending; requestingPermission = true
        try { requestPermissions(permissions.toTypedArray(), PERMISSION_REQUEST) }
        catch (failure: Exception) { pendingPermission = null; requestingPermission = false; throw failure }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != PERMISSION_REQUEST) return
        requestingPermission = false
        val pending = pendingPermission ?: return
        pendingPermission = null
        if (isFinishing || isDestroyed) {
            pending.result.error("ACTIVATION_CANCELLED", "Vuelve a abrir la activación", null)
            return
        }
        completeActivation(pending)
    }

    private fun completeActivation(pending: PendingActivation) {
        if (!ActivationNetworkPermission.granted(this)) {
            pending.result.error("LOCAL_NETWORK_PERMISSION_REQUIRED",
                "Permite Dispositivos cercanos en UwU Mouse para vincular el motor por red local", null)
            return
        }
        if (this !is LocalActivationActivity && LocalActivationActivity.isOpen) {
            pending.result.error("ACTIVATION_WINDOW_OPEN", "Continúa en la ventana Activar motor", null)
            return
        }
        try { pending.action(); pending.result.success(null) }
        catch (failure: Exception) { pending.result.error("NATIVE_STATE", failure.message, null) }
    }

    private fun cancelPendingActivation(message: String) {
        val pending = pendingPermission ?: return
        pendingPermission = null
        pending.result.error("ACTIVATION_CANCELLED", message, null)
    }

    override fun onDestroy() {
        cancelPendingActivation("Se cerró la pantalla de activación; vuelve a intentarlo")
        super.onDestroy()
    }

    private companion object { const val PERMISSION_REQUEST = 42 }
}
