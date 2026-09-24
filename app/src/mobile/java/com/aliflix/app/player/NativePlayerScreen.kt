package com.aliflix.app.player

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import coil.compose.AsyncImage
import com.aliflix.app.model.Episode
import com.aliflix.app.model.PlaybackSelection
import com.aliflix.app.ui.launch.AnimatedAliflixHeatmapLogo
import com.aliflix.app.ui.theme.AliflixAccentPrimary
import com.aliflix.app.ui.theme.AliflixAccentPrimaryContainer
import com.aliflix.app.ui.theme.AliflixAccentSecondary
import com.aliflix.app.ui.theme.AliflixError
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

internal data class NativePlayerUi(
    val captionDragging: Boolean = false,
    val title: String = "Aliflix",
    val detail: String = "",
    val artwork: String? = null,
    val stage: String? = null,
    val error: String? = null,
    val server: String = "Auto",
    val availableServers: List<String> = emptyList(),
    val ready: Boolean = false,
    val external: Boolean = false,
    val revision: Int = 0,
    val episodes: List<Episode> = emptyList(),
    val episodeNumber: Int? = null,
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val activeSubtitleTrack: SubtitleTrack? = null,
    val subtitleLoading: Boolean = false,
    val subtitleError: String? = null,
    val message: String? = null,
    val segments: List<IntroSegment> = emptyList(),
    val playbackSelection: PlaybackSelection? = null,
)

private val PlayerInk = Color(0xFF07080C)
private val PlayerScrimColor = Color.Black.copy(alpha = 0.28f)

private data class HudFeedback(
    val icon: ImageVector,
    val text: String,
    val progress: Float? = null,
)

