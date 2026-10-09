package com.aliflix.app.player

import com.aliflix.app.model.PlaybackProvider
import com.aliflix.app.model.PlaybackProviderId
import com.aliflix.app.model.MobilePlaybackProvider
import com.aliflix.app.model.isJapaneseAnime

import com.aliflix.app.data.hasInternetConnection
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
import androidx.activity.addCallback
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.*
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
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
    internal val resolverViewCount get() = if (::resolverHost.isInitialized) resolverHost.childCount else 0
    private var selection: PlaybackSelection? = null
    private var preparation: Job? = null
    private var subtitleJob: Job? = null
    private var subtitleSyncDebounceJob: Job? = null
    private var audioSyncJob: Job? = null
    private var referencePreparation: Job? = null
    private var referencePreparationKey: String? = null
    private var syncReferences: List<SubtitleSyncReference> = emptyList()
    private var audioCorrection: AudioSubtitleCorrection? = null
    private var audioCorrectionKey: String? = null
    private var correctionIdentity: List<String?>? = null
    private val correctionStore by lazy { SubtitleCorrectionStore(this) }
    private var subtitleRenderJob: Job? = null
    internal val preparedSyncReferences get() = syncReferences.toList()
    internal var subtitleTimingEvidence: SubtitleTimingMatch? = null
        private set
    private var activeSubtitleCues: List<SubtitleCue> = emptyList()
    private var activeSubtitleCuesJson: String? = null
    private var episodeQueueJob: Job? = null
    private var nextEpisodeWarmup: Job? = null
    private var warmingEpisodeKey: String? = null
    private var warmedEpisode: Triple<PlaybackSelection, String, NativePlaybackRequest>? = null
    private var warmedAt = 0L
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
    private val exhaustedSources = linkedSetOf<com.aliflix.app.model.PlaybackProvider>()
    private lateinit var resolverHost: FrameLayout
    private lateinit var video: SurfaceView
    private lateinit var videoFrame: AspectRatioFrameLayout
    private lateinit var subtitles: SubtitleView
    private lateinit var playerRoot: FrameLayout
    private val progress get() = (application as AliflixApplication).playbackProgressStore
    private val settingsStore get() = (application as AliflixApplication).playerSettingsStore
    private val captionPreferences by lazy { getSharedPreferences("account-caption-files", MODE_PRIVATE) }
    private val subtitleChoices by lazy { getSharedPreferences("native-subtitle-choice", MODE_PRIVATE) }
    private val syncedCaptionListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        reconcileSyncedCaptions()
    }

    private fun reconcileSyncedCaptions() {
        val current = selection ?: return
        if (ui.stage != null || !ui.ready || controller == null) return
        val enabled = subtitleChoices.getBoolean("enabled", intent.getBooleanExtra("autoSubtitles", true))
        if (!enabled) {
            if (activeSubtitleCuesJson != null) disableSubtitles()
            return
        }
        // The initial Flixer catalogue wins over a previously saved external search result.
        if (current.source.identity == com.aliflix.app.model.MobilePlaybackProvider.FLIXER &&
            (ui.activeSubtitleTrack == null || subtitleJob?.isActive == true)) return
        val language = subtitleChoices.getString("language", null) ?: intent.getStringExtra("subtitleLanguage") ?: "EN"
        val raw = captionPreferences.getString(captionKey(current), null) ?: return
        val saved = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return
        if (saved.optString("cues") == activeSubtitleCuesJson) return
        if (restoreCaption(current, language)) subtitleJob?.cancel()
    }

    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val displays by lazy { getSystemService(DisplayManager::class.java) }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updateOutput()
        override fun onDisplayChanged(displayId: Int) = updateOutput()
        override fun onDisplayRemoved(displayId: Int) = updateOutput()
    }
    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            updateOutput()
            updateSubtitleCues()
        }
        override fun onCues(cueGroup: CueGroup) {
            renderCaptions(NativePlaybackService.currentCaptionCues())
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        captionPreferences.registerOnSharedPreferenceChangeListener(syncedCaptionListener)
        subtitleChoices.registerOnSharedPreferenceChangeListener(syncedCaptionListener)
        if (savedInstanceState == null) PlaybackStartupTiming.begin(intent.getLongExtra("playTapElapsedMs", android.os.SystemClock.elapsedRealtime()))
        onBackPressedDispatcher.addCallback(this) { pauseAndLeavePlayer() }
        requestAccepted = savedInstanceState?.getBoolean("requestAccepted") == true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideSystemBars()
        val root = FrameLayout(this).apply { setBackgroundColor(android.graphics.Color.BLACK) }
        playerRoot = root
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateSubtitlePadding() }
        resolverHost = FrameLayout(this).apply {
            alpha = 0f
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
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
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                updateSubtitlePadding(lastControlsVisible)
            }
        }
        root.addView(videoFrame, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        videoFrame.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            ui = ui.copy(videoBottomPx = view.bottom)
        }
        subtitles = SubtitleView(this)
        subtitles.setApplyEmbeddedStyles(false)
        subtitles.setApplyEmbeddedFontSizes(false)
        applySubtitleStyle()
        lifecycleScope.launch {
            var previousDelay = settingsStore.settings.value.subtitleDelayTenths
            settingsStore.settings.collect {
                applySubtitleStyle()
                updateSubtitlePadding(lastControlsVisible)
                controller?.setPlaybackSpeed(it.playbackSpeed)
                if (previousDelay != it.subtitleDelayTenths) {
                    previousDelay = it.subtitleDelayTenths
                    activeSubtitleCuesJson?.let { json -> NativePlaybackService.updateSubtitles(renderSubtitleVtt(json, it.subtitleDelaySeconds)) }
                }
            }
        }
        root.addView(subtitles, FrameLayout.LayoutParams(-1, -1))
        root.addView(ComposeView(this).apply {
            setContent { AliflixMobileTheme {
                val playerSettings by settingsStore.settings.collectAsState()
                NativePlayerScreen(
                    state = ui,
                    player = controller,
                    settings = playerSettings,
                    onBack = ::pauseAndLeavePlayer,
                    onResumeClicked = { selection?.let { (application as AliflixApplication).libraryStore.markPlayed(it.media) } },
                    onRetry = retry@{
                        if (NativePlaybackService.retryCineJoyPlayback(manual = true)) {
                            ui = ui.copy(error = null, stage = null)
                            return@retry
                        }
                        exhaustedSources.clear(); triedServers.clear(); recoveryCount = 0
                        prepareSelection()
                    },
                    onServer = { prepareSelection() },
                    onSelectServer = ::selectServer,
                    onBrowseProvider = ::browseProvider,
                    onSelectProviderServer = ::selectProviderServer,
                    onRetryProviderServers = ::retryProviderDiscovery,
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
                        videoFrame.post { updateSubtitlePadding(lastControlsVisible) }
                    },
                    onRotate = {
                        requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    },
                    onSubtitleSearch = { searchSubtitles() },
                    onSubtitle = ::applySubtitle,
                    onAudioSelected = { group, index ->
                        if (selection?.source?.identity == PlaybackProviderId.CINEJOY && !ui.external) {
                            NativePlaybackService.selectAudio(group, index)
                        } else {
                            controller?.let { player ->
                                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                    .setOverrideForType(TrackSelectionOverride(group, index)).build()
                            }
                            val format = group.getFormat(index)
                            NativePlaybackService.saveAudioPreference(format.language, format.label)
                        }
                    },
                    onSubtitleDisable = ::disableSubtitles,
                    onSubtitleDelayChange = ::updateSubtitleDelay,
                    onSyncWithAudio = ::syncWithAudio,
                    onResetSubtitleSync = ::resetSubtitleSync,
                    onSubtitleVerticalOffsetChange = { offsetDp ->
                        settingsStore.updateSubtitleVerticalOffsetDp(offsetDp)
                        updateSubtitlePadding(lastControlsVisible)
                    },
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
                    onEpisode = { selectEpisode(it, recordPlay = true) },
                    onAutoEpisode = { selectEpisode(it, recordPlay = false) },
                    onReceiver = ::openReceiverPicker,
                    onBrightnessSwipe = ::adjustBrightness,
                    onVolumeSwipe = ::adjustVolume,
                    onVolumeGestureStarted = { volumeAccumulator = 0f },
                    onControlsVisibilityChanged = ::updateSubtitlePadding,
                    onOverlayVisibilityChanged = { captionGestureBlocked = it },
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
                    if (NativePlaybackService.handlesCineJoyRecovery) { stalledSince = 0; delay(300); continue }
                    if (stalledSince == 0L) stalledSince = android.os.SystemClock.elapsedRealtime()
                    if (android.os.SystemClock.elapsedRealtime() - stalledSince > 30_000) recover()
                } else stalledSince = 0
                delay(300)
            }
        }
    }

    private var captionGestureBlocked = false
    private var captionPreviewOffset: Int? = null
    private var renderedCaptions: List<Cue> = emptyList()
    private val captionDrag by lazy {
        CaptionDragGesture(this, ::captionBounds, { settingsStore.settings.value.subtitleVerticalOffsetDp },
            { super.dispatchTouchEvent(it) },
            {
                ui = ui.copy(captionDragging = true)
                subtitles.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            },
            { captionPreviewOffset = it; applySubtitlePadding() },
            { commit ->
                if (commit) captionPreviewOffset?.let(settingsStore::updateSubtitleVerticalOffsetDp)
                captionPreviewOffset = null
                ui = ui.copy(captionDragging = false)
                updateSubtitlePadding()
            })
    }

    internal fun captionBounds(): android.graphics.RectF? {
        if (captionGestureBlocked || ui.external || renderedCaptions.isEmpty() || !subtitles.isShown) return null
        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                settingsStore.settings.value.subtitleFontSizeSp, resources.displayMetrics)
        }
        val available = ((subtitles.width - subtitles.paddingLeft - subtitles.paddingRight) * 0.92f).toInt()
        if (available <= 0) return null
        val layouts = renderedCaptions.mapNotNull { cue -> cue.text?.let {
            android.text.StaticLayout.Builder.obtain(it, 0, it.length, paint, available)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).build()
        } }
        val height = layouts.maxOfOrNull { it.height } ?: return null
        val width = layouts.maxOfOrNull { layout -> (0 until layout.lineCount).maxOf { layout.getLineWidth(it) } } ?: return null
        val bottom = (subtitles.height - subtitles.paddingBottom).toFloat()
        val margin = 12 * resources.displayMetrics.density
        return android.graphics.RectF((subtitles.width - width) / 2 - margin, bottom - height - margin,
            (subtitles.width + width) / 2 + margin, bottom + margin)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::subtitles.isInitialized && captionDrag.onTouch(event)) return true
        return super.dispatchTouchEvent(event)
    }

    internal fun pauseAndLeavePlayer() {
        preparation?.cancel(); resolver?.close(); resolver = null
        subtitleJob?.cancel()
        controller?.pause()
        NativePlaybackService.pauseFromBack()
        recordCurrentProgress(urgent = true)
        finish()
    }

    override fun onNewIntent(intent: Intent) {
        recordCurrentProgress(urgent = true)
        super.onNewIntent(intent); setIntent(intent)
        if (intent.hasExtra("selection") || intent.hasExtra("request") || intent.hasExtra("requestFile")) {
            requestAccepted = false; exhaustedSources.clear(); triedServers.clear(); recoveryCount = 0; acceptRequest(intent)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("requestAccepted", requestAccepted); super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        recordCurrentProgress(urgent = true)
        super.onPause()
    }

    private fun selectEpisode(episode: Episode, recordPlay: Boolean) {
        val current = selection ?: return
        if (recordPlay) (application as AliflixApplication).libraryStore.markPlayed(current.media)
        recordCurrentProgress(urgent = true)
        selection = current.copy(seasonNumber = episode.seasonNumber, episodeNumber = episode.number, episodeTitle = episode.title)
        intent.putExtra("selection", selection!!.nativeJson())
        exhaustedSources.clear(); triedServers.clear()
        recoveryCount = 0
        loadEpisodeQueue()
        prepareSelection()
    }

    private fun loadEpisodeQueue() {
        val current = selection?.takeIf { it.media.type == com.aliflix.app.model.MediaType.TV } ?: return
        episodeQueueJob?.cancel()
        episodeQueueJob = lifecycleScope.launch {
            try {
                val repository = com.aliflix.app.data.MobileEpisodeRepository(this@NativePlayerActivity,
                    com.aliflix.app.recommendation.RecommendationAiClient(com.aliflix.app.BuildConfig.RECOMMENDATION_AI_BASE_URL))
                val season = current.seasonNumber ?: 1
                val episodes = repository.episodes(current.media.id, season)
                fun merge(additions: List<Episode>) {
                    val selected = selection?.takeIf { it.media.key == current.media.key } ?: return
                    selection = selected.copy(availableEpisodes = (selected.availableEpisodes.filterNot { old ->
                        additions.any { it.seasonNumber == old.seasonNumber && it.number == old.number }
                    } + additions).sortedWith(compareBy({ it.seasonNumber }, { it.number })))
                    updateSelectionUi()
                }
                merge(episodes)
                val followingSeason = repository.seasons(current.media.id)
                    .firstOrNull { it.number > season && it.episodeCount > 0 }
                if (followingSeason != null) merge(repository.episodes(current.media.id, followingSeason.number))
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
        }
    }

    private fun activeSelectionKey() = NativePlaybackService.activeRequest?.selectionJson?.let { runCatching { nativeSelection(it).key }.getOrNull() }

    private fun acceptRequest(intent: Intent) {
        selection = intent.getStringExtra("selection")?.let { runCatching { nativeSelection(it) }.getOrNull() }
            ?: NativePlaybackService.activeRequest?.selectionJson?.let { runCatching { nativeSelection(it) }.getOrNull() }
        updateSelectionUi()
        loadEpisodeQueue()
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

    private val resolvedServerNames = mutableMapOf<String, List<String>>()
    private val discoveredServers = mutableMapOf<com.aliflix.app.model.PlaybackProvider, List<MoviepireServerOption>>()
    private var discoveryJob: Job? = null

    private fun availableProvidersFor(current: PlaybackSelection): List<com.aliflix.app.model.PlaybackProvider> =
        (com.aliflix.app.model.mobileGeneralPlaybackProviders().filter { it.isAvailableFor(current.media) } +
            current.source.identity).distinct()

    /** Level-one drill-in: lists a source's servers without touching the running stream. */
    private fun browseProvider(provider: com.aliflix.app.model.PlaybackProvider) {
        val current = selection ?: return
        if (provider == current.source.identity) return
        val cached = discoveredServers[provider].orEmpty()
        if (cached.isNotEmpty()) {
            ui = ui.copy(providerDiscovery = ProviderServerDiscovery(provider = provider, servers = cached))
            return
        }
        if (ui.providerDiscovery.provider == provider && ui.providerDiscovery.loading) return
        ui = ui.copy(providerDiscovery = ProviderServerDiscovery(provider = provider, loading = true))
        discoveryJob?.cancel()
        discoveryJob = lifecycleScope.launch {
            val names = try {
                withTimeout(15_000) { discoverServerNames(provider, current) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                emptyList()
            }
            if (selection?.key != current.key) return@launch
            val options = names.map { name -> MoviepireServerOption(key = name, label = name, selected = false) }
            if (options.isEmpty()) {
                ui = ui.copy(providerDiscovery = ProviderServerDiscovery(provider = provider, failed = true))
            } else {
                discoveredServers[provider] = options
                ui = ui.copy(providerDiscovery = ProviderServerDiscovery(provider = provider, servers = options))
            }
        }
    }

    private fun retryProviderDiscovery() {
        ui.providerDiscovery.provider?.let(::browseProvider)
    }

    private suspend fun discoverServerNames(
        provider: com.aliflix.app.model.PlaybackProvider,
        current: PlaybackSelection,
    ): List<String> {
        val preferences = com.aliflix.app.data.PlaybackProviderRepository(this).preferences.value
        val probe = current.copy(source = preferences.sourceFor(current.media, provider))
        return when (provider) {
            com.aliflix.app.model.PlaybackProviderId.CINEJOY ->
                CineJoyNativeCatalog.fetchServers().map(CineJoyNativeCatalog::serverLabel)
            com.aliflix.app.model.MobilePlaybackProvider.MOVY,
            com.aliflix.app.model.MobilePlaybackProvider.FLIXER -> listOf(provider.displayName)
            com.aliflix.app.model.PlaybackProviderId.MOVIEPIRE,
            com.aliflix.app.model.MobilePlaybackProvider.SEVEN_MOVIES -> preferredNativeEmbeds(probe).map { it.first }
            com.aliflix.app.model.PlaybackProviderId.MIRURO,
            com.aliflix.app.model.PlaybackProviderId.ANIKURO,
            -> listOf(provider.displayName)
            else -> FmovieNativeCatalog().embeds(probe).map { it.first }
        }
    }

    /** Level-two pick inside a source that is not playing: move playback onto it. */
    private fun selectProviderServer(
        provider: com.aliflix.app.model.PlaybackProvider,
        serverName: String,
    ) {
        val current = selection ?: return
        if (provider == current.source.identity) {
            selectServer(serverName)
            return
        }
        val preferences = com.aliflix.app.data.PlaybackProviderRepository(this).preferences.value
        exhaustedSources.remove(provider)
        triedServers.remove(serverName)
        recordCurrentProgress(urgent = true)
        selection = current.copy(source = preferences.sourceFor(current.media, provider))
        ui = ui.copy(server = serverName, message = "Switching to ${provider.displayName} · $serverName…")
        prepareSelection(preferredServer = serverName, strictPreferredServer = true)
    }

    private fun updateSelectionUi() {
        if (introKey != selection?.key) {
            introKey = selection?.key; introJob?.cancel(); ui = ui.copy(segments = emptyList())
            selection?.let { current -> introJob = lifecycleScope.launch {
                MobileSkipMarkers(this@NativePlayerActivity).discover(current, { controller?.duration ?: 0L }) { markers ->
                    if (selection?.key == current.key) ui = ui.copy(segments = markers)
                }
            } }
        }
        selection?.let {
            val servers = resolvedServerNames[it.key] ?: when {
                it.source.identity == com.aliflix.app.model.PlaybackProviderId.CINEJOY ->
                    CineJoyNativeCatalog.FALLBACK_SERVERS.map(CineJoyNativeCatalog::serverLabel)
                it.source.identity.usesMoviepire -> listOf("Vid", "Mist", "Mistify", "Flix", "Peach")
                else -> listOf(it.source.identity.displayName)
            }
            ui = ui.copy(
                title = it.media.title,
                detail = if (it.media.type == com.aliflix.app.model.MediaType.TV)
                    "S${it.seasonNumber ?: 1} E${it.episodeNumber ?: 1}${it.episodeTitle?.let { title -> " • $title" }.orEmpty()}" else it.media.year,
                artwork = it.media.backdropUrl,
                episodes = it.availableEpisodes,
                episodeNumber = it.episodeNumber,
                availableServers = servers,
                availableProviders = availableProvidersFor(it),
                playbackSelection = it,
            )
        }
    }

    private fun selectServer(serverName: String) {
        if (serverName.equals(ui.server, ignoreCase = true) && controller?.playbackState == Player.STATE_READY) return
        recordCurrentProgress(urgent = true)
        ui = ui.copy(server = serverName, message = "Switching to $serverName…")
        prepareSelection(preferredServer = serverName, strictPreferredServer = true)
    }

    private val routeStore by lazy { PlaybackRouteStore(java.io.File(noBackupFilesDir, "playback-routes")) }

    private fun prepareSelection(positionMs: Long? = null, preferredServer: String? = null,
        strictPreferredServer: Boolean = false) {
        val current = selection ?: return
        // Server lists are per title and per episode, so never reuse a stale browse result.
        discoveryJob?.cancel()
        discoveredServers.clear()
        ui = ui.copy(providerDiscovery = ProviderServerDiscovery())
        recordCurrentProgress(urgent = true)
        val startingSource = current.source
        val active = NativePlaybackService.activeRequest?.selectionJson?.let { runCatching { nativeSelection(it) }.getOrNull() }
        val resume = positionMs ?: controller?.takeIf {
            mobileSameContent(active, current) && NativePlaybackService.playbackReady && it.currentPosition > 0
        }?.currentPosition ?: mobileSavedResumeMs(progress.progressFor(current))
        if (warmingEpisodeKey != current.key) nextEpisodeWarmup?.cancel()
        preparation?.cancel(); resolver?.close(); resolver = null; subtitleJob?.cancel()
        val offlineStore = com.aliflix.app.downloads.OfflineDownloads.get(this)
        val cached = offlineStore.manager.downloadIndex.getDownload(com.aliflix.app.data.playbackProgressKey(current))
        if (cached?.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED) {
            val saved = NativePlaybackRequest.fromJson(org.json.JSONObject(String(cached.request.data, Charsets.UTF_8)).getString("playback"))
            val downloadedEpisodes = offlineStore.entries.value.filter { it.download.state == androidx.media3.exoplayer.offline.Download.STATE_COMPLETED && it.selection.media.key == current.media.key }
                .mapNotNull { it.selection.let { s -> if (s.media.type == com.aliflix.app.model.MediaType.TV) com.aliflix.app.model.Episode(s.seasonNumber ?: 1, s.episodeNumber ?: 1, s.episodeTitle.orEmpty()) else null } }
            selection = current.copy(availableEpisodes = downloadedEpisodes)
            ui = ui.copy(stage = "Loading download", error = null, ready = false, server = "", availableServers = emptyList())
            activeSubtitleCues = emptyList()
            activeSubtitleCuesJson = null
            renderCaptions(emptyList())
            controller?.pause()
            preparation = lifecycleScope.launch {
                try {
                    val attempt = startNative(saved.copy(positionMs = resume, playing = false, selectionJson = selection!!.nativeJson()))
                    awaitNativeReady(saved.url, attempt)
                    ui = ui.copy(stage = null, ready = true, error = null)
                    reconcileSyncedCaptions()
                    controller?.play()
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    controller?.pause()
                    ui = ui.copy(stage = null, ready = false, error = "Download unavailable. Retry.")
                }
            }
            return
        }
        if (!hasInternetConnection()) {
            controller?.pause()
            ui = ui.copy(stage = null, ready = false, error = "Connect to the internet and try again")
            return
        }
        subtitleTimingEvidence = null
        activeSubtitleCues = emptyList()
        activeSubtitleCuesJson = null
        renderCaptions(emptyList())
        controller?.pause()
        if (preferredServer != null) {
            exhaustedSources.remove(current.source.identity)
            triedServers.remove(preferredServer)
        }
        val defaultServers = resolvedServerNames[current.key] ?: when {
            current.source.identity == com.aliflix.app.model.PlaybackProviderId.CINEJOY ->
                CineJoyNativeCatalog.FALLBACK_SERVERS.map(CineJoyNativeCatalog::serverLabel)
            current.source.identity.usesMoviepire -> listOf("Vid", "Mist", "Mistify", "Flix", "Peach")
            else -> listOf(current.source.identity.displayName)
        }
        ui = ui.copy(
            stage = "Preparing your video",
            error = null,
            ready = false,
            server = preferredServer ?: ui.server,
            availableServers = defaultServers,
            subtitleTracks = emptyList(),
            activeSubtitleTrack = null,
            message = null,
        )
        updateSelectionUi()
        preparation = lifecycleScope.launch {
            val choices = getSharedPreferences("native-subtitle-choice", MODE_PRIVATE)
            val auto = choices.getBoolean("enabled", intent.getBooleanExtra("autoSubtitles", true))
            val language = canonicalSubtitleLanguageCode(choices.getString("language", null) ?: intent.getStringExtra("subtitleLanguage") ?: "EN")
            val originalCaptions = if (current.source.identity == com.aliflix.app.model.MobilePlaybackProvider.FLIXER)
                launch { FlixerSubtitleRepository.tracks(current) } else null
            try {
                val preferences = com.aliflix.app.data.PlaybackProviderRepository(this@NativePlayerActivity).preferences.value
                val animeSeries = current.media.type == com.aliflix.app.model.MediaType.TV && current.media.isJapaneseAnime
                val history = getSharedPreferences("native-resolver-performance", MODE_PRIVATE)
                val seriesKey = "series:${current.media.key}"
                var detailWarm = if (preferredServer == null) DetailPreloadStore.take(current, resume)
                    ?.takeIf { !animeSeries || it.first.source.identity.isAnimeNative } else null
                val savedRoute = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { routeStore.load(current) }
                    ?.takeIf { preferredServer == null &&
                        current.source.identity != com.aliflix.app.model.PlaybackProviderId.CINEJOY &&
                        it.selection.source.identity == current.source.identity &&
                        (!animeSeries || it.selection.source.identity.isAnimeNative || preferredServer != null) &&
                        it.selection.source.identity !in exhaustedSources &&
                        (!current.source.identity.isAnimeNative || it.selection.source.identity == current.source.identity) }
                val savedProvider = savedRoute?.selection?.source?.identity?.name
                    ?: history.getString("$seriesKey:provider", null)?.takeIf { it == current.source.identity.name }
                val sources = (listOfNotNull(savedRoute?.selection) + playbackSourceFallbacks(current, preferences))
                    .distinctBy { it.source }
                    .filter { it.source.identity !in exhaustedSources && (!strictPreferredServer || it.source == current.source) }
                val orderedSources = sources.sortedBy {
                    (if (animeSeries && preferredServer == null && !it.source.identity.isAnimeNative) 2 else 0) +
                        if (it.source.identity.name == savedProvider) 0 else 1
                }
                val excludedBySource = mutableMapOf<com.aliflix.app.model.PlaybackProvider, MutableSet<String>>()
                excludedBySource[current.source.identity] = triedServers.toMutableSet()
                val cineJoyRetries = mutableMapOf<String, Int>()
                var cineJoyRetryServer: String? = null
                var cineJoyLowQuality = false
                var preferSaved = preferredServer != null || (savedProvider != null && !animeSeries)
                if (detailWarm == null && savedRoute?.request != null && triedServers.isEmpty()) {
                    val restored = savedRoute.selection
                    val request = savedRoute.request.copy(positionMs = resume, playing = false,
                        selectionJson = restored.nativeJson(), preferEmbeddedSubtitles = auto, subtitleLanguage = language.lowercase())
                    try {
                        ui = ui.copy(server = savedRoute.server)
                        val attempt = startNative(request)
                        withTimeout(4_000) { awaitNativeReady(request.url, attempt) }
                        intent.putExtra("selection", restored.nativeJson())
                        ui = ui.copy(stage = null, ready = true, error = null)
                        reconcileSyncedCaptions()
                        controller?.play()
                        if (auto) loadAutomaticSubtitles(restored, language, request.url)
                        warmNextEpisode(restored, savedRoute.server)
                        return@launch
                    } catch (error: Exception) {
                        ensureActive()
                        controller?.stop()
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { routeStore.invalidateStream(current) }
                        // Refresh this exact server before permitting any other provider.
                    }
                }
                repeat(if (strictPreferredServer) 2 else orderedSources.size + 2) {
                    ensureActive()
                    val candidates = orderedSources.filter { it.source.identity !in exhaustedSources }
                    if (candidates.isEmpty()) return@repeat
                    // Anime-native sources are raced together so whichever one's video starts first wins.
                    val batch = if (preferSaved) listOf(candidates.firstOrNull {
                        if (preferredServer != null) it.source == startingSource else it.source.identity.name == savedProvider
                    } ?: candidates.first()) else initialPlaybackRace(candidates)
                    preferSaved = false
                    if (warmingEpisodeKey == current.key && nextEpisodeWarmup?.isActive == true) {
                        kotlinx.coroutines.withTimeoutOrNull(2_000) { nextEpisodeWarmup?.join() }
                        if (nextEpisodeWarmup?.isActive == true) nextEpisodeWarmup?.cancel()
                    }
                    val warmed = detailWarm ?: warmedEpisode?.takeIf { it.first.key == current.key && preferredServer == null && android.os.SystemClock.elapsedRealtime() - warmedAt < 120_000 }?.let { it.copy(third = it.third.copy(positionMs = resume)) }
                    detailWarm = null
                    warmedEpisode = null
                    val winner = try {
                        warmed ?: firstSuccessful(batch.map { candidate -> suspend {
                            val adapter = NativeStreamResolver(this@NativePlayerActivity, progress, resolverHost)
                            var server = ""
                            val excluded = excludedBySource.getOrPut(candidate.source.identity) { mutableSetOf() }
                            val savedServer = if (candidate.source == savedRoute?.selection?.source) savedRoute.server else if (candidate.source.identity.name == savedProvider) history.getString("$seriesKey:server", null) else null
                            try {
                                val request = withTimeout(when (candidate.source.identity) {
                                    com.aliflix.app.model.PlaybackProviderId.MIRURO -> 45_000
                                    com.aliflix.app.model.PlaybackProviderId.ANIKURO -> 60_000
                                    com.aliflix.app.model.PlaybackProviderId.CINEJOY,
                                    com.aliflix.app.model.MobilePlaybackProvider.FLIXER,
                                    com.aliflix.app.model.MobilePlaybackProvider.SEVEN_MOVIES,
                                    com.aliflix.app.model.MobilePlaybackProvider.MOVY -> 35_000
                                    else -> 16_000
                                }) {
                                    adapter.resolve(candidate, resume, excluded, strictPreferredServer = (strictPreferredServer && candidate.source == startingSource) || (candidate.source.identity == com.aliflix.app.model.PlaybackProviderId.CINEJOY && cineJoyRetryServer != null),
                                        validateSingle = true,
                                        forceLowQuality = candidate.source.identity == com.aliflix.app.model.PlaybackProviderId.CINEJOY && cineJoyLowQuality,
                                        preferredServer = (if (candidate.source.identity == com.aliflix.app.model.PlaybackProviderId.CINEJOY) cineJoyRetryServer else null) ?: (if (candidate.source == startingSource) preferredServer?.takeUnless { it in excluded } else null) ?: savedServer?.takeUnless { it in excluded },
                                        onServers = { names -> if (names.isNotEmpty()) resolvedServerNames[candidate.key] = (resolvedServerNames[candidate.key].orEmpty() + names).distinct() }) { server = it }
                                }
                                Triple(candidate, server, request)
                            } catch (error: Exception) {
                                ensureActive()
                                if (candidate.source.identity.isAnimeNative) android.util.Log.d("AliflixAnime",
                                    "source_failed:${candidate.source.identity.name}:${error.javaClass.simpleName}")
                                if (server.isNotBlank() && !strictPreferredServer) excluded.add(server)
                                if (candidate.source.identity == com.aliflix.app.model.PlaybackProviderId.CINEJOY && cineJoyRetryServer != null) {
                                    excluded.add(checkNotNull(cineJoyRetryServer))
                                    cineJoyRetryServer = null
                                } else if (error is NoNativeServersException || candidate.source.identity == com.aliflix.app.model.MobilePlaybackProvider.MOVY) {
                                    exhaustedSources.add(candidate.source.identity)
                                }
                                if (!strictPreferredServer) exhaustedSources.add(candidate.source.identity)
                                throw error
                            } finally {
                                adapter.close()
                            }
                        } }, parallelism = 4)
                    } catch (error: Exception) { ensureActive(); return@repeat }
                    val (candidate, server, resolved) = winner
                    selection = candidate.copy(availableEpisodes = selection?.availableEpisodes?.takeUnless { it.isEmpty() } ?: candidate.availableEpisodes)
                    triedServers.clear()
                    triedServers.addAll(excludedBySource[candidate.source.identity].orEmpty())
                    ui = ui.copy(server = server, availableServers = resolvedServerNames[candidate.key].orEmpty().ifEmpty { ui.availableServers })
                    updateSelectionUi()
                    hideSystemBars()
                    PlaybackStartupTiming.mark("stream_resolved")
                    try {
                        val attempt = startNative(resolved.copy(positionMs = resume, playing = false, preferEmbeddedSubtitles = auto, subtitleLanguage = language.lowercase()))
                        awaitNativeReady(resolved.url, attempt)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { routeStore.save(candidate, server, resolved) }
                        }
                        if (candidate.media.type == com.aliflix.app.model.MediaType.TV) {
                            history.edit().putString("$seriesKey:provider", candidate.source.identity.name)
                                .putString("$seriesKey:server", server).apply()
                        }
                        if (auto) loadAutomaticSubtitles(candidate, language, resolved.url)
                        else if (candidate.source.identity == com.aliflix.app.model.MobilePlaybackProvider.FLIXER) {
                            subtitleJob = lifecycleScope.launch {
                                val tracks = FlixerSubtitleRepository.tracks(candidate)
                                if (selection?.key == candidate.key) ui = ui.copy(subtitleTracks = tracks)
                            }
                        }
                        intent.putExtra("selection", candidate.nativeJson())
                        ui = ui.copy(stage = null, ready = true, error = null,
                            message = null)
                        reconcileSyncedCaptions()
                        controller?.play()
                        warmNextEpisode(candidate, server)
                        return@launch
                    } catch (error: Exception) {
                        ensureActive()
                        android.util.Log.w("AliflixPlayback", "Native preparation failed for $server", error)
                        val sameServerRetry = candidate.source.identity == com.aliflix.app.model.PlaybackProviderId.CINEJOY &&
                            cineJoyRetries.getOrDefault(server, 0) == 0
                        if (sameServerRetry) {
                            cineJoyRetries[server] = 1
                            cineJoyRetryServer = server
                            // A mislabeled high-resolution stream can exceed a phone's decoder.
                            // Retry a smaller rendition on the same server with the same audio.
                            val code = (error as? androidx.media3.common.PlaybackException)?.errorCode
                            cineJoyLowQuality = cineJoyLowQuality || code in 4000..4999
                        } else {
                            cineJoyRetryServer = null
                            if (!strictPreferredServer) {
                                excludedBySource.getOrPut(candidate.source.identity) { mutableSetOf() }.add(server)
                                exhaustedSources.add(candidate.source.identity)
                            }
                        }
                        controller?.stop()
                    }
                }
                val message = "This video is currently unavailable. Please try again later."
                ui = ui.copy(stage = null, error = message, subtitleLoading = false)
            } finally { originalCaptions?.cancel(); resolver?.close(); resolver = null }
        }
    }

    /** Near the end, warm only the next episode on the server that actually worked. */
    private fun warmNextEpisode(current: PlaybackSelection, server: String) {
        nextEpisodeWarmup?.cancel()
        if (current.media.type != com.aliflix.app.model.MediaType.TV) return
        nextEpisodeWarmup = lifecycleScope.launch {
            while (selection?.key == current.key) {
                delay(250)
                val player = controller ?: continue
                val credits = upNextWindowStart(player.duration, ui.segments) ?: continue
                if (player.duration <= 45_000 || player.currentPosition < (credits - 15_000).coerceAtLeast(0) ||
                    player.currentPosition >= player.duration) continue
                val next = selection?.availableEpisodes?.filter {
                    it.seasonNumber > (current.seasonNumber ?: 1) ||
                        (it.seasonNumber == current.seasonNumber && it.number > (current.episodeNumber ?: 1))
                }?.minWithOrNull(compareBy({ it.seasonNumber }, { it.number })) ?: return@launch
                val candidate = current.copy(seasonNumber = next.seasonNumber, episodeNumber = next.number, episodeTitle = next.title)
                warmingEpisodeKey = candidate.key
                val adapter = NativeStreamResolver(this@NativePlayerActivity, progress, resolverHost)
                try {
                    var resolvedServer = server
                    val request = withTimeout(if (candidate.source.identity.isAnimeNative) 45_000 else 25_000) {
                        val resolved = adapter.resolve(candidate, 0, emptySet(), preferredServer = server,
                            validateSingle = true) { resolvedServer = it }
                        val audio = NativePlaybackService.selectedAudioLanguage()
                        StartupStreamCache.awaitPlayable(this@NativePlayerActivity, resolved.copy(playing = false), audio, bufferMs = 3_000)
                        resolved
                    }
                    ensureActive()
                    // A tap can already be handing this job off to this exact next episode.
                    if (selection?.key != current.key && selection?.key != candidate.key) return@launch
                    warmedEpisode = Triple(candidate, resolvedServer, request)
                    warmedAt = android.os.SystemClock.elapsedRealtime()
                } catch (error: Exception) { ensureActive(); delay(3_000); continue }
                finally { adapter.close() }
                return@launch
            }
        }
    }

    // Only launched after the first frame. Local captions are rendered against the
    // existing player clock; attaching or changing them never rebuilds its source.
    private fun captionKey(current: PlaybackSelection): String =
        subtitleContentKey(current)

    private fun saveCaption(current: PlaybackSelection, track: SubtitleTrack, json: String, automatic: Boolean = false) {
        val raw = org.json.JSONObject().put("cues", json).put("language", track.languageCode)
            .put("label", track.languageName).put("id", track.id).put("automatic", automatic).toString()
        getSharedPreferences("account-caption-files", MODE_PRIVATE).edit().putString(captionKey(current), raw).apply()
    }

    private fun restoreCaption(current: PlaybackSelection, language: String): Boolean = runCatching {
        val raw = getSharedPreferences("account-caption-files", MODE_PRIVATE).getString(captionKey(current), null) ?: return false
        val saved = org.json.JSONObject(raw)
        if (canonicalSubtitleLanguageCode(saved.getString("language")) != canonicalSubtitleLanguageCode(language)) return false
        val json = saved.getString("cues")
        val restoredCues = parseTimedTextSubtitleCues(nativeSubtitlesVtt(json, 0.0))
        if (!subtitleLanguageIsPlausible(restoredCues, language) || (saved.optBoolean("automatic", true) && !automaticCaptionCoversMovie(restoredCues, current, controller?.duration ?: 0))) return false
        val vtt = renderSubtitleVtt(json, settingsStore.settings.value.subtitleDelaySeconds)
        activeSubtitleCuesJson = json
        activeSubtitleCues = restoredCues
        NativePlaybackService.updateSubtitles(vtt, saved.getString("language"), saved.getString("label"))
        val track = SubtitleTrack("account-cache", saved.getString("language"), saved.getString("label"),
            "Saved selection", "", false, "vtt", null, "")
        ui = ui.copy(subtitleLoading = false, subtitleError = null, activeSubtitleTrack = track,
            subtitleTracks = (listOf(track) + ui.subtitleTracks.filterNot { it.id == track.id }))
        updateSubtitleCues()
        true
    }.getOrDefault(false)

    private fun loadAutomaticSubtitles(current: PlaybackSelection, language: String, streamUrl: String) {
        subtitleJob?.cancel()
        if (current.source.identity != com.aliflix.app.model.MobilePlaybackProvider.FLIXER && restoreCaption(current, language)) return
        if (NativePlaybackService.embeddedSubtitlesActive) return
        subtitleJob = lifecycleScope.launch {
            ui = ui.copy(subtitleLoading = true)
            try {
                withTimeout(20_000) {
                    val repository = SubdlSubtitleRepository()
                    suspend fun chooseFrom(tracks: List<SubtitleTrack>): Boolean {
                        ui = ui.copy(subtitleTracks = (ui.subtitleTracks + tracks).distinctBy { it.downloadToken })
                        val candidates = mobileSubtitleCandidates(tracks, language, current.seasonNumber, current.episodeNumber, current.media.title)
                        for (track in rankCompleteSubtitleCandidates(candidates, NativePlaybackService.selectedVideoFrameRate()).take(8)) {
                            ensureActive()
                            if (selection?.key != current.key || NativePlaybackService.activeStreamUrl != streamUrl || NativePlaybackService.embeddedSubtitlesActive) return true
                            val cues = withTimeoutOrNull(5_000) { repository.download(track, current).getOrNull() }.orEmpty()
                            if (cues.isEmpty() || !subtitleLanguageIsPlausible(cues, language) || !automaticCaptionCoversMovie(cues, current, controller?.duration ?: 0)) continue
                            if (NativePlaybackService.embeddedSubtitlesActive) return true
                            activeSubtitleCues = cues
                            val json = JSONArray().apply { cues.forEach { put(JSONArray().put(it.startSeconds).put(it.endSeconds).put(it.text)) } }.toString()
                            activeSubtitleCuesJson = json
                            saveCaption(current, track, json, automatic = true)
                            NativePlaybackService.updateSubtitles(renderSubtitleVtt(json, settingsStore.settings.value.subtitleDelaySeconds), track.languageCode.lowercase(), track.languageName, automatic = true)
                            ui = ui.copy(activeSubtitleTrack = track, subtitleError = null)
                            updateSubtitleCues()
                            return true
                        }
                        return false
                    }
                    val tracks = if (current.source.identity == MobilePlaybackProvider.FLIXER) FlixerSubtitleRepository.tracks(current)
                        else normalizeMobileSubtitleTracks(repository.search(current, language).getOrThrow())
                    if (chooseFrom(tracks)) return@withTimeout
                    if (current.source.identity == MobilePlaybackProvider.FLIXER) {
                        val fallback = normalizeMobileSubtitleTracks(repository.search(current, language).getOrNull().orEmpty())
                        if (chooseFrom(fallback)) return@withTimeout
                    }
                    ui = ui.copy(subtitleError = "No complete subtitles found. Choose another subtitle file.")
                }
            } catch (error: Exception) {
                ensureActive()
                ui = ui.copy(subtitleError = "Subtitles unavailable. Refresh to retry.")
            } finally { ui = ui.copy(subtitleLoading = false) }
        }
    }

    private suspend fun awaitNativeReady(url: String, attempt: String) = withTimeout(30_000) {
        while (true) {
            ensureActive()
            if (nativeAttemptMatches(attempt, NativePlaybackService.activeRequestId, url, NativePlaybackService.activeStreamUrl)) {
                NativePlaybackService.playbackFailure?.let { throw it }
                if (controller != null && NativePlaybackService.playbackReady &&
                    (NativePlaybackService.renderedStreamUrl == url || ui.external)) {
                    check(NativePlaybackService.hasSelectedAudio || ui.external) { "The stream has no supported audio track" }
                    return@withTimeout
                }
            }
            delay(100)
        }
    }

    private fun startNative(request: NativePlaybackRequest): String {
        selection = runCatching { nativeSelection(request.selectionJson) }.getOrNull() ?: selection
        updateSelectionUi()
        if (request.offlineDownloadId.isNotBlank()) ui = ui.copy(server = "", availableServers = emptyList())
        val playbackRequest = if (request.subtitlesVtt.isNotBlank()) {
            activeSubtitleCues = parseTimedTextSubtitleCues(request.subtitlesVtt)
            activeSubtitleCuesJson = JSONArray().apply { activeSubtitleCues.forEach { put(JSONArray().put(it.startSeconds).put(it.endSeconds).put(it.text)) } }.toString()
            request
        } else request
        val name = "native-request-${java.util.UUID.randomUUID()}.json"
        java.io.File(cacheDir, name).writeText(playbackRequest.toJson())
        val service = Intent(this, NativePlaybackService::class.java).putExtra("requestFile", name)
        if (request.playing) ContextCompat.startForegroundService(this, service) else startService(service)
        return name
    }

    private fun recover() {
        if (NativePlaybackService.activeRequest?.offlineDownloadId?.isNotBlank() == true) {
            ui = ui.copy(stage = null, error = "Download could not play. Delete it and download again."); return
        }
        if (selection == null || preparation?.isActive == true) return
        stalledSince = 0
        if (NativePlaybackService.retryCineJoyPlayback()) return
        if (ui.server.isNotBlank()) triedServers.add(ui.server)
        selection?.source?.identity?.let(exhaustedSources::add)
        if (recoveryCount++ < 8) prepareSelection()
        else ui = ui.copy(stage = null, error = "This video is currently unavailable. Please try again later.")
    }

    private fun offlineSubtitleRequest(): NativePlaybackRequest? {
        val id = NativePlaybackService.activeRequest?.offlineDownloadId?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val saved = com.aliflix.app.downloads.OfflineDownloads.get(this).manager.downloadIndex.getDownload(id) ?: return null
            NativePlaybackRequest.fromJson(org.json.JSONObject(String(saved.request.data, Charsets.UTF_8)).getString("playback"))
        }.getOrNull()
    }

    private fun searchSubtitles(auto: Boolean = false) {
        offlineSubtitleRequest()?.let { saved ->
            val tracks = if (saved.subtitlesVtt.isNotBlank() || saved.preferEmbeddedSubtitles) listOf(
                SubtitleTrack("offline", saved.subtitleLanguage, java.util.Locale.forLanguageTag(saved.subtitleLanguage).displayLanguage,
                    "", "", false, "vtt", null, "offline")) else emptyList()
            ui = ui.copy(subtitleLoading = false, subtitleTracks = tracks, subtitleError = null)
            return
        }
        val current = selection ?: return
        subtitleJob?.cancel()
        subtitleJob = lifecycleScope.launch {
            ui = ui.copy(subtitleLoading = true)
            val result = SubdlSubtitleRepository().search(current, intent.getStringExtra("subtitleLanguage") ?: "EN")
            ensureActive()
            ui = ui.copy(subtitleLoading = false, subtitleTracks = normalizeMobileSubtitleTracks(ui.subtitleTracks + result.getOrDefault(emptyList())),
                subtitleError = result.exceptionOrNull()?.let { "Subtitles couldn't load. Tap Refresh to retry." })
            if (auto) {
                val code = intent.getStringExtra("subtitleLanguage") ?: "EN"
                ui.subtitleTracks.firstOrNull { it.languageCode.equals(code, true) }?.let(::applySubtitle)
            }
        }
    }

    private fun applySubtitle(track: SubtitleTrack) {
        if (track.id == "account-cache") {
            selection?.let { restoreCaption(it, track.languageCode) }
            getSharedPreferences("native-subtitle-choice", MODE_PRIVATE).edit().putBoolean("enabled", true).putString("language", track.languageCode).apply()
            return
        }
        if (track.id == "offline") {
            offlineSubtitleRequest()?.let { saved ->
                if (saved.subtitlesVtt.isNotBlank()) {
                    activeSubtitleCues = parseTimedTextSubtitleCues(saved.subtitlesVtt)
                    activeSubtitleCuesJson = JSONArray().apply { activeSubtitleCues.forEach { put(JSONArray().put(it.startSeconds).put(it.endSeconds).put(it.text)) } }.toString()
                    NativePlaybackService.updateSubtitles(renderSubtitleVtt(activeSubtitleCuesJson, settingsStore.settings.value.subtitleDelaySeconds), saved.subtitleLanguage, track.languageName)
                } else {
                    controller?.trackSelectionParameters = controller!!.trackSelectionParameters.buildUpon()
                        .setPreferredTextLanguage(saved.subtitleLanguage).setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false).build()
                }
                ui = ui.copy(activeSubtitleTrack = track, subtitleError = null)
            }
            return
        }
        subtitleJob?.cancel()
        subtitleJob = lifecycleScope.launch {
            ui = ui.copy(subtitleLoading = true, subtitleError = null)
            val result = SubdlSubtitleRepository().download(track, selection)
            ensureActive()
            result.onSuccess { cues ->
                        activeSubtitleCues = cues
                val json = JSONArray().apply { cues.forEach { put(JSONArray().put(it.startSeconds).put(it.endSeconds).put(it.text)) } }.toString()
                activeSubtitleCuesJson = json
                selection?.let { saveCaption(it, track, json) }
                val vtt = renderSubtitleVtt(json, settingsStore.settings.value.subtitleDelaySeconds)
                NativePlaybackService.updateSubtitles(vtt, track.languageCode.lowercase(), track.languageName)
                controller?.sendCustomCommand(
                    SessionCommand(NativePlaybackService.ACTION_SET_CAST_SUBTITLES, Bundle.EMPTY),
                    Bundle().apply { putBoolean("enabled", true) }
                )
                updateSubtitleCues()
                getSharedPreferences("native-subtitle-choice", MODE_PRIVATE).edit().putBoolean("enabled", true).putString("language", track.languageCode).apply()
                ui = ui.copy(activeSubtitleTrack = track, subtitleError = null, message = "${track.languageName} subtitles enabled")
            }.onFailure {
                ui = ui.copy(subtitleError = it.message?.takeIf { msg -> msg.isNotBlank() } ?: "This subtitle couldn't load. Try another version.")
            }
            ui = ui.copy(subtitleLoading = false)
        }
    }

    private fun disableSubtitles() {
        getSharedPreferences("native-subtitle-choice", MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
        subtitleJob?.cancel()
        subtitleTimingEvidence = null
        activeSubtitleCues = emptyList()
        activeSubtitleCuesJson = null
        NativePlaybackService.rememberOriginalSubtitles(null)
        renderCaptions(emptyList())
        ui = ui.copy(activeSubtitleTrack = null)
        NativePlaybackService.updateSubtitles("")
        controller?.sendCustomCommand(
            SessionCommand(NativePlaybackService.ACTION_SET_CAST_SUBTITLES, Bundle.EMPTY),
            Bundle().apply { putBoolean("enabled", false) }
        )
    }

    private fun updateSubtitleDelay(tenths: Int) {
        settingsStore.updateSubtitleDelayTenths(tenths)
        updateSubtitleCues()
        subtitleSyncDebounceJob?.cancel()
        subtitleSyncDebounceJob = lifecycleScope.launch {
            delay(300)
            val cuesJson = activeSubtitleCuesJson ?: return@launch
            val vtt = renderSubtitleVtt(cuesJson, tenths / 10.0)
            NativePlaybackService.updateSubtitles(vtt)
        }
    }

    private fun renderSubtitleVtt(json: String?, delay: Double): String {
        NativePlaybackService.rememberOriginalSubtitles(json)
        val request = NativePlaybackService.activeRequest
        val key = if (request != null && json != null) subtitleCorrectionKey(request, selection?.key.orEmpty(), json,
            NativePlaybackService.selectedAudioFingerprint()) else null
        val correction = if (key == audioCorrectionKey) audioCorrection else key?.let(correctionStore::get)
        return correctedMobileVtt(parseTimedTextSubtitleCues(nativeSubtitlesVtt(json, 0.0)), correction, delay)
    }

    private fun refreshAudioCorrection() {
        val request = NativePlaybackService.activeRequest
        if (activeSubtitleCuesJson == null && !NativePlaybackService.embeddedSubtitlesActive) {
            NativePlaybackService.originalSubtitles()?.let {
                activeSubtitleCuesJson = it
                activeSubtitleCues = parseTimedTextSubtitleCues(nativeSubtitlesVtt(it, 0.0))
            }
        }
        val json = activeSubtitleCuesJson
        val track = NativePlaybackService.selectedAudioFingerprint()
        val identity = listOf(request?.url?.let(::stableSyncUrl), request?.streamUrlRules?.let(::stableSyncRules), selection?.key, json, track)
        if (identity != correctionIdentity) {
            val hadCorrection = audioCorrection != null
            audioSyncJob?.cancel(); audioSyncJob = null
            correctionIdentity = identity
            audioCorrectionKey = if (request != null && json != null && track.isNotBlank() && !NativePlaybackService.embeddedSubtitlesActive)
                subtitleCorrectionKey(request, selection?.key.orEmpty(), json, track) else null
            audioCorrection = null
            ui = ui.copy(audioSyncState = null, audioSyncApplied = false, audioSyncMessage = null, audioSyncRemainingSeconds = null)
            if (hadCorrection && json != null && !NativePlaybackService.embeddedSubtitlesActive)
                NativePlaybackService.updateSubtitles(correctedMobileVtt(activeSubtitleCues, null, settingsStore.settings.value.subtitleDelaySeconds))
        }
        val key = audioCorrectionKey
        val cached = key?.let(correctionStore::get)
        if (cached != audioCorrection) {
            audioCorrection = cached
            NativePlaybackService.updateSubtitles(renderSubtitleVtt(json, settingsStore.settings.value.subtitleDelaySeconds))
            ui = ui.copy(audioSyncApplied = cached != null, audioSyncState = if (cached != null) "Synced" else null)
        }
        val available = key != null && json != null && activeSubtitleCues.size >= 4 && controller != null &&
            !NativePlaybackService.embeddedSubtitlesActive && controller?.deviceInfo?.playbackType != DeviceInfo.PLAYBACK_TYPE_REMOTE
        val captionLanguage = ui.activeSubtitleTrack?.languageCode ?: request?.subtitleLanguage.orEmpty()
        val originalLanguage = selection?.media?.originalLanguage.orEmpty()
        // Keep already heard audio when switching subtitle language/edition.
        val recognitionKey = request?.let { listOf(stableSyncUrl(it.url), selection?.key, track,
            getSharedPreferences("player-account-scopes", MODE_PRIVATE).getString("active", "guest")).joinToString("|") }
        NativePlaybackService.configureDialogueRecognition(recognitionKey.takeIf { available }, captionLanguage, originalLanguage)
        prepareSyncReferences(key.takeIf { available }, captionLanguage, originalLanguage)
        if (ui.audioSyncAvailable != available) ui = ui.copy(audioSyncAvailable = available)
    }

    private fun prepareSyncReferences(key: String?, captions: String, original: String) {
        val language = dialogueRecognitionLanguage(NativePlaybackService.selectedAudioLanguage(), captions, original)
            ?.substringBefore('-') ?: return
        val preparationKey = key
        if (preparationKey == referencePreparationKey) return
        referencePreparation?.cancel(); referencePreparationKey = preparationKey; syncReferences = emptyList()
        val current = selection ?: return
        if (preparationKey == null) return
        val target = activeSubtitleCues.toList()
        referencePreparation = lifecycleScope.launch {
            try {
                withTimeout(20_000) {
                    val repository = SubdlSubtitleRepository()
                    // Catalogue diversity avoids spending the whole background budget
                    // comparing near-identical copies of one mistimed release.
                    val catalogues = coroutineScope {
                        val originals = async { withTimeoutOrNull(6_000) {
                            runCatching { FlixerSubtitleRepository.tracks(current) }.getOrDefault(emptyList())
                        }.orEmpty() }
                        val external = async { withTimeoutOrNull(6_000) {
                            normalizeMobileSubtitleTracks(repository.search(current, language).getOrNull().orEmpty())
                        }.orEmpty() }
                        originals.await() to external.await()
                    }
                    val originalCandidates = mobileSubtitleCandidates(catalogues.first, language, current.seasonNumber, current.episodeNumber, current.media.title)
                    val externalCandidates = mobileSubtitleCandidates(catalogues.second, language, current.seasonNumber, current.episodeNumber, current.media.title)
                    val primary = if (current.source.identity == MobilePlaybackProvider.FLIXER) originalCandidates else externalCandidates
                    val secondary = if (current.source.identity == MobilePlaybackProvider.FLIXER) externalCandidates else originalCandidates
                    val candidates = interleaveSubtitleCandidates(
                        rankCompleteSubtitleCandidates(primary, NativePlaybackService.selectedVideoFrameRate()),
                        rankCompleteSubtitleCandidates(secondary, NativePlaybackService.selectedVideoFrameRate()))
                    android.util.Log.i("AliflixAudioSync", "reference_candidates:count=${candidates.size},language=$language")
                    coroutineScope {
                        val completed = kotlinx.coroutines.channels.Channel<SubtitleSyncReference?>(12)
                        val gate = kotlinx.coroutines.sync.Semaphore(2)
                        val jobs = candidates.take(12).mapIndexed { index, candidate -> launch {
                            gate.acquire()
                            val prepared = try {
                                val cues = withTimeoutOrNull(4_000) { repository.download(candidate, current).getOrNull() }.orEmpty()
                                android.util.Log.i("AliflixAudioSync", "reference_download:index=$index,count=${cues.size},fps=${candidate.fps},direct=${candidate.downloadToken.startsWith("https:")}")
                                if (cues.size < 4 || !subtitleLanguageIsPlausible(cues, language) ||
                                    !automaticCaptionCoversMovie(cues, current, controller?.duration ?: 0)) null
                                else try { withContext(Dispatchers.Default) {
                                    val context = currentCoroutineContext()
                                    val deadline = android.os.SystemClock.elapsedRealtime() + 2_000
                                    TranslatedSubtitleSync.prepare(target, cues) {
                                        context.ensureActive()
                                        if (android.os.SystemClock.elapsedRealtime() >= deadline) throw ReferenceAlignmentTimeout()
                                    }
                                } } catch (_: ReferenceAlignmentTimeout) { null }
                            } finally { gate.release() }
                            completed.send(prepared)
                        } }
                        try {
                            repeat(jobs.size) {
                                val prepared = completed.receive() ?: return@repeat
                                if (referencePreparationKey != preparationKey || audioCorrectionKey != key) return@coroutineScope
                                val diverse = syncReferences.none {
                                    kotlin.math.abs(it.targetToReference.rate - prepared.targetToReference.rate) < .0003 &&
                                        kotlin.math.abs(it.targetToReference.offset - prepared.targetToReference.offset) < .6
                                }
                                if (syncReferences.isEmpty() || diverse) syncReferences = syncReferences + prepared
                                android.util.Log.i("AliflixAudioSync", "translation_reference_ready,count=${syncReferences.size},rate=${prepared.targetToReference.rate},offset=${prepared.targetToReference.offset}")
                                if (syncReferences.size >= 4 || syncReferences.map { (it.targetToReference.rate / .0003).toInt() }.distinct().size >= 2) return@coroutineScope
                            }
                        } finally {
                            jobs.forEach { it.cancel() }
                            completed.close()
                        }
                    }
                }
            } catch (error: Exception) { ensureActive() }
        }
    }

    internal fun syncWithAudio() {
        if (audioSyncJob?.isActive == true) {
            audioSyncJob?.cancel(); audioSyncJob = null
            ui = ui.copy(audioSyncState = if (audioCorrection != null) "Synced" else null, audioSyncMessage = null, audioSyncRemainingSeconds = null)
            return
        }
        refreshAudioCorrection()
        if (!ui.audioSyncAvailable) return
        val key = audioCorrectionKey ?: return
        val originals = activeSubtitleCues.toList()
        val originalRevision = correctionStore.preferences.getString(key, null)
        val scopes = getSharedPreferences("player-account-scopes", MODE_PRIVATE)
        val account = scopes.getString("active", "guest")
        val generation = NativePlaybackService.speechGeneration
        val continuity = NativePlaybackService.speechContinuity
        val position = (controller?.currentPosition ?: 0L) / 1000.0
        val initialHistory = NativePlaybackService.dialogueWordHistory(position)
        val playing = controller?.isPlaying == true
        val speed = controller?.playbackParameters?.speed ?: 1f
        val manualDelay = settingsStore.settings.value.subtitleDelaySeconds
        val began = android.os.SystemClock.elapsedRealtime()
        audioSyncJob = lifecycleScope.launch(start = CoroutineStart.LAZY) {
            val owner = currentCoroutineContext()[Job]
            ui = ui.copy(audioSyncState = "Syncing…", audioSyncMessage = null, audioSyncRemainingSeconds = null)
            var hadDialogue = false
            try {
                val result = AudioSyncDeadline.run(
                    clock = android.os.SystemClock::elapsedRealtime, onRemaining = {},
                    onExpired = { if (audioSyncJob === owner) {
                        audioSyncJob = null
                        ui = ui.copy(audioSyncState = "Couldn't match this dialogue", audioSyncRemainingSeconds = null)
                    } }, budgetMs = 9_500,
                ) { deadlineCheck ->
                    var solution: AudioSubtitleCorrection? = null
                    var finiteWords: List<HeardWord> = emptyList()
                    var finiteRequested = false
                    var finiteJob: Job? = null
                    var cloudTranscripts: List<List<HeardWord>> = emptyList()
                    var cloudJob: Job? = null
                    try {
                    while (solution == null && android.os.SystemClock.elapsedRealtime() - began < 9_000) {
                        deadlineCheck()
                        val observedPosition = withContext(Dispatchers.Main.immediate) {
                            (controller?.currentPosition ?: 0L) / 1000.0
                        }
                        val localHeard = (NativePlaybackService.recentDialogueWords(observedPosition) +
                            playedRecognitionWords(finiteWords, observedPosition))
                            .filter { it.start >= observedPosition - CURRENT_DIALOGUE_SECONDS }
                            .distinctBy { it.text to it.start }.sortedBy { it.start }
                        val localHistory = (initialHistory + NativePlaybackService.dialogueWordHistory(observedPosition) +
                            playedRecognitionWords(finiteWords, observedPosition))
                            .distinctBy { it.text to it.start }.sortedBy { it.start }
                        val cloudCandidates = cloudTranscripts.map { playedRecognitionWords(it, observedPosition) }.filter { it.size >= 9 }
                        val candidates = cloudCandidates + listOf(localHeard)
                        val latestReferences = syncReferences.toList()
                        solution = withContext(Dispatchers.Default) {
                            val context = currentCoroutineContext()
                            val check = { context.ensureActive(); deadlineCheck() }
                            val observed = NativePlaybackService.quickSpeechEvidence()
                            for (latestHeard in candidates) {
                            val cloudCandidate = latestHeard !== localHeard
                            val recentAfter = observedPosition - if (cloudCandidate) 120.0 else CURRENT_DIALOGUE_SECONDS
                            val latestHistory = if (cloudCandidate) latestHeard else localHistory
                            // Recovery may analyze dialogue before the current music
                            // tail. Its own scene is already verified by withheld
                            // words; do not reclassify it as an unverified older scene.
                            val verificationPosition = if (cloudCandidate) latestHeard.maxOfOrNull { it.end }?.coerceAtMost(observedPosition)
                                ?: observedPosition else observedPosition
                            hadDialogue = hadDialogue || latestHeard.size >= 9
                            val requireWideClock = latestReferences.any { it.verifiedEditionClock && kotlin.math.abs(it.targetToReference.rate - 1.0) > .0003 }
                            val editionRates = latestReferences.filter { it.verifiedEditionClock }.flatMap {
                                listOf(it.targetToReference.rate, 1.0 / it.targetToReference.rate)
                            } + 1.0
                            for (reference in latestReferences.sortedBy { kotlin.math.abs(it.targetToReference.rate - 1.0) }) {
                                check()
                                if (requireWideClock && kotlin.math.abs(reference.targetToReference.rate - 1.0) <= .0003) continue
                                TranslatedSubtitleSync.match(reference, latestHeard, latestHistory, observed, verificationPosition, recentAfter, check,
                                    if (cloudCandidate) cloudCandidates else emptyList())?.let {
                                    android.util.Log.i("AliflixAudioSync", "instant:verified_translation")
                                    return@withContext it
                                }
                            }
                            val wordCorrection = DialogueWordAlignment.matchClips(originals, if (cloudCandidate) cloudCandidates else listOf(latestHeard), observed, recentAfter = recentAfter,
                                diagnostic = { android.util.Log.i("AliflixAudioSync", it) }, cancelled = check, requireWideClock = requireWideClock,
                                candidateRates = if (requireWideClock) editionRates else null)
                            if (wordCorrection != null && AdaptiveSubtitleSynchronizer.verifyEarlierClock(originals, wordCorrection, observed, verificationPosition, check)) {
                                android.util.Log.i("AliflixAudioSync", "instant:verified_local_words,count=${latestHeard.size},score=${wordCorrection.confidence}")
                                return@withContext wordCorrection
                            }
                            // Earlier independently heard phrases can prove a supported
                            // subtitle rate; a short current exchange cannot invent drift.
                            val historicalClock = if (!requireWideClock && latestHistory.any { it.end >= recentAfter }) DialogueWordAlignment.match(originals, latestHistory, observed, recentAfter = recentAfter,
                                diagnostic = { android.util.Log.i("AliflixAudioSync", "history:$it") }, cancelled = check) else null
                            if (historicalClock != null && AdaptiveSubtitleSynchronizer.verifyEarlierClock(originals, historicalClock, observed, verificationPosition, check,
                                diagnostic = { android.util.Log.i("AliflixAudioSync", it) }))
                                return@withContext historicalClock

                            }

                            val recent = NativePlaybackService.recentSpeechWindow(observedPosition)
                            android.util.Log.i("AliflixAudioSync", "instant_context:position=$position,localWords=${localHeard.size},cloudCandidates=${cloudCandidates.size},recentStart=${recent?.start},recentFrames=${recent?.speech?.size};${NativePlaybackService.speechDiagnostics()}")
                            hadDialogue = hadDialogue || (recent != null && recent.speech.average() in .04.. .96)
                            val local = recent?.let { AdaptiveSubtitleSynchronizer.matchCurrent(originals, it, check) }
                            val correction = local?.correction
                            val accepted = if (correction != null &&
                                AdaptiveSubtitleSynchronizer.verifyReferenceQuick(correction.apply(originals), observed, check)) local else null
                            val reference = NativePlaybackService.embeddedReference()?.takeIf { it.complete &&
                                AdaptiveSubtitleSynchronizer.verifyReferenceQuick(it.cues, observed, check) }?.cues
                            val attempt = accepted ?: AdaptiveSubtitleSynchronizer.matchQuick(originals, observed, reference, cancelled = check)
                            android.util.Log.i("AliflixAudioSync", "instant:${attempt.reason},windows=${attempt.windows},score=${attempt.score},margin=${attempt.margin};${NativePlaybackService.speechDiagnostics()}")
                            attempt.correction
                        }
                        if (solution == null && cloudJob == null) {
                            NativePlaybackService.playedDialoguePcm(observedPosition)?.let { pcm ->
                                cloudJob = launch { GroqDialogueRecognition.recognize(pcm, NativePlaybackService.quickSpeechEvidence()) { cloudTranscripts = it } }
                            }
                        }
                        // Let a completed live segment reach the actual playhead
                        // before releasing that recognizer. Keep matching while
                        // finite recognition delivers independently timed phrases.
                        if (solution == null && !finiteRequested && android.os.Build.VERSION.SDK_INT >= 34 &&
                            android.os.SystemClock.elapsedRealtime() - began >= 1_000) {
                            val pcm = NativePlaybackService.playedDialoguePcm(observedPosition)
                            val language = dialogueRecognitionLanguage(NativePlaybackService.selectedAudioLanguage(),
                                ui.activeSubtitleTrack?.languageCode.orEmpty(), selection?.media?.originalLanguage.orEmpty())
                            if (pcm != null && language != null) {
                                finiteRequested = true
                                finiteJob = launch {
                                    NativePlaybackService.pauseContinuousRecognition(true)
                                    try {
                                        PlayedDialogueRecognition.recognize(this@NativePlayerActivity, language, pcm) { words ->
                                            finiteWords = (finiteWords + words).distinctBy { it.text to it.start }.sortedBy { it.start }
                                        }
                                        android.util.Log.i("AliflixAudioSync", "finite_dialogue:words=${finiteWords.size},start=${pcm.start}")
                                    } finally { NativePlaybackService.pauseContinuousRecognition(false) }
                                }
                            }
                        }
                        if (solution == null) delay(250)
                    }
                    } finally {
                        cloudJob?.cancel(); finiteJob?.cancel()
                        cloudJob?.join(); finiteJob?.join()
                    }
                    solution
                }
                ensureActive()
                refreshAudioCorrection()
                val elapsed = (android.os.SystemClock.elapsedRealtime() - began) / 1000.0
                val expected = position + if (playing) elapsed * speed else 0.0
                if (audioSyncJob !== owner || key != audioCorrectionKey || generation != NativePlaybackService.speechGeneration ||
                    continuity != NativePlaybackService.speechContinuity ||
                    correctionStore.preferences.getString(key, null) != originalRevision || scopes.getString("active", "guest") != account ||
                    controller?.deviceInfo?.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE ||
                    settingsStore.settings.value.subtitleDelaySeconds != manualDelay ||
                    kotlin.math.abs((controller?.currentPosition ?: 0L) / 1000.0 - expected) > 2.0) return@launch
                val presentation = result?.compensatingManualDelay(manualDelay)
                if (presentation != null) {
                    correctionStore.put(key, presentation)
                    refreshAudioCorrection(); updateSubtitleCues()
                    ui = ui.copy(audioSyncState = "Synced", audioSyncMessage = null, audioSyncRemainingSeconds = null)
                } else ui = ui.copy(audioSyncState = if (hadDialogue) "Couldn't match this dialogue" else "Not enough dialogue yet",
                    audioSyncMessage = null, audioSyncRemainingSeconds = null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                android.util.Log.w("AliflixAudioSync", "instant_failed:${error.javaClass.simpleName};${NativePlaybackService.speechDiagnostics()}")
                if (audioSyncJob === owner) ui = ui.copy(audioSyncState = "Couldn't match this dialogue", audioSyncRemainingSeconds = null)
            } finally {
                if (audioSyncJob === owner) {
                    audioSyncJob = null
                    if (ui.audioSyncState == "Syncing…") ui = ui.copy(audioSyncState = if (audioCorrection != null) "Synced" else null)
                }
            }
        }
        audioSyncJob?.start()
    }

    internal fun resetSubtitleSync() {
        audioSyncJob?.cancel(); audioSyncJob = null
        audioCorrectionKey?.let { correctionStore.put(it, null) }
        audioCorrection = null
        ui = ui.copy(audioSyncState = null, audioSyncApplied = false, audioSyncMessage = null, audioSyncRemainingSeconds = null)
        NativePlaybackService.updateSubtitles(renderSubtitleVtt(activeSubtitleCuesJson, settingsStore.settings.value.subtitleDelaySeconds))
        updateSubtitleCues()
    }

    private var volumeAccumulator = 0f

    private var lastControlsVisible = true

    private val subtitleLayoutUpdate = Runnable { applySubtitlePadding() }

    private fun updateSubtitlePadding(controlsVisible: Boolean = lastControlsVisible) {
        lastControlsVisible = controlsVisible
        if (!::subtitles.isInitialized) return
        // The video frame is laid out before its subtitle sibling. Changing sibling padding
        // during that layout can leave its canvas measured with the previous viewport until
        // another UI action requests layout. Apply only after the complete layout pass.
        subtitles.removeCallbacks(subtitleLayoutUpdate)
        subtitles.post(subtitleLayoutUpdate)
    }

    private fun applySubtitlePadding() {
        if (!::playerRoot.isInitialized || playerRoot.height <= 0 || subtitles.height <= 0) return
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        val density = resources.displayMetrics.density
        val offsetPx = ((captionPreviewOffset ?: settingsStore.settings.value.subtitleVerticalOffsetDp) * density).toInt()
        val controlsVisible = lastControlsVisible
        val bottomPx = if (isPortrait) {
            val spaceBelowPx = (playerRoot.height - videoFrame.bottom).coerceAtLeast(0)
            if (spaceBelowPx > (40 * density)) {
                // Directly under the video in portrait letterbox space
                (spaceBelowPx - (32 * density).toInt() + offsetPx).coerceAtLeast((16 * density).toInt())
            } else {
                val baseDp = if (controlsVisible) 100 else 40
                ((baseDp * density).toInt() + offsetPx).coerceAtLeast(0)
            }
        } else {
            val baseDp = if (controlsVisible) 132 else 48
            ((baseDp * density).toInt() + offsetPx).coerceAtLeast(0)
        }
        val horizontalPx = (16 * density).toInt()
        val textHeight = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
            settingsStore.settings.value.subtitleFontSizeSp * 3, resources.displayMetrics).toInt()
        val safeBottom = bottomPx.coerceIn(0, (subtitles.height - textHeight).coerceAtLeast(0))
        subtitles.setBottomPaddingFraction(0f)
        subtitles.setPadding(horizontalPx, 0, horizontalPx, safeBottom)
        val caption = captionBounds()
        ui = ui.copy(captionBottomPx = caption?.bottom?.toInt() ?: 0, captionTopPx = caption?.top?.toInt() ?: 0)
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
        renderCaptions(renderedCaptions)
        subtitles.invalidate()
        updateSubtitlePadding(lastControlsVisible)
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
        val sel = NativePlaybackService.activeRequest?.selectionJson?.let { runCatching { nativeSelection(it) }.getOrNull() } ?: return
        val c = controller ?: return
        if (!NativePlaybackService.canRecordProgress || c.currentMediaItem?.mediaId != sel.key ||
            c.playbackState !in setOf(Player.STATE_READY, Player.STATE_BUFFERING, Player.STATE_ENDED)) return
        val dur = c.duration.toDouble() / 1000.0
        val pos = c.currentPosition.toDouble() / 1000.0
        if (dur > 0.0 && pos >= 0.0) {
            progress.savePlayerProgress(sel, pos, dur, urgentCloudSync = urgent, ended = c.playbackState == Player.STATE_ENDED)
        }
    }

    private fun startSubtitleRenderLoop() {
        subtitleRenderJob?.cancel()
        subtitleRenderJob = lifecycleScope.launch {
            while (isActive) {
                updateSubtitleCues()
                delay(100)
            }
        }
    }

    private fun renderCaptions(cues: List<Cue>) {
        renderedCaptions = cues.map { mobileCaptionCue(it).buildUpon().setPosition(0.5f)
            .setPositionAnchor(Cue.ANCHOR_TYPE_MIDDLE).setSize(0.92f)
            .setLine(Cue.DIMEN_UNSET, Cue.LINE_TYPE_FRACTION)
            .clearWindowColor().build() }
        subtitles.setCues(renderedCaptions)
    }

    private fun updateSubtitleCues() {
        if (!::subtitles.isInitialized) return
        refreshAudioCorrection()
        val current = controller
        val external = !NativePlaybackService.castingSuppressed && (current?.deviceInfo?.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE || displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).isNotEmpty())
        if (external) {
            subtitles.visibility = View.GONE
            return
        }
        subtitles.visibility = View.VISIBLE
        renderCaptions(NativePlaybackService.currentCaptionCues())
    }

    override fun onStart() {
        super.onStart()
        startSubtitleRenderLoop()
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
                updateSubtitleCues()
            }.onFailure { ui = ui.copy(error = "Unable to connect to playback. Please retry.") }
        }, mainExecutor)
    }

    private fun updateOutput() {
        val current = controller ?: return
        val external = !NativePlaybackService.castingSuppressed && (current.deviceInfo.playbackType == DeviceInfo.PLAYBACK_TYPE_REMOTE || displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).isNotEmpty())
        videoFrame.visibility = if (external) View.INVISIBLE else View.VISIBLE
        val size = current.videoSize
        if (size.height > 0) videoFrame.setAspectRatio(size.width * size.pixelWidthHeightRatio / size.height)
        updateSubtitleCues()
        ui = ui.copy(external = external, revision = ui.revision + 1,
            ready = ui.ready || (ui.stage == null && current.playbackState == Player.STATE_READY),
            title = selection?.media?.title ?: current.mediaMetadata.title?.toString().orEmpty().ifBlank { "Aliflix" })
        if (NativePlaybackService.handlesCineJoyRecovery) {
            NativePlaybackService.cineJoyRecoveryMessage?.let { if (ui.message != it) ui = ui.copy(message = it) }
        }
        if (!NativePlaybackService.handlesCineJoyRecovery && (current.playerError != null || NativePlaybackService.cineJoyRecoveryExhausted) && preparation?.isActive != true && ui.error == null) {
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
        subtitleRenderJob?.cancel()
        preparation?.cancel(); controller?.stop(); controller?.clearMediaItems()
        startService(Intent(this, NativePlaybackService::class.java).setAction(NativePlaybackService.ACTION_STOP)); finish()
    }
    override fun onStop() {
        audioSyncJob?.cancel(); audioSyncJob = null
        ui = ui.copy(audioSyncState = if (audioCorrection != null) "Synced" else null, audioSyncMessage = null)
        recordCurrentProgress(urgent = true)
        subtitleRenderJob?.cancel()
        displays.unregisterDisplayListener(displayListener)
        sendSurface(clear = true); controller?.removeListener(listener); controller = null
        controllerFuture?.let(MediaController::releaseFuture); controllerFuture = null
        super.onStop()
    }
    override fun onDestroy() {
        audioSyncJob?.cancel()
        captionPreferences.unregisterOnSharedPreferenceChangeListener(syncedCaptionListener)
        subtitleChoices.unregisterOnSharedPreferenceChangeListener(syncedCaptionListener)
        recordCurrentProgress(urgent = true)
        if (::subtitles.isInitialized) subtitles.removeCallbacks(subtitleLayoutUpdate)
        subtitleRenderJob?.cancel()
        nextEpisodeWarmup?.cancel(); referencePreparation?.cancel()
        preparation?.cancel(); resolver?.close(); resolver = null; subtitleJob?.cancel(); subtitleSyncDebounceJob?.cancel(); episodeQueueJob?.cancel(); introJob?.cancel(); super.onDestroy()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::playerRoot.isInitialized) {
            playerRoot.post { updateSubtitlePadding(lastControlsVisible) }
        }
    }
    private fun sendSurface(clear: Boolean = false) {
        val surface = video.holder.surface.takeIf { !clear && it.isValid }
        NativePlaybackService.attachSurface(surfaceOwner, surface, tv = false)
    }
    private fun hideSystemBars() = WindowInsetsControllerCompat(window, window.decorView).let {
        it.hide(WindowInsetsCompat.Type.systemBars()); it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
}
