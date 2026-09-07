package com.aliflix.app.player

import android.app.PendingIntent
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Presentation
import android.view.Display
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
import androidx.media3.exoplayer.DefaultLoadControl
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
    private var tvSurface: Surface? = null
    private var tvSurfaceOwner: String? = null
    private var castActivityDisplay: Int? = null
    private var displayWasOff = false
    private var phoneSurfaceOwner: String? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var relayWakeLock: PowerManager.WakeLock? = null
    private var releasing = false
    private val handler = Handler(Looper.getMainLooper())
    private val progressTask = object : Runnable {
        override fun run() { saveProgress(false); handler.postDelayed(this, 5000) }
    }
    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) { castingSuppressed = false; updateDisplay() }
        override fun onDisplayChanged(displayId: Int) = updateDisplay()
        override fun onDisplayRemoved(displayId: Int) { castingSuppressed = false; updateDisplay() }
    }
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            updateWifiLock(); updateDisplay()
        }
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
            .setRenderersFactory(androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 60_000, 3_000, 6_000).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, scopedHttp)))
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        localPlayer.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { renderedStreamUrl = activeStreamUrl }
        })
        player = runCatching {
            CastPlayer.Builder(this).setLocalPlayer(localPlayer)
                .setRemotePlayer(RemoteCastPlayer.Builder(this).setMediaItemConverter(relayConverter()).build())
                .build()
        }.getOrDefault(localPlayer)
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (releasing) return
                playbackReady = player.playbackState == Player.STATE_READY
                playbackFailure = player.playerError
                hasSelectedAudio = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                updateWifiLock()
                updateDisplay()
                if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) || events.contains(Player.EVENT_IS_PLAYING_CHANGED)) saveProgress(true)
            }
        })
        val activity = PendingIntent.getActivity(this, 0, Intent(this, NativePlayerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, nativePhoneLaunchOptions())
        session = MediaSession.Builder(this, player).setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if (controller.packageName == packageName) return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .add(SessionCommand(ACTION_STOP_CAST, Bundle.EMPTY)).build()).build()
                    return if (controller.isTrusted) super.onConnect(session, controller) else MediaSession.ConnectionResult.reject()
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (command.customAction == ACTION_STOP_CAST && controller.packageName == packageName) {
                        castingSuppressed = true
                        if (player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) {
                            runCatching { com.google.android.gms.cast.framework.CastContext.getSharedInstance(this@NativePlaybackService).sessionManager.endCurrentSession(true) }
                        }
                        NativeCastActivity.closeOutput(); tvSurface = null; tvSurfaceOwner = null; castActivityDisplay = null
                        // Selecting the system's default route disconnects wireless display without stopping decoding.
                        val router = getSystemService(MediaRouter::class.java)
                        router.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO or MediaRouter.ROUTE_TYPE_LIVE_AUDIO, router.defaultRoute)
                        updateDisplay()
                        localPlayer.setVideoSurface(phoneSurface?.takeIf { it.isValid })
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return Futures.immediateFuture(SessionResult(SessionError.ERROR_NOT_SUPPORTED))
                }
            }).build()
        displays.registerDisplayListener(displayListener, handler)
        androidx.core.content.ContextCompat.registerReceiver(this, screenReceiver, android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        activeService = this
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
        playbackReady = false; playbackFailure = null; hasSelectedAudio = false
        activeRequest = next
        activeStreamUrl = next.url
        renderedStreamUrl = null
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
            .setMimeType(next.mimeType).setMediaMetadata(MediaMetadata.Builder().setTitle(next.title)
                .setSubtitle(selection?.episodeTitle)
                .setArtworkUri(selection?.media?.backdropUrl?.let(android.net.Uri::parse)).build())
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
        val display = if (castingSuppressed || player.mediaItemCount == 0 || player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE) null else
            getSystemService(MediaRouter::class.java).getSelectedRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO).presentationDisplay
                ?: displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        // Reattaching an unchanged phone surface can emit another player event indefinitely.
        // Phone attachments are handled by the surface command; this method handles TV transitions.
        if (display == null && presentation == null) {
            if (castActivityDisplay != null || tvSurface != null) {
                NativeCastActivity.closeOutput(); tvSurface = null; tvSurfaceOwner = null; castActivityDisplay = null
                localPlayer.setAudioAttributes(AudioAttributes.DEFAULT, true)
                localPlayer.setVideoSurface(phoneSurface?.takeIf { it.isValid })
            }
            return
        }
        if (display != null && !castingSuppressed && castActivityDisplay != display.displayId) {
            castActivityDisplay = display.displayId
            val options = android.app.ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId)
            val intent = Intent(this, NativeCastActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (getSystemService(android.app.ActivityManager::class.java).isActivityStartAllowedOnDisplay(this, display.displayId, intent)) {
                runCatching { startActivity(intent, options.toBundle()) }
            }
        }
        if (display != null && tvSurface?.isValid == true) return
        // Some system routes suspend their compositor on lock. Recreate the fallback surface
        // after that power cycle: a valid Surface can still refer to the abandoned producer.
        if (display?.state == Display.STATE_OFF) displayWasOff = true
        if (displayWasOff && display?.state == Display.STATE_ON) {
            displayWasOff = false
            presentationPlayerView?.player = null
            runCatching { presentation?.dismiss() }; presentation = null; presentationPlayerView = null
        }
        if (presentation?.display?.displayId == display?.displayId && presentation?.isShowing == true) return
        presentationPlayerView?.player = null
        runCatching { presentation?.dismiss() }
        presentation = null; presentationPlayerView = null
        if (display == null || player.mediaItemCount == 0) {
            NativeCastActivity.closeOutput(); tvSurface = null; tvSurfaceOwner = null; castActivityDisplay = null
            localPlayer.setAudioAttributes(AudioAttributes.DEFAULT, true)
            localPlayer.setVideoSurface(phoneSurface?.takeIf { it.isValid })
            return
        }
        runCatching {
            val output = Presentation(this, display)
            val view = PlayerView(output.context).apply { useController = false; this.player = localPlayer; setBackgroundColor(android.graphics.Color.BLACK) }
            output.setContentView(view, FrameLayout.LayoutParams(-1, -1))
            // Apply only to the TV window. Never dismiss the phone's secure lock or wake its screen.
            output.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
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
        val relaying = needed
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
        if (activeService === this) activeService = null
        NativeCastActivity.closeOutput()
        activeStreamUrl = null
        activeRequest = null
        playbackReady = false; playbackFailure = null; hasSelectedAudio = false
        renderedStreamUrl = null
        saveProgress(true)
        handler.removeCallbacksAndMessages(null)
        displays.unregisterDisplayListener(displayListener)
        unregisterReceiver(screenReceiver)
        castingSuppressed = false
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
        private var activeService: NativePlaybackService? = null

        /** Same-process main-thread handoff must finish before SurfaceHolder.surfaceDestroyed returns. */
        internal fun attachSurface(owner: String, surface: Surface?, tv: Boolean) {
            check(Looper.myLooper() == Looper.getMainLooper())
            val service = activeService ?: return
            if (service.releasing) return
            if (tv) {
                if (surface == null && service.tvSurfaceOwner != owner) return
                val previous = service.tvSurface
                service.tvSurface = surface; service.tvSurfaceOwner = if (surface != null) owner else null
                if (surface != null && !castingSuppressed) {
                    service.presentationPlayerView?.player = null
                    runCatching { service.presentation?.dismiss() }
                    service.presentation = null; service.presentationPlayerView = null
                    service.localPlayer.setVideoSurface(surface)
                    service.localPlayer.setAudioAttributes(AudioAttributes.DEFAULT, false)
                } else if (previous != null) {
                    service.localPlayer.clearVideoSurface(previous)
                    service.handler.post { service.updateDisplay() }
                }
            } else {
                if (surface == null && service.phoneSurfaceOwner != owner) return
                service.phoneSurface = surface; service.phoneSurfaceOwner = if (surface != null) owner else null
                if (service.presentation == null && service.tvSurface == null) service.localPlayer.setVideoSurface(surface)
            }
        }

        internal var playbackReady = false
            private set
        internal var playbackFailure: androidx.media3.common.PlaybackException? = null
            private set
        internal var hasSelectedAudio = false
            private set
        internal var castingSuppressed: Boolean = false
            private set
        internal var renderedStreamUrl: String? = null
            private set
        internal var activeRequest: NativePlaybackRequest? = null
            private set
        internal var activeStreamUrl: String? = null
            private set
        const val ACTION_STOP = "com.aliflix.app.STOP_NATIVE_PLAYBACK"

        const val ACTION_STOP_CAST = "com.aliflix.app.STOP_CAST"

        private const val NOTIFICATION_ID = 4103
        private const val CHANNEL_ID = "aliflix_native_playback"
    }
}