/**
 * Phone player using the approved v3.1.75 mobile playback redesign:
 * - Compact top bar (back, complete identity, Episodes / Subtitles / Rotate / More)
 * - Outlined 64dp play/pause with Â±15 second seek controls
 * - 35% / 30% / 35% double-tap seek zones with cumulative Â±15/Â±30/Â±45 HUD
 * - Redesigned purple timeline with scrub bubble
 * - Redesigned Episodes panel and playback settings (More) menu
 * - 3-second auto-hide during playback
 *
 * Native Media3 playback, subtitles, quality, servers, casting, rotation,
 * fit/fill, brightness/volume gestures, skip intro and next-episode behavior
 * are preserved from the previous native player.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NativePlayerScreen(
    state: NativePlayerUi,
    player: Player?,
    settings: PlayerSettings = PlayerSettings(),
    onBack: () -> Unit = {},
    onRetry: () -> Unit = {},
    onServer: () -> Unit = {},
    onSelectServer: (String) -> Unit = {},
    onStop: () -> Unit = {},
    onStopCast: () -> Unit = {},
    onWireless: () -> Unit = {},
    onFit: (Boolean) -> Unit = {},
    onRotate: () -> Unit = {},
    onSubtitleSearch: () -> Unit = {},
    onSubtitle: (SubtitleTrack) -> Unit = {},
    onSubtitleDisable: () -> Unit = {},
    onSubtitleDelayChange: (Int) -> Unit = {},
    onSubtitleVerticalOffsetChange: (Int) -> Unit = {},
    onSubtitleFontSizeChange: (Float) -> Unit = {},
    onSubtitleOpacityChange: (Float) -> Unit = {},
    onSpeedChange: (Float) -> Unit = {},
    onEpisode: (Episode) -> Unit = {},
    onReceiver: () -> Unit = {},
    onBrightnessSwipe: (Float) -> Float = { 0.5f },
    onVolumeSwipe: (Float) -> Float = { 0.5f },
    onVolumeGestureStarted: () -> Unit = {},
    onControlsVisibilityChanged: (Boolean) -> Unit = {},
    onOverlayVisibilityChanged: (Boolean) -> Unit = {},
) {
    val subtitleTracks = remember(state.subtitleTracks) { normalizeMobileSubtitleTracks(state.subtitleTracks) }
    var controls by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var isScrubbing by remember { mutableStateOf(false) }
    var episodesVisible by remember { mutableStateOf(false) }
    var moreVisible by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var fill by rememberSaveable { mutableStateOf(settings.resizeModeZoom) }
    var hudFeedback by remember { mutableStateOf<HudFeedback?>(null) }
    var hudVisible by remember { mutableStateOf(false) }
    var levelGestureActive by remember { mutableStateOf(false) }
    var hudTimerJob by remember { mutableStateOf<Job?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val seekFeedback = remember { SeekFeedbackController() }
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val density = LocalDensity.current
    val levelGestureEdge = with(density) { 28.dp.toPx() }
    val layoutDirection = LocalLayoutDirection.current
    val safeInsets = WindowInsets.safeDrawing
    val safeLeftInset = safeInsets.getLeft(density, layoutDirection).toFloat()
    val safeTopInset = safeInsets.getTop(density).toFloat()
    val safeRightInset = safeInsets.getRight(density, layoutDirection).toFloat()
    val safeBottomInset = safeInsets.getBottom(density).toFloat()
    val startVolumeGesture = rememberUpdatedState(onVolumeGestureStarted)
    val hudTopPadding = when {
        controls && isLandscape -> 84.dp
        controls -> 104.dp
        isLandscape -> 28.dp
        else -> 72.dp
    }

    LaunchedEffect(seekFeedback.state?.token) {
        if (seekFeedback.state != null) { delay(650); seekFeedback.dismiss() }
    }
    val overlayOpen = sheet != null || episodesVisible || moreVisible
    LaunchedEffect(overlayOpen) { onOverlayVisibilityChanged(overlayOpen) }
    LaunchedEffect(controls) { onControlsVisibilityChanged(controls) }
    LaunchedEffect(state.message) { state.message?.let { snackbar.showSnackbar(it) } }
    val preparing = state.stage != null || (!state.ready && state.error == null && player?.mediaItemCount != 0)
    val playing = player?.isPlaying == true
    val ended = player?.playbackState == Player.STATE_ENDED
    val duration = (player?.duration ?: 0L).coerceAtLeast(0L)
    val position = (player?.currentPosition ?: 0L).coerceIn(0L, duration.coerceAtLeast(1L))
    val next = state.episodes.dropWhile { it.number != state.episodeNumber }.drop(1).firstOrNull()

    // Next episode countdown
    var countdownCancelled by remember(state.episodeNumber) { mutableStateOf(false) }
    val remainingMs = duration - position
    val isNearEnd = duration > 45_000 && remainingMs in 1..25_000
    val showCountdown = next != null && isNearEnd && !countdownCancelled && !ended && !preparing && state.error == null

    LaunchedEffect(showCountdown, remainingMs) {
        if (showCountdown && remainingMs <= 1200 && next != null) {
            onEpisode(next)
        }
    }

    fun showHud(icon: ImageVector, text: String, progress: Float? = null) {
        hudFeedback = HudFeedback(icon, text, progress)
        hudVisible = true
        hudTimerJob?.cancel()
        hudTimerJob = coroutineScope.launch {
            delay(900)
            while (levelGestureActive) delay(100)
            hudVisible = false
        }
    }

    fun seekWithFeedback(forward: Boolean) {
        val current = player ?: return
        if (!current.isCurrentMediaItemSeekable || !current.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) return
        val from = current.currentPosition
        val durationMs = current.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        val target = (from + if (forward) 15_000L else -15_000L).coerceIn(0L, durationMs)
        if (target == from) return
        current.seekTo(target)
        seekFeedback.triggerSeek(forward)
        interaction++
    }

    // Auto-hide controls after 3 seconds of inactivity while playing
    LaunchedEffect(playing, controls, interaction, overlayOpen, isScrubbing, state.captionDragging) {
        if (playing && controls && !overlayOpen && !isScrubbing && !state.captionDragging) {
            delay(3000)
            controls = false
        }
    }
    LaunchedEffect(state.error, ended) { if (state.error != null || ended) controls = true }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(preparing, state.error) {
                if (preparing || state.error != null) return@pointerInput
                awaitPointerEventScope {
                    var accumulatedZoom = 1f
                    var pinching = false
                    while (true) {
                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        if (event.changes.count { it.pressed } >= 2) {
                            pinching = true
                            val zoomChange = event.calculateZoom()
                            if ((accumulatedZoom > 1f && zoomChange < 1f) || (accumulatedZoom < 1f && zoomChange > 1f)) accumulatedZoom = 1f
                            accumulatedZoom *= zoomChange
                            if (accumulatedZoom > 1.06f && !fill) {
                                accumulatedZoom = 1f
                                fill = true
                                onFit(true)
                                showHud(Icons.Default.AspectRatio, "Fill Screen")
                            } else if (accumulatedZoom < 0.94f && fill) {
                                accumulatedZoom = 1f
                                fill = false
                                onFit(false)
                                showHud(Icons.Default.AspectRatio, "Fit to Screen")
                            }
                        }
                        if (pinching) event.changes.forEach { it.consume() }
                        if (event.changes.none { it.pressed }) {
                            accumulatedZoom = 1f
                            pinching = false
                        }
                    }
                }
            }
            .pointerInput(preparing, state.error, overlayOpen, levelGestureEdge, safeLeftInset, safeTopInset, safeRightInset, safeBottomInset) {
                if (preparing || state.error != null || overlayOpen) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    if (!playerLevelGestureAllowed(
                            down.position.x,
                            down.position.y,
                            size.width.toFloat(),
                            size.height.toFloat(),
                            levelGestureEdge,
                            safeLeftInset,
                            safeTopInset,
                            safeRightInset,
                            safeBottomInset,
                        )) return@awaitEachGesture
                    val isLeft = down.position.x < size.width / 2f
                    fun adjust(amount: Float) {
                        val delta = -amount / (size.height * .7f).coerceAtLeast(1f)
                        val level = if (isLeft) onBrightnessSwipe(delta) else onVolumeSwipe(delta)
                        val icon = when {
                            isLeft && level <= .25f -> Icons.Default.BrightnessLow
                            isLeft && level < .75f -> Icons.Default.BrightnessMedium
                            isLeft -> Icons.Default.BrightnessHigh
                            level <= .01f -> Icons.AutoMirrored.Filled.VolumeMute
                            level < .5f -> Icons.AutoMirrored.Filled.VolumeDown
                            else -> Icons.AutoMirrored.Filled.VolumeUp
                        }
                        showHud(icon, if (isLeft) "Brightness" else "Volume", level)
                    }
                    val drag = awaitVerticalTouchSlopOrCancellation(down.id) { change, overSlop ->
                        change.consume()
                        if (!isLeft) startVolumeGesture.value()
                        levelGestureActive = true
                        adjust(overSlop)
                    }
                    if (drag != null) {
                        try {
                            verticalDrag(drag.id) { change ->
                                adjust(change.position.y - change.previousPosition.y)
                                change.consume()
                            }
                        } finally { levelGestureActive = false }
                    }
                }
            }
    ) {
        // Fullscreen tap / double-tap seek layer (35% / 30% / 35% zones)
        if (!preparing && state.error == null) {
            MobileVideoGestureDetector(
                onToggleControls = {
                    controls = !controls
                    interaction++
                },
                onSeekRewind = { seekWithFeedback(false) },
                onSeekForward = { seekWithFeedback(true) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (!controls) SeekFeedbackHud(seekFeedback.state, { seekFeedback.dismiss() })

        // Artwork Background
        if (preparing || state.error != null || state.external || ended) {
            Box(Modifier.fillMaxSize().background(PlayerInk)) {
                AsyncImage(state.artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.28f)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(PlayerInk.copy(alpha = 0.4f), PlayerInk.copy(alpha = 0.96f)))))
            }
        }

        // Preparing / Loading Overlay
        AnimatedVisibility(visible = preparing, modifier = Modifier.align(Alignment.Center),
            enter = fadeIn(tween(160)) + androidx.compose.animation.scaleIn(tween(180), initialScale = .96f),
            exit = fadeOut(tween(120))) {
            Column(
                Modifier.widthIn(max = 520.dp).padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AnimatedAliflixHeatmapLogo(Modifier.size(56.dp))
                Text(
                    state.title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (state.detail.isNotBlank()) {
                    Text(state.detail, color = Color.White.copy(alpha = 0.65f), textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                }
                LinearProgressIndicator(
                    Modifier.width(180.dp).height(3.dp).clip(CircleShape),
                    color = AliflixAccentPrimary,
                    trackColor = Color.White.copy(alpha = 0.12f)
                )
                Text(state.stage ?: "Preparing your video", color = AliflixAccentSecondary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        // In-Player Failure / Error Card (Keeping user inside player)
        if (state.error != null && !preparing) {
            Surface(
                modifier = Modifier.align(Alignment.Center).padding(24.dp).widthIn(max = 440.dp),
                shape = RoundedCornerShape(20.dp),
                color = Color(0xF510131A),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                shadowElevation = 24.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = AliflixError, modifier = Modifier.size(42.dp))
                    Text("Playback Problem", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(state.error, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center, fontSize = 13.sp)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onServer,
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.25f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Dns, null, modifier = Modifier.size(16.dp), tint = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text("Switch Server", color = Color.White, fontSize = 13.sp)
                        }
                        Button(
                            onClick = onRetry,
                            colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp), tint = Color.White)
                            Spacer(Modifier.width(6.dp))
                            Text("Retry", color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
            }
        }

        // External TV Casting Notice
        if (state.external && !preparing && state.error == null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xE610131A),
                border = BorderStroke(1.dp, AliflixAccentPrimary.copy(alpha = 0.4f)),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 80.dp).padding(horizontal = 24.dp)
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Default.CastConnected, null, tint = AliflixAccentSecondary, modifier = Modifier.size(22.dp))
                    Column {
                        Text("Playing on your TV", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Use other apps. Control playback from this screen.", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                    }
                }
            }
        }

        // Subtle dark scrim when controls are visible
        AnimatedVisibility(
            visible = controls,
            enter = fadeIn(tween(180)) + androidx.compose.animation.scaleIn(tween(220), initialScale = 0.98f),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize().background(PlayerScrimColor))
        }

        // Redesigned controls overlay (compact top bar, outlined center controls, purple timeline)
        AnimatedVisibility(
            visible = controls,
            enter = fadeIn(tween(180)) + androidx.compose.animation.scaleIn(tween(220), initialScale = 0.98f),
            exit = fadeOut(tween(200)),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    ,
            ) {
                MobilePlayerTopBar(
                    title = state.title,
                    detail = state.detail,
                    // The approved top bar shows the Episodes button for TV content;
                    // the native player knows episode availability directly.
                    isTv = state.episodes.isNotEmpty(),
                    onBack = onBack,
                    onEpisodes = {
                        episodesVisible = true
                        interaction++
                    },
                    onSubtitles = {
                        sheet = "Audio & subtitles"
                        interaction++
                    },
                    onQuality = {
                        sheet = "Quality"
                        interaction++
                    },
                    onRotate = {
                        onRotate()
                        interaction++
                    },
                    onMore = {
                        moreVisible = true
                        interaction++
                    },
                    modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).padding(top = 8.dp).animateEnterExit(
                        enter = androidx.compose.animation.slideInVertically(tween(200)) { -it / 3 },
                        exit = androidx.compose.animation.slideOutVertically(tween(140)) { -it / 4 }),
                )

                if (!preparing && state.error == null) {
                    MobilePlayerCenterControls(
                        isPlaying = playing,
                        onPlayPause = {
                            player?.let { p ->
                                if (ended) p.seekTo(0)
                                if (p.playWhenReady && !ended) p.pause() else p.play()
                            }
                            interaction++
                        },
                        onSeekBack = { seekWithFeedback(false) },
                        onSeekForward = { seekWithFeedback(true) },
                        feedback = seekFeedback.state,
                        modifier = Modifier.align(Alignment.Center).animateEnterExit(
                            enter = fadeIn(tween(220, delayMillis = 35)) + scaleIn(tween(260, easing = FastOutSlowInEasing), initialScale = .86f),
                            exit = fadeOut(tween(150)) + scaleOut(tween(190), targetScale = .93f)),
                    )
                }

                if (!preparing && state.ready && state.error == null &&
                    player?.playbackState in setOf(Player.STATE_READY, Player.STATE_ENDED)) MobilePlayerTimeline(
                    currentPositionMs = position,
                    durationMs = duration,
                    bufferedPositionMs = player?.bufferedPosition ?: 0L,
                    onSeek = { targetMs ->
                        player?.seekTo(targetMs)
                        interaction++
                    },
                    onScrubbingChanged = { scrubbing ->
                        isScrubbing = scrubbing
                        if (scrubbing) interaction++
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                        .animateEnterExit(enter = androidx.compose.animation.slideInVertically(tween(200)) { it / 3 },
                            exit = androidx.compose.animation.slideOutVertically(tween(140)) { it / 4 })
                        .padding(bottom = 20.dp),
                )
            }
        }

        // IN-PLAYER BUFFERING INDICATOR (pulsing ring loader)
        if (!preparing && state.error == null && player?.playbackState == Player.STATE_BUFFERING) {
            Box(modifier = Modifier.align(Alignment.Center), contentAlignment = Alignment.Center) {
                val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                val pulseScale by infiniteTransition.animateFloat(
                    initialValue = 1f,
                    targetValue = 1.4f,
                    animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                    label = "pulseScale"
                )
                val pulseAlpha by infiniteTransition.animateFloat(
                    initialValue = 0.35f,
                    targetValue = 0.05f,
                    animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                    label = "pulseAlpha"
                )
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .graphicsLayer { scaleX = pulseScale; scaleY = pulseScale; alpha = pulseAlpha }
                        .background(AliflixAccentPrimary, CircleShape)
                )
                CircularProgressIndicator(
                    modifier = Modifier.size(52.dp),
                    color = AliflixAccentPrimary,
                    strokeWidth = 3.dp,
                    trackColor = Color.White.copy(alpha = 0.15f)
                )
            }
        }

        // NEXT EPISODE COUNTDOWN CARD
        AnimatedVisibility(
            visible = showCountdown,
            enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 2 },
            exit = fadeOut(tween(200)) + slideOutVertically(tween(200)) { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(end = 16.dp, bottom = if (controls) 140.dp else 24.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xF212151E),
                border = BorderStroke(1.dp, AliflixAccentPrimary.copy(alpha = 0.6f)),
                shadowElevation = 16.dp,
                modifier = Modifier.widthIn(max = 360.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        val sec = (remainingMs / 1000).toInt().coerceIn(1, 25)
                        CircularProgressIndicator(
                            progress = { (remainingMs.toFloat() / 25_000f).coerceIn(0f, 1f) },
                            color = AliflixAccentPrimary,
                            trackColor = Color.White.copy(alpha = 0.15f),
                            modifier = Modifier.size(36.dp),
                            strokeWidth = 3.dp,
                        )
                        Text("$sec", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Column(Modifier.weight(1f)) {
                        Text("UP NEXT", color = AliflixAccentSecondary, fontWeight = FontWeight.Bold, fontSize = 10.sp, letterSpacing = 1.sp)
                        Text("E${next!!.number} Â· ${next.title}", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OpenPlayerButton(
                        onClick = { next?.let { onEpisode(it) } },
                        modifier = Modifier.size(36.dp).background(AliflixAccentPrimary, CircleShape)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play Now", tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    OpenPlayerButton(
                        onClick = { countdownCancelled = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                    }
                }
            }
        }

        // Intro / Skip Segment Button
        val segment = state.segments.firstOrNull { it.isActive(position, duration) }
        if (!preparing && state.error == null && !overlayOpen && player?.isCurrentMediaItemSeekable == true && !showCountdown) {
            AnimatedVisibility(
                segment != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(end = 20.dp, bottom = if (controls) 140.dp else 24.dp)
            ) {
                segment?.let { marker ->
                    Button(
                        onClick = { player.seekTo(marker.endMs); interaction++ },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AliflixAccentPrimary, contentColor = Color.White),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp)
                    ) {
                        Text(marker.kind.label, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Default.SkipNext, null, Modifier.size(18.dp))
                    }
                }
            }
        }

        // HUD Indicator (Brightness / Volume / Pinch)
        AnimatedVisibility(
            visible = hudVisible,
            enter = fadeIn(tween(180)) + scaleIn(tween(220, easing = FastOutSlowInEasing), initialScale = 0.96f),
            exit = fadeOut(tween(240)) + scaleOut(tween(240), targetScale = 0.98f),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = hudTopPadding)
        ) {
            hudFeedback?.let { item ->
                val displayedLevel by animateFloatAsState(item.progress ?: 0f,
                    spring(dampingRatio = 1f, stiffness = 800f), label = "player-level")
                Surface(
                    shape = RoundedCornerShape(50), color = Color(0xDC171922),
                    border = BorderStroke(0.5.dp, Color.White.copy(alpha = 0.12f)),
                    shadowElevation = 4.dp,
                ) {
                    Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(item.icon, item.text, tint = Color.White.copy(alpha = .9f), modifier = Modifier.size(20.dp))
                        if (item.progress != null) {
                            LinearProgressIndicator(
                                progress = { displayedLevel },
                                modifier = Modifier.width(92.dp).height(3.dp).clip(CircleShape),
                                color = Color.White.copy(alpha = .9f), trackColor = Color.White.copy(alpha = .14f),
                            )
                            Text("${(item.progress * 100).toInt()}%", modifier = Modifier.width(36.dp),
                                color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.End)
                        } else Text(item.text, color = Color.White, fontSize = 12.sp)
                    }
                }
            }
        }

        // Cumulative double-tap / Â±15s seek feedback HUD


        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    // Redesigned Episodes panel (landscape side panel / portrait bottom sheet)
    MobileEpisodesOverlay(
        visible = episodesVisible,
        isLandscape = isLandscape,
        episodes = state.episodes,
        currentSeason = state.episodes.firstOrNull { it.number == state.episodeNumber }?.seasonNumber,
        currentEpisode = state.episodeNumber,
        onSelectEpisode = { episode ->
            episodesVisible = false
            onEpisode(episode)
            interaction++
        },
        onDismiss = { episodesVisible = false },
    )

    // Redesigned playback settings (More) menu
    val playbackSelection = state.playbackSelection
    if (playbackSelection != null) {
        val serverOptions = remember(state.availableServers, state.server) {
            (state.availableServers.ifEmpty { listOf(state.server).filter { it.isNotBlank() } })
                .map { name -> MoviepireServerOption(key = name, label = name, selected = name.equals(state.server, ignoreCase = true)) }
        }
        MobilePlayerMoreSheet(
            visible = moreVisible,
            selection = playbackSelection,
            servers = serverOptions,
            subtitlesActive = state.activeSubtitleTrack != null,
            subtitleLanguage = state.activeSubtitleTrack?.languageName,
            onOpenSubtitles = {
                moreVisible = false
                sheet = "Audio & subtitles"
            },
            onSelectSpeed = { speed ->
                player?.setPlaybackSpeed(speed)
                onSpeedChange(speed)
                interaction++
            },
            onSelectServer = { option ->
                moreVisible = false
                onSelectServer(option.key)
            },
            onOpenProviderOptions = {
                moreVisible = false
                onServer()
            },
            onDismiss = { moreVisible = false },
            currentSpeed = player?.playbackParameters?.speed,
            onStopPlayback = {
                moreVisible = false
                onStop()
            },
            onOpenWirelessDisplay = {
                moreVisible = false
                onWireless()
            },
            castActive = state.external,
            onCast = {
                moreVisible = false
                if (state.external) {
                    sheet = "CastOptions"
                } else {
                    onReceiver()
                }
                interaction++
            },
        )
    }

    // MODAL BOTTOM SHEETS (Audio & subtitles, Quality, Cast options)
    if (sheet != null) {
        ModalBottomSheet(
            onDismissRequest = { sheet = null; interaction++ },
            containerColor = Color(0xFF141620),
            contentColor = Color.White,
            dragHandle = { BottomSheetDefaults.DragHandle(color = Color.White.copy(alpha = 0.3f)) }
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = sheet!!,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 6.dp)
                )

                when (sheet) {
                    "CastOptions" -> {
                        SheetOption("Stop Casting", "Disconnect TV and return playback to phone") {
                            sheet = null
                            onStopCast()
                        }
                        SheetOption("Switch Device", "Connect to another Google Cast receiver") {
                            sheet = null
                            onReceiver()
                        }
                    }

                    "Quality" -> {
                        SheetOption("Auto", selected = player?.trackSelectionParameters?.overrides?.values?.none { it.type == C.TRACK_TYPE_VIDEO } == true) {
                            player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build() }
                            sheet = null
                        }
                        TrackOptions(player, C.TRACK_TYPE_VIDEO) { sheet = null }
                    }

                    "Audio & subtitles" -> {
                        // Subtitle Sync / Delay
                        Text("SUBTITLE SYNC", color = AliflixAccentSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = Color.White.copy(alpha = 0.05f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Time Offset", color = Color.White, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                                    val offsetSec = settings.subtitleDelaySeconds
                                    val offsetText = if (offsetSec == 0.0) "0.0s" else if (offsetSec > 0) "+%.1fs".format(Locale.ROOT, offsetSec) else "%.1fs".format(Locale.ROOT, offsetSec)
                                    Text(offsetText, color = if (offsetSec != 0.0) AliflixAccentSecondary else Color.White.copy(alpha = 0.6f), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    FilledTonalIconButton(
                                        onClick = { onSubtitleDelayChange(settings.subtitleDelayTenths - 1) },
                                        modifier = Modifier.size(38.dp),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.White.copy(alpha = 0.1f), contentColor = Color.White)
                                    ) {
                                        Text("âˆ’0.1", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                    if (settings.subtitleDelayTenths != 0) {
                                        TextButton(onClick = { onSubtitleDelayChange(0) }) {
                                            Text("Reset", color = AliflixAccentSecondary, fontSize = 11.sp)
                                        }
                                    }
                                    FilledTonalIconButton(
                                        onClick = { onSubtitleDelayChange(settings.subtitleDelayTenths + 1) },
                                        modifier = Modifier.size(38.dp),
                                        colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color.White.copy(alpha = 0.1f), contentColor = Color.White)
                                    ) {
                                        Text("+0.1", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                    }
                                }
                            }
                        }

                        // Subtitle Size Selection
                        Text("SUBTITLE SIZE", color = AliflixAccentSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(13f to "Small", 16f to "Normal", 19f to "Medium", 23f to "Large").forEach { (size, label) ->
                                FilterChip(
                                    selected = kotlin.math.abs(settings.subtitleFontSizeSp - size) < 1.5f,
                                    onClick = { onSubtitleFontSizeChange(size) },
                                    label = { Text(label, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AliflixAccentPrimaryContainer,
                                        selectedLabelColor = AliflixAccentSecondary,
                                        labelColor = Color.White
                                    )
                                )
                            }
                        }

                        // Subtitle Background Opacity
                        Text("BACKGROUND OPACITY", color = AliflixAccentSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.padding(top = 8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0f to "0%", 0.25f to "25%", 0.5f to "50%", 0.75f to "75%", 1f to "100%").forEach { (op, label) ->
                                FilterChip(
                                    selected = kotlin.math.abs(settings.subtitleBackgroundOpacity - op) < 0.1f,
                                    onClick = { onSubtitleOpacityChange(op) },
                                    label = { Text(label, fontSize = 12.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = AliflixAccentPrimaryContainer,
                                        selectedLabelColor = AliflixAccentSecondary,
                                        labelColor = Color.White
                                    )
                                )
                            }
                        }

                        // Subtitle Preview
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Black.copy(alpha = settings.subtitleBackgroundOpacity),
                            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text(
                                "Aliflix Subtitle Preview",
                                color = Color.White,
                                fontSize = settings.subtitleFontSizeSp.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }

                        Spacer(Modifier.height(4.dp))
                        Text("AUDIO", color = AliflixAccentSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        TrackOptions(player, C.TRACK_TYPE_AUDIO) { }

                        Spacer(Modifier.height(4.dp))
                        Text("SUBTITLES", color = AliflixAccentSecondary, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                        SheetOption(
                            "Off",
                            selected = player?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true && state.activeSubtitleTrack == null
                        ) {
                            player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
                            onSubtitleDisable()
                        }
                        TrackOptions(player, C.TRACK_TYPE_TEXT) { }

                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("More Subtitles", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            TextButton(onClick = onSubtitleSearch) { Text("Search", color = AliflixAccentSecondary) }
                        }
                        if (state.subtitleLoading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = AliflixAccentPrimary)
                        state.subtitleError?.let { Text(it, color = AliflixError, fontSize = 13.sp) }
                        if (!state.subtitleLoading && subtitleTracks.isEmpty()) {
                            Text("No external subtitles found for this title.", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                        var language by remember { mutableStateOf<String?>(null) }
                        val languages = remember(subtitleTracks) {
                            subtitleTracks.map { it.languageName }.distinct().sortedWith(
                                compareBy<String> { name ->
                                    if (name.equals("EN", ignoreCase = true) || name.equals("English", ignoreCase = true)) 0 else 1
                                }.thenBy { it }
                            )
                        }
                        if (languages.isNotEmpty()) {
                            val activeLanguage = language?.takeIf { l -> languages.any { it.equals(l, ignoreCase = true) } }
                                ?: state.activeSubtitleTrack?.let { normalizeMobileSubtitleTracks(listOf(it)).first().languageName }?.takeIf { l -> languages.any { it.equals(l, ignoreCase = true) } }
                                ?: languages.firstOrNull()
                            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                languages.forEach { name ->
                                    FilterChip(
                                        selected = activeLanguage?.equals(name, ignoreCase = true) == true,
                                        onClick = { language = name },
                                        label = { Text(name) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = AliflixAccentPrimaryContainer,
                                            selectedLabelColor = AliflixAccentSecondary,
                                            labelColor = Color.White
                                        )
                                    )
                                }
                            }
                            subtitleTracks.filter { it.languageName.equals(activeLanguage, ignoreCase = true) }.forEach { track ->
                                SheetOption(track.languageName, track.releaseName, selected = state.activeSubtitleTrack?.id == track.id) {
                                    player?.let { it.trackSelectionParameters = it.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).build() }
                                    onSubtitle(track)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenPlayerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null,
            enabled = enabled, role = Role.Button, onClick = onClick
        ), contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun SheetOption(
    title: String,
    subtitle: String = "",
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AliflixAccentPrimaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = if (selected) AliflixAccentSecondary else Color.White, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) {
            Icon(Icons.Default.Check, "Selected", tint = AliflixAccentSecondary, modifier = Modifier.size(20.dp))
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun TrackOptions(player: Player?, type: Int, onSelect: () -> Unit) {
    val groups = player?.currentTracks?.groups.orEmpty().filter { it.type == type }
    groups.forEach { group ->
        (0 until group.length).filter { group.isTrackSupported(it, true) }.forEach { index ->
            val format = group.getTrackFormat(index)
            val label = if (type == C.TRACK_TYPE_VIDEO) format.height.takeIf { it > 0 }?.let { "${it}p" } ?: "Original"
            else format.label ?: format.language?.let { Locale.forLanguageTag(it).displayLanguage }
            ?: if (type == C.TRACK_TYPE_AUDIO) "Original audio" else "Subtitles"
            SheetOption(label, selected = group.isTrackSelected(index)) {
                player?.let {
                    it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(type, false)
                        .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
                        .build()
                }
                onSelect()
            }
        }
    }
    if (groups.isEmpty() && type == C.TRACK_TYPE_AUDIO) {
        Text("Original audio", color = Color.White.copy(alpha = 0.6f), modifier = Modifier.padding(14.dp))
    }
}

internal fun playerTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(Locale.ROOT, seconds / 60, seconds % 60)
}
