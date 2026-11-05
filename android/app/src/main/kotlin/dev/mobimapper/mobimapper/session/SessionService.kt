package dev.mobimapper.mobimapper.session

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.IBinder
import dev.mobimapper.mobimapper.MainActivity

class SessionService : Service(), DisplayManager.DisplayListener {
    private var lastGeometry = ""
    private fun displayGeometry(): String {
        val display = getSystemService(DisplayManager::class.java).getDisplay(0) ?: return "missing"
        val size = android.graphics.Point()
        @Suppress("DEPRECATION") display.getRealSize(size)
        return "${display.rotation}:${size.x}:${size.y}"
    }
    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { SessionController.pause("Pantalla apagada o bloqueada") }
    }
    override fun onCreate() {
        super.onCreate()
        dev.mobimapper.mobimapper.game.GameCoordinator.initialize(this)
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel("mapping", "UwU Mouse · sesión", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, SessionService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, "mapping").setSmallIcon(dev.mobimapper.mobimapper.R.drawable.mm_notification)
            .setColor(android.graphics.Color.rgb(42,126,238))
            .setContentTitle("UwU Mouse · sesión local").setContentText("Controles en la burbuja")
            .setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this,dev.mobimapper.mobimapper.R.drawable.mm_close),
                "Detener", stop).build()).build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        registerReceiver(lockReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        getSystemService(DisplayManager::class.java).registerDisplayListener(this, null)
        lastGeometry = displayGeometry()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { dev.mobimapper.mobimapper.game.GameCoordinator.stop(); stopSelf() }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) { if (displayId == 0) SessionController.pause("Pantalla retirada") }
    override fun onDisplayChanged(displayId: Int) {
        if (displayId != 0) return
        val geometry = displayGeometry()
        if (geometry != lastGeometry) dev.mobimapper.mobimapper.game.GameCoordinator.geometryChanged()
        lastGeometry = geometry
    }
    override fun onDestroy() {
        SessionController.pause()
        unregisterReceiver(lockReceiver)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
