package com.aliflix.app.player

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aliflix.app.MainActivity
import com.aliflix.app.R

/** Keeps per-app screen casting alive while Aliflix is not the foreground task. */
internal object CastSessionKeepAlive {
    fun start(context: Context): Boolean = runCatching {
        ContextCompat.startForegroundService(
            context,
            Intent(context, CastPlaybackService::class.java).setAction(CastPlaybackService.ACTION_START),
        )
        true
    }.getOrDefault(false)

    fun stop(context: Context) {
        runCatching { context.stopService(Intent(context, CastPlaybackService::class.java)) }
    }
}

class CastPlaybackService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
        )
        acquirePlaybackLocks()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releasePlaybackLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Casting playback",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Keeps Aliflix playback available while casting"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CastPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast_notification)
            .setContentTitle("Aliflix casting is active")
            .setContentText("Playback can continue while you use other apps")
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, "Stop background playback", stopIntent)
            .build()
    }

    @SuppressLint("WakelockTimeout")
    @Suppress("DEPRECATION")
    private fun acquirePlaybackLocks() {
        if (wakeLock?.isHeld != true) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
        if (wifiLock?.isHeld != true) {
            val wifiManager = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wifiManager
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, WIFI_LOCK_TAG)
                .apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }
    }

    private fun releasePlaybackLocks() {
        wakeLock?.takeIf(PowerManager.WakeLock::isHeld)?.release()
        wifiLock?.takeIf(WifiManager.WifiLock::isHeld)?.release()
        wakeLock = null
        wifiLock = null
    }

    internal companion object {
        const val ACTION_START = "com.aliflix.app.action.START_CAST_PLAYBACK"
        const val ACTION_STOP = "com.aliflix.app.action.STOP_CAST_PLAYBACK"
        private const val CHANNEL_ID = "aliflix_cast_playback"
        private const val NOTIFICATION_ID = 4102
        private const val WAKE_LOCK_TAG = "Aliflix:CastPlayback"
        private const val WIFI_LOCK_TAG = "Aliflix:CastWifi"
    }
}
