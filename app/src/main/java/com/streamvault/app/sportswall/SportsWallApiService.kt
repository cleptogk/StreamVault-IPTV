package com.streamvault.app.sportswall

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationCompat
import com.streamvault.app.MainActivity
import com.streamvault.app.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class SportsWallApiService : Service() {
    @Inject
    lateinit var controller: SportsWallController

    private var server: SportsWallApiServer? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification())
        SportsWallApiCredentials.token(this)
        server = runCatching {
            SportsWallApiServer(applicationContext, controller).also {
                it.start(5_000, false)
            }
        }.getOrNull()
        if (server == null) stopSelf()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        server?.stop()
        server = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Sports Wall control",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the authenticated LAN control API available"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_vault)
            .setContentTitle("StreamVault Sports Wall")
            .setContentText("Authenticated LAN control is ready on port ${SportsWallApiServer.PORT}")
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "sports_wall_control"
        private const val NOTIFICATION_ID = 8789

        fun start(context: Context) {
            val intent = Intent(context, SportsWallApiService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SportsWallApiService::class.java))
        }
    }
}
