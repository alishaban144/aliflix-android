package com.aliflix.app.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.focusable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Forward10
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Replay10
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.aliflix.app.BuildConfig
import com.aliflix.app.model.PlaybackProviderId
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.model.SubtitleLanguage
import com.aliflix.app.model.Episode
import com.aliflix.app.ui.theme.AliflixBlack
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixAccentPrimaryContainer
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixBorderSubtle
import com.aliflix.app.ui.theme.AliflixContentPrimary
import com.aliflix.app.ui.theme.AliflixContentSecondary
import com.aliflix.app.ui.theme.AliflixError
import com.aliflix.app.ui.theme.AliflixMuted
import com.aliflix.app.ui.theme.AliflixSurfaceElevated
import com.aliflix.app.ui.theme.AliflixSurfaceRaised

@Composable
fun WebPlayerScreen(
    selection: PlaybackSelection,
    visible: Boolean,
    controller: WebPlayerController,
    onClose: () -> Unit,
    onSelectEpisode: (Episode) -> Unit = {},
    preferredSubtitleLanguage: SubtitleLanguage = SubtitleLanguage.ENGLISH,
    autoDisplayPreferredSubtitles: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val loading by controller.loading.collectAsState()
    val error by controller.error.collectAsState()
    val webViewGeneration by controller.webViewGeneration.collectAsState()
    val moviepireServers by controller.moviepireServers.collectAsState()
    val switchingMoviepireServer by controller.switchingMoviepireServer.collectAsState()
    val playing by controller.playing.collectAsState()
    val nativePhoneMoviepire = selection.source.provider.usesMoviepire && !BuildConfig.IS_TV
    val playerAccent = AliflixAccentPrimary
    val resumableProgress = remember(selection.key) {
        controller.savedProgressFor(selection)?.takeIf { it.resumeEligible }
    }
    var resumeDecisionMade by remember(selection.key) {
        mutableStateOf(true)
    }
    var serverMenuExpanded by remember(selection.key) { mutableStateOf(false) }
    var episodeMenuExpanded by remember(selection.key) { mutableStateOf(false) }
    var controlsVisible by remember(selection.key) { mutableStateOf(true) }
    var subtitleDialogVisible by remember(selection.key) { mutableStateOf(false) }
    var subtitleTracks by remember(selection.key) { mutableStateOf<List<SubtitleTrack>?>(null) }
    var subtitleSearching by remember(selection.key) { mutableStateOf(false) }
    var subtitleSearchError by remember(selection.key) { mutableStateOf<String?>(null) }
    var subtitleSearchAttempt by remember(selection.key) { mutableStateOf(0) }
    var selectedSubtitleTrack by remember(selection.key) { mutableStateOf<SubtitleTrack?>(null) }
    var subtitleTrackLoading by remember(selection.key) { mutableStateOf(false) }
    var subtitleTrackError by remember(selection.key) { mutableStateOf<String?>(null) }
    var activeSubtitleCues by remember(selection.key) { mutableStateOf<List<SubtitleCue>>(emptyList()) }
    var subtitleDelayTenths by remember(selection.key) { mutableStateOf(0) }
    var subtitleFontPercent by remember(selection.key) { mutableStateOf(100) }
    var autoSubtitleAttemptedLanguage by remember(selection.key) { mutableStateOf<String?>(null) }
    val subtitleRepository = remember { SubdlSubtitleRepository() }
    val subtitleScope = rememberCoroutineScope()

    LaunchedEffect(selection.key) {
        controller.prepareSelection(selection)
    }

    LaunchedEffect(visible, resumeDecisionMade) {
        controller.setVisible(visible && resumeDecisionMade)
    }

    LaunchedEffect(
        nativePhoneMoviepire,
        selection.key,
        playing,
        subtitleDialogVisible,
        subtitleSearchAttempt,
    ) {
        val allowSubtitles = nativePhoneMoviepire || BuildConfig.IS_TV
        if (
            !allowSubtitles || subtitleTracks != null || subtitleSearching ||
            (!playing && !subtitleDialogVisible)
        ) return@LaunchedEffect
        subtitleSearching = true
        subtitleSearchError = null
        subtitleRepository.search(selection)
            .onSuccess { subtitleTracks = it }
            .onFailure { subtitleSearchError = it.message ?: "Subtitles are temporarily unavailable" }
        subtitleSearching = false
    }

    LaunchedEffect(
        nativePhoneMoviepire,
        autoDisplayPreferredSubtitles,
        preferredSubtitleLanguage,
        subtitleTracks,
        selection.key,
    ) {
        val tracks = subtitleTracks ?: return@LaunchedEffect
        val allowSubtitles = nativePhoneMoviepire || BuildConfig.IS_TV
        if (
            !allowSubtitles ||
            !autoDisplayPreferredSubtitles ||
            subtitleTrackLoading ||
            autoSubtitleAttemptedLanguage == preferredSubtitleLanguage.code
        ) return@LaunchedEffect
        val track = preferredSubtitleTrack(tracks, preferredSubtitleLanguage)
            ?: return@LaunchedEffect
        autoSubtitleAttemptedLanguage = preferredSubtitleLanguage.code
        subtitleTrackLoading = true
        subtitleTrackError = null
        subtitleRepository.download(track)
            .onSuccess { cues ->
                selectedSubtitleTrack = track
                activeSubtitleCues = cues
                controller.setSubtitles(
                    selection = selection,
                    cues = cues,
                    delaySeconds = subtitleDelayTenths / 10.0,
                    fontPercent = subtitleFontPercent,
                    languageCode = track.languageCode,
                    label = track.languageName,
                )
            }
            .onFailure {
                subtitleTrackError = it.message ?: "This subtitle could not be loaded"
            }
        subtitleTrackLoading = false
    }

    LaunchedEffect(
        nativePhoneMoviepire,
        playing,
        loading,
        error,
        controlsVisible,
        serverMenuExpanded,
        episodeMenuExpanded,
        subtitleDialogVisible,
        switchingMoviepireServer,
    ) {
        if (
            nativePhoneMoviepire &&
            controlsVisible &&
            !loading &&
            error == null &&
            !serverMenuExpanded &&
            !episodeMenuExpanded &&
            !subtitleDialogVisible &&
            !switchingMoviepireServer
        ) {
            delay(if (playing) 3_200L else 5_200L)
            controlsVisible = false
        }
    }

    BackHandler(enabled = visible) {
        if (!controller.handleBack()) onClose()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AliflixBlack),
    ) {
        if (resumeDecisionMade) {
            key(selection.key, webViewGeneration) {
                AndroidView(
                    factory = { controller.viewFor(selection) },
                    update = { controller.setVisible(visible) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text(
                    text = "Resume from ${formatPlaybackTime(resumableProgress!!.positionSeconds)}",
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            controller.requestResume(resumableProgress)
                            resumeDecisionMade = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = playerAccent),
                    ) {
                        Text("Resume")
                    }
                    Button(
                        onClick = {
                            controller.startOver(selection)
                            resumeDecisionMade = true
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AliflixSurfaceRaised,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("Start over")
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = loading && resumeDecisionMade,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AliflixBlack),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(17.dp))
                            .background(playerAccent),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "A",
                            color = Color.White,
                            fontSize = 29.sp,
                            fontWeight = FontWeight.Black,
                        )
                    }
                    Text(
                        text = "Preparing playback",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = selection.episodeTitle ?: selection.media.title,
                        color = AliflixMuted,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 250.dp),
                    )
                    CircularProgressIndicator(
                        color = playerAccent,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }
        }

        if (error != null) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(28.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(AliflixSurfaceRaised)
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(24.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Player unavailable",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White,
                )
                Text(text = error.orEmpty(), color = Color.LightGray)
                FilledIconButton(
                    onClick = controller::reload,
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = playerAccent),
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Retry player")
                }
            }
        }

        if (BuildConfig.IS_TV) {
            TvPlayerOverlay(
                selection = selection,
                controller = controller,
                playing = playing,
                servers = moviepireServers,
                switchingServer = switchingMoviepireServer,
                episodes = selection.availableEpisodes,
                subtitlesActive = selectedSubtitleTrack != null && activeSubtitleCues.isNotEmpty(),
                subtitleTracks = subtitleTracks,
                subtitleSearching = subtitleSearching,
                subtitleSearchError = subtitleSearchError,
                selectedSubtitleTrack = selectedSubtitleTrack,
                subtitleTrackLoading = subtitleTrackLoading,
                subtitleTrackError = subtitleTrackError,
                subtitleDelayTenths = subtitleDelayTenths,
                subtitleFontPercent = subtitleFontPercent,
                onClose = onClose,
                onSelectEpisode = onSelectEpisode,
                onSelectServer = { server -> controller.selectMoviepireServer(server) },
                onSearchSubtitles = {
                    subtitleSearchAttempt++
                    subtitleSearching = true
                    subtitleSearchError = null
                    subtitleScope.launch {
                        subtitleRepository.search(selection)
                            .onSuccess { subtitleTracks = it }
                            .onFailure { subtitleSearchError = it.message ?: "Subtitles are temporarily unavailable" }
                        subtitleSearching = false
                    }
                },
                onSelectTrack = { track ->
                    if (track == null) {
                        selectedSubtitleTrack = null
                        activeSubtitleCues = emptyList()
                        controller.clearSubtitles()
                    } else {
                        if (!subtitleTrackLoading) {
                            subtitleTrackLoading = true
                            subtitleTrackError = null
                            subtitleScope.launch {
                                subtitleRepository.download(track)
                                    .onSuccess { cues ->
                                        selectedSubtitleTrack = track
                                        activeSubtitleCues = cues
                                        controller.setSubtitles(
                                            selection = selection,
                                            cues = cues,
                                            delaySeconds = subtitleDelayTenths / 10.0,
                                            fontPercent = subtitleFontPercent,
                                            languageCode = track.languageCode,
                                            label = track.languageName,
                                        )
                                    }
                                    .onFailure {
                                        subtitleTrackError = it.message ?: "This subtitle could not be loaded"
                                    }
                                subtitleTrackLoading = false
                            }
                        }
                    }
                },
                onDelayChange = { tenths ->
                    subtitleDelayTenths = tenths.coerceIn(-100, 100)
                    controller.updateSubtitlePresentation(
                        delaySeconds = subtitleDelayTenths / 10.0,
                        fontPercent = subtitleFontPercent,
                    )
                },
                onFontPercentChange = { percent ->
                    subtitleFontPercent = percent.coerceIn(70, 180)
                    controller.updateSubtitlePresentation(
                        delaySeconds = subtitleDelayTenths / 10.0,
                        fontPercent = subtitleFontPercent,
                    )
                },
            )
        } else if (nativePhoneMoviepire) {
            if (!controlsVisible && resumeDecisionMade && error == null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = { controlsVisible = true },
                        ),
                )
            }
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter),
            ) {
                NativeMoviepireTopBar(
                    selection = selection,
                    servers = moviepireServers,
                    episodes = selection.availableEpisodes,
                    switchingServer = switchingMoviepireServer,
                    currentSeasonNumber = selection.seasonNumber,
                    currentEpisodeNumber = selection.episodeNumber,
                    serverMenuExpanded = serverMenuExpanded,
                    episodeMenuExpanded = episodeMenuExpanded,
                    subtitlesActive = selectedSubtitleTrack != null && activeSubtitleCues.isNotEmpty(),
                    onServerMenuExpandedChange = { serverMenuExpanded = it },
                    onEpisodeMenuExpandedChange = { episodeMenuExpanded = it },
                    onSelectServer = { server ->
                        controlsVisible = true
                        controller.selectMoviepireServer(server)
                    },
                    onSelectEpisode = onSelectEpisode,
                    onClose = onClose,
                    onFullscreen = {
                        val fullscreenActive = controller.requestMoviepireFullscreen()
                        controlsVisible = !fullscreenActive
                    },
                    onSubtitles = {
                        controlsVisible = true
                        subtitleDialogVisible = true
                    },
                    onCast = controller::openCastPicker,
                )
            }

            if (subtitleDialogVisible) {
                SubtitleDialog(
                    tracks = subtitleTracks.orEmpty(),
                    searching = subtitleSearching,
                    searchError = subtitleSearchError,
                    selectedTrack = selectedSubtitleTrack,
                    trackLoading = subtitleTrackLoading,
                    trackError = subtitleTrackError,
                    delayTenths = subtitleDelayTenths,
                    fontPercent = subtitleFontPercent,
                    onDismiss = { subtitleDialogVisible = false },
                    onRetry = {
                        subtitleTracks = null
                        subtitleSearchAttempt += 1
                    },
                    onDisable = {
                        selectedSubtitleTrack = null
                        activeSubtitleCues = emptyList()
                        subtitleTrackError = null
                        controller.clearSubtitles()
                    },
                    onSelectTrack = { track ->
                        if (subtitleTrackLoading) return@SubtitleDialog
                        subtitleTrackLoading = true
                        subtitleTrackError = null
                        subtitleScope.launch {
                            subtitleRepository.download(track)
                                .onSuccess { cues ->
                                    selectedSubtitleTrack = track
                                    activeSubtitleCues = cues
                                    controller.setSubtitles(
                                        selection = selection,
                                        cues = cues,
                                        delaySeconds = subtitleDelayTenths / 10.0,
                                        fontPercent = subtitleFontPercent,
                                        languageCode = track.languageCode,
                                        label = track.languageName,
                                    )
                                }
                                .onFailure {
                                    subtitleTrackError = it.message ?: "This subtitle could not be loaded"
                                }
                            subtitleTrackLoading = false
                        }
                    },
                    onDelayChange = { tenths ->
                        subtitleDelayTenths = tenths.coerceIn(-100, 100)
                        controller.updateSubtitlePresentation(
                            delaySeconds = subtitleDelayTenths / 10.0,
                            fontPercent = subtitleFontPercent,
                        )
                    },
                    onFontPercentChange = { percent ->
                        subtitleFontPercent = percent.coerceIn(70, 180)
                        controller.updateSubtitlePresentation(
                            delaySeconds = subtitleDelayTenths / 10.0,
                            fontPercent = subtitleFontPercent,
                        )
                    },
                )
            }
        } else {
            ExistingPlayerTopControls(selection, controller, onClose)
        }
    }
}

