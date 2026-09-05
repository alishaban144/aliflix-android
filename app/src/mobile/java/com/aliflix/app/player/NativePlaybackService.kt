package com.aliflix.app.player

import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Presentation
import android.content.Intent
import android.hardware.display.DisplayManager
import android.media.MediaRouter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Bundle
import android.view.Surface
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.DeviceInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionError
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.core.app.ServiceCompat
import androidx.media3.ui.PlayerView
import com.aliflix.app.AliflixApplication
import com.aliflix.app.model.Media
import com.aliflix.app.model.PlaybackProviderId
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.model.PlaybackSource
import com.google.android.gms.cast.MediaQueueItem
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject
import java.net.Inet4Address

/** Owns decoding, session, notification and TV surface. No Activity/WebView references. */
@androidx.annotation.OptIn(UnstableApi::class)
class NativePlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var localPlayer: ExoPlayer
    private lateinit var player: Player
    private val httpFactory = DefaultHttpDataSource.Factory()
    private var relay: CastStreamRelay? = null
    private var request: NativePlaybackRequest? = null
    private var originalItem: MediaItem? = null
    private var selection: PlaybackSelection? = null
    private var presentation: Presentation? = null
    private var presentationPlayerView: PlayerView? = null
    private var phoneSurface: Surface? = null
    private var surfaceController: MediaSession.ControllerInfo? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var relayWakeLock: PowerManager.WakeLock? = null
    private var releasing = false
    private val handler = Handler(Looper.getMainLooper())
    private val progressTask = object : Runnable {
        override fun run() { saveProgress(false); handler.postDelayed(this, 5000) }
    }
    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updateDisplay()
        override fun onDisplayChanged(displayId: Int) = updateDisplay()
        override fun onDisplayRemoved(displayId: Int) = updateDisplay()
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Video playback", NotificationManager.IMPORTANCE_LOW),
        )
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID).setChannelId(CHANNEL_ID).build())
        val scopedHttp = ResolvingDataSource.Factory(httpFactory) { spec ->
            val current = request
            if (current != null && current.cookie.isNotBlank() && spec.uri.host == android.net.Uri.parse(current.url).host) {
                spec.withRequestHeaders(spec.httpRequestHeaders + ("Cookie" to current.cookie))
            } else spec
        }
        localPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, scopedHttp)))
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player = runCatching {
            CastPlayer.Builder(this).setLocalPlayer(localPlayer)
                .setRemotePlayer(RemoteCastPlayer.Builder(this).setMediaItemConverter(relayConverter()).build())
                .build()
        }.getOrDefault(localPlayer)
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (releasing) return
                updateWifiLock()
                updateDisplay()
                if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) || events.contains(Player.EVENT_IS_PLAYING_CHANGED)) saveProgress(true)
            }
        })
        val activity = PendingIntent.getActivity(this, 0, Intent(this, NativePlayerActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if (controller.packageName == packageName) return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .add(SessionCommand(ACTION_PHONE_SURFACE, Bundle.EMPTY)).build()).build()
                    return if (controller.isTrusted) super.onConnect(session, controller) else MediaSession.ConnectionResult.reject()
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (command.customAction != ACTION_PHONE_SURFACE || controller.packageName != packageName) return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                    val surface = androidx.core.os.BundleCompat.getParcelable(args, "surface", Surface::class.java)
                    if (surface != null || surfaceController == controller) {
                        phoneSurface = surface; surfaceController = if (surface != null) controller else null
                        if (presentation == null) localPlayer.setVideoSurface(surface)
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
                override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
                    if (surfaceController == controller) {
                        phoneSurface = null; surfaceController = null
                        if (presentation == null) localPlayer.clearVideoSurface()
                    }
                }
            }).build()
        displays.registerDisplayListener(displayListener, handler)
        handler.post(progressTask)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            player.stop(); player.clearMediaItems(); stopSelf()
            return START_NOT_STICKY
        }
        intent?.let { nativeRequestPayload(this, it, consume = true) }?.let { raw ->
            runCatching {
                val next = NativePlaybackRequest.fromJson(raw)
                if (next.playing) {
                    // Android 15+ can reject audio focus before Media3's first playing event.
                    // Promote first; Media3 then replaces this with its real media notification.
                    ServiceCompat.startForeground(this, NOTIFICATION_ID,
                        Notification.Builder(this, CHANNEL_ID).setSmallIcon(com.aliflix.app.R.drawable.ic_cast_notification)
                            .setContentTitle(next.title).setContentText("Preparing video").setOnlyAlertOnce(true).build(),
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                }
                load(next)
            }.onFailure {
                android.util.Log.e("AliflixPlayback", "Unable to start native playback", it)
                player.stop(); player.clearMediaItems(); stopSelf()
            }
        }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    private fun load(next: NativePlaybackRequest) {
        saveProgress(true)
        player.stop(); player.clearMediaItems()
        relay?.close(); relay = null
        request = next
        activeStreamUrl = next.url
        selection = runCatching {
            val json = JSONObject(next.selectionJson)
            PlaybackSelection(Media.fromJson(json.getJSONObject("media")),
                seasonNumber = json.optInt("season", 1), episodeNumber = json.optInt("episode", 1),
                episodeTitle = json.optString("episodeTitle").takeIf { it.isNotBlank() },
                source = PlaybackSource(PlaybackProviderId.valueOf(json.getString("provider")), json.getString("baseUrl")))
        }.getOrNull()
        val headers = mutableMapOf("Referer" to next.referer, "Origin" to java.net.URI(next.referer).let { "${it.scheme}://${it.rawAuthority}" })
        // Cookie forwarding for segmented streams is handled per origin by the relay.
        httpFactory.setUserAgent(next.userAgent).setDefaultRequestProperties(headers)
        val itemBuilder = MediaItem.Builder().setMediaId(selection?.key ?: next.title).setUri(next.url)
            .setMimeType(next.mimeType).setMediaMetadata(MediaMetadata.Builder().setTitle(next.title).build())
        if (next.subtitlesVtt.isNotBlank()) {
            val file = java.io.File(cacheDir, "native-playback-subtitles.vtt").apply { writeText(next.subtitlesVtt) }
            itemBuilder.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(file))
                .setMimeType("text/vtt").setLabel("Aliflix subtitles").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
        }
        originalItem = itemBuilder.build()
        player.setMediaItem(checkNotNull(originalItem), next.positionMs)
        player.prepare()
        player.playWhenReady = next.playing
        updateDisplay()
    }

    private fun relayConverter(): MediaItemConverter = object : MediaItemConverter {
        private val delegate = DefaultMediaItemConverter()
        override fun toMediaItem(item: MediaQueueItem): MediaItem = originalItem ?: delegate.toMediaItem(item)
        override fun toMediaQueueItem(item: MediaItem): MediaQueueItem {
            val current = checkNotNull(request)
            val currentRelay = relay ?: CastStreamRelay(current, lanAddress()).also { relay = it }
            val builder = item.buildUpon().setUri(currentRelay.streamUrl)
            if (current.subtitlesVtt.isNotBlank()) builder.setSubtitleConfigurations(listOf(
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(currentRelay.subtitleUrl))
                    .setMimeType("text/vtt").setLabel("Aliflix subtitles").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
            return delegate.toMediaQueueItem(builder.build())
        }
    }

    private fun lanAddress(): String {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val wifi = connectivity.allNetworks.firstOrNull { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        val network = wifi ?: connectivity.activeNetwork
        return connectivity.getLinkProperties(network)?.linkAddresses?.map { it.address }
            ?.filterIsInstance<Inet4Address>()?.firstOrNull { !it.isLoopbackAddress }?.hostAddress
            ?: error("Connect the phone and TV to the same Wi-Fi network")
    }

    @Suppress("DEPRECATION")
    private fun updateDisplay() {
        if (releasing) return
        val display = if (player.mediaItemCount == 0 || player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) null else
            getSystemService(MediaRouter::class.java).getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO).presentationDisplay
                ?: displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        // Reattaching an unchanged phone surface can emit another player event indefinitely.
        // Phone attachments are handled by the surface command; this method handles TV transitions.
        if (display == null && presentation == null) return
        if (presentation?.display?.displayId == display?.displayId && presentation?.isShowing == true) return
        presentationPlayerView?.player = null
        runCatching { presentation?.dismiss() }
        presentation = null; presentationPlayerView = null
        if (display == null || player.mediaItemCount == 0) {
            localPlayer.setAudioAttributes(AudioAttributes.DEFAULT, true)
            localPlayer.setVideoSurface(phoneSurface?.takeIf { it.isValid })
            return
        }
        runCatching {
            val output = Presentation(this, display)
            val view = PlayerView(output.context).apply { useController = false; this.player = localPlayer; setBackgroundColor(android.graphics.Color.BLACK) }
            output.setContentView(view, FrameLayout.LayoutParams(-1, -1))
            output.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
            output.show()
            output.window?.setLayout(-1, -1)
            presentation = output; presentationPlayerView = view
            // The selected external output must not be paused by focus changes in phone apps.
            localPlayer.setAudioAttributes(AudioAttributes.DEFAULT, false)
        }.onFailure {
            presentationPlayerView?.player = null
            android.util.Log.e("AliflixPlayback", "Unable to attach external display", it)
        }
    }

    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("WakelockTimeout")
    private fun updateWifiLock() {
        val needed = player.playWhenReady && player.playbackState != Player.STATE_ENDED && player.mediaItemCount > 0
        if (needed && wifiLock?.isHeld != true) {
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Aliflix:NativeCastWifi")
                .apply { setReferenceCounted(false); acquire() }
        } else if (!needed) { wifiLock?.takeIf { it.isHeld }?.release(); wifiLock = null }
        val relaying = needed && player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE
        if (relaying && relayWakeLock?.isHeld != true) {
            relayWakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aliflix:CastRelay")
                .apply { setReferenceCounted(false); acquire() }
        } else if (!relaying) { relayWakeLock?.takeIf { it.isHeld }?.release(); relayWakeLock = null }
    }

    private fun saveProgress(urgent: Boolean) {
        val current = selection ?: return
        val duration = player.duration
        if (duration <= 0) return
        (application as AliflixApplication).playbackProgressStore.savePlayerProgress(current, player.currentPosition / 1000.0, duration / 1000.0, urgent)
    }

    override fun onDestroy() {
        releasing = true
        activeStreamUrl = null
        saveProgress(true)
        handler.removeCallbacksAndMessages(null)
        displays.unregisterDisplayListener(displayListener)
        presentationPlayerView?.player = null
        runCatching { presentation?.dismiss() }
        session?.release(); session = null
        player.release()
        relay?.close(); relay = null
        wifiLock?.takeIf { it.isHeld }?.release(); wifiLock = null
        relayWakeLock?.takeIf { it.isHeld }?.release(); relayWakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        internal var activeStreamUrl: String? = null
            private set
        const val ACTION_STOP = "com.aliflix.app.STOP_NATIVE_PLAYBACK"
        const val ACTION_PHONE_SURFACE = "com.aliflix.app.PHONE_PLAYBACK_SURFACE"
        private const val NOTIFICATION_ID = 4103
        private const val CHANNEL_ID = "aliflix_native_playback"
    }
}
