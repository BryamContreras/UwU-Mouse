package dev.uwumouse.app.activation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import dev.uwumouse.app.MainActivity
import dev.uwumouse.presentation.R

/** Keeps pairing available while Android's short-lived code dialog remains on screen. */
class ActivationService:Service() {
    companion object { var instance:ActivationService?=null;private set
        const val CODE="pairing_code"
        const val CHANNEL="local_activation"
        const val NOTIFICATION=34
    }
    override fun onCreate() {
        super.onCreate();instance=this;LocalActivation.initialize(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,"UwU Mouse · activación",NotificationManager.IMPORTANCE_HIGH))
        startForeground(NOTIFICATION,notification(),ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }
    private fun notification():Notification {
        val state=LocalActivation.status()
        val open=PendingIntent.getActivity(this,34,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val code=PendingIntent.getBroadcast(this,34,Intent(this,ActivationReplyReceiver::class.java).setAction("pair"),PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val cancel=PendingIntent.getBroadcast(this,35,Intent(this,ActivationReplyReceiver::class.java).setAction("cancel"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder=Notification.Builder(this,CHANNEL).setSmallIcon(R.drawable.mm_notification).setColor(Color.rgb(42,126,238))
            .setContentTitle("UwU Mouse · activar sin PC").setContentText(state["message"] as String)
            .setStyle(Notification.BigTextStyle().bigText(state["message"] as String))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this,R.drawable.mm_close),"Cancelar",cancel).build())
        if(state["busy"]!=true) builder.addAction(Notification.Action.Builder(
            Icon.createWithResource(this,R.drawable.mm_notification),"Introducir código",code)
            .addRemoteInput(RemoteInput.Builder(CODE).setLabel("Código de 6 dígitos").build())
            .setAllowGeneratedReplies(false).build())
        return builder.build()
    }
    fun refresh() { getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification()) }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int)=START_NOT_STICKY
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onDestroy() { instance=null;LocalActivation.serviceStopped();stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy() }
}

class ActivationReplyReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        LocalActivation.initialize(context)
        if(intent.action=="cancel") { LocalActivation.cancel();return }
        if(intent.action!="pair") return
        val code=RemoteInput.getResultsFromIntent(intent)?.getCharSequence(ActivationService.CODE)?.toString() ?: return
        // The code is used in memory only; never stored in preferences, logs or diagnostics.
        Handler(Looper.getMainLooper()).post {
            runCatching { LocalActivation.pair(null,null,code) }.onFailure {
                android.widget.Toast.makeText(context,it.message ?: "Comprueba el código y el puerto",android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}
