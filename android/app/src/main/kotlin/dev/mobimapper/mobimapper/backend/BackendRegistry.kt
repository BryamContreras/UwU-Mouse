package dev.mobimapper.mobimapper.backend

import android.content.Context
import android.os.Bundle
import android.os.Binder
import android.util.Base64
import android.util.Log
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

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
