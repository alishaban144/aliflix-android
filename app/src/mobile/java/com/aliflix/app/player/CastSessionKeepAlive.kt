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
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.aliflix.app.MainActivity
import com.aliflix.app.R

/** Keeps app-only screen casting alive and exposes system playback controls. */
internal object CastSessionKeepAlive {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var playbackCommandHandler: ((CastPlaybackCommand) -> Unit)? = null

    @Volatile
    private var serviceRunning = false

    fun setPlaybackCommandHandler(handler: ((CastPlaybackCommand) -> Unit)?) {
        playbackCommandHandler = handler
    }

    fun start(
        context: Context,
        title: String,
        subtitle: String?,
        playing: Boolean,
    ): Boolean = runCatching {
        ContextCompat.startForegroundService(
            context,
            playbackIntent(context, CastPlaybackService.ACTION_START, title, subtitle, playing),
        )
        true
    }.getOrDefault(false)

    fun update(
        context: Context,
        title: String,
        subtitle: String?,
        playing: Boolean,
    ) {
        if (!serviceRunning) return
        runCatching {
            context.startService(
                playbackIntent(context, CastPlaybackService.ACTION_UPDATE, title, subtitle, playing),
            )
        }
    }

    fun stop(context: Context) {
        serviceRunning = false
        runCatching { context.stopService(Intent(context, CastPlaybackService::class.java)) }
    }

    internal fun markServiceRunning(running: Boolean) {
        serviceRunning = running
    }

    internal fun dispatch(command: CastPlaybackCommand) {
        mainHandler.post { playbackCommandHandler?.invoke(command) }
    }

    private fun playbackIntent(
        context: Context,
        action: String,
        title: String,
        subtitle: String?,
        playing: Boolean,
    ): Intent = Intent(context, CastPlaybackService::class.java)
        .setAction(action)
        .putExtra(CastPlaybackService.EXTRA_TITLE, title)
        .putExtra(CastPlaybackService.EXTRA_SUBTITLE, subtitle)
        .putExtra(CastPlaybackService.EXTRA_PLAYING, playing)
}

private data class CastNotificationState(
    val title: String = "Aliflix",
    val subtitle: String? = null,
    val playing: Boolean = true,
) {
    fun updateFrom(intent: Intent): CastNotificationState = copy(
        title = intent.getStringExtra(CastPlaybackService.EXTRA_TITLE)
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: title,
        subtitle = if (intent.hasExtra(CastPlaybackService.EXTRA_SUBTITLE)) {
            intent.getStringExtra(CastPlaybackService.EXTRA_SUBTITLE)
                ?.trim()
                ?.takeIf(String::isNotEmpty)
        } else {
            subtitle
        },
        playing = if (intent.hasExtra(CastPlaybackService.EXTRA_PLAYING)) {
            intent.getBooleanExtra(CastPlaybackService.EXTRA_PLAYING, playing)
        } else {
            playing
        },
    )
}

class CastPlaybackService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var notificationState = CastNotificationState()
    private lateinit var mediaSession: MediaSession

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        CastSessionKeepAlive.markServiceRunning(true)
        mediaSession = MediaSession(this, "AliflixCastPlayback").apply {
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = applyPlaybackCommand(CastPlaybackCommand.PLAY)

                    override fun onPause() = applyPlaybackCommand(CastPlaybackCommand.PAUSE)

                    override fun onStop() = stopFromControls()
                },
            )
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopFromControls()
                return START_NOT_STICKY
            }
            ACTION_PLAY -> applyPlaybackCommand(CastPlaybackCommand.PLAY)
            ACTION_PAUSE -> applyPlaybackCommand(CastPlaybackCommand.PAUSE)
            ACTION_START,
            ACTION_UPDATE,
            null,
            -> if (intent != null) notificationState = notificationState.updateFrom(intent)
        }
        updateForegroundNotification()
        acquirePlaybackLocks()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        CastSessionKeepAlive.markServiceRunning(false)
        releasePlaybackLocks()
        mediaSession.isActive = false
        mediaSession.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun applyPlaybackCommand(command: CastPlaybackCommand) {
        notificationState = notificationState.copy(playing = command == CastPlaybackCommand.PLAY)
        CastSessionKeepAlive.dispatch(command)
        updateForegroundNotification()
    }

    private fun stopFromControls() {
        CastSessionKeepAlive.dispatch(CastPlaybackCommand.STOP)
        stopSelf()
    }

    private fun updateForegroundNotification() {
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, notificationState.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, notificationState.title)
                .putString(
                    MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
                    notificationState.subtitle ?: "Casting",
                )
                .build(),
        )
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP,
                )
                .setState(
                    if (notificationState.playing) {
                        PlaybackState.STATE_PLAYING
                    } else {
                        PlaybackState.STATE_PAUSED
                    },
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (notificationState.playing) 1f else 0f,
                )
                .build(),
        )
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
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
        val playbackIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, CastPlaybackService::class.java).setAction(
                if (notificationState.playing) ACTION_PAUSE else ACTION_PLAY,
            ),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, CastPlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val playbackLabel = if (notificationState.playing) "Pause" else "Play"
        val playbackIcon = if (notificationState.playing) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast_notification)
            .setContentTitle(notificationState.title)
            .setContentText(notificationState.subtitle ?: "Casting from Aliflix")
            .setSubText("Aliflix cast playback")
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(playbackIcon, playbackLabel, playbackIntent).build())
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    stopIntent,
                ).build(),
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0),
            )
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Cast playback controls",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Controls Aliflix while a title is casting"
                setShowBadge(false)
            },
        )
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
        const val ACTION_UPDATE = "com.aliflix.app.action.UPDATE_CAST_PLAYBACK"
        const val ACTION_PLAY = "com.aliflix.app.action.PLAY_CAST_PLAYBACK"
        const val ACTION_PAUSE = "com.aliflix.app.action.PAUSE_CAST_PLAYBACK"
        const val ACTION_STOP = "com.aliflix.app.action.STOP_CAST_PLAYBACK"
        const val EXTRA_TITLE = "cast_title"
        const val EXTRA_SUBTITLE = "cast_subtitle"
        const val EXTRA_PLAYING = "cast_playing"
        private const val CHANNEL_ID = "aliflix_cast_playback"
        private const val NOTIFICATION_ID = 4102
        private const val WAKE_LOCK_TAG = "Aliflix:CastPlayback"
        private const val WIFI_LOCK_TAG = "Aliflix:CastWifi"
    }
}
