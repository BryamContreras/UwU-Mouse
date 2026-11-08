package dev.mobimapper.mobimapper.session

import dev.mobimapper.mobimapper.runtime.NativeSessionHost

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.IBinder
import dev.mobimapper.mobimapper.MainActivity

class SessionService : Service(), DisplayManager.DisplayListener {
    private var lastGeometry = ""
    private fun displayGeometry(): String {
        val id=SessionController.targetDisplayId
        val display = getSystemService(DisplayManager::class.java).getDisplay(id) ?: return "$id:missing"
        val size = android.graphics.Point()
        @Suppress("DEPRECATION") display.getRealSize(size)
        val metrics=android.util.DisplayMetrics()
        @Suppress("DEPRECATION") display.getRealMetrics(metrics)
        return "$id:${display.state}:${display.rotation}:${size.x}:${size.y}:${metrics.densityDpi}"
    }
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id=SessionController.targetDisplayId
            val target=getSystemService(DisplayManager::class.java).getDisplay(id)
            val locked=getSystemService(KeyguardManager::class.java).isKeyguardLocked
            if(id==0 || locked || target?.state!=android.view.Display.STATE_ON) {
                SessionController.pause("Pantalla apagada o bloqueada")
                SessionController.targetChanged(false)
                NativeSessionHost.instance?.captureMouse(false)
            } else NativeSessionHost.instance?.refreshContext()
        }
    }
    override fun onCreate() {
        super.onCreate()
        NativeSessionHost.initialize(this)
        dev.mobimapper.mobimapper.game.GameCoordinator.initialize(this)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("mapping", "UwU Mouse · sesión", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, SessionService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "mapping").setSmallIcon(dev.mobimapper.presentation.R.drawable.mm_notification)
            .setColor(android.graphics.Color.rgb(42,126,238))
            .setContentTitle("UwU Mouse · sesión local").setContentText("Controles en la burbuja")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this,dev.mobimapper.presentation.R.drawable.mm_close),
                "Detener", stop).build()).build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        registerReceiver(lockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        getSystemService(DisplayManager::class.java).registerDisplayListener(this, null)
        lastGeometry = displayGeometry()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastGeometry=displayGeometry()
        if (intent?.action == "stop") { dev.mobimapper.mobimapper.game.GameCoordinator.stop(); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDisplayAdded(displayId: Int) {
        dev.mobimapper.mobimapper.runtime.NativeSessionHost.instance?.refreshContext()
        if(SessionController.active) SessionController.pause("Cambió la conexión de pantallas; reanuda manualmente")
    }
    override fun onDisplayRemoved(displayId: Int) {
        if (displayId == SessionController.targetDisplayId)
            dev.mobimapper.mobimapper.game.GameCoordinator.displayUnavailable(displayId)
        else dev.mobimapper.mobimapper.runtime.NativeSessionHost.instance?.refreshContext()
    }
    override fun onDisplayChanged(displayId: Int) {
        if (displayId != SessionController.targetDisplayId) return
        val geometry = displayGeometry()
        if (geometry != lastGeometry) dev.mobimapper.mobimapper.game.GameCoordinator.geometryChanged()
        lastGeometry = geometry
        if(getSystemService(DisplayManager::class.java).getDisplay(displayId)?.state!=android.view.Display.STATE_ON) {
            // Cancel pending arms/diagnostics too, even when no contact has started yet.
            SessionController.pause("Pantalla apagada o desconectada; inicia manualmente")
            SessionController.targetChanged(false)
            NativeSessionHost.instance?.captureMouse(false)
        }
        NativeSessionHost.instance?.refreshContext()
    }
    override fun onDestroy() {
        SessionController.sessionServiceDestroyed()
        unregisterReceiver(lockReceiver)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