@Composable
private fun NativeMoviepireTopBar(
    selection: PlaybackSelection,
    servers: List<MoviepireServerOption>,
    episodes: List<Episode>,
    switchingServer: Boolean,
    currentSeasonNumber: Int?,
    currentEpisodeNumber: Int?,
    serverMenuExpanded: Boolean,
    episodeMenuExpanded: Boolean,
    subtitlesActive: Boolean,
    onServerMenuExpandedChange: (Boolean) -> Unit,
    onEpisodeMenuExpandedChange: (Boolean) -> Unit,
    onSelectServer: (MoviepireServerOption) -> Unit,
    onSelectEpisode: (Episode) -> Unit,
    onClose: () -> Unit,
    onFullscreen: () -> Unit,
    onSubtitles: () -> Unit,
    onCast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedServer = servers.firstOrNull(MoviepireServerOption::selected)
    val episodeContext = if (currentSeasonNumber != null && currentEpisodeNumber != null) {
        "S${currentSeasonNumber.toString().padStart(2, '0')}  •  " +
            "E${currentEpisodeNumber.toString().padStart(2, '0')}"
    } else {
        null
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(
                        AliflixBlack.copy(alpha = 0.98f),
                        AliflixBlack.copy(alpha = 0.86f),
                        AliflixBlack.copy(alpha = 0.42f),
                        Color.Transparent,
                    ),
                ),
            )
            .padding(top = 38.dp, start = 14.dp, end = 14.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PlayerIconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to Aliflix")
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = selection.media.title,
                    color = AliflixContentPrimary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val secondaryLine = listOfNotNull(
                    episodeContext,
                    selection.episodeTitle?.takeIf(String::isNotBlank),
                ).joinToString("  •  ")
                if (secondaryLine.isNotBlank()) {
                    Text(
                        text = secondaryLine,
                        color = AliflixContentSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            PlayerIconButton(onClick = onFullscreen) {
                Icon(Icons.Rounded.Fullscreen, contentDescription = "Play fullscreen")
            }
            PlayerIconButton(
                onClick = onSubtitles,
                active = subtitlesActive,
            ) {
                Icon(Icons.Rounded.Subtitles, contentDescription = "Subtitles")
            }
            PlayerIconButton(onClick = onCast) {
                Icon(Icons.Rounded.Cast, contentDescription = "Cast video to TV")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box {
                Button(
                    onClick = { onServerMenuExpandedChange(true) },
                    enabled = servers.isNotEmpty() && !switchingServer,
                    modifier = Modifier.heightIn(min = 44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AliflixSurfaceElevated.copy(alpha = 0.94f),
                        contentColor = AliflixContentPrimary,
                        disabledContainerColor = AliflixSurfaceElevated.copy(alpha = 0.78f),
                        disabledContentColor = AliflixContentSecondary,
                    ),
                    border = BorderStroke(1.dp, AliflixBorderSubtle),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    if (switchingServer) {
                        CircularProgressIndicator(
                            color = AliflixAccentSecondary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(
                        text = if (switchingServer) {
                            "Switching server"
                        } else {
                            selectedServer?.label ?: "Choose server"
                        },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    if (!switchingServer) {
                        Spacer(Modifier.size(4.dp))
                        Icon(
                            Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                DropdownMenu(
                    expanded = serverMenuExpanded,
                    onDismissRequest = { onServerMenuExpandedChange(false) },
                ) {
                    servers.forEach { server ->
                        DropdownMenuItem(
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(
                                        text = server.label,
                                        color = if (server.selected) {
                                            AliflixAccentSecondary
                                        } else {
                                            AliflixContentPrimary
                                        },
                                        fontWeight = if (server.selected) {
                                            FontWeight.Bold
                                        } else {
                                            FontWeight.Medium
                                        },
                                    )
                                    if (server.selected) {
                                        Text(
                                            text = "Currently playing",
                                            color = AliflixContentSecondary,
                                            fontSize = 10.sp,
                                        )
                                    }
                                }
                            },
                            enabled = !server.selected && !switchingServer,
                            onClick = {
                                onServerMenuExpandedChange(false)
                                onSelectServer(server)
                            },
                        )
                    }
                }
            }
            if (episodes.isNotEmpty()) {
                Spacer(Modifier.size(10.dp))
                Box {
                    Button(
                        onClick = { onEpisodeMenuExpandedChange(true) },
                        modifier = Modifier.heightIn(min = 44.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AliflixAccentPrimary.copy(alpha = 0.92f),
                            contentColor = Color.White,
                        ),
                        border = BorderStroke(1.dp, AliflixAccentSecondary.copy(alpha = 0.34f)),
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Text(
                            text = episodeContext ?: "Episodes",
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                        )
                        Spacer(Modifier.size(4.dp))
                        Icon(
                            Icons.Rounded.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = episodeMenuExpanded,
                        onDismissRequest = { onEpisodeMenuExpandedChange(false) },
                    ) {
                        episodes.forEach { episode ->
                            val current = episode.seasonNumber == currentSeasonNumber &&
                                episode.number == currentEpisodeNumber
                            DropdownMenuItem(
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text(
                                            text = "S${episode.seasonNumber.toString().padStart(2, '0')}  •  " +
                                                "E${episode.number.toString().padStart(2, '0')}",
                                            color = if (current) {
                                                AliflixAccentSecondary
                                            } else {
                                                AliflixContentSecondary
                                            },
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                        Text(
                                            text = episode.title,
                                            color = AliflixContentPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            fontWeight = if (current) {
                                                FontWeight.Bold
                                            } else {
                                                FontWeight.Medium
                                            },
                                        )
                                    }
                                },
                                enabled = !current,
                                onClick = {
                                    onEpisodeMenuExpandedChange(false)
                                    onSelectEpisode(episode)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SubtitleDialog(
    tracks: List<SubtitleTrack>,
    searching: Boolean,
    searchError: String?,
    selectedTrack: SubtitleTrack?,
    trackLoading: Boolean,
    trackError: String?,
    delayTenths: Int,
    fontPercent: Int,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onDisable: () -> Unit,
    onSelectTrack: (SubtitleTrack) -> Unit,
    onDelayChange: (Int) -> Unit,
    onFontPercentChange: (Int) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.9f)
                .widthIn(max = 520.dp)
                .heightIn(min = 280.dp),
            shape = RoundedCornerShape(28.dp),
            color = AliflixSurfaceRaised.copy(alpha = 0.99f),
            border = BorderStroke(1.dp, AliflixBorderSubtle),
            shadowElevation = 18.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(AliflixAccentPrimary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Subtitles,
                            contentDescription = null,
                            tint = AliflixAccentSecondary,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Subtitles",
                            color = AliflixContentPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                        )
                        Text(
                            text = if (!searching && searchError == null && tracks.isNotEmpty()) {
                                "${tracks.size} exact ${if (tracks.size == 1) "match" else "matches"} • SubDL"
                            } else {
                                "Exact title and episode match • SubDL"
                            },
                            color = AliflixContentSecondary,
                            fontSize = 11.sp,
                        )
                    }
                    PlayerIconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close subtitles")
                    }
                }

                if (selectedTrack != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(AliflixSurfaceElevated)
                            .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(20.dp))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SubtitleAdjustmentRow(
                            title = "Timing",
                            value = if (delayTenths == 0) {
                                "Synced"
                            } else {
                                "%+.1fs".format(delayTenths / 10.0)
                            },
                            supportingText = "+ moves subtitles later",
                            decreaseEnabled = delayTenths > -100,
                            increaseEnabled = delayTenths < 100,
                            onDecrease = { onDelayChange(delayTenths - 1) },
                            onIncrease = { onDelayChange(delayTenths + 1) },
                        )
                        SubtitleAdjustmentRow(
                            title = "Text size",
                            value = "$fontPercent%",
                            supportingText = "Adjust subtitle readability",
                            decreaseEnabled = fontPercent > 70,
                            increaseEnabled = fontPercent < 180,
                            onDecrease = { onFontPercentChange(fontPercent - 10) },
                            onIncrease = { onFontPercentChange(fontPercent + 10) },
                        )
                    }
                }

                when {
                    searching -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 28.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                color = AliflixAccentSecondary,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.size(10.dp))
                            Text("Finding exact subtitles…", color = AliflixContentSecondary)
                        }
                    }
                    searchError != null -> {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(18.dp))
                                .background(AliflixSurfaceElevated)
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                text = searchError,
                                color = AliflixContentSecondary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Button(
                                onClick = onRetry,
                                colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary),
                            ) {
                                Text("Try again")
                            }
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            item(key = "off") {
                                SubtitleTrackRow(
                                    title = "Off",
                                    supportingText = "Hide subtitles",
                                    languageCode = null,
                                    selected = selectedTrack == null,
                                    enabled = !trackLoading,
                                    onClick = onDisable,
                                )
                            }
                            items(tracks, key = SubtitleTrack::id) { track ->
                                SubtitleTrackRow(
                                    title = track.languageName.ifBlank { track.languageCode },
                                    supportingText = buildString {
                                        append(track.releaseName)
                                        track.fps?.let { append(" • ").append(it).append(" fps") }
                                        if (track.hearingImpaired) append(" • HI")
                                    },
                                    languageCode = track.languageCode,
                                    selected = selectedTrack?.id == track.id,
                                    enabled = !trackLoading,
                                    onClick = { onSelectTrack(track) },
                                )
                            }
                            if (tracks.isEmpty()) {
                                item(key = "empty") {
                                    Text(
                                        text = "No subtitles are available for this exact title yet.",
                                        color = AliflixContentSecondary,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 18.dp),
                                    )
                                }
                            }
                        }
                    }
                }

                if (trackLoading) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            color = AliflixAccentSecondary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.size(8.dp))
                        Text("Loading subtitle…", color = AliflixContentSecondary, fontSize = 12.sp)
                    }
                }
                if (trackError != null) {
                    Text(
                        text = trackError,
                        color = AliflixError,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitleTrackRow(
    title: String,
    supportingText: String,
    languageCode: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(17.dp))
            .background(
                if (selected) AliflixAccentPrimary.copy(alpha = 0.18f) else AliflixSurfaceElevated,
            )
            .border(
                1.dp,
                if (selected) AliflixAccentSecondary.copy(alpha = 0.55f) else AliflixBorderSubtle,
                RoundedCornerShape(17.dp),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (languageCode != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(9.dp))
                    .background(Color.Black.copy(alpha = 0.34f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            ) {
                Text(
                    text = languageCode.take(3),
                    color = if (selected) AliflixAccentSecondary else AliflixContentSecondary,
                    fontWeight = FontWeight.Black,
                    fontSize = 10.sp,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = AliflixContentPrimary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            )
            Text(
                text = supportingText,
                color = AliflixContentSecondary,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = AliflixAccentSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SubtitleAdjustmentRow(
    title: String,
    value: String,
    supportingText: String,
    decreaseEnabled: Boolean,
    increaseEnabled: Boolean,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = AliflixContentPrimary, fontWeight = FontWeight.SemiBold)
            Text(supportingText, color = AliflixContentSecondary, fontSize = 10.sp)
        }
        FilledIconButton(
            onClick = onDecrease,
            enabled = decreaseEnabled,
            modifier = Modifier.size(34.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.32f),
                contentColor = AliflixContentPrimary,
            ),
        ) {
            Icon(Icons.Rounded.Remove, contentDescription = "Decrease $title", modifier = Modifier.size(17.dp))
        }
        Text(
            text = value,
            color = AliflixAccentSecondary,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            modifier = Modifier.widthIn(min = 52.dp),
        )
        FilledIconButton(
            onClick = onIncrease,
            enabled = increaseEnabled,
            modifier = Modifier.size(34.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.Black.copy(alpha = 0.32f),
                contentColor = AliflixContentPrimary,
            ),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = "Increase $title", modifier = Modifier.size(17.dp))
        }
    }
}

@Composable
private fun BoxScope.ExistingPlayerTopControls(
    selection: PlaybackSelection,
    controller: WebPlayerController,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(top = 44.dp, start = 16.dp),
    ) {
        PlayerIconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to Aliflix")
        }
    }

    if (!BuildConfig.IS_TV) {
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 44.dp, end = 16.dp),
        ) {
            PlayerIconButton(onClick = controller::openCastPicker) {
                Icon(Icons.Rounded.Cast, contentDescription = "Cast video to TV")
            }
        }
    }

    Box(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 44.dp),
    ) {
        if (selection.source.provider == PlaybackProviderId.RAMOFLIX) {
            Button(
                onClick = controller::showProviderOptions,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black.copy(alpha = 0.72f),
                    contentColor = Color.White,
                ),
                shape = CircleShape,
            ) {
                Text(
                    text = "Ramoflix · Servers",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            Text(
                text = selection.source.provider.displayName,
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.72f))
                    .padding(horizontal = 18.dp, vertical = 11.dp),
            )
        }
    }
}

