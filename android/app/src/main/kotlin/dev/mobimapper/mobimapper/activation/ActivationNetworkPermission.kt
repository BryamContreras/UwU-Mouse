package dev.mobimapper.mobimapper.activation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Android 17 gates both NSD discovery and direct LAN sockets behind a runtime grant. */
object ActivationNetworkPermission {
    fun required(context:Context):Boolean = Build.VERSION.SDK_INT>=37 && context.applicationInfo.targetSdkVersion>=37
    fun granted(context:Context):Boolean = if(Build.VERSION.SDK_INT>=37 && context.applicationInfo.targetSdkVersion>=37)
        context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)==PackageManager.PERMISSION_GRANTED else true
    fun requireGranted(context:Context) {
        check(granted(context)) { "Permite Dispositivos cercanos en UwU Mouse para vincular el motor por red local" }
    }
}
