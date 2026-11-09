package dev.uwumouse.app.editor

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/** Controls the system interruption filter without changing the user's notification policy. */
class DoNotDisturbController internal constructor(private val backend: DoNotDisturbBackend) {
    constructor(context: Context) : this(AndroidDoNotDisturbBackend(context.applicationContext))

    val hasAccess: Boolean get() = backend.hasAccess
    val isEnabled: Boolean get() {
        // Take one snapshot: Android can change the global filter between calls.
        val filter = backend.filter
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /** Returns whether Android accepted the request; the resulting system state may change asynchronously. */
    fun setEnabled(enabled: Boolean): Boolean {
        if (!hasAccess) return false
        return try {
            backend.setFilter(
                if (enabled) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                else NotificationManager.INTERRUPTION_FILTER_ALL
            )
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    fun requestAccess(): Boolean = try {
        backend.openSettings()
        true
    } catch (_: RuntimeException) {
        false
    }

    /** Observes system changes on the main thread. The caller owns and must close the subscription. */
    fun observe(onChanged: () -> Unit): AutoCloseable = backend.observe(onChanged)
}

internal interface DoNotDisturbBackend {
    val hasAccess: Boolean
    val filter: Int
    fun setFilter(filter: Int)
    fun openSettings()
    fun observe(onChanged: () -> Unit): AutoCloseable
}

private class AndroidDoNotDisturbBackend(private val context: Context) : DoNotDisturbBackend {
    private val manager = context.getSystemService(NotificationManager::class.java)

    override val hasAccess: Boolean get() = manager.isNotificationPolicyAccessGranted
    override val filter: Int get() = manager.currentInterruptionFilter
    override fun setFilter(filter: Int) = manager.setInterruptionFilter(filter)

    override fun openSettings() {
        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    override fun observe(onChanged: () -> Unit): AutoCloseable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = onChanged()
        }
        val filter = IntentFilter().apply {
            addAction(NotificationManager.ACTION_NOTIFICATION_POLICY_ACCESS_GRANTED_CHANGED)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        context.registerReceiver(receiver, filter, null, Handler(Looper.getMainLooper()), Context.RECEIVER_NOT_EXPORTED)
        return AutoCloseable { context.unregisterReceiver(receiver) }
    }
}