@Composable
private fun PlayerIconButton(
    onClick: () -> Unit,
    active: Boolean = false,
    content: @Composable () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (active) {
                AliflixAccentPrimary.copy(alpha = 0.94f)
            } else {
                Color.Black.copy(alpha = 0.68f)
            },
            contentColor = Color.White,
        ),
        content = content,
    )
}

private fun formatPlaybackTime(seconds: Double): String {
    val totalSeconds = seconds.toLong().coerceAtLeast(0L)
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val remainingSeconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    } else {
        "%d:%02d".format(minutes, remainingSeconds)
    }
}

private enum class TvPlayerSubOverlay {
    NONE,
    AUDIO_SUBS,
    EPISODES,
    SERVERS,
}

@Composable
private fun BoxScope.TvPlayerOverlay(
    selection: PlaybackSelection,
    controller: WebPlayerController,
    playing: Boolean,
    servers: List<MoviepireServerOption>,
    switchingServer: Boolean,
    episodes: List<Episode>,
    subtitlesActive: Boolean,
    subtitleTracks: List<SubtitleTrack>?,
    subtitleSearching: Boolean,
    subtitleSearchError: String?,
    selectedSubtitleTrack: SubtitleTrack?,
    subtitleTrackLoading: Boolean,
    subtitleTrackError: String?,
    subtitleDelayTenths: Int,
    subtitleFontPercent: Int,
    onClose: () -> Unit,
    onSelectEpisode: (Episode) -> Unit,
    onSelectServer: (MoviepireServerOption) -> Unit,
    onSearchSubtitles: () -> Unit,
    onSelectTrack: (SubtitleTrack?) -> Unit,
    onDelayChange: (Int) -> Unit,
    onFontPercentChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var activeSubOverlay by remember { mutableStateOf<TvPlayerSubOverlay?>(null) }
    var scrubPositionSeconds by remember { mutableStateOf<Double?>(null) }
    var scrubStepIndex by remember { mutableIntStateOf(0) }
    var lastSeekTimeMs by remember { mutableLongStateOf(0L) }
    var clockTime by remember { mutableStateOf(formatCurrentClockTime()) }

    val seekSteps = listOf(10.0, 30.0, 60.0, 120.0, 300.0)
    val coroutineScope = rememberCoroutineScope()
    var seekDebounceJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val rootFocusRequester = remember { FocusRequester() }
    val playPauseFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        while (isActive) {
            clockTime = formatCurrentClockTime()
            delay(30_000L)
        }
    }

    LaunchedEffect(controlsVisible, playing, activeSubOverlay, scrubPositionSeconds) {
        if (controlsVisible && playing && activeSubOverlay == null && scrubPositionSeconds == null) {
            delay(4000L)
            controlsVisible = false
        }
    }

    LaunchedEffect(controlsVisible, activeSubOverlay) {
        if (controlsVisible && activeSubOverlay == null) {
            try {
                playPauseFocusRequester.requestFocus()
            } catch (_: Exception) {}
        }
    }

    fun adjustScrub(forward: Boolean) {
        controlsVisible = true
        val current = scrubPositionSeconds ?: controller.currentPositionSeconds
        val duration = controller.durationSeconds.takeIf { it > 0.0 }
            ?: (selection.media.runtime.filter { it.isDigit() }.toDoubleOrNull()?.times(60.0) ?: 3600.0)
        val now = System.currentTimeMillis()
        if (now - lastSeekTimeMs < 800L) {
            scrubStepIndex = (scrubStepIndex + 1).coerceAtMost(seekSteps.lastIndex)
        } else {
            scrubStepIndex = 0
        }
        lastSeekTimeMs = now
        val delta = seekSteps[scrubStepIndex] * (if (forward) 1.0 else -1.0)
        val newPos = (current + delta).coerceIn(0.0, duration)
        scrubPositionSeconds = newPos

        seekDebounceJob?.cancel()
        seekDebounceJob = coroutineScope.launch {
            delay(900L)
            controller.seekTo(newPos)
            scrubPositionSeconds = null
            scrubStepIndex = 0
        }
    }

    fun commitScrubImmediately() {
        val pos = scrubPositionSeconds
        if (pos != null) {
            seekDebounceJob?.cancel()
            controller.seekTo(pos)
            scrubPositionSeconds = null
            scrubStepIndex = 0
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(rootFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                if (event.key == Key.Back || event.key == Key.Escape) {
                    if (activeSubOverlay != null) {
                        activeSubOverlay = null
                        controlsVisible = true
                        return@onPreviewKeyEvent true
                    }
                    if (controlsVisible) {
                        commitScrubImmediately()
                        controlsVisible = false
                        return@onPreviewKeyEvent true
                    }
                    onClose()
                    return@onPreviewKeyEvent true
                }

                if (activeSubOverlay != null) {
                    return@onPreviewKeyEvent false
                }

                if (!controlsVisible) {
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> {
                            controller.togglePlayPause()
                            return@onPreviewKeyEvent true
                        }
                        Key.MediaPlay -> {
                            controller.play()
                            return@onPreviewKeyEvent true
                        }
                        Key.MediaPause -> {
                            controller.pause()
                            return@onPreviewKeyEvent true
                        }
                        Key.MediaFastForward, Key.DirectionRight -> {
                            adjustScrub(forward = true)
                            return@onPreviewKeyEvent true
                        }
                        Key.MediaRewind, Key.DirectionLeft -> {
                            adjustScrub(forward = false)
                            return@onPreviewKeyEvent true
                        }
                        Key.DirectionUp, Key.DirectionDown -> {
                            controlsVisible = true
                            return@onPreviewKeyEvent true
                        }
                    }
                } else {
                    if (scrubPositionSeconds != null) {
                        when (event.key) {
                            Key.DirectionLeft, Key.MediaRewind -> {
                                adjustScrub(forward = false)
                                return@onPreviewKeyEvent true
                            }
                            Key.DirectionRight, Key.MediaFastForward -> {
                                adjustScrub(forward = true)
                                return@onPreviewKeyEvent true
                            }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                                commitScrubImmediately()
                                return@onPreviewKeyEvent true
                            }
                        }
                    } else {
                        when (event.key) {
                            Key.MediaFastForward -> {
                                adjustScrub(forward = true)
                                return@onPreviewKeyEvent true
                            }
                            Key.MediaRewind -> {
                                adjustScrub(forward = false)
                                return@onPreviewKeyEvent true
                            }
                            Key.MediaPlayPause -> {
                                controller.togglePlayPause()
                                return@onPreviewKeyEvent true
                            }
                            Key.MediaPlay -> {
                                controller.play()
                                return@onPreviewKeyEvent true
                            }
                            Key.MediaPause -> {
                                controller.pause()
                                return@onPreviewKeyEvent true
                            }
                        }
                    }
                }
                false
            },
    ) {
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                AliflixBlack.copy(alpha = 0.90f),
                                Color.Transparent,
                                Color.Transparent,
                                AliflixBlack.copy(alpha = 0.92f),
                            ),
                        ),
                    ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .padding(horizontal = 36.dp, vertical = 28.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = selection.media.title,
                                color = Color.White,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AliflixAccentPrimaryContainer)
                                    .border(1.dp, AliflixAccentPrimary.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = selection.source.provider.displayName,
                                    color = AliflixAccentSecondary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        if (selection.seasonNumber != null && selection.episodeNumber != null) {
                            val epTitle = selection.episodeTitle?.takeIf { it.isNotBlank() }
                            Text(
                                text = "S${selection.seasonNumber} E${selection.episodeNumber}" +
                                    (if (epTitle != null) " • $epTitle" else ""),
                                color = AliflixAccentSecondary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    Text(
                        text = clockTime,
                        color = AliflixAccentSecondary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                if (scrubPositionSeconds != null) {
                    val currentScrub = scrubPositionSeconds!!
                    val basePos = controller.currentPositionSeconds
                    val deltaSec = (currentScrub - basePos).toInt()
                    val deltaSign = if (deltaSec >= 0) "+" else ""
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .clip(RoundedCornerShape(20.dp))
                            .background(AliflixSurfaceElevated.copy(alpha = 0.95f))
                            .border(1.5.dp, AliflixAccentSecondary, RoundedCornerShape(20.dp))
                            .padding(horizontal = 24.dp, vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "${deltaSign}${deltaSec}s",
                                color = AliflixAccentSecondary,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Black,
                            )
                            Text(
                                text = formatPlaybackTime(currentScrub),
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 36.dp, vertical = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val currentSec = scrubPositionSeconds ?: controller.currentPositionSeconds
                    val durSec = controller.durationSeconds.takeIf { it > 0.0 }
                        ?: (selection.media.runtime.filter { it.isDigit() }.toDoubleOrNull()?.times(60.0) ?: 3600.0)
                    val progressFrac = (currentSec / durSec).coerceIn(0.0, 1.0).toFloat()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text(
                            text = formatPlaybackTime(currentSec),
                            color = if (scrubPositionSeconds != null) AliflixAccentSecondary else Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                        )
                        LinearProgressIndicator(
                            progress = { progressFrac },
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = AliflixAccentPrimary,
                            trackColor = Color.White.copy(alpha = 0.22f),
                        )
                        Text(
                            text = formatPlaybackTime(durSec),
                            color = AliflixContentSecondary,
                            fontSize = 13.sp,
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TvPlayerIconButton(
                                icon = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                                onClick = { controller.togglePlayPause() },
                                focusRequester = playPauseFocusRequester,
                            )
                            TvPlayerIconButton(
                                icon = Icons.Rounded.Replay10,
                                contentDescription = "Rewind 10 seconds",
                                onClick = {
                                    val target = (controller.currentPositionSeconds - 10.0).coerceAtLeast(0.0)
                                    controller.seekTo(target)
                                },
                            )
                            TvPlayerIconButton(
                                icon = Icons.Rounded.Forward10,
                                contentDescription = "Forward 10 seconds",
                                onClick = {
                                    val target = (controller.currentPositionSeconds + 10.0).coerceAtMost(durSec)
                                    controller.seekTo(target)
                                },
                            )
                            TvPlayerIconButton(
                                icon = Icons.Rounded.Subtitles,
                                contentDescription = "Subtitles and audio",
                                active = subtitlesActive,
                                onClick = {
                                    if (subtitleTracks == null && !subtitleSearching) {
                                        onSearchSubtitles()
                                    }
                                    activeSubOverlay = TvPlayerSubOverlay.AUDIO_SUBS
                                },
                            )
                            if (episodes.isNotEmpty()) {
                                TvPlayerIconButton(
                                    icon = Icons.Rounded.VideoLibrary,
                                    contentDescription = "Episodes",
                                    onClick = {
                                        activeSubOverlay = TvPlayerSubOverlay.EPISODES
                                    },
                                )
                            }
                            if (servers.isNotEmpty()) {
                                TvPlayerIconButton(
                                    icon = Icons.Rounded.Dns,
                                    contentDescription = "Servers",
                                    onClick = {
                                        activeSubOverlay = TvPlayerSubOverlay.SERVERS
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = activeSubOverlay != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AliflixBlack.copy(alpha = 0.88f))
                    .padding(horizontal = 48.dp, vertical = 36.dp),
            ) {
                when (activeSubOverlay) {
                    TvPlayerSubOverlay.AUDIO_SUBS -> {
                        TvAudioSubtitlesSheet(
                            tracks = subtitleTracks.orEmpty(),
                            searching = subtitleSearching,
                            searchError = subtitleSearchError,
                            selectedTrack = selectedSubtitleTrack,
                            trackLoading = subtitleTrackLoading,
                            trackError = subtitleTrackError,
                            delayTenths = subtitleDelayTenths,
                            fontPercent = subtitleFontPercent,
                            onClose = { activeSubOverlay = null },
                            onRetry = onSearchSubtitles,
                            onDisable = {
                                onSelectTrack(null)
                                activeSubOverlay = null
                            },
                            onSelectTrack = { track ->
                                onSelectTrack(track)
                                activeSubOverlay = null
                            },
                            onDelayChange = onDelayChange,
                            onFontPercentChange = onFontPercentChange,
                        )
                    }
                    TvPlayerSubOverlay.EPISODES -> {
                        TvEpisodesRailSheet(
                            episodes = episodes,
                            currentEpisodeNumber = selection.episodeNumber,
                            onSelectEpisode = { ep ->
                                onSelectEpisode(ep)
                                activeSubOverlay = null
                            },
                            onClose = { activeSubOverlay = null },
                        )
                    }
                    TvPlayerSubOverlay.SERVERS -> {
                        TvServersSheet(
                            servers = servers,
                            switchingServer = switchingServer,
                            onSelectServer = { s ->
                                onSelectServer(s)
                                activeSubOverlay = null
                            },
                            onClose = { activeSubOverlay = null },
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun TvPlayerIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    focusRequester: FocusRequester? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .scale(if (isFocused) 1.15f else 1.0f)
            .clip(CircleShape)
            .background(
                when {
                    isFocused -> AliflixAccentPrimary
                    active -> AliflixAccentPrimaryContainer
                    else -> Color.Black.copy(alpha = 0.65f)
                },
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = when {
                    isFocused -> AliflixAccentSecondary
                    active -> AliflixAccentPrimary
                    else -> AliflixBorderSubtle
                },
                shape = CircleShape,
            )
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isFocused || active) Color.White else AliflixContentSecondary,
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun BoxScope.TvAudioSubtitlesSheet(
    tracks: List<SubtitleTrack>,
    searching: Boolean,
    searchError: String?,
    selectedTrack: SubtitleTrack?,
    trackLoading: Boolean,
    trackError: String?,
    delayTenths: Int,
    fontPercent: Int,
    onClose: () -> Unit,
    onRetry: () -> Unit,
    onDisable: () -> Unit,
    onSelectTrack: (SubtitleTrack) -> Unit,
    onDelayChange: (Int) -> Unit,
    onFontPercentChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            initialFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(AliflixSurfaceElevated)
            .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(24.dp))
            .padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Subtitles & Audio",
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Use D-pad to adjust sync and select language",
                    color = AliflixContentSecondary,
                    fontSize = 13.sp,
                )
            }
            Text(
                text = "Back to return",
                color = AliflixAccentSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            TvAdjusterCard(
                title = "Sync Offset",
                value = "%+.1fs".format(delayTenths / 10.0),
                onDecrease = { onDelayChange(delayTenths - 1) },
                onIncrease = { onDelayChange(delayTenths + 1) },
                modifier = Modifier.weight(1f),
            )
            TvAdjusterCard(
                title = "Font Size",
                value = "${fontPercent}%",
                onDecrease = { onFontPercentChange(fontPercent - 10) },
                onIncrease = { onFontPercentChange(fontPercent + 10) },
                modifier = Modifier.weight(1f),
            )
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when {
                searching -> {
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(
                            color = AliflixAccentSecondary,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(24.dp),
                        )
                        Text("Searching subtitles…", color = Color.White, fontSize = 14.sp)
                    }
                }
                searchError != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(text = searchError, color = AliflixContentSecondary, fontSize = 13.sp)
                        TvSimpleButton(text = "Retry", onClick = onRetry, focusRequester = initialFocusRequester)
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item(key = "off") {
                            TvSubtitleItem(
                                title = "Off",
                                subtitle = "Disable subtitles",
                                selected = selectedTrack == null,
                                onClick = onDisable,
                                focusRequester = initialFocusRequester,
                            )
                        }
                        items(tracks, key = SubtitleTrack::id) { track ->
                            TvSubtitleItem(
                                title = track.languageName.ifBlank { track.languageCode },
                                subtitle = buildString {
                                    append(track.releaseName)
                                    track.fps?.let { append(" • ").append(it).append(" fps") }
                                    if (track.hearingImpaired) append(" • HI")
                                },
                                selected = selectedTrack?.id == track.id,
                                onClick = { onSelectTrack(track) },
                            )
                        }
                    }
                }
            }
        }

        if (trackLoading) {
            Text("Loading subtitle track…", color = AliflixAccentSecondary, fontSize = 12.sp)
        }
        if (trackError != null) {
            Text(text = trackError, color = AliflixError, fontSize = 12.sp)
        }
    }
}

@Composable
private fun TvAdjusterCard(
    title: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(AliflixSurfaceRaised)
            .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(16.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(title, color = AliflixContentPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(value, color = AliflixAccentSecondary, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvSmallIconButton(icon = Icons.Rounded.Remove, contentDescription = "Decrease $title", onClick = onDecrease)
            TvSmallIconButton(icon = Icons.Rounded.Add, contentDescription = "Increase $title", onClick = onIncrease)
        }
    }
}

@Composable
private fun TvSmallIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .scale(if (isFocused) 1.15f else 1.0f)
            .clip(CircleShape)
            .background(if (isFocused) AliflixAccentPrimary else Color.Black.copy(alpha = 0.4f))
            .border(1.dp, if (isFocused) AliflixAccentSecondary else AliflixBorderSubtle, CircleShape)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isFocused) Color.White else AliflixContentSecondary,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun TvSubtitleItem(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .scale(if (isFocused) 1.02f else 1.0f)
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    isFocused -> AliflixAccentPrimary
                    selected -> AliflixAccentPrimaryContainer
                    else -> AliflixSurfaceRaised
                },
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = when {
                    isFocused -> AliflixAccentSecondary
                    selected -> AliflixAccentPrimary
                    else -> AliflixBorderSubtle
                },
                shape = RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                color = Color.White,
                fontWeight = if (isFocused || selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 14.sp,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = if (isFocused) Color.White.copy(alpha = 0.85f) else AliflixContentSecondary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = if (isFocused) Color.White else AliflixAccentSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun BoxScope.TvEpisodesRailSheet(
    episodes: List<Episode>,
    currentEpisodeNumber: Int?,
    onSelectEpisode: (Episode) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            initialFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .clip(RoundedCornerShape(24.dp))
            .background(AliflixSurfaceElevated)
            .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(24.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Select Episode",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Back to return",
                color = AliflixAccentSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            itemsIndexed(episodes, key = { _, ep -> "${ep.seasonNumber}_${ep.number}" }) { index, ep ->
                val isSelected = ep.number == currentEpisodeNumber
                TvEpisodeRailCard(
                    episode = ep,
                    selected = isSelected,
                    onSelect = { onSelectEpisode(ep) },
                    focusRequester = if (isSelected || (currentEpisodeNumber == null && index == 0)) initialFocusRequester else null,
                )
            }
        }
    }
}

@Composable
private fun TvEpisodeRailCard(
    episode: Episode,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .widthIn(min = 200.dp, max = 240.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onSelect)
            .scale(if (isFocused) 1.06f else 1.0f)
            .clip(RoundedCornerShape(16.dp))
            .background(
                when {
                    isFocused -> AliflixAccentPrimary
                    selected -> AliflixAccentPrimaryContainer
                    else -> AliflixSurfaceRaised
                },
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = when {
                    isFocused -> AliflixAccentSecondary
                    selected -> AliflixAccentPrimary
                    else -> AliflixBorderSubtle
                },
                shape = RoundedCornerShape(16.dp),
            )
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = "E${episode.number} • ${episode.title.ifBlank { "Episode ${episode.number}" }}",
                color = Color.White,
                fontWeight = if (isFocused || selected) FontWeight.Bold else FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (episode.overview.isNotBlank()) {
                Text(
                    text = episode.overview,
                    color = if (isFocused) Color.White.copy(alpha = 0.85f) else AliflixContentSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BoxScope.TvServersSheet(
    servers: List<MoviepireServerOption>,
    switchingServer: Boolean,
    onSelectServer: (MoviepireServerOption) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val initialFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            initialFocusRequester.requestFocus()
        } catch (_: Exception) {}
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter)
            .clip(RoundedCornerShape(24.dp))
            .background(AliflixSurfaceElevated)
            .border(1.dp, AliflixBorderSubtle, RoundedCornerShape(24.dp))
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Playback Servers",
                color = Color.White,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Back to return",
                color = AliflixAccentSecondary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            servers.forEachIndexed { index, server ->
                TvServerCard(
                    server = server,
                    switching = switchingServer && server.selected,
                    onSelect = { onSelectServer(server) },
                    focusRequester = if (server.selected || index == 0) initialFocusRequester else null,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TvServerCard(
    server: MoviepireServerOption,
    switching: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onSelect)
            .scale(if (isFocused) 1.05f else 1.0f)
            .clip(RoundedCornerShape(14.dp))
            .background(
                when {
                    isFocused -> AliflixAccentPrimary
                    server.selected -> AliflixAccentPrimaryContainer
                    else -> AliflixSurfaceRaised
                },
            )
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = when {
                    isFocused -> AliflixAccentSecondary
                    server.selected -> AliflixAccentPrimary
                    else -> AliflixBorderSubtle
                },
                shape = RoundedCornerShape(14.dp),
            )
            .padding(14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = server.label,
                color = Color.White,
                fontWeight = if (isFocused || server.selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = 13.sp,
            )
            if (switching) {
                CircularProgressIndicator(
                    color = AliflixAccentSecondary,
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(14.dp),
                )
            } else if (server.selected) {
                Icon(
                    Icons.Rounded.Check,
                    contentDescription = "Active Server",
                    tint = if (isFocused) Color.White else AliflixAccentSecondary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun TvSimpleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    var isFocused by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .scale(if (isFocused) 1.08f else 1.0f)
            .clip(RoundedCornerShape(12.dp))
            .background(if (isFocused) AliflixAccentPrimary else AliflixSurfaceRaised)
            .border(1.dp, if (isFocused) AliflixAccentSecondary else AliflixBorderSubtle, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

private fun formatCurrentClockTime(): String {
    return SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
}

