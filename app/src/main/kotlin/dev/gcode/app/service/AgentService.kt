package dev.gcode.app.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.gcode.app.MainActivity
import dev.gcode.app.R
import dev.gcode.app.agent.AgentSessionManager
import dev.gcode.app.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Keeps agent turns alive while the app is in the background (ARCHITECTURE.md section 6.1).
 * Type dataSync: started from the user's Send tap; stops itself when no turn is running and on onTimeout.
 */
@AndroidEntryPoint
class AgentService : Service() {
    @Inject lateinit var manager: AgentSessionManager

    @Inject @ApplicationScope
    lateinit var scope: CoroutineScope

    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    // ServiceCompat ignores the type below API 29, so the inlined constant is safe on API 26-28.
    @SuppressLint("InlinedApi")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            manager.stopAll()
            stopSelf()
            return START_NOT_STICKY
        }
        ensureChannel(this)
        val notification = notification(getString(R.string.agent_running))
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (watcher?.isActive != true) {
            watcher = scope.launch {
                manager.live.collect { live ->
                    val running = live.values.filter { it.running }
                    if (running.isEmpty()) {
                        stopSelf()
                    } else {
                        val status = running.firstNotNullOfOrNull { it.status } ?: getString(R.string.agent_running)
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(status))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+: the dataSync budget is used up; stop cleanly. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        manager.stopAll()
        stopSelf()
    }

    override fun onDestroy() {
        watcher?.cancel()
        super.onDestroy()
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_code)
        .setContentTitle(getString(R.string.app_name))
        .setContentText(text)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
        .addAction(
            R.drawable.ic_stop,
            getString(R.string.stop),
            PendingIntent.getService(this, 1, Intent(this, AgentService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE),
        )
        .build()

    companion object {
        const val CHANNEL_ID = "agent"
        const val NOTIFICATION_ID = 41
        const val ACTION_STOP = "dev.gcode.app.STOP_AGENT"

        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_agent), NotificationManager.IMPORTANCE_LOW))
            }
        }

        /** Call from a user action (the Send tap), so starting a foreground service is allowed. */
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, AgentService::class.java)) }
        }
    }
}
