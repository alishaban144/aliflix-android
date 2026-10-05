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
import com.aliflix.app.model.PlaybackProvider
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
    private var preferredQualityApplied = false
    private var preferredAudioLabelApplied = false
    private lateinit var cineJoyRecovery: CineJoyPlayerRecovery
    private var originalItem: MediaItem? = null
    private var externalCaptionCues: List<SubtitleCue> = emptyList()
    private var originalCaptionJson: String? = null
    private val speechCapture = PlaybackSpeechBuffer()
    private val embeddedSyncReference = EmbeddedSyncReference()
    private var playedUntil = 0.0
    private var lastAudioFingerprint = ""
    private val subtitleFiles = mutableListOf<java.io.File>()
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
    private val captionTask = object : Runnable {
        override fun run() {
            if (releasing) return
            cineJoyRecovery.tick()
            if (player.deviceInfo.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE)
                playedUntil = maxOf(playedUntil, player.currentPosition / 1000.0)
            val cues = currentCaptionCues()
            presentationPlayerView?.subtitleView?.setCues(cues)
            NativeCastActivity.renderCaptions(cues)
            handler.postDelayed(this, 100)
        }
    }
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
        speechCapture.enableNeural(applicationContext)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Video playback", NotificationManager.IMPORTANCE_LOW),
        )
        setMediaNotificationProvider(DefaultMediaNotificationProvider.Builder(this)
            .setNotificationId(NOTIFICATION_ID).setChannelId(CHANNEL_ID).build())
        val scopedHttp = ResolvingDataSource.Factory(httpFactory) { spec ->
            val current = request
            current?.resolveStreamSpec(spec) ?: spec
        }
        localPlayer = ExoPlayer.Builder(this)
            .setRenderersFactory(SpeechCaptureRenderers(this, speechCapture).setEnableDecoderFallback(true))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(30_000, 60_000, 250, 1_000).build())
            .setMediaSourceFactory(DefaultMediaSourceFactory(androidx.media3.datasource.DataSource.Factory {
                if (request?.offlineDownloadId?.isNotBlank() == true) DefaultDataSource(this, com.aliflix.app.downloads.OfflineDownloads.get(this).offlineFactory().createDataSource())
                else {
                val fragments = androidx.media3.datasource.DataSource.Factory {
                    val source = scopedHttp.createDataSource()
                    val current = request
                    if (current?.referer == CineJoyNativeCatalog.REFERER)
                        CineJoyFragmentDataSource(source, current.url) else source
                }
                val playlists = androidx.media3.datasource.DataSource.Factory {
                    val source = fragments.createDataSource()
                    val current = request
                    if (current?.referer == CineJoyNativeCatalog.REFERER)
                        CineJoyAudioPlaylistDataSource(source, current.url, fragments) else source
                }
                val upstream = StartupStreamCache.factory(this, DefaultDataSource.Factory(this, playlists)).createDataSource()
                    val current = request
                    if (current != null && current.referer == CineJoyNativeCatalog.REFERER)
                        CineJoyManifestDataSource(upstream, current,
                            adaptiveVideo = (application as AliflixApplication).playerSettingsStore.settings.value.preferredVideoQuality != PreferredVideoQuality.LOW,
                        ) else upstream
                }
            }, ReferenceExtractorsFactory(embeddedSyncReference)).setLoadErrorHandlingPolicy(CineJoyLoadErrorPolicy { request?.referer == CineJoyNativeCatalog.REFERER }))
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        localPlayer.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { renderedStreamUrl = activeStreamUrl; PlaybackStartupTiming.mark("first_frame") }
        })
        player = runCatching {
            CastPlayer.Builder(this).setLocalPlayer(localPlayer)
                .setRemotePlayer(RemoteCastPlayer.Builder(this).setMediaItemConverter(relayConverter()).build())
                .build()
        }.onFailure { android.util.Log.e("AliflixCast", "Google Cast initialization failed", it) }
            .getOrDefault(localPlayer)
        cineJoyRecovery = CineJoyPlayerRecovery(player, handler, ::saveAudioChoice)
        player.addListener(cineJoyRecovery)
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                val fingerprint = selectedAudioFingerprint()
                if (fingerprint.isNotBlank() && fingerprint != lastAudioFingerprint) {
                    speechCapture.reset(); playedUntil = player.currentPosition / 1000.0
                    lastAudioFingerprint = fingerprint
                }
                if (!preferredQualityApplied && (application as AliflixApplication).playerSettingsStore.settings.value.preferredVideoQuality == PreferredVideoQuality.LOW && lowestVideoTrack(tracks) != null) {
                    preferredQualityApplied = true
                    applyLowestVideoTrack(player, tracks)
                }
                applySavedAudioChoice(tracks)
                if (request?.preferEmbeddedSubtitles != true || embeddedSubtitlesActive) return
                val language = canonicalSubtitleLanguageCode(request?.subtitleLanguage.orEmpty())
                val match = tracks.groups.asSequence().filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
                    (0 until group.length).asSequence().map { group to it }
                }.firstOrNull { (group, index) ->
                    val format = group.getTrackFormat(index)
                    group.isTrackSupported(index) && !format.id.orEmpty().contains("aliflix-external") &&
                        canonicalSubtitleLanguageCode(format.language.orEmpty()) == language
                } ?: return
                embeddedSubtitlesActive = true
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(androidx.media3.common.TrackSelectionOverride(match.first.mediaTrackGroup, match.second))
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build()
            }
            override fun onEvents(player: Player, events: Player.Events) {
                if (releasing) return
                playbackReady = player.playbackState == Player.STATE_READY
                if (playbackReady) {
                    PlaybackStartupTiming.mark("ready")
                    applySavedAudioChoice(player.currentTracks)
                }
                playbackFailure = player.playerError
                hasSelectedAudio = player.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
                updateWifiLock()
                updateDisplay()
                if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED) || events.contains(Player.EVENT_IS_PLAYING_CHANGED) || events.contains(Player.EVENT_POSITION_DISCONTINUITY) || events.contains(Player.EVENT_DEVICE_INFO_CHANGED)) saveProgress(true)
            }
        })
        val activity = PendingIntent.getActivity(this, 0, Intent(this, NativePlayerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, nativePhoneLaunchOptions())
        session = MediaSession.Builder(this, player).setSessionActivity(activity)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                    if (controller.packageName == packageName) return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                            .add(SessionCommand(ACTION_STOP_CAST, Bundle.EMPTY))
                            .add(SessionCommand(ACTION_SET_CAST_SUBTITLES, Bundle.EMPTY))
                            .build()).build()
                    return if (controller.isTrusted) super.onConnect(session, controller) else MediaSession.ConnectionResult.reject()
                }
                override fun onCustomCommand(session: MediaSession, controller: MediaSession.ControllerInfo, command: SessionCommand, args: Bundle): ListenableFuture<SessionResult> {
                    if (command.customAction == ACTION_STOP_CAST && controller.packageName == packageName) {
                        saveProgress(true)
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
                    if (command.customAction == ACTION_SET_CAST_SUBTITLES && controller.packageName == packageName) {
                        val enabled = args.getBoolean("enabled", true)
                        runCatching {
                            val castSession = com.google.android.gms.cast.framework.CastContext.getSharedInstance(this@NativePlaybackService).sessionManager.currentCastSession
                            castSession?.remoteMediaClient?.setActiveMediaTracks(if (enabled) longArrayOf(1L) else longArrayOf())
                        }
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
        handler.post(captionTask)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            saveProgress(true)
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
                activeRequestId = intent.getStringExtra("requestFile")
                load(next)
            }.onFailure {
                android.util.Log.e("AliflixPlayback", "Unable to start native playback", it)
                playbackFailure = androidx.media3.common.PlaybackException("Unable to start native playback", it,
                    androidx.media3.common.PlaybackException.ERROR_CODE_UNSPECIFIED)
                player.stop(); player.clearMediaItems(); stopSelf()
            }
        }
        super.onStartCommand(intent, flags, startId)
        return START_NOT_STICKY
    }

    private fun load(next: NativePlaybackRequest) {
        cineJoyRecovery.reset(false)
        speechCapture.reset()
        embeddedSyncReference.reset(); playedUntil = 0.0
        lastAudioFingerprint = ""
        saveProgress(true)
        player.stop(); player.clearMediaItems()
        relay?.close(); relay = null
        request = next
        preferredQualityApplied = false
        preferredAudioLabelApplied = false
        val preferredAudio = getSharedPreferences("native-audio-choice", MODE_PRIVATE).getString("language", null)
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .setPreferredAudioLanguage(preferredAudio)
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .build()
        player.trackSelectionParameters = preferredQualityParameters(player.trackSelectionParameters,
            (application as AliflixApplication).playerSettingsStore.settings.value.preferredVideoQuality)
        // CineJoy can mislabel dimensions (Dark's 1080p decodes to 2160x1080).
        // Keep its AVC quality ladder for Auto rather than forcing that heavy rendition.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setMinVideoSize(0, 0)
            .build()
        externalCaptionCues = if (next.offlineDownloadId.isNotBlank() && !next.offlineAutoSubtitles) emptyList()
            else parseTimedTextSubtitleCues(next.subtitlesVtt)
        originalCaptionJson = externalCaptionCues.takeIf { it.isNotEmpty() }?.let(::subtitleCuesJson)
        val delay = (application as AliflixApplication).playerSettingsStore.settings.value.subtitleDelaySeconds
        val initialVtt = if (externalCaptionCues.isEmpty()) next.subtitlesVtt else correctedMobileVtt(externalCaptionCues, null, delay)
        val prepared = next.copy(subtitlesVtt = initialVtt)
        request = prepared
        externalCaptionCues = parseTimedTextSubtitleCues(initialVtt)
        embeddedSubtitlesActive = false
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredTextLanguage(next.subtitleLanguage)
            .setSelectUndeterminedTextLanguage(false)
            // Enable embedded text only after onTracksChanged finds this exact language.
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, next.subtitlesVtt.isBlank()).build()
        relay = if (next.offlineDownloadId.isBlank()) runCatching { CastStreamRelay(prepared, lanAddress()) }.getOrNull() else null
        playbackReady = false; playbackFailure = null; hasSelectedAudio = false
        activeRequest = prepared
        activeStreamUrl = next.url
        renderedStreamUrl = null
        selection = runCatching {
            val json = JSONObject(next.selectionJson)
            PlaybackSelection(Media.fromJson(json.getJSONObject("media")),
                seasonNumber = json.optInt("season", 1), episodeNumber = json.optInt("episode", 1),
                episodeTitle = json.optString("episodeTitle").takeIf { it.isNotBlank() },
                source = PlaybackSource(PlaybackProvider.valueOf(json.getString("provider")), json.getString("baseUrl")))
        }.getOrNull()
        val headers = mutableMapOf("Referer" to next.referer, "Origin" to java.net.URI(next.referer).let { "${it.scheme}://${it.rawAuthority}" })
        // Cookie forwarding for segmented streams is handled per origin by the relay.
        httpFactory.setUserAgent(next.userAgent).setDefaultRequestProperties(headers)
        val saved = if (next.offlineDownloadId.isNotBlank()) com.aliflix.app.downloads.OfflineDownloads.get(this).manager.downloadIndex.getDownload(next.offlineDownloadId) else null
        require(next.offlineDownloadId.isBlank() || saved?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED) { "Download unavailable" }
        val itemBuilder = (saved?.request?.toMediaItem()?.buildUpon() ?: MediaItem.Builder()).setMediaId(selection?.key ?: next.title).setUri(next.url)
            .setMimeType(next.mimeType).setMediaMetadata(MediaMetadata.Builder().setTitle(next.title)
                .setSubtitle(selection?.episodeTitle)
                .setArtworkUri(selection?.media?.backdropUrl?.let(android.net.Uri::parse)).build())
        if (next.subtitlesVtt.isNotBlank()) {
            val file = java.io.File(cacheDir, "native-playback-subtitles.vtt").apply { writeText(initialVtt) }
            val lang = next.subtitleLanguage.ifBlank { "en" }
            itemBuilder.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(file))
                .setId("aliflix-external").setMimeType("text/vtt").setLanguage(lang).setLabel(next.subtitleLabel)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED).build()))
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setPreferredTextLanguage(lang)
                .setSelectUndeterminedTextLanguage(true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .build()
        }
        if (next.offlineDownloadId.isNotBlank() && !next.offlineAutoSubtitles) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }
        originalItem = itemBuilder.build()
        cineJoyRecovery.reset(next.offlineDownloadId.isBlank() && next.referer == CineJoyNativeCatalog.REFERER)
        player.setMediaItem(checkNotNull(originalItem), next.positionMs)
        PlaybackStartupTiming.mark("media3_prepare")
        player.prepare()
        player.playWhenReady = next.playing
        updateDisplay()
    }

    private fun applySavedAudioChoice(tracks: androidx.media3.common.Tracks) {
        if (preferredAudioLabelApplied) return
        val savedLabel = selection?.let {
            getSharedPreferences("native-audio-choice", MODE_PRIVATE).getString("label:${it.source.identity.name}:${it.key}", null)
        }
        if (savedLabel.isNullOrBlank()) { preferredAudioLabelApplied = true; return }
        val match = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
            (0 until group.length).map { group to it }
        }.firstOrNull { (group, index) -> group.isTrackSupported(index, true) && group.getTrackFormat(index).label == savedLabel } ?: return
        // v142 could persist a failed choice. Establish default audio first, then
        // restore the preference transactionally so a bad saved track cannot trap startup.
        if (cineJoyRecovery.enabled && player.playbackState != Player.STATE_READY) return
        preferredAudioLabelApplied = true
        if (cineJoyRecovery.enabled) cineJoyRecovery.select(match.first.mediaTrackGroup, match.second)
        else player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
            .setOverrideForType(androidx.media3.common.TrackSelectionOverride(match.first.mediaTrackGroup, match.second)).build()
    }

    private fun saveAudioChoice(language: String?, label: String?) {
        val prefs = getSharedPreferences("native-audio-choice", MODE_PRIVATE).edit()
        val key = selection?.let { "label:${it.source.identity.name}:${it.key}" }
        if (!language.isNullOrBlank() && language != "und") {
            prefs.putString("language", language)
            key?.let(prefs::remove)
        } else if (!label.isNullOrBlank()) {
            prefs.remove("language")
            key?.let { prefs.putString(it, label) }
        }
        prefs.remove("label").apply()
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
                    .setMimeType("text/vtt").setLanguage(current.subtitleLanguage).setLabel(current.subtitleLabel).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
            val queueItem = delegate.toMediaQueueItem(builder.build())
            return withCastSubtitles(queueItem, current, currentRelay.subtitleUrl)
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
        if (player.currentMediaItem?.mediaId != current.key || player.playbackState !in setOf(Player.STATE_READY, Player.STATE_ENDED)) return
        val duration = player.duration
        if (duration <= 0) return
        (application as AliflixApplication).playbackProgressStore.savePlayerProgress(current, player.currentPosition / 1000.0, duration / 1000.0, urgent, ended = player.playbackState == Player.STATE_ENDED)
    }

    override fun onDestroy() {
        saveProgress(true)
        releasing = true
        if (activeService === this) activeService = null
        NativeCastActivity.closeOutput()
        activeStreamUrl = null
        activeRequest = null
        playbackReady = false; playbackFailure = null; hasSelectedAudio = false
        renderedStreamUrl = null
        handler.removeCallbacksAndMessages(null)
        displays.unregisterDisplayListener(displayListener)
        unregisterReceiver(screenReceiver)
        castingSuppressed = false
        presentationPlayerView?.player = null
        runCatching { presentation?.dismiss() }
        session?.release(); session = null
        player.release()
        subtitleFiles.forEach { it.delete() }; subtitleFiles.clear()
        relay?.close(); relay = null
        wifiLock?.takeIf { it.isHeld }?.release(); wifiLock = null
        relayWakeLock?.takeIf { it.isHeld }?.release(); relayWakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        private var activeService: NativePlaybackService? = null

        internal val handlesCineJoyRecovery get() = activeService?.let {
            it.cineJoyRecovery.enabled && it.cineJoyRecovery.hasPlayed && !it.cineJoyRecovery.exhausted &&
                it.player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL
        } == true
        internal val cineJoyRecoveryExhausted get() = activeService?.cineJoyRecovery?.let { it.enabled && it.exhausted } == true
        internal val cineJoyRecoveryMessage get() = activeService?.cineJoyRecovery?.message

        internal fun retryCineJoyPlayback(manual: Boolean = false): Boolean {
            check(Looper.myLooper() == Looper.getMainLooper())
            if (!handlesCineJoyRecovery) return false
            activeService?.cineJoyRecovery?.recover(manual)
            return true
        }

        internal fun saveAudioPreference(language: String?, label: String?) {
            check(Looper.myLooper() == Looper.getMainLooper())
            activeService?.saveAudioChoice(language, label)
        }

        internal fun selectAudio(group: androidx.media3.common.TrackGroup, index: Int) {
            check(Looper.myLooper() == Looper.getMainLooper())
            val service = activeService ?: return
            val choice = nativeAudioChoice(service.player.currentTracks, group, index) ?: return
            // Explicit selection supersedes the startup-only saved-label application.
            service.preferredAudioLabelApplied = true
            val actualIndex = choice.trackIndices.first()
            if (service.player.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_LOCAL &&
                service.cineJoyRecovery.select(choice.mediaTrackGroup, actualIndex)) return
            service.player.trackSelectionParameters = service.player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false).setOverrideForType(choice).build()
            val format = choice.mediaTrackGroup.getFormat(actualIndex)
            service.saveAudioChoice(format.language, format.label)
        }

        internal val isPlaybackRequested get() = activeService?.player?.playWhenReady == true

        internal val decodedAudioEvidence get() = activeService?.speechCapture?.let {
            it.generation to it.decodedFrameCount
        }

        /** Main-thread diagnostic for device acceptance; never exposes media bytes. */
        internal fun playbackEvidence(): String {
            check(Looper.myLooper() == Looper.getMainLooper())
            val service = activeService ?: return "Service absent"
            return listOf("local" to service.localPlayer, "session" to service.player).joinToString("\n") { (name, player) ->
                "$name state=${player.playbackState},position=${player.currentPosition},buffer=${player.totalBufferedDuration},playing=${player.isPlaying},requested=${player.playWhenReady},error=${player.playerError?.stackTraceToString()}"
            } + "\nrecovery=${service.cineJoyRecovery.message},enabled=${service.cineJoyRecovery.enabled},hasPlayed=${service.cineJoyRecovery.hasPlayed}"
        }

        internal fun pauseFromBack() {
            check(Looper.myLooper() == Looper.getMainLooper())
            activeService?.player?.pause()
        }

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

        internal fun currentCaptionCues(): List<androidx.media3.common.text.Cue> {
            val service = activeService ?: return emptyList()
            if (embeddedSubtitlesActive || service.externalCaptionCues.isEmpty()) return service.localPlayer.currentCues.cues.map(::mobileCaptionCue)
            val seconds = service.player.currentPosition / 1000.0
            return service.externalCaptionCues.asSequence().filter { seconds >= it.startSeconds && seconds < it.endSeconds }
                .map { androidx.media3.common.text.Cue.Builder().setText(mobileCaptionText(it.text)).build() }.toList()
        }

        internal fun speechEvidence(): List<SpeechWindow> = activeService?.let { it.speechCapture.windows(it.playedUntil) }.orEmpty()
        internal fun speechDiagnostics(): String = activeService?.speechCapture?.diagnostics() ?: "service_absent"
        internal fun embeddedReference(): EmbeddedSyncReference.Reference? = activeService?.embeddedSyncReference?.observed()
        internal fun originalSubtitles(): String? = activeService?.originalCaptionJson
        internal fun rememberOriginalSubtitles(json: String?) { activeService?.originalCaptionJson = json }
        internal val speechGeneration: Long get() = activeService?.speechCapture?.generation ?: -1L
        internal val speechCaptureUnavailable: Boolean get() = activeService?.speechCapture?.unavailable == true

        internal fun selectedAudioFingerprint(): String {
            val service = activeService ?: return ""
            val selected = service.localPlayer.currentTracks.groups
            .filter { it.type == C.TRACK_TYPE_AUDIO }.flatMap { group ->
                (0 until group.length).filter { group.isTrackSelected(it) }.map { index ->
                    val f = group.getTrackFormat(index)
                    audioSyncFingerprint(f, service.localPlayer.currentMediaItem?.localConfiguration?.subtitleConfigurations?.isNotEmpty() == true)
                }
            }.joinToString(";")
            // HLS duration changes as its timeline loads; it is not an audio-track
            // identity. Exact source/rendition/content are already in the cache key.
            return selected.ifBlank { service.lastAudioFingerprint }
        }

        internal fun selectedAudioLanguage(): String = activeService?.localPlayer?.currentTracks?.groups
            ?.filter { it.type == C.TRACK_TYPE_AUDIO }?.flatMap { group ->
                (0 until group.length).filter { group.isTrackSelected(it) }.map { group.getTrackFormat(it).language.orEmpty() }
            }?.firstOrNull().orEmpty()

        internal fun updateSubtitles(vtt: String, language: String = "", label: String = "", automatic: Boolean = false) {
            val service = activeService ?: return
            if (automatic && embeddedSubtitlesActive) return
            if (!automatic) {
                embeddedSubtitlesActive = false
                service.request = service.request?.copy(preferEmbeddedSubtitles = false)
            }
            val current = service.request ?: return
            val updated = current.copy(
                subtitlesVtt = vtt,
                subtitleLanguage = language.ifBlank { current.subtitleLanguage },
                subtitleLabel = label.ifBlank { current.subtitleLabel }
            )
            service.request = updated
            activeRequest = updated
            service.externalCaptionCues = parseTimedTextSubtitleCues(vtt)
            service.relay?.updateSubtitles(vtt)
            val lang = updated.subtitleLanguage.ifBlank { "en" }
            val configs = if (vtt.isBlank()) emptyList() else {
                // A new URI prevents a previously parsed track from retaining old text/timing.
                val file = java.io.File(service.cacheDir, "native-subtitles-${java.util.UUID.randomUUID()}.vtt").apply { writeText(vtt) }
                service.subtitleFiles.add(file)
                listOf(MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(file))
                    .setId("aliflix-external").setMimeType("text/vtt").setLanguage(lang).setLabel(updated.subtitleLabel)
                    .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build())
            }
            service.player.trackSelectionParameters = service.player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                .setPreferredTextLanguage(lang).setSelectUndeterminedTextLanguage(true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !automatic || vtt.isBlank()).build()
            val item = service.originalItem?.buildUpon()?.setSubtitleConfigurations(configs)?.build() ?: return
            service.originalItem = item
            // The Activity renders local external cues against currentPosition. Keep
            // the updated item for Cast handoff, without interrupting native decoding.

        }

        internal var embeddedSubtitlesActive = false
            private set
        internal var playbackReady = false
            private set
        internal var activeRequestId: String? = null
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

        const val ACTION_SET_CAST_SUBTITLES = "com.aliflix.app.SET_CAST_SUBTITLES"

        private const val NOTIFICATION_ID = 4103
        private const val CHANNEL_ID = "aliflix_native_playback"
    }
}
