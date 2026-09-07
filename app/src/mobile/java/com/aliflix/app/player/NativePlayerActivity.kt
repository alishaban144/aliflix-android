package com.aliflix.app.player

import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.*
import android.widget.FrameLayout
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.*
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import com.aliflix.app.AliflixApplication
import com.aliflix.app.model.Episode
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.ui.theme.AliflixMobileTheme
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.*
import org.json.JSONArray

/** Disposable UI and preparation; the service retains decoding, the TV surface and media session. */
@androidx.annotation.OptIn(UnstableApi::class)
class NativePlayerActivity : FragmentActivity() {
    private val surfaceOwner = java.util.UUID.randomUUID().toString()
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller by mutableStateOf<MediaController?>(null)
    private var ui by mutableStateOf(NativePlayerUi())
    internal val playbackUiState get() = ui
    internal val playbackController get() = controller
    private var selection: PlaybackSelection? = null
    private var preparation: Job? = null
    private var subtitleJob: Job? = null
    private var subtitleSyncDebounceJob: Job? = null
    private var activeSubtitleCuesJson: String? = null
    private var episodeQueueJob: Job? = null
    private var introJob: Job? = null
    private var introKey: String? = null
    private var resolver: NativeStreamResolver? = null
    private var requestAccepted = false
    private lateinit var receiverButton: androidx.mediarouter.app.MediaRouteButton
    private val receiverPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) openReceiverPicker() else ui = ui.copy(message = "Allow Nearby devices in app settings to find compatible TVs.")
    }
    private var recoveryCount = 0
    private var stalledSince = 0L
    private val triedServers = linkedSetOf<String>()
    private lateinit var resolverHost: FrameLayout
    private lateinit var video: SurfaceView
    private lateinit var videoFrame: AspectRatioFrameLayout
    private lateinit var subtitles: SubtitleView
    private val progress get() = (application as AliflixApplication).playbackProgressStore
    private val settingsStore get() = (application as AliflixApplication).playerSettingsStore
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updateOutput()
        override fun onDisplayChanged(displayId: Int) = updateOutput()
        override fun onDisplayRemoved(displayId: Int) = updateOutput()
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updateOutput()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestAccepted = savedInstanceState?.getBoolean("requestAccepted") == true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        resolverHost = FrameLayout(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS }
        root.addView(resolverHost, FrameLayout.LayoutParams(-1, -1))
        receiverButton = androidx.mediarouter.app.MediaRouteButton(this).apply {
            visibility = View.INVISIBLE; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        runCatching {
            com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(this, receiverButton)
        }
        root.addView(receiverButton, FrameLayout.LayoutParams(1, 1))
        video = SurfaceView(this)
        video.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = sendSurface()
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = sendSurface()
            override fun surfaceDestroyed(holder: SurfaceHolder) = sendSurface(clear = true)
        })
        videoFrame = AspectRatioFrameLayout(this).apply {
            resizeMode = if (settingsStore.settings.value.resizeModeZoom) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            addView(video, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(videoFrame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        subtitles = SubtitleView(this)
        applySubtitleStyle()
        root.addView(subtitles, FrameLayout.LayoutParams(-1, -1))
        root.addView(ComposeView(this).apply {
            setContent { AliflixMobileTheme {
                val playerSettings by settingsStore.settings.collectAsState()
                NativePlayerScreen(
                    state = ui,
                    player = controller,
                    settings = playerSettings,
                    onBack = { finish() },
                    onRetry = { triedServers.clear(); recoveryCount = 0; prepareSelection() },
                    onServer = { prepareSelection() },
                    onSelectServer = ::selectServer,
                    onStop = ::stopPlayback,
                    onStopCast = {
                        controller?.sendCustomCommand(SessionCommand(NativePlaybackService.ACTION_STOP_CAST, Bundle.EMPTY), Bundle.EMPTY)
                    },
                    onWireless = {
                        runCatching { startActivity(Intent(Settings.ACTION_CAST_SETTINGS)) }
                            .onFailure { ui = ui.copy(message = "Wireless display settings are unavailable on this phone.") }
                    },
                    onFit = { fill ->
                        settingsStore.updateResizeModeZoom(fill)
                        videoFrame.resizeMode = if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
                    },
                    onRotate = {
                        requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    },
                    onSubtitleSearch = { searchSubtitles() },
                    onSubtitle = ::applySubtitle,
                    onSubtitleDisable = ::disableSubtitles,
                    onSubtitleDelayChange = ::updateSubtitleDelay,
                    onSubtitleFontSizeChange = { sizeSp ->
                        settingsStore.updateSubtitleFontSize(sizeSp)
                        applySubtitleStyle()
                    },
                    onSubtitleOpacityChange = { opacity ->
                        settingsStore.updateSubtitleBackgroundOpacity(opacity)
                        applySubtitleStyle()
                    },
                    onSpeedChange = { speed ->
                        settingsStore.updatePlaybackSpeed(speed)
                        controller?.setPlaybackSpeed(speed)
                    },
                    onEpisode = { episode ->
                        selection?.let { current ->
                            recordCurrentProgress(urgent = true)
                            selection = current.copy(seasonNumber = episode.seasonNumber, episodeNumber = episode.number, episodeTitle = episode.title)
                            intent.putExtra("selection", selection!!.nativeJson())
                            triedServers.clear()
                            recoveryCount = 0
                            prepareSelection()
                        }
                    },
                    onReceiver = ::openReceiverPicker,
                    onBrightnessSwipe = ::adjustBrightness,
                    onVolumeSwipe = ::adjustVolume,
                    onControlsVisibilityChanged = ::updateSubtitlePadding,
                )
            } }
        }, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        acceptRequest(intent)
        lifecycleScope.launch {
            while (isActive) {
                updateOutput()
                val current = controller
                if (preparation?.isActive != true && current?.playWhenReady == true && current.playbackState == Player.STATE_BUFFERING) {
                    if (stalledSince == 0L) stalledSince = android.os.SystemClock.elapsedRealtime()
                    if (android.os.SystemClock.elapsedRealtime() - stalledSince > 30_000) recover()
                } else stalledSince = 0
                delay(300)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (intent.hasExtra("selection") || intent.hasExtra("request") || intent.hasExtra("requestFile")) {
            requestAccepted = false; triedServers.clear(); recoveryCount = 0; acceptRequest(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("requestAccepted", requestAccepted); super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        recordCurrentProgress(urgent = true)
        super.onPause()
    }

    private fun activeSelectionKey() = NativePlaybackService.activeRequest?.selectionJson?.let { runCatching { nativeSelection(it).key }.getOrNull() }

    private fun acceptRequest(intent: Intent) {
        selection = intent.getStringExtra("selection")?.let { runCatching { nativeSelection(it) }.getOrNull() }
            ?: NativePlaybackService.activeRequest?.selectionJson?.let { runCatching { nativeSelection(it) }.getOrNull() }
        updateSelectionUi()
        if (selection?.media?.type == com.aliflix.app.model.MediaType.TV && selection?.availableEpisodes.isNullOrEmpty()) {
            val current = checkNotNull(selection)
            episodeQueueJob?.cancel()
            episodeQueueJob = lifecycleScope.launch {
                try {
                    val repository = com.aliflix.app.data.MobileEpisodeRepository(this@NativePlayerActivity,
                        com.aliflix.app.recommendation.RecommendationAiClient(com.aliflix.app.BuildConfig.RECOMMENDATION_AI_BASE_URL))
                    val episodes = repository.episodes(current.media.id, current.seasonNumber ?: 1)
                    if (selection?.key == current.key) { selection = current.copy(availableEpisodes = episodes); updateSelectionUi() }
                } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            }
        }
        if (requestAccepted) {
            if (selection != null && activeSelectionKey() != selection?.key) prepareSelection()
            return
        }
        requestAccepted = true
        val raw = runCatching { nativeRequestPayload(this, intent, consume = true) }.getOrNull()
        intent.removeExtra("request"); intent.removeExtra("requestFile")
        if (raw != null) {
            runCatching { NativePlaybackRequest.fromJson(raw) }.onSuccess(::startNative)
                .onFailure { ui = ui.copy(error = "The saved stream is no longer available. Please choose the title again.") }
        } else if (selection != null && intent.hasExtra("selection") && (activeSelectionKey() != selection?.key || NativePlaybackService.activeStreamUrl == null)) prepareSelection()
    }

    private fun updateSelectionUi() {
        if (introKey != selection?.key) {
            introKey = selection?.key; introJob?.cancel(); ui = ui.copy(segments = emptyList())
            selection?.let { current -> introJob = lifecycleScope.launch {
                val markers = IntroDbRepository(this@NativePlayerActivity).segments(current)
                if (selection?.key == current.key) ui = ui.copy(segments = markers)
            } }
        }
        selection?.let {
            val servers = if (it.source.provider.usesMoviepire) listOf("Vid", "Mist", "Mistify", "Flix", "Peach") else listOf(it.source.provider.displayName)
            ui = ui.copy(
                title = it.media.title,
                detail = if (it.media.type == com.aliflix.app.model.MediaType.TV)
                    "S${it.seasonNumber ?: 1} · E${it.episodeNumber ?: 1}${it.episodeTitle?.let { title -> " · $title" }.orEmpty()}" else it.media.year,
                artwork = it.media.backdropUrl,
                episodes = it.availableEpisodes,
                episodeNumber = it.episodeNumber,
                availableServers = servers,
            )
        }
    }

    private fun selectServer(serverName: String) {
        if (serverName.equals(ui.server, ignoreCase = true) && controller?.playbackState == Player.STATE_READY) return
        recordCurrentProgress(urgent = true)
        ui = ui.copy(server = serverName, message = "Switching to $serverName…")
        prepareSelection(preferredServer = serverName)
    }

    private fun prepareSelection(positionMs: Long? = null, preferredServer: String? = null) {
        val current = selection ?: return
        recordCurrentProgress(urgent = true)
        val resume = positionMs ?: controller?.takeIf { it.currentMediaItem?.mediaId == current.key }?.currentPosition?.takeIf { it > 0 }
            ?: ((progress.progressFor(current)?.takeUnless { it.completed }?.positionSeconds ?: 0.0) * 1000).toLong()
        preparation?.cancel(); resolver?.close(); resolver = null; subtitleJob?.cancel()
        controller?.pause()
        if (preferredServer != null) {
            triedServers.remove(preferredServer)
        }
        val defaultServers = if (current.source.provider.usesMoviepire) listOf("Vid", "Mist", "Mistify", "Flix", "Peach") else listOf(current.source.provider.displayName)
        ui = ui.copy(
            stage = "Preparing your video",
            error = null,
            ready = false,
            server = preferredServer ?: ui.server,
            availableServers = defaultServers,
            subtitleTracks = emptyList(),
            message = null,
        )
        updateSelectionUi()
        preparation = lifecycleScope.launch {
            val initialSubtitles = async {
                if (!intent.getBooleanExtra("autoSubtitles", true)) return@async ""
                val repository = SubdlSubtitleRepository()
                val tracks = repository.search(current).getOrDefault(emptyList())
                ensureActive()
                ui = ui.copy(subtitleTracks = tracks)
                val code = intent.getStringExtra("subtitleLanguage") ?: "EN"
                val track = tracks.firstOrNull { it.languageCode.equals(code, true) } ?: return@async ""
                val cues = repository.download(track).getOrDefault(emptyList())
                ensureActive()
                if (cues.isEmpty()) "" else {
                    val json = JSONArray().apply {
                        cues.forEach { put(JSONArray().put(it.startSeconds).put(it.endSeconds).put(it.text)) }
                    }.toString()
                    activeSubtitleCuesJson = json
                    ui = ui.copy(activeSubtitleTrack = track)
                    nativeSubtitlesVtt(json, settingsStore.settings.value.subtitleDelaySeconds)
                }
            }
            var success = false
            for (attempt in 0 until 9) {
                if (success) break
                ensureActive()
                val adapter = NativeStreamResolver(this@NativePlayerActivity, progress, resolverHost)
                resolver = adapter
                var server = ""
                try {
                    val resolved = adapter.resolve(current, resume, triedServers, preferredServer = if (attempt == 0) preferredServer else null) { label ->
                        server = label
                        ui = ui.copy(server = label, stage = "Preparing your video")
                    }
                    val vtt = withTimeoutOrNull(1500) { initialSubtitles.await() }.orEmpty()
                    val request = resolved.copy(subtitlesVtt = vtt,
                        subtitleLanguage = ui.activeSubtitleTrack?.languageCode?.lowercase() ?: "en",
                        subtitleLabel = ui.activeSubtitleTrack?.languageName ?: "Aliflix subtitles")
                    adapter.close(); resolver = null; hideSystemBars()
                    ui = ui.copy(stage = "Preparing your video")
                    startNative(request)
                    withTimeout(25_000) {
                        while (true) {
                            ensureActive()
                            if (NativePlaybackService.activeStreamUrl == request.url) {
                                NativePlaybackService.playbackFailure?.let { throw it }
                                if (NativePlaybackService.playbackReady && (NativePlaybackService.renderedStreamUrl == request.url || ui.external)) {
                                    check(NativePlaybackService.hasSelectedAudio || ui.external) { "The stream has no supported audio track" }
                                    break
                                }
                            }
                            delay(200)
                        }
                    }
                    success = true; ui = ui.copy(stage = null, ready = true, error = null)
                    if (server.isNotBlank()) triedServers.add(server)
                    if (ui.subtitleTracks.isEmpty()) searchSubtitles()
                } catch (cancelled: CancellationException) {
                    if (cancelled !is TimeoutCancellationException) throw cancelled
                } catch (_: NoNativeServersException) {
                    break
                } catch (_: Exception) {
                    // Retry with a fresh provider page and fresh signed stream URL.
                } finally {
                    adapter.close(); if (resolver === adapter) resolver = null; hideSystemBars()
                }
                if (!success) {
                    controller?.stop()
                    if (server.isNotBlank()) triedServers.add(server)
                    ui = ui.copy(stage = "Trying another server")
                    if (server.isBlank()) break // A failed provider page cannot supply another server.
                }
            }
            if (success && NativePlaybackService.activeRequest?.subtitlesVtt.isNullOrBlank()) {
                val lateVtt = initialSubtitles.await()
                val active = NativePlaybackService.activeRequest
                if (lateVtt.isNotBlank() && active != null && selection?.key == current.key) {
                    startNative(active.copy(subtitlesVtt = lateVtt,
                        subtitleLanguage = ui.activeSubtitleTrack?.languageCode?.lowercase() ?: "en",
                        subtitleLabel = ui.activeSubtitleTrack?.languageName ?: "Aliflix subtitles",
                        positionMs = controller?.currentPosition?.takeIf { it > 0 } ?: active.positionMs,
                        playing = controller?.playWhenReady ?: active.playing))
                }
            } else initialSubtitles.cancel()
            if (!success) ui = ui.copy(stage = null, error = "We couldn't prepare this title. Check your connection and try again. Some servers may be unavailable.")
        }
    }

    private fun startNative(request: NativePlaybackRequest) {
        val name = "native-request-${java.util.UUID.randomUUID()}.json"
        java.io.File(cacheDir, name).writeText(request.toJson())
        val service = Intent(this, NativePlaybackService::class.java).putExtra("requestFile", name)
        if (request.playing) ContextCompat.startForegroundService(this, service) else startService(service)
    }

    private fun recover() {
        if (selection == null || preparation?.isActive == true) return
        stalledSince = 0
        if (recoveryCount++ < 2) prepareSelection()
        else ui = ui.copy(stage = null, error = "Playback was interrupted. Retry to reconnect or choose another server.")
    }

    private fun searchSubtitles(auto: Boolean = false) {
        val current = selection ?: return
        subtitleJob?.cancel()
        subtitleJob = lifecycleScope.launch {
            ui = ui.copy(subtitleLoading = true)
            val result = SubdlSubtitleRepository().search(current)
            ensureActive()
            ui = ui.copy(subtitleLoading = false, subtitleTracks = result.getOrDefault(emptyList()),
                subtitleError = result.exceptionOrNull()?.let { "Subtitles couldn't load. Tap Refresh to retry." })
            if (auto) {
                val code = intent.getStringExtra("subtitleLanguage") ?: "EN"
                ui.subtitleTracks.firstOrNull { it.languageCode.equals(code, true) }?.let(::applySubtitle)
            }
        }
    }

    private fun applySubtitle(track: SubtitleTrack) {
        subtitleJob?.cancel()
        subtitleJob = lifecycleScope.launch {
            ui = ui.copy(subtitleLoading = true)
            val result = SubdlSubtitleRepository().download(track)
            ensureActive()
            result.onSuccess { cues ->
                val request = NativePlaybackService.activeRequest ?: return@onSuccess
                val json = JSONArray().apply { cues.forEach { put(JSONArray().put(it.startSeconds).put(it.endSeconds).put(it.text)) } }.toString()
                activeSubtitleCuesJson = json
                val vtt = nativeSubtitlesVtt(json, settingsStore.settings.value.subtitleDelaySeconds)
                startNative(request.copy(subtitlesVtt = vtt, subtitleLanguage = track.languageCode.lowercase(), subtitleLabel = track.languageName,
                    positionMs = controller?.currentPosition ?: request.positionMs, playing = controller?.playWhenReady ?: true))
                controller?.sendCustomCommand(
                    SessionCommand(NativePlaybackService.ACTION_SET_CAST_SUBTITLES, Bundle.EMPTY),
                    Bundle().apply { putBoolean("enabled", true) }
                )
                ui = ui.copy(activeSubtitleTrack = track, message = "${track.languageName} subtitles enabled")
            }.onFailure { ui = ui.copy(subtitleError = "This subtitle couldn't load. Try another version.") }
            ui = ui.copy(subtitleLoading = false)
        }
    }

    private fun disableSubtitles() {
        activeSubtitleCuesJson = null
        ui = ui.copy(activeSubtitleTrack = null)
        val req = NativePlaybackService.activeRequest
        if (req != null) {
            val pos = controller?.currentPosition ?: req.positionMs
            val playing = controller?.playWhenReady ?: true
            startNative(req.copy(subtitlesVtt = "", positionMs = pos, playing = playing))
        }
        controller?.sendCustomCommand(
            SessionCommand(NativePlaybackService.ACTION_SET_CAST_SUBTITLES, Bundle.EMPTY),
            Bundle().apply { putBoolean("enabled", false) }
        )
    }

    private fun updateSubtitleDelay(tenths: Int) {
        settingsStore.updateSubtitleDelayTenths(tenths)
        subtitleSyncDebounceJob?.cancel()
        subtitleSyncDebounceJob = lifecycleScope.launch {
            delay(300)
            val cuesJson = activeSubtitleCuesJson ?: return@launch
            val req = NativePlaybackService.activeRequest ?: return@launch
            val vtt = nativeSubtitlesVtt(cuesJson, tenths / 10.0)
            val pos = controller?.currentPosition ?: req.positionMs
            val playing = controller?.playWhenReady ?: true
            startNative(req.copy(subtitlesVtt = vtt, positionMs = pos, playing = playing))
        }
    }

    private var volumeAccumulator = 0f

    private fun updateSubtitlePadding(controlsVisible: Boolean) {
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val bottomDp = if (isPortrait) {
            if (controlsVisible) 175 else 72
        } else {
            if (controlsVisible) 56 else 16
        }
        val density = resources.displayMetrics.density
        val bottomPx = (bottomDp * density).toInt()
        val horizontalPx = (16 * density).toInt()
        subtitles.setPadding(horizontalPx, 0, horizontalPx, bottomPx)
    }

    private fun applySubtitleStyle() {
        val sizeSp = settingsStore.settings.value.subtitleFontSizeSp
        val bgOpacity = settingsStore.settings.value.subtitleBackgroundOpacity
        subtitles.setFixedTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        val alpha = (bgOpacity * 255).toInt().coerceIn(0, 255)
        val bgColor = android.graphics.Color.argb(alpha, 0, 0, 0)
        val style = CaptionStyleCompat(
            android.graphics.Color.WHITE,
            bgColor,
            android.graphics.Color.TRANSPARENT,
            CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
            android.graphics.Color.BLACK,
            null
        )
        subtitles.setStyle(style)
        updateSubtitlePadding(controlsVisible = true)
    }

    private fun adjustBrightness(delta: Float): Float {
        val lp = window.attributes
        val current = if (lp.screenBrightness < 0f) {
            try {
                Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
            } catch (_: Exception) { 0.5f }
        } else lp.screenBrightness
        val target = (current + delta).coerceIn(0.01f, 1.0f)
        lp.screenBrightness = target
        window.attributes = lp
        return target
    }

    private fun adjustVolume(delta: Float): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeAccumulator += delta * max * 1.2f
        if (kotlin.math.abs(volumeAccumulator) >= 1.0f) {
            val step = volumeAccumulator.toInt()
            volumeAccumulator -= step
            val target = (current + step).coerceIn(0, max)
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            return target.toFloat() / max.coerceAtLeast(1)
        }
        return current.toFloat() / max.coerceAtLeast(1)
    }

    private fun recordCurrentProgress(urgent: Boolean = true) {
        val sel = selection ?: return
        val c = controller ?: return
        if (c.currentMediaItem?.mediaId != sel.key || c.playbackState !in setOf(Player.STATE_READY, Player.STATE_ENDED)) return
        val dur = c.duration.toDouble() / 1000.0
        val pos = c.currentPosition.toDouble() / 1000.0
        if (dur > 0.0 && pos >= 0.0) {
            progress.savePlayerProgress(sel, pos, dur, urgentCloudSync = urgent, ended = c.playbackState == Player.STATE_ENDED)
        }
    }

    override fun onStart() {
        super.onStart()
        displays.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        val future = MediaController.Builder(this, SessionToken(this, ComponentName(this, NativePlaybackService::class.java))).buildAsync()
        controllerFuture = future
        future.addListener({
            if (controllerFuture !== future) return@addListener
            runCatching { future.get() }.onSuccess {
                controller = it
                it.addListener(listener)
                it.setPlaybackSpeed(settingsStore.settings.value.playbackSpeed)
                sendSurface()
                updateOutput()
            }.onFailure { ui = ui.copy(error = "Unable to connect to playback. Please retry.") }
        }, mainExecutor)
    }

    private fun updateOutput() {
        val current = controller ?: return
        val external = !NativePlaybackService.castingSuppressed && (current.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE || displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).isNotEmpty())
        videoFrame.visibility = if (external) View.INVISIBLE else View.VISIBLE
        val size = current.videoSize
        if (size.height > 0) videoFrame.setAspectRatio(size.width * size.pixelWidthHeightRatio / size.height)
        subtitles.setCues(current.currentCues.cues); subtitles.visibility = if (external) View.GONE else View.VISIBLE
        ui = ui.copy(external = external, revision = ui.revision + 1,
            ready = ui.ready || (ui.stage == null && current.playbackState == Player.STATE_READY),
            title = selection?.media?.title ?: current.mediaMetadata.title?.toString().orEmpty().ifBlank { "Aliflix" })
        if (current.playerError != null && preparation?.isActive != true && ui.error == null) {
            if (selection != null) recover() else ui = ui.copy(error = "This stream couldn't play. Choose the title again to get a fresh stream.")
        }
    }

    private fun openReceiverPicker() {
        ui = ui.copy(message = null)
        val permission = "android.permission.ACCESS_LOCAL_NETWORK"
        if (android.os.Build.VERSION.SDK_INT >= 37 && checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            receiverPermission.launch(permission); return
        }
        runCatching {
            com.google.android.gms.cast.framework.CastButtonFactory.setUpMediaRouteButton(this, receiverButton)
            if (!receiverButton.showDialog()) {
                receiverButton.performClick()
            }
        }.onFailure { ui = ui.copy(message = "Google Cast is unavailable on this phone.") }
    }

    private fun stopPlayback() {
        recordCurrentProgress(urgent = true)
        preparation?.cancel(); controller?.stop(); controller?.clearMediaItems()
        startService(Intent(this, NativePlaybackService::class.java).setAction(NativePlaybackService.ACTION_STOP)); finish()
    }
    override fun onStop() {
        recordCurrentProgress(urgent = true)
        displays.unregisterDisplayListener(displayListener)
        sendSurface(clear = true); controller?.removeListener(listener); controller = null
        controllerFuture?.let(MediaController::releaseFuture); controllerFuture = null
        super.onStop()
    }
    override fun onDestroy() {
        recordCurrentProgress(urgent = true)
        preparation?.cancel(); resolver?.close(); resolver = null; subtitleJob?.cancel(); subtitleSyncDebounceJob?.cancel(); episodeQueueJob?.cancel(); introJob?.cancel(); super.onDestroy()
    }
    private fun sendSurface(clear: Boolean = false) {
        val surface = video.holder.surface.takeIf { !clear && it.isValid }
        NativePlaybackService.attachSurface(surfaceOwner, surface, tv = false)
    }
    private fun hideSystemBars() = WindowInsetsControllerCompat(window, window.decorView).let {
        it.hide(WindowInsetsCompat.Type.systemBars()); it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}

